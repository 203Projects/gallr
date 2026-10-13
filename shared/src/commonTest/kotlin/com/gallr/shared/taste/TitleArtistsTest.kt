package com.gallr.shared.taste

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleArtistsTest {
    @Test
    fun `the segment after the pipe names artists paired across languages by position`() {
        val single =
            titleArtists(nameKo = "손끝에서 | 게오르그 바젤리츠", nameEn = "At the Fingertips | Georg Baselitz")
        val baselitz = TitleArtist("게오르그 바젤리츠", "Georg Baselitz", setOf("게오르그 바젤리츠", "georg baselitz"))
        assertEquals(listOf(baselitz), single)

        val duo =
            titleArtists(
                nameKo = "연결의 형태 | 유정민, 정해윤",
                nameEn = "Forms of Connection | Yoo Jungmin, Jung Haeyoon",
            )
        assertEquals(listOf("유정민", "정해윤"), duo.map(TitleArtist::nameKo))
        assertEquals(listOf("Yoo Jungmin", "Jung Haeyoon"), duo.map(TitleArtist::nameEn))
        assertEquals(setOf("정해윤", "jung haeyoon"), duo[1].keys)
    }

    @Test
    fun `unequal lists stay unpaired and a title without a pipe names nobody`() {
        val unpaired = titleArtists(nameKo = "전시 | 김하나, 이둘", nameEn = "Show | Kim Hana")
        assertEquals(listOf("김하나", "이둘", ""), unpaired.map(TitleArtist::nameKo))
        assertEquals(listOf("", "", "Kim Hana"), unpaired.map(TitleArtist::nameEn))

        assertEquals(emptyList(), titleArtists(nameKo = "🏛️ 게오르그 바젤리츠", nameEn = "🏛️ Georg Baselitz"))
        assertEquals(emptyList(), titleArtists(nameKo = "", nameEn = ""))
    }

    @Test
    fun `a known artist title or an artist prefix before a colon names that artist`() {
        val known = setOf("게오르그 바젤리츠", "georg baselitz", "곽훈")

        val museum =
            titleAsKnownArtist(nameKo = "🏛️ 게오르그 바젤리츠", nameEn = "🏛️ Georg Baselitz ", knownKeys = known)
        val baselitz = TitleArtist("게오르그 바젤리츠", "Georg Baselitz", setOf("게오르그 바젤리츠", "georg baselitz"))
        assertEquals(baselitz, museum)

        val recent = titleAsKnownArtist(nameKo = "곽훈: 근작", nameEn = "Hoon Kwak: Recent Works ", knownKeys = known)
        assertEquals(TitleArtist("곽훈", "Hoon Kwak", setOf("곽훈", "hoon kwak")), recent)

        val plainTitle =
            titleAsKnownArtist(
                nameKo = "🏛 이것은 개념미술이 (아니)다",
                nameEn = "🏛 This is (Not) Conceptual Art",
                knownKeys = known,
            )
        assertNull(plainTitle)
        assertNull(titleAsKnownArtist(nameKo = "손끝에서 | 게오르그 바젤리츠", nameEn = "", knownKeys = known))
    }
}
