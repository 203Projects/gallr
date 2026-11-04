package com.gallr.shared.taste

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtTermDetectorTest {
    @Test
    fun `korean and english synonyms map to the catalogue taxonomy`() {
        val detected =
            detectArtTerms(
                nameKo = "손끝에서",
                nameEn = "At the Fingertips",
                descriptionKo = "대형 캔버스에 그린 회화 연작과 드로잉을 선보인다. 기억과 풍경을 다룬다.",
                descriptionEn = "",
            )

        assertEquals(
            listOf(
                "medium:painting",
                "medium:drawing",
                "theme:memory",
                "theme:nature",
                "theme:landscape",
                "mood:monumental",
            ),
            detected.map(ArtTerm::id),
        )
        assertEquals(listOf("회화", "드로잉", "기억", "자연", "풍경", "기념비적"), detected.map(ArtTerm::nameKo))

        val english =
            detectArtTerms(
                nameKo = "",
                nameEn = "Quiet Rooms",
                descriptionKo = "",
                descriptionEn = "Site-specific installations and single-channel video explore identity.",
            )
        assertEquals(
            listOf("medium:installation", "medium:video", "theme:identity", "mood:quiet-meditative"),
            english.map(ArtTerm::id),
        )
    }

    @Test
    fun `english matches whole words only and matching ignores case`() {
        val none = detectArtTerms("", "", "", "The display replays a replayed scene.")
        assertEquals(emptyList(), none.map(ArtTerm::id))

        val upper = detectArtTerms("", "", "", "PAINTINGS AND SCULPTURES.")
        assertEquals(listOf("medium:painting", "medium:sculpture"), upper.map(ArtTerm::id))
    }

    @Test
    fun `editor terms lead without duplicates and each category stays within its cap`() {
        val editorTerm = ArtTerm("mood:playful", ArtTermCategory.MOOD, "유희적", "Playful")
        val exhibition =
            exhibition(
                descriptionKo =
                    "회화, 조각, 사진전, 설치 작품, 비디오, 디지털, 퍼포먼스, 드로잉, 판화, 공예가 유희적으로 뒤섞인다.",
                artTerms = listOf(editorTerm, ArtTerm("medium:craft", ArtTermCategory.MEDIUM, "공예", "Craft")),
            )

        val terms = exhibition.effectiveArtTerms()

        assertEquals(listOf("mood:playful", "medium:craft"), terms.take(2).map(ArtTerm::id))
        assertEquals(terms.size, terms.distinctBy(ArtTerm::id).size)
        assertTrue(terms.count { it.category == ArtTermCategory.MEDIUM } <= 6, terms.toString())
        assertEquals(1, terms.count { it.category == ArtTermCategory.MOOD })
    }

    @Test
    fun `the expansion terms are detected from korean substrings and english whole words`() {
        val cases =
            mapOf(
                "medium:ceramics" to ("백자와 도예 작업" to "porcelain vessels"),
                "medium:textile" to ("섬유와 직물로 짠 화면" to "woven textile works"),
                "medium:sound" to ("사운드 설치" to "a sound installation"),
                "medium:media-art" to ("미디어아트 신작" to "new media art"),
                "medium:architecture-design" to ("건축가의 드로잉" to "architecture and its models"),
                "style:dansaekhwa" to ("단색화의 계보" to "a history of dansaekhwa"),
                "style:minhwa" to ("조선 민화" to "joseon minhwa"),
                "style:korean-painting" to ("수묵 한국화" to "contemporary ink painting"),
                "style:pop" to ("팝아트의 색" to "pop art colours"),
                "style:surreal" to ("초현실적 장면" to "surrealist scenes"),
                "theme:body" to ("신체의 감각" to "bodily sensation"),
                "theme:time" to ("시간성에 대한 탐구" to "on temporality"),
                "theme:ecology" to ("기후 위기" to "the climate crisis"),
                "theme:labor" to ("노동의 풍경" to "domestic labour"),
                "theme:gender" to ("젠더와 페미니즘" to "feminist histories"),
                "theme:archive" to ("아카이브 자료" to "archival documents"),
                "theme:landscape" to ("산수화" to "mountain landscapes"),
                "mood:humorous" to ("해학적인 장면" to "a humorous turn"),
            )

        for ((id, texts) in cases) {
            val (korean, english) = texts
            assertTrue(id in detectArtTerms(korean, "", "", "").map(ArtTerm::id), "$id from '$korean'")
            assertTrue(id in detectArtTerms("", english, "", "").map(ArtTerm::id), "$id from '$english'")
        }
    }

    @Test
    fun `staff-only terms are never inferred yet resolve to their labels`() {
        val detected =
            detectArtTerms(
                nameKo = "집으로 가는 길",
                nameEn = "Dark and Warm",
                descriptionKo = "어두운 방, 따뜻한 빛, 쓸쓸한 오후. 우리 집 이야기.",
                descriptionEn = "A dark room, warm light and a melancholy afternoon at home.",
            ).map(ArtTerm::id)

        for (id in listOf("theme:home", "mood:dark", "mood:warm", "mood:melancholy")) {
            assertTrue(id !in detected, "$id was inferred from text: $detected")
        }
        assertEquals(
            listOf("집", "어두운", "따뜻한", "쓸쓸한"),
            resolveArtTerms(listOf("theme:home", "mood:dark", "mood:warm", "mood:melancholy"), emptyList())
                .map(ArtTerm::nameKo),
        )
    }

    @Test
    fun `words that usually describe the visit or the credits do not tag a show`() {
        val detected =
            detectArtTerms(
                nameKo = "전시",
                nameEn = "Exhibition",
                descriptionKo = "관람 시간은 오전 10시부터입니다. 전시 디자인과 그래픽 디자인, 기록 사진, 여성 작가 12인.",
                descriptionEn =
                    "A body of work shown for the first time. Exhibition design by the studio. Opening time 10am.",
            ).map(ArtTerm::id)

        for (id in listOf("theme:time", "medium:architecture-design", "theme:body", "theme:archive", "theme:gender")) {
            assertTrue(id !in detected, "$id was inferred: $detected")
        }
    }

    @Test
    fun `blank text yields nothing`() {
        assertEquals(emptyList(), detectArtTerms("", "", null, null))
        assertEquals(emptyList(), exhibition(descriptionKo = "").effectiveArtTerms())
    }

    private fun exhibition(
        descriptionKo: String,
        artTerms: List<ArtTerm> = emptyList(),
    ) = Exhibition(
        id = "one",
        nameKo = "전시",
        nameEn = "Exhibition",
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 9, 30),
        isFeatured = false,
        latitude = 37.57,
        longitude = 126.98,
        descriptionKo = descriptionKo,
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        artTerms = artTerms,
    )
}
