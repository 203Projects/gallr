package com.gallr.shared.data.network.dto

import com.gallr.shared.data.model.Editor
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EditorDto(
    val id: String,
    @SerialName("name_ko") val nameKo: String,
    @SerialName("name_en") val nameEn: String = "",
    @SerialName("title_ko") val titleKo: String,
    @SerialName("title_en") val titleEn: String = "",
    @SerialName("bio_ko") val bioKo: String,
    @SerialName("bio_en") val bioEn: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("active_from") val activeFrom: String? = null,
    @SerialName("active_to") val activeTo: String? = null,
) {
    fun toDomain(): Editor =
        Editor(
            id = id,
            nameKo = nameKo,
            nameEn = nameEn,
            titleKo = titleKo,
            titleEn = titleEn,
            bioKo = bioKo,
            bioEn = bioEn,
            isActive = isActive,
            activeFrom =
                activeFrom?.let {
                    try {
                        LocalDate.parse(it)
                    } catch (_: Exception) {
                        LocalDate(2000, 1, 1)
                    }
                } ?: LocalDate(2000, 1, 1),
            activeTo =
                activeTo?.let {
                    try {
                        LocalDate.parse(it)
                    } catch (_: Exception) {
                        null
                    }
                },
        )
}
