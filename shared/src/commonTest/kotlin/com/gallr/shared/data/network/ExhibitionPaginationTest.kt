package com.gallr.shared.data.network

import com.gallr.shared.data.network.dto.ExhibitionDto
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExhibitionPaginationTest {

    @Test
    fun `loads all 1205 rows and requests an empty terminal page`() = runTest {
        val rows = (0 until 1_205).map { index -> dto(id = id(index)) }
        val requests = mutableListOf<ExhibitionPageRequest>()

        val result = fetchAllExhibitionDtos { request ->
            requests += request
            pageAfter(rows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
        }

        assertEquals(1_205, result.size)
        assertEquals(listOf(null, id(499), id(999), id(1_204)), requests.map { it.cursorExclusive })
        assertEquals(rows.map { it.id }, result.map { it.id })
    }

    @Test
    fun `loads complete collections at 999 1000 and 1001 row boundaries`() = runTest {
        listOf(999, 1_000, 1_001).forEach { rowCount ->
            val rows = (0 until rowCount).map { index -> dto(id = id(index)) }
            val requests = mutableListOf<ExhibitionPageRequest>()

            val result = fetchAllExhibitionDtos { request ->
                requests += request
                pageAfter(rows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
            }

            val nonEmptyPageCount =
                (rowCount + EXHIBITION_PAGE_SIZE - 1) / EXHIBITION_PAGE_SIZE
            assertEquals(rowCount, result.size, "row count $rowCount must not be truncated")
            assertEquals(
                rows.map { it.id },
                result.map { it.id },
                "row count $rowCount must preserve the complete id sequence",
            )
            assertEquals(
                nonEmptyPageCount + 1,
                requests.size,
                "row count $rowCount must include one empty terminal request",
            )
            assertEquals(
                rows.last().id,
                requests.last().cursorExclusive,
                "the empty terminal request must advance past the final row for $rowCount",
            )
        }
    }

    @Test
    fun `continues after server-capped short pages until an empty page`() = runTest {
        val rows = (0 until 401).map { index -> dto(id = id(index)) }
        var calls = 0

        val result = fetchAllExhibitionDtos { request ->
            calls += 1
            pageAfter(rows, request.cursorExclusive, serverCap = 137)
        }

        assertEquals(401, result.size)
        assertEquals(4, calls, "three non-empty short pages plus one empty terminal page")
    }

    @Test
    fun `retains featured and event filters for every page and integrity request`() = runTest {
        val rows = listOf(dto("a"), dto("b"))

        listOf(
            ExhibitionPageFilter.Featured,
            ExhibitionPageFilter.Event("hannam-saturdays"),
        ).forEach { filter ->
            val requests = mutableListOf<ExhibitionPageRequest>()
            val integrityFilters = mutableListOf<ExhibitionPageFilter?>()

            fetchAllExhibitions(
                filter = filter,
                fetchPage = { request ->
                    requests += request
                    pageAfter(rows, request.cursorExclusive, serverCap = 1)
                },
                fetchIntegrity = { integrityFilter ->
                    integrityFilters += integrityFilter
                    integrityFor(rows)
                },
            )

            assertEquals(3, requests.size)
            assertTrue(requests.all { it.filter == filter })
            assertEquals(listOf(null, "a", "b"), requests.map { it.cursorExclusive })
            assertEquals(listOf<ExhibitionPageFilter?>(filter), integrityFilters)
        }
    }

    @Test
    fun `builds scalar PostgREST filters and encoded keyset URL`() {
        val rawFilter = "hannam.event,(한남):\\filter\""
        val rawCursor = "exhibition.row,(한남):\\cursor\""
        val featuredUrl = Url(
            buildExhibitionPageUrl(
                restBase = "https://example.supabase.co/rest/v1",
                request = ExhibitionPageRequest(filter = ExhibitionPageFilter.Featured),
            ),
        )
        val urlString = buildExhibitionPageUrl(
            restBase = "https://example.supabase.co/rest/v1",
            request = ExhibitionPageRequest(
                cursorExclusive = rawCursor,
                filter = ExhibitionPageFilter.Event(rawFilter),
            ),
        )
        val url = Url(urlString)

        assertEquals(ExhibitionCatalogSource.LEGACY.selectColumns, url.parameters["select"])
        assertEquals("id.asc", url.parameters["order"])
        assertEquals("500", url.parameters["limit"])
        assertEquals("eq.${postgrestFilterLiteral(rawFilter)}", url.parameters["event_id"])
        assertEquals("gt.${postgrestFilterLiteral(rawCursor)}", url.parameters["id"])
        assertEquals("eq.true", featuredUrl.parameters["is_featured"])
        assertFalse(urlString.contains(' '), "raw spaces must not leak into the URL")
        assertFalse(urlString.contains("한남"), "non-ASCII query values must be percent encoded")
        listOf("%2C", "%28", "%29", "%3A", "%5C", "%22").forEach { encoded ->
            assertTrue(urlString.contains(encoded), "URLBuilder must encode scalar content as $encoded")
        }
        assertEquals("simple", postgrestFilterLiteral("simple"))
        assertEquals("quote\" and slash\\", postgrestFilterLiteral("quote\" and slash\\"))
    }

    @Test
    fun `builds unfiltered featured and encoded event integrity RPC URLs`() {
        val unfiltered = Url(
            buildExhibitionIntegrityUrl("https://example.supabase.co/rest/v1"),
        )
        val featured = Url(
            buildExhibitionIntegrityUrl(
                restBase = "https://example.supabase.co/rest/v1",
                filter = ExhibitionPageFilter.Featured,
            ),
        )
        val rawEventId = "hannam / 한남?edition=1"
        val eventUrlString = buildExhibitionIntegrityUrl(
            restBase = "https://example.supabase.co/rest/v1",
            filter = ExhibitionPageFilter.Event(rawEventId),
        )
        val event = Url(eventUrlString)

        assertTrue(unfiltered.parameters.names().isEmpty(), "unfiltered RPC must omit default arguments")
        assertEquals(setOf("p_featured_only"), featured.parameters.names())
        assertEquals("true", featured.parameters["p_featured_only"])
        assertEquals(setOf("p_event_id"), event.parameters.names())
        assertEquals(rawEventId, event.parameters["p_event_id"])
        assertFalse(eventUrlString.contains(' '))
        assertFalse(eventUrlString.contains("한남"))
    }

    @Test
    fun `builds event lookup with encoded scalar id`() {
        val rawId = "event.with,(reserved):한남 &?#\"\\"
        val urlString = buildEventByIdUrl(
            restBase = "https://example.supabase.co/rest/v1",
            id = rawId,
        )
        val url = Url(urlString)

        assertEquals("/rest/v1/events", url.encodedPath)
        assertEquals("*", url.parameters["select"])
        assertEquals("eq.$rawId", url.parameters["id"])
        assertEquals("1", url.parameters["limit"])
        assertFalse(urlString.contains("한남"))
        listOf("%2C", "%28", "%29", "%3A", "%26", "%3F", "%23", "%22", "%5C")
            .forEach { encoded ->
                assertTrue(urlString.contains(encoded), "URLBuilder must encode event ID as $encoded")
            }
    }

    @Test
    fun `checksum uses UTF-8 byte length framing and lowercase sha256`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            exhibitionIdChecksumSha256(emptyList()),
        )
        assertEquals(
            "7109eab322e40417f68c2324b32fc46083de5559b26afa84ad8bec35a870d871",
            exhibitionIdChecksumSha256(listOf("a", "é")),
        )
    }

    @Test
    fun `canonical checksum frames UTF-8 ids and database row checksums`() {
        val rows = listOf(
            dto(id = "a", contentChecksumSha256 = "a".repeat(64)),
            dto(id = "한", contentChecksumSha256 = "b".repeat(64)),
        )

        assertEquals(
            "bdb846f9b6e11a951433d397181c31f47b1ba49b1844e32484d10ca2742dab96",
            exhibitionCatalogChecksumSha256(rows),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            exhibitionCatalogChecksumSha256(emptyList()),
        )
    }

    @Test
    fun `canonical same-id content change retries the entire fetch and recovers`() = runTest {
        val staleRows = listOf(dto("same-id", contentChecksumSha256 = "a".repeat(64)))
        val currentRows = listOf(dto("same-id", contentChecksumSha256 = "b".repeat(64)))
        var collectionAttempts = 0
        var integrityCalls = 0
        var activeRows = staleRows

        val result = fetchVerifiedExhibitionDtos(
            source = ExhibitionCatalogSource.CANONICAL_V2,
            fetchPage = { request ->
                if (request.cursorExclusive == null) {
                    collectionAttempts += 1
                    activeRows = if (collectionAttempts == 1) staleRows else currentRows
                }
                pageAfter(activeRows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
            },
            fetchIntegrity = {
                integrityCalls += 1
                integrityFor(currentRows, ExhibitionCatalogSource.CANONICAL_V2)
            },
        )

        assertEquals(2, collectionAttempts)
        assertEquals(2, integrityCalls)
        assertEquals("b".repeat(64), result.single().contentChecksumSha256)
    }

    @Test
    fun `permanent canonical same-id content mismatch retries once then fails closed`() = runTest {
        val staleRows = listOf(dto("same-id", contentChecksumSha256 = "a".repeat(64)))
        val currentRows = listOf(dto("same-id", contentChecksumSha256 = "b".repeat(64)))
        var collectionAttempts = 0
        var integrityCalls = 0

        assertFailsWith<ExhibitionIntegrityMismatchException> {
            fetchVerifiedExhibitionDtos(
                source = ExhibitionCatalogSource.CANONICAL_V2,
                fetchPage = { request ->
                    if (request.cursorExclusive == null) collectionAttempts += 1
                    pageAfter(staleRows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
                },
                fetchIntegrity = {
                    integrityCalls += 1
                    integrityFor(currentRows, ExhibitionCatalogSource.CANONICAL_V2)
                },
            )
        }

        assertEquals(2, collectionAttempts)
        assertEquals(2, integrityCalls)
    }

    @Test
    fun `verified empty canonical catalog succeeds with the empty catalog checksum`() = runTest {
        var pageCalls = 0
        var integrityCalls = 0

        val result = fetchVerifiedExhibitionDtos(
            source = ExhibitionCatalogSource.CANONICAL_V2,
            fetchPage = {
                pageCalls += 1
                emptyList()
            },
            fetchIntegrity = {
                integrityCalls += 1
                integrityFor(emptyList(), ExhibitionCatalogSource.CANONICAL_V2)
            },
        )

        assertTrue(result.isEmpty())
        assertEquals(1, pageCalls)
        assertEquals(1, integrityCalls)
    }

    @Test
    fun `canonical rows require a valid database content checksum`() = runTest {
        listOf(null, "BAD-CHECKSUM").forEach { checksum ->
            var integrityCalls = 0
            assertFailsWith<IllegalStateException> {
                fetchVerifiedExhibitionDtos(
                    source = ExhibitionCatalogSource.CANONICAL_V2,
                    fetchPage = { listOf(dto("invalid", contentChecksumSha256 = checksum)) },
                    fetchIntegrity = {
                        integrityCalls += 1
                        integrityFor(emptyList(), ExhibitionCatalogSource.CANONICAL_V2)
                    },
                )
            }
            assertEquals(0, integrityCalls)
        }
    }

    @Test
    fun `canonical integrity requires a valid catalog checksum without retry`() = runTest {
        val rows = listOf(dto("a", contentChecksumSha256 = "a".repeat(64)))
        val malformed = listOf(null, "BAD-CHECKSUM")

        malformed.forEach { checksum ->
            var collectionAttempts = 0
            var integrityCalls = 0
            assertFailsWith<IllegalStateException> {
                fetchVerifiedExhibitionDtos(
                    source = ExhibitionCatalogSource.CANONICAL_V2,
                    fetchPage = { request ->
                        if (request.cursorExclusive == null) collectionAttempts += 1
                        pageAfter(rows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
                    },
                    fetchIntegrity = {
                        integrityCalls += 1
                        integrityFor(rows, ExhibitionCatalogSource.CANONICAL_V2)
                            .copy(catalogChecksumSha256 = checksum)
                    },
                )
            }
            assertEquals(1, collectionAttempts)
            assertEquals(1, integrityCalls)
        }
    }

    @Test
    fun `sorts by opening date descending and preserves database order for ties`() = runTest {
        val rows = listOf(
            dto(id = "z", openingDate = "2026-03-01"),
            dto(id = "a", openingDate = "2026-03-01"),
            dto(id = "m", openingDate = "2026-01-01"),
            dto(id = "d", openingDate = "2025-12-01"),
        )
        var pageCalls = 0

        val result = fetchAllExhibitions(
            fetchPage = {
                pageCalls += 1
                if (pageCalls == 1) rows else emptyList()
            },
            fetchIntegrity = { integrityFor(rows) },
        )

        assertTrue("z" > "a", "tie fixture must differ from Kotlin lexical ordering")
        assertEquals(listOf("z", "a", "m", "d"), result.map { it.id })
    }

    @Test
    fun `same-count membership change retries the entire fetch and recovers`() = runTest {
        val staleRows = listOf(dto("a"), dto("c"))
        val currentRows = listOf(dto("a"), dto("b"))
        val filter = ExhibitionPageFilter.Event("hannam-saturdays")
        var collectionAttempts = 0
        var integrityCalls = 0
        var activeRows = staleRows
        val pageFilters = mutableListOf<ExhibitionPageFilter?>()
        val integrityFilters = mutableListOf<ExhibitionPageFilter?>()

        val result = fetchAllExhibitions(
            filter = filter,
            fetchPage = { request ->
                pageFilters += request.filter
                if (request.cursorExclusive == null) {
                    collectionAttempts += 1
                    activeRows = if (collectionAttempts == 1) staleRows else currentRows
                }
                pageAfter(activeRows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
            },
            fetchIntegrity = { integrityFilter ->
                integrityCalls += 1
                integrityFilters += integrityFilter
                integrityFor(currentRows)
            },
        )

        assertEquals(2, collectionAttempts)
        assertEquals(2, integrityCalls)
        assertTrue(pageFilters.all { it == filter })
        assertEquals(listOf<ExhibitionPageFilter?>(filter, filter), integrityFilters)
        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    @Test
    fun `permanent same-count membership mismatch throws without exposing a prefix`() = runTest {
        val receivedRows = listOf(dto("a"), dto("c"))
        val expectedRows = listOf(dto("a"), dto("b"))
        var collectionAttempts = 0
        var integrityCalls = 0

        val error = assertFailsWith<ExhibitionIntegrityMismatchException> {
            fetchAllExhibitions(
                fetchPage = { request ->
                    if (request.cursorExclusive == null) collectionAttempts += 1
                    pageAfter(receivedRows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
                },
                fetchIntegrity = {
                    integrityCalls += 1
                    integrityFor(expectedRows)
                },
            )
        }

        assertEquals(2, collectionAttempts)
        assertEquals(2, integrityCalls)
        assertTrue(error.message.orEmpty().contains("integrity mismatch"))
    }

    @Test
    fun `permanent count mismatch retries once then throws`() = runTest {
        val rows = listOf(dto("a"), dto("b"))
        val wrongCount = integrityFor(rows).copy(rowCount = 3)
        var collectionAttempts = 0

        assertFailsWith<ExhibitionIntegrityMismatchException> {
            fetchAllExhibitions(
                fetchPage = { request ->
                    if (request.cursorExclusive == null) collectionAttempts += 1
                    pageAfter(rows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
                },
                fetchIntegrity = { wrongCount },
            )
        }

        assertEquals(2, collectionAttempts)
    }

    @Test
    fun `malformed integrity values throw immediately without retry`() = runTest {
        val rows = listOf(dto("a"))
        val valid = integrityFor(rows)
        val malformedRows = listOf(
            valid.copy(rowCount = -1),
            valid.copy(idChecksumSha256 = "NOT-A-LOWERCASE-SHA256"),
        )

        malformedRows.forEach { malformed ->
            var collectionAttempts = 0
            var integrityCalls = 0

            assertFailsWith<IllegalStateException> {
                fetchAllExhibitions(
                    fetchPage = { request ->
                        if (request.cursorExclusive == null) collectionAttempts += 1
                        pageAfter(rows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
                    },
                    fetchIntegrity = {
                        integrityCalls += 1
                        malformed
                    },
                )
            }

            assertEquals(1, collectionAttempts)
            assertEquals(1, integrityCalls)
        }
    }

    @Test
    fun `malformed integrity row cardinality is rejected`() {
        val valid = integrityFor(listOf(dto("a")))

        assertFailsWith<IllegalStateException> {
            singleExhibitionIntegrityRow(emptyList())
        }
        assertFailsWith<IllegalStateException> {
            singleExhibitionIntegrityRow(listOf(valid, valid))
        }
    }

    @Test
    fun `malformed integrity JSON fails decoding`() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        assertFailsWith<SerializationException> {
            json.decodeFromString<List<ExhibitionReaderIntegrityDto>>(
                """[{"row_count":2}]""",
            )
        }
    }

    @Test
    fun `integrity RPC failure propagates immediately without retry or partial result`() = runTest {
        val rows = listOf(dto("a"), dto("b"))
        val rpcFailure = IllegalStateException("integrity RPC HTTP 503")
        var collectionAttempts = 0
        var integrityCalls = 0

        val error = assertFailsWith<IllegalStateException> {
            fetchAllExhibitions(
                fetchPage = { request ->
                    if (request.cursorExclusive == null) collectionAttempts += 1
                    pageAfter(rows, request.cursorExclusive, serverCap = EXHIBITION_PAGE_SIZE)
                },
                fetchIntegrity = {
                    integrityCalls += 1
                    throw rpcFailure
                },
            )
        }

        assertTrue(error === rpcFailure)
        assertEquals(1, collectionAttempts)
        assertEquals(1, integrityCalls)
    }

    @Test
    fun `rejects duplicate id inside a page`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            fetchAllExhibitionDtos {
                listOf(dto("duplicate"), dto("duplicate"))
            }
        }

        assertTrue(error.message.orEmpty().contains("duplicate id"))
    }

    @Test
    fun `rejects a repeated cursor id on a later page`() = runTest {
        var calls = 0

        val error = assertFailsWith<IllegalStateException> {
            fetchAllExhibitionDtos {
                calls += 1
                if (calls == 1) listOf(dto("a"), dto("b")) else listOf(dto("b"))
            }
        }

        assertEquals(2, calls)
        assertTrue(error.message.orEmpty().contains("duplicate id 'b'"))
    }

    @Test
    fun `rejects blank ids`() = runTest {
        assertFailsWith<IllegalStateException> {
            fetchAllExhibitionDtos { listOf(dto(" ")) }
        }
    }

    @Test
    fun `accepts database order that differs from Kotlin lexical order when integrity matches`() = runTest {
        val rowsInDatabaseOrder = listOf(dto("z"), dto("a"))
        val requestedCursors = mutableListOf<String?>()

        val result = fetchVerifiedExhibitionDtos(
            fetchPage = { request ->
                requestedCursors += request.cursorExclusive
                when (request.cursorExclusive) {
                    null -> listOf(rowsInDatabaseOrder[0])
                    "z" -> listOf(rowsInDatabaseOrder[1])
                    "a" -> emptyList()
                    else -> error("Unexpected cursor ${request.cursorExclusive}")
                }
            },
            fetchIntegrity = { integrityFor(rowsInDatabaseOrder) },
        )

        assertTrue("z" > "a", "fixture must differ from Kotlin lexical ordering")
        assertEquals(listOf("z", "a"), result.map { it.id })
        assertEquals(listOf(null, "z", "a"), requestedCursors)
    }

    @Test
    fun `invalid dto fails the complete request instead of being dropped`() = runTest {
        val rows = listOf(dto("a"), dto("b", openingDate = "not-a-date"))
        var calls = 0

        val error = assertFailsWith<IllegalStateException> {
            fetchAllExhibitions(
                fetchPage = { request ->
                    calls += 1
                    if (request.cursorExclusive == null) rows else emptyList()
                },
                fetchIntegrity = { integrityFor(rows) },
            )
        }

        assertEquals(2, calls, "mapping happens only after the empty terminal page confirms completeness")
        assertTrue(error.message.orEmpty().contains("Exhibition 'b'"))
    }

    @Test
    fun `page two failure propagates without returning a partial prefix`() = runTest {
        val transportFailure = IllegalStateException("page two HTTP 503")
        var calls = 0

        val error = assertFailsWith<IllegalStateException> {
            fetchAllExhibitionDtos {
                calls += 1
                if (calls == 1) listOf(dto("a")) else throw transportFailure
            }
        }

        assertEquals(2, calls)
        assertTrue(error === transportFailure)
    }

    private fun pageAfter(
        rows: List<ExhibitionDto>,
        cursorExclusive: String?,
        serverCap: Int,
    ): List<ExhibitionDto> {
        val start = if (cursorExclusive == null) {
            0
        } else {
            val cursorIndex = rows.indexOfFirst { it.id == cursorExclusive }
            check(cursorIndex >= 0) { "Fixture does not contain cursor '$cursorExclusive'" }
            cursorIndex + 1
        }
        return rows.drop(start).take(serverCap)
    }

    private fun id(index: Int): String = "ex-${index.toString().padStart(4, '0')}"

    private fun integrityFor(
        rows: List<ExhibitionDto>,
        source: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
    ) = ExhibitionReaderIntegrityDto(
        rowCount = rows.size.toLong(),
        idChecksumSha256 = exhibitionIdChecksumSha256(rows.map { it.id }),
        catalogChecksumSha256 = if (source.requiresContentIntegrity) {
            exhibitionCatalogChecksumSha256(rows)
        } else {
            null
        },
    )

    private fun dto(
        id: String,
        openingDate: String = "2026-01-01",
        closingDate: String = "2026-12-31",
        contentChecksumSha256: String? = null,
    ) = ExhibitionDto(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Exhibition $id",
        venueNameKo = "전시장",
        venueNameEn = "Venue",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "서울",
        regionEn = "Seoul",
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = false,
        contentChecksumSha256 = contentChecksumSha256,
    )
}
