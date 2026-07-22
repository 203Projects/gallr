# ADR-0002: Verify complete public-reader collections with keyset pagination

**Status:** Accepted
**Date:** 2026-07-21
**Deciders:** gallr engineering; production activation still requires the
content and backend owners

**Follow-up:** ADR-0003 retains this exact legacy contract and adds a separate
V2 catalog/content checksum for the canonical read model.

## Context

The website build, the mobile catalog, the mobile featured feed, and mobile
event detail all read collections from PostgREST. Their original requests were
unpaginated. PostgREST limits a response to 1,000 rows by default, so an HTTP
success could represent only the first part of the catalog. The website made
the issue harder to see by requesting `limit=2000` even though the server cap
remained 1,000.

These readers need the complete set before they can build detail pages and the
sitemap, calculate city and editor counts, join bookmarks, search locally, or
render map pins. UI-level infinite scrolling would not satisfy those consumers.

The following constraints shape the decision:

- Existing permanent IDs and reader DTO fields must remain unchanged.
- A later-page failure must never be returned or published as a complete list.
- A server may cap a requested 500-row page below 500, so a short page cannot
  prove completion.
- Transport order must be indexed and deterministic, while visitor-facing
  presentation order remains `opening_date DESC, id ASC`.
- Count alone cannot detect a concurrent delete plus insert with no net row
  change.
- Featured and event-scoped reads must use the same completeness contract and
  must retain their filters on every page.
- The solution must work in Kotlin Multiplatform and in the Node website build.
- Production data, the live reader, and the Google Sheet workflow are outside
  this local implementation step.

## Decision

We will eagerly drain public exhibition collections with an ID keyset and
verify their membership against a single-snapshot database checksum.

1. Each reader requests `order=id.asc&limit=500`. After a non-empty page, the
   final ID becomes the exclusive cursor for the next request:
   `id=gt.<encoded-id>`.
2. Readers stop only after an explicit empty page. A short page is accepted as
   data and followed by another request.
3. Filters are typed and repeated on every page. Featured uses
   `is_featured=eq.true`; event detail uses an encoded
   `event_id=eq.<event-id>`.
4. Page IDs must be nonblank and unique across the complete attempt. PostgreSQL
   and PostgREST own the `id ASC` ordering; clients do not repeat that comparison
   with JavaScript or Kotlin string semantics because database collation can
   differ. The final row of each non-empty page becomes the next cursor. A
   global seen-ID set prevents a repeated page or cursor loop, and the final
   database-order checksum detects any out-of-order, omitted, or extra member.
5. No DTO is mapped to the domain or exposed until the terminal page arrives.
   Invalid required dates fail the complete mobile request instead of being
   silently dropped.
6. The website requests `Prefer: count=exact` on its first data page and
   validates `Content-Range`. Both clients then call
   `public.exhibition_reader_integrity(text, boolean)`.
7. The integrity function is `STABLE`, `SECURITY INVOKER`, and uses an empty
   `search_path`. In one SQL statement it returns the RLS-visible row count and
   SHA-256 of the database-ordered ID stream. Each ID is encoded as its UTF-8
   byte length, a colon, and the ID; encoded values are concatenated without a
   delimiter.
8. The client computes the same checksum over the received transport order.
   Count and checksum must both match. A mismatch discards the complete attempt
   and retries from page one once. A second mismatch, a malformed integrity
   response, or any HTTP/decoding failure fails closed.
9. Only after verification do readers apply presentation order
   `opening_date DESC, id ASC`. Event detail deliberately re-sorts its verified
   subset by opening date ascending in its view model.
10. The legacy full catalog uses its primary-key index. Event-scoped reads use
    `(event_id, id) WHERE event_id IS NOT NULL`; featured reads use
    `(id) WHERE is_featured = true`.
11. The missing tracked `public.exhibitions.ticket_url` column is restored
    additively because the shipped web DTO already selects it. No live table or
    canonical data is changed by implementing this migration locally.

## Options Considered

### Option A: Raise the PostgREST row cap

| Dimension | Assessment |
|-----------|------------|
| Implementation effort | Low |
| Completeness guarantee | Temporary; fails again at the next cap |
| Payload size | Grows without a bound |
| Failure isolation | Poor; one large response |
| Operational clarity | Low; client correctness depends on server tuning |

**Pros:** Minimal client code and one request while the catalog stays small.

**Cons:** Moves rather than removes the limit, increases response size, and
still cannot prove that a successful response is complete.

### Option B: OFFSET pagination

| Dimension | Assessment |
|-----------|------------|
| Implementation effort | Low to medium |
| Deep-page performance | Degrades with offset depth |
| Concurrent writes | Rows can be skipped or duplicated as offsets shift |
| Index use | Weaker than an ID keyset |
| Completeness verification | Still requires a separate contract |

**Pros:** Familiar page-number model and simple test fixtures.

**Cons:** Repeatedly scans skipped rows and is unstable when rows are inserted
or removed during a multi-page fetch.

### Option C: ID keyset plus exact count only

| Dimension | Assessment |
|-----------|------------|
| Implementation effort | Medium |
| Deep-page performance | Stable and index-backed |
| Truncation detection | Strong |
| Same-count membership changes | Not detected |
| Cross-platform support | Simple |

**Pros:** Removes the server-cap failure and catches most partial reads.

**Cons:** A delete and insert can preserve the exact count while changing the
set, so count equality alone is not sufficient cutover evidence.

### Option D: ID keyset plus count and single-snapshot ID checksum

| Dimension | Assessment |
|-----------|------------|
| Implementation effort | Medium to high |
| Deep-page performance | Stable and index-backed |
| Membership verification | Strong, including same-count replacement |
| Security | RLS-preserving `SECURITY INVOKER` RPC |
| Client cost | One small integrity request and one hash per refresh/build |

**Pros:** Gives both clients an explicit completeness proof, separates indexed
transport order from presentation order, and fails safely under instability.

**Cons:** Adds a public RPC and a multiplatform hashing dependency, and it
verifies ID membership rather than a transactionally frozen copy of every
field.

### Option E: Server-side snapshot/export endpoint

| Dimension | Assessment |
|-----------|------------|
| Implementation effort | High |
| Snapshot consistency | Strongest; rows can share one database snapshot |
| Payload/streaming work | Requires a new delivery protocol |
| Client complexity | Lower pagination logic, higher download/cache logic |
| Suitability now | Premature for the current catalog and clients |

**Pros:** Can return a fully consistent publication generation and verify field
content as well as membership.

**Cons:** Introduces export storage or streaming, generation lifecycle,
caching, expiry, and replay concerns before current scale requires them.

## Trade-off Analysis

Option D is selected. Keyset pagination solves the performance and server-cap
problem, while the count and checksum turn completion into a testable contract
instead of an assumption. The extra RPC is small compared with the complete
catalog payload and runs only once per attempt.

The integrity snapshot proves that the received ID set matches the database at
the time of the RPC. It does not freeze all page reads into one transaction and
does not detect an in-place field update with the same ID. That limitation is
acceptable for this stage: a build or refresh may briefly show the prior value,
but it cannot silently omit an exhibition. If exact field-generation snapshots
become necessary, Option E should replace this contract rather than expanding
the checksum ad hoc.

## Consequences

- Catalogs larger than 1,000 rows are assembled completely before use.
- A later page, integrity endpoint, decoding, or validation failure produces an
  error or the existing development-only seed fallback, never a partial prefix.
- Web and mobile now share one stable presentation order; equal opening dates
  preserve the explicitly verified PostgreSQL `id ASC` transport order.
- Mobile treats malformed required dates as a catalog integrity failure instead
  of hiding the affected exhibition.
- Two small public-reader indexes and one aggregate RPC become part of the
  schema contract and must be recreated or repointed during the canonical
  public-projection swap.
- Database migrations must be deployed before clients that require the
  integrity RPC. A reversed rollout order intentionally fails closed.
- Pagination does not impose a new character-set constraint on permanent legacy
  IDs or depend on JavaScript/Kotlin ordering matching PostgreSQL collation.
- The checksum verifies membership, not field freshness. A future publication
  generation or snapshot export remains available if stronger semantics are
  needed.

## Rollback Boundary

The change is additive in the database. The old `public.exhibitions` table and
its read policy remain in place. Before production activation, rollback is
simply not deploying the new clients.

After activation, restore the prior web build and mobile release if the new
reader fails unexpectedly. Keep the integrity function and indexes during the
rollback; they do not change rows or broaden table privileges. Do not remove
them until old and new client versions are outside the supported rollback
window.

## Action Items

1. [x] Add indexed, duplicate-safe eager keyset pagination to web, mobile
   catalog, mobile featured, and mobile event-scoped reads.
2. [x] Add first-page exact-count validation to the website build.
3. [x] Add the RLS-preserving count and ID-checksum integrity function.
4. [x] Restore the missing tracked legacy `ticket_url` column.
5. [x] Test 999, 1,000, 1,001, 1,205, server-shortened pages, duplicates,
   invalid DTOs, same-count membership changes, and later-page failures.
6. [x] Rehearse the actual PostgREST row cap locally with 1,205 uniquely
   prefixed rows; verify four data requests, the terminal empty page, exact
   count, and ID checksum, then remove the fixtures and restore the baseline.
7. [ ] Deploy database migrations to staging before the web/mobile builds and
   verify all three filtered collection shapes.
8. [x] Add and re-review the separate V2 integrity function for the canonical
   projection while retaining this legacy function unchanged for rollback.
9. [ ] Activate production readers only within the controlled cutover and
   rollback window defined by ADR-0001.
