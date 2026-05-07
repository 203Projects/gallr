package com.gallr.shared.notifications

import com.gallr.shared.repository.BookmarkRepository
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

class NotificationSyncService(
    private val scheduler: NotificationScheduler,
    private val exhibitionRepo: ExhibitionRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val languageRepo: LanguageRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    /**
     * Reconcile scheduled notifications against current bookmarks.
     *
     * @param triggeredByMutation true when called after a bookmark add/remove
     *   (re-arms the inactivity timer); false on cold-start reconcile where
     *   inactivity is preserved if pending but not re-armed if already fired
     *   (per spec Q2: only mutations re-arm).
     */
    suspend fun sync(triggeredByMutation: Boolean = false) {
        if (!scheduler.hasPermission()) return

        val bookmarkIds = bookmarkRepo.observeBookmarkedIds().first()
        if (bookmarkIds.isEmpty()) {
            scheduler.cancelAll()
            return
        }

        val language = languageRepo.observeLanguage().first()
        val now = clock.now()

        val all = exhibitionRepo.getExhibitions().getOrElse { emptyList() }
        val bookmarked = all.filter { it.id in bookmarkIds }
        val existing = scheduler.scheduledIds()

        val desired = buildSet {
            bookmarked.forEach { ex ->
                addAll(TriggerRules.computeTriggers(ex, now, timeZone, language))
            }
            // Inactivity: re-arm on mutation OR preserve if still pending.
            // On cold-start reconcile after an inactivity has already fired,
            // it won't be in `existing` and won't be re-scheduled.
            if (triggeredByMutation || INACTIVITY_NOTIFICATION_ID in existing) {
                add(TriggerRules.inactivitySpec(now, timeZone, language))
            }
        }
        val desiredById = desired.associateBy { it.id }

        for (id in existing - desiredById.keys) {
            scheduler.cancel(id)
        }
        for (spec in desired) {
            if (spec.id !in existing) {
                scheduler.schedule(spec)
            }
        }
    }
}
