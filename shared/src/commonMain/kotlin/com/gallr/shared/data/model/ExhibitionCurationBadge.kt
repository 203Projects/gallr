package com.gallr.shared.data.model

/** Curatorial signals that may be rendered consistently across exhibition surfaces. */
enum class ExhibitionCurationBadge {
    FEATURED,
    EDITORS_PICK,
    ;

    fun label(language: AppLanguage): String =
        when (this) {
            FEATURED -> if (language == AppLanguage.KO) "추천" else "FEATURED"
            EDITORS_PICK -> if (language == AppLanguage.KO) "에디터 추천" else "EDITOR'S PICK"
        }
}

/** Returns visible curation badges in their stable presentation order. */
fun Exhibition.curationBadges(): List<ExhibitionCurationBadge> =
    buildList {
        if (isFeatured) add(ExhibitionCurationBadge.FEATURED)
        if (editorId == HOUSE_EDITOR_ID) add(ExhibitionCurationBadge.EDITORS_PICK)
    }

private const val HOUSE_EDITOR_ID = "gallr-editors"
