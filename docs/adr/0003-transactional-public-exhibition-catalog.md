# ADR-0003: Serve published exhibitions from a transactional public read model

**Status:** Accepted
**Date:** 2026-07-21
**Deciders:** gallr engineering; staging and production activation still require
the content, mobile, web, and backend owners

## Context

The versioned CMS now owns exhibition identity, drafts, published snapshots,
media, curation, audit history, and migration provenance in private PostgreSQL
schemas. Anonymous web and mobile readers still target the Sheet-fed
`public.exhibitions` table. A service-only `public.exhibitions_v2_preview` proves
that canonical records can reproduce the legacy DTO, but it is not a safe public
endpoint: as a security-invoker view it would require anonymous privileges on
the private source tables.

The public cutover needs a contract that:

- exposes only unarchived published content and never grants access to drafts;
- preserves permanent IDs, legacy field names, nullability, media fallback,
  event/editor associations, curation flags, and indexed ID-keyset pagination;
- changes in the same database transaction as publish, archive, restore, legacy
  import, curation, and any privileged cover maintenance;
- detects both collection-membership changes and in-place field changes while a
  reader drains several PostgREST pages;
- lets web and mobile select the old or new source as one closed resource/RPC
  pair, without an automatic cross-source fallback;
- leaves the legacy table, Sheet artifacts, and existing integrity RPC intact
  until the rollback window is explicitly closed, while keeping installed
  legacy-reading clients fresh after editorial ownership moves to canonical.

## Decision

We will materialize the anonymous contract in a dedicated flat table named
`public.exhibition_catalog_v2`.

1. The table contains only the public business fields already produced by the
   compatibility preview, plus a database-derived
   `content_checksum_sha256`. It has no draft IDs, staff IDs, audit metadata, or
   write API.
2. `content_private.refresh_exhibition_catalog_v2(text)` is the only
   normal writer. It deletes a row when the canonical identity is archived,
   unpublished, missing, or points at a non-published version; otherwise it
   upserts the complete flattened published snapshot.
3. Narrow source-table triggers invoke that idempotent projector after changes
   to publication identity, published-version content, curation, current cover
   attachments, or current cover assets. This also covers service-role
   maintenance that could bypass application command functions. Intermediate
   trigger results during a multi-statement publish remain invisible under MVCC;
   only the final committed projection is public, and any projection failure
   aborts the source transaction.
4. The migration backfills every existing canonical identity before granting
   reader access. A service-role-only reconciliation RPC compares the expected
   canonical preview with the materialized rows and reports missing, unexpected,
   or mismatched IDs.
5. Anonymous and authenticated roles receive `SELECT` only, behind an explicit
   RLS read policy. `PUBLIC` receives no table or function privilege. The private
   schemas remain outside the Data API and receive no anonymous grants.
6. The public table is constrained at its boundary: nonblank permanent and
   Korean identity fields, required ordered dates, paired/range-checked
   coordinates, derived editor compatibility aliases, and lowercase SHA-256.
   It has the ID primary key, `(event_id, id)` partial index, featured-ID partial
   index, and homepage `(closing_date, id)` partial index.
7. `updated_at` comes from `legacy_source_updated_at` for an imported snapshot or
   the published version's `updated_at` for CMS-native content. It deliberately
   excludes `content.exhibitions.updated_at`, because saving a private draft
   updates the identity and must not alter public cache metadata or reveal draft
   activity.
8. Curation remains compatibility-oriented in this cutover: placement `enabled`
   overrides the published version flag. Position and start/end windows are not
   activated implicitly; scheduled curation requires a later decision and a
   boundary-refresh scheduler.
9. A private `BEFORE INSERT OR UPDATE` trigger overwrites each row's content hash
   from every other public business field. Callers do not reproduce PostgreSQL
   serialization for timestamps or floating-point values.
10. `public.exhibition_catalog_v2_integrity(text, boolean)` remains
    `STABLE`, `SECURITY INVOKER`, and pinned to an empty `search_path`. It returns
    row count, the ADR-0002 ID checksum, and a catalog checksum over each
    database-ordered `(id, content_checksum_sha256)` pair. Both values are
    UTF-8-byte-length framed before hashing.
11. Reader configuration is a closed choice:

    | Flag | Resource | Integrity RPC | Verification |
    | --- | --- | --- | --- |
    | `legacy` | `exhibitions` | `exhibition_reader_integrity` | count + ID membership |
    | `canonical-v2` | `exhibition_catalog_v2` | `exhibition_catalog_v2_integrity` | count + ID membership + field content |

    Unset configuration means `legacy`; any other value is invalid. All catalog,
    featured, event-scoped, showcase, and seed-refresh reads resolve the same
    source. A canonical request never silently falls back to legacy after a
    network, decoding, or integrity failure.
12. The website source is a build environment flag. Android and iOS receive the
    same source at application composition time. Installed mobile binaries
    cannot be remotely rolled back by a build-time flag, so the legacy endpoint
    remains supported until the minimum supported app version uses V2 or a
    separately designed server-controlled kill switch exists.
13. A disabled-by-default compatibility bridge preserves that support after the
    Sheet writer is retired. Its two runtime booleans define exactly three valid
    operating states:

    | State | `legacy_mirror_enabled` | `legacy_writes_blocked` | Writer |
    | --- | --- | --- | --- |
    | Sheet-owned | `false` | `false` | Sheet/Apps Script before transfer |
    | Canonical-owned | `true` | `true` | Canonical command path only |
    | Frozen | `false` | `true` | None |

    The service-only
    `admin_enable_legacy_exhibition_mirror(bigint, text, text, text)` command
    requires the reviewed V2 row count, ID hash, catalog hash, and a nonblank
    reason. In one locked transaction it requires canonical/V2 reconciliation
    and exact legacy/V2 ID, field, and checksum parity, records the baseline and
    audit reason, enables canonical-to-legacy projection, and revokes
    `service_role` insert, update, delete, and truncate on
    `public.exhibitions`. A table ownership guard rejects direct and already-
    queued legacy writes. `admin_disable_legacy_exhibition_mirror(text)` is an
    idempotent freeze operation: it requires a reason, stops mirroring, leaves
    the ownership guard active, and revokes the same DML privileges again rather
    than restoring them or restarting Apps Script. Both transitions append
    operational evidence through owned command functions; caller roles cannot
    directly insert, update, delete, or truncate `content.audit_log`. This is an
    operational append-only boundary, not cryptographic immutability against the
    PostgreSQL owner or infrastructure administrator.

## Options Considered

### Option A: Grant anonymous access to the canonical compatibility view

This avoids materialized data, but the security-invoker view requires privileges
on private canonical tables. Adding public RLS to a graph that includes versions,
media, and curation couples anonymous security and query performance directly to
the editorial model. A security-definer view would hide the grants but create a
larger privilege-bypass surface. Both options make DTO changes depend on private
schema evolution.

### Option B: Maintain a dedicated public read model asynchronously

An outbox worker could build the same flat table, but publication would become
eventually consistent. The admin could report success while visitors still see
old content, and every worker outage would need replay, lag monitoring, and
ordering rules before basic publication was trustworthy.

### Option C: Maintain a dedicated public read model transactionally

This adds one representation and projection logic, but creates the narrowest
anonymous privilege boundary and the simplest indexed query plan. Database
triggers cover command, import, foreign-key, and privileged maintenance paths in
the same transaction, eliminating projection lag. Reconciliation makes drift an
observable invariant rather than an assumption.

### Option D: Rename or replace `public.exhibitions` in place

This gives the shortest client path but combines schema deployment, reader
activation, and rollback into one destructive event. Older mobile binaries and
the Sheet writer would compete for the same name, and a failed cutover could not
be reversed by configuration alone.

## Trade-off Analysis

Option C is selected. The extra table and trigger coverage are justified by the
clear security boundary, stable DTO, predictable PostgREST indexes, and atomic
visitor visibility. The projector is intentionally idempotent and the expected
catalog is independently queryable, so a complete rebuild and drift report are
available without replaying editorial commands.

The V2 catalog checksum strengthens ADR-0002 for this new endpoint. ADR-0002's
legacy ID-only RPC remains unchanged for rollback compatibility. V2 readers hash
the row checksums returned with each page and compare that aggregate with one
final database snapshot; a concurrent in-place edit therefore discards and
retries the whole attempt just like a membership change.

Transactional triggers add write latency and row locks. The catalog is bounded,
each ordinary command affects one exhibition, refresh is a single-row upsert or
delete, and shared-media refreshes lock affected exhibition IDs in deterministic
order. This is preferable to exposing successful publication before its public
representation exists.

## Consequences

- Draft creation and autosave never add or modify a public catalog row.
- Publish/import, archive, restore, curation, and privileged cover changes become
  publicly visible atomically with their canonical source transaction.
- Anonymous queries no longer join or hold privileges on private editorial
  tables.
- A projection constraint or refresh error fails publication instead of leaving
  canonical and public state divergent.
- Public reads remain flat and use the same ID-keyset/filter shapes already
  verified beyond the PostgREST 1,000-row cap.
- V2 detects mixed field generations across pages without asking JavaScript or
  Kotlin to serialize database types.
- Events and editors remain legacy reference endpoints. This decision retires
  the exhibition Sheet path only; it does not by itself retire their Sheets.
- Curation scheduling and gallery-media delivery remain future public-contract
  work rather than hidden behavior changes.
- The old and new public models coexist temporarily, so operators must monitor
  reconciliation and keep one declared source of truth during cutover.
- Before final transfer the Sheet owns the legacy table and the compatibility
  bridge is in Sheet-owned (`false`/`false`) state. After exact-parity activation,
  canonical-owned (`true`/`true`) publication updates
  both public catalogs transactionally, keeping installed legacy-reading clients
  and the reader rollback path current without retaining a second writer.
- Reader rollback leaves the mirror enabled. Editorial rollback moves it to
  frozen (`false`/`true`) state; the write guard remains active and DML is
  re-revoked idempotently. Returning ownership to the Sheet requires separate
  approval, reconciliation of post-export canonical commands, and a separately
  implemented parity-gated owner operation that atomically clears
  `legacy_writes_blocked`, restores exact DML grants, and appends an audit event.
  This recovery operation is not implemented by the V2 migration; a privilege
  regrant alone is insufficient and forbidden.
- Event and editor catalogs remain in coexistence. Their rows must not be
  hard-deleted during this window; use inactive state until their writers and
  foreign-key behavior are migrated separately.

## Rollback Boundary

Database deployment is additive. Applying the V2 migration does not change the
default reader, rename the legacy table, disable Apps Script, or mutate
production in this implementation step.

Before activation, rollback is simply leaving `GALLR_EXHIBITION_SOURCE` and the
mobile build source on `legacy`. A V2 canary while the Sheet is still writable
is observation-only: it validates the reader path but cannot prove final content
equivalence until the quiet-window delta and activation checks pass.

After mirror activation, a reader rollback sets the web/build source back to
`legacy` and ships the prior mobile configuration if needed while leaving the
mirror enabled. Canonical publishing then continues to keep that endpoint and
installed legacy-reading clients fresh. Do not auto-fallback within one running
client because that can mix catalog, featured, and event collections from
different sources.

An editorial-ownership rollback is different: pause canonical commands and call
`admin_disable_legacy_exhibition_mirror(text)` with a nonblank incident reason.
That freezes projection but leaves legacy DML revoked and Apps Script disabled.
It also leaves the ownership guard active and re-revokes legacy DML
idempotently, so an earlier privilege regrant or a transaction queued behind the
freeze lock cannot become a second writer after commit. The freeze transition
appends an audit event that caller roles cannot update, delete, or truncate.
Resuming the Sheet requires separately approved reconciliation of every
post-export canonical command, followed by a separately implemented,
parity-gated, audited owner operation that atomically returns the runtime to
Sheet-owned (`false`/`false`) and restores exact DML grants. A regrant alone
leaves the guard active. Only after that operation commits may the Sheet writer
be activated; resumption is never an automatic consequence of disabling the
mirror.

Keep `public.exhibitions`, `public.exhibition_reader_integrity`, the final Sheet
export, Apps Script source, and credentials throughout the agreed window. Close
the rollback boundary only after a complete editorial cycle has zero unexplained
projection drift, verified filtered checksums and pagination, successful rebuilds,
acceptable latency, and supported mobile versions on V2. Only then may a separate
destructive migration remove the legacy exhibition table and Sheet writer.

## Action Items

1. [x] Create the transactional published-only table, projector, triggers,
   backfill, grants, indexes, V2 integrity RPC, and service reconciliation RPC.
2. [x] Add a strict legacy/V2 source pair to web and mobile readers while keeping
   `legacy` as the default.
3. [x] Add database and client tests for publication lifecycle, security,
   checksum integrity, backfill, filtered pagination, rollback selection,
   append-only bridge audit evidence, and queued legacy-writer rejection.
4. [ ] Rehearse the migration and final legacy import on an isolated staging
   clone; retain reconciliation output plus measured migration wall-clock and
   write-blocking lock duration as cutover evidence.
5. [ ] Deploy the database before any canonical reader build and verify all,
   featured, event, homepage, empty, and over-1,000-row paths.
6. [ ] Run a web canary, then release mobile builds with an explicitly approved
   source value and rollback plan.
7. [ ] Freeze Sheet writes, apply the final delta, disable Apps Script, and move
   editorial ownership exclusively to the admin only after exact-parity mirror
   activation succeeds with the recorded count, ID hash, catalog hash, and
   reason.
8. [ ] Remove the legacy table, RPC, Sheet credentials, and Apps Script only in a
   later approved retirement migration after the rollback window closes.
