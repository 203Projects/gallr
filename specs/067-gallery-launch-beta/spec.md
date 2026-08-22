# Feature Specification: Free Gallery Launch Beta

**Feature Branch:** `shin/067-gallery-launch-beta`
**Created:** 2026-08-22
**Status:** Complete

## Product boundary

This feature supersedes the paid activation portion of spec 049. The existing
RSVP, private guest list, manual guest entry, revocable token, and idempotent
check-in contracts remain authoritative. R3 launches first as a free beta.
Payment, pricing, subscriptions, and refunds require a future specification.

R2 public impact remains independent. R4 local promotion remains separately
gated and must not become visible merely because R3 is enabled.

## User stories

### US1 — Activate a free Launch Kit

As an active gallery owner, I can explicitly activate one free Launch Kit for a
currently published exhibition and enter its opening-night workspace without a
checkout or payment redirect.

### US2 — Share an RSVP link and QR code

As an owner with an active Launch Kit, I can open and copy the environment-
matched RSVP link and download a print-ready QR code containing that same
revocable URL.

As an invited visitor, the RSVP link presents the published exhibition cover,
identity, description, exhibition period, opening reception, venue/address,
hours, and gallery contact before asking for my RSVP.

### US3 — Manage and check in guests

As an owner, I can search/filter the private guest list, add a walk-in, see RSVP,
party-size, and checked-in totals, and use a responsive check-in mode. A repeated
check-in preserves the original arrival time.

If my gallery has multiple active Launch Kits, I can select each exhibition and
reach its matching invitation, totals, guest list, and check-in state.

### US4 — Release R3 without exposing R4

As the operator, I can enable the owner Launch Kit and public RSVP independently
while Gallery promotion requests, Admin promotion review, server delivery, and
visitor promotion presentation remain disabled.

## Acceptance criteria

1. Only an active owner of the exhibition's gallery may activate a Kit, and the
   exhibition must be currently published, non-archived, and backed by its
   canonical published version.
2. Activation is an idempotent, revision-safe database command with a stable
   request ID. Replays and concurrent attempts create at most one Kit and return
   the same active entitlement.
3. A pre-existing pending Kit may become active. Cancelled or refunded Kits do
   not silently reactivate.
4. Activation records one bounded audit event without payment claims or guest
   data. Publication, Featured, catalogue/map/search ordering, and public
   eligibility remain unchanged.
5. Every active Kit has an explicit provider-independent entitlement source.
   Existing payment-backed active rows are `paid`; beta activation creates
   `free_beta`. R4 owner requests and visitor selection require `paid` in the
   database and reject/ignore `free_beta` even if a client flag is misconfigured.
6. Browser roles retain no direct privileges on Launch Kit, guest, rate-limit,
   or historical payment tables. The public activation wrapper is executable
   only by authenticated owners and independently authorizes tenancy.
7. The old checkout preparation, checkout attachment, and payment webhook RPCs
   are absent after the migration. Historical nullable payment columns and
   webhook-event rows are preserved without becoming a beta entitlement.
8. The owner action creates or returns the active Kit directly and navigates to
   the Launch Kit workspace. No checkout URL, success query string, Stripe SDK,
   or payment secret is required.
9. The RSVP URL derives from `VITE_PUBLIC_SITE_URL`, contains only the random
   revocable token, and updates immediately after token rotation.
10. QR generation is browser-local and loaded only on demand. The downloaded SVG
   encodes the exact displayed RSVP URL, has a quiet zone and error correction,
   contains no guest/personal data, and uses a filesystem-safe filename.
11. Public RSVP retains bounded name/email/party-size validation, explicit
    privacy acknowledgement, keyed rate limiting, normalized-email
    deduplication, and generic responses that do not reveal prior registration.
12. Public RSVP lookup exposes only already-published presentation fields:
    cover URL, bilingual exhibition and venue identity, bilingual description,
    exhibition dates, reception date/time, address, hours, and contact. Payment,
    membership, review, audit, internal media, and guest data remain absent.
13. The RSVP page renders the published cover as the primary visual and groups
    reception time, exhibition period, location, hours, contact, and description
    before the form. Optional empty fields collapse without empty labels; a
    missing or failed image leaves a clean text-only layout without broken alt
    text.
14. Guest names/emails remain private to the owning gallery. Listing stays
    keyset-paginated; manual add and check-in commands remain idempotent and
    tenant-scoped.
15. Multiple active Kits are selectable by exhibition. Switching Kits resets
    pagination/search state and never shows guests or promotion state from the
    previously selected exhibition.
16. A pending gallery membership never sees or enters the Launch Kit workspace,
    even when the environment-wide R3 flag is enabled.
17. With `VITE_OWNER_PROMOTION_ENABLED` absent or false, the Gallery application
    makes no promotion RPC and renders no promotion request UI. R3 and R4 can be
    enabled or rolled back independently.
18. Admin promotion review, the public/mobile presentation clients, and the
    `promoted-nearby` server endpoint each have their own default-off R4 control.
    Every disabled surface fails closed without changing organic discovery.
19. All new UI follows `DESIGN.md`: sharp edges, monochrome surfaces, 8pt-grid
    spacing, minimum 44px controls, and orange only for the primary action or
    active indicator.
20. Database, repository, component, QR utility, Edge, public-web,
    accessibility, and rendered browser tests cover successful activation,
    denial, replay/concurrency, token rotation, exact QR payload, promotion
    isolation, RSVP submission, guest privacy, and repeated check-in.
21. Production deployment, hosted function/config changes, migration apply,
    account-gate changes, and production guest collection require a separate
    reviewed release authorization.

## Out of scope

- Pricing, Stripe or another payment provider, subscriptions, invoices,
  discounts, refunds, tax handling, or paid entitlement migration.
- Social-image generation, press kits, inquiry capture, CRM, email campaigns,
  teams, multiple door roles, or richer/billing-grade reports.
- R4 promotion activation or changes to organic/editorial discovery.
- Production deployment, secrets, Auth redirects, DNS, or data-retention policy
  activation.
