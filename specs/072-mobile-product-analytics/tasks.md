# Tasks: Mobile product analytics

- [x] T001 Remove raw OAuth URL and catalogue-cache exception-message logging;
  add redaction regressions.
- [x] T002 Add and observe failing closed-event serialization, forbidden-field,
  no-op, queue-cap, TTL, retry, and opt-out tests.
- [x] T003 Implement typed shared events, recorder contract, bounded queue, and
  Ktor batch client.
- [x] T004 Create the migration with Supabase CLI, then add failing pgTAP tests
  for RLS/grants, aggregate increments, dedupe, quotas, and pruning.
- [x] T005 Implement the private receipt/aggregate/quota schema and transactional
  service recorder.
- [x] T006 Add failing Edge handler/backend tests for validation, body/batch
  bounds, source identity, sanitized failures, and kill switch.
- [x] T007 Implement and configure `mobile-analytics`; extend product-surface
  configuration validation.
- [x] T008 Wire common mobile capture points behind disabled-by-default release
  and user gates without adding a platform analytics SDK.
- [x] T009 Update privacy/store disclosures and add analytics preference UI
  before any production enablement request.
- [x] T010 Run shared/Compose/Android/iOS, Edge, migration, pgTAP, lint, advisor,
  and concurrency gates.
- [x] T011 Open a `develop`-targeted PR and complete review/CI.
