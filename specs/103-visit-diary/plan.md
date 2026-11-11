# Implementation Plan: Visit diary with 감상 joined in

**Branch**: `103-visit-diary-plan` | **Date**: 2026-11-11 | **Spec**: [spec.md](spec.md) | **Release**: 1.16
(spec-kit resolves the feature folder from the `103-` prefix; the spec branch `103-visit-diary` is already merged)
**Input**: Feature specification from `specs/103-visit-diary/spec.md` (seven binding owner decisions, no open
questions) and its [research.md](research.md); plan research is appended there as P1–P12.

## Summary

A visit becomes a diary entry: the visit date, a private feeling, up to three private tags and a private note of up to
280 characters, kept on the device for guests and synced whole to the account for signed-in people. A public 감상 is
the entry's note published through review. The plan builds on the existing visit record and its account sync instead
of a new store: four additive migrations add diary fields to `content_private.my_gallr_visits`, six new mutation kinds
to `sync_my_gallr_archive` (so a publish rides the same offline outbox as the entry it belongs to), a moderation
status on `public.thoughts` that stays in step with `is_approved` for 1.15, a trigger that returns any edited text to
review (fixing the live defect), reports that hide a 감상 from its reporter only, and publisher-role staff RPCs for a
new Admin 감상 section. In the app, the free composer, 내 감상, the Profile 전시 일기 grid and the mobile admin queue
go; an entry sheet, the MY 일기 list, a report sheet, a hide-author action and a Settings list come; the on-device
recommender reads the feeling. The public web changes only its privacy page.

## Technical Context

**Language/Version**: Kotlin 2.x (KMP; versions in `gradle/libs.versions.toml`); TypeScript (Admin, Node 22.23.1 per
`.node-version`); SQL (Postgres 17, Supabase); Eleventy HTML (privacy page only)
**Primary Dependencies**: Compose Multiplatform (Material3 `ModalBottomSheet`, `DatePicker`), JetBrains lifecycle
ViewModel, DataStore Preferences, kotlinx.serialization, kotlinx.datetime, Ktor via supabase-kt; React/Vite (Admin).
No new dependency.
**Storage**: device DataStore (guest entries in the existing `my_gallr_exhibition_visits_v1` payload, the account
outbox `my_gallr_account_state_v1`, two new device-only keys); Supabase `content_private.my_gallr_visits` (diary
fields, owner-only through a definer RPC), `public.thoughts` (moderation columns), new `public.thought_reports`
**Testing**: `shared/commonTest` and `composeApp/commonTest` (kotlin-test, `runTest`); Compose UI tests in
`composeApp/iosTest` with `androidx.compose.ui.test.v2.runComposeUiTest`; pgTAP suites 076–079; Vitest (Admin);
Node contract and Playwright tests (web privacy page)
**Target Platform**: Android and iOS app (1.16); staff Admin; Supabase (staging `gallr-staging`, production
`gallr-korea`); public web privacy page
**Project Type**: mobile app + backend + operator web tool
**Performance Goals**: entry save visible in the diary on the same frame the sheet closes (local apply before sync);
diary build for 500 entries under the existing recommender test bound on the background dispatcher; one RPC per
exhibition page for 감상 (replacing today's two queries)
**Constraints**: additive migrations only, recorded migrations immutable; 1.15 must keep working and must never make
unreviewed text public; feelings, tags and notes never leave the owner's account rows, never reach analytics or any
staff surface; analytics allowlist unchanged; no reader-facing AI wording; no emoji; accent only in its three roles
**Scale/Scope**: 157 accounts, 21 synced visits, 4 감상 (all approved, all on closed shows still in the catalogue), 2
active staff (both `admin`), read on production 2026-11-11 (P11); 6 user stories; 4 migrations in 1.16 plus one
deferred follow-up

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I Spec-First | `spec.md` with 6 stories, 34 FRs, 11 SCs, owner decisions 1–7, checklist passed | Pass |
| II Test-First | Each slice opens with failing tests: pgTAP 076–079 before each migration's functions (SC-005's 1.15 and 1.16 paths, reporter-only hiding, unauthorized roles, cascades); `shared/commonTest` before every rule (`visitDateOptions`, `canRecordVisit`, `mergeEntry`, `buildDiary`, `diaryNoteStatus`, `visibleThoughts`, mutation reducer, wire mapping, feeling weights in `VisitFeelingRankingTest` and an extended `TasteProfileRankingTest`); `composeApp/commonTest` for ViewModels (`DiaryEntryViewModelTest`, `DiaryViewModelTest`, `ExhibitionThoughtsViewModelTest`); iosTest `runComposeUiTest` v2 for the entry sheet, diary list, 감상 card menu and report sheet; Vitest for the Admin repository and workspace; Node test for the privacy page wording | Pass (enforced per slice, see Slices) |
| III Simplicity | Extends `ExhibitionVisit` and the existing sync RPC instead of a diary table or second sync channel; reuses the route-report pattern, `RouteReportSheet` layout, `MyGallrMode` for the ended-saved list, the Settings in-screen destinations; no new module, dependency or base class. One extra migration (M3) exists only to sequence the Admin deploy | Pass |
| IV Incremental | Server slices are live and harmless for 1.15 before any app work ships; US1, US2, US4, US5 and US6 are testable alone; US3 needs US1's sheet and US5's queue, both earlier slices; US6 can slip without touching the rest | Pass |
| V Observability | `AppLog.tagged("Diary")`, `("Thoughts")`, `("HiddenAuthors")` with stable snake_case operations and exception type only, never note text, ids or content (FR-034); every staff decision writes `content.audit_log` without the text | Pass |
| VI Shared-First | All rules in `shared/commonMain`: eligibility (`canRecordVisit`, replacing `shouldOfferVisitPrompt` and `isVisitEligible` in `MyGallrViewModel.kt:62-71`), date options, merge rule, `buildDiary` grouping and the ended-saved count, note status mapping, hidden-author filtering, feeling and tag weighting, vocabularies with KO/EN labels, repositories and DTOs. `composeApp/commonMain` holds only ViewModels (entry sheet, diary, thoughts, hidden authors) and Compose UI; the visit-save logic leaves the `App.kt:913-939` callback for `DiaryEntryViewModel`. Platform source sets change only at the composition roots (`MainActivity`, `MainViewController`) to construct two new DataStore repositories | Pass |
| VII Mobile-First | Every consumer capability (entry, diary, publish, report, hide) is in the Android and iOS app only; the web gains privacy-page text and no 감상 display or write path; Admin is an operator tool | Pass |

Post-design re-check (after research P1–P12, data model and contracts): no new project, framework or platform adapter;
the publish-through-sync decision (P1) removes a second offline queue rather than adding one; M3's separate migration
is sequencing, not complexity. **Pass.**

## Project Structure

### Documentation (this feature)

```text
specs/103-visit-diary/
├── spec.md
├── research.md            # spec facts + plan research P1–P12
├── plan.md                # this file
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── database.md        # tables, columns, trigger, RLS, grants, RPCs, mutation kinds, archive JSON
│   ├── shared-diary-api.md # Kotlin domain types, pure functions, repositories, guarantees
│   ├── diary-ui.md        # screens, states, every new KO/EN string, DESIGN.md additions
│   └── admin-thoughts.md  # Admin 감상 section, repository interface, tests
├── checklists/requirements.md
└── tasks.md               # /speckit.tasks (not created by this plan)
```

### Source Code (repository root)

```text
supabase/migrations/
├── 20261112100000_thought_moderation_status.sql      # M1
├── 20261112100100_thought_reports_and_review.sql     # M2
├── 20261112100200_retire_mobile_thought_moderation.sql  # M3
└── 20261112100300_visit_diary_sync.sql               # M4
supabase/tests/database/076_thought_moderation_status.test.sql … 079_visit_diary_sync.test.sql
supabase/functions/delete-account/README.md          # list my_gallr tables and thought_reports

shared/src/commonMain/kotlin/com/gallr/shared/
├── data/model/ExhibitionVisit.kt         # visitDate, feeling, tags, note, entryUpdatedAt (defaults)
├── data/model/MyGallrAccountArchive.kt   # new mutation variants, publicNotes
├── diary/VisitFeeling.kt, VisitTag.kt    # fixed vocabularies, wire ids, KO/EN labels, mood-term map
├── diary/VisitEligibility.kt             # canRecordVisit, visitDateOptions
├── diary/EntryMerge.kt                   # mergeEntry (mirror of the server rule)
├── diary/Diary.kt                        # DiaryEntry, DiaryMonth, Diary, buildDiary
├── diary/PublicNote.kt                   # OwnPublicNote, PublicNoteStatus, diaryNoteStatus
├── thoughts/PublicThought.kt             # PublicThought, ThoughtReportReason, visibleThoughts
├── repository/VisitRepository.kt, DataStoreVisitRepository.kt, MyGallrAccountSync.kt
├── repository/PublicNoteRepository.kt    # signed-in publish/unpublish/acknowledge over the coordinator
├── repository/ThoughtRepository.kt, ThoughtRepositoryImpl.kt   # RPC read, report; moderation methods removed
├── repository/HiddenThoughtAuthorRepository.kt (+ DataStore impl)
├── repository/DiaryDeviceStateRepository.kt (+ DataStore impl)   # ended-saved strip dismissal
├── data/network/MyGallrAccountApiClient.kt, dto/ThoughtDto.kt
└── recommendation/LocalExhibitionRecommender.kt, ExhibitionRecommendation.kt
shared/src/commonTest/…                   # one test file per rule above, VisitFeelingRankingTest, TasteProfileRankingTest

composeApp/src/commonMain/kotlin/com/gallr/app/
├── viewmodel/DiaryEntryViewModel.kt      # sheet state and save (moved from App.kt:913-939)
├── viewmodel/DiaryViewModel.kt           # MY 일기 list, filter, strip
├── viewmodel/ExhibitionThoughtsViewModel.kt  # RPC list, report, hide, own-card actions
├── viewmodel/HiddenAuthorsViewModel.kt
├── viewmodel/MyGallrViewModel.kt         # DIARY section, ADD_ENDED_SAVED mode; prompt rules removed
├── ui/diary/DiaryEntrySheet.kt, DiaryVisitRow.kt, DiaryList.kt, EndedSavedStrip.kt
├── ui/detail/ExhibitionDetailScreen.kt, ThoughtsSection.kt, ThoughtReportSheet.kt
├── ui/mygallr/MyGallrScreen.kt, AddPastVisitsScreen.kt
├── ui/profile/ProfileScreen.kt, AccountScreen.kt
├── ui/settings/SettingsScreen.kt, SettingsContent.kt, HiddenAuthorsScreen.kt
├── AppNavigationState.kt                 # MyTabRequest.DIARY_PUBLISHED
└── App.kt                                # wiring; isAdmin check and visit callback removed
deleted: ui/detail/ThoughtComposer.kt, ui/profile/MyThoughtsScreen.kt, ui/profile/PendingThoughtsScreen.kt,
         viewmodel/MyThoughtsViewModel.kt, viewmodel/PendingThoughtsViewModel.kt, viewmodel/ThoughtListUiState.kt
composeApp/src/iosTest/…                  # DiaryEntrySheetTest, DiaryListTest, ThoughtCardMenuTest, ThoughtReportSheetTest
androidApp/…/MainActivity.kt, composeApp/src/iosMain/…/MainViewController.kt   # construct new repositories

admin/src/domain.ts, components/PrimaryNavigation.tsx, App.tsx, i18n.tsx, staff-i18n-messages.ts
admin/src/repositories/AdminThoughtRepository.ts, SupabaseAdminThoughtRepository.ts, InMemoryAdminThoughtRepository.ts
admin/src/components/ThoughtModerationWorkspace.tsx (+ tests)

web/privacy.html, web/tests/privacy-page.test.ts
docs/mobile-analytics-store-disclosures.md, DESIGN.md, CHANGELOG.md, TODOS.md, CLAUDE.md (Recent Changes),
admin/AGENTS.md (deploy note)
```

**Structure Decision**: the existing KMP split (rules and repositories in `shared`, ViewModels and Compose UI in
`composeApp`), Supabase migrations with pgTAP, and the Admin React app. No new module. A `diary` package in `shared`
groups the new rules the way `taste` grouped spec 101's.

## Decisions this plan makes

Each is argued with evidence in research P1–P12; the contracts hold the exact shapes.

1. **One sync channel (P1).** Diary fields are columns on `content_private.my_gallr_visits`; edits, the sign-in merge,
   delete, publish, unpublish and approval acknowledgement are mutation kinds of `sync_my_gallr_archive`. A publish
   therefore always reaches the server after the entry it copies, offline saves need no second queue, and 보내는 중 is
   simply "a publish mutation is still in the outbox". `add_visit` and `remove_visit` keep their 1.15 meaning.
2. **Moderation status on thoughts (P2).** `moderation_status` (`pending` / `approved` / `rejected`) with a check that
   `is_approved = (moderation_status = 'approved')`; a rewritten trigger: any author change of `content`, from any app
   version, returns the row to `pending`; authors can never set `approved` or `rejected`; staff decisions are trusted
   only inside the staff RPCs (a transaction-local flag plus the publisher role). Rejection keeps the row.
3. **The link between a 감상 and its entry is the shared key `(user_id, exhibition_id)`** — unique on `thoughts`
   (`006_create_thoughts.sql:11`) and the primary key of `my_gallr_visits` (`…account_archive.sql:24`). No foreign key:
   1.15 감상 without a visit must stay legal until the follow-up (decision 5).
4. **Reporter-only hiding on the server (P3).** 1.16 reads 감상 through `list_exhibition_thoughts`, which leaves out any
   감상 the caller has an open report on; 1.15 keeps its direct table read and cannot report.
5. **Staff role: `publisher` (P4)**, through `content_private.has_staff_role('publisher')`; `admin` satisfies it. The
   mobile admin path (`profiles.is_admin` in the app, `has_staff_role('admin')` in the RLS staff clauses) is retired:
   the 1.16 app no longer reads `is_admin`, and M3 removes the staff clauses so no app version can hard-delete or
   approve outside Admin. `profiles.is_admin` stays as the server-maintained mirror until the minimum version reaches
   1.16, then a cleanup migration may drop it.
6. **Sign-in merge on the server (P5)**: `merge_visit` fills only empty account fields and appends a differing device
   note under the account note; the merged note may exceed 280 characters (bounded at 562) so no text is lost, and the
   sheet blocks publishing until it is trimmed.
7. **Reported list order: oldest open report first (P6)**, so the item nearest its 48-hour deadline is on top; this
   follows US5 scenario 5 and owner decision 6 rather than the story's "newest report first" phrase.
8. **Feeling weights (P7, P8)**: 좋았어요 = save weights; 그저 그랬어요 / none = today's visit weights; 아쉬웠어요 = no
   anchor and half a 덜 보기 step per shared reviewed term and credited artist, under the same caps; four tags map to
   mood terms, 마음에 남았어요 stays unmapped.

## Slices (delivery order)

Sizes: S ≈ 1 day, M ≈ 2–3 days, L ≈ 4–5 days. Each slice ends with the CLAUDE.md verification-contract gates named.

| # | Slice | Stories | Size | Depends on | Tests first | Gates |
|---|---|---|---|---|---|---|
| 1 | **M1 thought moderation status** and the edit-returns-to-review trigger | US5 (FR-019, FR-030, SC-005 1.15 path) | S | — | pgTAP 076 | lineage validator, clean replay + pgTAP |
| 2 | **M2 reports and staff review RPCs** + **Admin 감상 section**, then **M3** retiring the mobile staff path | US5, server half of US4 | M (SQL S, Admin M) | 1 | pgTAP 077, 078; Vitest repository then workspace | pgTAP; `admin` typecheck, test, build |
| 3 | **M4 diary sync**: visit columns, six mutation kinds, archive JSON | server half of US1–US3 | M | 1 | pgTAP 079 (incl. SC-005 1.16 path, merge cases, cascades) | pgTAP |
| 4 | **Shared diary core**: domain types, vocabularies, `canRecordVisit`, `visitDateOptions`, `mergeEntry`, mutation variants and DTOs, repositories, `buildDiary`, `diaryNoteStatus` | US1–US3 (shared) | M | 3's contract (not its deploy) | `shared/commonTest` per rule | `shared:ktlintCheck shared:allTests` |
| 5 | **Entry sheet and detail row** (`DiaryEntryViewModel`, App.kt callback removed, DESIGN.md sections first) | US1 | L | 4 | `composeApp/commonTest` VM; iosTest sheet | composeApp + Android host gates |
| 6 | **MY 일기** (`DiaryViewModel`, list, strip, filter, Profile grid and 내 감상 removed) | US2 | M | 4 | `buildDiary` tests (4), VM test, iosTest list | composeApp + Android host gates |
| 7 | **감상 list, report, hide** (`list_exhibition_thoughts`, report sheet, hidden authors, Settings list) | US4 | M | 2 (RPCs), 4 | `visibleThoughts`, repository wire tests, VM test, iosTest menu + sheet | composeApp + Android host gates |
| 8 | **Publish from the diary** (switch, statuses, one-time line, 감상 남기기 → sheet, composer and admin UI removed) | US3 | M | 5, 7 | `diaryNoteStatus`, VM tests, iosTest sheet states | composeApp + Android host gates |
| 9 | **Recommender reads the feeling** | US6 | S–M | 4 (types only) | `VisitFeelingRankingTest`, `TasteProfileRankingTest` profiles | `shared:allTests` |
| 10 | **Privacy page, store record, docs** | FR-033, release | S | — | `web/tests/privacy-page.test.ts` | `web` `npm test`; `git diff --check` |

**Parallel lanes** (no shared files between lanes):
- Lane A (database): 1 → 2-SQL → 3. Then Admin (2-Admin) runs alone in `admin/`.
- Lane B (shared core): 4 can start against the contracts while Lane A is in review.
- After 4: Lane C = 5 → 8 (detail screen, sheet, ThoughtsSection's write side); Lane D = 6 (MY tab, Profile);
  Lane E = 7 (ThoughtsSection's read side, Settings) — 7 and 8 both touch `ThoughtsSection.kt`, so 8 starts after 7
  merges; Lane F = 9 (recommender files only); Lane G = 10 (web and docs) at any time.
- Release gate (1.16): all of slices 1–8 and 10 merged, migrations M1–M4 live on production, Admin deployed, full
  verification contract, manual pass on both platforms (quickstart), then the store submission.

**Smallest shippable 1.16.** The spec marks US1–US5 as P1 and states that US4 "ships with the release, not after it"
and that removing the composer without US3 would remove the only way to write a 감상; only US6 (P2) can be cut. Cut
version: slices 1–8 and 10; slice 9 moves to 1.16.x or 1.17. Feelings and tags are still recorded and synced, so
nothing is lost when 9 lands later. Nothing inside a P1 story is marked optional; cutting the ended-saved strip
(FR-013) or the Settings hidden list (FR-024) would need a spec amendment by the owner.

## Migrations and deploy order

Versions are later than every migration on `develop` (latest `20261111051000_security_storage_boundaries.sql`) and
than every version recorded on staging and production (both at `20261104130000`, read 2026-11-11). pgTAP 075 is the
highest suite; 076–079 are free. If anything newer lands on `develop` first, renumber before the first apply (never
after). `20261111051000` is on `develop` but applied nowhere yet; it is not part of this feature and applies first by
version order under its own plan.

| Step | Where | What | 1.15 effect | Rollback |
|---|---|---|---|---|
| 1 | staging, then production | **M1** `20261112100000_thought_moderation_status` | none visible; an edited approved text now goes back to review (the defect fix) | new migration restoring the old trigger body; columns are inert |
| 2 | staging, then production | **M2** `20261112100100_thought_reports_and_review` | none (new table and RPCs only) | Admin rollback leaves the RPCs unused; drop by a new migration if ever needed |
| 3 | Vercel `gallr-admin` (Preview on staging first) | Admin with the 감상 section | none | redeploy the previous production deployment |
| 4 | staging, then production | **M3** `20261112100200_retire_mobile_thought_moderation` | a staff member on 1.15 loses the in-app queue and in-app delete; authors unaffected | new migration restoring the staff clauses |
| 5 | staging, then production | **M4** `20261112100300_visit_diary_sync` | none (`add_visit`/`remove_visit` unchanged; new archive keys ignored by `ignoreUnknownKeys`, `SupabaseProvider.kt:89-92`) | redefine the sync function without the new kinds by a new migration — only before any 1.16 build is in testers' hands |
| 6 | stores | app **1.16** submitted only after steps 1–5 are live on production | — | halt the phased release; server stays compatible with 1.15 |
| 7 | later, not 1.16 | after the owner sets the minimum to ≥ 1.16.0 in Admin → 앱 버전 (only once 1.16 is live on both stores; spec 098) | 1.15 is blocked by then | new migration M5 (`…_thoughts_require_diary_entry`, next pgTAP number then) makes the table insert path refuse a 감상 with no `my_gallr_visits` row for the same person and exhibition; drop `profiles.is_admin` in the same cleanup if nothing reads it |

The owner applies M1, M2, M3 and M4 to **staging** in that order (Admin Preview on staging between M2 and M3), runs
the staging checks in quickstart, then applies the same four to **production** in the same order with the Admin
production deploy between M2 and M3. Every apply starts with the lineage validator and a dry run that proposes exactly
the expected versions.

## Mobile architecture

Detail in [contracts/shared-diary-api.md](contracts/shared-diary-api.md); the essentials:

- **Domain.** `ExhibitionVisit` gains `visitDate: LocalDate? = null`, `feeling: VisitFeeling? = null`,
  `tags: List<VisitTag> = emptyList()`, `note: String? = null`, `entryUpdatedAt: Instant? = null`. Defaults keep the
  guest payload (`DataStoreVisitRepository.kt:15-32`, schema version 1, `ignoreUnknownKeys`) and the account outbox
  readable without a schema bump. Feelings and tags are stored and sent as wire strings and mapped with
  `fromWire(...)`, so an unknown value from a later version decodes to "none" instead of failing the whole payload.
- **Sync.** `MyGallrAccountMutation` gains `UpdateVisit`, `MergeVisit`, `DeleteEntry`, `PublishNote`, `UnpublishNote`,
  `AcknowledgeNoteApproval`; the reducer `apply()` (`MyGallrAccountSync.kt:418-468`) applies each locally with the
  same rules as the server (`mergeEntry` is one shared function tested against the same case table as pgTAP 079).
  `mergeGuestRecords` (`:250-265`) sends `MergeVisit` instead of `AddVisit`. `MyGallrAccountArchive` gains
  `publicNotes`.
- **Refresh-pending sessions** (PR #405). `App.kt:277` already keeps the account active while
  `isSessionRefreshPending` is true and skips server work; the coordinator's flush then fails without a token and keeps
  the outbox (`MyGallrAccountSync.kt:306-312`). The diary follows that rule: entries save locally and show at once,
  `diaryNoteStatus` maps a pending publish to 보내는 중 (never 다시 시도) while the refresh is pending, the archive is
  not cleared, guest entries are not merged twice (they are cleared only after acknowledgement, `:315-328`), and the
  outbox flushes when the auth state that follows the refresh re-runs the effect. Because requests go out without the
  session while pending (`App.kt:275-276` comment), `ExhibitionThoughtsViewModel` does not reload 감상 then (an
  anonymous read would drop the reporter filter) and the report action shows "연결을 확인한 뒤 다시 시도해 주세요"
  instead of opening sign-in.
- **Repositories.** `VisitRepository` gains `saveEntry` and `deleteEntry`; `PublicNoteRepository` (signed-in only,
  over the coordinator) exposes own public notes and publish/unpublish/acknowledge; `ThoughtRepository` keeps the
  reader side (`exhibitionThoughts` through the RPC, `report`) and loses `submitThought`, `updateThought`,
  `getPendingThoughts`, `approveThought`, `rejectThought`, `getUserThoughts`, `getUserThoughtCount` (the Profile count
  comes from the archive). `HiddenThoughtAuthorRepository` and `DiaryDeviceStateRepository` are device-only DataStore
  stores with private keys `HIDDEN_THOUGHT_AUTHORS_KEY = "hidden_thought_authors_v1"` and
  `DIARY_ENDED_SAVED_DISMISSED_COUNT_KEY = "diary_ended_saved_dismissed_count_v1"`, never cleared on sign-out.
- **ViewModels.** `DiaryEntryViewModel` (one per exhibition, `viewModel(key = "diary-entry-$id") { … }` with an
  initializer) owns the sheet state and the save that today sits in `App.kt:913-939`, including the existing
  `VISIT_RECORDED` intent on first creation only; `DiaryViewModel` owns the 일기 list, filter and strip;
  `ExhibitionThoughtsViewModel` is rewritten for the RPC, hidden authors and report; `HiddenAuthorsViewModel` backs the
  Settings list. All expose `StateFlow` from a private `MutableStateFlow` or `stateIn(WhileSubscribed(5000))`.
- **Navigation** (`AppNavigationState.kt`). The entry sheet and the report sheet are sheets hosted by their screens,
  not destinations, with the open-sheet id in `rememberSaveable` so sign-in (`showAccount`, pushed over the screen)
  returns to the sheet. `MyTabRequest` gains `DIARY_PUBLISHED` (Profile's 감상 count opens MY → 일기 with the filter);
  the ended-saved list is `MyGallrMode.ADD_ENDED_SAVED` reusing `AddPastVisitsScreen`; the hidden list is a
  `SettingsDestination.HIDDEN_AUTHORS` inside `SettingsScreen` (its existing in-screen pattern, `:84-142`).
- **Changed or removed screens.** `ExhibitionDetailScreen` visit prompt (`:314-388`) → `DiaryVisitRow`;
  `ThoughtsSection` (`:49-241`) loses the composer, the emoji button, the admin delete and the own-pending block, gains
  the card menu and 감상 남기기 → sheet; `ThoughtComposer.kt`, `MyThoughtsScreen.kt`, `PendingThoughtsScreen.kt` and
  their ViewModels are deleted; `ProfileScreen` loses the 전시 일기 grid (`:158-165, 292-349`), `DiaryCard`, the admin
  block (`:352-383`) and its local screen flags; MY's 방문 section (`MyGallrScreen.kt:118-125, 166-171, 441-632`)
  becomes 일기; `App.kt`'s `isAdmin` state and profile check (`:252, 291-298`) go.

## Recommender (US6)

Current constants (`LocalExhibitionRecommender.kt:957-996`): saved artist 0.50 / title artist 0.40 / term 0.35 /
detected 0.08 / text 0.20; visited 0.35 / 0.30 / 0.25 / 0.06 / 0.12; `CHOSEN_TERM_WEIGHT` 0.30;
`CHOSEN_ANCHOR_SHARE` 0.6; `DISMISSAL_STEP` 0.25; `DISMISSAL_CAP_SHARE` 0.5.

| Feeling | Rule | Effect |
|---|---|---|
| 좋았어요 | anchor scored with the SAVED weights, source `VISITED` | equal to a save of that show (SC-009) |
| 그저 그랬어요, none | unchanged VISITED weights | equal to today |
| 아쉬웠어요 | no anchor; penalty count 0.5 per shared reviewed term and credited artist, added to the 덜 보기 count before the existing cap: `min(0.25 × (dismissals + 0.5 × disliked), 0.5 × base)` | one such visit costs 0.125 per term and per artist; one 덜 보기 costs 0.175 per term and 0.25 per artist; floored at 0; tier unchanged |
| 좋았어요 + tag | 조용히 머물기 → `mood:quiet-meditative`, 즐거웠어요 → `mood:playful`, 압도적이었어요 → `mood:monumental`, 마음이 무거웠어요 → `mood:dark` count as picked terms (0.30, each further one half the step before) | reason `방문한 "<전시>"와 비슷한 분위기: <용어>`; never the tag or feeling |

마음에 남았어요 (Moving) stays unmapped: `mood:intimate` (친밀함) describes scale and closeness, not being moved, and a
wrong mapping would teach the ranker a taste the visitor never had (P8). Tags on other feelings carry no weight. Tests:
`VisitFeelingRankingTest` (four rules, equality and inequality asserted on basis points) and three new made-up profiles
in `TasteProfileRankingTest` (a loved visit, a disliked visit, a loved visit with a calm tag) whose leaders are worked
by hand like the existing ones.

## UI and Admin

[contracts/diary-ui.md](contracts/diary-ui.md) lists every screen state and every new KO/EN string and the DESIGN.md
sections to add before slices 5–8 (FR-032). [contracts/admin-thoughts.md](contracts/admin-thoughts.md) holds the
Admin section (`?section=thoughts`, label 감상 / "Visitor notes", publisher and admin only).

## Privacy and store

- `web/privacy.html`: line 48 gains diary entries; a new "Public notes and reports" item; line 71/72 adds feelings,
  tags, private notes and reports to what analytics never carries; the effective date (line 14) moves to the release
  date; `web/tests/privacy-page.test.ts:66` changes with it.
- App Store privacy: no new data type. Private notes, feelings, tags and reports are user content linked to the account
  and used for app functionality, which the existing `Other User Content` row (`docs/mobile-analytics-store-
  disclosures.md:102`, `iosApp/iosApp/PrivacyInfo.xcprivacy:44-50`) already declares; its purpose text widens to
  "profile bio, thoughts, diary entries and reports". Google Play: the doc records only the analytics delta (`:60-63`),
  so the owner confirms in Play Console that "Other user-generated content" is already declared as collected and
  linked (it covers thoughts today); no new type either way.
- App Store guideline 1.2 mapping: filtering → every 감상 reviewed before it is public, and any edit returns to review;
  reporting with timely response → 신고 with fixed reasons and a 48-hour staff decision; blocking → 이 사용자의 감상
  숨기기 with a Settings list; contact → Settings 의견 보내기 (`SettingsContent.kt:183-199`, `hello@gallrmap.com`).
  Review notes for 1.16 should name these four places.

## Risks

| Risk | Recommended answer |
|---|---|
| A malformed new mutation fails the whole sync batch (`…account_archive.sql:355-357`) and the outbox retries forever | New kinds never raise for conflicts (no visit, empty note, already approved): they are no-ops; only a malformed payload raises, which the app never builds (wire tests) |
| 1.16 reaches a server without M4 | Submission only after step 5 on production (deploy order); the quickstart's staging pass uses a 1.16 build |
| Client-clock last-write-wins misorders two devices | Accepted by the spec ("the most recent save wins"); ties keep the server row; documented in the contract |
| Merged note over 280 | Allowed to 562, publish blocked with a counter line until trimmed (P5) |
| Material3 `DatePicker` defaults to the theme primary | Pass explicit `DatePickerDefaults.colors` (selected day `onBackground`), covered by an iosTest that asserts no accent on the selected day |
| A rejected 감상 reads 검토 중 on 1.15 | Accepted in research §6; ends when the minimum version is raised |
| Staff miss the 48-hour window | Admin shows the time since the oldest open report and the deadline; SC-004 query in quickstart for the owner's weekly check |
| `thought_reports` grows a reporter's identity | Reporter ids never leave staff RPCs (counts and reasons only); cascades on account deletion |

## Owner questions

1. **Mobile moderation on 1.15**: retire it the day the Admin 감상 section goes live (M3, recommended: two staff,
   both on Admin) **or** keep the 1.15 in-app queue and delete until the minimum version reaches 1.16?
2. **Reported list order**: oldest open report first (recommended, matches the 48-hour rule and US5 scenario 5)
   **or** newest report first as US5's opening sentence says?

## Complexity Tracking

No constitution violations. No entries.
