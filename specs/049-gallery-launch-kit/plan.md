# Implementation Plan: Free Gallery Launch Kit

## Architecture

Use one authenticated, idempotent owner RPC to activate a Launch Kit immediately
for a currently published exhibition owned by that gallery. No payment provider,
checkout Edge Function, webhook, client price, or billing credential is present.

Store entitlements and guest data in canonical `content` tables, behind narrow
owner and service RPCs. Public RSVP uses a random kit token and a dedicated Edge
handler; it never grants browser roles direct guest-table access.

## Data model

- `content.launch_kits`: one row per exhibition with lifecycle, random public
  token, activation metadata, and optimistic revision. Historical nullable
  payment columns are inert lineage fields and are not part of the active contract.
- `content.launch_guests`: kit-scoped RSVP/owner entries with normalized email,
  party size, status, privacy/source evidence, and immutable first check-in time.
- `content.launch_rsvp_rate_limits`: keyed request digests and bounded windows;
  no raw IP address or user agent is stored.

All foreign keys and filter/join paths receive matching indexes. RLS is enabled;
generic browser/owner table grants are revoked in favor of RPC authorization.

## Application surfaces

- Published owner exhibition: working `Activate free Launch Kit` action.
- Launch Kit workspace: table-first guest list, public RSVP link, real totals,
  search/filter, manual add, and check-in mode.
- Public `/rsvp/` page: exhibition identity, compact RSVP form, privacy notice,
  success state, and no discovery/ranking changes.

## Verification

1. Add failing pgTAP contracts for free activation, grants, tenant isolation,
   public RSVP, pagination, and check-in replay.
2. Implement schema and narrow RPCs; run focused/full DB tests and lint.
3. Prove checkout/payment RPCs and deployable functions are absent.
4. Implement public RSVP handler/page and owner repository/UI tests.
5. Run builds, accessibility, browser workflows, and concept fidelity review at
   1440x1000 desktop and 390x844 mobile.

## Visual reference

- Desktop owner guest list:
  `/Users/hanshin/.codex/generated_images/019fb78a-01e0-7000-bd37-d1fec8eae08b/exec-b54232af-0135-4694-8924-9fae9be8926b.png`
- Mobile check-in:
  `/Users/hanshin/.codex/generated_images/019fb78a-01e0-7000-bd37-d1fec8eae08b/exec-3d68dbf2-4dd8-4167-87d0-fbdde95c1d9b.png`
