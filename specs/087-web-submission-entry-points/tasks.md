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

Live rollout remains separate: migration, exact public Auth callback allowlist,
real staging email/review evidence, then activation of the public-form flag.
