package com.gallr.shared.fixture

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.GeoPoint
import com.gallr.shared.data.network.dto.ExhibitionDto
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

/**
 * Public-catalogue snapshot decoded through the production DTO path.
 *
 * The rows are published, public catalogue fields only (no user data) and reproduce the
 * discovery defects measured on 2026-10-02: a location-blind For You route pool, boilerplate-driven
 * text similarity, sparse artist and term metadata, and free-text opening hours.
 */
internal object DiscoveryFixture {
    val referenceDate: LocalDate = PublishedCatalogueFixture.referenceDate

    /** Map centre of the Hannam gallery cluster. */
    val HANNAM = GeoPoint(37.5345, 127.0010)

    /** Map centre of Seongsu. */
    val SEONGSU = GeoPoint(37.5446, 127.0557)

    /** Map centre of Cheongdam. */
    val CHEONGDAM = GeoPoint(37.5240, 127.0470)

    /** Map centre of the Samcheong and Bukchon museum cluster. */
    val SAMCHEONG = GeoPoint(37.5795, 126.9815)

    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    val exhibitions: List<Exhibition> by lazy {
        PublishedCatalogueFixture.rowsJson
            .map { row ->
                val dto = json.decodeFromString(ExhibitionDto.serializer(), row)
                dto.toDomain() ?: error("fixture row '${dto.id}' did not map to a domain exhibition")
            }.sortedBy(Exhibition::id)
    }

    fun exhibition(id: String): Exhibition = exhibitions.first { it.id == id }
}
