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
 * What a signed-in account itself brought to the device: its saves as read from the server and its archive's visits
 * and follows as the server sent them. However and whenever it reaches the list, none of it is the visitor's new act.
 */
data class ArrivedHistory(
    val bookmarkedIds: Set<String> = emptySet(),
    val visitedIds: Set<String> = emptySet(),
    /** Followed galleries by gallery key. */
    val followKeys: Set<String> = emptySet(),
) {
    companion object {
        val NONE = ArrivedHistory()
    }
}

/**
 * The hidden exhibitions that [now] brings back (spec 101 US3): a save, a visit or a follow of the gallery that
 * [before] did not have, and that the account did not bring ([arrived]), says the visitor wants the show after all.
 * A follow is new by its gallery key, so a follow that only gains its gallery id is the same follow.
 */
fun liftedDismissals(
    before: LiftSignals,
    now: LiftSignals,
    hiddenExhibitionIds: Set<String>,
    arrived: ArrivedHistory = ArrivedHistory.NONE,
): Set<String> {
    if (hiddenExhibitionIds.isEmpty()) return emptySet()
    val newSaves = now.bookmarkedIds - before.bookmarkedIds - arrived.bookmarkedIds
    val newVisits = now.visitedIds - before.visitedIds - arrived.visitedIds
    val newFollows = now.follows.filterKeys { it !in before.follows && it !in arrived.followKeys }
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
