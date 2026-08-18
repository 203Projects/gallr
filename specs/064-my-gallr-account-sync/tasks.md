# Tasks: My Gallr account backup and restore

- [x] T001 Define guest merge, account isolation, cross-device ordering, and notification-consent
  semantics.
- [x] T002 Add authenticated archive command tables, RPCs, RLS, and pgTAP coverage.
- [x] T003 Add shared account archive models and command-source contract tests.
- [x] T004 Implement the durable per-account cache and pending mutation queue.
- [x] T005 Implement auth-aware visit and followed-gallery repositories.
- [x] T006 Trigger merge/restore on sign-in and refresh on resume.
- [x] T007 Update My Gallr backup, restore, sync-state, and account invitation copy.
- [ ] T008 Verify focused tests, clean database replay, mobile gates, and manual account isolation.
  - 2026-08-14: focused tests, clean 75-migration replay, 1,253 pgTAP assertions, schema lint,
    security advisors, three concurrency regressions, and iOS guest/account-entry regression passed.
  - 2026-08-14: a disposable full Supabase Auth/Data API run passed with three client sessions and
    two accounts: backup, idempotent retry, restore, remote removal convergence, cross-account
    isolation, anonymous denial, and device-local notification consent. Temporary users and archive
    rows were removed.
  - 2026-08-14: an isolated temporary hosted branch passed the full 75-migration replay, all 25
    account-archive pgTAP assertions, concurrent retry/removal ordering, and real Data API add,
    retry, restore, removal, cross-account isolation, and device-local notification-consent checks.
    Hosted advisors identified and verified the gallery foreign-key index fix. All hosted fixtures
    and the temporary branch were removed. The remaining T008 work is the signed-in real-device
    manual account-isolation pass.
  - 2026-08-16: the complete isolated 75-migration replay, 1,253 pgTAP assertions, schema lint,
    security advisors, three concurrency regressions, shared/Compose/Android tests, iOS host build,
    and guest-to-account UI handoff passed again. Signed-in account isolation remains proven by the
    disposable Auth/Data API run; a final signed-in physical-device UX pass remains.
