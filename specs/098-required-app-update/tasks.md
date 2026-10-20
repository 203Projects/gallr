# Tasks: Required app update

**Input**: Design documents from `/specs/098-required-app-update/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R8), data-model.md, contracts/app-version-policy.md,
contracts/update-screens.md, quickstart.md

**Tests**: Required (constitution, Test-First). Each test task is written and seen failing before its implementation.

**Organization**: by user story. US1 (the gate) and US2 (fail open) share the repository and are the MVP; US3 (nudge)
and US4 (Admin panel) follow.

Paths: `shared/` = `shared/src/commonMain/kotlin/com/gallr/shared/`, `sharedTest/` =
`shared/src/commonTest/kotlin/com/gallr/shared/`, `app/` = `composeApp/src/commonMain/kotlin/com/gallr/app/`,
`appTest/` = `composeApp/src/commonTest/kotlin/com/gallr/app/`, `iosUiTest/` =
`composeApp/src/iosTest/kotlin/com/gallr/app/`, `admin/` = `admin/src/`.

## Format: `[ID] [P?] [Story?] Description`

---

## Phase 1: Setup

- [X] T001 Record the baseline: `./gradlew shared:testAndroidHostTest composeApp:testAndroidHostTest` counts and
  `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` on this branch (no code change).

---

## Phase 2: Foundational (blocks every story)

**Purpose**: the version rule in `shared` and the policy on the server (research R1, R2).

- [X] T002 [P] Write `sharedTest/app/AppVersionTest.kt` (fails first): `AppVersion.parse` reads "1.13.1", "1.14.0-debug"
  (suffix ignored) and "01.2.3"; returns null for "1.13", "", "a.b.c"; comparison 1.13.1 < 1.14.0 < 1.14.10;
  `updateRequirement` gives REQUIRED below the minimum, RECOMMENDED between, NONE at or above the recommended, and
  NONE when installed equals the minimum.
- [X] T003 [P] Write `supabase/tests/database/068_app_version_policy.test.sql` (fails first): `anon` and
  `authenticated` may execute `get_app_version_policy`; the seed rows read back `1.13.1` for both platforms; an
  unknown platform reads null; `anon` has no privilege on `admin_set_app_version_policy` and no select on the table;
  a non-staff authenticated caller is refused; a staff caller stores a row and `updated_by`/`updated_at` are set;
  `1.14` and `recommended < minimum` are refused and the row is unchanged.
- [X] T004 Add `shared/app/AppVersion.kt`: `AppVersion`, `UpdatePolicy`, `UpdateRequirement`, `updateRequirement()`.
  Passes T002.
- [X] T005 Add `supabase/migrations/20261020100000_app_version_policy.sql`: table, checks, RLS, the two RPCs with
  `content_private.admin_assert_staff()` on the write, explicit grants/revokes, seed at `1.13.1` (data-model.md).
  Run the lineage validator, then the pgTAP suite in the isolated scratch workdir. Passes T003.

**Checkpoint**: `./gradlew shared:allTests --tests "com.gallr.shared.app.*"` and pgTAP 068 pass.

---

## Phase 3: User Story 1 + 2 - The gate, and never blocked by a failed read (Priority: P1) 🎯 MVP

**Goal**: the app reads the policy, keeps the last answer, and stops an old build at the notice.

**Independent Test**: quickstart steps 1–4 and 6.

### Tests

- [X] T006 [P] [US1] Write `sharedTest/data/network/AppVersionPolicyApiClientTest.kt` (fails first, MockEngine as
  `MostViewedExhibitionApiClientTest`): posts `{"p_platform":"android"}` to `rpc/get_app_version_policy`; a `200`
  with both versions gives `UpdatePolicy`; `null`, a non-200, a body without the fields or an unparseable version gives
  a failed read (`null`), never an exception out of the client.
- [X] T007 [P] [US1] Write `sharedTest/repository/AppUpdateRepositoryImplTest.kt` (fails first, in-memory DataStore as
  `DataStoreRouteVisitTimeRepositoryTest`): starts from the stored policy (NONE on a fresh install); `refresh()`
  stores a successful read and emits REQUIRED for installed 1.14.0 with minimum 1.15.0; a failed read keeps the
  stored REQUIRED (US2 scenario 3); a later read with minimum 1.13.1 clears it; the stored key is
  `app_version_policy`; read failures are logged as `app_version_policy_read_failed` (assert via the log seam used by
  other repository tests, or by behaviour only if none exists).
- [X] T008 [P] [US1] Write `appTest/ui/update/UpdatePresentationTest.kt` (fails first): the notice's heading, line,
  button and fallback line in KO and EN exactly as contracts/update-screens.md.
- [X] T009 [P] [US1] Write `iosUiTest/ui/update/RequiredUpdateScreenTest.kt` (fails first,
  `androidx.compose.ui.test.v2.runComposeUiTest`): the heading is a heading, the button reports `onUpdate`, and the
  screen's back handler consumes back (the test asserts the installed handler is enabled and swallows the event).

### Implementation

- [X] T010 [US1] Add `shared/data/network/AppVersionPolicyApiClient.kt` (`AppVersionPolicyApi` interface + client).
  Passes T006.
- [X] T011 [US1] Add `shared/repository/AppUpdateRepository.kt` and `AppUpdateRepositoryImpl.kt` (installed version,
  platform, api, DataStore; `requirement`, `refresh()`; private key `APP_VERSION_POLICY_KEY = "app_version_policy"`).
  Passes T007.
- [X] T012 [US1] Add `app/platform/InstalledAppVersion.kt` (`expect fun installedAppVersion(): String`, non-composable,
  Android `PackageInfo.versionName`, iOS `CFBundleShortVersionString`) and `app/platform/AppStoreLink.kt`
  (`expect fun appStoreListingUrl(): String`: Play `https://play.google.com/store/apps/details?id=com.gallr.app`, App
  Store `https://apps.apple.com/app/gallr/id6760855059`).
- [X] T013 [US1] Add `app/ui/update/UpdatePresentation.kt` and `app/ui/update/RequiredUpdateScreen.kt`
  (contracts/update-screens.md; `PlatformBackHandler(onBack = {})`; the fallback line after a failed open). Passes T008,
  T009.
- [X] T014 [US1] Wire it: `App.kt` takes `appUpdateRepository`, collects `requirement`, shows `RequiredUpdateScreen`
  instead of `TabNavigationShell` when REQUIRED (splash overlay still above), calls `refresh()` once at start and in the
  existing ON_RESUME observer when the app was away ≥ 60 s (track ON_PAUSE time); `MainActivity.kt` and
  `MainViewController.kt` construct `AppUpdateRepositoryImpl(installedAppVersion(), platform, client, dataStore)`.

**Checkpoint**: quickstart steps 1–4 and 6 on the Android emulator against a local stack.

---

## Phase 4: User Story 3 - A recommended update is a gentle nudge (Priority: P2)

**Independent Test**: quickstart step 5.

- [X] T015 [P] [US3] Extend `sharedTest/repository/AppUpdateRepositoryImplTest.kt` (fails first): RECOMMENDED for
  installed 1.14.0 with minimum 1.13.1 and recommended 1.15.0; `dismissNudge(today)` stores `app_update_nudge_dismissed`
  as `yyyy-MM-dd`; `nudgeDismissedOn` reads it back; a malformed value reads as null.
- [X] T016 [P] [US3] Extend `appTest/ui/update/UpdatePresentationTest.kt` (fails first): the nudge's text and two
  actions in KO and EN; `showUpdateNudge(requirement, dismissedOn, today)` is true only for RECOMMENDED and a day
  other than today.
- [X] T017 [US3] Add `nudgeDismissedOn` and `dismissNudge` to the repository; add `app/ui/update/UpdateNudge.kt` and
  show it at the top of the Home tab's rails (`app/ui/tabs/home/HomeScreen.kt`) when `showUpdateNudge` is true; 나중에
  calls `dismissNudge(today in Seoul)`. Passes T015, T016.

**Checkpoint**: quickstart step 5 on the emulator.

---

## Phase 5: User Story 4 - Staff raise the minimum without a release (Priority: P2)

**Independent Test**: quickstart step 7.

- [X] T018 [P] [US4] Write `admin/components/AppVersionsWorkspace.test.tsx` (fails first, Vitest, in-memory
  repository): two rows (Android, iOS) render the stored values and the editor/time; a field that is not `N.N.N` is
  refused with a message and Save is disabled; minimum newer than last released shows the inline warning; Save calls
  the repository with the row's values; an RPC error is shown and the fields keep their values.
- [X] T019 [US4] Add `admin/repositories/AppVersionPolicyRepository.ts` (Supabase `rpc` read of both rows through a
  select on the RPC per platform, and `admin_set_app_version_policy` write) with a fixture implementation in
  `admin/data/fixtures.ts`.
- [X] T020 [US4] Add `admin/components/AppVersionsWorkspace.tsx`; register the section "App versions" / "앱 버전"
  (`admin/domain.ts` `AdminSection` + slug `app-versions`, `admin/i18n.tsx`, `PrimaryNavigation.tsx` for the admin
  role, `App.tsx` routing). Passes T018.

**Checkpoint**: `cd admin && npx -y -p node@22.23.1 -- sh -c 'npm run typecheck && npm test && npm run build'`.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T021 [P] DESIGN.md → new "Required update" entry under Component Patterns (the notice and the nudge per
  contracts/update-screens.md) and a decisions-log row for 2026-10-20. Owner approval needed for DESIGN.md changes.
- [X] T022 [P] Docs: `supabase/AGENTS.md` or the nearest README notes the new table and RPCs; `admin/AGENTS.md` lists
  the new section; `CLAUDE.md` Recent Changes line for 098; the release checklist notes that the first production
  value is set by staff after the store release.
- [X] T023 Add `InAppUpdater` (interface, no-op default) in `app/platform/` and call it from the notice's button
  before the store link; no Android Play implementation in this feature (research R6). Record the follow-up in
  `TODOS.md`.
- [X] T024 Gates: `./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests`,
  `./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug`, pgTAP
  068 in the scratch workdir, Admin typecheck/test/build at Node 22.23.1.
- [X] T025 Quickstart manual steps 1–7 on the Android emulator and the iOS simulator against a local stack; record
  results here.
  - 2026-10-20, Android emulator `gallr_test` (API 36), debug build 1.13.1 against the scratch stack `gallr098`
    (migration 20261020100000 applied, policy rows edited by SQL): step 1 opens as today; step 2 the notice shows on
    relaunch, back does nothing, 업데이트 opens Google Play (the emulator's sign-in page); step 3 airplane mode and a
    relaunch keep the notice; step 4 lowering the minimum lifts it; step 5 the nudge shows at the top of Home, 나중에
    hides it, a relaunch the same day keeps it hidden. The next-day case could not be exercised (the emulator image
    refuses a date change); it is covered by `UpdatePresentationTest`. Steps 6 (English) and 7 (Admin) were not
    walked on a device; both are covered by unit tests (`UpdatePresentationTest`, `AppVersionsWorkspace.test.tsx`).
    The iOS simulator was not walked; `RequiredUpdateScreenTest` ran in `composeApp:iosSimulatorArm64Test`.

---

## Dependencies & Execution Order

- Setup → Foundational (T002–T005) → US1+2 (T006–T014) → US3 (T015–T017) → US4 (T018–T020) → Polish (T021–T025).
- T004 before T006/T007/T010/T011; T005 before T014's manual check (the local stack needs the migration).
- T011 before T014 and T017; T019 before T020.

### Parallel opportunities

- T002 ∥ T003; T006 ∥ T007 ∥ T008 ∥ T009; T015 ∥ T016; T018 can start as soon as T005 fixes the RPC shape; T021 ∥ T022.
- US4 (Admin) is independent of US3 and can run alongside it.

## Parallel Example: User Story 1

```text
Task: "T006 [US1] API client test in sharedTest/data/network/AppVersionPolicyApiClientTest.kt"
Task: "T007 [US1] repository test in sharedTest/repository/AppUpdateRepositoryImplTest.kt"
Task: "T008 [US1] presentation test in appTest/ui/update/UpdatePresentationTest.kt"
Task: "T009 [US1] screen UI test in iosUiTest/ui/update/RequiredUpdateScreenTest.kt"
```

## Implementation Strategy

1. Foundational: the rule in `shared` and the policy on the server, both fully tested.
2. US1+2: the gate, failing open. Stop and validate on the emulator: this is the MVP.
3. US3: the nudge. 4. US4: the Admin panel.
5. Polish: docs, the `InAppUpdater` seam, gates, both platforms.

## Notes

- Commit after each checkpoint.
- Applying the migration to staging or production, and setting the first real values, are separately authorized
  operations (supabase/AGENTS.md); this feature only adds the migration and seeds the current store version.
- The release that carries this must not raise the minimum above itself.
