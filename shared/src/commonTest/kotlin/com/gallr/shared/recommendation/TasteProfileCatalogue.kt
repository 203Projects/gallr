package com.gallr.shared.recommendation

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionArtist
import com.gallr.shared.taste.ART_TERM_VOCABULARY
import kotlinx.datetime.LocalDate

/**
 * Spec 101 US6 (T070): a fixed, hand-built catalogue of fifteen made-up exhibitions with reviewed terms, independent
 * of production data. Every show runs over the same dates, none is featured and none has a description, so the
 * ranking between them comes from reviewed terms and credited artists alone; names share no characters, so text
 * similarity adds nothing. Each term appears on few shows, so a profile's expected leaders follow from what they
 * share.
 */
internal object TasteProfileCatalogue {
    val today: LocalDate = LocalDate(2026, 11, 4)

    val kim = ExhibitionArtist("artist-kim", "김하늘", "Haneul Kim")
    val lee = ExhibitionArtist("artist-lee", "이도윤", "Doyun Lee")

    /** One unrelated syllable per show, so no two names share a character run the text matcher could read. */
    private val names = "갑을병정무기경신임계자축인묘진".map(Char::toString).iterator()

    val exhibitions: List<Exhibition> =
        listOf(
            show("monochrome", "medium:painting", "style:abstract", "style:dansaekhwa", "mood:quiet-meditative"),
            show("gesture", "medium:painting", "style:abstract", "mood:monumental", artists = listOf(kim)),
            show("figures", "medium:painting", "theme:identity", "mood:intimate"),
            show("city-photos", "medium:photography", "theme:city", "theme:archive", artists = listOf(lee)),
            show("portraits", "medium:photography", "theme:identity", "theme:gender"),
            show("bronze", "medium:sculpture", "mood:monumental", "theme:body"),
            show("forest-room", "medium:installation", "medium:sculpture", "theme:nature"),
            show("tapes", "medium:video", "medium:installation", "theme:memory", "theme:archive"),
            show("ink-mountains", "style:korean-painting", "theme:landscape", "mood:quiet-meditative"),
            show("vessels", "medium:ceramics", "mood:intimate"),
            show("woven", "medium:textile", "medium:painting", "style:abstract", artists = listOf(kim)),
            show("diary", "medium:drawing", "mood:intimate", "theme:memory"),
            show("bodies", "medium:performance", "theme:body", "theme:gender"),
            show("documents", "theme:archive", "theme:memory", "style:conceptual"),
            show("coastline", "medium:photography", "theme:landscape", "theme:nature", artists = listOf(lee)),
        )

    fun term(id: String): ArtTerm = ART_TERM_VOCABULARY.first { it.term.id == id }.term

    private fun show(
        id: String,
        vararg termIds: String,
        artists: List<ExhibitionArtist> = emptyList(),
    ) = Exhibition(
        id = id,
        nameKo = names.next(),
        nameEn = "",
        venueNameKo = "갤러리",
        venueNameEn = "Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 10, 1),
        closingDate = LocalDate(2026, 12, 31),
        isFeatured = false,
        latitude = 37.57,
        longitude = 126.98,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        galleryId = "gallery-$id",
        artists = artists,
        artTerms = termIds.map(::term),
    )
}
