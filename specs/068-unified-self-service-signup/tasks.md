# Tasks: Unified Self-Service Signup

- [x] T001 Record the shared-identity, open-signup, closed-privilege product
  boundary and the separate hosted rollout gate.
- [x] T002 [US3] Add and observe failing Gallery adapter/component tests for
  signup-disabled and generic OAuth callback errors plus URL cleanup.
- [x] T003 [US3] Implement bounded Gallery callback error parsing and bilingual
  actionable copy without leaking raw Auth parameters.
- [x] T004 [US1] Add and observe failing Gallr ViewModel tests for
  signup-disabled email and OAuth classification.
- [x] T005 [US1] Implement Gallr signup-disabled error state and KO/EN copy.
- [x] T006 [US1] Add and observe failing product-config tests for global
  self-service signup, verified email signup, and disabled SMS/anonymous signup.
- [x] T007 [US1] Align `supabase/config.toml` and the config validator with the
  approved shared signup policy.
- [x] T008 [US2] Update identity architecture, Gallery documentation, and the
  release runbook so signup never grants owner/editor/staff access.
- [x] T009 Run focused then full Gallery, configuration, shared/KMP, Android,
  and iOS-relevant verification; record any hosted checks not run.
- [x] T010 Open a `develop`-targeted PR and complete review/CI before requesting
  any hosted staging or production Auth configuration change. Completed by PR #225.
