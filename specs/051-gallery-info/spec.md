# Feature Specification: Gallery Info

**Feature Branch**: `051-gallery-info`
**Created**: 2026-08-05
**Status**: In progress

## Product boundary

Gallery Info is the canonical organization-and-venue profile maintained in
`gallery.gallrmap.com`. The gallery identity remains `content.galleries`; reusable venue defaults
remain `content.venues`; exhibition versions remain independent snapshots. No read-time join or
later Gallery Info edit may rewrite an existing draft, submitted review snapshot, or published
version.

## User stories

### US1 — Maintain canonical gallery information (P1)

An eligible owner can open a first-class `Gallery Info` tab, review the current bilingual identity
and venue defaults, and save an allowlisted revision without receiving direct table privileges.

**Acceptance criteria**

1. The form includes bilingual gallery/venue name, bilingual city and region, bilingual address,
   latitude/longitude, default opening hours, and contact information.
2. An active gallery owner may read and edit only their gallery.
3. A pending owner may edit only when the gallery is pending, was created by that same user through
   the new-gallery claim path, and still belongs to that pending membership.
4. A pending claimant for an existing active gallery cannot read or mutate Gallery Info.
5. Saves require the current optimistic revision, accept only documented fields, increment the
   revision once, and emit a tenant-scoped audit record without logging contact values.
6. Browser roles receive no generic insert/update/delete privilege on galleries or venues.

### US2 — Select a server-geocoded address (P1)

An eligible owner searches a Korean address, reviews a bounded candidate list, explicitly selects
one result, and saves the provider-normalized Korean/English address, city/region, and WGS-84
coordinates.

**Acceptance criteria**

1. Gallery uses the existing authenticated `geocode-address` Edge Function and NAVER server-side
   provider integration; provider credentials never enter the browser bundle or response.
2. At most three bounded, validated candidates are returned and no candidate is applied until its
   explicit `Use this address` action is activated.
3. Editing the search query after selection clears the pending selection so stale coordinates
   cannot be saved for a different address.
4. The Edge authorization boundary admits existing active staff plus owners eligible under US1,
   and rejects all other authenticated and anonymous callers before provider access.
5. Distributed per-caller and per-project one-minute quotas remain atomic, shared across Edge
   instances, and fail closed when authorization or quota storage is unavailable.
6. Provider/network/configuration failures are sanitized, structured, non-cacheable, and do not
   leak credentials or raw provider payloads.

### US3 — Seed an independent exhibition snapshot (P1)

When an owner creates an exhibition, every matching Gallery Info venue field is copied into the new
draft once, after which the exhibition remains independently editable.

**Acceptance criteria**

1. New drafts copy venue name, city, region, address, coordinates, default hours, and default
   contact from the canonical venue in the same database transaction.
2. The created exhibition version retains its canonical `venue_id` reference but owns independent
   snapshot columns and revision state.
3. Saving Gallery Info never updates any `content.exhibition_versions` row or review payload.
4. Saving an exhibition never updates Gallery Info.
5. Existing drafts, submitted snapshots, and published snapshots remain byte-for-byte unchanged
   when Gallery Info is later edited.

## Functional requirements

- **FR-001** `content.galleries` MUST expose one positive aggregate revision used for both identity
  and canonical-venue changes.
- **FR-002** Owner Gallery Info reads and writes MUST use authenticated RPC wrappers with private
  implementations that independently resolve `auth.uid()` and authorize tenant access.
- **FR-003** Save validation MUST reject non-object patches, unknown keys, invalid types, blank or
  oversized Korean names, oversized text, incomplete/invalid coordinates, and stale revisions.
- **FR-004** The save transaction MUST create a canonical venue when absent or update the linked
  venue when present, then update gallery identity/revision and audit evidence atomically.
- **FR-005** The geocoder MUST keep `verify_jwt = true`, server-side credentials, bounded I/O,
  explicit timeouts, structured logs, and the current provider candidate limit.
- **FR-006** Staff geocoding MUST continue to work with the same contributor-or-higher access.
- **FR-007** Gallery Info navigation and form controls MUST follow `DESIGN.md`, use sharp geometry,
  minimum 44px targets, labelled inputs, live status/error semantics, keyboard focus, and usable
  desktop/mobile layouts.
- **FR-008** The feature MUST add no AI or AI-provider dependency.
- **FR-009** Release documentation MUST include migration, Edge, gallery, security, rollback, and
  visual verification steps without deploying or changing credentials.

## Non-goals

- Multiple branches or multiple canonical venues per gallery.
- Owner edits to published exhibitions, staff curation, or claim approval behavior.
- Browser-side NAVER SDK geocoding, manual coordinate entry, maps, address autocomplete, or AI.
- Deployment, DNS changes, credential creation/rotation, staging mutation, commits, or staging.

## Success and quality criteria

- Clean database replay, all pgTAP tests, migration-lineage validation, and database lint pass.
- Edge tests prove staff continuity, owner eligibility, bounded candidates, atomic quotas, fail-closed
  behavior, and sanitized errors.
- Gallery repository/component/navigation tests prove parsing, revisions, selection semantics,
  snapshot creation, tenant-safe failures, accessibility, and responsive rendering.
- Gallery tests, typecheck, and production build pass; desktop and mobile browser checks show no
  overlay, relevant console errors, clipping, overlap, or inaccessible target-flow control.
