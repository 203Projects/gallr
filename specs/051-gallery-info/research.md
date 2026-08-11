# Research: Gallery Info

## Existing owner and venue model

`content.galleries` is the durable organization and optionally points to one
`content.venues` row. The venue already owns bilingual name, city, region, address,
WGS-84 coordinates, default hours, and default contact. Browser DML is revoked on
both tables; owner operations follow public SECURITY INVOKER wrappers backed by
independently authorizing `content_private` implementations.

Decision: preserve this split and treat gallery identity plus its canonical venue as
one revisioned Gallery Info aggregate. Do not add a second profile table.

## Pending-owner distinction

The general owner draft assertion admits pending memberships for either a new gallery
or an existing-gallery claim. Gallery Info needs a narrower boundary. A pending caller
is eligible only when membership status, gallery status, gallery creator, and membership
creator all prove the caller created that still-pending organization. Existing claims
target active galleries and therefore fail this predicate.

Decision: add a dedicated Gallery Info access assertion; do not reuse
`owner_assert_gallery_membership(false)`.

## Exhibition snapshot behavior

`owner_create_exhibition_draft_impl` already copies the full canonical venue into a new
`content.exhibition_versions` row, including coordinates, hours, and contact. Existing
versions are never derived from the venue at read time.

Decision: retain the create-time copy and add regression coverage. Extend the owner
exhibition DTO/save allowlist for latitude and longitude so the copied coordinates are
visible and independently editable.

## Geocoding

Admin already invokes `geocode-address`, which validates an authenticated staff caller,
consumes atomic per-staff and per-project quotas, calls NAVER with server-held secrets,
and returns at most three normalized candidates. The provider, parsing, timeout, and
sanitization logic meet the Gallery Info requirement; only caller authorization and quota
scope are staff-specific.

Decision: add generic geocode-caller RPCs that admit current staff or a Gallery
Info-eligible owner. Preserve the legacy Admin quota RPC for compatibility. Use the same
private counter table and project advisory lock, adding an `owner` scope with the same
10-per-caller limit. The Edge Function switches to the generic RPCs and therefore shares
one project ceiling across staff and owners.

## Frontend

The Gallery SPA uses state-driven workspaces and a fixed desktop rail/mobile header.
Repository responses are parsed strictly and all canonical changes use RPCs.

Decision: add `GalleryInfoWorkspace`, typed repository methods, and a geocoding adapter
that mirrors Admin's bounded parser. Address/locality/coordinates are read-only selected
candidate outputs; names, hours, and contact are ordinary controlled inputs.

## Current Supabase guidance reviewed

- RLS remains enabled on exposed tables, while function-scoped grants and empty search
  paths prevent generic browser access.
- SECURITY DEFINER code remains in the non-exposed private schema and checks `auth.uid()`.
- Provider credentials remain Edge Function secrets and never enter public clients.
- `verify_jwt = true` remains enabled for the user-authenticated Edge Function.
- The April 2026 Data API auto-exposure change does not require table grants because this
  feature deliberately exposes functions, not canonical tables.
