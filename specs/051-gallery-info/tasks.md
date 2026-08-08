# Tasks: Gallery Info

- [x] T001 Validate canonical migration lineage and record the existing owner/venue/geocode contracts.
- [x] T002 Add failing pgTAP coverage for revision, authorization, pending-owner distinction,
  allowlists, tenant isolation, audit logging, canonical venue writes, snapshot copying, and
  snapshot non-propagation.
- [x] T003 Generate and implement the additive Gallery Info migration with least-privilege grants,
  owner RPCs, aggregate revisioning, and generic staff/owner geocode quota RPCs.
- [x] T004 Add failing Edge tests for staff continuity, eligible/ineligible owners, quota scope,
  fail-closed behavior, and bounded sanitized responses; update the existing geocoder implementation.
- [x] T005 Add failing Gallery repository and geocoding-adapter tests for strict response parsing,
  optimistic save arguments, bounded candidates, and malformed payload rejection.
- [x] T006 Add failing Gallery component/navigation tests for tab access, explicit address selection,
  stale-selection clearing, saving, conflicts, pending eligibility, accessibility, and mobile behavior.
- [x] T007 Implement the Gallery Info domain/repository/service/component/navigation/styles slice.
- [x] T008 Update the gallery owner release runbook and relevant architecture/config documentation.
- [x] T009 Verify lineage, clean replay, all pgTAP, DB lint, all Edge tests, product config, Gallery
  tests/typecheck/build, and desktop/mobile rendered behavior.
