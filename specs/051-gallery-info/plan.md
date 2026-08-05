# Implementation Plan: Gallery Info

## Technical approach

Treat the gallery plus its optional canonical venue as one owner-editable aggregate. Add a positive
revision to `content.galleries`, then expose `owner_get_gallery_info()` and
`owner_save_gallery_info(expected_revision, patch)` through the established security-invoker/public
wrapper plus independently authorizing private implementation pattern. The private save validates a
strict JSON allowlist, upserts only the caller's canonical venue, increments the gallery revision,
and appends one audit row in the same transaction.

Extend the existing `geocode-address` Edge Function rather than create a second provider path. A new
database-backed geocode caller resolver recognizes active staff or Gallery Info-eligible owners. Its
quota function consumes the existing project counter plus a caller-type counter atomically. The
provider integration, three-candidate bound, timeouts, response validation, and server-held NAVER
credentials remain unchanged.

Add a typed Gallery Info repository slice and geocoding service to the owner SPA, a focused
`GalleryInfoWorkspace`, and first-class desktop/mobile navigation. Address fields and coordinates
are populated only by an explicitly selected result. Existing draft creation already copies the
canonical venue; pgTAP will expand that contract to every requested field and prove subsequent
Gallery Info saves do not mutate old snapshots.

## Constitution check — before implementation

- **I Spec-first**: PASS — this specification, plan, and tasks precede tests and code.
- **II Test-first**: PASS — pgTAP, Edge, repository, and component tests will be added and observed
  failing before their implementations.
- **III Simplicity/YAGNI**: PASS — one aggregate revision, two owner RPCs, one reused Edge Function,
  and one workspace cover the stated requirement; no branch/team/map/AI subsystem is added.
- **IV Incremental delivery**: PASS — database authorization/snapshot behavior, Edge access, and UI
  selection/save behavior each have independent executable contracts.
- **V Observability**: PASS — saves and geocode outcomes emit structured audit/log evidence while
  excluding contact values, queries, credentials, and raw provider bodies.
- **VI Shared-first**: PASS — no KMP/mobile business logic changes; the standalone Gallery SPA and
  Supabase backend remain separate product surfaces under the constitution's web exception.

## Data and API design

- `content.galleries.revision`: positive integer aggregate revision, default 1.
- `content_private.owner_assert_gallery_info_access()`: returns caller/gallery only for an active
  owner or personally-created brand-new pending gallery.
- `owner_get_gallery_info()`: returns allowlisted identity, venue, coordinate, contact, revision,
  and update timestamp fields.
- `owner_save_gallery_info(p_expected_revision, p_patch)`: strict allowlist, atomic venue/gallery
  update, optimistic conflict, and audit record.
- `geocode_current_caller()`: safe caller identity for active staff or Gallery Info-eligible owner.
- `geocode_consume_rate_limit()`: existing fixed-window project ceiling plus per-staff/per-owner
  caller ceiling, with consistent advisory-lock order.
- `geocode-address`: authorizes through those generic geocode RPCs and preserves its current
  provider contract.

## UI and accessibility design

- Add `Gallery Info` beside `Exhibitions` in the fixed desktop rail and as an always-available mobile
  workspace control; keep Launch Kit conditional.
- Use one wide form with identity, address search/results, selected address summary, and venue
  defaults. All fields use existing monochrome/sharp form patterns and 8pt spacing.
- Korean/English address, locality, and coordinates are read-only outputs from a selected candidate;
  owners may edit names, hours, and contact directly.
- Candidate results are a semantic list with address-specific button names; search/result/save
  status uses `role=status`/`aria-live`, and failures use the existing monochrome `role=alert` style.
- Desktop uses a two-column identity/details layout where space allows; mobile collapses to one
  column without sticky controls or horizontal scrolling.

## Verification strategy

1. Run migration-lineage validation before generating a migration with the local Supabase CLI.
2. Add focused pgTAP tests and observe them fail on missing revision/RPCs and current staff-only
   geocode access.
3. Add Edge tests and observe owner authorization/quota paths fail before implementation.
4. Add Gallery repository/component/navigation tests and observe missing API/UI failures.
5. Implement database, Edge, repository, component, navigation, and styles in that order.
6. Run clean `supabase db reset`, all pgTAP, DB lint, all Edge Deno tests, Gallery tests/typecheck/build,
   and product-surface config checks.
7. Use the in-app Browser workflow to exercise active-owner load, address search/selection/save,
   exhibition creation, and desktop/mobile layouts; record console/DOM/screenshot evidence.
8. Update the gallery owner release runbook with apply/rollback and verification gates. Do not
   deploy, stage, commit, or change credentials.

## Complexity tracking

No constitution violation is planned.

## Constitution check — after design

PASS. The design adds no direct browser DML, no cross-tenant reader, no published-snapshot coupling,
no provider credential exposure, and no KMP platform logic.
