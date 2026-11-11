# Tasks: Visit diary with 감상 joined in

**Input**: Design documents from `/specs/103-visit-diary/`
**Prerequisites**: plan.md (slices 1–10, deploy order), spec.md (US1–US6, owner decisions 1–9), research.md (§1–§6,
P1–P12), data-model.md, contracts/database.md, contracts/shared-diary-api.md, contracts/diary-ui.md,
contracts/admin-thoughts.md, quickstart.md

**Tests**: Required (constitution II, Test-First). In every slice the test task is written and **seen failing** before
the implementation task that follows it; an implementation task names the test it makes pass.

**Organization**: the phases follow the plan's ten slices in their order. Slice 1 (M1) and slices 3–4 (M4 and the
shared diary core) are foundational and carry no story label; slice 2 is US5, slice 5 US1, slice 6 US2, slice 7 US4,
slice 8 US3, slice 9 US6, slice 10 and the release are Polish and Rollout. US1–US5 are P1; US6 is P2 and the only story
that may move out of 1.16 (see MVP / cut line).

Paths: `shared/` = `shared/src/commonMain/kotlin/com/gallr/shared/`, `sharedTest/` =
`shared/src/commonTest/kotlin/com/gallr/shared/`, `app/` = `composeApp/src/commonMain/kotlin/com/gallr/app/`,
`appTest/` = `composeApp/src/commonTest/kotlin/com/gallr/app/`, `iosUiTest/` =
`composeApp/src/iosTest/kotlin/com/gallr/app/`, `admin/` = `admin/src/`, `migrations/` = `supabase/migrations/`,
`pgtap/` = `supabase/tests/database/`. Every path below exists on `origin/develop` at `6d737dde` unless marked
**NEW**; files to delete are marked **DELETE**.

In a worktree, prefix Gradle with `ANDROID_HOME=$HOME/Library/Android/sdk`; run Node tools with
`npx -p node@22.23.1 …` when the host Node differs from `.node-version`. Run pgTAP from an isolated scratch Supabase
workdir (see `.github/workflows/database-tests.yml`); suite 057 is known to fail in a local replay for an unrelated
default-privileges reason, so compare against `develop` before treating it as a regression.

**OWNER STEP** tasks are done by the owner (or by an agent only on the owner's explicit word in that session): applying
migrations to `gallr-staging` or `gallr-korea`, deploying Admin, store consoles, release and submission. No agent
applies a hosted migration, deploys, or merges a pull request on its own.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: touches different files from every other open task in its phase, and depends on no open task in it
- **[USn]**: the user story the task serves (story phases only)

## Migration versions and pgTAP numbers (checked 2026-11-11)

| Check | Result |
|---|---|
| Newest migration on `origin/develop` | `20261111051000_security_storage_boundaries.sql` |
| Newest pgTAP suite on `origin/develop` | `075_security_storage_boundaries.test.sql` |
| Recently pushed branches adding a migration or suite (`fix/*`, `perf/*`, `103-*`, read with `git diff --diff-filter=A`) | none |
| Open pull requests | none |
| Staging `gallr-staging` recorded (read-only `supabase_migrations.schema_migrations`) | latest `20261104130000`, 122 recorded, none at or after `20261111051000` |
| Production `gallr-korea` recorded (same read) | latest `20261104130000`, 122 recorded, none at or after `20261111051000` |
| Lineage validator (Node 22.23.1) | PASS (123 files, 7 recovered versions) |

So M1–M4 (`20261112100000`, `…100100`, `…100200`, `…100300`) and suites 076–079 are free and sort after everything on
`develop` and on both hosted projects. The IDs do not need to move. `20261111051000` is on `develop` and applied on
neither project; it belongs to its own plan and applies first by version order (see T127). If anything with a version
at or after `20261112100000`, or a suite numbered 076 or higher, lands on `develop` before T004, renumber in plan.md,
contracts/database.md and this file before writing the first migration file, never after a hosted apply.

---

## Phase 1: Setup

- [ ] T001 Record the baseline on the working branch before any change, under a "Baseline" heading at the top of this
  file: test counts from `./gradlew shared:testAndroidHostTest composeApp:testAndroidHostTest`, the lineage validator
  result, `(cd admin && npm test)` and `(cd web && npm test)` pass/fail counts (no code change). Done when the table is
  committed.
- [ ] T002 Re-run the version check above (local `ls supabase/migrations supabase/tests/database`, the read-only hosted
  lists, open pull requests) on the day the database lane starts; record the date and result here. Done when it
  confirms the IDs are still free, or the renumbering rule above has been applied.

---

## Phase 2: Foundational — slice 1, M1 thought moderation status (blocks US3, US4, US5)

**Purpose**: a moderation status on `public.thoughts` kept in step with `is_approved`, and the trigger that returns any
edited text to review (fixes the live defect, research §1; FR-019, FR-030, SC-005 1.15 path). Harmless for 1.15.

- [ ] T003 Run `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` and
  `node --test scripts/staging-rehearsal/lib/validate-migration-lineage.test.mjs`; both pass before any SQL is written.
- [ ] T004 Write `pgtap/076_thought_moderation_status.test.sql` (**NEW**) per contracts/database.md M1: backfill
  (approved rows → `approved`, `approved_at`, `requested_at`); an author INSERT with `is_approved true` or
  `moderation_status 'approved'` lands `pending`; an author UPDATE of an approved row's `content` returns it to
  `pending`, `is_approved false`, and it leaves the anon read (SC-005, 1.15 path); an author UPDATE that only sets
  `approved` changes nothing; `approval_seen_at` may go null → now only on an approved row; a staff `admin` approving
  another person's pending row through the table (1.15 queue) lands `approved` with `decided_by`; the same staff member
  editing their own row is an author; a `publisher` has no legacy path; the check constraints hold after every case;
  anon and authenticated grants unchanged. Run it on a scratch stack and see it fail (no columns yet).
- [ ] T005 Write `migrations/20261112100000_thought_moderation_status.sql` (**NEW**): the seven columns, backfill,
  `is_approved` not null default false, the three checks, the two partial indexes, and `create or replace` of
  `content_private.enforce_thought_moderation()` with the check order moderating → legacy staff path → author path →
  null uid (contracts/database.md). Idempotent, `set search_path = ''`, schema-qualified. Make T004 pass; re-run the
  lineage validator; `pgtap/001_cms_foundation.test.sql` still passes.
- [ ] T006 Gate: clean replay of every migration plus all pgTAP suites on the scratch stack; record the result in the
  pull request. Done when 001–076 pass (057 compared with `develop`).
- [ ] T007 **OWNER STEP** Apply M1 `20261112100000` to **staging** after T005 merges: lineage validator, a dry run that
  proposes only the expected version(s) (see T127 about `20261111051000`), then apply; confirm with a read-only
  `select version from supabase_migrations.schema_migrations order by version desc limit 3`.

**Checkpoint**: on staging, an approved 감상 edited through the table by its author leaves the public read (quickstart
"Staging" step 1, last sentence). 1.15 behaves as before.

---

## Phase 3: User Story 5 - Staff moderate 감상 in Admin (Priority: P1) — slice 2

**Goal**: reports table and reader/staff RPCs (M2), the Admin 감상 section, then M3 retiring the mobile staff path on
the day the section goes live (owner decision 8). Reported list oldest open report first (owner decision 9).

**Independent Test** (quickstart US5): publish a note → it appears under 검토 대기 in Admin; reject with 광고·홍보 → it
leaves the list and the author's row reads `rejected` with that reason; report an approved 감상 → it appears under
신고됨 with the reason and first-report time; take it down → it leaves the exhibition page; the oldest report is on
top; no feeling, tag or visit date anywhere on the page.

### Tests for User Story 5

- [ ] T008 [P] [US5] Write `pgtap/077_thought_reports_and_review.test.sql` (**NEW**) per contracts/database.md M2:
  `list_exhibition_thoughts` excludes a 감상 only for the reader with an open report on it (reader A reports, reader B
  still sees it; three reporters, a fourth reader still sees it — SC-004); dismiss returns it to the reporter with
  `reported_by_me`; a re-report of the same approved text is refused (`thought_report_exists`), of re-approved text
  accepted; own and non-public refused; anon cannot report; publisher and admin can list and decide, contributor and
  plain authenticated get `thought_not_staff`; staleness guards (`thought_review_stale`, `thought_reports_stale`);
  `list_reported_thoughts` orders by oldest open report first (owner decision 9); take-down hides it from everyone and
  the author's row is `rejected` with the reason; audit rows carry no content; queue payloads have no `feeling`,
  `tags`, `visit_date` or `note` key; reporter deletion and 감상 deletion cascade reports. See it fail.
- [ ] T009 [P] [US5] Write `pgtap/078_retire_mobile_thought_moderation.test.sql` (**NEW**): an active `admin` staff
  member can no longer select, update or delete another person's pending or approved 감상 through the table; authors
  keep all three on their own rows; the 1.15 author flow (insert, read own pending, delete own) passes. See it fail.
- [ ] T010 [P] [US5] Write `admin/repositories/SupabaseAdminThoughtRepository.test.ts` (**NEW**): RPC names and
  argument keys for `list_thought_review_queue`, `decide_thought`, `list_reported_thoughts`,
  `resolve_thought_reports`; every error mapping in contracts/admin-thoughts.md; malformed payloads, including any
  `feeling`, `tags`, `visit_date` or `note` key, raise `MalformedAdminThoughtPayloadError`. See it fail.
- [ ] T011 [P] [US5] Write `admin/repositories/InMemoryAdminThoughtRepository.test.ts` (**NEW**): same contract on the
  fixture repository (ordering, staleness, transitions). See it fail.
- [ ] T012 [P] [US5] Write `admin/components/ThoughtModerationWorkspace.test.tsx` (**NEW**): pending ordered by
  `requestedAt`, reported by `firstReportedAt`, both oldest first; reject and take-down require a reason; `<h>시간 남음`
  then `48시간 경과`; stale result refreshes with its message; KO and EN copy; no private field in the DOM. See it fail.
- [ ] T013 [P] [US5] Extend `admin/domain.test.ts` (slug `thoughts` ↔ `Thoughts`), `admin/components/PrimaryNavigation.test.tsx`
  (visible to publisher and admin, hidden from contributor) and `admin/App.test.tsx` (deep link `?section=thoughts`,
  contributor redirected to Exhibitions). See them fail.

### Implementation for User Story 5

- [ ] T014 [US5] Write `migrations/20261112100100_thought_reports_and_review.sql` (**NEW**): `public.thought_reports`
  with checks, unique and open indexes, RLS on with no policies and no client grants; `list_exhibition_thoughts`,
  `report_thought`; staff impls under `has_staff_role('publisher')` that set `gallr.thought_moderation` locally and
  write `content.audit_log` without text; public wrappers and grants per the contract. Lineage validator first; make
  T008 pass; 076 still passes.
- [ ] T015 [US5] Write `migrations/20261112100200_retire_mobile_thought_moderation.sql` (**NEW**): re-create the three
  `public.thoughts` authenticated policies without staff clauses. Make T009 pass; 076 and 077 still pass. The file
  merges with this phase but is applied only right after the Admin deploy (staging T024 → T025, production
  T131 → T132).
- [ ] T016 [P] [US5] Update `supabase/functions/delete-account/README.md` (lines 23–25) to list
  `content_private.my_gallr_archives`, `my_gallr_visits`, `my_gallr_followed_galleries`,
  `my_gallr_mutation_receipts` and `public.thought_reports` among the cascades. Done when every table named matches a
  migration.
- [ ] T017 [US5] Add `"Thoughts"` to `AdminSection` and slug `thoughts` in `admin/domain.ts`, `navigation.thoughts`
  (`Visitor notes` / `감상`) in `admin/i18n.tsx`, and the `thoughts.*` EN and KO messages from
  contracts/admin-thoughts.md in `admin/staff-i18n-messages.ts`; make the `domain.test.ts` part of T013 pass.
- [ ] T018 [US5] Add `admin/repositories/AdminThoughtRepository.ts`, `admin/repositories/SupabaseAdminThoughtRepository.ts`
  and `admin/repositories/InMemoryAdminThoughtRepository.ts` (all **NEW**, modelled on the route repositories); make
  T010 and T011 pass.
- [ ] T019 [US5] Build `admin/components/ThoughtModerationWorkspace.tsx` (**NEW**, modelled on
  `RouteModerationWorkspace.tsx`; preview beside the list, destructive action subordinate); make T012 pass.
- [ ] T020 [US5] Wire the section into `admin/components/PrimaryNavigation.tsx` and `admin/App.tsx` (initial-section and
  `handleNavigation` guards, repository construction, fixture mode); make the rest of T013 pass.
- [ ] T021 [P] [US5] Add the deploy note to `admin/AGENTS.md`: this Admin needs M2 `20261112100100` applied first;
  M3 `20261112100200` is applied right after this Admin reaches production, the same day (owner decision 8).
- [ ] T022 [US5] Gates: pgTAP replay 001–078; `(cd admin && npm ci && npm run typecheck && npm test && npm run build)`
  with Node 22.23.1. Done when all pass and the counts are in the pull request.
- [ ] T023 [US5] **OWNER STEP** Apply M2 `20261112100100` to **staging** (after T007): lineage validator, dry run, apply,
  read-only confirmation.
- [ ] T024 [US5] **OWNER STEP** Deploy Admin as a Vercel **Preview** against staging and check quickstart "Staging" step 3:
  `?section=thoughts` as publisher shows 검토 대기; as contributor the section is hidden.
- [ ] T025 [US5] **OWNER STEP** Apply M3 `20261112100200` to **staging** right after T024 passes (owner decision 8).
- [ ] T026 [US5] Device check, **Android** (owner, or an agent with an emulator): against staging after T025, a 1.15
  build signed in as a staff `admin` can no longer approve, read or delete another person's 감상 from the in-app
  queue (US5 scenario 11); a 1.15 author can still post, see their own 검토 중 and delete their own. The 1.16 checks of
  scenario 8 (no admin queue in Profile) happen in T081–T082. Session refresh pending: not applicable (no 1.16 code yet).
- [ ] T027 [US5] Device check, **iOS** (owner, or an agent with a simulator): the same as T026 with a 1.15 build
  against staging.

**Checkpoint**: staging has M1–M3 and the Admin Preview moderates 감상; staff on 1.15 can no longer moderate in the app.
US5 is testable on its own from the Admin side (quickstart US5 except the 1.16 Profile line).

---

## Phase 4: Foundational — slices 3–4, diary sync (M4) and the shared diary core (blocks US1, US2, US3)

**Purpose**: diary columns and six mutation kinds on the existing account sync (M4); the shared domain types,
vocabularies, pure rules, repositories and DTOs; the two device-only stores constructed once at the composition roots;
DESIGN.md sections that FR-032 requires before any screen. Slice 4 may start against contracts/database.md while slice
3 is in review (plan: "3's contract, not its deploy").

### Slice 3 — M4

- [ ] T028 Write `pgtap/079_visit_diary_sync.test.sql` (**NEW**) per contracts/database.md M4: each new kind
  (`update_visit`, `merge_visit`, `delete_entry`, `publish_note`, `unpublish_note`, `acknowledge_note_approval`);
  last-write-wins both directions and on ties (ties keep the server row); the merge case table shared with T034
  (account empty, device empty, both equal, both differ, tags, 280 + 280 → 562); `publish_note` copies the note and
  lands `pending` (SC-005, 1.16 path); an approved row edited through `publish_note` leaves the anon read at once;
  publish with no visit or no note is a no-op that still records a receipt; `update_visit` with a null note deletes
  the 감상; `delete_entry` removes both; `remove_visit` leaves the 감상; `add_visit` still `do nothing` on conflict;
  the archive carries the new visit keys and `public_notes` (including a 감상 with no visit); anon and authenticated
  still cannot select `my_gallr_visits`; no staff RPC returns diary fields; deleting the auth user cascades visits,
  thoughts and reports; a malformed new mutation raises `visit_invalid`, a semantic conflict does not. See it fail.
- [ ] T029 Write `migrations/20261112100300_visit_diary_sync.sql` (**NEW**): the five columns and checks with
  `content_private.visit_tags_valid(text[])`; `create or replace` of `content_private.my_gallr_archive_json(uuid)`
  and `content_private.sync_my_gallr_archive_impl(jsonb)` keeping every existing branch's behaviour. Lineage validator
  first; make T028 pass; `pgtap/034_my_gallr_account_archive.test.sql` and 076–078 still pass.
- [ ] T030 Gate: clean replay plus every pgTAP suite (001–079). Done when all pass (057 compared with `develop`).
- [ ] T031 **OWNER STEP** Apply M4 `20261112100300` to **staging** after T025: lineage validator, dry run, apply,
  read-only confirmation; then quickstart "Staging" step 2 with a 1.15 build (record a visit, follow a gallery, open
  MY → nothing changed).

### Slice 4 — shared diary core: tests first

- [ ] T032 [P] Write `sharedTest/diary/VisitVocabularyTest.kt` (**NEW**): `VisitFeeling` and `VisitTag` wire ids and
  KO/EN labels match data-model.md; `fromWire` of an unknown or null value is null; `MAX_PER_ENTRY == 3`; exactly four
  tags carry a mood term (`calm_to_linger`, `playful`, `overwhelming`, `heavy`) and `moving` carries none.
- [ ] T033 [P] Write `sharedTest/diary/VisitEligibilityTest.kt` (**NEW**): `canRecordVisit` true from the opening day
  (running and closed), false before; `visitDateOptions` for a running show (오늘 offered and preselected, 어제 only
  inside the run), a closed show (no preselection, no 오늘, `latest = closing`), a show opening today (오늘 only), and
  `null` for an upcoming show.
- [ ] T034 [P] Write `sharedTest/diary/EntryMergeTest.kt` (**NEW**): `mergeEntry` over the same case table as T028
  (fill only empty fields, differing notes joined `account\n\ndevice`, tags kept from the account when non-empty,
  `entryUpdatedAt` = max, 280 + 280 within 562).
- [ ] T035 [P] Write `sharedTest/diary/EntrySaveTest.kt` (**NEW**): `applyEntrySave` creates an entry with snapshot,
  record id and `createdAt` on first save, replaces only the four diary fields and `entryUpdatedAt` on later saves,
  trims trailing whitespace and stores a blank note as null, refuses more than three or duplicate tags.
- [ ] T036 [P] Write `sharedTest/diary/PublicNoteStatusTest.kt` (**NEW**): `diaryNoteStatus` → null without note and
  public note; `PRIVATE`; `SENDING` while a publish or unpublish is queued, also after a failure while the session
  refresh is pending; `RETRY` only on a failure outside a pending refresh; `IN_REVIEW`, `PUBLISHED`, `NOT_PUBLISHED`
  with reason from the moderation states.
- [ ] T037 [P] Write `sharedTest/diary/DiaryTest.kt` (**NEW**): `buildDiary` guarantees in contracts/shared-diary-api.md:
  months newest first, entries in a month newest first then by localized name; 날짜 미정 last by record time; a public
  note with no visit becomes an undated entry (snapshot from the catalogue, or null); every visit listed with no LIST
  input (SC-008); `endedSavedCount` and `showEndedSavedStrip` against the dismissed count; `PUBLISHED_NOTES` filter;
  `showApprovalLine` only for approved and unseen; `isEmpty`.
- [ ] T038 [P] Extend `sharedTest/data/model/ExhibitionVisitTest.kt`: a stored v1 JSON payload without the new keys
  decodes with defaults; an unknown feeling or tag wire decodes to none instead of failing the payload.
- [ ] T039 [P] Extend `sharedTest/repository/DataStoreVisitRepositoryTest.kt`: guest `saveEntry` creates and replaces,
  `deleteEntry` removes, the existing `my_gallr_exhibition_visits_v1` payload is still read.
- [ ] T040 [P] Extend `sharedTest/repository/MyGallrAccountSyncTest.kt`: the six mutation kinds round-trip through
  `toStored`/`toDomain`; the reducer `apply` follows the server rules (`UpdateVisit` newer wins, `MergeVisit` uses
  `mergeEntry`, `DeleteEntry` drops visit and local public note, `PublishNote` marks pending, `UnpublishNote` removes,
  `AcknowledgeNoteApproval` sets seen); `mergeGuestRecords` sends `MergeVisit`; while a session refresh is pending the
  flush fails without a token, keeps the outbox and the archive, and guest records are not merged twice.
- [ ] T041 [P] Extend `sharedTest/data/network/MyGallrAccountApiClientTest.kt`: request JSON for each new kind matches
  contracts/database.md (keys `visit_date`, `feeling`, `tags`, `note`, `entry_updated_at`); a response with
  `public_notes` and the new visit keys decodes; a response without them decodes to defaults.
- [ ] T042 [P] Write `sharedTest/repository/PublicNoteRepositoryTest.kt` (**NEW**): own notes come from the archive's
  `publicNotes`; publish, unpublish and acknowledge enqueue the matching mutations; pending note mutations are
  observable per exhibition.
- [ ] T043 [P] Write `sharedTest/repository/DataStoreDiaryDeviceStateRepositoryTest.kt` (**NEW**) and
  `sharedTest/repository/DataStoreHiddenThoughtAuthorRepositoryTest.kt` (**NEW**) against an in-memory preferences
  DataStore: round trips; keys exactly `diary_ended_saved_dismissed_count_v1` and `hidden_thought_authors_v1`; a
  malformed stored list reads as empty and logs `hidden_authors_read_failed` (exception type only).

### Slice 4 — shared diary core: implementation

- [ ] T044 Add `shared/diary/VisitFeeling.kt` and `shared/diary/VisitTag.kt` (**NEW**); make T032 pass.
- [ ] T045 Extend `shared/data/model/ExhibitionVisit.kt` with `visitDate`, `feelingWire`, `tagWires`, `note`,
  `entryUpdatedAt` (defaults, derived `feeling`/`tags`); make T038 pass and keep every existing test green.
- [ ] T046 Add `shared/diary/VisitEligibility.kt` (**NEW**: `canRecordVisit`, `VisitDateOptions`,
  `visitDateOptions`); make T033 pass.
- [ ] T047 Add `shared/diary/EntryMerge.kt` (**NEW**) and `shared/diary/DiaryEntryDraft.kt` (**NEW**: draft and
  `applyEntrySave`); make T034 and T035 pass.
- [ ] T048 Add `shared/diary/PublicNote.kt` (**NEW**: `OwnPublicNote`, `PublicNoteModeration`,
  `ThoughtRejectionReason`, `PublicNoteStatus`, `PendingNoteMutation`, `diaryNoteStatus`); make T036 pass.
- [ ] T049 Add `shared/diary/Diary.kt` (**NEW**: `DiaryEntry`, `DiaryMonth`, `DiaryFilter`, `Diary`, `buildDiary`);
  make T037 pass.
- [ ] T050 Extend `shared/data/model/MyGallrAccountArchive.kt` (`publicNotes`), `shared/repository/MyGallrAccountSync.kt`
  (six mutation variants, stored kinds, reducer, `MergeVisit` in `mergeGuestRecords`) and
  `shared/data/network/MyGallrAccountApiClient.kt` (request and response DTOs); make T040 and T041 pass.
- [ ] T051 Extend `shared/repository/VisitRepository.kt` with `saveEntry` and `deleteEntry`, implement them in
  `shared/repository/DataStoreVisitRepository.kt` (guest) and in the account-aware visit repository in
  `shared/repository/MyGallrAccountSync.kt` (`UpdateVisit`, `DeleteEntry`); make T039 pass.
- [ ] T052 Add `shared/repository/PublicNoteRepository.kt` (**NEW**, interface and coordinator-backed implementation);
  make T042 pass.
- [ ] T053 Add `shared/repository/DiaryDeviceStateRepository.kt` and `shared/repository/DataStoreDiaryDeviceStateRepository.kt`
  (**NEW**), `shared/thoughts/HiddenAuthor.kt` (**NEW**, the `HiddenAuthor` type), and
  `shared/repository/HiddenThoughtAuthorRepository.kt` and `shared/repository/DataStoreHiddenThoughtAuthorRepository.kt`
  (**NEW**) with private module-level keys and `AppLog.tagged("HiddenAuthors")`; make T043 pass. (Moved here from
  slice 7 so the composition roots change once; see Dependencies.)
- [ ] T054 Construct `DataStoreDiaryDeviceStateRepository` and `DataStoreHiddenThoughtAuthorRepository` beside
  `DataStoreTastePreferenceRepository` in `androidApp/src/main/kotlin/com/gallr/app/MainActivity.kt` and
  `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`, and add matching parameters to `App(...)` in
  `app/App.kt` (unused until US2 and US4). Done when both platforms build.
- [ ] T055 [P] Update `DESIGN.md` before any diary screen (FR-032): Component Patterns sections **Diary row on the
  exhibition page**, **Diary entry sheet**, **MY 일기**, **감상 card menu and report sheet**, **Hidden people
  (Settings)** from contracts/diary-ui.md; a Decisions Log row (2026-11, spec 103: diary entry replaces the visit prompt
  and the free composer; selected chips filled `onBackground`; note counter neutral, never the accent); remove the
  `labelLarge` example "EXHIBITION DIARY" from the Type Scale table (line 35).
- [ ] T056 Gate: `./gradlew shared:ktlintCheck shared:allTests` and
  `./gradlew androidApp:assembleDebug composeApp:linkDebugFrameworkIosSimulatorArm64`; every pre-existing shared test
  passes unchanged.

**Checkpoint**: the server stores and syncs diary fields on staging, and `shared` holds every diary rule with tests.
No screen has changed yet; 1.15 behaviour is unchanged.

---

## Phase 5: User Story 1 - Record a visit with its date and feeling (Priority: P1) 🎯 MVP — slice 5

**Goal**: one diary row on every opened exhibition opens the entry sheet (date, feeling, tags, note); the visit-save
logic leaves `App.kt` for `DiaryEntryViewModel`.

**Independent Test** (quickstart US1): guest, running show: 다녀왔어요 → 오늘 preselected, 어제 only inside the run → 어제,
좋았어요, save → row `11월 10일 다녀옴 · 좋았어요 수정`; MY → 일기 lists it under this month (after US2; before US2 it is
in MY → 방문); force-quit and reopen → kept. Closed show: no preselected date, no 오늘, 저장 disabled until a date.
Upcoming show: no row. Airplane mode, signed in: saves at once, reaches a second device after reconnecting; reinstall
and sign in → entries return with feeling and tags.

### Tests for User Story 1

- [ ] T057 [P] [US1] Write `appTest/viewmodel/DiaryEntryViewModelTest.kt` (**NEW**): state from no entry and from an
  existing entry; date options from `visitDateOptions`; save disabled for a closed show until a date; a feeling tap
  selects one and a second tap clears; a fourth tag is refused with the limit message; note input stops at 280 and a
  merged note over 280 is kept; save calls `saveEntry` and records `VISIT_RECORDED` only on first creation; delete
  calls `deleteEntry`; a failure sets the save-failed state and logs `diary_entry_save_failed` with the exception type
  only; with a session refresh pending the entry saves locally and shows at once; an undated entry built from a
  public 감상 with no visit opens with the public text as its note, asks for a date, and its first save creates the
  visit (FR-022). Keep a subscriber alive for `WhileSubscribed` state. See it fail.
- [ ] T058 [P] [US1] Write `iosUiTest/ui/diary/DiaryEntrySheetTest.kt` (**NEW**, `runComposeUiTest` v2): date chips per
  run (running, closed, opening today); 저장 disabled for a closed show until a date; fourth tag refused with
  `태그는 3개까지 고를 수 있어요`; the counter `n/280` is never the accent colour; the DatePicker's selected day is not
  the accent; delete confirm reads `이 기록을 삭제할까요?` without a public note; English copy with `AppLanguage.EN`. See
  it fail.
- [ ] T059 [P] [US1] Write `iosUiTest/ui/detail/DiaryVisitRowTest.kt` (**NEW**): the row's no-entry, dated (with and
  without feeling) and undated states; an upcoming show shows no row. See it fail.

### Implementation for User Story 1

- [ ] T060 [US1] Add `app/viewmodel/DiaryEntryViewModel.kt` (**NEW**; constructor injection, `viewModelFactory {
  initializer { } }`, private `MutableStateFlow` + `asStateFlow()`, `AppLog.tagged("Diary")`); make T057 pass.
- [ ] T061 [US1] Build `app/ui/diary/DiaryEntrySheet.kt` (**NEW**: full-height `ModalBottomSheet`, testable
  `DiaryEntrySheetContent`, date/feeling/tag/note sections, explicit monochrome `DatePickerDefaults.colors`, pinned
  저장 and 기록 삭제, unsaved-changes confirm) per contracts/diary-ui.md and the T055 DESIGN.md section, without the
  publish block (US3); make T058 pass.
- [ ] T062 [US1] Build `app/ui/diary/DiaryVisitRow.kt` (**NEW**: the four states, one merged Button node, 52dp); make
  T059 pass.
- [ ] T063 [US1] In `app/ui/detail/ExhibitionDetailScreen.kt` replace the visit prompt (`:314-388`, and the
  `shouldOfferVisitPrompt` import at `:64`) with `DiaryVisitRow` shown when `canRecordVisit`; host the entry sheet
  with its open state in `rememberSaveable`; obtain `DiaryEntryViewModel` with `viewModel(key = "diary-entry-$id") {
  … }` and an initializer (iOS rule).
- [ ] T064 [US1] Remove the visit-save callback from `app/App.kt` (`:913-939`, `visitSaveFailed` state) and pass the
  visit repository, clock and analytics recorder the detail screen needs instead (FR-002 reverses spec 065 FR-003).
- [ ] T065 [US1] Gates: `./gradlew composeApp:ktlintCheck composeApp:allTests androidApp:ktlintCheck
  composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug` and
  `./gradlew composeApp:iosSimulatorArm64Test`.
- [ ] T066 [US1] Device check, **Android** (owner, or an agent with an emulator), against staging with M4 (T031),
  Korean and English: the US1 independent test above, including offline save and the second device, and that a dated
  visit takes at most three taps from the page (row, date, save; SC-007); plus, with a
  **session refresh pending** (signed in, access token expired, network off at launch so the refresh cannot finish —
  the PR #405 state): the entry saves and shows at once, the app does not sign out, and the entry reaches the account
  after the refresh completes. Record the result in this file.
- [ ] T067 [US1] Device check, **iOS** (simulator or device): the same as T066. Record the result in this file.

**Checkpoint**: US1 works on its own: a visit with a date and feeling can be recorded on running and closed shows, by
guests and accounts, offline and across devices.

---

## Phase 6: User Story 2 - Keep and read the diary in MY (Priority: P1) — slice 6

**Goal**: MY's 방문 section becomes 일기 (months, 날짜 미정, filter, ended-saved strip); the Profile 전시 일기 grid, 내 감상
and the mobile admin queue go.

**Independent Test** (quickstart US2): entries in two months → two headings, newest first; a bulk-added show under
날짜 미정; type a LIST search and return → unchanged; save two closed shows → strip reads 2 → add them → both under 날짜
미정; close the strip → gone until a third saved show ends; Profile has no 전시 일기 grid and its 감상 count opens 일기
with 공개한 감상.

### Tests for User Story 2

- [ ] T068 [P] [US2] Write `appTest/viewmodel/DiaryViewModelTest.kt` (**NEW**): state is `buildDiary` over visits,
  own public notes, catalogue, saved ids and the dismissed count; filter toggles; closing the strip stores the current
  count; LIST search, city and filter state are not inputs (SC-008); with a session refresh pending nothing is cleared.
  See it fail.
- [ ] T069 [P] [US2] Write `iosUiTest/ui/diary/DiaryListTest.kt` (**NEW**): month grouping, 날짜 미정 last, filter chips,
  strip shows and hides by count, `+ 지난 전시 추가` still present (FR-014), empty state copy and CTA,
  empty-under-filter copy. See it fail.
- [ ] T070 [P] [US2] Extend `appTest/viewmodel/MyGallrViewModelTest.kt` and `appTest/viewmodel/MyGallrSectionsTest.kt`:
  the section reads 일기 / DIARY with its count; `ADD_ENDED_SAVED` lists only saved shows with `closingDate < today`
  and no entry and adds them as undated entries; LIST → 내 전시 unchanged (FR-015). See them fail.
- [ ] T071 [P] [US2] Extend `appTest/viewmodel/ProfileViewModelTest.kt`: the 감상 count counts own public notes of any
  status from the archive; no call to `getUserThoughts` or `getPendingThoughts`; no admin state. Extend
  `appTest/AppNavigationStateTest.kt`: `MyTabRequest.DIARY_PUBLISHED` selects MY → 일기 with the filter and is consumed
  once. See them fail.

### Implementation for User Story 2

- [ ] T072 [US2] Add `app/viewmodel/DiaryViewModel.kt` (**NEW**); make T068 pass.
- [ ] T073 [US2] Build `app/ui/diary/DiaryList.kt` and `app/ui/diary/EndedSavedStrip.kt` (**NEW**) per
  contracts/diary-ui.md "MY → 일기"; make T069 pass.
- [ ] T074 [US2] In `app/viewmodel/MyGallrViewModel.kt` add the DIARY section and `MyGallrMode.ADD_ENDED_SAVED`, and
  replace `isVisitEligible` with `canRecordVisit`; make T070 pass. After T063 has merged (it removes the last caller),
  delete `shouldOfferVisitPrompt` and `isVisitEligible` (`:62-71`) and their cases in
  `appTest/viewmodel/MyGallrActivationRulesTest.kt`.
- [ ] T075 [US2] In `app/ui/mygallr/MyGallrScreen.kt` (`:118-125`, `:166-171`, `:441-632`) host `DiaryList` in place of
  the 방문 section; in `app/ui/mygallr/AddPastVisitsScreen.kt` add the `끝난 저장 전시` / `ENDED SAVED SHOWS` title and
  `N개 기록 추가` button for `ADD_ENDED_SAVED`; a row tap opens the entry sheet from T061 (needs T061 merged).
- [ ] T076 [US2] Add `MyTabRequest.DIARY_PUBLISHED` in `app/AppNavigationState.kt`; make the navigation part of T071
  pass.
- [ ] T077 [US2] In `app/ui/profile/ProfileScreen.kt` remove the 전시 일기 grid and `DiaryCard` (`:158-165`,
  `:292-349`), the admin block (`:352-383`), the `showMyThoughts`/`showPendingThoughts` flags and screens; the 감상
  count opens `DIARY_PUBLISHED`. In `app/viewmodel/ProfileViewModel.kt` take the count from `PublicNoteRepository`
  and drop `ThoughtRepository`; update `app/ui/profile/AccountScreen.kt` construction. Make the Profile part of T071
  pass (FR-015; FR-027 and US5 scenario 8 on the app side).
- [ ] T078 [US2] Delete `app/ui/profile/MyThoughtsScreen.kt`, `app/viewmodel/MyThoughtsViewModel.kt`,
  `appTest/viewmodel/MyThoughtsViewModelTest.kt`, `app/ui/profile/PendingThoughtsScreen.kt`,
  `app/viewmodel/PendingThoughtsViewModel.kt`, `appTest/viewmodel/PendingThoughtsViewModelTest.kt` and
  `app/viewmodel/ThoughtListUiState.kt` (all **DELETE**). Done when nothing references them.
- [ ] T079 [US2] Wire `DiaryViewModel`, the device-state repository from T054 and `DIARY_PUBLISHED` in `app/App.kt`
  (MY tab and Profile hosting only).
- [ ] T080 [US2] Gates: as T065.
- [ ] T081 [US2] Device check, **Android**, Korean and English: the US2 independent test above, and a staff account's
  Profile shows no admin queue (US5 scenario 8); with a **session refresh pending** the diary still lists every entry and nothing is cleared or duplicated after the refresh. Record here.
- [ ] T082 [US2] Device check, **iOS**: the same as T081. Record here.

**Checkpoint**: US1 + US2 give a complete private diary; LIST search no longer changes it; no admin queue in Profile.

---

## Phase 7: User Story 4 - Report a 감상 or stop seeing one person's 감상 (Priority: P1) — slice 7

**Goal**: 감상 read through `list_exhibition_thoughts` (reporter-only hiding), a card ⋯ menu, the report sheet, hide
author with undo, Settings → 숨긴 사용자. Needs M2 (T014) and the shared core.

**Independent Test** (quickstart US4): reader A reports a 감상 with 광고·홍보 → `신고했어요`, it disappears for A; reader B
still sees it. Staff keep it → A sees it again with 신고함. Hide its author → all their 감상 vanish on this device,
되돌리기 restores, Settings → 숨긴 사용자 lists them with 숨기기 해제. A guest choosing 신고 → sign-in, then the sheet.

### Tests for User Story 4

- [ ] T083 [P] [US4] Write `sharedTest/thoughts/ThoughtVisibilityTest.kt` (**NEW**): `visibleThoughts` drops hidden
  authors and never the reader's own; order kept.
- [ ] T084 [P] [US4] Write `sharedTest/repository/ThoughtRepositoryImplTest.kt` (**NEW**, Ktor `MockEngine` as in
  `MyGallrAccountApiClientTest.kt`): `exhibitionThoughts` calls `list_exhibition_thoughts` with `p_exhibition_id` and
  `p_limit` and maps `reported_by_me`, author name and avatar; `report` calls `report_thought`; `PT409
  thought_report_exists` → `ThoughtReportAlreadySentException`.
- [ ] T085 [P] [US4] Rewrite `appTest/viewmodel/ExhibitionThoughtsViewModelTest.kt` and update
  `appTest/viewmodel/FakeThoughtRepository.kt`: list through the RPC; hide and undo; report success, already sent
  (신고함) and failure (logs `thought_report_failed`); a guest report asks for sign-in and opens the sheet after; own
  card offers edit (event) and unpublish (through `PublicNoteRepository`); while a session refresh is pending the list
  does not reload and report yields the connection message instead of sign-in. See it fail.
- [ ] T086 [P] [US4] Write `appTest/viewmodel/HiddenAuthorsViewModelTest.kt` (**NEW**): lists hidden authors by name,
  unhide removes one, works for guests. See it fail.
- [ ] T087 [P] [US4] Write `iosUiTest/ui/detail/ThoughtCardMenuTest.kt` (**NEW**: own vs other items, 신고함 disabled,
  hide snackbar with 되돌리기) and `iosUiTest/ui/detail/ThoughtReportSheetTest.kt` (**NEW**: five reasons, 신고하기
  disabled until a reason, 보내는 중… while busy). See them fail.
- [ ] T088 [P] [US4] Extend `appTest/ui/settings/SettingsContentTest.kt`: the 숨긴 사용자 / HIDDEN PEOPLE row shows the
  count above zero. See it fail.

### Implementation for User Story 4

- [ ] T089 [US4] Add `shared/thoughts/PublicThought.kt` (**NEW**: `PublicThought`, `ThoughtReportReason`,
  `visibleThoughts`); make T083 pass.
- [ ] T090 [US4] Add `exhibitionThoughts` and `report` to `shared/repository/ThoughtRepository.kt` and
  `shared/repository/ThoughtRepositoryImpl.kt`, with a list DTO in `shared/data/network/dto/ThoughtDto.kt` and
  `ThoughtReportAlreadySentException`; keep the old methods until T106; make T084 pass.
- [ ] T091 [US4] Rewrite the read side of `app/viewmodel/ExhibitionThoughtsViewModel.kt` (RPC list, hidden authors,
  report, own-card actions, refresh-pending rule, `AppLog.tagged("Thoughts")`); the submit path stays until T104; make
  T085 pass.
- [ ] T092 [US4] Add `app/viewmodel/HiddenAuthorsViewModel.kt` (**NEW**); make T086 pass.
- [ ] T093 [US4] Build `app/ui/detail/ThoughtReportSheet.kt` (**NEW**, layout of `RouteReportSheet.kt:41-124`) and add
  the 44dp ⋯ card menu, unpublish confirm and hide snackbar to `app/ui/detail/ThoughtsSection.kt`; remove the admin
  delete (`:174-185`) and the `isAdmin` parameter; make T087 pass.
- [ ] T094 [US4] Add `SettingsDestination.HIDDEN_AUTHORS` to `app/ui/settings/SettingsScreen.kt`, the row to
  `app/ui/settings/SettingsContent.kt`, and `app/ui/settings/HiddenAuthorsScreen.kt` (**NEW**); make T088 pass.
- [ ] T095 [US4] After T063 and T064 have merged: in `app/ui/detail/ExhibitionDetailScreen.kt` (the thoughts block
  `:492-515` and the parameter list) pass the hidden-author repository, `PublicNoteRepository` and the refresh-pending
  flag to `ExhibitionThoughtsViewModel.factory` and drop `isAdmin`; in `app/App.kt` remove the `isAdmin` state and
  profile check (`:252`, `:291-298`, `:305`, `:944`) and pass the T054 repository.
- [ ] T096 [US4] Gates: as T065.
- [ ] T097 [US4] Device check, **Android**, against staging with M2 and M3, two signed-in accounts and a guest: the
  US4 independent test above; with a **session refresh pending**, 신고 shows `연결을 확인한 뒤 다시 시도해 주세요` and the
  list does not reload as anonymous. Record here.
- [ ] T098 [US4] Device check, **iOS**: the same as T097. Record here.

**Checkpoint**: readers can report and hide; a report hides a 감상 from its reporter only; App Store guideline 1.2's
report and block are in place.

---

## Phase 8: User Story 3 - Publish a diary note as a 감상 (Priority: P1) — slice 8

**Goal**: 감상으로 공개 in the entry sheet with statuses and the one-time line; 감상 남기기 opens the sheet; the free composer,
emoji, accent counter and own-pending block go; the old `ThoughtRepository` surface goes. Needs US1 (sheet), US4
(`ThoughtsSection.kt` read side), US5 (queue) and, for T106, US2 (Profile no longer uses the old methods).

**Independent Test** (quickstart US3): signed in, write a note, turn on 감상으로 공개, save → 검토 중; the page does not
show it. Approve in Admin → page shows it with your name; reopening the diary shows 감상이 공개됐어요 once. Edit the note
→ page keeps the old text; 수정한 내용으로 다시 요청 → page shows nothing for it, 검토 중. Reject with 광고·홍보 → 공개 안 됨 ·
사유: 광고·홍보, note intact. Turn off → 비공개 and gone. Delete the entry → the confirmation names the published note;
both gone. Guest → `로그인하면 감상으로 공개할 수 있어요` opens sign-in and returns to the sheet.

### Tests for User Story 3

- [ ] T099 [P] [US3] Extend `appTest/viewmodel/DiaryEntryViewModelTest.kt`: publish offered only signed in with a
  non-empty note; a merged note over 280 blocks publishing; save with publish on enqueues `UpdateVisit` then
  `PublishNote`; statuses from `diaryNoteStatus` (보내는 중 while queued, also during a pending refresh); ask again after
  an edit; turning off unpublishes and keeps the note; delete with a public note uses the longer confirmation; the
  approval line acknowledges once (`AcknowledgeNoteApproval`); a guest's sign-in request returns to the sheet. See it
  fail.
- [ ] T100 [P] [US3] Extend `iosUiTest/ui/diary/DiaryEntrySheetTest.kt`: guest publish line; disclosure text; disabled
  switch and `메모를 쓰면 감상으로 공개할 수 있어요` with an empty note; status lines with `사유:`; delete confirm with
  `공개한 감상도 함께 삭제돼요`; the switch is never the accent. See it fail.
- [ ] T101 [P] [US3] Extend `iosUiTest/ui/detail/DiaryVisitRowTest.kt`: `감상 남기기` / `WRITE A NOTE` shows only on
  opened shows, without emoji, and opens the sheet with the note focused; an upcoming show shows neither the row nor
  감상 남기기. See it fail.
- [ ] T102 [P] [US3] Extend `appTest/viewmodel/DiaryViewModelTest.kt`: the one-time 감상이 공개됐어요 line shows above
  the list once and is acknowledged. See it fail.

### Implementation for User Story 3

- [ ] T103 [US3] Add the publish block, status line, ask-again buttons and one-time line to `app/ui/diary/DiaryEntrySheet.kt`
  and the publish/unpublish/acknowledge actions to `app/viewmodel/DiaryEntryViewModel.kt` over `PublicNoteRepository`
  (`diary_note_publish_failed`, `diary_note_unpublish_failed`, `diary_approval_ack_failed`); make T099 and T100 pass.
- [ ] T104 [US3] In `app/ui/detail/ThoughtsSection.kt` remove the composer path, the emoji button (`:232-233`) and the
  own-pending block (`:142-170`, `:200-211`), and add `감상 남기기` → entry sheet with the note focused (hosted by
  `app/ui/detail/ExhibitionDetailScreen.kt`); remove the submit, update and delete paths from
  `app/viewmodel/ExhibitionThoughtsViewModel.kt`; make T101 pass.
- [ ] T105 [US3] Delete `app/ui/detail/ThoughtComposer.kt` (**DELETE**; the accent counter at `:91-100` goes with it).
- [ ] T106 [US3] After T077–T078 have merged: remove `getThoughtsForExhibition`, `getUserThoughts`, `submitThought`,
  `updateThought`, `deleteThought`, `getUserThoughtForExhibition`, `getUserThoughtCount`, `getPendingThoughts`,
  `approveThought`, `rejectThought` from `shared/repository/ThoughtRepository.kt` and
  `shared/repository/ThoughtRepositoryImpl.kt`; replace `shared/data/model/Thought.kt` and the old DTO in
  `shared/data/network/dto/ThoughtDto.kt` with the `PublicThought` DTO; trim `appTest/viewmodel/FakeThoughtRepository.kt`;
  stop reading `Profile.isAdmin` (`shared/data/model/Profile.kt`, `shared/data/network/dto/ProfileDto.kt`; the column
  stays on the server, plan decision 5). Keep `thoughtDateLabel` and `appTest/ui/detail/ThoughtDateLabelTest.kt` if
  the card still uses it.
- [ ] T107 [US3] Wire the diary one-time line in `app/viewmodel/DiaryViewModel.kt` and `app/ui/diary/DiaryList.kt`;
  make T102 pass.
- [ ] T108 [US3] Gates: as T065, plus `./gradlew shared:ktlintCheck shared:allTests`.
- [ ] T109 [US3] Device check, **Android**, against staging with Admin Preview: the US3 independent test above; with a
  **session refresh pending**, a publish reads 보내는 중 (never 보내지 못했어요) and reaches 검토 중 after the refresh.
  Record here.
- [ ] T110 [US3] Device check, **iOS**: the same as T109. Record here.

**Checkpoint**: every public 감상 comes from a diary entry and passes review; the four existing 감상 show in their
authors' diaries as undated published entries (SC-011).

---

## Phase 9: User Story 6 - The diary tunes 내 취향 on the device (Priority: P2) — slice 9

**Goal**: the recommender reads the feeling and mood tags (plan "Recommender (US6)"). Can start once T044–T045 merge
(types only); recommender files only, plus the reason string.

**Independent Test** (spec US6): in the made-up profile tests, a 좋았어요 visit ranks shows sharing its artist as high as
a save; 아쉬웠어요 drops them and lowers others sharing its terms by less than one 덜 보기.

### Tests for User Story 6

- [ ] T111 [P] [US6] Write `sharedTest/recommendation/VisitFeelingRankingTest.kt` (**NEW**): contract guarantees 1–6
  (no feelings → output identical; `LOVED` = save in basis points; `FINE`/none = today; `NOT_FOR_ME` adds no evidence
  and costs `min(0.25 × (dismissals + 0.5 × disliked), 0.5 × base)` per shared reviewed term and credited artist, less
  than one 덜 보기, floored at zero, `hasPersonalEvidence` unchanged; mood tags on `LOVED` add `VisitedMood` like a
  picked term, on other feelings nothing; determinism and input-order independence). See it fail.
- [ ] T112 [P] [US6] Extend `sharedTest/recommendation/TasteProfileRankingTest.kt` (and
  `sharedTest/recommendation/TasteProfileCatalogue.kt` if a show is needed) with three made-up profiles (a loved
  visit, a disliked visit, a loved visit tagged 조용히 머물기 좋았어요) whose leaders are worked by hand. See it fail.
- [ ] T113 [P] [US6] Extend `appTest/ui/discovery/RecommendationPresentationTest.kt`: the `VisitedMood` reason reads
  `방문한 "<전시>"와 비슷한 분위기: <용어>` (and its EN form) and never contains a feeling or tag label. See it fail.

### Implementation for User Story 6

- [ ] T114 [US6] Add `RecommendationEvidence.VisitedMood(anchor, term)` to `shared/recommendation/ExhibitionRecommendation.kt`
  and the feeling weights, `DISLIKED_VISIT_PENALTY_SHARE = 0.5` and the tag map from `VisitTag.moodTermId` to
  `shared/recommendation/LocalExhibitionRecommender.kt`; make T111 and T112 pass with every existing recommender test
  unchanged.
- [ ] T115 [US6] Add the `VisitedMood` reason to `app/ui/discovery/RecommendationPresentation.kt`; make T113 pass.
- [ ] T116 [US6] Gates: `./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:testAndroidHostTest`.
- [ ] T117 [US6] Device check, **Android**: mark a visit 아쉬웠어요 → a show sharing its artist drops in 내 취향 but
  stays in the personal group; mark it 좋았어요 → it rises. Session refresh pending: not applicable (ranking is
  local). Record here.
- [ ] T118 [US6] Device check, **iOS**: the same as T117. Record here.

**Checkpoint**: feelings change 내 취향 on the device only; nothing new leaves the device for ranking.

---

## Phase 10: Polish & cross-cutting — slice 10 and release documents

- [ ] T119 [P] Extend `web/tests/privacy-page.test.ts` (Playwright, run by `npm test` in `web/`; the expectation at
  `:66` changes): the content item names diary entries (visit date, feeling, tags, private note) stored on the device
  and synced privately; a "Public notes and reports" item; feelings, tags, private notes and reports listed among what
  analytics never carries; the new effective date. See it fail.
- [ ] T120 Update `web/privacy.html` (line 14 effective date → release date, line 48, a new item, lines 71–72, with the
  Korean translations) per FR-033; make T119 pass with `(cd web && npm ci && npm test)`.
- [ ] T121 [P] Update `docs/mobile-analytics-store-disclosures.md` (`:102` purpose → "profile bio, thoughts, diary
  entries and reports"; a note that Play needs no new type). `iosApp/iosApp/PrivacyInfo.xcprivacy` needs no change: its
  `OtherUserContent` entry (`:44-50`) already declares linked, app-functionality data and has no free-text purpose.
- [ ] T122 [P] SC-010 and FR-029: extend `sharedTest/analytics/MobileAnalyticsEventTest.kt` to assert the event field
  allowlist is unchanged and contains no `feeling`, `tags` or `note`; confirm
  `supabase/functions/mobile-analytics/handler.ts` (`:70-80`) is unchanged.
- [ ] T123 Privacy and logging review (FR-029, FR-034), recorded in this file: no network call other than the account
  sync carries a feeling, tag or note; every new log uses `AppLog.tagged` with a snake_case operation and the exception
  type only; no staff RPC or Admin payload carries diary fields; no reader-facing AI wording or emoji (FR-031).
- [ ] T124 **OWNER STEP** Store declarations: in App Store Connect widen the Other User Content purpose text to diary
  entries and reports (no new data type); in Play Console confirm "Other user-generated content" is declared as
  collected and linked (it covers 감상 today); write the 1.16 App Review note naming the four guideline 1.2 places
  (review before publication and on every edit; 신고 with a 48-hour decision; 이 사용자의 감상 숨기기 with the Settings
  list; Settings 의견 보내기).
- [ ] T125 [P] Add a 103 line to `CLAUDE.md` "Recent Changes" by hand (do not run the agent-context script, research
  P12), an `## [Unreleased]` entry in `CHANGELOG.md` (Added / Changed / Fixed — the edited-text defect / Infrastructure —
  M1–M4), and `TODOS.md` items: the M5 follow-up (`…_thoughts_require_diary_entry`, next pgTAP number then) and the
  `profiles.is_admin` drop once the minimum version is ≥ 1.16.0; the owner's weekly SC-003/SC-004 check with the
  quickstart queries; the SC-001/SC-002 read eight weeks after release.
- [ ] T126 Full verification per CLAUDE.md: `./gradlew shared:ktlintCheck composeApp:ktlintCheck androidApp:ktlintCheck
  shared:allTests composeApp:allTests composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug`,
  `./gradlew composeApp:iosSimulatorArm64Test`, the uncached iOS link only if a dependency changed (none planned),
  `(cd admin && npm test && npm run build)`, `(cd web && npm test)`, pgTAP 001–079 on a scratch stack, the lineage
  validator, `git diff --check`. Record each result here.

---

## Phase 11: Rollout and release (owner steps, plan "Migrations and deploy order")

- [ ] T127 **OWNER STEP** Before the first hosted apply (T007): `20261111051000_security_storage_boundaries` is on
  `develop` and recorded on neither project (read 2026-11-11). Apply it under its own plan first, or confirm that the
  dry run for M1 lists it and that applying it together is intended. Each later apply starts with the lineage
  validator and a dry run that proposes exactly the expected version.
- [ ] T128 **OWNER STEP** Staging pass with all four migrations and Admin Preview (after T031): quickstart "Staging"
  steps 1–3 with a 1.15 build, and the manual US1–US5 pass with a 1.16 build (T066–T110 cover it). Production waits
  for this.
- [ ] T129 **OWNER STEP** Apply M1 `20261112100000` to **production**.
- [ ] T130 **OWNER STEP** Apply M2 `20261112100100` to **production**.
- [ ] T131 **OWNER STEP** Deploy the Admin with the 감상 section to **production** (Vercel `gallr-admin`, which deploys
  production from `main`: the owner's `develop` → `main` promotion pull request, or a manual production deploy of that
  build). Check `?section=thoughts` as an admin.
- [ ] T132 **OWNER STEP** Apply M3 `20261112100200` to **production** the same day as T131 (owner decision 8: the 1.15
  in-app moderation is retired when the Admin section goes live).
- [ ] T133 **OWNER STEP** Apply M4 `20261112100300` to **production**.
- [ ] T134 **OWNER STEP** Confirm, read-only, that production's `supabase_migrations.schema_migrations` records all four
  versions, and run the SC-011 query (the four 감상 public on 2026-11-11 are still approved).
- [ ] T135 **OWNER STEP** Release 1.16: bump `VERSION`, `androidApp` `versionCode`/`versionName` and `CHANGELOG.md`
  (`## [1.16.0] - YYYY-MM-DD`) together; build and **submit to the stores only after T134** (the 1.16 store submission
  waits until M1–M4 are all live in production). After both stores are live, set the recommended version in Admin →
  앱 버전 (spec 098); raising the minimum to 1.16 and M5 are follow-ups, not part of this release.

---

## Dependencies & execution order

### Slice and phase dependencies

| Phase (slice) | Depends on | Blocks |
|---|---|---|
| 1 Setup | — | everything |
| 2 M1 (1) | 1 | 3, 4-SQL |
| 3 US5 (2) | 2 | 7 (needs M2's RPCs), 8 (needs the queue), rollout |
| 4 M4 + shared core (3–4) | 2 for M4's SQL; slice 4 needs only the contracts | 5, 6, 7, 8; 9 needs only T044–T045 |
| 5 US1 (5) | 4 | 6's T074 cleanup and T075 row tap, 7's T095, 8 |
| 6 US2 (6) | 4; T074's deletion and T075's row tap need 5 merged | 8's T106 |
| 7 US4 (7) | 3 (M2), 4; T095 needs 5 merged | 8 |
| 8 US3 (8) | 5, 7; T106 also needs 6 | rollout |
| 9 US6 (9) | T044–T045 | — (may slip) |
| 10 Polish (10) | T119–T122 any time; T123–T126 last | 11 |
| 11 Rollout | owner; T128 after all staging applies; T135 after T134 | — |

Within each phase: test tasks before implementation; models and pure rules before repositories; repositories before
ViewModels; ViewModels before screens; wiring (`App.kt`, detail screen) last.

### Files that make two slices collide

| File | Slices | Rule |
|---|---|---|
| `app/App.kt` | 4 (T054 parameters), 5 (T064), 6 (T079), 7 (T095) | one wiring task per lane; merge in the order 4 → 5 → 6 → 7 → 8; a lane merges `develop` into its branch (merge, never rebase) before its wiring task |
| `app/ui/detail/ExhibitionDetailScreen.kt` | 5 (T063 visit row), 7 (T095 thoughts block and parameters), 8 (T104 hosts 감상 남기기) | 7's T095 waits for 5 to merge; 8 after 7 |
| `app/ui/detail/ThoughtsSection.kt` | 7 (read side, menu, admin delete), 8 (composer, emoji, own-pending) | 8 starts after 7 merges (plan) |
| `app/viewmodel/ExhibitionThoughtsViewModel.kt` + its test and `FakeThoughtRepository.kt` | 7, 8 (T104, T106) | same as `ThoughtsSection.kt` |
| `app/viewmodel/MyGallrViewModel.kt` | 5 stops calling `shouldOfferVisitPrompt`; 6 deletes it | T074's deletion after T063 merges |
| `shared/repository/ThoughtRepository*.kt` | 7 adds, 8 removes | T106 after 6 and 7 merge |
| `app/ui/diary/DiaryEntrySheet.kt`, `app/viewmodel/DiaryEntryViewModel.kt` | 5, 8 | same lane (C) |
| `app/viewmodel/DiaryViewModel.kt`, `app/ui/diary/DiaryList.kt` | 6, 8 (T107) | T107 after 6 merges |
| `androidApp/…/MainActivity.kt`, `iosMain/…/MainViewController.kt` | 4 only (T054) | both device stores constructed once in slice 4 |
| `shared/repository/MyGallrAccountSync.kt` | 4 only | — |
| `shared/recommendation/*` | 9 only | — |

### Suggested parallel worker lanes (non-overlapping file ownership)

| Lane | Tasks | Owns |
|---|---|---|
| A — database | T003–T006, T014–T016, T028–T030 (then idle) | `supabase/migrations/2026111210*`, `supabase/tests/database/076–079`, `supabase/functions/delete-account/README.md` |
| A2 — Admin | T010–T013, T017–T022 (can start with T008 against contracts/admin-thoughts.md; the integration check needs M2 on a scratch stack) | `admin/**` |
| B — shared core | T032–T054, T056 | `shared/diary/**`, `shared/thoughts/HiddenAuthor.kt`, `shared/data/model/{ExhibitionVisit,MyGallrAccountArchive}.kt`, `shared/repository/{VisitRepository,DataStoreVisitRepository,MyGallrAccountSync,PublicNoteRepository,DiaryDeviceState*,HiddenThoughtAuthor*}.kt`, `shared/data/network/MyGallrAccountApiClient.kt`, the composition roots, `App(...)` parameters; matching `sharedTest/**` |
| G — docs | T055 (first, blocks UI), T119–T122, T125 | `DESIGN.md`, `web/privacy.html`, `web/tests/privacy-page.test.ts`, `docs/mobile-analytics-store-disclosures.md`, `sharedTest/analytics/MobileAnalyticsEventTest.kt`, `CHANGELOG.md`, `TODOS.md`, `CLAUDE.md` |
| C — entry and publish | T057–T065 (US1), then T099–T101, T103–T106, T108 (US3, after E merges) | `app/ui/diary/{DiaryEntrySheet,DiaryVisitRow}.kt`, `app/viewmodel/DiaryEntryViewModel.kt`, `app/ui/detail/ExhibitionDetailScreen.kt` (visit row, then 감상 남기기), `App.kt` visit callback; in US3 also `ThoughtsSection.kt`, `ThoughtComposer.kt`, `ExhibitionThoughtsViewModel.kt` write side, `shared/repository/ThoughtRepository*.kt` cleanup, `shared/data/model/{Thought,Profile}.kt`, DTOs |
| D — MY 일기 and Profile | T068–T080, T102, T107 | `app/ui/diary/{DiaryList,EndedSavedStrip}.kt`, `app/viewmodel/{DiaryViewModel,MyGallrViewModel,ProfileViewModel}.kt`, `app/ui/mygallr/**`, `app/ui/profile/**`, `app/AppNavigationState.kt`, deleted MyThoughts/PendingThoughts files, `App.kt` MY/Profile wiring |
| E — 감상 read, report, hide | T083–T096 | `shared/thoughts/PublicThought.kt`, `shared/repository/ThoughtRepository*.kt` (additions), `ThoughtDto.kt` (additions), `app/viewmodel/{ExhibitionThoughtsViewModel,HiddenAuthorsViewModel}.kt`, `app/ui/detail/{ThoughtsSection,ThoughtReportSheet}.kt`, `app/ui/settings/**`, the detail screen's thoughts block and `App.kt` `isAdmin` lines (T095, after C's T063–T064) |
| F — recommender | T111–T116 | `shared/recommendation/*`, `sharedTest/recommendation/*`, `app/ui/discovery/RecommendationPresentation.kt` and its test |

Timeline: A, A2, B and G start together (B against the contracts). After B merges: C, D, E and F in parallel, with the
ordering rules above (C's T063–T064 merge first; E's T095 and D's T074/T075 follow). C's US3 work starts when E has
merged. Device checks (T026–T027, T066–T067, T081–T082, T097–T098, T109–T110, T117–T118) follow each lane's merge.

### Owner-gated tasks

T007, T023, T024, T025, T031, T124 and T127–T135 are OWNER STEPS. T026–T027, T066–T067, T081–T082, T097–T098,
T109–T110 and T117–T118 are device checks the owner or an agent with a simulator or emulator may run.

## Parallel examples

- Phase 3 tests: T008, T009, T010, T011, T012, T013 together; then T014 → T015 (one scratch stack) beside T017 → T018 →
  T019 → T020 in `admin/`.
- Phase 4 tests: T032–T043 together; implementations T044 → T045 → T046 → T047 → T048 → T049 → T050 → T051 → T052 →
  T053 → T054 in order (shared files), T055 beside them.
- After Phase 4: lanes C (T057–T059 together), D (T068–T071 together), E (T083–T088 together) and F (T111–T113
  together) start at once.

## Implementation strategy

1. **Server first.** Phases 2–4 (slices 1–4) are live and harmless for 1.15 before any app screen changes; M1 fixes
   the edited-text defect, and the Admin section plus M3 moves moderation off the app (owner decision 8).
2. **MVP increment: US1** (Phase 5). A visit with a date and feeling can be recorded; testable alone.
3. **US2 and US4 in parallel** (Phases 6–7), then **US3** (Phase 8), which needs US1's sheet, US4's card side and US5's
   queue.
4. **US6** (Phase 9) in parallel from the end of Phase 4.
5. Polish, then the rollout in the plan's order; the store submission comes last.

### MVP / cut line

Per spec and plan ("Smallest shippable 1.16"): US1–US5 are P1 and all ship in 1.16. US4 ships with the release, not
after it, and removing the composer without US3 would remove the only way to write a 감상, so no P1 story and no part of
one is optional; cutting the ended-saved strip (FR-013) or the Settings hidden list (FR-024) would need a spec amendment
by the owner. **Only US6 (Phase 9, T111–T118) may move to 1.16.x or 1.17**; feelings and tags are still recorded and
synced, so nothing is lost when it lands later. The release gate is Phases 1–8, 10 and 11 complete.

## Cross-artifact check (`speckit.analyze`, 2026-11-11)

No constitution conflict and no requirement without a task: FR-001–FR-034 and SC-001–SC-011 each map to at least one
task (SC-001–SC-003 and SC-006 are owner reads or the M5 follow-up, recorded in TODOS by T125). Fixed as plain
documentation errors in this pull request:

- plan.md project structure lacked `viewmodel/ProfileViewModel.kt` (it reads `getUserThoughts` and
  `getPendingThoughts` today), `ui/discovery/RecommendationPresentation.kt` (the new `VisitedMood` reason),
  `diary/DiaryEntryDraft.kt`, `data/model/Thought.kt`, `Profile.kt` and `dto/ProfileDto.kt`; added.
- plan.md slice 8 depended on 5 and 7 only; removing the old `ThoughtRepository` methods also needs slice 6 (Profile
  stops using them there); added. Slice 7's detail-screen wiring needs slice 5 merged; added.
- The hidden-author store moved from slice 7 to slice 4 so the composition roots change once (T053–T054); plan.md
  slices 4 and 7 and contracts/shared-diary-api.md (`thoughts/HiddenAuthor.kt`) updated.
- plan.md's constitution check called the privacy-page test a Node test; it is the Playwright suite
  `web/tests/privacy-page.test.ts`, run by `npm test`.
- Tasks gained explicit coverage for FR-014 (bulk add stays, T069), FR-022 (first save of a legacy 감상, T057) and
  SC-007 (three taps, T066).

Left for the owner (not decided here):

1. `20261111051000_security_storage_boundaries` is applied on neither project; apply it under its own plan before M1,
   or accept it in M1's push (T127).
2. Vercel deploys Admin production from `main`, so T131 is either the `develop` → `main` promotion (which carries
   everything else on `develop` then) or a manual production deploy of that build; choose which and when.
3. The plan sends production applies only after all four migrations and the staging pass (T128). M1 alone fixes a live
   defect (an author's edit of an approved 감상 is public unreviewed, research §1); sending M1 to production earlier
   would be a change to the plan's order and is the owner's call.
4. How to put a device into the "session refresh pending" state for T066–T110 is not verified on a device (research
   P9); the owner may prefer a debug switch over the expired-token-offline recipe.
