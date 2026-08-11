package com.gallr.shared.data.network

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.network.dto.ExhibitionDto
import io.ktor.http.URLBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

internal const val EXHIBITION_PAGE_SIZE = 500

internal sealed interface ExhibitionPageFilter {
    data object Featured : ExhibitionPageFilter

    data class Event(
        val id: String,
    ) : ExhibitionPageFilter
}

internal data class ExhibitionPageRequest(
    val cursorExclusive: String? = null,
    val filter: ExhibitionPageFilter? = null,
)

@Serializable
internal data class ExhibitionReaderIntegrityDto(
    @SerialName("row_count") val rowCount: Long,
    @SerialName("id_checksum_sha256") val idChecksumSha256: String,
    @SerialName("catalog_checksum_sha256") val catalogChecksumSha256: String? = null,
)

internal class ExhibitionIntegrityMismatchException(
    expected: ExhibitionReaderIntegrityDto,
    actualRowCount: Long,
    actualIdChecksum: String,
    actualCatalogChecksum: String?,
) : IllegalStateException(
        "Exhibition integrity mismatch: " +
            "expected count=${expected.rowCount}, id='${expected.idChecksumSha256}', " +
            "catalog=${expected.catalogChecksumSha256}; " +
            "received count=$actualRowCount, id='$actualIdChecksum', " +
            "catalog=$actualCatalogChecksum",
    )

/**
 * Returns a value for a basic PostgREST scalar operator. The basic eq/gt
 * grammar does not strip surrounding quotes, so URLBuilder alone owns the
 * query-string encoding of punctuation, quotes, backslashes, and Unicode.
 */
internal fun postgrestFilterLiteral(value: String): String = value

internal fun buildExhibitionPageUrl(
    restBase: String,
    request: ExhibitionPageRequest,
    source: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
    includeCountryCode: Boolean = true,
): String =
    URLBuilder("$restBase/${source.tableName}")
        .apply {
            parameters.append("select", source.selectColumns(includeCountryCode))
            parameters.append("order", "id.asc")
            parameters.append("limit", EXHIBITION_PAGE_SIZE.toString())
            when (val filter = request.filter) {
                null -> {
                    // No catalog filter is required.
                }

                ExhibitionPageFilter.Featured -> {
                    parameters.append("is_featured", "eq.true")
                }

                is ExhibitionPageFilter.Event -> {
                    parameters.append("event_id", "eq.${postgrestFilterLiteral(filter.id)}")
                }
            }
            request.cursorExclusive?.let { cursor ->
                parameters.append("id", "gt.${postgrestFilterLiteral(cursor)}")
            }
        }.buildString()

/** Builds the typed GET arguments for the reader-integrity RPC. */
internal fun buildExhibitionIntegrityUrl(
    restBase: String,
    filter: ExhibitionPageFilter? = null,
    source: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
): String =
    URLBuilder("$restBase/rpc/${source.integrityRpcName}")
        .apply {
            when (filter) {
                null -> Unit
                ExhibitionPageFilter.Featured -> parameters.append("p_featured_only", "true")
                is ExhibitionPageFilter.Event -> parameters.append("p_event_id", filter.id)
            }
        }.buildString()

/**
 * Matches the database framing contract: UTF-8 byte length, ':', then id,
 * repeated without a delimiter before hashing the complete byte sequence.
 */
internal fun exhibitionIdChecksumSha256(idsInDatabaseOrder: List<String>): String =
    buildString {
        idsInDatabaseOrder.forEach { id ->
            append(id.encodeUtf8().size)
            append(':')
            append(id)
        }
    }.encodeUtf8().sha256().hex()

/**
 * Aggregates canonical row checksums without asking clients to reproduce the
 * database's field serialization. Each row contributes two UTF-8 byte-length
 * frames: its id and its database-computed content checksum.
 */
internal fun exhibitionCatalogChecksumSha256(rowsInDatabaseOrder: List<ExhibitionDto>): String =
    buildString {
        rowsInDatabaseOrder.forEach { row ->
            val checksum = row.contentChecksumSha256
            check(checksum != null && checksum.matches(SHA256_REGEX)) {
                "Canonical exhibition '${row.id}' must include a 64-character lowercase " +
                    "content_checksum_sha256"
            }
            append(row.id.encodeUtf8().size)
            append(':')
            append(row.id)
            append(checksum.encodeUtf8().size)
            append(':')
            append(checksum)
        }
    }.encodeUtf8().sha256().hex()

internal fun singleExhibitionIntegrityRow(rows: List<ExhibitionReaderIntegrityDto>): ExhibitionReaderIntegrityDto {
    check(rows.size == 1) {
        "Exhibition integrity RPC must return exactly one row; received ${rows.size}"
    }
    return rows.single()
}

/**
 * Eagerly loads a complete exhibition collection using an id keyset cursor.
 *
 * A short page is not treated as the end because PostgREST may impose a lower
 * server-side row cap than the requested page size. Only an empty page ends the
 * collection. Any page failure or contract violation escapes to the caller, so
 * a partial prefix can never be mistaken for a complete catalogue. PostgreSQL
 * owns text ordering; the client validates nonblank/global-unique IDs but does
 * not reproduce database collation with Kotlin string comparisons.
 */
internal suspend fun fetchAllExhibitionDtos(
    filter: ExhibitionPageFilter? = null,
    source: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
    fetchPage: suspend (ExhibitionPageRequest) -> List<ExhibitionDto>,
): List<ExhibitionDto> {
    val all = mutableListOf<ExhibitionDto>()
    val seenIds = mutableSetOf<String>()
    var cursor: String? = null

    while (true) {
        val page =
            fetchPage(
                ExhibitionPageRequest(
                    cursorExclusive = cursor,
                    filter = filter,
                ),
            )
        if (page.isEmpty()) break

        page.forEach { dto ->
            val id = dto.id
            check(id.isNotBlank()) { "Exhibition page contains a blank id" }
            if (source.requiresContentIntegrity) {
                val checksum = dto.contentChecksumSha256
                check(checksum != null && checksum.matches(SHA256_REGEX)) {
                    "Canonical exhibition '$id' must include a 64-character lowercase " +
                        "content_checksum_sha256"
                }
            }
            check(seenIds.add(id)) {
                "Exhibition pages contain duplicate id '$id'"
            }
        }

        all += page
        cursor = page.last().id
    }

    return all
}

/**
 * Confirms the complete keyset result against a separately computed database
 * snapshot. A mismatch can be caused by a concurrent catalogue change, so the
 * whole page sequence and integrity RPC are retried exactly once.
 */
internal suspend fun fetchVerifiedExhibitionDtos(
    filter: ExhibitionPageFilter? = null,
    source: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
    fetchPage: suspend (ExhibitionPageRequest) -> List<ExhibitionDto>,
    fetchIntegrity: suspend (ExhibitionPageFilter?) -> ExhibitionReaderIntegrityDto,
): List<ExhibitionDto> {
    repeat(2) { attempt ->
        val rows = fetchAllExhibitionDtos(filter, source, fetchPage)
        val expected = fetchIntegrity(filter).validated(source)
        val actualRowCount = rows.size.toLong()
        val actualIdChecksum = exhibitionIdChecksumSha256(rows.map { it.id })
        val actualCatalogChecksum =
            if (source.requiresContentIntegrity) {
                exhibitionCatalogChecksumSha256(rows)
            } else {
                null
            }

        if (
            expected.rowCount == actualRowCount &&
            expected.idChecksumSha256 == actualIdChecksum &&
            (
                !source.requiresContentIntegrity ||
                    expected.catalogChecksumSha256 == actualCatalogChecksum
            )
        ) {
            return rows
        }

        if (attempt == 1) {
            throw ExhibitionIntegrityMismatchException(
                expected = expected,
                actualRowCount = actualRowCount,
                actualIdChecksum = actualIdChecksum,
                actualCatalogChecksum = actualCatalogChecksum,
            )
        }
    }

    error("Unreachable exhibition integrity retry state")
}

private val SHA256_REGEX = Regex("[0-9a-f]{64}")

private fun ExhibitionReaderIntegrityDto.validated(source: ExhibitionCatalogSource): ExhibitionReaderIntegrityDto =
    apply {
        check(rowCount >= 0) { "Exhibition integrity row_count must be non-negative" }
        check(idChecksumSha256.matches(SHA256_REGEX)) {
            "Exhibition integrity id_checksum_sha256 must be 64 lowercase hex characters"
        }
        if (source.requiresContentIntegrity) {
            check(catalogChecksumSha256?.matches(SHA256_REGEX) == true) {
                "Canonical exhibition integrity catalog_checksum_sha256 must be 64 lowercase " +
                    "hex characters"
            }
        }
    }

/**
 * Maps only after every page has arrived, then applies the public presentation
 * order. Invalid DTOs fail the entire request instead of disappearing silently.
 */
internal suspend fun fetchAllExhibitions(
    filter: ExhibitionPageFilter? = null,
    source: ExhibitionCatalogSource = ExhibitionCatalogSource.LEGACY,
    fetchPage: suspend (ExhibitionPageRequest) -> List<ExhibitionDto>,
    fetchIntegrity: suspend (ExhibitionPageFilter?) -> ExhibitionReaderIntegrityDto,
): List<Exhibition> =
    fetchVerifiedExhibitionDtos(filter, source, fetchPage, fetchIntegrity)
        .map { dto ->
            dto.toDomain()
                ?: error("Exhibition '${dto.id}' has an invalid opening_date or closing_date")
        }
        // Kotlin's stable sort keeps verified database order for equal dates.
        .sortedWith(compareByDescending<Exhibition> { it.openingDate })
