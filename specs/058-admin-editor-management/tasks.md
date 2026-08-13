# Tasks: Admin Editor Management

## Phase 1: Contract and test-first foundation

- [x] T001 [US1] Add failing admin repository mapping/RPC tests in `admin/src/repositories/SupabaseAdminEditorRepository.test.ts`.
- [x] T002 [US1] Add failing editor directory component test in `admin/src/components/EditorOnboardingWorkspace.test.tsx`.
- [x] T003 [US2] Add failing editor update component and repository tests.
- [x] T004 [US3] Add failing editor deactivate/restore component and repository tests.
- [x] T005 [US1] [US2] [US3] Add failing pgTAP authorization, revision, update, and deactivation tests in `supabase/tests/database/030_admin_editor_management.test.sql`.

## Phase 2: Backend and repository

- [x] T006 [US1] Add revision and admin list RPC in a new chronological Supabase migration.
- [x] T007 [US2] Add revision-checked admin update RPC and audit event.
- [x] T008 [US3] Add revision-checked reversible access RPC and audit events.
- [x] T009 [US1] [US2] [US3] Extend the admin repository domain, Supabase adapter, and fixture adapter.

## Phase 3: Admin experience

- [x] T010 [US1] Add the managed-editor directory to the existing Editors workspace.
- [x] T011 [US2] Add profile/schedule edit mode with validation and stale-write recovery.
- [x] T012 [US3] Add explicit deactivate confirmation and restore-access actions with preserved-history copy.
- [x] T013 [US1] [US2] [US3] Add responsive, DESIGN.md-compliant styles.

## Phase 4: Verification

- [x] T014 Run focused tests and mark red-green evidence.
- [x] T015 Run migration lineage checks and available database tests.
- [x] T016 Run admin typecheck, full tests, and build.
