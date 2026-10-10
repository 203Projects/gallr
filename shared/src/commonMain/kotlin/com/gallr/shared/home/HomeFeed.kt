package com.gallr.shared.home

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

/** What a themed collection on the home tab gathers. */
enum class HomeCollectionKind {
    CLOSING_THIS_WEEK,
    OPENING_THIS_WEEK,
    NEIGHBORHOOD,
    THEME,
}

/**
 * A themed collection: a concept rather than one venue, framed by a headline and represented by one cover
 * borrowed from its exhibitions. [key] is unique within a feed (the kind for the timing collections, the
 * district or term otherwise).
 */
data class HomeCollection(
    val kind: HomeCollectionKind,
    val key: String,
    val titleKo: String,
    val titleEn: String,
    val coverImageUrl: String?,
    val exhibitions: List<Exhibition>,
) {
    init {
        require(key.isNotBlank()) { "collection key must not be blank" }
        require(exhibitions.isNotEmpty()) { "a collection holds at least one exhibition" }
    }

    val count: Int get() = exhibitions.size

    fun localizedTitle(lang: AppLanguage): String =
        when (lang) {
            AppLanguage.EN -> titleEn.ifEmpty { titleKo }
            AppLanguage.KO -> titleKo
        }
}

/**
 * The home tab's content, derived from the visible catalogue and the visitor's own follows. Sections are
 * independent: any of them may be empty, and the screen shows only what has content.
 */
data class HomeFeed(
    /** Featured picks for the hero pager, in the catalogue's featured order. */
    val hero: List<Exhibition>,
    /** The house editors' picks that the hero does not already show. */
    val editorPicks: List<Exhibition>,
    /** Running exhibitions at galleries the visitor follows, newest first. */
    val fromFollowedGalleries: List<Exhibition>,
    val collections: List<HomeCollection>,
) {
    val isEmpty: Boolean
        get() = hero.isEmpty() && editorPicks.isEmpty() && fromFollowedGalleries.isEmpty() && collections.isEmpty()

    companion object {
        val EMPTY = HomeFeed(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
