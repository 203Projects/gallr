# Implementation and verification

- [x] Failing public-navigation and individual-form contract tests.
- [x] Bilingual choice page and homepage/header entry points.
- [x] Safe public Auth configuration, email-link verification and form client.
- [x] Failing pgTAP contracts; verified-identity intake migration.
- [x] Browser success/error/retry/callback and responsive checks.
- [x] Full Web gates, clean database replay, pgTAP/lint/security checks and CI.
- [x] Reviewable PR and documented environment-specific rollout requirements.


Verification: `npm test` passed all Node/build/accessibility gates and 116 browser
tests. A clean disposable replay passed 51 pgTAP suites / 1,662 assertions,
schema lint, security advisors, and all existing concurrency regressions.
GitHub PR #293 database and public-web CI passed on runtime commit
`33e51d907bff76a78caf15cc4d48321693acbc14`, including the supported Ubuntu
toolchain safety suite. The local macOS toolchain test's setpgid limitation
does not reproduce in CI.

Rollout order: migrations, exact public Auth callback allowlist, staging Auth-link/image/review evidence, then activation of the public-form flag and a dedicated develop-to-main promotion. Completion is recorded below.

## Required image follow-up

- [x] Failing client/browser/database image contracts before implementation
- [x] Verified upload reservation, exact ownership Storage policy, transactional image intake
- [x] Required image chooser, preview, validation and retry feedback
- [x] Full web/database checks and staging Auth-link/image upload/staff review verification
- [x] Reviewed develop integration and production promotion

Image follow-up local verification: clean 92-migration replay; 52 pgTAP suites / 1,696 assertions; schema lint and security advisors passed. All eight existing concurrency suites plus individual image reservation/retry/quota races passed. Full public web Node/build/accessibility gates and 116 Playwright tests passed. Red tests failed for missing image validator/chooser/reservation before implementation. This initial local evidence preceded the hosted staging and production checks below.

Hosted staging verification at `f9034d99be2aeb0b6825855e13e96a4f7cbedeb5`: all 52 pgTAP suites / 1,696 assertions and schema lint passed; security advisors reported only nine existing warnings, with none on the new RPCs. Real Auth signup and returning-user callbacks, physical Storage upload, upload/submission retries, private-read denial and browser submission passed. Staff acceptance of the browser-uploaded image created an unpublished cover draft inside a rolled-back transaction. PR #296 merged into develop. No SMTP delivery was exercised; the optional connected-mailbox test awaits approval. Production promotion is recorded below.

Expired-upload regression: reproduce expired cached and persisted pending reservations; renew without re-verification; prove uploaded ambiguous retries still reuse the original image and submission identity after a full page reload.

Expiry regression verification: Node and browser tests reproduced the old missing-expiry check and verification loop before implementation. Full web contract/build/accessibility checks and all 120 Playwright tests passed after the fix.

## Production completion — 2026-09-30

- PR #299 merged the pending-upload expiry recovery; PR #300 promoted reviewed develop to main at `4ca9e48a6a95617914d82b43a7303bbb32f86b15`. The public-web, database, Android/shared-Kotlin and Codex review checks passed.
- Production applied only `20260930031557` and `20260930075724`, bringing history to 92 migrations while retaining all 412 catalogue entries. Native TLS target checks verified the committed Seoul fingerprint.
- The exact `https://gallrmap.com/submit/individual/` Auth callback was added while preserving the Gallery Site URL and all existing redirect entries. `GALLR_ENABLE_INDIVIDUAL_SUBMISSION=1` was enabled only for the public site's Production environment.
- Vercel production deployment `dpl_ASq7WBnAyaHS9VsE6bqPeU5Y4L7m` is READY from the exact main commit above. Live public checks verified both entry choices, required image input, the pending-upload recovery, the matched production Auth project, public-only client credentials and anonymous denial for both intake RPCs. No production fixtures were created.
- The Publish GitHub release workflow published immutable `v1.11.0` from that production commit. VERSION and Android versionName are 1.11.0; versionCode is 38. Mobile store upload was outside this rollout.
- SMTP inbox delivery was not exercised; the optional connected-mailbox test still awaits explicit approval. Auth-link verification and the physical image/review journey were verified on hosted staging.
