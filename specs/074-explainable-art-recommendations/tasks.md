# Tasks: Explainable artist and style recommendations

- [x] T001 Add failing pgTAP and concurrency tests for private artist/taxonomy
  storage, version cloning/replacement, owner/staff authorization, unresolved
  publish denial, canonical publication, and legacy compatibility.
- [x] T002 Create the additive migration with controlled vocabulary, private
  RLS/grants, presence-sensitive Admin/owner patch contracts, bounded lookups,
  publication guard, and canonical-v2 metadata projection.
- [x] T003 Add failing shared DTO/domain tests for rich/default-empty metadata,
  canonical-v2 versus legacy selection, malformed entries, and cache
  compatibility; then implement the additive catalogue contract.
- [x] T004 Add failing recommender tests for exact artist and controlled-term
  evidence, group-show normalization, lower-priority text fallback, nonempty
  evidence, deterministic diversity, and prepared-index invalidation; then
  implement the pure local scoring/evidence model.
- [x] T005 Add failing Compose tests for bilingual `WHY THIS` presentation,
  card accessibility, evidence-only empty state, route evidence snapshots, and
  analytics structural exclusion; then implement the mobile UI and route flow.
- [x] T006 Add failing Admin repository/component tests for supported-empty
  metadata, artist search/create/resolve, term selection, ordering, revision
  conflicts, publication blocking, malformed responses, and accessibility;
  then implement the staff Art editor.
- [x] T007 Add failing Gallery repository/component tests for canonical artist
  selection, unresolved suggestions, ordered credits, controlled terms,
  editable-state rules, stale revisions, malformed responses, and
  accessibility; then implement owner metadata entry.
- [x] T008 Run full database, shared, Compose, Android, Admin, Gallery, and iOS
  verification plus a bilingual runtime visual/accessibility pass.
- [x] T009 Independently review security, privacy, scoring truthfulness,
  compatibility, and React/KMP quality; fix all P1/P2 findings.
- [x] T010 Push a stacked PR without deploying schema/configuration, enabling
  analytics, adding paid services, or changing legacy credentials/readers.
