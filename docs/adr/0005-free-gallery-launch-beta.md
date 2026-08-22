# ADR-0005: Activate Gallery Launch Kit as a free beta

**Status:** Accepted
**Date:** 2026-08-22
**Decider:** gallr owner/operator
**Related:** [Gallery Launch Kit spec](../../specs/049-gallery-launch-kit/spec.md),
[Gallery launch beta spec](../../specs/067-gallery-launch-beta/spec.md)

## Context

Gallr already contains a tested RSVP, guest-list, and check-in implementation,
but its only activation path is a dormant Stripe Checkout integration. The
customer-visible feature remains disabled. A later branch proposed free
activation, but its backdated migration and stale application changes were
never merged. QR generation from the original product discussion was never
implemented.

The current product decision is to run the Launch Kit as a free beta and decide
on paid packaging later. The beta must preserve owner tenancy, guest privacy,
revocable invitation links, release-slice isolation, and the existing immutable
migration lineage. Enabling R3 must not expose the separate R4 promotion system.

## Decision

R3 uses an explicit, idempotent owner command to activate one free Launch Kit
for one currently published exhibition. The command reuses the existing
`launch_kits`, `launch_guests`, RSVP token, and guest/check-in contracts.

The change is delivered through a new chronological additive migration on
current `develop`; the stale August 9 migration is not inserted into history.
Callable payment RPCs and the two unused Stripe Edge Functions are removed from
the active product surface. Historical nullable payment columns and the webhook
event table remain in place so the migration is non-destructive and old evidence
is preserved.

Each active Kit carries an explicit provider-independent entitlement source:
`free_beta` or `paid`. Existing active rows satisfy the old payment constraint
and are backfilled as `paid`; new beta activations are `free_beta`. R4 promotion
continues to require `paid` at the database request and delivery boundaries, so
no environment flag or handcrafted RPC can turn a beta Kit into paid placement.

QR generation runs in the Gallery browser only after an owner opens an active
Launch Kit. A zero-dependency universal encoder renders the environment-matched,
revocable RSVP URL directly to a scalable SVG string on demand. No QR payload,
image, visitor identifier, or new secret is stored server-side, and the encoder
does not increase the initial owner-workspace bundle.

R4 receives separate default-off controls at every surface: owner requests,
Admin review, server delivery, public presentation, and mobile presentation.
The R3 Gallery workspace must not query or render promotion state when the owner
flag is disabled. Public impact (R2), public RSVP (R3), owner Launch Kit (R3),
and every R4 surface retain independent release controls.

Payment is future scope. A later paid design may grant the existing `paid`
entitlement through a newly specified provider/package without changing RSVP,
guest, QR, or check-in behavior.

## Options considered

### A. Keep Stripe as the beta activation path

| Dimension | Assessment |
| --- | --- |
| Complexity | High |
| Beta speed | Low |
| Privacy/operations | Requires payment, refund, and support policy now |
| Future flexibility | Couples product validation to pricing |

**Pros:** Preserves the existing checkout code and tests.

**Cons:** Contradicts the free-beta decision and blocks learning on unrelated
commercial and operational work.

### B. Merge the stale free-Launch-Kit branch

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium initially, high during conflict resolution |
| Lineage safety | Unacceptable without rewriting its old migration |
| Regression risk | High; the branch predates Gallery Info, Google sign-in, and later fixes |
| Future flexibility | Adequate after substantial repair |

**Pros:** Contains a proven free-activation design and useful tests.

**Cons:** It is far behind `develop`, includes unrelated stale changes, and
cannot safely insert its August 9 migration into the current production lineage.

### C. Port the free entitlement onto current `develop` (selected)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium |
| Lineage safety | High; one new chronological additive migration |
| Regression risk | Lowest; edits stay on current surfaces |
| Future flexibility | High; commercial entitlement remains separable |

**Pros:** Preserves current features, minimizes database change, removes dead
payment attack surface, and lets Gallr validate RSVP/check-in immediately.

**Cons:** Requires focused reimplementation and full current-branch verification.

## Consequences

- Galleries can opt into RSVP, QR, guest-list, and check-in without payment.
- Publication remains free and unchanged; Launch Kit activation remains explicit.
- Existing payment evidence is preserved, but no payment function is callable or
  deployable as part of the beta.
- R4 keeps its paid-only product invariant even while R3 beta Kits are active.
- A later paid launch requires a new product decision, specification, migration,
  and release gate rather than silently re-enabling old Stripe code.
- QR files always reflect the current RSVP token. Rotating the token intentionally
  invalidates previously downloaded QR codes.

## Action items

1. Add failing database tests and a new additive free-activation migration.
2. Replace checkout calls with the owner activation RPC.
3. Add environment-aware RSVP URL and on-demand QR download behavior.
4. Isolate every R4 owner/Admin/server/public/mobile surface behind its own flag.
5. Remove Stripe runtime code/config while preserving historical schema fields.
6. Run database, Edge, Gallery, web, accessibility, and rendered beta journeys.
