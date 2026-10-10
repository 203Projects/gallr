# Verification and handoff — 2026-09-22

**Final production outcome:** [production-verification.md](production-verification.md).
The checkpoint notes below precede that rollout; their pending deployment and
credential statements are historical, not the current release status.

## Scope and integration

Branch: `084-share-preview-workflow-completion`, based on develop `db2f49f`,
isolated at `/private/tmp/gallr-completion.XAtXAA`. Original dirty worktree and
unrelated web/release-artifact changes are preserved.

Upstream already provides intake/decision events, bilingual escaped templates,
and Admin query-parameter review links. This branch reuses those paths, avoiding
duplicate dispatch. Mobile preserves native-presentation share analytics and
existing discovery navigation.

## Completed automated gates

- Kotlin/Compose style and all tests, Android lint/debug build, iOS release
  simulator framework link all passed together:

  ```sh
  ./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests androidApp:ktlintCheck androidApp:lintDebug androidApp:assembleDebug composeApp:linkReleaseFrameworkIosSimulatorArm64 --console=plain
  ```

- Four preview lifecycle/theme tests pass.
- Outbox delivery: 64 tests and checks passed (Deno 2.9.4).
- Outbox worker: 15 tests/checks passed; logs use allowlisted codes instead of
  raw provider text, exception messages, or recipient data.
- Gallery: 131 tests, typecheck and production build passed (Node 22).
- Current-base Admin: 265 tests, typecheck and production build passed (Node 22),
  with ambient Supabase build variables cleared for the fixture tests.
- Fresh native, Gallery, Admin, and email gates were rerun on this branch after
  confirming it is up to date with develop. The email formatter caught a README
  line-wrap issue; fixed in `2df7ae9`, then the full email gates passed.
- Fresh replay of all 90 migrations succeeded.
- Canonical migration lineage and 16 guard regression tests passed; database
  schema lint and security advisors report no issues after the final replay.
- Additional continuation checks: 14 database-target/validated-transport/legacy
  mirror safety tests passed; the safe-shell startup-injection test passed.
  The broader shell safety sequence stopped at reviewed-toolchain.test.sh:
  macOS reported `child setpgid: Operation not permitted`. Subsequent suites in
  that initial sequence did not run; all eleven suites and reviewed-toolchain
  subsequently passed unchanged in Linux, as recorded below. No guard was
  weakened to bypass the host failure.
- Full pgTAP: 50 files, 1,634 assertions passed after final migration change
  and a fresh 90-migration replay.
- Actual migration backfill regression: two assertions passed. First observed
  red before preferring saved intake addresses over changed Auth addresses.
  Reapplication preserves snapshots. CI invokes the same runner.
- Eight concurrency suites passed: catalogue, art metadata, public rebuild
  coalescing, alert installation, competing claim approval, Launch Kit, account
  archive, and owner withdrawal versus staff decision (both winners).

Local database: `supabase_db_gallr-completion-verification`, workspace
`/private/tmp/gallr-completion-db.lhTI5Y`. Synthetic data only; original
`supabase_db_gallr` untouched. CLI reset rebound all interfaces on this Mac;
the task-owned container was recreated at explicit loopback `127.0.0.1:59322`,
preserving its disposable volume.

## Native UI evidence

Artifacts: `output/gallr-completion-20260922/` in the operator's local evidence
workspace (outside this repository).

- iPhone/iPad native regression tests opened, dismissed outside, and reopened
  the share sheet twice, then returned to detail successfully.
- Testing found two iOS defects missed by compilation: generic thumbnail and
  Share disabled after outside dismissal. URL-backed metadata image/icon and
  idempotent activity-controller disappearance handling fixed both.
- Android chooser shows the thumbnail; cancellation re-enables Share.
- Android, iPhone and iPad: KO/EN × System/Light/Dark with dark OS, then KO/System
  with light OS. Seven screenshots each; card/background pixels match
  expected palettes (255 light, 18 dark).
- Initial shared-iPhone System/dark screenshots were light despite their labels.
  Rerunning on a dedicated simulator with appearance applied after launch and
  parallel testing disabled passed all seven pixel checks; no production theme
  change was needed. Final evidence is in `iphone-matrix-dedicated/`.
- English dark capture demonstrates missing-cover fallback.
- KakaoTalk/Instagram are not installed; no destination send was performed.

## Remaining release verification

### Approved change of plan

The user explicitly requested skipping staging and testing production, then
approved the described rollout through normal PRs with labelled, unpublished
test fixtures. This supersedes the staging rehearsal gate for this release,
not the production safety checks or the separate mobile-store approval gate.
Intake tests may reach the production intake inbox and two active admin
accounts. Decision tests use the Resend acceptance sink `delivered@resend.dev`;
provider acceptance does not establish human inbox receipt. Existing users and
published exhibitions are excluded from fixture mutations. Production read-only
inventory found no pending claims or queued workflow-email events, and confirmed
that the snapshot/contact migration is not yet installed.

The unused staging project and credentials remain intact. Its sealed intent
policy applies only to the old staging candidate and grants no production authority.

All eleven additional shell safety suites passed in an isolated Linux container,
and the reviewed-toolchain test passed there unchanged. The macOS process-group
failure is not waived; the Linux results cover the corresponding local gate.

Code is committed and pushed to the feature branch. No remote migration,
deployment, real email send or PR has been performed at this handoff.

Implementation is preserved in local commits `0ce9d49` (native preview) and
`7c9355a` (workflow email completion). Earlier 1Password authorization timeouts
were resolved for the new staging item verification below.

1. Staging credential provisioned with user approval: Resend
   `gallr-staging-workflow-email`, sending-only access scoped to
   `auth.gallrmap.com`. User saved it in the **Gallr** vault (not DEV).
   Reference: `op://Gallr/gallr-staging-workflow-email/password`.
   CLI confirmed the item, a nonempty password and Resend key format without
   displaying the value. Production `gallr-korea-smtp` was left unchanged.
   This confirms credential storage, not provider delivery or deployed configuration.
2. Follow the approved production exception above and the outbox-delivery
   README's configuration and deployment order. Verify the exact production
   identity before migration and receiver/worker/Gallery deployment, then run
   only the approved fixture delivery/retry checks. Explicitly triage historical
   undelivered events. The following staging preparation is retained as audit
   history; its rehearsal is superseded for this release.
   **Environment provisioned with explicit user approval:** created a separate
   **Gallr Staging** organization on Free ($0/month), then **gallr-staging** in
   Seoul (`ap-northeast-2`) after the provider quoted $0/month for this exact
   organization. Read-only verification reports ACTIVE_HEALTHY and an empty
   migration history. Staging project-reference SHA-256:
   `895b9a952be25460006ac145f10ce7798157585aa97d0635508e3a5252a186d0`.
   This is a fresh project, not a branch or production clone. Do not reuse the
   old DEV staging credentials; new target-bound credentials, migration setup,
   deployment, and sink-only provider rehearsal remain pending. No existing
   project was paused, upgraded, or modified.
   New project API credentials are saved and read-back verified in
   `op://Gallr/gallr-staging-20260922-api/password` (server-only secret key)
   and the same item's `publishable_key` field. The item's username identifies
   the fresh staging project. These are not the database password. The initial
   CLI stdin create produced an empty item; filling that task-created empty
   item with an explicit stdin template and comparing both saved fields to the
   provider values succeeded. No secret values were displayed or persisted in
   repository files.
   With separate explicit user approval, generated a new 48-character random
   database password in `op://Gallr/gallr-staging-20260922-database/password`.
   Rechecked the exact fresh staging project name, organization, region and
   healthy status before the change. Supabase's database-password endpoint
   accepted the update with HTTP 200; subsequent 1Password read-back matched.
   Production and historical credentials were untouched. Direct database login
   verification remains part of migration preflight; remote migrations are unstarted.
   The saved staging Resend key was compared in memory and differs from the
   production SMTP key; neither credential value was displayed.
3. Perform authorized KakaoTalk/Instagram checks on equipped devices/test
   accounts. State tests cover loading/failure/cancel,
   but not every native network-failure UI case was fault-injected.
4. Final current-diff review, CI/PR and coordinated rollout. Store upload or
   review submission requires explicit platform-specific approval.

## Router prerequisite (already complete)

`~/.codex/skills/typesafe-model-router` is linked into Claude skills.
Five tests and two live TypeSafe smoke cases passed. It selects settings and
launch arguments; it did not switch this chat and is not an automatic orchestrator.
