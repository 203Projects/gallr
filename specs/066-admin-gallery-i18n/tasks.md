# Tasks: Bilingual Admin and Gallery portals

**Input**: [spec.md](./spec.md) and [plan.md](./plan.md)

## Phase 1 — Specification and inventory

- [x] T001 Write the bilingual portal specification and pass both Constitution Checks.
- [x] T002 Inventory interface-owned strings, accessible labels, display formatting, and bilingual
  entity labels across `admin/src` and `gallery/src`.

## Phase 2 — Locale foundations

- [x] T003 [P] [US2] Add failing Admin locale resolution, persistence, document-language, and live
  switching tests in `admin/src/i18n.test.tsx`.
- [x] T004 [P] [US2] Add failing Gallery locale resolution, persistence, document-language, and live
  switching tests in `gallery/src/i18n.test.tsx`.
- [x] T005 [P] [US2] Implement the dependency-free Admin locale provider and typed messages in
  `admin/src/i18n.tsx`.
- [x] T006 [P] [US2] Implement the dependency-free Gallery locale provider and typed messages in
  `gallery/src/i18n.tsx`.

## Phase 3 — User Story 1: work in either language

- [x] T007 [P] [US1] Add failing representative Korean Admin workflow tests before changing Admin
  components.
- [x] T008 [P] [US1] Add failing representative Korean Gallery workflow tests before changing Gallery
  components.
- [x] T009 [US1] Localize Admin blocked/auth/navigation/list/detail/review/dialog/feedback states and
  add the persistent language control across the outer shell.
- [x] T010 [US1] Localize Gallery blocked/auth/onboarding/navigation/exhibition/gallery-info/Launch Kit
  states and add the persistent language control across the outer shell.

## Phase 4 — User Story 3: localized content and formatting

- [x] T011 [P] [US3] Add failing active-locale entity fallback and locale-formatting tests for Admin.
- [x] T012 [P] [US3] Add failing active-locale entity fallback and locale-formatting tests for Gallery.
- [x] T013 [US3] Route Admin statuses, display dates/counts, and read-only bilingual entity labels
  through locale-aware helpers while preserving canonical form values.
- [x] T014 [US3] Route Gallery statuses, display dates/counts, and read-only bilingual entity labels
  through locale-aware helpers while preserving canonical form values.

## Phase 5 — Verification and polish

- [x] T015 Audit `admin/src` and `gallery/src` for remaining interface-owned English in Korean mode.
- [x] T016 Run full test, typecheck, and build gates for both portals.
- [x] T017 Run Browser-plugin desktop/mobile rendered QA for language switching, persistence, layout,
  accessible language metadata, console health, and representative interactions.
- [x] T018 Run `git diff --check`, review the React changes against the performance checklist, and
  update this task list with completed evidence.

## Dependencies & Execution Order

- T001–T002 define scope.
- T003–T004 must fail before T005–T006 are implemented.
- T007–T008 must fail before T009–T010 are implemented.
- T011–T012 must fail before T013–T014 are implemented.
- Admin and Gallery work can proceed in parallel because they are separate deployables.
- Full verification starts after both portal slices are complete.
