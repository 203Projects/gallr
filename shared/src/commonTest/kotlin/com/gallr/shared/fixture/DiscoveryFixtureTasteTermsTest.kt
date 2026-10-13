package com.gallr.shared.fixture

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.taste.effectiveArtTerms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Term detection against the published catalogue snapshot: most shows get a tag, and the spot checks read right. */
class DiscoveryFixtureTasteTermsTest {
    private val catalogue = DiscoveryFixture.exhibitions

    @Test
    fun `most published exhibitions receive at least one taxonomy term from their own text`() {
        val tagged = catalogue.count { it.effectiveArtTerms().isNotEmpty() }
        assertTrue(tagged * 100 / catalogue.size >= 70, "tagged $tagged of ${catalogue.size}")
    }

    @Test
    fun `spot checks match how a visitor would describe the show`() {
        assertEquals(listOf("medium:painting", "mood:monumental"), termIds("손끝에서"))
        assertTrue("medium:installation" in termIds("🏛️ 서도호"), termIds("🏛️ 서도호").toString())
        assertTrue("medium:photography" in termIds("We Are Martin Parr"), termIds("We Are Martin Parr").toString())
    }

    private fun termIds(namePrefix: String): List<String> =
        catalogue
            .first { it.nameKo.startsWith(namePrefix) || it.nameEn.contains(namePrefix) }
            .effectiveArtTerms()
            .map(ArtTerm::id)
}
