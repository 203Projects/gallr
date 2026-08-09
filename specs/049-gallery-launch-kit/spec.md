# Feature Specification: Free Gallery Launch Kit

## User stories

### Story 1 — Activate for a published exhibition

As an active gallery owner, I can activate one free Launch Kit for an exhibition
that Gallr has already published. Activation is immediate and does not require
payment details or a payment provider.

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

1. Launch Kit is free. The current release has no checkout, subscription,
   payment-provider connection, price, invoice, or billing entitlement.
2. Only an active owner of a currently published, non-archived exhibition may
   activate a Kit. Free publication remains unchanged and activation never changes
   Featured, catalogue order, search, map ranking, or public eligibility.
3. The authenticated owner command derives the gallery and exhibition on the
   server, activates exactly one Kit per exhibition, and is replay-safe by request ID.
4. Checkout, webhook, payment activation, and client-supplied price surfaces are
   absent from application runtime and deployment configuration.
5. Activation records the actor, request ID, exhibition, free activation source,
   and activation time in the audit boundary.
6. An active kit has a random public token that can be rotated. Public lookup
   exposes only published exhibition RSVP presentation fields, never owner,
   membership or internal review data.
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
    tests cover eligibility, tenant isolation, activation idempotency,
    personal-data boundaries, duplicate RSVP, and replayed check-in.

## Out of scope

- Payments, checkout, subscriptions, refunds, invoices, tax, discounts, saved
  cards, or off-session charging. A gallery payment option is future scope.
- QR/social asset generation, inquiries, promotion, CRM sync, or richer reports.
- Unique visitor analytics or billing based on page-load metrics.
- Payment-provider credentials or setup, deployment, DNS, or data-retention automation.
