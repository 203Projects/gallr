# Feature Specification: Hide exhibitions from My Exhibitions

## User story

As an eligible gallery owner, I can remove an exhibition from **My exhibitions**
without deleting its canonical record, submitted review history, or published
catalog snapshot.

## Requirements

- The action is a soft hide for every owner status, including draft, submitted,
  needs changes, published, and archived.
- Hidden exhibitions no longer appear in `owner_list_exhibitions()` for that
  gallery. Admin, review, publication, public catalog, metrics, media, and audit
  records remain unchanged.
- An active owner may hide an exhibition belonging to their gallery. A pending
  owner may do so only for a new pending gallery they personally created. A
  pending claimant for an existing gallery and every cross-tenant caller fail
  closed.
- The browser uses an owner RPC; it receives no direct canonical-table write.
- The RPC verifies the displayed working-version ID and revision, is idempotent,
  and appends metadata-only audit evidence.
- The UI labels the action “Remove from My exhibitions”, explains that published
  and submitted records remain intact, asks for explicit confirmation, and
  removes the row only after the server accepts the command.
- No AI, deployment, credential, commit, or staging changes are part of this feature.

## Acceptance scenarios

1. An active owner hides a draft and it disappears from their list while all
   exhibition/version rows remain.
2. An active owner hides submitted and published exhibitions; review and public
   database state remain intact.
3. A stale revision, pending claimant, or cross-tenant caller cannot hide.
4. A repeated accepted request remains successful without duplicate audit rows.
5. Canceling the UI confirmation performs no RPC; confirming performs one RPC
   and removes only that row from the rendered list.
