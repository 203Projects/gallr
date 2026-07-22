# gallr admin

The admin application is the editorial replacement for the exhibition Google
Sheet. It provides an invite-only staff login, exhibition list/search/status
filters, draft creation, bilingual autosave, immutable published versions,
preview, publish, archive, and restore behind a typed repository boundary.
Its Media workspace adds direct signed uploads, cover replacement, ordered
galleries, version-scoped alt/credit/rights metadata, processing status, and
safe removal without giving the browser canonical table access. The details
workspace also edits paired coordinates, an exhibition-specific ticket URL,
and optional event/editor associations without returning to the Sheet.

The adapter is selected by configuration:

- With `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY`, the app requires
  Supabase Auth plus an active `content.staff_members` role and uses the
  staff-only RPC adapter.
- Without those variables, it uses deterministic in-memory fixtures for tests
  and visual review. Fixture mode is not a production persistence path.

## Run locally

Requirements: Node.js 22 or newer.

```bash
cd admin
npm install
npm run dev
```

Vite serves the app at `http://127.0.0.1:5173` by default.

## Verify

```bash
cd admin
npm run typecheck
npm test
npm run build
```

## Environment

Copy `.env.example` to `.env.local` to enable the Supabase adapter:

```text
VITE_SUPABASE_URL=https://your-project.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=your-publishable-key
```

Only the publishable browser key belongs in the admin client. Never put a
service-role or secret key in a `VITE_` variable.

For local development without Supabase, an optional NAVER Maps JavaScript
geocoder can search real addresses with the application's public,
referrer-restricted browser client ID:

```text
VITE_NAVER_MAPS_CLIENT_ID=your-public-browser-client-id
```

This development-only adapter loads NAVER's official `geocoder` submodule on
the first search. Enable **Web Dynamic Map** and **Geocoding** for that NAVER
application and register `http://127.0.0.1:5173` as a Web service URL. The
public browser ID may be exposed; the NAVER client secret must never use a
`VITE_` variable. Production builds do not select this adapter.

The bundled Inter and Gothic A1 font files retain their original SIL Open Font
License terms. Copyright notices and the complete license are in
[`public/fonts/ATTRIBUTION.md`](public/fonts/ATTRIBUTION.md).

Production address lookup will require the protected `geocode-address` Edge
Function and its server-only NAVER credentials. It is implemented locally but
is not deployed by this branch. Setup, deployment, and the request contract are
documented in
[`../supabase/functions/geocode-address/README.md`](../supabase/functions/geocode-address/README.md).
When neither live adapter is configured, the UI visibly enters Fixture mode;
use `서울 용산구 한남대로 28` for its deterministic candidate flow.

## Repository contract

`AdminExhibitionRepository` is the seam between the UI and persistence. Its
Supabase implementation maps operations to narrowly scoped database functions:

- `list` → staff-only exhibition query
- `getExhibitionLookups` → one staff-only query returning both event and editor
  choices, including inactive rows so an existing historical assignment remains
  visible
- `createDraft` → transaction that creates a permanent identity and draft v1
- `saveDraft` → update guarded by exact working-version ID and revision; editing
  a published record first creates/reuses an isolated draft
- `publish` → publisher-only transaction that validates and advances the
  published pointer; ambiguous retries retain the same request UUID
- `archive` / `restore` → publisher-only, reversible, idempotent lifecycle
  commands
- `listMedia` → version-scoped media DTO plus short-lived private previews
- `uploadAndAttachMedia` → reserve an immutable path, create/use a signed upload
  token, finalize the Storage object, and attach it to the exact draft revision
- media metadata/reorder/detach → narrow revision-checked commands that return
  the updated exhibition and ordered media bundle

## Editable details and associations

Incomplete drafts may leave the map location blank, but publication requires a
nonblank Korean address plus latitude and longitude. Latitude must be between
-90 and 90 and longitude between -180 and 180. Controlled form blanks cross
the save boundary as database `NULL`, not empty coordinate strings. Changing
the Korean address clears both coordinates so a stale pin cannot be published
for a different address.

**Find coordinates** sends the Korean address to the authenticated
`geocode-address` Edge Function. The function verifies active staff access,
calls NAVER Cloud Geocoding with server-held credentials, and returns at most
three candidates. The editor must choose a result before the address and WGS-84
coordinate pair enter the normal revision-guarded autosave. Manual paired
coordinate correction remains available for gallery entrances. English address
copy stays optional; choosing a result replaces it with the provider's English
address so the Korean address and selected coordinates remain one coherent
location result.

`ticketUrl` belongs to the exhibition. When present it must be an absolute
`http://` or `https://` URL; leaving it blank stores `NULL`. The editor and
compatibility projection do not fall back to the associated event's ticket URL.
If a client wants an event-level ticket link, it must fetch and present that
event value explicitly.

The event and editor selectors use the single
`admin_get_exhibition_lookups()` staff-only RPC. Inactive choices remain in the
response and are labelled in the form so historical associations can be kept or
removed deliberately. A details autosave sends scalar edits and association
changes in one revision-guarded patch; one accepted autosave increments the
working revision once. This slice does not change media upload, attachment, or
publication behavior.

### Operator workflow

1. Add or edit the event/editor in its owning workflow, then select its stable
   ID on the exhibition. Save both coordinates together and use the
   exhibition's own ticket URL when it has one.
2. To remove an exhibition association, choose **No linked event** or **No
   editor attribution**. This clears only that draft's foreign key after the
   next autosave; it does not delete the referenced row.
3. To retire an event or editor globally, set it inactive. Do not hard-delete
   reference rows: inactive records remain selectable for historical context.
4. Until the event Sheet is retired, treat removing an event row from that
   Sheet as destructive. The legacy sync can delete the database row and the
   current `ON DELETE SET NULL` foreign key can erase linked version
   associations. Move these foreign keys to `ON DELETE RESTRICT` only after the
   event Sheet/Apps Script writer is disabled and reconciled.

The browser accepts JPEG, PNG, and WebP up to 10 MiB, but it is not the trust
boundary. The server-side outbox worker fully validates and decodes the bytes,
copies them to an immutable public path, and changes the asset from `ready` to
`published`. The editor polls while processing and blocks exhibition publication
until every attached image is published. Generic exhibition JSON never accepts
cover URLs, alt text, or credit.

Worker configuration and editor/operator procedures are documented in
`../docs/admin-media-and-outbox-runbook.md`. The worker secret and Supabase
server credential must never appear in this app's environment or bundle.

Do not point the admin at production until every cutover gate in
`docs/exhibition-content-architecture.md` passes. In particular, migration
version `000` requires manual production-history reconciliation. Complete web
and mobile collection transport is now implemented and verified locally beyond
1,000 rows, including final count/checksum validation. This does not authorize a
production reader swap: the staged legacy import, public projection decision,
production outbox/rebuild path, editorial freeze, and rollback approvals remain
open gates.
