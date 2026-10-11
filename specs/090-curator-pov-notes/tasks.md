# Tasks: Curator POV Notes

**Input**: Design documents from `/specs/090-curator-pov-notes/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md) (R1–R14),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Included. The constitution's Principle II (Test-First) is non-negotiable: every tested
path below starts from a test that is written and seen to fail before its implementation task.

**Organization**: Tasks are grouped by user story so each story is an independent, testable
increment. Story order follows the spec's priorities: US1 editor review (P1), US2 home carousel
(P1), US3 detail block (P2), US4 carousel export (P3).

**Numbering note**: develop gained migration `20261011010000_legacy_reader_grants_events_editors`
and pgTAP suite 057 after the plan was written, so this feature's migration is
`20261012000000_curator_notes.sql` and its suite is `058_curator_notes.test.sql`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on unfinished tasks)
- **[Story]**: US1–US4 from spec.md
- Paths are repository-relative; the worktree for this branch is the checkout to use

## Path Conventions

- Database: `supabase/migrations/`, `supabase/tests/database/`
- Edge Function: `supabase/functions/draft-curator-notes/`
- Shared (KMP): `shared/src/commonMain/kotlin/com/gallr/shared/`, tests in `shared/src/commonTest/`
- App: `composeApp/src/commonMain/kotlin/com/gallr/app/`, tests in `composeApp/src/commonTest/`
- Admin: `admin/src/`

---

## Phase 1: Setup

**Purpose**: Bring the branch up to date and add the dependencies the plan approved.

- [x] T001 Merge `origin/develop` into `090-curator-pov-notes` in the worktree so the branch carries #325–#329 (the grants migration and suite 057); resolve nothing by hand except `CLAUDE.md`'s Recent Changes list if it conflicts
- [x] T002 [P] Add `uqr` 0.1.3 and the current stable `fflate` to `admin/package.json` dependencies and refresh `admin/package-lock.json` with `npm install --package-lock-only` under Node 22.23.1
- [x] T003 [P] Create `supabase/functions/draft-curator-notes/deno.json` (imports: `@supabase/supabase-js` 2.112.2, `@anthropic-ai/sdk` pinned to the current npm release, `zod` pinned; `compilerOptions.strict`; tasks `check` and `test` mirroring `promoted-nearby`), an empty `deno.lock` to be filled by the first `deno task check`, and `README.md` with the contract summary from `contracts/draft-curator-notes-function.md` (modes, auth, env names, logging, deploy notes)
- [x] T004 [P] Add `.github/workflows/product-surfaces.yml` coverage if the Edge discovery step does not already pick up every function with a `deno.json` (read the workflow first; if it globs, this task is a no-op to record)

---

## Phase 2: Foundational

**Purpose**: Schema, security and the public read view every story depends on. The migration is
written once; later phases add functions through the same file before it ships, never through a
second migration.

**⚠️ CRITICAL**: US1–US4 all read or write these objects.

- [x] T005 Write the schema half of `supabase/tests/database/058_curator_notes.test.sql` (pgTAP, `begin … rollback`): tables and enum exist; RLS enabled on `content.curator_notes` and `content.curator_note_versions`; `anon`, `authenticated` and `service_role` have no table privileges; the partial unique index allows one `draft` per exhibition; `public.curator_notes_published` is selectable by `anon` and `authenticated`, returns only `approved` rows, and drops a row whose exhibition is missing from `exhibition_catalog_v2`; `mobile_analytics_discovery_kind` accepts `curator_note`. Run it against the local stack and confirm it fails
- [x] T006 Create `supabase/migrations/20261012000000_curator_notes.sql` with: enum `content.curator_note_status`; tables `content.curator_notes` and `content.curator_note_versions` per data-model.md (columns, checks, indexes, comments); RLS enabled and all privileges revoked from `public, anon, authenticated, service_role`; view `public.curator_notes_published` (approved rows joined to `public.exhibition_catalog_v2`, columns per data-model.md) with `select` granted to `anon, authenticated`; the `mobile_analytics_discovery_kind` check constraint re-created with `curator_note` added. Wrap in `begin; … commit;`, idempotent DDL
- [x] T007 Run `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs`, `supabase db reset` on the local stack and the schema half of suite 058; fix until green
- [x] T008 [P] Add `CURATOR_NOTE` (`curator_note`) to `DiscoveryKind` in `shared/src/commonMain/kotlin/com/gallr/shared/analytics/MobileAnalyticsEvent.kt` with a KDoc line, and add `curator_note` to `DISCOVERY_KINDS` in `supabase/functions/mobile-analytics/handler.ts` with a passing/failing pair in `supabase/functions/mobile-analytics/handler_test.ts` written first

**Checkpoint**: Clean replay passes suite 058's schema cases; the view answers through PostgREST with the publishable key and returns no rows.

---

## Phase 3: User Story 1 - Editors review automatically drafted notes (Priority: P1) 🎯 MVP

**Goal**: Drafts are produced on a schedule and on demand, stored with provenance, reviewed in Admin (edit, mark unsupported, approve, decline, regenerate) with revision guards, and published only on approval.

**Independent Test**: Seed two published exhibitions on the local stack; run the function in `scheduled` mode with a fake Claude backend; confirm two drafts in the queue; approve one with an edit and decline the other with a reason; confirm `curator_notes_published` shows exactly the approved one and the declined exhibition is `auto_draft_blocked`.

### Database functions (tests first)

- [ ] T009 [US1] Extend `supabase/tests/database/058_curator_notes.test.sql` with the function cases from `contracts/database-functions.md`: candidates (each qualification rule in and out, featured-first order, blocked / open-draft / unchanged-hash exclusions, packet keys and hash shape); store (version numbering, single open draft, grounding-key check, `unusable` rows); queue and get as a publisher, denied for contributor, anonymous and signed-in visitor; edit, mark, approve (`curator_note_unsupported`, supersession, reviewer snapshot for an editor-profile user and a staff-only user), decline, request, export audit; `curator_note_stale` on every write. Run and confirm the new cases fail
- [ ] T010 [US1] Add to `supabase/migrations/20261012000000_curator_notes.sql`: `content_private.list_curator_note_candidates(p_limit)`, `content_private.store_curator_note_draft(p_payload)` (both granted to `service_role` only), the audit helper `content_private.record_curator_note_audit(...)` following `record_route_audit`, and the packet builder `content_private.curator_note_packet(p_exhibition_id)` returning `(packet jsonb, packet_hash text, source_version_id uuid)` with sorted-key canonical JSON and `encode(sha256(...), 'hex')`
- [ ] T011 [US1] Add to the same migration the staff functions as `content_private.*_impl` plus `public.*` wrappers granted to `authenticated`: `list_curator_note_queue`, `get_curator_note`, `save_curator_note_edit`, `mark_curator_note_sentence`, `approve_curator_note` (reviewer-name snapshot via `content.editor_memberships` → `public.editors`, else `profiles.display_name`), `decline_curator_note`, `request_curator_note_draft`, `record_curator_note_export`; every one checks `content_private.has_staff_role('publisher')` and raises the contract's errors
- [ ] T012 [US1] Add to the same migration `content_private.schedule_curator_note_drafts()` (vault secrets `gallr_curator_notes_function_url` and `gallr_curator_notes_schedule_token`, validation modelled on `20260804010156_legacy_mobile_catalog_mirror.sql`, `net.http_post` of `{"mode":"scheduled","limit":20}`) and `select cron.schedule('curator_notes_daily', '0 18 * * *', $$select content_private.schedule_curator_note_drafts()$$)` guarded against duplicates
- [ ] T013 [US1] Re-run the lineage validator, `supabase db reset`, the full suite 058, `supabase db lint` and `supabase db advisors` on the local stack; fix until green

### Edge Function (tests first)

- [ ] T014 [P] [US1] Write `supabase/functions/draft-curator-notes/schema.ts` test expectations inside `supabase/functions/draft-curator-notes/drafting_test.ts`: a fixture packet and hash shared with suite 058 (`fixtures/packet.json`), canonicalisation equals the SQL hash, each validation rule (unknown grounding key, number not in sources, Hangul missing in KO, Hangul present in EN, length exceeded, insight count) with a passing and a failing draft, refusal → `provider_refused`, null parse → `parse_failed`, `model` recorded from the answer. Confirm the file fails to compile against the not-yet-written modules
- [ ] T015 [P] [US1] Write `supabase/functions/draft-curator-notes/handler_test.ts` with a fake backend: method and content-type validation, body size cap, `scheduled` auth (good, bad, missing token), `single` auth (publisher, contributor, anonymous), `single` for a non-candidate → 404, time budget with a slow backend reporting `remaining`, response shapes for 200/400/401/404/503, one structured log line per exhibition without note text
- [ ] T016 [US1] Implement `supabase/functions/draft-curator-notes/schema.ts` (Zod `NoteDraftSchema` per the contract) and `drafting.ts`: `canonicalPacket`, `packetHash` (Web Crypto SHA-256), `buildMessages(packet, promptVersion, examples)`, `validateDraft(draft, packet) → { ok } | { code }`, `toStorePayload(...)`; `PROMPT_VERSION = "2026-10-11.1"`
- [ ] T017 [US1] Write `supabase/functions/draft-curator-notes/prompt/system.md` (curator voice rules: why now, what to look for in the room, connection to practice or to other Seoul shows, one concrete tip; no summary, no marketing, no facts outside the packet; KO and EN written independently; grounding keys per paragraph) and `prompt/examples.json` with two reference notes drawn from the sample the owner approved on 2026-10-11, each carrying `grounding` arrays that reference only packet keys
- [ ] T018 [US1] Implement `supabase/functions/draft-curator-notes/backend.ts`: `createDraftingBackend(env)` exposing `listCandidates(limit)`, `requestPacket(exhibitionId, jwt)`, `storeDraft(payload)`, `isPublisher(jwt)` (calls `public.admin_current_staff()` with the caller's token on a publishable-key client) and `draft(messages)` which calls `client.messages.parse` on `claude-opus-5-5` (env `CURATOR_NOTES_MODEL` override) with `max_tokens: 8000`, `output_config: { effort: "high", format: zodOutputFormat(NoteDraftSchema) }`, `betas: ["server-side-fallback-2026-07-01"]`, `fallbacks: "default"`, SDK timeout 90 s, `maxRetries: 2`, returning `{ parsed, model, stopReason, usage }`
- [ ] T019 [US1] Implement `supabase/functions/draft-curator-notes/handler.ts` (`createDraftHandler({ env, createBackend, now })`: CORS for Admin origins, auth per mode, candidate loop with the 50 s budget, per-exhibition outcomes, structured logging) and `index.ts` (`Deno.serve(createDraftHandler({...}))`); run `deno task test` and `deno task check` until green and commit the generated `deno.lock`

### Admin workspace (tests first)

- [ ] T020 [P] [US1] Write `admin/src/repositories/AdminCuratorNoteRepository.test.ts` against a fake Supabase client: RPC names and arguments for `listQueue`, `get`, `saveEdit`, `markSentence`, `approve`, `decline`, `requestDraft` (RPC then `functions.invoke("draft-curator-notes", { mode: "single" })`), `recordExport`; mapping of `PT409 curator_note_stale` → `CuratorNoteStaleError`, `curator_note_unsupported` → `CuratorNoteUnsupportedError`, `42501` → `CuratorNoteNotStaffError`; malformed response rejected; fail-closed when the client is null
- [ ] T021 [P] [US1] Write `admin/src/components/CuratorNotesWorkspace.test.tsx`: queue renders oldest-first with the featured flag and prompt version; opening an item shows the facts packet beside both language drafts with grounding chips; editing marks `edited`; marking a sentence unsupported disables Approve with the reason; Approve, Decline (reason required, note ≤ 500) and Regenerate call the repository with the shown revision; a stale error reloads and shows the message; a denial shows the not-staff state
- [ ] T022 [US1] Implement `admin/src/repositories/AdminCuratorNoteRepository.ts` (types `CuratorNoteQueueItem`, `CuratorNoteVersion`, `CuratorNoteFacts`, `CuratorNoteDecision`, the three errors, `SupabaseAdminCuratorNoteRepository`, and an in-memory implementation for fixture mode) until T020 passes
- [ ] T023 [US1] Implement `admin/src/components/CuratorNotesWorkspace.tsx` (queue list, review pane, per-paragraph grounding chips, per-sentence unsupported toggles, Approve / Decline dialog / Regenerate, stale and denied states) using the existing `Dialogs.tsx` and the monochrome styles in `admin/src/styles.css`; no new colours, 0dp corners
- [ ] T024 [US1] Wire `Notes` into `admin/src/components/PrimaryNavigation.tsx` (publisher and admin roles) and `admin/src/App.tsx` (construct the repository beside `routeRepository`, render the workspace for the section), add the strings to `admin/src/staff-i18n-messages.ts`, and extend `admin/src/components/PrimaryNavigation.test.tsx` for the new item; run `npm run typecheck && npm test && npm run build` in `admin/`

**Checkpoint**: The independent test above passes on the local stack with the fake backend; with a staging deployment and the real key, a `single` draft lands in the queue within two minutes.

---

## Phase 4: User Story 2 - Visitors meet the curator's eye on the home tab (Priority: P1)

**Goal**: The home tab shows approved notes for running or upcoming exhibitions as the 큐레이터의 시선 / CURATOR'S EYE carousel between the hero and the rails; a tap opens the exhibition with the note in view; impressions and taps are attributed.

**Independent Test**: With three approved notes and one draft in the data (suite 058 fixtures or the local stack), the home tab shows three cards newest-approval first with cover, hook, one insight line, counter and the next card peeking; none for the draft; a tap opens the detail screen flagged `scrollToNote`; switching to English switches the cards with Korean fallback where English is empty.

### Shared (tests first)

- [ ] T025 [P] [US2] Write `shared/src/commonTest/kotlin/com/gallr/shared/curatornote/CuratorEyeCardsTest.kt`: notes whose exhibition is not in the catalogue are dropped; ended exhibitions dropped against an injected `today`; one card per exhibition; newest `approvedAt` first; limit 10; language helpers fall back to Korean when English is empty; `firstInsight` returns the first paragraph
- [ ] T026 [P] [US2] Write `shared/src/commonTest/kotlin/com/gallr/shared/data/network/dto/CuratorNoteDtoTest.kt`: a well-formed row maps to `CuratorNote`; `insights_*` objects map to text lists; a row missing `exhibition_id`, `hook_ko` or `approved_at` is skipped by the page decoder and counted, the other rows survive; unknown keys ignored
- [ ] T027 [P] [US2] Write `shared/src/commonTest/kotlin/com/gallr/shared/repository/CuratorNoteRepositoryImplTest.kt` with a fake `CuratorNoteApiClient`: `getPublishedNotes` returns `Result.success(list)`, a thrown client error becomes `Result.failure`, `getNote` returns `null` for an empty page
- [ ] T028 [US2] Implement `shared/src/commonMain/kotlin/com/gallr/shared/curatornote/CuratorNote.kt` (`CuratorNote`, `CuratorEyeCard`, `curatorEyeCards`, `CURATOR_EYE_LIMIT`) with KDoc, until T025 passes
- [ ] T029 [US2] Implement `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/CuratorNoteDto.kt` (`@Serializable`, `@SerialName` snake_case, `toDomain()`, `decodeCuratorNotePage(json): Pair<List<CuratorNote>, Int>` skipping malformed rows) until T026 passes
- [ ] T030 [US2] Implement `shared/src/commonMain/kotlin/com/gallr/shared/data/network/CuratorNoteApiClient.kt` (`getPublished(limit = 30)`, `getForExhibition(id)` against `curator_notes_published` with `SupabaseApiHeaders`, Ktor client built like `EventApiClient`) and `shared/src/commonMain/kotlin/com/gallr/shared/repository/CuratorNoteRepository.kt` + `CuratorNoteRepositoryImpl.kt` (`runCatching` boundary, `AppLog.tagged("CuratorNotes")` with `curator_note_row_skipped` count) until T027 passes
- [ ] T031 [US2] Extend `shared/src/commonMain/kotlin/com/gallr/shared/home/HomeFeed.kt` with `curatorEye: List<CuratorEyeCard>` (default empty, included in `isEmpty`, `EMPTY` updated) and add a `HomeFeedBuilderTest` case in `shared/src/commonTest/kotlin/com/gallr/shared/home/HomeFeedBuilderTest.kt` showing `buildHomeFeed(..., curatorNotes = …)` fills it through `curatorEyeCards`; run `./gradlew shared:ktlintCheck shared:allTests`

### App (tests first)

- [ ] T032 [P] [US2] Extend `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/HomeViewModelTest.kt`: a `CuratorNoteRepository` fake feeding three notes yields `feed.curatorEye` of three cards; a failing repository yields an empty section while the rest of the feed loads and logs `curator_notes_load_failed`
- [ ] T033 [P] [US2] Extend `composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/home/HomePresentationTest.kt`: `homeCopy(lang, name).curatorEyeTitle` is `큐레이터의 시선` / `CURATOR'S EYE`; `curatorEyeReviewerLine(name, lang)` is `<이름> 검수` / `Reviewed by <name>`; `curatorEyeCardDescription(card, index, count, lang)` composes the accessibility sentence from the contract
- [ ] T034 [US2] Update `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/HomeViewModel.kt` to take `CuratorNoteRepository`, load notes on the background dispatcher alongside the catalogue, and pass them to `buildHomeFeed`; update the factory and `App.kt` construction until T032 passes
- [ ] T035 [US2] Add the strings and helpers to `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/home/HomePresentation.kt` until T033 passes
- [ ] T036 [US2] Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/home/CuratorEyePager.kt`: `HorizontalPager` with `contentPadding` so the next card peeks by `GallrSpacing.xl`, `beyondViewportPageCount = 1`, `pagerCounter`, card = `AsyncImage` cover with the hero's no-image treatment and `GrainWash` at the collection strength, hook (`headlineSmall`), one insight line (`bodyMedium`, 2 lines, ellipsis), reviewer line (`labelSmall`); one merged semantics node per card; `reduceMotion` disables snap animation; 0dp corners; accent only on the active counter
- [ ] T037 [US2] Place the section in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/home/HomeScreen.kt` after `home-hero` and before `home-for-you` (`item(key = "home-curator-eye")`, only when `state.feed.curatorEye` is not empty) with `SectionHeader(copy.curatorEyeTitle)`; emit `exhibition_impression` with `DiscoveryKind.CURATOR_NOTE` and the page's `PositionBucket` once per card per tab visit through the existing `onImpressions` path (add `HomeSection.CURATOR_EYE` where `HomeSection` is defined), and `exhibition_opened` with the same kind on tap via `onExhibitionTap(exhibition, HomeOrigin.CURATOR_EYE)`
- [ ] T038 [US2] Thread `scrollToNote` through `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`: a `HomeOrigin.CURATOR_EYE` tap sets the detail destination with `scrollToNote = true`; other origins leave it false; map `HomeOrigin.CURATOR_EYE` to `DiscoveryKind.CURATOR_NOTE` in the analytics mapping and extend its test in `composeApp/src/commonTest/kotlin/com/gallr/app/` where `HomeOrigin` is tested
- [ ] T039 [US2] Run `./gradlew composeApp:ktlintCheck composeApp:allTests androidApp:ktlintCheck androidApp:lintDebug androidApp:assembleDebug`; walk the section on the API 37 emulator against the local stack with three approved notes (see quickstart §3) in Korean, English and at 200% font scale; capture screenshots

**Checkpoint**: The independent test passes on the emulator; the home tab is unchanged when no note is approved.

---

## Phase 5: User Story 3 - Visitors read the full note on the exhibition page (Priority: P2)

**Goal**: The detail screen shows the newest approved note in a labelled block with the editor's name and the disclosure line, and nothing otherwise.

**Independent Test**: Open an exhibition with an approved note and one without on the local stack: the first shows the block (label, hook, paragraphs, tip, "AI 초안 · <이름> 검수"), the second shows no block or placeholder; a superseded version is never shown; arriving from the carousel lands with the block in view.

- [ ] T040 [P] [US3] Write `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/CuratorNoteViewModelTest.kt`: `Loading` first; `Ready(note)` from the repository; `Ready(null)` for an exhibition without a note; `Error` on failure with `curator_note_load_failed` logged; the state is per exhibition id
- [ ] T041 [P] [US3] Write `composeApp/src/commonTest/kotlin/com/gallr/app/ui/detail/CuratorNotePresentationTest.kt`: block label `큐레이터의 시선` / `CURATOR'S EYE`, tip prefix `관람 팁` / `Viewing tip`, disclosure `AI 초안 · <이름> 검수` / `Drafted with AI, reviewed by <name>`, Korean fallback when English is empty
- [ ] T042 [US3] Implement `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/CuratorNoteViewModel.kt` (constructor injection, `viewModelFactory`, `StateFlow<CuratorNoteUiState>`) until T040 passes
- [ ] T043 [US3] Implement `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/CuratorNotePresentation.kt` and `CuratorNoteBlock.kt` (label `labelLarge` uppercase in EN, hook `titleLarge`, paragraphs `bodyLarge`, tip `bodyMedium` medium weight, disclosure `labelSmall` `onSurfaceVariant`; renders only for `Ready` with a note) until T041 passes
- [ ] T044 [US3] Add `curatorNote: CuratorNoteUiState` and `scrollToNote: Boolean` parameters to `composeApp/src/commonMain/kotlin/com/gallr/app/ui/detail/ExhibitionDetailScreen.kt`, place the block between the description and the Thoughts section, and scroll so the label sits at the top inset once the block is laid out when `scrollToNote` is true and a note exists
- [ ] T045 [US3] Obtain the `CuratorNoteViewModel` with `viewModel { … }` (initializer form) at the detail destination in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`, pass its state and `scrollToNote`; run `./gradlew composeApp:ktlintCheck composeApp:allTests androidApp:assembleDebug` and verify both detail cases on the emulator

**Checkpoint**: US2 and US3 together: carousel tap lands on the block.

---

## Phase 6: User Story 4 - Staff export a note as an Instagram carousel (Priority: P3)

**Goal**: From an approved note in Admin, staff download one archive with 1080×1350 slides (cover, two to four insight slides, closing slide with dates, venue, disclosure and QR) and a caption file, in the chosen language.

**Independent Test**: Export one approved note in Korean and in English; each archive holds the expected slide count at 1080×1350, readable text on every slide, a scannable code on the last slide and a `caption.txt` whose hashtags come only from the packet; a note with too much text reports what was left out; a missing cover refuses the export with a reason; the export is recorded in the audit log.

- [ ] T046 [P] [US4] Write `admin/src/export/carousel.test.ts` for the pure `layoutCarousel(note, facts, language, measure)`: splits insight text across slides with a fake measurer at 44px; never shrinks; four-slide cap with `omitted` sentences reported; never emits an empty slide; closing slide fields (dates `2026.10.01 – 11.30`, venue, district, hours, tip, disclosure line); caption text and hashtags drawn only from the packet (venue, district, term names, spaces removed, at most six term tags)
- [ ] T047 [P] [US4] Write `admin/src/export/carouselZip.test.ts`: entry names `01-cover.png`, `02-insight.png` …, `0N-closing.png`, `caption.txt`, optional `report.txt`; stored entries; PNG dimension check by header bytes on a fake renderer output
- [ ] T048 [US4] Implement `admin/src/export/carousel.ts`: `layoutCarousel` (pure), `renderCarousel(layout, assets)` drawing each slide on an offscreen 1080×1350 canvas (fonts via `document.fonts.load`, poster fetched and sampled the way `gallery/src/exhibitionQr.ts` does, palette wash on the cover, paper/ink choice with ≥ 7:1 contrast else white/black, QR from `uqr` on a white tile linking to the exhibition's public URL, wordmark and slide number), and `exportCarousel(...)` orchestrating fetch → layout → render → zip with a 30 s guard; until T046 passes
- [ ] T049 [US4] Implement `admin/src/export/carouselZip.ts` with `fflate.zipSync` (level 0) until T047 passes
- [ ] T050 [US4] Create `admin/src/components/CarouselExportDialog.tsx` (language choice limited to languages the version has, progress state, refusal with reason, download trigger, then `repository.recordExport(versionId, language, slideCount)`), open it from the approved-version view in `CuratorNotesWorkspace.tsx`, and extend `CuratorNotesWorkspace.test.tsx` for the dialog flow; run `npm run typecheck && npm test && npm run build`
- [ ] T051 [US4] Add the export type sizes (title 64px, body 44px, caption 32px at 1080 width) and the carousel rules to the exported-content exception in `DESIGN.md`, next to the share card entry, and time one export on a laptop (quickstart §4)

**Checkpoint**: Both archives verified by hand per the independent test.

---

## Phase 7: Polish & Cross-Cutting

- [ ] T052 [P] Add the "Curator's eye" section and the detail note block to `DESIGN.md` (Home tab patterns, type roles, counter, peek, disclosure line) and a Decisions Log row dated 2026-10-11 (assisted drafting disclosed by name; owner decision A)
- [ ] T053 [P] Add the 1.13.0 (or next) Unreleased entries to `CHANGELOG.md`: Added (home carousel, detail block, Admin Notes, carousel export), Infrastructure (migration `20261012000000_curator_notes`, suite 058, Edge Function `draft-curator-notes`, `curator_note` discovery kind)
- [ ] T054 [P] Document operations in `supabase/functions/draft-curator-notes/README.md`: secrets (`ANTHROPIC_API_KEY`, `CURATOR_NOTES_SCHEDULE_TOKEN`, optional `CURATOR_NOTES_MODEL`) injected from 1Password, vault entries and cron, staging-first deployment, how to read the per-exhibition log line, cost expectations (≈ USD 0.04 per draft), and the prompt-version bump rule; link it from `supabase/AGENTS.md`'s function list if one exists
- [x] T055 Update `specs/090-curator-pov-notes/plan.md`, `data-model.md`, `contracts/database-functions.md` and `quickstart.md` references from suite 057 to 058 and the migration name to `20261012000000_curator_notes` (done with the task list on 2026-10-11; the Constitution Check is unchanged)
- [ ] T056 Run every step of `specs/090-curator-pov-notes/quickstart.md` (database, function, shared and app gates, Admin gate, analytics test, docs `git diff --check`); record what ran and what did not in the handoff
- [ ] T057 Review every log call for ids, URLs or note text (none allowed), every new composable for 0dp corners and accent use, and every new SQL function for `security definer` + `set search_path = ''` + schema-qualified names

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** → **Foundational (Phase 2)** → user stories.
- **US1 (Phase 3)** needs only Phase 2. It is the MVP: without approved notes nothing else has content.
- **US2 (Phase 4)** needs Phase 2 (view, discovery kind). It renders nothing until US1 approves a note, but it is testable on its own with suite 058 fixtures or rows inserted into the local stack.
- **US3 (Phase 5)** needs Phase 2 and the shared read path from US2 (T028–T030). If US3 is built first, do T028–T030 as part of it.
- **US4 (Phase 6)** needs US1's Admin workspace (T022–T024) for its entry point.
- **Polish (Phase 7)** after the stories you ship.

### Within each story

- Tests before implementation (T009 before T010–T012; T014/T015 before T016–T019; T020/T021 before T022–T024; T025–T027 before T028–T031; T032/T033 before T034–T038; T040/T041 before T042–T045; T046/T047 before T048–T050).
- Database before Edge Function before Admin inside US1; shared before ViewModel before UI inside US2 and US3.

### Parallel Opportunities

- Phase 1: T002, T003, T004 together.
- Phase 2: T008 beside T005–T007.
- US1: T014 and T015 together; T020 and T021 together; the Edge Function (T014–T019) can proceed on fakes while T010–T013 are in review.
- US2: T025, T026, T027 together; T032 and T033 together.
- US3: T040 and T041 together.
- US4: T046 and T047 together.
- Phase 7: T052, T053, T054 together.

---

## Parallel Example: User Story 1

```bash
# Tests first, in parallel:
Task: "Write drafting_test.ts (packet hash, validation rules, refusal, parse failure)"
Task: "Write handler_test.ts (auth per mode, budget, response shapes)"
Task: "Write AdminCuratorNoteRepository.test.ts (RPC mapping, errors, fail-closed)"
Task: "Write CuratorNotesWorkspace.test.tsx (queue, review, unsupported blocks approve)"

# Then implementations that do not share files:
Task: "Implement schema.ts + drafting.ts"
Task: "Implement AdminCuratorNoteRepository.ts"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 and Phase 2.
2. Phase 3 (US1): the migration's functions, the Edge Function on fakes, the Admin Notes workspace.
3. Validate the independent test on the local stack; deploy the function to staging with the real key and draft one exhibition on demand.

### Incremental Delivery

1. US1 → editors can review and approve (nothing visible to visitors yet).
2. US2 → the home carousel appears as soon as one note is approved.
3. US3 → the detail block completes the carousel's destination.
4. US4 → exports for Instagram.

### Scope note

Every task keeps business rules in SQL or `shared`, the Edge Function to drafting and validation, and composables to rendering state (constitution VI). No consumer surface is added to the web (constitution VII).

---

## Notes

- 57 tasks: Setup 4, Foundational 4, US1 16, US2 15, US3 6, US4 6, Polish 6.
- Keep `gradlew.bat` out of commits (`git checkout -- gradlew.bat` before each commit on Windows).
- Kotlin/Native rejects commas in backtick test names.
- Commit after each task or logical group; stop at each checkpoint and run that story's independent test.
