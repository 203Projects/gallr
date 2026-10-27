package com.gallr.shared.taste

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.galleryKey

/** What can lift a 덜 보기 hide: the visitor's saves, visits and followed galleries over one loaded catalogue. */
data class LiftSignals(
    val catalogue: List<Exhibition>,
    val bookmarkedIds: Set<String>,
    val visitedIds: Set<String>,
    /** Followed galleries by gallery key, with the gallery id where the follow has one. */
    val follows: Map<String, String?>,
)

/**
 * The hidden exhibitions that [now] brings back (spec 101 US3): a save, a visit or a follow of the gallery that
 * [before] did not have says the visitor wants the show after all. A follow is new by its gallery key, so a follow
 * that only gains its gallery id is the same follow.
 */
fun liftedDismissals(
    before: LiftSignals,
    now: LiftSignals,
    hiddenExhibitionIds: Set<String>,
): Set<String> {
    if (hiddenExhibitionIds.isEmpty()) return emptySet()
    val newSaves = now.bookmarkedIds - before.bookmarkedIds
    val newVisits = now.visitedIds - before.visitedIds
    val newFollows = now.follows.filterKeys { it !in before.follows }
    val newlyFollowedIds = newFollows.values.filterNotNullTo(mutableSetOf())
    return now.catalogue
        .filter { it.id in hiddenExhibitionIds }
        .filter { exhibition ->
            exhibition.id in newSaves ||
                exhibition.id in newVisits ||
                exhibition.galleryId?.let(newlyFollowedIds::contains) == true ||
                galleryKey(exhibition.venueNameKo, exhibition.venueNameEn) in newFollows
        }.mapTo(mutableSetOf(), Exhibition::id)
}
