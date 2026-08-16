# Implementation Plan: My Gallr guest archive

**Branch**: `shin/060-my-gallr-guest-archive` | **Date**: 2026-08-13 | **Spec**: [spec.md](./spec.md)

## Summary

Replace the fourth-tab authentication wall with an always-available My Gallr archive. Store visits as a versioned JSON payload in the existing KMP Preferences DataStore, expose them through a shared repository, coordinate archive/search behavior in a dedicated common ViewModel, and preserve existing sign-in/profile screens as a voluntary Account subview. No backend or platform-specific behavior is added.

## Technical Context

**Language/Version**: Kotlin 2.x as configured by the repository
**Primary Dependencies**: Kotlin coroutines/Flow, kotlinx.serialization, AndroidX DataStore Preferences, Compose Multiplatform, JetBrains KMP lifecycle ViewModel
**Storage**: Existing Preferences DataStore with one versioned JSON key
**Testing**: kotlin-test and kotlinx-coroutines-test in `shared/commonTest` and `composeApp/commonTest`
**Target Platform**: Android and iOS through KMP
**Project Type**: Mobile application with shared KMP domain/data and Compose UI
**Performance Goals**: Archive mutations complete in one atomic DataStore update; search remains responsive for the current catalogue size
**Constraints**: Offline-first, no authentication dependency, no new library, square UI, immutable historical snapshot, no destructive recovery from malformed persisted data
**Scale/Scope**: One new shared repository and model, one common ViewModel, one fourth-tab feature surface, existing account/profile screens reused

## Constitution Check

### Before design

- **Spec-first**: PASS. `spec.md` defines independently testable P1-P3 stories and explicit acceptance criteria before code.
- **Test-first**: PASS by plan. Shared repository/model and common ViewModel tests will be added and observed failing before implementation.
- **Simplicity/YAGNI**: PASS. The slice uses the existing DataStore and catalogue; it adds no backend, sync abstraction, database, or platform service.
- **Incremental delivery**: PASS. Guest archive provides standalone value; account sync, following, and push remain separate later slices.
- **Observability**: PASS by plan. Persistence failures use `AppLog` operation names without IDs, URLs, snapshots, or exception messages.
- **Shared-first**: PASS. Models and persistence live in `shared/commonMain`; orchestration and UI live in `composeApp/commonMain`; no business logic enters platform source sets.

### After design

- **Shared-first placement**: PASS. `ExhibitionVisit` and `VisitRepository` own persistence semantics in `shared`; `MyGallrViewModel` coordinates immutable screen state; composables render state and emit callbacks.
- **Complexity review**: PASS. A single concrete DataStore repository is sufficient until a real cloud implementation exists; no speculative sync wrapper is introduced.
- **UI rules**: PASS. The design reuses theme tokens, `RectangleShape`, `GallrSpacing`, and existing card/image patterns; orange remains limited to the empty-state/save CTA and active tab indicator.

## Project Structure

```text
specs/060-my-gallr-guest-archive/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── tasks.md

shared/src/commonMain/kotlin/com/gallr/shared/
├── data/model/ExhibitionVisit.kt
└── repository/
    ├── VisitRepository.kt
    └── DataStoreVisitRepository.kt

shared/src/commonTest/kotlin/com/gallr/shared/
├── data/model/ExhibitionVisitTest.kt
└── repository/DataStoreVisitRepositoryTest.kt

composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/profile/ProfileTab.kt
├── ui/mygallr/
│   ├── MyGallrScreen.kt
│   └── AddPastVisitsScreen.kt
└── viewmodel/MyGallrViewModel.kt

composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/
└── MyGallrViewModelTest.kt
```

**Structure Decision**: Follow the existing Shared-First boundary. The feature is named `mygallr` in presentation while existing account/profile screens remain under `ui/profile`.

## Complexity Tracking

No constitution violations or additional dependencies are required.
