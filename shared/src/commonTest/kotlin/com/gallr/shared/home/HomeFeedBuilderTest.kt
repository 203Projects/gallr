package com.gallr.shared.home

import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.VisitTag
import com.gallr.shared.data.model.galleryKey
import com.gallr.shared.route.PublicRouteSummary
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HomeFeedBuilderTest {
    private val today = LocalDate(2026, 10, 10)

    @Test
    fun `curator notes become cards only for exhibitions in the feed`() {
        val shown = exhibition("shown")
        val note = { id: String, day: Int ->
            CuratorNote(
                exhibitionId = id,
                versionNumber = 1,
                hookKo = "훅",
                hookEn = "",
                insightsKo = listOf("하나", "둘"),
                insightsEn = emptyList(),
                tipKo = "팁",
                tipEn = "",
                approvedAt = Instant.parse("2026-10-0${day}T03:00:00Z"),
            )
        }

        val feed =
            buildHomeFeed(
                exhibitions = listOf(shown),
                featured = emptyList(),
                today = today,
                curatorNotes = listOf(note("elsewhere", 2), note("shown", 1)),
            )

        assertEquals(listOf("shown"), feed.curatorEye.map { it.exhibition.id })
        val withoutNotes = buildHomeFeed(exhibitions = listOf(shown), featured = emptyList(), today = today)
        assertTrue(withoutNotes.curatorEye.isEmpty())
    }

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
    fun `collections that share exhibitions borrow different covers`() {
        val identity = ArtTerm("identity", ArtTermCategory.THEME, "정체성", "Identity")
        val nature = ArtTerm("nature", ArtTermCategory.THEME, "자연", "Nature")
        val shared =
            exhibition(
                "shared",
                cover = "https://img/shared.jpg",
                isFeatured = true,
                artTerms = listOf(identity, nature),
            )
        val identityOnly =
            (1..2).map {
                exhibition(
                    "i$it",
                    cover = "https://img/i$it.jpg",
                    artTerms = listOf(identity),
                )
            }
        val natureOnly = (1..2).map { exhibition("n$it", cover = "https://img/n$it.jpg", artTerms = listOf(nature)) }

        val feed =
            buildHomeFeed(
                exhibitions = listOf(shared) + identityOnly + natureOnly,
                featured = emptyList(),
                today = today,
            )

        val covers = feed.collections.map { it.coverImageUrl }
        assertEquals(2, covers.size)
        assertEquals("https://img/shared.jpg", covers.first(), "the first theme keeps the featured cover")
        assertEquals(covers.size, covers.distinct().size, "the second theme takes a cover of its own")
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

    @Test
    fun `the routes rail keeps the ranking order up to three and hides without rows`() {
        val ranked = (1..4).map { publicRoute("r$it") }

        val feed =
            buildHomeFeed(exhibitions = listOf(exhibition("a")), featured = emptyList(), today = today, routes = ranked)
        val withoutRoutes = buildHomeFeed(exhibitions = listOf(exhibition("a")), featured = emptyList(), today = today)

        assertEquals(listOf("r1", "r2", "r3"), feed.routes.map { it.summary.id })
        assertTrue(withoutRoutes.routes.isEmpty())
    }

    @Test
    fun `a route card borrows its stops' covers in stop order`() {
        val first = exhibition("first").copy(coverImageUrl = "https://img/first.jpg")
        val bare = exhibition("bare").copy(coverImageUrl = null)
        val second = exhibition("second").copy(coverImageUrl = "https://img/second.jpg")

        val feed =
            buildHomeFeed(
                exhibitions = listOf(second, bare, first),
                featured = emptyList(),
                today = today,
                routes = listOf(publicRoute("r1"), publicRoute("r2")),
                routeStops = mapOf("r1" to listOf("first", "gone", "bare", "second", "first")),
            )

        // Stops that left the catalogue or have no cover are skipped; a repeated cover shows once.
        assertEquals(listOf("https://img/first.jpg", "https://img/second.jpg"), feed.routes[0].stopCoverUrls)
        // A route whose stops could not be read still appears, without covers.
        assertTrue(feed.routes[1].stopCoverUrls.isEmpty())
    }

    @Test
    fun `most viewed keeps the server's order and needs three shown exhibitions`() {
        val shown = listOf(exhibition("a"), exhibition("b"), exhibition("c"), exhibition("d"))

        val feed =
            buildHomeFeed(
                exhibitions = shown,
                featured = emptyList(),
                today = today,
                popularExhibitionIds = listOf("c", "ended", "a", "c", "d"),
            )
        val tooFew =
            buildHomeFeed(
                exhibitions = shown,
                featured = emptyList(),
                today = today,
                popularExhibitionIds = listOf("c", "ended", "a"),
            )

        val mostViewed = feed.collections.first()
        assertEquals(HomeCollectionKind.MOST_VIEWED, mostViewed.kind)
        assertEquals(listOf("c", "a", "d"), mostViewed.exhibitions.map { it.id })
        assertTrue(tooFew.collections.none { it.kind == HomeCollectionKind.MOST_VIEWED })
    }

    @Test
    fun `a situation gathers the exhibitions whose terms answer it and needs three`() {
        val quiet = ArtTerm("mood:quiet-meditative", ArtTermCategory.MOOD, "고요함·명상적", "Quiet / meditative")
        val minimal = ArtTerm("style:minimalist", ArtTermCategory.STYLE, "미니멀", "Minimalist")
        val intimate = ArtTerm("mood:intimate", ArtTermCategory.MOOD, "친밀함", "Intimate")
        val shows =
            listOf(
                exhibition("q1", artTerms = listOf(quiet)),
                exhibition("plain"),
                exhibition("q2", artTerms = listOf(minimal)),
                exhibition("q3", artTerms = listOf(quiet, minimal)),
                exhibition("i1", artTerms = listOf(intimate)),
                exhibition("i2", artTerms = listOf(intimate)),
            )

        val feed = buildHomeFeed(exhibitions = shows, featured = emptyList(), today = today)

        val situations = feed.collections.filter { it.kind == HomeCollectionKind.SITUATION }
        // Two intimate shows are not enough for "for two"; the quiet visit has three, in catalogue order.
        assertEquals(listOf("situation:quiet"), situations.map { it.key })
        assertEquals(listOf("q1", "q2", "q3"), situations.single().exhibitions.map { it.id })
        assertEquals("조용히 보고 싶은 날", situations.single().localizedTitle(AppLanguage.KO))
        assertEquals(listOf("고요함", "미니멀"), situations.single().localizedTags(AppLanguage.KO))
        assertEquals(listOf("QUIET", "MINIMAL"), situations.single().localizedTags(AppLanguage.EN))
    }

    @Test
    fun `a term that a shown situation already answers is not also a theme tile`() {
        val quiet = ArtTerm("mood:quiet-meditative", ArtTermCategory.MOOD, "고요함·명상적", "Quiet / meditative")
        val identity = ArtTerm("identity", ArtTermCategory.THEME, "정체성", "Identity")
        val quietShows = (1..3).map { exhibition("q$it", artTerms = listOf(quiet)) }
        val identityShows = (1..3).map { exhibition("i$it", artTerms = listOf(identity)) }
        val others = (1..6).map { exhibition("o$it") }

        val shows = quietShows + identityShows + others

        val feed = buildHomeFeed(exhibitions = shows, featured = emptyList(), today = today)

        assertEquals(
            listOf("situation:quiet", "identity"),
            feed.collections.filter { it.kind != HomeCollectionKind.NEIGHBORHOOD }.map { it.key },
        )
    }

    @Test
    fun `situations come in a fixed order and only an editor's tag says a show suits children`() {
        assertEquals(
            listOf(
                "situation:quiet",
                "situation:photo",
                "situation:together",
                "situation:family",
                "situation:lift",
                "situation:strange",
            ),
            HOME_SITUATIONS.map { it.key },
        )
        // Suitability for children is an editor's call, never inferred from a mood word.
        val aboutFamilies = HOME_SITUATIONS.filter { "아이" in it.titleKo || "가족" in it.titleKo }
        assertEquals(listOf("situation:family"), aboutFamilies.map { it.key })
        assertTrue(aboutFamilies.single().termIds.isEmpty())
        assertEquals(setOf(VisitTag.FAMILY), aboutFamilies.single().visitTags)
    }

    @Test
    fun `the family collection gathers only what an editor tagged`() {
        val playful = ArtTerm("mood:playful", ArtTermCategory.MOOD, "유희적", "Playful")
        val tagged = (1..3).map { exhibition("f$it").copy(visitTags = setOf(VisitTag.FAMILY)) }
        val playfulOnly = (1..3).map { exhibition("p$it", artTerms = listOf(playful)) }

        val feed = buildHomeFeed(exhibitions = playfulOnly + tagged, featured = emptyList(), today = today)

        val family = feed.collections.single { it.key == "situation:family" }
        assertEquals(listOf("f1", "f2", "f3"), family.exhibitions.map { it.id })
        assertEquals("아이·가족과 함께", family.localizedTitle(AppLanguage.KO))
    }

    @Test
    fun `free admission is a curation of what editors tagged free and needs two`() {
        val free = (1..2).map { exhibition("free$it").copy(visitTags = setOf(VisitTag.FREE_ADMISSION)) }
        val others = (1..3).map { exhibition("o$it") }

        val feed = buildHomeFeed(exhibitions = others + free, featured = emptyList(), today = today)
        val onlyOne = buildHomeFeed(exhibitions = others + free.take(1), featured = emptyList(), today = today)

        val tile = feed.collections.single { it.kind == HomeCollectionKind.FREE_ADMISSION }
        assertEquals(listOf("free1", "free2"), tile.exhibitions.map { it.id })
        assertEquals("무료 관람", tile.localizedTitle(AppLanguage.KO))
        assertEquals("FREE ADMISSION", tile.localizedTitle(AppLanguage.EN))
        assertTrue(onlyOne.collections.none { it.kind == HomeCollectionKind.FREE_ADMISSION })
    }

    private fun publicRoute(id: String) =
        PublicRouteSummary(
            id = id,
            name = "동선 $id",
            stopCount = 3,
            firstDistrictKo = "종로구",
            firstDistrictEn = "Jongno-gu",
            lastDistrictKo = "용산구",
            lastDistrictEn = "Yongsan-gu",
            authorDisplayName = "작가",
            isEditor = false,
            copyCount30d = 4,
            firstSharedDay = today,
            approvedAt = Instant.parse("2026-10-09T00:00:00Z"),
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
