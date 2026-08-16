# Tasks: My Gallr guest archive

## Phase 1: Specification and design

- [x] T001 Write prioritized user stories and acceptance criteria in `specs/060-my-gallr-guest-archive/spec.md`.
- [x] T002 Complete Shared-First and complexity gates in `specs/060-my-gallr-guest-archive/plan.md`.
- [x] T003 Record persistence, snapshot, and account-access decisions in the feature design documents.

## Phase 2: Shared archive foundation

- [x] T004 [P] [US1] Add failing model tests in `shared/src/commonTest/kotlin/com/gallr/shared/data/model/ExhibitionVisitTest.kt`.
- [x] T005 [P] [US1] Add failing persistence and idempotency tests in `shared/src/commonTest/kotlin/com/gallr/shared/repository/DataStoreVisitRepositoryTest.kt`.
- [x] T006 [US1] Verify the new shared tests fail before implementation.
- [x] T007 [US1] Implement `ExhibitionVisit` and its snapshot in `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionVisit.kt`.
- [x] T008 [US1] Implement the documented repository contract in `shared/src/commonMain/kotlin/com/gallr/shared/repository/VisitRepository.kt`.
- [x] T009 [US1] Implement versioned atomic persistence in `shared/src/commonMain/kotlin/com/gallr/shared/repository/DataStoreVisitRepository.kt`.

## Phase 3: My Gallr orchestration

- [x] T010 [P] [US1] Add failing archive state tests in `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/MyGallrViewModelTest.kt`.
- [x] T011 [P] [US2] Add failing search, bulk selection, duplicate, and save-retry tests to `MyGallrViewModelTest.kt`.
- [x] T012 [US1] Verify the new ViewModel tests fail before implementation.
- [x] T013 [US1] Implement immutable My Gallr state and archive orchestration in `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/MyGallrViewModel.kt`.

## Phase 4: Shared Compose experience

- [x] T014 [US1] Implement the empty and populated Visits archive in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/mygallr/MyGallrScreen.kt`.
- [x] T015 [US2] Implement searchable multi-select archive creation in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/mygallr/AddPastVisitsScreen.kt`.
- [x] T016 [US3] Make My Gallr the default for every auth state and preserve opt-in account/profile access in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/profile/ProfileTab.kt`.
- [x] T017 [US1] Wire one shared `DataStoreVisitRepository` through Android and iOS composition roots and `App.kt`.

## Phase 5: Verification

- [x] T018 Run focused shared and Compose common tests.
- [x] T019 Run `./gradlew shared:ktlintCheck shared:allTests`.
- [x] T020 Run `./gradlew composeApp:ktlintCheck composeApp:allTests`.
- [x] T021 Run Android lint/assemble and the available iOS framework compile gate.
- [ ] T022 Complete the manual guest/archive/account regression steps in `quickstart.md`.
  - 2026-08-14: Korean/English, light/dark, iPhone 17e compact layout, and native accessibility-tree
    checks passed. Review found and fixed unnamed selection targets, an arrow-only Back label, and
    low-contrast functional borders. Remaining work is the signed-in real-device isolation and
    hands-on VoiceOver gesture/spoken-pacing pass.
  - 2026-08-14: iOS guest archive, following, add flows, accessibility targets, account entry,
    and termination/relaunch persistence passed. A signed-in profile pass remains.
  - 2026-08-16: iOS 26.5 simulator regression passed for opening-date eligibility, visit save,
    termination/relaunch persistence, duplicate prevention, gallery following, the contextual account
    handoff, gallery detail, and the notification rationale. A future-exhibition archive defect and
    stale gallery-alert copy were found and fixed. The signed-in real-device isolation and hands-on
    VoiceOver spoken-pacing pass remain.
