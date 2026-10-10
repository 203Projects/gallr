package com.gallr.shared.home

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.galleryKey
import com.gallr.shared.taste.effectiveArtTerms
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Builds the home feed from the visible catalogue. Pure and deterministic: the same inputs always give the
 * same feed, and nothing here depends on the clock beyond [today].
 *
 * @param exhibitions the catalogue as the tabs show it (running or opening within two weeks), in catalogue order.
 * @param featured the featured list in its own order; the hero keeps that order.
 */
fun buildHomeFeed(
    exhibitions: List<Exhibition>,
    featured: List<Exhibition>,
    today: LocalDate,
    followedGalleries: List<FollowedGallery> = emptyList(),
): HomeFeed {
    val hero = featured.take(HERO_LIMIT)
    val heroIds = hero.mapTo(mutableSetOf()) { it.id }
    val editorPicks =
        exhibitions
            .filter { it.editorId == Editor.HOUSE_EDITOR_ID && it.id !in heroIds }
            .take(RAIL_LIMIT)
    return HomeFeed(
        hero = hero,
        editorPicks = editorPicks,
        fromFollowedGalleries = followedGalleryExhibitions(exhibitions, followedGalleries, today),
        collections = collections(exhibitions, today),
    )
}

private fun followedGalleryExhibitions(
    exhibitions: List<Exhibition>,
    followedGalleries: List<FollowedGallery>,
    today: LocalDate,
): List<Exhibition> {
    if (followedGalleries.isEmpty()) return emptyList()
    val followedIds = followedGalleries.mapNotNullTo(mutableSetOf()) { it.galleryId }
    val followedKeys =
        followedGalleries.flatMapTo(mutableSetOf()) { followed ->
            listOf(followed.galleryKey, galleryKey(followed.snapshot.nameKo, followed.snapshot.nameEn))
        }
    return exhibitions
        .filter { it.closingDate >= today }
        .filter { it.galleryId in followedIds || galleryKey(it.venueNameKo, it.venueNameEn) in followedKeys }
        .sortedByDescending { it.openingDate }
        .take(RAIL_LIMIT)
}

private fun collections(
    exhibitions: List<Exhibition>,
    today: LocalDate,
): List<HomeCollection> {
    val weekEnd = today.plus(DAYS_IN_WEEK - 1, DateTimeUnit.DAY)
    val closing =
        exhibitions
            .filter { it.closingDate in today..weekEnd }
            .sortedBy { it.closingDate }
            .asCollection(HomeCollectionKind.CLOSING_THIS_WEEK, "closing-this-week", "이번 주 마감", "CLOSING THIS WEEK")
    val opening =
        exhibitions
            .filter { it.openingDate in today..weekEnd }
            .sortedBy { it.openingDate }
            .asCollection(HomeCollectionKind.OPENING_THIS_WEEK, "opening-this-week", "이번 주 개막", "OPENING THIS WEEK")
    return (listOfNotNull(closing, opening) + neighborhoods(exhibitions) + themes(exhibitions)).take(COLLECTION_LIMIT)
}

private fun neighborhoods(exhibitions: List<Exhibition>): List<HomeCollection> =
    exhibitions
        .filter { it.regionKo.isNotBlank() }
        .groupBy { it.regionKo.trim() }
        .values
        .filter { it.size >= GROUP_MIN_SIZE }
        .sortedWith(compareByDescending<List<Exhibition>> { it.size }.thenBy { it.first().regionKo.trim() })
        .take(COLLECTIONS_PER_KIND)
        .mapNotNull { shows ->
            val first = shows.first()
            shows.asCollection(
                kind = HomeCollectionKind.NEIGHBORHOOD,
                key = "region:${first.regionKo.trim()}",
                titleKo = first.regionKo.trim(),
                titleEn = first.regionEn.trim().ifEmpty { first.regionKo.trim() },
            )
        }

/**
 * Themes come from the exhibitions' reviewed or detected terms. A term on a third of the catalogue or more
 * describes the catalogue, not a theme, so it never becomes a collection.
 */
private fun themes(exhibitions: List<Exhibition>): List<HomeCollection> {
    val byTerm = LinkedHashMap<String, Pair<ArtTerm, MutableList<Exhibition>>>()
    exhibitions.forEach { exhibition ->
        exhibition.effectiveArtTerms().distinctBy(ArtTerm::id).forEach { term ->
            byTerm.getOrPut(term.id) { term to mutableListOf() }.second += exhibition
        }
    }
    val broadest = exhibitions.size / BROAD_TERM_DIVISOR
    return byTerm.values
        .filter { (_, shows) -> shows.size in GROUP_MIN_SIZE..maxOf(broadest, GROUP_MIN_SIZE) }
        .sortedWith(
            compareByDescending<Pair<ArtTerm, List<Exhibition>>> { it.second.size }
                .thenBy { it.first.category.ordinal }
                .thenBy { it.first.id },
        ).take(COLLECTIONS_PER_KIND)
        .mapNotNull { (term, shows) ->
            shows.asCollection(
                kind = HomeCollectionKind.THEME,
                key = term.id,
                titleKo = term.nameKo.ifBlank { term.nameEn },
                titleEn = term.nameEn.ifBlank { term.nameKo },
            )
        }
}

private fun List<Exhibition>.asCollection(
    kind: HomeCollectionKind,
    key: String,
    titleKo: String,
    titleEn: String,
): HomeCollection? {
    if (size < COLLECTION_MIN_SIZE) return null
    return HomeCollection(
        kind = kind,
        key = key,
        titleKo = titleKo,
        titleEn = titleEn,
        coverImageUrl = conceptCover(),
        exhibitions = this,
    )
}

/** The concept image: a featured cover when the collection has one, otherwise the first cover. */
private fun List<Exhibition>.conceptCover(): String? =
    firstOrNull { it.isFeatured && it.coverImageUrl != null }?.coverImageUrl
        ?: firstOrNull { it.coverImageUrl != null }?.coverImageUrl

private const val HERO_LIMIT = 6
private const val RAIL_LIMIT = 10
private const val COLLECTION_LIMIT = 6
private const val COLLECTIONS_PER_KIND = 2

/** A timing collection says something with two exhibitions; a district or theme needs three to read as one. */
private const val COLLECTION_MIN_SIZE = 2
private const val GROUP_MIN_SIZE = 3
private const val DAYS_IN_WEEK = 7
private const val BROAD_TERM_DIVISOR = 3
