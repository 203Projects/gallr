# Implementation Plan: Always-visible tab bar

**Branch**: `094-persistent-tab-bar` | **Date**: 2026-10-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/094-persistent-tab-bar/spec.md`

## Summary

The tab bar moves out of the tab screens into one root `Scaffold` that hosts every screen, so it is visible
everywhere except while the keyboard is open. `AppNavigationState` changes from one current destination with
remembered back targets to a trail of screens per tab: opening a screen pushes onto the current tab's trail, back
pops it, switching tabs keeps every trail and re-tapping a tab clears its own. A saveable state holder keyed by entry
brings back scroll positions after a tab switch. Screens consume the root's bottom inset, so their own action bars
sit directly above the tab bar with no per-screen layout work. Sign-in becomes an `Account` screen opened on top of
the screen that asked (research R5, spec FR-008 amended).

## Technical Context

**Language/Version**: Kotlin 2.x Multiplatform, Compose Multiplatform (versions in `gradle/libs.versions.toml`)
**Primary Dependencies**: Compose foundation/material3 already in `composeApp`; no new dependency
**Storage**: N/A (in-memory navigation state)
**Testing**: `commonTest` (kotlin-test) for `AppNavigationState` and `isTabBarVisible`; `iosTest` Compose UI tests
with `runComposeUiTest` (v2) for the shell (bar over a destination, state restored after a tab switch, bar hidden
for an IME inset)
**Target Platform**: Android (host app `androidApp`) and iOS (`iosApp` host), shared UI in `composeApp/commonMain`
**Project Type**: mobile app (KMP)
**Performance Goals**: tab switch and back show the next screen within the existing 150–200ms crossfade; only the
visible screen is composed
**Constraints**: DESIGN.md unchanged for the bar; no NavController (CLAUDE.md); Android back at a tab's top unchanged;
no content hidden behind either bar at the largest font scale
**Scale/Scope**: 1 state class, 1 root shell in `App.kt`, 12 destinations (11 today + `Account`), `ProfileTab`'s
account view extracted, about 15 call sites of the old `return…`/`showTabs` calls

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Spec-First | `spec.md` committed (`188b625a`) before planning; FR-008 amendment recorded in the spec with its reason | PASS |
| II. Test-First | `AppNavigationStateTest` is rewritten first for trails, re-tap, back, dedupe, the single composer and sign-in; shell UI tests fail before the shell moves | PASS (enforced in tasks) |
| III. Simplicity & YAGNI | No navigation library; one state class; screens unchanged except back wiring; no process-death restoration (not required) | PASS |
| IV. Incremental Delivery | US1 + US2 ship together (a visible bar without per-tab trails would discard work on every tap); US3 and US4 are verified on the same build | PASS |
| V. Observability | No new logging; screen-view analytics keep their contract (FR-015) | PASS |
| VI. Shared-First | Navigation state is presentation orchestration and stays in `composeApp/commonMain` beside the existing `AppNavigationState`; no business rule moves into UI | PASS |
| VII. Mobile-First | App-only change | PASS |

**Post-design re-check**: unchanged, all PASS.

## Project Structure

### Documentation (this feature)

```text
specs/094-persistent-tab-bar/
├── plan.md              # This file
├── research.md          # R1–R9
├── data-model.md        # AppTab, NavEntry, trails and transitions
├── quickstart.md        # Automated and manual verification
├── contracts/
│   └── tab-navigation.md
├── checklists/requirements.md
└── tasks.md             # /speckit.tasks
```

### Source Code (repository root)

```text
composeApp/src/commonMain/kotlin/com/gallr/app/
├── AppNavigationState.kt        # trails per tab, NavEntry, AppTab, back(), Account destination
├── App.kt                       # root Scaffold + bar, content host with SaveableStateHolder, one back handler
├── TabNavigationShell.kt        # new: the root shell composable and isTabBarVisible (testable without App)
├── ui/components/GallrNavigationBar.kt   # unchanged look; takes AppTab
└── ui/profile/
    ├── ProfileTab.kt            # local showAccount removed; onAccount opens the Account destination
    └── AccountScreen.kt         # new: today's account view (sign-in / profile) extracted from ProfileTab

composeApp/src/commonTest/kotlin/com/gallr/app/
├── AppNavigationStateTest.kt    # rewritten for trails
├── PublicRouteNavigationTest.kt # updated for push/back and in-place sign-in
└── TabBarVisibilityTest.kt      # isTabBarVisible

composeApp/src/iosTest/kotlin/com/gallr/app/
└── TabNavigationShellTest.kt    # bar over a destination, saved state after a tab switch, bar hidden for IME
```

**Structure Decision**: All changes live in `composeApp`; `shared`, `androidApp` and `iosApp` are untouched.

## Owner decisions (2026-10-15)

1. The bar stays on every screen.
2. Each tab remembers its screens; re-tapping a tab returns it to its top.
3. The bar steps aside only while the keyboard is open; the full-screen map keeps it.

Planning added one consequence, recorded in the spec: sign-in opens over the screen that asked (R5).

## Risks

- **Screen state held in plain `remember`** resets after a tab switch (R2). Mitigation: convert the values a visitor
  would notice, verified by the quickstart's "each tab remembers" pass.
- **Inset double-counting** on a screen that pads the bottom by hand. Mitigation: `consumeWindowInsets` at the root
  and the quickstart's "last item above the bar" pass at the largest font size.
- **Back handler order on Android**: inner layers must still win. Mitigation: the root handler is composed first and
  enabled only while the trail is non-empty; covered by the quickstart back pass.
- **Analytics double counts** when returning to a tab whose top is a detail screen. That is the intended "screen
  viewed" semantics (R9); the analytics test is updated to state it.

## Complexity Tracking

No violations to justify.
