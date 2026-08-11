# Authenticated account deletion

`delete-account` is the only supported mobile account-deletion command. It
validates the bearer session against Supabase Auth, invokes the caller-scoped
database preparation RPC, deletes that same Auth identity with the component's
server credential, and returns success only after the identity is confirmed
absent.

## Contract

- The caller must have signed in within the previous 15 minutes. A stale session
  receives `reauthentication_required`; the mobile UI instructs the user to sign
  out and sign in again.
- Three preparation attempts are allowed per 15-minute user window.
- Active staff and pending, active, or suspended gallery owners receive
  `support_required`. Their Auth row is also protected by a database trigger so
  a role assigned after preparation cannot be deleted in a race. Support must
  transfer or revoke operational ownership before retrying.
- Preparation inserts a durable `account.avatar_cleanup_requested` outbox event
  before Auth deletion. The outbox worker refuses to remove avatars while the
  Auth identity exists, retries transient Storage failures, and scrubs the user
  UUID from the delivered event after cleanup.
- Consumer `profiles`, `bookmarks`, `thoughts`, rate-limit rows, and command
  receipts cascade with Auth deletion. Editorial authorship and the deletion
  audit survive only with nullable/anonymized actor references.
- Successful deletion is irreversible. There is no account or consumer-data
  recovery window. The outbox job is recovery for delayed object cleanup, not
  recovery for the deleted account.

If the administrative delete call fails and a follow-up lookup proves the
identity still exists, the function cancels the cleanup event and returns a safe
failure. If the result cannot be determined, the cleanup event remains: the
worker's identity check makes it safe, and the client receives
`deletion_status_unknown` instead of a false claim that nothing was deleted.

## Configuration and deployment

Hosted Supabase supplies `SUPABASE_URL`. Configure component-named entries for
`delete_account` in both `SUPABASE_PUBLISHABLE_KEYS` and `SUPABASE_SECRET_KEYS`,
sourced from the exact environment's 1Password items. Local single-key and
legacy anon/service-role variables remain compatibility fallbacks only while the
repository-wide API-key migration is open.

Deployment order is mandatory:

1. Apply `20260808110631_authenticated_account_deletion.sql` to the approved
   staging project.
2. Deploy the updated `outbox-worker` and `delete-account` functions to the same
   staging project.
3. Confirm the existing scheduler invokes `outbox-worker` and that a disposable
   recent-login consumer fixture deletes its Auth/database rows and avatar.
4. Exercise stale-login, rate-limit, active-staff, gallery-owner, Storage retry,
   and ambiguous-delete paths. Confirm no operator identity or credential is
   logged.
5. Record cleanup delivery and identity scrubbing, then repeat through the
   separately approved production change.

Do not deploy a function before its migration, do not use a production server
credential in staging, and do not delete a real account as a smoke-test fixture.

## Local verification

```sh
deno task check
deno task test
```

The database contract is covered by
`supabase/tests/database/023_authenticated_account_deletion.test.sql`.
