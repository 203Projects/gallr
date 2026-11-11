# Contract: shared diary API (shared/commonMain)

Public types and functions other modules call. Pure functions take `today`, a clock or a time zone as parameters;
nothing here reads `Clock.System` or the network directly except the repositories' injected clients.

## Domain types

```kotlin
// data/model/ExhibitionVisit.kt (extended; every new field has a default so stored JSON v1 still decodes)
@Serializable
data class ExhibitionVisit(
    val clientRecordId: String,
    val exhibitionId: String,
    val snapshot: ExhibitionVisitSnapshot,
    val createdAt: Instant,
    val visitDate: LocalDate? = null,
    @SerialName("feeling") val feelingWire: String? = null,   // mapped through VisitFeeling.fromWire
    @SerialName("tags") val tagWires: List<String> = emptyList(),
    val note: String? = null,
    val entryUpdatedAt: Instant? = null,
) {
    val feeling: VisitFeeling? get() = VisitFeeling.fromWire(feelingWire)
    val tags: List<VisitTag> get() = tagWires.mapNotNull(VisitTag::fromWire)
}

// diary/VisitFeeling.kt
enum class VisitFeeling(val wire: String) { LOVED("loved"), FINE("fine"), NOT_FOR_ME("not_for_me");
    fun label(language: AppLanguage): String     // 좋았어요 / Loved it, …
    companion object { fun fromWire(value: String?): VisitFeeling? } }

// diary/VisitTag.kt
enum class VisitTag(val wire: String, val moodTermId: String?) { THE_WORK("the_work", null), …,
    CALM_TO_LINGER("calm_to_linger", "mood:quiet-meditative"), PLAYFUL("playful", "mood:playful"),
    MOVING("moving", null), OVERWHELMING("overwhelming", "mood:monumental"), HEAVY("heavy", "mood:dark"), …;
    fun label(language: AppLanguage): String
    companion object { const val MAX_PER_ENTRY = 3; fun fromWire(value: String): VisitTag? } }

// diary/DiaryEntryDraft.kt — what the sheet saves
data class DiaryEntryDraft(
    val visitDate: LocalDate?, val feeling: VisitFeeling?, val tags: List<VisitTag>, val note: String?,
)

// diary/PublicNote.kt
enum class PublicNoteModeration { PENDING, APPROVED, REJECTED }
enum class ThoughtRejectionReason(val wire: String) { NOT_ABOUT_EXHIBITION, PROMOTIONAL, INAPPROPRIATE_LANGUAGE,
    PERSONAL_INFORMATION, OTHER }
data class OwnPublicNote(
    val exhibitionId: String, val thoughtId: String, val content: String, val moderation: PublicNoteModeration,
    val rejectionReason: ThoughtRejectionReason?, val requestedAt: Instant, val approvedAt: Instant?,
    val approvalSeen: Boolean,
)
enum class PublicNoteStatus { PRIVATE, SENDING, RETRY, IN_REVIEW, PUBLISHED, NOT_PUBLISHED }

// diary/Diary.kt
data class DiaryEntry(
    val exhibitionId: String, val snapshot: ExhibitionVisitSnapshot?,   // null only for a 감상 on an unknown show
    val visit: ExhibitionVisit?,                                        // null for a 감상 with no visit (undated)
    val publicNote: OwnPublicNote?, val noteStatus: PublicNoteStatus?,  // status only when there is a note
    val showApprovalLine: Boolean,
)
data class DiaryMonth(val year: Int, val month: Int, val entries: List<DiaryEntry>)
enum class DiaryFilter { ALL, PUBLISHED_NOTES }
data class Diary(val months: List<DiaryMonth>, val undated: List<DiaryEntry>, val endedSavedCount: Int,
                 val showEndedSavedStrip: Boolean, val isEmpty: Boolean)

// thoughts/PublicThought.kt
data class PublicThought(val id: String, val authorId: String, val content: String, val createdAt: Instant,
                         val authorDisplayName: String, val authorAvatarUrl: String?, val reportedByMe: Boolean)
enum class ThoughtReportReason(val wire: String) { INAPPROPRIATE("inappropriate"), PROMOTIONAL("promotional"),
    NOT_ABOUT_EXHIBITION("not_about_exhibition"), PERSONAL_INFORMATION("personal_information"), OTHER("other") }
data class HiddenAuthor(val userId: String, val displayName: String, val hiddenAt: Instant)
```

## Pure rules (each is a `shared/commonTest` file, written first)

```kotlin
fun canRecordVisit(exhibition: Exhibition, today: LocalDate): Boolean          // openingDate <= today (FR-002)

data class VisitDateOptions(val today: Boolean, val yesterday: Boolean, val earliest: LocalDate,
                            val latest: LocalDate, val preselected: LocalDate?)
fun visitDateOptions(opening: LocalDate, closing: LocalDate, today: LocalDate): VisitDateOptions?
    // null when opening > today; running: today offered and preselected; closed: no preselection,
    // today not offered; yesterday only inside [opening, closing]; latest = min(closing, today)

fun mergeEntry(account: ExhibitionVisit, device: ExhibitionVisit): ExhibitionVisit
    // fill-only-empty; differing notes joined "account\n\ndevice"; entryUpdatedAt = max; same case table as pgTAP 079

fun applyEntrySave(existing: ExhibitionVisit?, exhibition: Exhibition, draft: DiaryEntryDraft,
                   now: Instant, recordId: String): ExhibitionVisit   // creates or replaces the four fields

fun diaryNoteStatus(entryNote: String?, publicNote: OwnPublicNote?, pendingNoteMutation: PendingNoteMutation?,
                    syncFailed: Boolean, sessionRefreshPending: Boolean): PublicNoteStatus?
    // null when there is no note and no public note; SENDING while a publish/unpublish mutation is queued and either
    // no failure has happened or the session refresh is pending; RETRY only on a failure outside a pending refresh

fun buildDiary(visits: List<ExhibitionVisit>, publicNotes: List<OwnPublicNote>, catalogue: List<Exhibition>,
               savedIds: Set<String>, today: LocalDate, filter: DiaryFilter, dismissedEndedSavedCount: Int,
               pendingNoteMutations: Map<String, PendingNoteMutation>, syncFailed: Boolean,
               sessionRefreshPending: Boolean): Diary

fun visibleThoughts(thoughts: List<PublicThought>, hiddenAuthorIds: Set<String>,
                    currentUserId: String?): List<PublicThought>   // drops hidden authors, never the reader's own
```

Guarantees for `buildDiary` (tests): months by visit date newest first, entries within a month newest first then by
localized name; 날짜 미정 last, by record time newest first; a public note with no visit becomes an undated entry
whose snapshot comes from the catalogue (or null); every visit appears whatever the LIST tab state is (SC-008: no
filter input from the LIST tab exists); `endedSavedCount` counts saved ids with `closingDate < today` and no entry;
`showEndedSavedStrip = endedSavedCount > 0 && endedSavedCount > dismissedEndedSavedCount`; `PUBLISHED_NOTES` keeps
entries whose status is anything but `PRIVATE` or null; `showApprovalLine` only for `APPROVED` and `!approvalSeen`.

## Account sync

```kotlin
sealed interface MyGallrAccountMutation {
    … existing AddVisit, RemoveVisit, FollowGallery, UnfollowGallery, AcknowledgeGallery …
    data class UpdateVisit(override val mutationId: String, val visit: ExhibitionVisit) : MyGallrAccountMutation
    data class MergeVisit(override val mutationId: String, val visit: ExhibitionVisit) : MyGallrAccountMutation
    data class DeleteEntry(override val mutationId: String, val exhibitionId: String) : MyGallrAccountMutation
    data class PublishNote(override val mutationId: String, val exhibitionId: String) : MyGallrAccountMutation
    data class UnpublishNote(override val mutationId: String, val exhibitionId: String) : MyGallrAccountMutation
    data class AcknowledgeNoteApproval(override val mutationId: String, val exhibitionId: String) : MyGallrAccountMutation
}
data class MyGallrAccountArchive(val revision: Long, val visits: List<ExhibitionVisit>,
    val followedGalleries: List<FollowedGallery>, val publicNotes: List<OwnPublicNote> = emptyList())
```

Stored kinds (`toStored`/`toDomain`, `MyGallrAccountSync.kt:484-537`): `update_visit`, `merge_visit`, `delete_entry`,
`publish_note`, `unpublish_note`, `acknowledge_note_approval`. Wire DTOs (`MyGallrAccountApiClient.kt`) send the new
visit keys `visit_date`, `feeling`, `tags`, `note`, `entry_updated_at` and read them back with defaults; the response
DTO gains `public_notes` with a default empty list. A wire test asserts the JSON for each kind against
`contracts/database.md`.

Local reducer (`apply`, `:418-468`): `UpdateVisit` replaces the four fields when newer; `MergeVisit` calls
`mergeEntry`; `DeleteEntry` removes the visit and the local public note; `PublishNote` marks the note pending
locally; `UnpublishNote` removes it; `AcknowledgeNoteApproval` sets `approvalSeen`.

## Repositories

```kotlin
interface VisitRepository {                         // existing three methods unchanged
    fun observeVisits(): Flow<List<ExhibitionVisit>>
    suspend fun addVisits(visits: List<ExhibitionVisit>)
    suspend fun removeVisit(exhibitionId: String)
    /** Creates or replaces the entry; a guest writes the device store, an account queues UpdateVisit. */
    suspend fun saveEntry(visit: ExhibitionVisit)
    /** Removes the entry and, for an account, its public note (DeleteEntry). */
    suspend fun deleteEntry(exhibitionId: String)
}

/** Signed-in only: the account's own public notes and the actions on them. Backed by the sync coordinator. */
interface PublicNoteRepository {
    fun observeOwnNotes(): Flow<List<OwnPublicNote>>
    fun observePendingNoteMutations(): Flow<Map<String, PendingNoteMutation>>
    suspend fun publish(exhibitionId: String)
    suspend fun unpublish(exhibitionId: String)
    suspend fun acknowledgeApproval(exhibitionId: String)
}

/** Reader side of 감상. Suspending calls may throw; callers turn failures into UI state and logs. */
interface ThoughtRepository {
    suspend fun exhibitionThoughts(exhibitionId: String, limit: Int = 20): List<PublicThought>  // RPC
    suspend fun report(thoughtId: String, reason: ThoughtReportReason)                         // RPC
}
class ThoughtReportAlreadySentException : Exception()   // PT409 thought_report_exists, shown as 신고함

interface HiddenThoughtAuthorRepository {
    val hiddenAuthors: Flow<List<HiddenAuthor>>       // malformed storage → empty, logs hidden_authors_read_failed
    suspend fun hide(author: HiddenAuthor)
    suspend fun unhide(userId: String)
}

interface DiaryDeviceStateRepository {
    val dismissedEndedSavedCount: Flow<Int>
    suspend fun dismissEndedSavedStrip(count: Int)
}
```

DataStore keys (private module-level constants, persisted names fixed): `HIDDEN_THOUGHT_AUTHORS_KEY =
stringPreferencesKey("hidden_thought_authors_v1")`, `DIARY_ENDED_SAVED_DISMISSED_COUNT_KEY =
intPreferencesKey("diary_ended_saved_dismissed_count_v1")`. Existing keys keep their names; no schema version bump.

Removed from `ThoughtRepository`: `getThoughtsForExhibition`, `getUserThoughts`, `submitThought`, `updateThought`,
`deleteThought`, `getUserThoughtForExhibition`, `getUserThoughtCount`, `getPendingThoughts`, `approveThought`,
`rejectThought` (`ThoughtRepository.kt:5-34`); `Thought`/`ThoughtDto` become `PublicThought` and its DTO.
`Profile.isAdmin` is no longer read by the app.

## Recommender

No signature change: `RecommendationContext.visits` already carries `ExhibitionVisit`
(`ExhibitionRecommendation.kt:128-149`). New private constants beside the existing ones:
`DISLIKED_VISIT_PENALTY_SHARE = 0.5` and the tag map read from `VisitTag.moodTermId`. New evidence
`RecommendationEvidence.VisitedMood(anchor, term)` (source `VISITED`, contribution as `ChosenTerm`).

Guarantees (each a test):
1. With no feelings recorded, output is identical to today's for every existing test and fixture.
2. A `LOVED` visit and a save of the same show give equal basis points to every candidate.
3. A `FINE` or feeling-less visit gives today's visited contributions.
4. A `NOT_FOR_ME` visit adds no evidence; per shared reviewed term and credited artist it subtracts
   `min(0.25 × (dismissCount + 0.5 × dislikedCount), 0.5 × base)` in place of today's dismissal-only term; one such
   visit alone costs less than one 덜 보기 on the same id; score floored at zero; `hasPersonalEvidence` unchanged.
5. Tags on a `LOVED` visit with a mood term add `VisitedMood` evidence like a picked term (0.30, each further one half
   the step before); tags on other feelings add nothing; reasons never contain a feeling or tag label.
6. Determinism and input-order independence hold.

## Logging

`AppLog.tagged("Diary")`: `diary_entry_save_failed`, `diary_entry_delete_failed`, `diary_note_publish_failed`,
`diary_note_unpublish_failed`, `diary_approval_ack_failed`; `AppLog.tagged("Thoughts")`: `thought_list_failed`,
`thought_report_failed`; `AppLog.tagged("HiddenAuthors")`: `hidden_authors_read_failed`,
`hidden_author_write_failed`. Exception type only, never ids or text (FR-034).
