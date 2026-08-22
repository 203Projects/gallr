# Implementation Plan: Paid Gallery Launch Kit

> Historical plan. Activation is superseded by
> [`067-gallery-launch-beta`](../067-gallery-launch-beta/plan.md); do not restore
> the removed checkout/webhook runtime from this document.

## Architecture

Use Stripe-hosted Checkout Sessions for the one-time purchase. The authenticated
checkout Edge Function obtains an owner-authorized pending-kit context from
Postgres, then creates Checkout using only the configured Price ID. A separate
unauthenticated webhook function verifies the raw request signature with Stripe
before calling the service-only activation RPC.

Store entitlements and guest data in canonical `content` tables, behind narrow
owner and service RPCs. Public RSVP uses a random kit token and a dedicated Edge
handler; it never grants browser roles direct guest-table access.

## Data model

- `content.launch_kits`: one row per exhibition, pending/active/cancelled/refunded
  lifecycle, Stripe identifiers, authoritative amount/currency, random public
  token, activation metadata, and optimistic revision.
- `content.launch_guests`: kit-scoped RSVP/owner entries with normalized email,
  party size, status, privacy/source evidence, and immutable first check-in time.
- `content.launch_rsvp_rate_limits`: keyed request digests and bounded windows;
  no raw IP address or user agent is stored.

All foreign keys and filter/join paths receive matching indexes. RLS is enabled;
generic browser/owner table grants are revoked in favor of RPC authorization.

## Application surfaces

- Published owner exhibition: working “Launch this exhibition” action.
- Launch Kit workspace: table-first guest list, public RSVP link, real totals,
  search/filter, manual add, and check-in mode.
- Public `/rsvp/` page: exhibition identity, compact RSVP form, privacy notice,
  success state, and no discovery/ranking changes.

## Verification

1. Add failing pgTAP contracts for payment lifecycle, grants, tenant isolation,
   public RSVP, pagination, and check-in replay.
2. Implement schema and narrow RPCs; run focused/full DB tests and lint.
3. Add Stripe Checkout/webhook handler tests using injected SDK boundaries.
4. Implement public RSVP handler/page and owner repository/UI tests.
5. Run builds, accessibility, browser workflows, and concept fidelity review at
   1440x1000 desktop and 390x844 mobile.

## Visual reference

- Desktop owner guest list:
  `/Users/hanshin/.codex/generated_images/019fb78a-01e0-7000-bd37-d1fec8eae08b/exec-b54232af-0135-4694-8924-9fae9be8926b.png`
- Mobile check-in:
  `/Users/hanshin/.codex/generated_images/019fb78a-01e0-7000-bd37-d1fec8eae08b/exec-3d68dbf2-4dd8-4167-87d0-fbdde95c1d9b.png`
