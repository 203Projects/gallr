# Tasks: Local discovery experience

- [x] T001 Add failing ViewModel tests for prepared-index reuse, signal reranks,
  cold start, route modes, route persistence, and insufficient candidates.
- [x] T002 Add failing pure presentation tests for bilingual recommendation
  reasons, modes, distances, durations, numbered stops, warnings, and errors.
- [x] T003 Implement `LocalDiscoveryViewModel` on a background dispatcher with
  memory-only recommendation and route state.
- [x] T004 Implement the DESIGN-compliant recommendations screen and Featured
  entry point, including bookmark/detail behavior and visible impressions.
- [x] T005 Implement the route screen and current-map-center entry point with
  single-stop external map handoff and explicit estimate warnings.
- [x] T006 Extend typed navigation so recommendation and route details return to
  their originating surface without losing in-memory state.
- [x] T007 Wire only the existing allowlisted recommendation/route analytics and
  prove that no local profile, origin, coordinates, or stop identity is emitted.
- [x] T008 Run shared/Compose/Android/iOS verification plus bilingual runtime,
  accessibility, release-gate, and no-paid-dependency checks.
- [x] T009 Open a stacked PR, complete independent review, and reach green CI.
