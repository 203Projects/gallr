# Production verification — 2026-09-22

## Released

- Feature PR [#283](https://github.com/203Projects/gallr/pull/283) and production
  promotion PR [#284](https://github.com/203Projects/gallr/pull/284) merged after
  all CI checks passed.
- Production source: `aac2d635bf61178e30696f54f0a868027559c034`.
- Gallery's production domain resolves to the READY Vercel deployment of that
  exact main commit, not a preview.
- Applied only `20260922023714_complete_workflow_email_delivery.sql`; verified
  the snapshot columns, new contact RPC, preserved old RPC, security-invoker
  wrapper and pinned internal search path.
- Set the explicit production email environment before deploying receiver v28
  and worker v24 from the exact main checkout. Prior function sources were
  retained for rollback. The compatible additive schema can remain on app rollback.

## Live acceptance

The operator explicitly waived hosted staging and approved labelled unpublished
production fixtures. Four claim cases cover existing/new galleries and both
decisions; two exhibition cases cover approval/rejection with an entered contact
different from the account address. All fixture galleries were disabled and the
synthetic publisher deactivated before commit, preventing public discovery or
unrelated claims. No exhibition was published and no existing media was modified.

- **22/22 scoped outbox events delivered**, including **12 workflow notifications**:
  six intake notifications and six decisions.
- Decision recipients were provider test-sink aliases; intake used the approved
  existing staff audience. All three rejection snapshots retained their saved
  review reason; all intake snapshots included a timestamp.
- A claim retained its original captured address after the synthetic Auth email
  changed. Both submission decisions used the separately entered contact.
- A deliberate provider idempotency conflict produced a retryable failure without
  undoing the approved claim. Restoring the original test payload recovered on
  attempt 2. An identical replay was accepted using the same idempotency key.
  This tests provider rejection/recovery, not a deliberately induced global outage.
- The existing cron worker processed the events; its schedule and global
  housekeeping were not manually accelerated. The cadence is roughly one event
  per minute under this test burst.
- Unauthorized receiver request rejected with 401.
- Cleanup removed only the synthetic fixture scope. Final checks: zero test
  users, galleries, exhibitions or events; zero pending workflow notifications.
- Published exhibitions remained **408**. Pre-existing failed outbox events
  remained **52**; those unrelated failures were not altered by this release.

Provider acceptance and the outbox's delivered state do not independently prove
human inbox receipt. No recipient lists, email bodies, review content or secret
values were retained in the test evidence.

The first live test attempt used an incorrect runner assertion (`pending` rather
than retryable `failed` with no dead-letter timestamp). It was stopped and all
fixtures cleaned before staff intake release; one decision test-sink message had
been accepted. The corrected runner completed the results above. Product code
did not require a fix for that test-harness mismatch.

## Verification and remaining boundaries

Fresh local/CI gates: native tests/style/lint/build/link, Gallery **131** tests,
Admin **265**, email receiver **64**, worker **15**, clean replay of **90**
migrations and **1,634** pgTAP assertions. Backfill, concurrency, and Linux
safety suites passed. Review fixes included app-share failure diagnostics,
off-main preview bitmap decoding and accessible decision-email error feedback.

The share-preview source is merged and verified on Android, iPhone and iPad;
the latest iPhone native cancellation/re-share regression also passed after the
review fixes. **Mobile store upload/review was not performed or authorized.**
Real KakaoTalk/Instagram destination sends remain unverified on equipped devices.

The production browser smoke tool failed with `No active page`; no rendered
production UI check is claimed. Deployment/domain identity and live backend
acceptance were verified separately. The unused Free staging project and its
separate credentials remain intact; its old sealed policy is not production authority.
