# Tasks: Local discovery intelligence

- [x] T001 Add and observe failing bilingual recommendation, cold-start,
  explicit-signal, visited-exclusion, diversity, and determinism tests.
- [x] T002 Implement local content vectors, component scoring, explanations, and
  diversity re-ranking in `shared/commonMain`.
- [x] T003 Add and observe failing route-mode, stop-count, invalid-coordinate,
  distinct-venue, distance, duration, and determinism tests.
- [x] T004 Implement the provider-neutral leg contract, local estimator, and
  neighborhood route planner in `shared/commonMain`.
- [x] T005 Run focused and full shared verification and record performance for a
  representative catalogue fixture.
  - Android host: 153 ms for 1,205 exhibitions.
  - iOS simulator: 1.058 s for 1,205 exhibitions.
- [x] T005A Split catalogue preparation from reranking, reuse an exact immutable
  index across signal/date changes, reject duplicate IDs and result limits above
  20, and verify deterministic concurrent reads. The expanded fixture covering
  one 1,205-row preparation plus 20 prepared reranks completed in 175 ms on the
  Android host and 1.595 s on the iOS simulator.
- [x] T006 Specify and implement the mobile presentation/ViewModel as a separate
  independently reviewable story after the shared engine is stable.
- [x] T007 Open a `develop`-targeted PR and complete review/CI.
