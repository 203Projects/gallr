package com.gallr.shared.taste

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 101 FR-001: the sheet offers only terms the running catalogue can answer, and six featured covers. */
class TastePickChoicesTest {
    private val today = LocalDate(2026, 10, 26)

    private fun term(
        id: String,
        category: ArtTermCategory,
    ) = ArtTerm(id, category, id.substringAfter(':'), id.substringAfter(':'))

    private val painting = term("medium:painting", ArtTermCategory.MEDIUM)
    private val sculpture = term("medium:sculpture", ArtTermCategory.MEDIUM)
    private val photography = term("medium:photography", ArtTermCategory.MEDIUM)
    private val installation = term("medium:installation", ArtTermCategory.MEDIUM)
    private val minimal = term("style:minimalist", ArtTermCategory.STYLE)
    private val city = term("theme:city", ArtTermCategory.THEME)
    private val quiet = term("mood:quiet-meditative", ArtTermCategory.MOOD)

    @Test
    fun `only reviewed terms on at least three running or upcoming exhibitions are offered`() {
        val catalogue =
            carrying(painting, 5) + carrying(minimal, 4) + carrying(city, 3) + carrying(quiet, 3) +
                carrying(sculpture, 2) +
                carrying(photography, 3, closing = LocalDate(2026, 10, 1))

        val choices = tastePickChoices(catalogue, featuredInHomeOrder = emptyList(), today = today)

        assertEquals(listOf(painting, minimal, city, quiet), choices.terms)
    }

    @Test
    fun `ties fall back to category then vocabulary order and no category takes more than three`() {
        val catalogue =
            listOf(painting, sculpture, photography, installation, minimal, city).flatMap { carrying(it, 3) }

        val choices = tastePickChoices(catalogue, featuredInHomeOrder = emptyList(), today = today)

        assertEquals(listOf(painting, sculpture, photography, minimal, city), choices.terms)
    }

    @Test
    fun `fewer than four qualifying terms offers no term chips`() {
        val catalogue = carrying(painting, 5) + carrying(minimal, 4) + carrying(city, 3)

        assertTrue(tastePickChoices(catalogue, featuredInHomeOrder = emptyList(), today = today).terms.isEmpty())
    }

    @Test
    fun `at most ten terms are offered`() {
        val many = (1..14).map { term("theme:t$it", ArtTermCategory.values()[it % 4]) }
        val catalogue = many.flatMap { carrying(it, 3) }

        assertEquals(10, tastePickChoices(catalogue, featuredInHomeOrder = emptyList(), today = today).terms.size)
    }

    @Test
    fun `covers are the first six featured exhibitions still running or upcoming`() {
        val featured =
            (1..8).map {
                exhibition(
                    "featured-$it",
                    closing =
                        if (it ==
                            2
                        ) {
                            LocalDate(2026, 10, 1)
                        } else {
                            LocalDate(2026, 12, 1)
                        },
                )
            }

        val choices = tastePickChoices(featured, featuredInHomeOrder = featured, today = today)

        assertEquals(listOf(1, 3, 4, 5, 6, 7).map { "featured-$it" }, choices.covers.map(Exhibition::id))
    }

    @Test
    fun `picked ids resolve to catalogue labels first then the vocabulary and unknown ids drop`() {
        val renamed = ArtTerm("theme:city", ArtTermCategory.THEME, "도시 풍경", "Cityscape")
        val catalogue = carrying(renamed, 1)

        val resolved = resolveArtTerms(listOf("theme:city", "medium:painting", "theme:nowhere"), catalogue)

        assertEquals(listOf("medium:painting", "theme:city"), resolved.map(ArtTerm::id))
        assertEquals("도시 풍경", resolved.last().nameKo)
    }

    private var serial = 0

    private fun carrying(
        term: ArtTerm,
        count: Int,
        closing: LocalDate = LocalDate(2026, 12, 1),
    ): List<Exhibition> = (1..count).map { exhibition("ex-${serial++}", closing = closing, artTerms = listOf(term)) }

    private fun exhibition(
        id: String,
        closing: LocalDate,
        artTerms: List<ArtTerm> = emptyList(),
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 9, 1),
        closingDate = closing,
        isFeatured = true,
        latitude = 37.57,
        longitude = 126.98,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        artTerms = artTerms,
    )
}
