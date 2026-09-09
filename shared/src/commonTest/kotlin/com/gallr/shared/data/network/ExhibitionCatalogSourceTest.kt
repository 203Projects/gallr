package com.gallr.shared.data.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExhibitionCatalogSourceTest {
    @Test
    fun `missing configuration defaults to the legacy rollback source`() {
        assertEquals(ExhibitionCatalogSource.LEGACY, ExhibitionCatalogSource.fromConfig())
        assertEquals(ExhibitionCatalogSource.LEGACY, ExhibitionCatalogSource.fromConfig(""))
        assertEquals(ExhibitionCatalogSource.LEGACY, ExhibitionCatalogSource.fromConfig("   "))
        assertEquals(ExhibitionCatalogSource.LEGACY, ExhibitionCatalogSource.fromConfig("legacy"))
    }

    @Test
    fun `canonical v2 configuration owns its table and integrity RPC pair`() {
        val source = ExhibitionCatalogSource.fromConfig("canonical-v2")
        val restBase = "https://example.supabase.co/rest/v1"
        val pageUrl =
            buildExhibitionPageUrl(
                restBase = restBase,
                request = ExhibitionPageRequest(),
                source = source,
            )
        val integrityUrl =
            buildExhibitionIntegrityUrl(
                restBase = restBase,
                source = source,
            )

        assertEquals(ExhibitionCatalogSource.CANONICAL_V2, source)
        assertTrue(pageUrl.startsWith("$restBase/exhibition_catalog_v2?"))
        assertTrue(
            integrityUrl.startsWith("$restBase/rpc/exhibition_catalog_v2_integrity"),
        )
        assertTrue(source.requiresContentIntegrity)
        assertTrue("ticket_url" in source.selectColumns)
        assertTrue("gallery_id" in source.selectColumns)
        assertTrue("artists" in source.selectColumns)
        assertTrue("art_terms" in source.selectColumns)
        assertTrue(source.selectColumns.endsWith(",content_checksum_sha256"))
    }

    @Test
    fun `legacy source retains its table and two-field integrity RPC pair`() {
        val source = ExhibitionCatalogSource.LEGACY
        val restBase = "https://example.supabase.co/rest/v1"

        assertTrue(
            buildExhibitionPageUrl(restBase, ExhibitionPageRequest(), source)
                .startsWith("$restBase/exhibitions?"),
        )
        assertTrue(
            buildExhibitionIntegrityUrl(restBase, source = source)
                .startsWith("$restBase/rpc/exhibition_reader_integrity"),
        )
        assertEquals(false, source.requiresContentIntegrity)
        assertTrue("ticket_url" in source.selectColumns)
        assertTrue("gallery_id" !in source.selectColumns)
        assertTrue("artists" !in source.selectColumns)
        assertTrue("art_terms" !in source.selectColumns)
        assertTrue("content_checksum_sha256" !in source.selectColumns)
    }

    @Test
    fun `pre migration catalog retries once without country code`() =
        runTest {
            val requestedSelections = mutableListOf<Boolean>()
            val rollout = CatalogCountryCodeRollout()

            val result =
                rollout.fetch(
                    request = { includesCountryCode ->
                        requestedSelections += includesCountryCode
                        if (includesCountryCode) throw MissingCountryCodeColumnForTest
                        "legacy rows"
                    },
                    isMissingCountryCodeColumn = { it === MissingCountryCodeColumnForTest },
                )

            assertEquals("legacy rows", result)
            assertEquals(listOf(true, false), requestedSelections)
            assertTrue("country_code" in ExhibitionCatalogSource.LEGACY.selectColumns(includeCountryCode = true))
            assertTrue("country_code" !in ExhibitionCatalogSource.LEGACY.selectColumns(includeCountryCode = false))
        }

    @Test
    fun `unknown or path-like source configuration fails closed`() {
        listOf("canonical", "CANONICAL-V2", "../../content/exhibition_versions").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) {
                ExhibitionCatalogSource.fromConfig(value)
            }
        }
    }
}

private data object MissingCountryCodeColumnForTest : RuntimeException()
