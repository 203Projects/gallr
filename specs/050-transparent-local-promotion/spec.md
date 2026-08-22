# Feature Specification: Transparent Local Promotion

**Feature Branch**: `050-transparent-local-promotion`
**Created**: 2026-07-31
**Status**: In progress

## Product boundary

R4 adds one paid, clearly labelled local placement to the Gallery Launch Kit. It does not
change catalogue ordering, map results, search results, saved exhibitions, editorial Featured,
or homepage curation. A promotion is available only for an active Launch Kit attached to a
currently published gallery-owned exhibition.

The Launch Kit payment is the entitlement. The forward R3 beta migration records
this provider-independently as `entitlement_source=paid`; a `free_beta` Kit is
never R4-eligible. R4 introduces no second price, budget, auction, bid, radius,
or self-serve ranking control.

## User stories

### US1 — Gallery owner requests local promotion (P1)

An owner with an active Launch Kit can request promotion for that exhibition and see its
review state. Repeated network delivery of the same request is idempotent. An owner cannot
request promotion for another gallery, an inactive Kit, or an unpublished exhibition.

**Acceptance criteria**

1. The active Launch Kit workspace offers `Request local promotion` and explains that the
   placement is paid, locally relevant, staff reviewed, and frequency limited.
2. A successful request shows `Submitted for review`; rejection shows the staff note; an
   approved/active request shows its scheduled window.
3. Owners have no direct table privileges and can access only their own request through
   explicit command/query functions.

### US2 — Staff reviews and schedules promotion (P1)

Staff can review submitted requests independently from editorial curation. Approval requires
an explicit start and end time and cannot outlive the exhibition. Rejection requires a note.

**Acceptance criteria**

1. `Promotions` is a dedicated staff-admin section; Featured controls remain in Curation.
2. Staff can filter requests, approve with a valid schedule, or reject with a reason.
3. Every decision is idempotent and emits an audit-log and outbox event.
4. Only publisher-or-higher staff roles may read or decide requests.

### US3 — Visitor sees a transparent, relevant, capped placement (P1)

A visitor who has selected a city or region may see at most one currently active promotion
matching that locality per Seoul calendar day.

**Acceptance criteria**

1. The module is labelled `Promoted near you` and `Paid placement · Shown at most once per
   day` (localized on mobile), with an explanation link.
2. The placement is rendered before, and outside, the organic result collection. Removing it
   leaves catalogue/map/search/Featured data and ordering byte-for-byte unchanged.
3. The runtime endpoint returns no placement without a non-empty locality, for expired or
   unpublished records, or after the same installation was served any promotion that Seoul day.
4. The service stores only a SHA-256 digest of an installation-scoped random key, never the raw
   key, IP address, account identifier, or precise coordinates.
5. Web and mobile fail closed: if the promotion service is unavailable, organic discovery
   continues normally with no empty promotional shell.

## Functional requirements

- **FR-001** Promotion states are `submitted`, `approved`, `active`, `rejected`, and `ended`.
- **FR-002** There is at most one promotion record per Launch Kit.
- **FR-003** Owner requests derive exhibition, gallery, city, and region from the active Kit's
  published exhibition; the client cannot provide or override them.
- **FR-003A** Owner request and service delivery independently require the Kit's
  entitlement source to be `paid`. A directly seeded promotion backed by a
  `free_beta` Kit must not be delivered.
- **FR-004** Staff approval requires `starts_at < ends_at`, a future end, and an end no later
  than the published exhibition closing day in `Asia/Seoul`.
- **FR-005** Eligibility is computed at read time from status/schedule plus the canonical
  published version. Stale records must never leak.
- **FR-006** A service-only atomic selector chooses at most one matching campaign and records
  the daily cap in the same transaction.
- **FR-007** Rotation among equally eligible campaigns is deterministic for a viewer/day and
  must not alter an organic ranking field.
- **FR-008** Web uses a local random installation key and the selected `city` query filter.
- **FR-009** Mobile stores a random installation key in DataStore and requests promotion only
  for the selected city/region while the All Exhibitions tab is active.
- **FR-010** No promotion table is readable or writable directly by browser roles.
- **FR-011** Impression rows are pseudonymous and contain only campaign id, SHA-256 viewer
  digest, Seoul display date, locality, and timestamp.
- **FR-012** Operational logs are structured and exclude the raw installation key.

## Non-goals

- Additional checkout, subscriptions, credits, bidding, spend controls, radius targeting,
  demographic targeting, precise geolocation, conversion attribution, or campaign analytics.
- Promotion in Featured, map pins, search ranking, catalogue ordering, editorial slots, or push
  notifications.
- Owner-selected dates, creative uploads, custom copy, or multiple simultaneous campaigns.

## Success and quality criteria

- Database pgTAP proves tenant isolation, least privilege, state transitions, schedule bounds,
  published-record checks, locality matching, and the once-per-day cap.
- Edge tests prove validation, raw-key hashing boundary, CORS, safe failures, and structured logs.
- Shared KMP tests prove DTO mapping, installation-key persistence, and promotion state behavior.
- Web/admin/gallery tests prove disclosure copy, separation from organic results, and accessible
  interactions at desktop and mobile widths.
