# Feature Specification: My Gallr account backup and restore

**Feature branch**: `shin/060-my-gallr-guest-archive`
**Status**: Local implementation, clean replay, authenticated multi-client restore, and isolated
hosted branch validation complete; the signed-in real-device manual pass remains
**Depends on**: `060-my-gallr-guest-archive`, `061-my-gallr-gallery-following`,
`063-followed-gallery-publication-alerts`

## Goal

Turn account creation into a concrete My Gallr benefit: visits and followed galleries are backed up
after sign-in and restored on another device, while the guest experience remains fully useful
without an account.

## User stories

### US1 — Back up an existing guest collection (P1)

As a guest who has already recorded visits or followed galleries, I can sign in and keep the union
of my device collection and any collection already attached to my account.

**Acceptance criteria**

1. Sign-in uploads guest visits and gallery follows without discarding cloud records.
2. Repeating the merge is idempotent and does not duplicate records.
3. A sync failure leaves the guest collection intact and exposes a retryable state.
4. The account invitation truthfully names archive backup and cross-device restore.

### US2 — Restore My Gallr on another device (P1)

As a signed-in member, I can open Gallr on another device and recover my visits and followed
galleries.

**Acceptance criteria**

1. Authenticated My Gallr reads the account collection and retains a private per-account cache.
2. Visit removal, follow, unfollow, and gallery acknowledgement converge across devices.
3. Retried mutations are idempotent and cross-device removals do not resurrect merely because an
   older device reconnects.
4. Signing out returns to the separate guest collection; another account cannot see the previous
   account's cached collection.

### US3 — Keep notification consent device-specific (P1)

As a member restoring followed galleries, I choose separately whether each device may notify me.

**Acceptance criteria**

1. Gallery identity, snapshot, and acknowledgement state sync with the account.
2. `newExhibitionAlertsEnabled`, provider addresses, and OS permission state never sync as account
   archive fields.
3. A restored gallery defaults to alerts off on a new installation.
4. Registering an installation while authenticated associates only that proven installation with
   the current account.

## Functional requirements

- **FR-001**: Server records MUST be private to `auth.uid()` and unavailable to anonymous users.
- **FR-002**: Client roles MUST mutate account archives only through narrow authenticated commands;
  direct table writes are forbidden.
- **FR-003**: Every client mutation MUST carry a stable high-entropy mutation ID and ambiguous
  retries MUST return the same logical result.
- **FR-004**: Account mutation ordering MUST be serialized per user and advance a monotonic archive
  revision.
- **FR-005**: The client MUST persist pending account mutations and account cache separately from
  the guest archive.
- **FR-006**: Initial guest merge MUST enqueue additive operations and clear guest records only after
  the server has acknowledged those exact mutations and the account cache is committed.
- **FR-007**: Account switching and sign-out MUST never expose one account's cache to another account
  or to a guest.
- **FR-008**: Remote notification opt-in MUST remain installation-local and MUST NOT be inferred from
  a restored gallery follow.
- **FR-009**: Sync failures MUST be redacted, retryable, and must not block local archive browsing.

## Out of scope

- Public profiles, social activity, ratings, notes, or shared collections.
- Background sync while the application is terminated.
- Email alerts or account-level notification consent.
- Production migration, remote deployment, provider provisioning, or staging activation.

## Success measures

- Guest-to-account merge completion rate.
- Percentage of members successfully restoring at least one My Gallr record on a second device.
- No duplicate visits/follows after retries.
- No cross-account cache disclosure and no automatic notification opt-in after restore.
