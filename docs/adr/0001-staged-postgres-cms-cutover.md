# ADR-0001: Stage legacy exhibitions before the PostgreSQL CMS cutover

**Status:** Accepted
**Date:** 2026-07-21
**Deciders:** gallr engineering and content operations; production reader cutover
requires explicit product and backend owner approval

## Context

Exhibitions are currently edited in Google Sheets and copied into the legacy
`public.exhibitions` table by Apps Script. That workflow has become difficult to
operate as the catalog grows, while shipped mobile and web clients still depend
on the legacy table's IDs, fields, nullability, and image URLs.

The replacement CMS uses `content.exhibitions` for permanent identity and
`content.exhibition_versions` for draft, published, and superseded snapshots.
The canonical schemas are private, and browser mutations pass through narrow,
role-checked commands. Backfill therefore has to preserve existing database IDs
and public behavior without turning a mutable Sheet row into a new identity,
silently dropping invalid source data, or exposing private editorial tables to
anonymous clients.

Migration `20260721075225_legacy_import_and_compatibility_preview.sql` adds an
additive Phase 4 boundary. It leaves `public.exhibitions` untouched, stages a
timestamped legacy database export, applies only a validated batch, and compares
the result with a service-only compatibility preview. The remaining public read
architecture and projection swap are controlled-cutover decisions, not implied
by creating the preview.

The following constraints shape the decision:

- Existing database IDs must remain authoritative for bookmarks, thoughts,
  links, and downstream caches.
- Invalid source rows must remain inspectable and must block apply rather than
  disappearing from the report.
- Replaying the same source snapshot must be idempotent.
- Distinct full snapshots must apply in strictly increasing source-snapshot
  order; a pending older batch must never roll canonical data back after a
  newer batch is applied.
- A later import must not overwrite an admin draft or a published version that
  changed after the prior import.
- Absence from an import snapshot is not proof that an exhibition should be
  archived.
- Legacy cover URLs must remain byte-for-byte compatible until media is
  deliberately migrated.
- Phase 4 must not change the anonymous Data API surface.

## Decision

We will use the versioned `content` model as the canonical exhibition database
and cross the legacy boundary through private, staged PostgreSQL imports.

1. Each source export becomes an immutable `content.legacy_import_batches`
   record, identified by source metadata and SHA-256 checksums. The submitted
   bundle row and its server-normalized copy are retained in
   `content.legacy_import_rows`, including invalid rows and structured issues;
   the byte-exact database and Sheet exports remain checksum-protected operator
   artifacts outside the database. `content.legacy_import_links` records the
   durable relationship between a legacy source ID and the canonical identity
   and version created from it.
2. IDs from the legacy `public.exhibitions` database export are authoritative.
   Sheet-derived hashes, mutable titles, venues, cities, or dates cannot create
   replacement identities during backfill. The import link requires
   `source_id = exhibition_id`.
3. Staging, apply, and reconciliation are explicit service-role-only commands:
   `migration_stage_legacy_exhibitions`,
   `migration_apply_legacy_exhibitions`, and
   `migration_reconcile_legacy_exhibitions`. Tables and implementation
   functions remain private with RLS enabled and no anonymous or authenticated
   grants.
4. Staging normalizes every row, reports validation errors and warnings, and
   plans `insert`, `revise`, `unchanged`, or `blocked`. Apply takes a transaction
   advisory lock, re-evaluates stateful conflicts, and rejects the entire batch
   if any error remains. It will not overwrite a canonical record with an
   active draft or a published pointer that changed since the previous import.
   It also rejects a source timestamp older than or equal to the latest applied
   full snapshot; operators must capture a fresh source snapshot and must never
   edit timestamps to bypass ordering.
5. Staging records the exact latest-applied batch as its review baseline. Stage
   and apply share one importer advisory lock. If another batch is applied after
   review, apply rejects the staged batch even when its source timestamp is
   newer; the operator must capture and review a fresh full snapshot. This keeps
   planned actions and missing-ID evidence from becoming stale.
6. Applying a changed legacy row creates a new published version and supersedes
   the prior imported version. An unchanged row preserves its version. Audit and
   provenance records remain available after the operation.
7. IDs missing from a later snapshot are reported as
   `missing_previously_imported_ids`; they are not archived, deleted, or hidden.
   Removal remains an explicit publisher action in the admin workflow.
8. A legacy cover remains `legacy_cover_image_url`. The compatibility preview
   prefers a valid published CMS cover attachment and otherwise returns the
   legacy URL. Phase 4 does not download or copy the object and does not create a
   fake `media_assets` row with unknown bytes, MIME type, dimensions, or rights.
9. `public.exhibitions_v2_preview` is a `security_invoker` view containing only
   unarchived identities with a published canonical version. It is selectable
   only by `service_role` during Phase 4. Its purpose is deterministic field and
   checksum reconciliation, not public delivery.
10. Anonymous access will move only during a controlled cutover after a separate
   decision chooses and validates either a direct RLS-backed projection or a
   dedicated public read model. That decision must define exact DTO shape,
   nullability, grants, RLS, ordering, pagination, rebuild behavior, monitoring,
   and rollback. Creating a service-only preview does not authorize an anonymous
   grant or a rename over `public.exhibitions`.

## Options Considered

### Option A: Continue direct Google Sheet sync

| Dimension | Assessment |
|-----------|------------|
| Initial complexity | Low |
| Operational risk | High; full-table replacement and deployment drift remain |
| Auditability | Low; skipped and transformed rows are primarily log output |
| Identity safety | Poor; IDs can be derived from mutable fields |
| Scalability | Poor; editorial and database concerns remain coupled to one Sheet |
| Rollback | Weak after a destructive or partial sync |

**Pros:** No migration effort, familiar editor workflow, and no immediate client
change.

**Cons:** Preserves the maintenance problem, leaves the Sheet as a database,
cannot provide version history or concurrency control, and keeps destructive
sync behavior in the publishing path.

### Option B: Run a one-shot SQL backfill and swap immediately

| Dimension | Assessment |
|-----------|------------|
| Initial complexity | Medium |
| Operational risk | High; validation, import, and reader change share one event |
| Auditability | Medium if logs are retained; poor for rejected source rows |
| Identity safety | Depends on ad hoc matching logic |
| Scalability | Good after success, but migration is difficult to rehearse |
| Rollback | Medium; requires undoing both data and reader changes |

**Pros:** Short migration window and little permanent import infrastructure.

**Cons:** Makes discrepancies visible too late, is hard to replay safely, tends
to discard invalid rows, and combines reversible backfill with a user-visible
cutover.

### Option C: Expose a direct RLS-backed view over canonical tables

| Dimension | Assessment |
|-----------|------------|
| Initial complexity | Medium |
| Runtime complexity | Medium to high; joins, media fallback, and policies run on reads |
| Contract isolation | Medium; public DTO remains coupled to canonical schema |
| Security | Sound only with an explicit underlying RLS and grant design |
| Consistency | Immediate; no read-model lag |
| Scalability | Requires query-plan, index, ordering, and pagination validation |

**Pros:** One source of truth, no projection-copy worker, and immediate publish
visibility.

**Cons:** A `security_invoker` view uses the caller's privileges on underlying
objects. Anonymous access cannot be added safely by granting around the private
model without a deliberate RLS design. Public query cost and schema coupling
also grow with each compatibility join.

### Option D: Maintain a dedicated public read model

| Dimension | Assessment |
|-----------|------------|
| Initial complexity | High |
| Runtime complexity | Low for clients; reads target a flat, indexed contract |
| Contract isolation | High; canonical and public schemas can evolve independently |
| Security | Clear; narrow public RLS and grants apply to the read model only |
| Consistency | Eventual unless updated in the publish transaction |
| Scalability | High with explicit indexes, ordering, and pagination |

**Pros:** Stable DTOs, simple anonymous authorization, predictable client
queries, and a clean place for legacy compatibility fields.

**Cons:** Adds projection ownership, consistency and replay concerns, monitoring,
and another representation that must be reconciled after every publish.

### Option E: Stage into canonical content and keep the preview service-only

| Dimension | Assessment |
|-----------|------------|
| Initial complexity | Medium to high |
| Operational risk | Low; import and reader cutover are separate gates |
| Auditability | High; raw, normalized, issue, action, link, and audit data remain |
| Identity safety | High; database IDs and import provenance are enforced |
| Scalability | Good for a bounded migration; batches are capped at 5,000 rows |
| Rollback | Strong before the public reader changes |

**Pros:** Supports deterministic rehearsal, exact reconciliation, idempotent
replay, conflict detection, and an additive Phase 4 rollout with no anonymous
behavior change.

**Cons:** Temporarily maintains legacy and canonical representations, requires a
trusted operator for service-only commands, and intentionally leaves the final
public read option unresolved.

## Trade-off Analysis

Option E is selected because it separates three risks that should not share one
deployment: source-data quality, canonical import correctness, and anonymous
reader behavior. The permanent staging/link records cost more than a one-shot
script, but they make every imported version attributable and make repeated
delta snapshots safe to evaluate.

The direct RLS view and dedicated read model remain valid cutover candidates.
The direct view minimizes duplication and publication lag, while the read model
provides the strongest security and contract boundary. The service-only preview
lets us measure exact field parity and query behavior before choosing between
them. No option may be granted to anonymous callers merely because its columns
look compatible.

Continuing the Sheet sync or using one-shot SQL optimizes for short-term effort
at the expense of identity integrity, auditability, and rollback. Those
trade-offs conflict with the reason for replacing the Sheet workflow.

## Consequences

- Backfill becomes repeatable, idempotent, and reviewable before any public
  reader changes.
- An older pending batch cannot be applied after a newer full snapshot, so
  retries cannot silently roll canonical versions backward.
- A reviewed batch cannot apply after its latest-applied baseline changes, so
  its planned actions and missing-ID report remain tied to the state reviewed by
  the operators.
- Invalid rows remain evidence in the staged batch and block apply; operators
  must correct the source or produce a new reviewed snapshot.
- Existing public IDs, timestamps, flags, relationships, nullable values, and
  cover URLs can be compared field by field against the compatibility preview.
- Admin and importer changes cannot silently overwrite each other; a draft or
  changed published pointer creates a conflict that must be resolved.
- Removing a row from an export no longer removes content. Editors must archive
  intentionally, which is safer but adds an explicit lifecycle step.
- Legacy media remains externally referenced until it is deliberately migrated.
  This avoids fabricated metadata but leaves URL availability and rights cleanup
  as later work.
- The legacy table, Apps Script, and canonical CMS coexist through the cutover
  window. Operators must follow a documented source-of-truth and freeze sequence
  to avoid divergent writes.
- Service-role import commands require tightly controlled credentials, an
  operator runbook, immutable source artifacts, and retained reconciliation
  output.
- A follow-up ADR must select the anonymous public read architecture; Phase 4
  does not complete Sheet retirement by itself.

## Rollback Boundary

Before the anonymous projection swap, rollback is operationally simple: stop
admin/import writes and continue serving the untouched legacy
`public.exhibitions` table. Staged and applied canonical records are additive and
can remain for diagnosis; no destructive reverse migration is required. The
service-only preview carries no client traffic, and legacy cover objects were not
copied or rewritten.

During controlled cutover, retain the legacy table, timestamped exports, Apps
Script source, and credentials for the agreed rollback window. If verification
fails after the reader changes, restore the legacy reader, pause canonical
publishing and imports, and reconcile any commands accepted during the window
before retrying. Do not infer deletes or archives from a failed or incomplete
snapshot.

The rollback boundary closes only after the new reader has completed an
editorial cycle with matching counts/checksums, acceptable latency, successful
web rebuilds, and verified mobile behavior, and after the owner explicitly
approves retiring the legacy pipeline.

## Remaining Blockers

1. Events are managed by a separate Google Sheet and Apps Script pipeline. Event
   identity, editing, media, migration, and public-read ownership need their own
   canonical workflow before that Sheet can be retired; exhibition imports must
   continue to validate references against the legacy `public.events` table in
   the meantime.
2. ADR-0003 now selects and implements a dedicated transactional public read
   model locally. Staging backfill, performance evidence, canary activation, and
   the rollback window remain incomplete; every shipped reader still defaults to
   the legacy table until those gates pass.
3. Production backfill/reconciliation, the downstream web rebuild receiver,
   outbox deployment/scheduling, the content freeze, and rollback approval are
   still operational cutover gates.

## Action Items

1. [x] Add private immutable import batches, rows, provenance links, validation,
   apply, and reconciliation commands.
2. [x] Add a service-only `security_invoker` compatibility preview with legacy
   cover fallback and no anonymous grants.
3. [ ] Produce timestamped Sheet and legacy database exports and retain their
   checksums as immutable migration evidence.
4. [ ] Run dry-run, apply, and reconciliation against a staging clone; resolve
   every blocked row and unexplained field difference.
5. [x] Add the missing admin fields and tests for coordinates, event/editor
   associations, and ticket URLs.
6. [ ] Decide and document the events migration and Sheet-retirement path.
7. [x] Implement and verify web/mobile pagination beyond 1,000 exhibitions with
   exact count and single-snapshot ID-checksum validation.
8. [x] Write ADR-0003 and implement the dedicated read model, grants, policies,
   transaction triggers, content integrity, strict reader flags, and rollback
   runbook locally.
9. [ ] Execute the production freeze, final delta import, reader swap, monitoring,
   and rollback-window closure only after all cutover gates pass.
