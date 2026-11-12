package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.observability.AppLog
import com.gallr.shared.repository.ExhibitionRepository
import com.gallr.shared.repository.LanguageRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

private val notificationSyncLog = AppLog.tagged("NotificationSyncService")

class NotificationSyncService(
    private val scheduler: NotificationScheduler,
    private val exhibitionRepo: ExhibitionRepository,
    private val languageRepo: LanguageRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val syncLock = Mutex()
    private var bookmarks: ReminderBookmarks? = null

    /**
     * Keeps reminders for [bookmarks] from now on, in the app's language: syncs again whenever the language changes.
     * Suspends until cancelled, so the composition that owns the visitor's bookmarks runs it for as long as it lives.
     * Before it starts and after it stops, a sync leaves every reminder as it is.
     */
    suspend fun follow(bookmarks: ReminderBookmarks) {
        this.bookmarks = bookmarks
        try {
            languageRepo
                .observeLanguage()
                .distinctUntilChanged()
                .drop(1)
                .collect { sync(triggeredByMutation = false) }
        } finally {
            if (this.bookmarks === bookmarks) this.bookmarks = null
        }
    }

    /**
     * Reconcile scheduled notifications against current bookmarks. Reminders whose time or wording changed (a new
     * closing date, a renamed show, another app language) are scheduled again; anything the service cannot know for
     * sure (the saves or the catalogue) leaves every reminder as it is.
     *
     * @param triggeredByMutation true when called after a bookmark add/remove
     *   (re-arms the inactivity timer); false on cold-start reconcile where
     *   inactivity is preserved if pending but not re-armed if already fired
     *   (per spec Q2: only mutations re-arm).
     */
    suspend fun sync(triggeredByMutation: Boolean = false) {
        syncLock.withLock { reconcile(triggeredByMutation) }
    }

    private suspend fun reconcile(triggeredByMutation: Boolean) {
        if (!scheduler.hasPermission()) return

        val bookmarkIds = bookmarks?.knownIds() ?: return
        if (bookmarkIds.isEmpty()) {
            scheduler.cancelAll()
            return
        }

        val catalogue =
            exhibitionRepo.getExhibitions().getOrElse { error ->
                // A failed read says nothing about the saved shows: keep every reminder until a read succeeds.
                notificationSyncLog.warn("read_catalogue", error)
                return
            }
        val language = languageRepo.observeLanguage().first()
        val now = clock.now()
        val pending = scheduler.pendingReminders().associateBy { it.id }
        val desired =
            desiredReminders(
                bookmarked = catalogue.filter { it.id in bookmarkIds },
                pendingInactivity = pending[INACTIVITY_NOTIFICATION_ID],
                triggeredByMutation = triggeredByMutation,
                now = now,
                language = language,
            ).associateBy { it.id }

        for (id in pending.keys - desired.keys) {
            scheduler.cancel(id)
        }
        for (spec in desired.values) {
            if (pending[spec.id] != spec) {
                scheduler.schedule(spec)
            }
        }
    }

    private fun desiredReminders(
        bookmarked: List<Exhibition>,
        pendingInactivity: NotificationSpec?,
        triggeredByMutation: Boolean,
        now: Instant,
        language: AppLanguage,
    ): List<NotificationSpec> =
        buildList {
            bookmarked.forEach { ex ->
                addAll(TriggerRules.computeTriggers(ex, now, timeZone, language))
            }
            // Inactivity: re-arm on mutation; otherwise keep a pending one on its day, in the current wording.
            // On cold-start reconcile after an inactivity has already fired, it is no longer pending and
            // won't be re-scheduled.
            val inactivity = TriggerRules.inactivitySpec(now, timeZone, language)
            when {
                triggeredByMutation -> add(inactivity)
                pendingInactivity != null -> add(inactivity.copy(triggerAt = pendingInactivity.triggerAt))
            }
        }
}
