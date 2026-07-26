package com.gallr.shared.data.network

/**
 * Selects one complete public exhibition read contract.
 *
 * A source owns both its table and integrity RPC so callers cannot accidentally
 * combine canonical pages with the legacy integrity snapshot (or vice versa).
 */
enum class ExhibitionCatalogSource(
    val configValue: String,
    internal val tableName: String,
    internal val integrityRpcName: String,
    internal val requiresContentIntegrity: Boolean,
) {
    LEGACY(
        configValue = "legacy",
        tableName = "exhibitions",
        integrityRpcName = "exhibition_reader_integrity",
        requiresContentIntegrity = false,
    ),
    CANONICAL_V2(
        configValue = "canonical-v2",
        tableName = "exhibition_catalog_v2",
        integrityRpcName = "exhibition_catalog_v2_integrity",
        requiresContentIntegrity = true,
    ),
    ;

    internal val selectColumns: String
        get() = if (requiresContentIntegrity) {
            "$BASE_SELECT_COLUMNS,content_checksum_sha256"
        } else {
            BASE_SELECT_COLUMNS
        }

    companion object {
        private const val BASE_SELECT_COLUMNS =
            "id,name_ko,name_en,venue_name_ko,venue_name_en,city_ko,city_en," +
                "region_ko,region_en,opening_date,closing_date,is_featured,latitude,longitude," +
                "description_ko,description_en,address_ko,address_en,cover_image_url,hours," +
                "contact,reception_date,opening_time,event_id,editor_id"

        /** Missing configuration deliberately retains the legacy rollback path. */
        fun fromConfig(value: String? = null): ExhibitionCatalogSource {
            val configured = value?.trim().orEmpty().ifEmpty { LEGACY.configValue }
            return entries.firstOrNull { it.configValue == configured }
                ?: throw IllegalArgumentException(
                    "Unsupported exhibition catalog source '$configured'; " +
                        "expected '${LEGACY.configValue}' or '${CANONICAL_V2.configValue}'",
                )
        }
    }
}
