# Data model: Curator POV notes

Decision identifiers (R1–R14) refer to [research.md](research.md).

## Server (Supabase)

### `content.curator_notes` (one row per exhibition)

| Column | Type | Rules |
|---|---|---|
| `exhibition_id` | text, primary key | Catalogue id (`exhibition_catalog_v2.id`); no foreign key so history survives catalogue edits |
| `approved_version_id` | uuid, null | The live version; references `curator_note_versions(id)`; set only by `approve_curator_note` |
| `auto_draft_blocked` | boolean, not null, default false | True after a decline; cleared by `request_curator_note_draft` (FR-010) |
| `last_source_hash` | text, null | Hash of the packet the newest version was drafted from (R3) |
| `updated_at` | timestamptz, not null | Bumped by every function that touches the row |

### `content.curator_note_versions`

| Column | Type | Rules |
|---|---|---|
| `id` | uuid, primary key, default `gen_random_uuid()` | |
| `exhibition_id` | text, not null | References `curator_notes(exhibition_id)` on delete cascade |
| `version_number` | integer, not null | 1-based per exhibition; unique (`exhibition_id`, `version_number`) |
| `status` | `content.curator_note_status`, not null | Enum: `draft`, `unusable`, `replaced`, `declined`, `approved`, `superseded` |
| `hook_ko`, `hook_en` | text, not null, default '' | One sentence; `char_length <= 120`; `hook_ko` must not be blank |
| `insights_ko`, `insights_en` | jsonb, not null, default '[]' | Array of `{ "text": string, "grounding": string[] }`; 2–4 entries for KO, 0 or 2–4 for EN; each `text` `<= 420` chars |
| `tip_ko`, `tip_en` | text, not null, default '' | One concrete viewing tip; `<= 240` chars |
| `unsupported_refs` | text[], not null, default '{}' | Sentence references `"<lang>:<section>:<index>"` an editor marked unsupported; approval refuses a non-empty array (FR-007) |
| `edited` | boolean, not null, default false | True once an editor changed any text field (SC-005 measurement) |
| `producer` | text, not null | `claude` for generated drafts |
| `model` | text, not null | The model that answered (`response.model`, R2) |
| `prompt_version` | text, not null | `PROMPT_VERSION` of the function (R13) |
| `source_fields` | text[], not null | Packet keys present when drafted (FR-003) |
| `source_hash` | text, not null | SHA-256 of the canonical packet JSON (R3) |
| `source_version_id` | uuid, null | `content.exhibition_versions.id` the packet came from, when known |
| `request_kind` | text, not null | `scheduled`, `single`, `regenerate` |
| `requested_by` | uuid, null | Staff user for `single` and `regenerate`; references `auth.users(id)` on delete set null |
| `generated_at` | timestamptz, not null | |
| `rejection_code` | text, null | Set with status `unusable`: `grounding_unknown_key`, `number_not_in_sources`, `language_mismatch`, `length_exceeded`, `insight_count`, `provider_refused`, `parse_failed` |
| `reviewed_by` | uuid, null | Approving or declining staff user |
| `reviewer_name_ko`, `reviewer_name_en` | text, null | Snapshot at approval (R8) |
| `decided_at` | timestamptz, null | Approval or decline time |
| `decline_reason` | text, null | `not_insightful`, `wrong_facts`, `tone`, `exhibition_not_suitable`, `other` |
| `decline_note` | text, null | `<= 500` chars |
| `approved_at` | timestamptz, null | Equals `decided_at` for approved rows; indexed for the home order |
| `created_at`, `updated_at` | timestamptz, not null | `updated_at` is the revision Admin sends back (R9) |

Indexes: (`exhibition_id`, `status`) and a partial unique index allowing at most one `draft` per
exhibition; (`approved_at desc`) where `status = 'approved'`.

Row-level security on both tables: enabled; all privileges revoked from `public`, `anon`,
`authenticated`, `service_role` (R14). Writes only through the functions below.

### `public.curator_notes_published` (view)

Approved versions joined to `public.exhibition_catalog_v2` on `exhibition_id`, so a note leaves the
view when its exhibition is unpublished or hidden (FR-017). Columns: `exhibition_id`,
`version_number`, `hook_ko`, `hook_en`, `insights_ko`, `insights_en`, `tip_ko`, `tip_en`,
`reviewer_name_ko`, `reviewer_name_en`, `approved_at`, `closing_date`. `select` granted to `anon` and
`authenticated`. Never includes `draft`, `declined`, `unusable`, `replaced` or `superseded` rows.

### `content.audit_log` rows written by this feature

`curator_note_drafted` (service), `curator_note_edited`, `curator_note_sentence_marked`,
`curator_note_approved`, `curator_note_declined`, `curator_note_regenerated`,
`curator_note_exported` (with `language` and `slide_count`). The payload never carries the note text.

### Status transitions

```text
                 ┌──────────── regenerate ────────────┐
                 │                                    ▼
 (none) ─draft─► draft ─approve─► approved ─new approval─► superseded
                 │ │  ▲                      ▲
                 │ │  └─ edit (same status)  │
                 │ └─decline─► declined      │
                 │                           │
                 └─(validation fails)─► unusable
 draft ─regenerate─► replaced   (the new draft takes its place)
```

- `approve` requires status `draft`, an empty `unsupported_refs`, a matching `expected_revision`,
  and a non-blank `hook_ko`, 2–4 KO insights and a KO tip; it sets the previous approved version to
  `superseded` and points `curator_notes.approved_version_id` at the new one.
- `decline` requires status `draft` and a matching revision; sets `auto_draft_blocked`.
- `regenerate` marks the current draft `replaced` (kept for comparison) and asks the function for a
  new one; works from `draft`, `declined` and `unusable`, and from `approved` when staff want a fresh
  take.
- A scheduled run never drafts an exhibition that has a `draft` or whose `auto_draft_blocked` is set,
  and skips one whose approved `source_hash` equals the current packet hash.

### Database functions (contracts in [contracts/database-functions.md](contracts/database-functions.md))

| Function | Caller | Purpose |
|---|---|---|
| `content_private.list_curator_note_candidates(p_limit)` | service role (Edge Function) | Qualifying exhibitions with their packets (R4) |
| `content_private.store_curator_note_draft(p_payload jsonb)` | service role | Validates and inserts a `draft` or `unusable` version, assigns `version_number`, updates `curator_notes` |
| `public.list_curator_note_queue()` | staff publisher | Open drafts, oldest first, with featured flag and prompt version |
| `public.get_curator_note(p_exhibition_id)` | staff publisher | All versions plus the current packet |
| `public.save_curator_note_edit(p_version_id, p_fields jsonb, p_expected_revision)` | staff publisher | Updates text fields of a `draft`; sets `edited` |
| `public.mark_curator_note_sentence(p_version_id, p_ref, p_unsupported, p_expected_revision)` | staff publisher | Adds or removes an unsupported reference |
| `public.approve_curator_note(p_version_id, p_expected_revision)` | staff publisher | Publishes (R8 snapshot) |
| `public.decline_curator_note(p_version_id, p_reason, p_note, p_expected_revision)` | staff publisher | Declines and blocks auto drafts |
| `public.request_curator_note_draft(p_exhibition_id)` | staff publisher | Clears the block, marks the open draft `replaced`, returns the packet so Admin can call the function in `single` mode |
| `public.record_curator_note_export(p_version_id, p_language, p_slide_count)` | staff publisher | Audit row for an export |
| `content_private.schedule_curator_note_drafts()` | `pg_cron` | Calls the Edge Function with the vault token (R1) |

Every staff function checks `content_private.has_staff_role('publisher')` and raises `42501`
`curator_note_not_staff` otherwise; revision mismatches raise `PT409` `curator_note_stale`;
approval with unsupported references raises `PT409` `curator_note_unsupported`.

## Shared (KMP, `shared/src/commonMain`)

### `com.gallr.shared.curatornote`

```kotlin
data class CuratorNote(
    val exhibitionId: String,
    val versionNumber: Int,
    val hookKo: String, val hookEn: String,
    val insightsKo: List<String>, val insightsEn: List<String>,
    val tipKo: String, val tipEn: String,
    val reviewerNameKo: String, val reviewerNameEn: String,
    val approvedAt: Instant,
) {
    fun hook(lang: AppLanguage): String          // EN falls back to KO when empty (FR-014)
    fun insights(lang: AppLanguage): List<String>
    fun tip(lang: AppLanguage): String
    fun reviewerName(lang: AppLanguage): String
    val firstInsight: (AppLanguage) -> String    // the one-line insight on the card
}

data class CuratorEyeCard(val note: CuratorNote, val exhibition: Exhibition)

fun curatorEyeCards(
    notes: List<CuratorNote>,
    catalogue: List<Exhibition>,
    today: LocalDate,
    limit: Int = CURATOR_EYE_LIMIT, // 10
): List<CuratorEyeCard>
```

Rules of `curatorEyeCards`: keep a note only when its exhibition is in `catalogue` and
`closingDate >= today`; one card per exhibition; order by `approvedAt` descending; take `limit`.

`HomeFeed` gains `curatorEye: List<CuratorEyeCard>` (empty when none) and `isEmpty` includes it.

### Data

- `CuratorNoteDto` (`@Serializable`, snake_case `@SerialName`, `insights_*` decoded as a list of
  objects with `text`; malformed rows are skipped with a logged count, never fail the page).
- `CuratorNoteApiClient(supabaseUrl, supabaseApiKey)`: `getPublished(limit)`,
  `getForExhibition(exhibitionId)`.
- `CuratorNoteRepository` (interface) + `CuratorNoteRepositoryImpl`: `suspend fun getPublishedNotes():
  Result<List<CuratorNote>>`, `suspend fun getNote(exhibitionId: String): Result<CuratorNote?>`;
  failure boundary with `runCatching`.

### Analytics

`DiscoveryKind.CURATOR_NOTE` (`curator_note`), used on `exhibition_impression` and
`exhibition_opened` with surface `featured` (the home tab).

## App (`composeApp/src/commonMain`)

- `HomeViewModel` takes `CuratorNoteRepository`; the feed combines the notes with the catalogue
  through `curatorEyeCards`; a notes failure logs and yields an empty section (the tab still loads).
- `CuratorNoteViewModel(exhibitionId, repository)`: `StateFlow<CuratorNoteUiState>` with
  `Loading`, `Ready(note: CuratorNote?)`, `Error`.
- `ui/tabs/home/CuratorEyePager.kt`: the section; `ui/detail/CuratorNoteBlock.kt`: the block.
- `ExhibitionDetailScreen` gains `scrollToNote: Boolean` and `curatorNote: CuratorNoteUiState`.
- Presentation strings in `HomePresentation.kt` / a `CuratorNotePresentation.kt`:
  section title `큐레이터의 시선` / `CURATOR'S EYE`, card line `<이름> 검수` / `Reviewed by <name>`,
  block label `큐레이터의 시선`, disclosure `AI 초안 · <이름> 검수` / `Drafted with AI, reviewed by <name>`.

## Admin (`admin/src`)

- `repositories/AdminCuratorNoteRepository.ts`: types `CuratorNoteQueueItem`, `CuratorNoteVersion`,
  `CuratorNoteFacts` (the packet), `CuratorNoteDecision`; methods `listQueue`, `get`, `saveEdit`,
  `markSentence`, `approve`, `decline`, `requestDraft` (RPC then the function call), `recordExport`;
  errors `CuratorNoteStaleError`, `CuratorNoteUnsupportedError`, `CuratorNoteNotStaffError`.
- `components/CuratorNotesWorkspace.tsx` (queue + review pane) and
  `components/CarouselExportDialog.tsx`.
- `export/carousel.ts` (slide layout and rendering), `export/carouselZip.ts` (archive), with the
  layout pure and tested apart from the canvas.

## Edge Function (`supabase/functions/draft-curator-notes`)

- `index.ts` (composition), `handler.ts` (auth, modes, budget, response codes), `drafting.ts`
  (packet, prompt, Claude call, validation), `backend.ts` (database and Claude I/O behind an
  interface), `prompt/system.md`, `prompt/examples.json`, `schema.ts` (Zod schema), tests for the
  handler, the validation rules and the packet hash.
