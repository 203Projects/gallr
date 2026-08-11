# Account deletion runbook

This runbook governs the irreversible authenticated consumer-account deletion
path. It does not authorize a migration, Edge deployment, credential change,
or deletion of a real user. Confirm the exact staging or production Supabase
project and use only that environment's 1Password items.

## Retention and recovery decision

For ordinary consumer accounts, a confirmed command immediately deletes the
Supabase Auth identity. Database-owned profile, bookmark, thought, rate-limit,
and command-receipt rows cascade in the same database transaction. This action
has no recovery window. Existing editorial records retain their content while
nullable actor references become anonymous.

Avatar objects are deleted asynchronously because Auth/database and Storage do
not share a transaction. A durable outbox event is written before Auth
deletion. The worker removes only root `avatars/<user-id>.<extension>` objects,
only after proving the Auth identity no longer exists. It retries transient
failures and scrubs the user UUID from the event after successful cleanup. The
audit retains the opaque deletion request ID and `cleanup_scheduled=true`; it
does not retain the user's email or credential.

Operational accounts are not self-service deletable while they have active
staff access or a pending, active, or suspended gallery membership. Support
must identify the exact account, transfer required ownership, revoke the
operational role, and ask the user to retry. Never bypass the database trigger
or delete the role row merely to make the command pass without confirming the
business transfer.

## Staging verification

Use a new disposable consumer fixture whose email and avatar exist only in the
approved staging environment.

1. Verify migration `20260808110631`, `delete-account`, and the updated
   `outbox-worker` are deployed to the same staging project.
2. Confirm the outbox scheduler is active and healthy. Record only project
   fingerprints, function versions, and timestamps; do not record keys, tokens,
   raw project references, or user JWTs.
3. With a session older than 15 minutes, verify the app requests a fresh
   sign-in and creates no cleanup event.
4. Sign in again, upload a staging-only avatar, create a bookmark and thought,
   and confirm deletion in the app.
5. Verify the Auth identity, profile, bookmark, thought, rate counter, and any
   command receipt are absent. Verify the avatar cleanup event is delivered,
   the object is absent, and its delivered event no longer contains the user
   UUID.
6. Separately verify active staff and pending/active/suspended owner fixtures
   receive the support path and remain intact.
7. Induce one staging-only Storage failure. Confirm the account remains deleted,
   cleanup retries, and the object is removed after recovery.

Stop if the environment identity is unclear, the outbox scheduler is inactive,
an operator account can be deleted, a consumer identity survives a reported
success, or a delivered cleanup record still contains the user UUID.

### Staging evidence — 2026-08-08

A billed, data-less, disposable preview branch of Seoul production was used for
the first hosted rehearsal. The migration, updated outbox worker, and
`delete-account` function were deployed to that branch. Disposable fixtures
passed stale-session rejection, recent-session deletion, profile/bookmark/
thought/rate-limit cascades, avatar cleanup, delivered-event identity
scrubbing, intentional identity-present worker failure and retry, active-staff
protection at both the command and database-trigger layers, rate limiting, and
fixture cleanup. The preview branch and its test-only probes, credentials,
users, events, and objects were deleted after the run; no branch remains
billable. This evidence authorizes no production deployment or real-user test.

## Production rollout and rollback

Production requires its own approved change record after staging evidence.
Apply the migration before deploying either function, deploy the worker before
enabling the client path, and use component-scoped production credentials from
the production 1Password items.

Before the first real deletion, verify monitoring for function failures,
dead-lettered `account.avatar_cleanup_requested` events, and avatar cleanup
latency. The code deployment can be rolled back before a deletion. A completed
account deletion cannot be rolled back; never describe redeployment or database
restore as user-level recovery.

### Production evidence — 2026-08-09

The separately approved rollout completed against Seoul production
(`gallr-korea`, `ap-northeast-2`). Seven completed daily physical backups were
available before the change; the newest preceded the rollout by less than 13
hours. PITR was not enabled, and database backups do not recover deleted Storage
objects. Migration `20260808110631` was applied with canonical history, followed
by `outbox-worker` version 12 and `delete-account` version 1. The worker's first
scheduled post-deploy call returned HTTP 200. The deletion endpoint requires
gateway JWT verification and returned HTTP 401 without a bearer token.

Supabase API-key names require underscores, so production uses component name
`delete_account` in the hosted publishable/secret maps. The two new keys are in
separate Seoul-only 1Password items; no existing key was rotated, replaced, or
disabled. Post-deploy checks found all 120 Auth users intact, zero deletion
rate-limit rows, zero avatar-cleanup events, the expected database trigger and
least-privilege grants, and no blocked or long-running transaction. No production
user or disposable production fixture was deleted.

The preflight also found one older, unrelated dead-lettered
`submission.accepted` notification. The account-deletion rollout did not replay, acknowledge, or
modify it. It was resolved afterward through a separate audited recovery documented in
`docs/admin-media-and-outbox-runbook.md`.

For an ambiguous client result, do not ask the user to retry immediately.
First refresh or reopen the app: an absent Auth session indicates deletion
likely completed and the durable cleanup event must remain. If the identity
still exists, the command may be retried after the rate window.
