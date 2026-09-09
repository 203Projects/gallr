# Tasks: Personal Exhibition Map

> **Status: Partially implemented and superseded historical plan.** The setup,
> explicit country identity, abstract-map delivery, and provider removal shipped.
> Later My Gallr and mobile-discovery specifications delivered the supported
> visit archive and external map handoff. The unchecked US2–US5 boxes below are
> not the active roadmap: remaining Near Me, repeat-visit, photo-matching, or
> diary expansion requires a fresh specification against the current MapLibre,
> My Gallr, privacy, and analytics contracts. See `TODOS.md` for open work.

**Input**: Design documents from `/specs/053-personal-exhibition-map/`
**Prerequisites**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `handoff.md`, `contracts/`
**Tests**: Required by Constitution Principle II. Every tested implementation task is preceded by a
focused test task that must be run and observed failing.

**Organization**: Tasks are grouped by user story so abstract discovery, Near Me, manual visits,
photo matching, and diary filters can each be demonstrated independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel because it changes different files and has no unfinished dependency.
- **[Story]**: Maps the task to `US1`–`US5` in `spec.md`.
- Paths are repository-relative and name the intended implementation/test surface.

---

## Phase 1: Setup and Baseline

**Purpose**: Protect migration lineage, establish reproducible geometry provenance, and record the
current app behavior before any feature implementation.

- [x] T001 Run `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs`,
  `./gradlew shared:allTests`, `./gradlew composeApp:testDebugUnitTest`, and `git diff --check`; record
  any pre-existing failure separately and do not mask it with feature changes.
- [x] T002 [P] Add Natural Earth source/version/license and deterministic transformation instructions
  to `scripts/map-geometry/README.md`; create `scripts/map-geometry/source/` only for the minimal
  public-domain boundary inputs needed by Korea and Seoul.
- [x] T003 [P] Capture the existing map/provider removal inventory in
  `specs/053-personal-exhibition-map/provider-removal-checklist.md`, covering Gradle aliases,
  Android dependencies/config, iOS cinterop definitions/SPM references, credentials/configuration,
  and all `MapView` call sites.

**Checkpoint**: Baseline is known, migration lineage is safe, and geometry/provider changes have an
auditable source and removal checklist.

---

## Phase 2: Foundational — Explicit Country Identity

**Purpose**: Make catalog geography unambiguous before country/city scope logic is introduced.

**CRITICAL**: Complete this phase before any user-story implementation.

### Failing tests first

- [x] T004 [P] Add failing pgTAP coverage for `country_code` default/validation, immutable venue-to-
  version snapshot behavior, canonical catalog payload/checksum/reconciliation, and legacy mirror
  propagation in `supabase/tests/database/026_catalog_country_code.test.sql`; run the focused test and
  confirm the missing-column failures.
- [x] T005 [P] Add failing DTO/domain/cache compatibility tests for explicit `KR`, unknown-field
  rollout compatibility, invalid code rejection, and cached round trips in
  `shared/src/commonTest/kotlin/com/gallr/shared/data/network/dto/ExhibitionCountryCodeTest.kt` and
  `shared/src/commonTest/kotlin/com/gallr/shared/repository/DataStoreExhibitionCountryCodeTest.kt`;
  run them and confirm failure before model changes.
- [x] T006 [P] Read `gas/AGENTS.md`, then add the failing `country_code` header/upsert/omission cases to
  the existing `gas/SyncExhibitions` test surface specified there; prove the current
  `KNOWN_COLUMNS` contract does not sync the field.

### Implementation

- [x] T007 Implement the additive `country_code` columns, backfill/check constraints, canonical source
  and snapshot propagation, catalog payload/checksum/refresh/reconcile changes, and legacy mirror/
  restore compatibility in
  `supabase/migrations/20260811110015_catalog_country_code.sql`; preserve existing mobile readers.
- [x] T008 [P] Add `countryCode` to `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`,
  `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionDto.kt`,
  `shared/src/commonMain/kotlin/com/gallr/shared/data/network/ExhibitionCatalogSource.kt`, and
  `shared/src/commonMain/kotlin/com/gallr/shared/repository/DataStoreExhibitionCache.kt`; update all
  constructors/fixtures with explicit `KR` and keep the temporary DTO rollout default.
- [x] T009 [P] Add `country_code` to the exhibition sync `KNOWN_COLUMNS` and payload handling in
  `gas/SyncExhibitions.gs`, preserving existing default behavior when an older sheet lacks the header.
- [x] T010 Run the focused shared/GAS/pgTAP tests, a clean `supabase db reset`, migration-lineage
  validation, and DB lint; do not begin US1 until all country propagation contracts pass.

**Checkpoint**: Every active and historical catalog path has explicit country identity, with no owner
UI expansion beyond the current Korea default.

---

## Phase 3: User Story 1 — Explore the Abstract Korea Map (Priority: P1) 🎯 Visual MVP

**Goal**: Replace the street-map tab with the approved Korea/Seoul abstract dot map, grouped exhibition
marks, explicit scopes, and an equivalent accessible result list.

**Independent Test**: With a fixed catalog, navigate Korea → Seoul → district/exhibition in at most
three selections; select a shared venue mark and access every grouped exhibition without location or
visit infrastructure.

### Failing tests first

- [x] T011 [P] [US1] Add failing pure tests for `MapScope`, Korea/Seoul registry resolution, catalog-
  derived district identity, child aggregation, mixed saved/unexplored counts, and incomplete
  coordinates in `shared/src/commonTest/kotlin/com/gallr/shared/map/MapScopeRegistryTest.kt` and
  `shared/src/commonTest/kotlin/com/gallr/shared/map/ScopeAggregateTest.kt`.
- [x] T012 [P] [US1] Add failing pure tests for normalized projection, deterministic nearest-free-cell
  snapping, same-coordinate grouping, capacity overflow, stable recomputation, and the prohibition on
  display coordinates as geographic inputs in
  `shared/src/commonTest/kotlin/com/gallr/shared/map/DotMapProjectorTest.kt`.
- [x] T013 [P] [US1] Add failing Compose/ViewModel tests for initial All mode, Korea→Seoul→district
  transitions, mark/list selection parity, grouped results, loading/empty/missing-geometry states, and
  bilingual labels in
  `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/PersonalMapViewModelTest.kt` and
  `composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/map/DotMapHitTestTest.kt`.

### Implementation

- [x] T014 [P] [US1] Implement documented public `MapScope`, `DotMapGeometry`, `ProjectedMapMark`, and
  `ScopeAggregate` models in `shared/src/commonMain/kotlin/com/gallr/shared/data/model/map/`.
- [x] T015 [US1] Implement the pure `MapScopeRegistry`, `ScopeAggregator`, `DotMapProjector`, and stable
  venue/exhibition grouping in `shared/src/commonMain/kotlin/com/gallr/shared/map/`, including
  coordinate-unavailable output for the equivalent list.
- [x] T016 [P] [US1] Implement `scripts/map-geometry/generate-dot-map.mjs`, generate normalized Korea
  and Seoul geometry into
  `composeApp/src/commonMain/composeResources/files/map_geometry/`, and add provenance/version checks
  to `scripts/map-geometry/generate-dot-map.test.mjs`.
- [x] T017 [US1] Implement `PersonalMapViewModel` and immutable UI state in
  `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/PersonalMapViewModel.kt`, using shared
  scope/projection services and existing catalog/bookmark flows.
- [x] T018 [P] [US1] Implement stateless batched `DotMapCanvas`, deterministic hit testing, and canvas
  summary semantics in
  `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/DotMapCanvas.kt`; do not create one
  Compose or accessibility node per background dot.
- [x] T019 [P] [US1] Implement sharp, token-based `MapScopeHeader`, `MapLegend`, `MapModeTabs`,
  `ScopeSummaryPanel`, and equivalent `MapResultList` components in
  `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapComponents.kt` following `handoff.md`
  and `DESIGN.md`.
- [x] T020 [US1] Replace the native `MapView` composition in
  `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapScreen.kt`, wire the new ViewModel
  through `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt`, and keep exhibition detail navigation
  state-driven.
- [x] T021 [US1] Run the US1 shared/Compose/geometry tests and Android assembly, then visually verify
  the approved direct-to-Seoul All/My Exhibitions states on iOS and Android. The original Korea scope
  and district-editor comparisons were superseded by the 2026-08-10 direct-to-Seoul UX revision.

**Checkpoint**: The approved abstract map is functional and reviewable without Near Me, visit rows, or
photo support.

### Direct-to-Seoul UX revision (2026-08-10)

- [x] T064 [US1] Add failing ViewModel coverage for Seoul as the initial scope and selection-save
  behavior that remains in Seoul; then remove the country overview route and back affordance from the
  Map composition, run focused Android/iOS verification, and capture the revised first frame.
- [x] T066 [US1] Add failing tests for independent same-location pins, My Exhibitions labels, and
  saved-ID state; then remove the city header, district editor, and numbered clusters, render a titled
  filled pin for every exhibition, use orange for saved pins in All, and pass simulator design QA.
- [x] T067 [US1] Add failing pure tests for screen-space title-overlap grouping and zoom separation;
  then replace only unreadable colliding pins with a numbered group, open an exact exhibition list on
  selection, and verify individual titled pins return when the map creates sufficient separation.

---

## Phase 4: User Story 2 — Find Exhibitions Near Me (Priority: P1)

**Goal**: Resolve one foreground/cached location into a supported scope, rank exhibitions by real
distance, show an approximate abstract indicator, and open exact coordinates externally.

**Independent Test**: Inject a fixed Seoul coordinate, activate Near Me, verify deterministic distance
order and the approximate marker, then confirm exact venue coordinates are handed to an external map.
Denial must preserve manual exploration.

### Failing tests first

- [ ] T022 [P] [US2] Add failing Haversine and nearby tests for poles/antimeridian, null coordinates,
  stable ties, maximum results, and identical fixed-fixture ordering in
  `shared/src/commonTest/kotlin/com/gallr/shared/map/NearbyExhibitionFinderTest.kt`.
- [ ] T023 [P] [US2] Add failing ViewModel tests for action-only permission requests, cached/fresh fixes,
  supported/unsupported scope resolution, denial/revocation/no-fix, loading de-duplication, offline
  last-known labels, and privacy-safe error state in
  `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/PersonalMapNearbyTest.kt`.
- [ ] T024 [P] [US2] Add failing common contract tests with fake platform implementations for exact-
  coordinate external navigation, provider fallback, and no display-cell leakage in
  `composeApp/src/commonTest/kotlin/com/gallr/app/platform/ExternalMapLauncherContractTest.kt`.

### Implementation

- [ ] T025 [US2] Implement pure Haversine distance, deterministic `NearbyExhibitionFinder`, and
  coordinate-to-supported-scope resolution in `shared/src/commonMain/kotlin/com/gallr/shared/map/`.
- [ ] T026 [US2] Replace the current map-specific location shim with injected typed
  `UserLocationProvider` behavior in
  `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.kt`,
  `composeApp/src/androidMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.android.kt`, and
  `composeApp/src/iosMain/kotlin/com/gallr/app/ui/tabs/map/UserLocation.ios.kt`; retain one-shot/cached
  foreground access only.
- [ ] T027 [P] [US2] Implement `ExternalMapLauncher` and provider-neutral result types in
  `composeApp/src/commonMain/kotlin/com/gallr/app/platform/ExternalMapLauncher.kt`, with Android intent
  and iOS URL implementations in the corresponding `androidMain`/`iosMain` platform files; prefer an
  installed Naver Map deep link in Korea and provide a system/web fallback.
- [ ] T028 [US2] Add Near Me action/state, approximate projected user location, ordered distance list,
  permission/unavailable/manual-city fallbacks, and directions actions to
  `PersonalMapViewModel.kt`, `MapScreen.kt`, and `MapComponents.kt`.
- [x] T029 [US2] After confirming no remaining embedded-map caller, remove
  `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/map/MapView.kt`, platform `MapView` files,
  Naver aliases/dependencies from `gradle/libs.versions.toml` and `composeApp/build.gradle.kts`, iOS
  NMaps cinterop/definitions/SPM references, and reviewed obsolete configuration listed by T003.
  Completed early under the direct-to-Seoul revision; provider-neutral external navigation remains T027.
- [ ] T030 [US2] Run US2 tests, Android assembly, and iOS simulator framework compile; verify the
  Near Me reference `assets/seoul-near-me.png`, action-only permission timing, denial path, external
  Naver/system handoff, and an outside-supported-scope coordinate.

**Checkpoint**: Nearby discovery and precise directions work without an embedded map SDK or continuous
location tracking.

---

## Phase 5: User Story 3 — Mark an Exhibition Visited (Priority: P1)

**Goal**: Create, edit, delete, persist, and synchronize repeat private visits with immutable catalog
snapshots, including anonymous use and historical entries after catalog removal.

**Independent Test**: Log the same exhibition twice without a photo while anonymous, restart offline,
edit one note/date, sign in and retry sync, then remove the live catalog row and verify both diary
entries remain intelligible with no duplication.

### Failing tests first

- [ ] T031 [P] [US3] Add failing pgTAP owner-isolation, repeat-visit, constraint, immutable-field,
  editable-date/note, cascade, catalog-independence, and grant tests in
  `supabase/tests/database/027_exhibition_visits.test.sql`; prove the table/policies do not yet exist.
- [ ] T032 [P] [US3] Add failing serialization and local CRUD tests for versioned DataStore envelopes,
  repeat visits, immutable snapshots, immediate flows, edits, tombstones, corrupt payload recovery,
  and private relative file references in
  `shared/src/commonTest/kotlin/com/gallr/shared/repository/LocalVisitRepositoryTest.kt`.
- [ ] T033 [P] [US3] Add failing cloud merge tests for stable client UUIDs, owner assignment,
  interrupted/retried upserts, local/cloud conflicts, tombstone deletion, sign-out isolation, and no
  duplicate rows in `shared/src/commonTest/kotlin/com/gallr/shared/repository/VisitSyncServiceTest.kt`.
- [ ] T034 [P] [US3] Add failing Compose tests for three-action manual logging, validation, immediate
  visited state, repeat visits, edit/delete confirmation, offline/sync failures, and historical
  snapshot detail in
  `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/LogVisitViewModelTest.kt` and
  `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/PersonalMapVisitedStateTest.kt`.

### Implementation

- [ ] T035 [US3] Create `public.exhibition_visits`, constraints, indexes, owner-only RLS/grants, and
  constrained insert/update behavior in
  `supabase/migrations/20260809130000_exhibition_visits.sql`; do not foreign-key visits to a live
  catalog row.
- [ ] T036 [P] [US3] Implement documented `ExhibitionVisit`, `VisitSnapshot`, `VisitMatchSource`, and
  sync-state models in `shared/src/commonMain/kotlin/com/gallr/shared/data/model/visit/`, plus typed
  DTO mapping in `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/ExhibitionVisitDto.kt`.
- [ ] T037 [US3] Implement `VisitRepository`, `LocalVisitDataSource`, versioned DataStore persistence,
  and tombstones in `shared/src/commonMain/kotlin/com/gallr/shared/repository/visit/`; add thin injected
  client-ID and app-private-file interfaces without platform business logic.
- [ ] T038 [P] [US3] Implement owner-scoped Supabase visit CRUD in
  `shared/src/commonMain/kotlin/com/gallr/shared/repository/visit/CloudVisitDataSource.kt`, returning
  typed failures and never logging note/snapshot coordinate content.
- [ ] T039 [US3] Implement idempotent local/cloud reconciliation in
  `shared/src/commonMain/kotlin/com/gallr/shared/repository/visit/VisitSyncService.kt`, preserving local
  content until remote confirmation and retaining synced rows as an offline cache.
- [ ] T040 [US3] Implement thin Android/iOS client-ID generation and private-file implementations under
  `shared/src/androidMain/kotlin/com/gallr/shared/platform/` and
  `shared/src/iosMain/kotlin/com/gallr/shared/platform/`; wire repositories in
  `composeApp/src/androidMain/kotlin/com/gallr/app/MainActivity.kt` and
  `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`.
- [ ] T041 [US3] Implement `LogVisitViewModel`, manual `LogVisitScreen`, private visit detail/edit/delete
  flow, and state-driven navigation in
  `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/LogVisitViewModel.kt`,
  `composeApp/src/commonMain/kotlin/com/gallr/app/ui/visit/`, and `App.kt`; follow sharp DESIGN.md
  controls and the 280-character private note contract.
- [ ] T042 [US3] Derive visited marks from distinct visit exhibition IDs and historical snapshot marks
  in `PersonalMapViewModel.kt`; preserve closed/unpublished visits and disclose repeat-visit counts.
- [ ] T043 [US3] Run focused pgTAP/shared/Compose tests, clean DB reset/lint, Android process-restart/
  offline checks, iOS framework tests, two-account RLS checks, and the anonymous→authenticated retry
  scenario.

**Checkpoint**: The complete P1 product works: abstract exploration, Near Me, and private manual visit
history. Photo support remains optional and absent.

---

## Phase 6: User Story 4 — Log a Visit from a Photo (Priority: P2)

**Goal**: Let a user take or choose one photo, decode optional metadata locally, explicitly confirm a
ranked exhibition candidate, and attach one sanitized owner-only derivative.

**Independent Test**: Use synthetic photos with GPS/date, missing GPS, no metadata, and corrupt data;
verify candidate ranking/manual fallback, zero automatic visits, zero GPS in the derivative, and
cross-user denial for the stored object.

### Failing tests first

- [ ] T044 [P] [US4] Add synthetic image fixtures (never personal photos) under
  `composeApp/src/commonTest/resources/visit_photos/` and failing shared matcher tests for schedule
  overlap, real distance, ambiguity, missing/redacted metadata, unsupported geography, distance bounds,
  and stable ties in `shared/src/commonTest/kotlin/com/gallr/shared/map/VisitCandidateMatcherTest.kt`.
- [ ] T045 [P] [US4] Add failing fake-gateway/ViewModel tests for library/camera choice, cancellation,
  metadata absence, explicit candidate confirmation, save-without-photo, upload retry, draft retention,
  and zero source-coordinate persistence in
  `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/PhotoVisitViewModelTest.kt`.
- [ ] T046 [P] [US4] Add failing Android and iOS platform fixture tests that decode source metadata,
  sanitize/re-read the derivative, and assert GPS/location dictionaries and source identifiers are
  absent in `composeApp/src/androidInstrumentedTest/kotlin/com/gallr/app/platform/VisitMediaGatewayTest.kt`
  and `composeApp/src/iosTest/kotlin/com/gallr/app/platform/VisitMediaGatewayTest.kt`.
- [ ] T047 [P] [US4] Add failing pgTAP tests for photo-row visit ownership, one-photo uniqueness,
  immutable path, MIME/size/dimension validation, private bucket configuration, owner-only object
  SELECT/INSERT/DELETE, cross-owner denial, and no public/list/update policy in
  `supabase/tests/database/028_exhibition_visit_photos.test.sql`.

### Implementation

- [ ] T048 [US4] Add AndroidX ExifInterface and required instrumented-test dependencies through
  `gradle/libs.versions.toml` and `composeApp/build.gradle.kts`; add only the camera/media-location
  declarations justified by the gateway to `composeApp/src/androidMain/AndroidManifest.xml` and
  `iosApp/iosApp/Info.plist`, with bilingual purpose copy where the OS supports it.
- [ ] T049 [US4] Create the private `visit-photos` bucket, `exhibition_visit_photos` table, constraints,
  owner-only RLS/grants, and Storage policies in
  `supabase/migrations/20260809140000_exhibition_visit_photos.sql`.
- [ ] T050 [P] [US4] Implement ephemeral `PhotoMetadata`, `VisitCandidate`, and the pure deterministic
  matcher in `shared/src/commonMain/kotlin/com/gallr/shared/map/VisitCandidateMatcher.kt`; discard raw
  source metadata after confirmation and never expose it through repository DTOs.
- [ ] T051 [US4] Define injected `VisitMediaGateway` result/sanitizer contracts in
  `composeApp/src/commonMain/kotlin/com/gallr/app/platform/VisitMediaGateway.kt`; implement Android
  photo picker, camera capture, best-effort original EXIF access, resize/reorientation, and fresh JPEG
  encoding in `composeApp/src/androidMain/kotlin/com/gallr/app/platform/VisitMediaGateway.android.kt`.
- [ ] T052 [US4] Implement iOS library/camera intake, Image I/O metadata decoding, resize/reorientation,
  and metadata-free JPEG encoding in
  `composeApp/src/iosMain/kotlin/com/gallr/app/platform/VisitMediaGateway.ios.kt`; metadata denial or
  redaction must return a normal no-metadata result.
- [ ] T053 [US4] Implement private photo upload/metadata/delete operations and retry state in
  `shared/src/commonMain/kotlin/com/gallr/shared/repository/visit/VisitPhotoDataSource.kt` and integrate
  them into `VisitSyncService.kt` using deterministic owner/visit/photo paths.
- [ ] T054 [US4] Add photo-source choice, candidate rows, explicit confirmation, privacy copy, optional
  note, failure/manual fallback, and save-without-photo behavior to
  `composeApp/src/commonMain/kotlin/com/gallr/app/ui/visit/LogVisitScreen.kt` and
  `LogVisitViewModel.kt`, matching `assets/log-visit-photo-match.png`.
- [ ] T055 [US4] Run matcher/ViewModel/platform fixture/pgTAP tests, clean DB reset/lint, two-account
  Storage checks, derivative metadata inspection, camera/library device checks, and log inspection for
  photo bytes, coordinates, notes, private paths, and signed URLs.

**Checkpoint**: Photo-assisted logging works as an explicit private confirmation flow; manual visit
logging still works when every media permission or metadata signal is unavailable.

---

## Phase 7: User Story 5 — Review Visited and Unexplored Progress (Priority: P2)

**Goal**: Complete To Visit, Visited, and All behavior at country/city/district scope, including mixed
aggregates, historical visits, and private memory panels.

**Independent Test**: Seed saved, repeat-visited, historical, active-unexplored, and missing-coordinate
items across Korean cities; verify every mode's semantic marks, accessible list, counts, and selection
without claiming mixed areas are wholly visited.

### Failing tests first

- [ ] T056 [P] [US5] Add failing shared tests for To Visit exclusion after first visit, distinct
  visited-exhibition counts versus total visit counts, mixed aggregates, historical snapshot inclusion,
  closed/unpublished rows, missing coordinates, and country/city/district filtering in
  `shared/src/commonTest/kotlin/com/gallr/shared/map/PersonalMapModeFilterTest.kt`.
- [ ] T057 [P] [US5] Add failing Compose tests for mode tabs/counts, selected-state reset, historical
  memory panels, repeat visits, inaccessible-coordinate list rows, large-text wrapping, and state-
  explicit semantics in
  `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/PersonalMapModesTest.kt` and
  `composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/map/MapAccessibilityStateTest.kt`.

### Implementation

- [ ] T058 [US5] Implement pure To Visit/Visited/All filtering and aggregate calculation in
  `shared/src/commonMain/kotlin/com/gallr/shared/map/PersonalMapModeFilter.kt`, keeping background
  geometry separate from semantic marks.
- [ ] T059 [US5] Complete mode/count/breadcrumb state in `PersonalMapViewModel.kt` and `MapScreen.kt`;
  merge active catalog items with immutable visit snapshots without double-counting the same live
  exhibition.
- [ ] T060 [US5] Implement `VisitMemoryPanel`, repeat-visit disclosure, historical fallback imagery,
  unavailable-location rows, and scope summaries in `MapComponents.kt` and
  `composeApp/src/commonMain/kotlin/com/gallr/app/ui/visit/VisitDetailScreen.kt`, matching
  `assets/seoul-visited-diary.png`.
- [ ] T061 [US5] Run US5 tests and manually verify all modes in Korean/English, active/historical/mixed
  scopes, offline cached state, dark mode, large text, TalkBack, and VoiceOver.

**Checkpoint**: All five user stories and the approved personal-atlas loop are independently complete.

---

## Phase 8: Cross-Cutting Hardening and Release Evidence

**Purpose**: Verify privacy, accessibility, performance, migration safety, and provider removal across
the completed feature.

- [ ] T062 [P] Add privacy-safe structured operation logging for scope, nearby, visit, metadata,
  upload, delete, and sync outcomes in the relevant shared services/ViewModels; add redaction tests in
  `shared/src/commonTest/kotlin/com/gallr/shared/repository/visit/VisitLoggingPrivacyTest.kt`.
- [ ] T063 [P] Add a renderer performance fixture for dense Korea/Seoul scopes in
  `composeApp/src/commonTest/kotlin/com/gallr/app/ui/tabs/map/DotMapPerformanceContractTest.kt` and
  profile a release build to confirm one batched background surface, bounded semantic marks, stable hit
  targets, and no per-dot accessibility nodes.
- [ ] T064 Audit the final implementation against every item in `handoff.md`, `DESIGN.md`, and
  `provider-removal-checklist.md`; verify no Naver embedded SDK/cinterop/config remains while installed
  Naver Map direction handoff still works.
- [ ] T065 Run the complete commands in `quickstart.md`: migration-lineage validation, clean DB reset,
  all pgTAP, DB lint, GAS tests, `shared:allTests`, `composeApp:allTests`, Android assembly, and iOS
  simulator framework compile; preserve evidence and fix regressions before completion.
- [ ] T066 [P] Update `CHANGELOG.md`, applicable privacy/release documentation, and `VERSION` plus mobile
  version fields only when this feature is selected for a release; document the removal of the embedded
  map SDK and the new private location/photo behavior without claiming automatic visit detection.

---

## Dependencies and Execution Order

### Phase dependencies

- **Phase 1** has no dependency.
- **Phase 2** depends on Phase 1 and blocks every user story.
- **US1** depends on Phase 2 and is the first visual checkpoint.
- **US2** depends on US1 because it projects nearby state into the abstract renderer.
- **US3** depends on US1; it may begin alongside US2 after the renderer contracts stabilize, but the P1
  checkpoint requires both.
- **US4** depends on US3's visit/local/cloud lifecycle but not US5.
- **US5** depends on US3's visit state and US1's scope renderer; it can run alongside US4.
- **Phase 8** depends on every story selected for the release.

### Test-first dependencies within each story

- T011–T013 must fail before T014–T020.
- T022–T024 must fail before T025–T029.
- T031–T034 must fail before T035–T042.
- T044–T047 must fail before T048–T054.
- T056–T057 must fail before T058–T060.
- Each checkpoint task runs only after its story's implementation and focused tests are green.

### Parallel opportunities

- T002 and T003 can run in parallel after T001.
- T004, T005, and T006 are independent failing-test surfaces.
- T011, T012, and T013 can be authored in parallel; T016 can run alongside T014/T015 after its source
  and expected geometry contract are agreed.
- T022, T023, and T024 can be authored in parallel; T027 uses different platform files from T025/T026.
- T031–T034 can be authored in parallel. After the visit model stabilizes, T038 can run alongside the
  local implementation in T037.
- T044–T047 can be authored in parallel. T052 follows the common gateway contract introduced by T051;
  its iOS implementation remains isolated from T051's Android platform file.
- US4 and US5 can proceed in parallel after US3.
- T062 and T063 can proceed in parallel before final audit/verification.

---

## Implementation Strategy

### Visual checkpoint first

1. Complete Setup and Country Identity.
2. Complete US1.
3. Stop and compare the live Korea/Seoul tab with the approved references before investing in
   location, database, or media work.

### P1 product checkpoint

1. Add US2 Near Me and exact external directions.
2. Add US3 manual private visits and anonymous/authenticated persistence.
3. Stop and validate the complete core loop without any photo permission or upload dependency.

### P2 progression

1. Add US4 photo matching/private derivative as an optional enhancement.
2. Add US5 complete planning/diary filters and memory presentation.
3. Finish cross-cutting hardening and release evidence.

## Notes

- A task marked `[P]` must still respect its listed prerequisite and test-first gate.
- Do not stage, commit, deploy, change credentials, or mutate production data unless separately
  authorized.
- Keep original geographic coordinates and display cells in distinct types throughout implementation.
- Do not broaden photo work into library scanning, public sharing, or automatic visit creation.
- Do not add PostGIS until measured on-device catalog performance fails the stated success criterion.
