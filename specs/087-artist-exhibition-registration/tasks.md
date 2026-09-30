# Tasks

- [x] Write and observe failing navigation, domain/adapter and pgTAP contracts.
- [x] Add authenticated registration/upload reservation, finalization and account-only status RPCs.
- [x] Implement exhibition-first public entry points, accessible form, authentication, review and status UI.
- [x] Add build configuration, pinned client bundle and release gate.
- [x] Verify web behavior at desktop/mobile and backend isolation, retries, upload validation and staff handoff.
- [x] Update account/access and intake documentation with activation boundaries and actual verification evidence.

Verification: Node 22.23.1 full web suite passed, including 111 Playwright tests,
registration adapter/domain/configuration checks and accessibility audits. Clean replay of all
91 migrations passed in a task-specific disposable local database; all 51 pgTAP suites passed
(1,673 assertions), migration history matched, database lint/security advisors passed, real
libpq routing passed, and all eight existing concurrency regressions passed. `git diff --check`
passed. Browser Auth/RPC/Storage transport uses intercepted local fixtures; live email and
Auth/Storage HTTP integration are reserved for the required staging rehearsal before activation.

Final review follow-up: added an indexed account-reservation ledger, a clear expiry error before
Storage writes (40 artist pgTAP assertions), and a two-session regression proving concurrent retry
identity and the three-per-hour quota. Database lint/security advisors and focused web tests passed.
