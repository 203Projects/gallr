# Implementation Plan: My Gallr account backup and restore

## Architecture

`Compose UI -> MyGallrViewModel -> auth-aware repositories -> local guest/account store + narrow
authenticated RPC source`

The database stores private account visit and gallery rows plus an idempotent mutation receipt.
Commands serialize by user, apply ordered operations transactionally, advance one account revision,
and return a complete redacted archive snapshot. The client keeps a per-user cache and a durable
pending-operation queue. Guest DataStore payloads remain independent.

## Merge and conflict policy

- First sign-in enqueues additive guest records, applies them to the account, commits the returned
  account cache, and only then removes the acknowledged guest records.
- Normal account changes enqueue operations before updating the visible account cache. Failed sends
  remain pending and retry on launch/resume.
- The server's accepted mutation order is authoritative. An old device with no pending operation
  applies the newer server state instead of re-uploading its stale cache, preventing resurrection.
- A gallery follow syncs with `newExhibitionAlertsEnabled=false`; each installation retains its own
  explicit alert preference separately.

## Verification

- pgTAP: anonymous denial, owner isolation, direct-table denial, idempotent retry, ordered add/remove,
  revision advancement, and sanitized snapshots.
- Common tests: first merge, retry, removal convergence, account switch isolation, offline pending
  queue, and restored alerts-off behavior.
- Existing Android, iOS, lint, Deno, and migration-lineage gates remain required.
