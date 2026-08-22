# Feature Specification: Paid Gallery Launch Kit

> **Superseded for activation by spec 067.** This file preserves the original
> paid implementation contract and historical test rationale. The RSVP, guest,
> token, and check-in requirements remain applicable; the current beta uses the
> free entitlement and QR contract in
> [`067-gallery-launch-beta`](../067-gallery-launch-beta/spec.md). Any future
> paid rollout requires a new commercial specification.

## User stories

### Story 1 — Purchase for a published exhibition

As an active gallery owner, I can purchase one Launch Kit for an exhibition
that Gallr has already published for free. Checkout is hosted by Stripe and the
kit becomes active only after Gallr verifies Stripe's signed paid webhook.

### Story 2 — Share an RSVP page

As an owner with an active Launch Kit, I receive a revocable public RSVP URL for
that exhibition. Visitors can submit their name, email, and party size after
affirming the RSVP privacy notice.

### Story 3 — Manage opening-night guests

As the gallery owner, I can search and filter my exhibition's guest list, add a
guest manually, and see going/guest/check-in totals without reading any other
gallery's personal data.

### Story 4 — Check guests in

As the gallery owner at the door, I can use a responsive check-in view and mark
a guest checked in once. Replayed check-in commands are idempotent and preserve
the original arrival time.

## Acceptance criteria

1. Launch Kit is a one-time Checkout Session purchase, not a subscription.
2. Only an active owner of a currently published, non-archived exhibition may
   start Checkout. Free publication remains unchanged and purchase never changes
   Featured, catalogue order, search, map ranking, or public eligibility.
3. Checkout uses a configured Stripe Price ID and server-side secret. No Stripe
   secret or trusted price amount is accepted from browser input.
4. Checkout creates a pending kit. Browser success/cancel redirects never grant
   access. Only a correctly signed paid `checkout.session.completed` webhook can
   activate it, and Stripe event/session/payment identifiers are unique.
5. Webhook processing is idempotent and records the authoritative paid amount,
   currency, payment intent, event ID, and activation time.
6. An active kit has a random public token that can be rotated. Public lookup
   exposes only published exhibition RSVP presentation fields, never owner,
   payment, membership, or internal review data.
7. Public RSVP accepts only bounded name/email/party-size fields and an explicit
   privacy acknowledgement. It rate-limits by a keyed request digest and
   deduplicates normalized email per kit without exposing whether an address was
   previously registered.
8. Guest email/name data is not publicly readable. Browser roles receive no
   direct table privileges; public writes pass through the narrow Edge endpoint
   and private service RPC.
9. Owners can list, search, filter, add, and check in guests only for active kits
   belonging to their gallery. Guest listing uses keyset pagination.
10. Check-in is idempotent: the first command records arrival; retries return the
    same guest and never replace the original timestamp.
11. Owner summaries distinguish RSVP records, total party size, and checked-in
    party size. They are real values, not inferred page views.
12. Owner guest-list and mobile check-in UI follow the accepted generated
    concepts and `DESIGN.md`: true white, monochrome, sharp edges, table/open
    rows, and orange only for the primary action/active indicator.
13. Database, Edge, repository, component, public-web, accessibility, and browser
    tests cover eligibility, tenant isolation, webhook signatures/idempotency,
    personal-data boundaries, duplicate RSVP, and replayed check-in.

## Out of scope

- Subscriptions, teams, multiple door staff, refunds UI, invoices, tax advice,
  discount codes, saved cards, or off-session charging.
- QR/social asset generation, inquiries, promotion, CRM sync, or richer reports.
- Unique visitor analytics or billing based on page-load metrics.
- Production Stripe product/price creation, webhook registration, credential
  changes, deployment, DNS, or data-retention automation.
