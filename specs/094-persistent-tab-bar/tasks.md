# Tasks: Always-visible tab bar

**Input**: Design documents from `/specs/094-persistent-tab-bar/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R9), data-model.md, contracts/tab-navigation.md, quickstart.md

**Tests**: Required. The constitution's Test-First principle applies; every test task is written and seen failing
before its implementation task.

**Organization**: by user story. US1 and US2 (both P1) ship together, because a visible bar without per-tab trails
would discard the visitor's place on every tap (plan, Constitution Check IV).

Paths are relative to the repository root. `app/` below means `composeApp/src/commonMain/kotlin/com/gallr/app/`,
`appTest/` means `composeApp/src/commonTest/kotlin/com/gallr/app/` and `iosUiTest/` means
`composeApp/src/iosTest/kotlin/com/gallr/app/`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US4 from spec.md

---

## Phase 1: Setup

- [ ] T001 Record the baseline: run `./gradlew composeApp:testAndroidHostTest` and note the test count, so later runs
  show only intended changes (no code change).

---

## Phase 2: Foundational (blocks every story)

**Purpose**: the trail model every story depends on (data-model.md, research R1).

- [ ] T002 Rewrite `appTest/AppNavigationStateTest.kt` for trails (fails first). Cover:
  - `selectTab` to another tab keeps both trails; `selectTab` to the selected tab clears only its trail; at a tab's
    top it changes nothing;
  - each `show…` pushes onto the selected tab; `back()` pops it and returns false at the top;
  - opening the current destination again adds nothing (FR-009);
  - `showRecommendations`, `showCollection` and `showRoute` open in the current tab;
  - `showMyRoutes` and `showAddPastVisits` select MY, clear MY's trail and set the request;
  - `destination` is null at a tab's top and `canGoBack` follows the trail;
  - every popped or cleared entry id is reported through `onEntriesDropped`;
  - keep the existing assertions that still hold (typed identifiers, analytics suppression, route request ids).
  Backtick names without commas (Kotlin/Native).
- [ ] T003 Implement the model in `app/AppNavigationState.kt` to pass T002: `enum class AppTab`, `data class NavEntry(id,
  destination)`, four observable trails, derived `currentEntry` / `destination` / `canGoBack`, `back()`, the new
  `selectTab(AppTab)`, `onEntriesDropped`. Remove `AppDestination.Tabs`, the private back targets, `returnTo`,
  `return…`, `showTabs` and `MyTabRequest.SIGN_IN`. Keep `selectTab(index: Int)` only if a caller still needs it after
  T009; otherwise remove it.
- [ ] T004 [P] Write `appTest/TabBarVisibilityTest.kt` (fails first): `isTabBarVisible(imeBottomPx = 0)` is true,
  any positive value is false.
- [ ] T005 [P] Add `isTabBarVisible(imeBottomPx: Int): Boolean` in `app/TabNavigationShell.kt` to pass T004.

**Checkpoint**: `./gradlew composeApp:testAndroidHostTest --tests "com.gallr.app.AppNavigationStateTest" --tests
"com.gallr.app.TabBarVisibilityTest"` passes; `App.kt` does not compile yet (fixed in US1).

---

## Phase 3: User Story 1 - Reach any tab from any screen (Priority: P1) 🎯 MVP

**Goal**: the bar is on every screen and highlights the owning tab; screens' own action bars sit directly above it.

**Independent Test**: quickstart §2 steps 1, 2 and 8.

### Tests for User Story 1

- [ ] T006 [P] [US1] Write `iosUiTest/TabNavigationShellTest.kt` cases (fail first) against `TabNavigationShell`
  with fake content:
  - with a destination open, the four tab items exist and the owning tab reports selected;
  - tapping another tab shows that tab's content;
  - content with a bottom-aligned 52dp action bar ends directly above the tab bar (bounds touch, no overlap).

### Implementation for User Story 1

- [ ] T007 [US1] Create `TabNavigationShell(selectedTab, onTabSelected, lang, imeBottomPx, content)` in
  `app/TabNavigationShell.kt` per contracts/tab-navigation.md: root `Scaffold(contentWindowInsets = WindowInsets(0),
  bottomBar = GallrNavigationBar when isTabBarVisible)`, content in `Modifier.padding(innerPadding)
  .consumeWindowInsets(innerPadding)`. Passes T006.
- [ ] T008 [P] [US1] Change `app/ui/components/GallrNavigationBar.kt` to take `AppTab` (`selectedTab: AppTab`,
  `onTabSelected: (AppTab) -> Unit`); look, labels and semantics unchanged (FR-014).
- [ ] T009 [US1] Rebuild the root of `app/App.kt` on the shell:
  - wrap the content `AnimatedContent` in `TabNavigationShell`, crossfading on the visible entry key (150ms);
  - render a tab's top screen when `destination` is null, in an inner `Scaffold` with today's logo `TopAppBar`
    (ConnectMenu, settings on MY) and no bottom bar; delete the old `AppDestination.Tabs` branch and its `Scaffold`;
  - keep the crop overlay, sign-up nudge and splash as siblings after the shell;
  - replace `HOME_TAB_INDEX`, `MY_LIST_TAB_INDEX`, `PROFILE_TAB_INDEX` with `AppTab`;
  - update the recommendations refresh (`selectedTab == HOME`) and the deep-link / nudge `selectTab` calls.
- [ ] T010 [US1] Update the screen-view analytics effect in `app/App.kt` (keyed on `destination` and `selectedTab`)
  for a null `destination` at a tab's top, keeping FR-015: a tab's top reports the tab, a destination reports itself.
  Update the affected analytics test under `appTest/analytics/` first if one asserts the old `Tabs` value.

**Checkpoint**: T006 passes; on the emulator every destination shows the bar (quickstart §2 step 1).

---

## Phase 4: User Story 2 - Each tab keeps its place (Priority: P1)

**Goal**: switching tabs returns to the same screen, scroll position and input; re-tap returns to the top.

**Independent Test**: quickstart §2 steps 3 and 4.

### Tests for User Story 2

- [ ] T011 [P] [US2] Add to `iosUiTest/TabNavigationShellTest.kt` (fails first): a destination holding a
  `rememberSaveable` counter and a scrolled `LazyColumn` keeps both after switching to another tab and back; after
  re-tapping its tab and reopening, the counter starts fresh (saved state removed).
- [ ] T012 [P] [US2] Add to `appTest/AppNavigationStateTest.kt` (fails first): `showRouteComposer()` while the composer
  is open in another tab selects that tab and pushes nothing (research R6).

### Implementation for User Story 2

- [ ] T013 [US2] In `app/App.kt` (or the shell), keep one `rememberSaveableStateHolder()` and render the visible entry
  in `SaveableStateProvider("tab-${tab.ordinal}")` or `SaveableStateProvider("entry-${id}")`; wire
  `onEntriesDropped` to `removeState`. Passes T011.
- [ ] T014 [US2] Implement the single-composer rule in `app/AppNavigationState.kt` to pass T012.
- [ ] T015 [P] [US2] Make the exhibition page's `showSharePreview` in `app/App.kt` a `rememberSaveable` keyed by the
  exhibition, so a share preview open in one tab is still open after a tab switch (research R2). Leave transient
  flags (`isVisitSaving`, `visitSaveFailed`) as they are.

**Checkpoint**: T011 and T012 pass; quickstart §2 steps 3–4 hold on the emulator.

---

## Phase 5: User Story 3 - Back steps through the current tab only (Priority: P2)

**Goal**: back pops the current tab's trail; sign-in opens over the screen that asked and returns to it.

**Independent Test**: quickstart §2 steps 5 and 6.

### Tests for User Story 3

- [ ] T016 [P] [US3] Update `appTest/PublicRouteNavigationTest.kt` (fails first): a preview opened from MY's routes
  pops back to MY's top; sign-in from a preview pushes `Account` on the same tab and `back()` returns to the preview;
  모두 보기 selects MY, clears its trail and requests the routes section.
- [ ] T017 [P] [US3] Add to `appTest/AppNavigationStateTest.kt` (fails first): `showSignIn()` pushes `Account` on the
  current tab; leaving it returns to the composer; signing in from MY's own account button pushes `Account` on MY.

### Implementation for User Story 3

- [ ] T018 [US3] Add `AppDestination.Account` handling in `app/AppNavigationState.kt` (`showSignIn` pushes it) to pass
  T016 and T017.
- [ ] T019 [US3] Extract today's account view from `app/ui/profile/ProfileTab.kt` (the `Column` with the
  `← MY GALLR` button, loading state, `SignInScreen` and `ProfileScreen`) into
  `app/ui/profile/AccountScreen.kt(authState, signInViewModel, …, onClose)`. Remove `showAccount`; MY's account button
  calls a new `onAccount` that pushes `Account`; drop the `SIGN_IN` request branch.
- [ ] T020 [US3] Render `AppDestination.Account` in `app/App.kt` with `AccountScreen`; its `onClose` calls `back()`
  and, when the visitor is still signed out and the screen below is the composer, `routeComposerViewModel
  .cancelSignIn()` (today's `onAccountClosed` behaviour). Remove `onAccountClosed` from `ProfileTab`.
- [ ] T021 [US3] Replace back wiring in `app/App.kt`:
  - one root `PlatformBackHandler(enabled = navigation.canGoBack) { navigation.back() }`, composed before the content;
  - every destination's `onBack` and its `PlatformBackHandler(navigation::return…/showTabs)` become `navigation.back()`
    / removed, except inner layers that keep their own handler (share preview, the public preview's `closePreview()`
    before `back()`);
  - remove every `returnTo = …` argument and the `returnTo` parameter of the local exhibition opener;
  - Settings' calls to `showTabs()` after sign-out or account deletion become `back()`.

**Checkpoint**: T016 and T017 pass; quickstart §2 steps 5–6 hold on the emulator, including Android back at a tab's
top behaving as before.

---

## Phase 6: User Story 4 - The bar steps aside while typing (Priority: P2)

**Goal**: no bar while the keyboard is open.

**Independent Test**: quickstart §2 step 7.

### Tests for User Story 4

- [ ] T022 [P] [US4] Add to `iosUiTest/TabNavigationShellTest.kt` (fails first): with `imeBottomPx > 0` the tab items
  do not exist and the content fills to the bottom; with `0` they are back.

### Implementation for User Story 4

- [ ] T023 [US4] In `app/App.kt`, read `WindowInsets.ime.getBottom(LocalDensity.current)` and pass it to
  `TabNavigationShell`. Passes T022.

**Checkpoint**: on both platforms the composer name field, the stop search and the sign-in fields stay visible with
the keyboard open (quickstart §2 step 7).

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T024 [P] Update `DESIGN.md` → Component Patterns → Navigation: the tab bar is on every screen, highlights the
  owning tab, steps aside only while the keyboard is open, and screens' own action bars sit directly above it; add a
  decisions-log row for 2026-10-15. Owner approval needed for DESIGN.md changes.
- [ ] T025 [P] Update `CLAUDE.md` → KMP conventions → Navigation (trails per tab in `AppNavigationState`, `back()`,
  one root `Scaffold` with the bar) and add a Recent Changes line for 094.
- [ ] T026 Run the gates: `./gradlew composeApp:ktlintCheck composeApp:allTests` and
  `./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug`.
- [ ] T027 Run quickstart §2 on the Android emulator and the iOS simulator, including the largest font size (step 10),
  and record the results here.

---

## Dependencies & Execution Order

### Phase dependencies

- Setup (T001) → Foundational (T002–T005) → US1 (T006–T010) → US2 (T011–T015) → US3 (T016–T021) → US4 (T022–T023)
  → Polish (T024–T027).
- `App.kt` does not compile between T003 and T009; run the foundational tests with the host test filter only.

### User story dependencies

- **US1** depends on Foundational.
- **US2** depends on US1 (the shell hosts the state holder).
- **US3** depends on Foundational and edits the same `App.kt` regions as US1, so it follows US1.
- **US4** depends on US1 only; it can run beside US2 or US3 once T007 exists.

### Within each story

- Tests first and failing, then implementation, then the checkpoint.
- Tasks on `app/App.kt` (T009, T010, T013, T015, T020, T021, T023) are sequential.

### Parallel opportunities

- T004/T005 beside T002/T003.
- T006 and T008 beside each other.
- T011 and T012; T016 and T017; T024 and T025.

---

## Parallel Example: User Story 1

```text
Task: "T006 [US1] TabNavigationShellTest cases in iosUiTest/TabNavigationShellTest.kt"
Task: "T008 [US1] GallrNavigationBar takes AppTab in app/ui/components/GallrNavigationBar.kt"
```

## Implementation Strategy

### MVP (US1 + US2)

1. Foundational: the trail model and its tests.
2. US1: the shell and the root rebuilt on it.
3. US2: saved state per entry and the single composer.
4. **Stop and validate** quickstart steps 1–4. This is the smallest releasable increment.

### Then

5. US3: back and in-place sign-in.
6. US4: keyboard.
7. Polish: docs, gates, manual pass on both platforms.

## Notes

- Commit after each checkpoint.
- No screen file outside `App.kt`, `GallrNavigationBar.kt`, `ProfileTab.kt` and the new files should need layout
  changes; if one does, it means an inset is being padded by hand. Fix it there and note it in T027.
