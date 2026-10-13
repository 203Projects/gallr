package com.gallr.shared.home

import com.gallr.shared.curatornote.CuratorEyeCard
import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition

/** What a themed collection on the home tab gathers. */
enum class HomeCollectionKind {
    /** The exhibitions opened most often in the app this week, most opened first (spec 091 FR-019). */
    MOST_VIEWED,

    /** Exhibitions an editor tagged as free to enter (spec 091 FR-022). */
    FREE_ADMISSION,
    CLOSING_THIS_WEEK,
    OPENING_THIS_WEEK,

    /** A situation answered by a bundle of art terms: a quiet visit, somewhere to photograph ([HomeSituation]). */
    SITUATION,
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
    /** For a situation, the words its tile shows after `#`; empty for every other kind. */
    val tagsKo: List<String> = emptyList(),
    val tagsEn: List<String> = emptyList(),
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

    fun localizedTags(lang: AppLanguage): List<String> =
        when (lang) {
            AppLanguage.EN -> tagsEn.ifEmpty { tagsKo }
            AppLanguage.KO -> tagsKo
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
    /** Approved curator notes for running or upcoming exhibitions, newest approval first (spec 090). */
    val curatorEye: List<CuratorEyeCard> = emptyList(),
    /** The public ranking's first listed routes for the 지금 주목받는 동선 rail, in ranking order; empty hides it (spec 091). */
    val routes: List<HomeRoute> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            hero.isEmpty() && editorPicks.isEmpty() && fromFollowedGalleries.isEmpty() && collections.isEmpty() &&
                curatorEye.isEmpty()

    companion object {
        val EMPTY = HomeFeed(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
