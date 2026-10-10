package com.gallr.shared.home

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.galleryKey
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HomeFeedBuilderTest {
    private val today = LocalDate(2026, 10, 10)

    @Test
    fun `the hero takes the featured list in its order up to six`() {
        val featured = (1..8).map { exhibition("f$it", isFeatured = true) }

        val feed = buildHomeFeed(exhibitions = featured, featured = featured, today = today)

        assertEquals(featured.take(6).map { it.id }, feed.hero.map { it.id })
    }

    @Test
    fun `editor picks leave out what the hero already shows`() {
        val heroPick = exhibition("hero", isFeatured = true, editorId = Editor.HOUSE_EDITOR_ID)
        val picks = (1..12).map { exhibition("pick$it", editorId = Editor.HOUSE_EDITOR_ID) }
        val other = exhibition("other", editorId = "guest-editor")

        val feed =
            buildHomeFeed(exhibitions = listOf(heroPick, other) + picks, featured = listOf(heroPick), today = today)

        assertEquals(picks.take(10).map { it.id }, feed.editorPicks.map { it.id })
    }

    @Test
    fun `followed gallery exhibitions match by id or name and list newest first while running`() {
        val byId = exhibition("by-id", galleryId = "gallery-1", opening = today.minusDays(3))
        val byName =
            exhibition("by-name", venueKo = "갤러리 현대", venueEn = "Gallery Hyundai", opening = today.minusDays(1))
        val ended =
            exhibition("ended", galleryId = "gallery-1", opening = today.minusDays(40), closing = today.minusDays(1))
        val elsewhere = exhibition("elsewhere", venueKo = "다른 곳")
        val followed =
            listOf(
                followedGallery("k1", galleryId = "gallery-1"),
                followedGallery(galleryKey("갤러리 현대", "Gallery Hyundai"), nameKo = "갤러리 현대", nameEn = "Gallery Hyundai"),
            )

        val feed =
            buildHomeFeed(
                exhibitions = listOf(byId, byName, ended, elsewhere),
                featured = emptyList(),
                today = today,
                followedGalleries = followed,
            )

        assertEquals(listOf("by-name", "by-id"), feed.fromFollowedGalleries.map { it.id })
    }

    @Test
    fun `nobody followed means no gallery rail`() {
        val feed = buildHomeFeed(exhibitions = listOf(exhibition("a")), featured = emptyList(), today = today)

        assertTrue(feed.fromFollowedGalleries.isEmpty())
    }

    @Test
    fun `timing collections cover this week and need at least two exhibitions`() {
        val closingSoon = (1..3).map { exhibition("c$it", closing = today.plusDays(it)) }
        val closingLater = exhibition("later", closing = today.plusDays(20))
        val openingSoon = exhibition("o1", opening = today.plusDays(2), closing = today.plusDays(30))

        val feed =
            buildHomeFeed(
                exhibitions = closingSoon + closingLater + openingSoon,
                featured = emptyList(),
                today = today,
            )

        val closing = feed.collections.single { it.kind == HomeCollectionKind.CLOSING_THIS_WEEK }
        assertEquals(listOf("c1", "c2", "c3"), closing.exhibitions.map { it.id }, "soonest closing first")
        assertEquals("이번 주 마감", closing.localizedTitle(AppLanguage.KO))
        assertEquals("CLOSING THIS WEEK", closing.localizedTitle(AppLanguage.EN))
        assertNull(
            feed.collections.find { it.kind == HomeCollectionKind.OPENING_THIS_WEEK },
            "one opening is not a collection",
        )
    }

    @Test
    fun `neighbourhood collections take the two busiest districts with at least three shows`() {
        val hannam = (1..4).map { exhibition("h$it", regionKo = "한남동", regionEn = "Hannam-dong") }
        val seongsu = (1..3).map { exhibition("s$it", regionKo = "성수동", regionEn = "Seongsu-dong") }
        val samcheong = (1..3).map { exhibition("sc$it", regionKo = "삼청동", regionEn = "Samcheong-dong") }
        val lone = exhibition("lone", regionKo = "청담동")

        val feed =
            buildHomeFeed(exhibitions = hannam + samcheong + seongsu + lone, featured = emptyList(), today = today)

        val neighbourhoods = feed.collections.filter { it.kind == HomeCollectionKind.NEIGHBORHOOD }
        assertEquals(listOf("한남동", "삼청동"), neighbourhoods.map { it.localizedTitle(AppLanguage.KO) }, "count, then name")
        assertEquals("Hannam-dong", neighbourhoods.first().localizedTitle(AppLanguage.EN))
        assertEquals(4, neighbourhoods.first().count)
    }

    @Test
    fun `theme collections skip terms that describe a third of the catalogue or more`() {
        val painting = ArtTerm("painting", ArtTermCategory.MEDIUM, "회화", "Painting")
        val identity = ArtTerm("identity", ArtTermCategory.THEME, "정체성", "Identity")
        val everywhere = (1..9).map { exhibition("p$it", artTerms = listOf(painting)) }
        val identityShows = (1..3).map { exhibition("i$it", artTerms = listOf(identity)) }

        val feed = buildHomeFeed(exhibitions = everywhere + identityShows, featured = emptyList(), today = today)

        val themes = feed.collections.filter { it.kind == HomeCollectionKind.THEME }
        assertEquals(listOf("정체성"), themes.map { it.localizedTitle(AppLanguage.KO) })
        assertEquals("identity", themes.single().key)
        assertEquals(listOf("i1", "i2", "i3"), themes.single().exhibitions.map { it.id })
    }

    @Test
    fun `a collection's concept image prefers a featured cover`() {
        val plain = exhibition("plain", closing = today.plusDays(1), cover = "https://img/plain.jpg")
        val featured =
            exhibition("feat", closing = today.plusDays(2), cover = "https://img/feat.jpg", isFeatured = true)
        val noCover = exhibition("none", closing = today.plusDays(3), cover = null)

        val feed = buildHomeFeed(exhibitions = listOf(noCover, plain, featured), featured = emptyList(), today = today)

        assertEquals("https://img/feat.jpg", feed.collections.single().coverImageUrl)
    }

    @Test
    fun `collections are ordered timing then neighbourhoods then themes and capped at six`() {
        val identity = ArtTerm("identity", ArtTermCategory.THEME, "정체성", "Identity")
        val nature = ArtTerm("nature", ArtTermCategory.THEME, "자연", "Nature")
        val city = ArtTerm("city", ArtTermCategory.THEME, "도시", "City")
        val shows =
            (1..3).map { exhibition("c$it", closing = today.plusDays(it)) } +
                (1..3).map { exhibition("o$it", opening = today.plusDays(it), closing = today.plusDays(40)) } +
                (1..3).map { exhibition("h$it", regionKo = "한남동") } +
                (1..3).map { exhibition("s$it", regionKo = "성수동") } +
                (1..3).map { exhibition("sc$it", regionKo = "삼청동") } +
                (1..3).map { exhibition("i$it", artTerms = listOf(identity)) } +
                (1..3).map { exhibition("n$it", artTerms = listOf(nature)) } +
                (1..3).map { exhibition("ci$it", artTerms = listOf(city)) }

        val feed = buildHomeFeed(exhibitions = shows, featured = emptyList(), today = today)

        assertEquals(6, feed.collections.size)
        assertEquals(
            listOf(
                HomeCollectionKind.CLOSING_THIS_WEEK,
                HomeCollectionKind.OPENING_THIS_WEEK,
                HomeCollectionKind.NEIGHBORHOOD,
                HomeCollectionKind.NEIGHBORHOOD,
                HomeCollectionKind.THEME,
                HomeCollectionKind.THEME,
            ),
            feed.collections.map { it.kind },
        )
        assertEquals(
            feed.collections.size,
            feed.collections
                .map { it.key }
                .distinct()
                .size,
            "keys are unique",
        )
    }

    @Test
    fun `an empty catalogue is an empty feed`() {
        val feed = buildHomeFeed(exhibitions = emptyList(), featured = emptyList(), today = today)

        assertTrue(feed.isEmpty)
    }

    private fun LocalDate.plusDays(days: Int): LocalDate = LocalDate.fromEpochDays(toEpochDays() + days)

    private fun LocalDate.minusDays(days: Int): LocalDate = plusDays(-days)

    private fun followedGallery(
        key: String,
        galleryId: String? = null,
        nameKo: String = "갤러리",
        nameEn: String = "Gallery",
    ) = FollowedGallery(
        galleryKey = key,
        snapshot = FollowedGallerySnapshot(nameKo, nameEn, "서울", "Seoul", "한남동", "Hannam-dong"),
        knownExhibitionIds = emptySet(),
        followedAt = Instant.fromEpochMilliseconds(0),
        galleryId = galleryId,
    )

    private fun exhibition(
        id: String,
        isFeatured: Boolean = false,
        editorId: String? = null,
        galleryId: String? = null,
        venueKo: String = "장소 $id",
        venueEn: String = "Venue $id",
        regionKo: String = "",
        regionEn: String = "",
        opening: LocalDate = today.minusDays(10),
        closing: LocalDate = today.plusDays(30),
        cover: String? = "https://img/$id.jpg",
        artTerms: List<ArtTerm> = emptyList(),
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Show $id",
        venueNameKo = venueKo,
        venueNameEn = venueEn,
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = regionKo,
        regionEn = regionEn,
        openingDate = opening,
        closingDate = closing,
        isFeatured = isFeatured,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = cover,
        editorId = editorId,
        galleryId = galleryId,
        artTerms = artTerms,
    )
}
