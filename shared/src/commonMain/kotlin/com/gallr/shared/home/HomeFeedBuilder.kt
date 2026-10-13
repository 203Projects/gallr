package com.gallr.shared.home

import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.curatornote.curatorEyeCards
import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.VisitTag
import com.gallr.shared.data.model.galleryKey
import com.gallr.shared.route.PublicRouteSummary
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
 * @param curatorNotes approved curator notes; only those about exhibitions in [exhibitions] become cards.
 * @param routes the public route ranking, most copied first; [routeStops] maps a route id to its stops' exhibition
 *   ids in stop order, for the covers on its card.
 * @param popularExhibitionIds the exhibitions opened most this week, most opened first, as the server ranked them.
 */
fun buildHomeFeed(
    exhibitions: List<Exhibition>,
    featured: List<Exhibition>,
    today: LocalDate,
    followedGalleries: List<FollowedGallery> = emptyList(),
    curatorNotes: List<CuratorNote> = emptyList(),
    routes: List<PublicRouteSummary> = emptyList(),
    routeStops: Map<String, List<String>> = emptyMap(),
    popularExhibitionIds: List<String> = emptyList(),
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
        collections = collections(exhibitions, today, popularExhibitionIds),
        curatorEye = curatorEyeCards(curatorNotes, exhibitions, today),
        routes = homeRoutes(routes, routeStops, exhibitions),
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
    popularExhibitionIds: List<String>,
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
    val mostViewed = mostViewed(exhibitions, popularExhibitionIds)
    // Reading an exhibition's terms scans its text, so it happens once for the situations and the themes.
    val termsByExhibition = exhibitions.map { it to it.effectiveArtTerms().distinctBy(ArtTerm::id) }
    val situations = situations(termsByExhibition)
    val free =
        exhibitions
            .filter { VisitTag.FREE_ADMISSION in it.visitTags }
            .take(RAIL_LIMIT)
            .asCollection(HomeCollectionKind.FREE_ADMISSION, "free-admission", "무료 관람", "FREE ADMISSION")
    val answered = HOME_SITUATIONS.filter { situation -> situations.any { it.key == situation.key } }
    val themes = themes(termsByExhibition, exclude = answered.flatMapTo(mutableSetOf()) { it.termIds })
    return (listOfNotNull(mostViewed, closing, opening, free) + situations + neighborhoods(exhibitions) + themes)
        .take(COLLECTION_LIMIT)
        .withDistinctCovers()
}

/**
 * The situations enough exhibitions answer, in [HOME_SITUATIONS] order: an exhibition answers one when any of its
 * terms is in the situation's bundle or it carries one of the situation's visit tags, and three make a collection.
 */
private fun situations(termsByExhibition: List<Pair<Exhibition, List<ArtTerm>>>): List<HomeCollection> =
    HOME_SITUATIONS.mapNotNull { situation ->
        val shows =
            termsByExhibition
                .filter { (exhibition, terms) ->
                    terms.any { it.id in situation.termIds } || exhibition.visitTags.any { it in situation.visitTags }
                }.map { it.first }
                .take(RAIL_LIMIT)
        if (shows.size < GROUP_MIN_SIZE) return@mapNotNull null
        HomeCollection(
            kind = HomeCollectionKind.SITUATION,
            key = situation.key,
            titleKo = situation.titleKo,
            titleEn = situation.titleEn,
            coverImageUrl = null,
            exhibitions = shows,
            tagsKo = situation.tagsKo,
            tagsEn = situation.tagsEn,
        )
    }

/**
 * The exhibitions opened most this week, in the order the server ranked them. Ids the catalogue no longer shows are
 * dropped, and fewer than [MOST_VIEWED_MIN_SIZE] left is not a ranking worth a tile.
 */
private fun mostViewed(
    exhibitions: List<Exhibition>,
    popularExhibitionIds: List<String>,
): HomeCollection? {
    val byId = exhibitions.associateBy(Exhibition::id)
    val shows = popularExhibitionIds.distinct().mapNotNull(byId::get).take(RAIL_LIMIT)
    if (shows.size < MOST_VIEWED_MIN_SIZE) return null
    return HomeCollection(
        kind = HomeCollectionKind.MOST_VIEWED,
        key = "most-viewed",
        titleKo = "지금 가장 많이 본",
        titleEn = "MOST VIEWED NOW",
        coverImageUrl = null,
        exhibitions = shows,
    )
}

/**
 * Each collection borrows a different cover where it can: a featured cover no earlier collection took, then any
 * unused cover, then any cover at all. Two tiles with the same picture read as one theme split in two.
 */
private fun List<HomeCollection>.withDistinctCovers(): List<HomeCollection> {
    val used = mutableSetOf<String>()
    return map { collection ->
        val covers = collection.exhibitions.filter { it.coverImageUrl != null }
        val cover =
            covers.firstOrNull { it.isFeatured && it.coverImageUrl !in used }?.coverImageUrl
                ?: covers.firstOrNull { it.coverImageUrl !in used }?.coverImageUrl
                ?: covers.firstOrNull()?.coverImageUrl
        cover?.let(used::add)
        collection.copy(coverImageUrl = cover)
    }
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
 * describes the catalogue, not a theme, so it never becomes a collection; nor does a term in [exclude], which a
 * shown situation already answers.
 */
private fun themes(
    termsByExhibition: List<Pair<Exhibition, List<ArtTerm>>>,
    exclude: Set<String>,
): List<HomeCollection> {
    val byTerm = LinkedHashMap<String, Pair<ArtTerm, MutableList<Exhibition>>>()
    termsByExhibition.forEach { (exhibition, terms) ->
        terms.filterNot { it.id in exclude }.forEach { term ->
            byTerm.getOrPut(term.id) { term to mutableListOf() }.second += exhibition
        }
    }
    val broadest = termsByExhibition.size / BROAD_TERM_DIVISOR
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
        coverImageUrl = null,
        exhibitions = this,
    )
}

private const val HERO_LIMIT = 6
private const val RAIL_LIMIT = 10
private const val COLLECTION_LIMIT = 14
private const val MOST_VIEWED_MIN_SIZE = 3
private const val COLLECTIONS_PER_KIND = 2

/** A timing collection says something with two exhibitions; a district or theme needs three to read as one. */
private const val COLLECTION_MIN_SIZE = 2
private const val GROUP_MIN_SIZE = 3
private const val DAYS_IN_WEEK = 7
private const val BROAD_TERM_DIVISOR = 3
