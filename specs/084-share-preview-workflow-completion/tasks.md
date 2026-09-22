# Tasks

## Setup

- [x] T001 Read guidance and write spec/plan/research/contracts in specs/084-share-preview-workflow-completion/.

## US1 — Share preview

- [x] T002 [US1] Add failing theme/palette/preview lifecycle tests in composeApp/src/commonTest (including shared ThemeMode rule).
- [x] T003 [US1] Implement ThemeMode resolution and share image/palette/state in shared and composeApp commonMain.
- [x] T004 [US1] Split native render/share, add sheet metadata and completion guards in ShareHandler.android.kt and ShareHandler.ios.kt.
- [x] T005 [US1] Add full-screen preview in composeApp ui/detail and wire App.kt/detail callbacks.
- [x] T006 [US1] Run shared/Compose tests/style, Android build/lint, iOS simulator link and document manual gaps.

## US2 — Decision emails

- [x] T007 [US2] Add failing workflow email tests in supabase/functions/outbox-delivery and payload tests in supabase/tests.
- [x] T008 [US2] Capture claim email snapshots and submission contact via new migration and gallery contract/UI.
- [x] T009 [US2] Reuse upstream bilingual escaped decision emails; complete provider configuration/isolation and retry diagnostics.

## US3 — Intake emails

- [x] T010 [US3] Preserve and verify upstream transactional intake payloads for both claim types and exhibition submissions.
- [x] T011 [US3] Reuse upstream intake email content and query-parameter Admin review links; avoid duplicate dispatch.
- [x] T012 [US3] Add privacy-safe worker attempt/final-failure logs and tests.

## Verification

- [x] T013 Run Deno, database, Admin/gallery gates and update delivery runbook.
- [x] T014 Review scoped diff and record completed/unavailable checks in this directory.

Dependencies: T002 → T003 → T004/T005 → T006. Email T007 → T008/T009 → T010/T011/T012 → T013. Mobile and email paths are independently implementable. Native Android/iOS adapters can be verified independently after shared contracts exist.

## Current execution status

Completion is based on current develop in an isolated worktree. See verification.md
for current results rather than the earlier branch's counts.

- [x] T015 Preserve current upstream workflows and analytics during integration.
- [x] T016 Fix and verify iOS share thumbnail and outside-tap cancellation/re-share on iPhone and iPad.
- [x] T017 Fresh 90-migration replay, 1,625 pgTAP assertions, two backfill assertions, and eight concurrency suites.
- [x] T018 Finish pixel-verified native language/theme matrix and capture final evidence.
- [ ] T019 Staging migration/deployment and sink-only real-provider delivery/retry rehearsal (requires staging credentials).
- [ ] T020 Authorized KakaoTalk/Instagram destination verification on equipped devices.
- [ ] T021 Final release review/CI and coordinated rollout; store submission requires explicit platform approval.
