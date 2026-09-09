# Specification: Mobile product analytics

## Product boundary

Gallr's mobile apps are the primary customer surface and therefore the primary
source for product analytics. The first release records only aggregate product
events needed to evaluate discovery, local recommendations, route creation, and
high-intent actions.

This is not diagnostic logging, public-web page-load impact, advertising
attribution, or a user surveillance pipeline. It does not receive a taste
vector, search text, route trace, precise location, URL, contact value, thought,
guest data, account identifier, or reusable installation identifier.

## User Story 1 — Measure mobile discovery (P1)

As the product owner, I can understand which mobile discovery surfaces lead to
exhibition opens and meaningful actions without identifying individual users.

### Acceptance criteria

1. A closed shared event model supports only:
   - `surface_viewed`
   - `exhibition_impression`
   - `exhibition_opened`
   - `exhibition_intent`
   - `recommendations_shown`
   - `route_created`
   - `route_started`
2. Every dimension is an enum or bounded validated identifier. Free-form
   property maps are forbidden.
3. Recommendation analytics include a result count; per-result impressions and
   opens use rank buckets. They never include local scores, reason vectors,
   input history, or profile features.
4. Route analytics include curation mode, stop-count, and coarse distance/time
   bands, never origin, coordinates, geometry, venue sequence, or route ID.
5. `open_maps` is recorded only after platform handoff succeeds. Bookmark and
   visit intent events reflect completed state changes, not attempted taps.
   The wire action `share` means the platform share sheet opened successfully;
   it never claims that the user completed a share.
6. Mobile capture is controlled by both a release kill switch and a user-facing
   analytics preference. Until policy/store disclosure and the preference UI
   ship, the production default is disabled.

## User Story 2 — Deliver safely while offline (P1)

As a mobile user, analytics never blocks app behavior and tolerates offline use
without leaking or accumulating an unbounded history.

### Acceptance criteria

1. A dedicated purgeable DataStore queue holds at most 200 typed events for at
   most seven days and drops the oldest overflow.
2. Each queued event has a random retry identity used only for idempotency. It
   is not reused as an installation or account identity.
3. Recording is append-before-send; confirmed batches are removed atomically.
4. Failures retain the bounded queue and emit only stable redacted diagnostic
   operations. Analytics failure never changes navigation or product state.
5. Disabling analytics immediately clears the queue and prevents identity or
   network creation.

## User Story 3 — Aggregate first-party reporting (P1)

As the product owner, I can query daily aggregate counts from Supabase without
retaining raw behavioral event rows.

### Acceptance criteria

1. A dedicated `mobile-analytics` Edge Function accepts bounded POST batches,
   rejects unknown fields, and never reads cookies or Auth identity.
2. The database transaction stores only:
   - seven-day event-ID receipts with no event attributes, for retry dedupe;
   - daily aggregate counters across allowlisted dimensions.
3. Aggregate and receipt tables are private/defense-in-depth RLS protected.
   `anon` and `authenticated` receive no direct table or recorder-function
   privileges.
4. The function uses a component-scoped server secret, sanitized responses, a
   body/batch limit, server-derived source rate bounds, and no raw request or
   provider-body logging.
5. Mobile analytics remains separate from public exhibition page-load impact,
   operational logs, and disabled paid-promotion data.
6. Because v1 has no stable person/device identity, reports must not claim
   unique users, sessions, funnels across visits, or retention. Those require a
   later privacy decision and separate specification.

## Privacy prerequisites

- Remove existing raw OAuth callback URL logging on iOS.
- Route catalogue-cache failures through `AppLog` rather than raw `println` or
  exception messages.
- Update privacy/store disclosures and add the preference UI before enabling
  production collection.
- Do not reuse gallery-alert, promotion, Auth, or account-sync identities.

## Success criteria

- Shared serialization/queue tests prove no forbidden fields can be represented.
- Edge and pgTAP suites prove validation, rate bounds, dedupe, aggregate
  arithmetic, receipt expiry, and least privilege.
- Android/iOS verification proves analytics-disabled behavior performs no
  network call and analytics failures never affect customer flows.
