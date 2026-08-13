# gallr admin

The admin application is the editorial replacement for the exhibition Google
Sheet. It provides an invite-only staff login, exhibition search plus publish-state,
date-state, homepage-placement, and chronological list controls, draft creation,
bilingual autosave, immutable published versions,
preview, publish, archive, and restore behind a typed repository boundary.
Its Media workspace adds direct signed uploads, cover replacement, ordered
galleries, version-scoped alt/credit/rights metadata, processing status, and
safe removal without giving the browser canonical table access. The details
workspace also edits paired coordinates, an exhibition-specific ticket URL,
and optional event/editor associations without returning to the Sheet.

Invited external editors use the same authentication screen but enter a
separate **My curation** workspace. An active `content.editor_memberships` row
links one Auth user to one `public.editors` identity. The editor can stage
curation changes, edit the collection's bilingual curatorial statement,
propose their own personal bio, and suggest a missing exhibition; the browser
never loads the staff repository or navigation. Admin review remains the
boundary for every public change.

The adapter is selected by configuration:

- With `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY`, the app requires
  Supabase Auth plus either an active `content.staff_members` role or an active
  `content.editor_memberships` link. Each access type uses its own RPC adapter.
- Without those variables, the app fails closed with a configuration screen.
  Deterministic in-memory fixtures are available only in tests or when a local
  development build explicitly sets `VITE_ADMIN_FIXTURE_MODE=true`. Fixture
  mode is never selected by a production build and is not a persistence path.

## Run locally

Use Node.js 22.23.1 as declared by the root `.node-version` file.

```bash
cd admin
npm ci
npm run dev
```

Vite serves the app at `http://127.0.0.1:5173` by default.

To review the UI without connecting to Supabase, opt into temporary local data:

```bash
cd admin
VITE_ADMIN_FIXTURE_MODE=true npm run dev
```

The workspace is visibly labelled **Fixture admin** in this mode. Changes live
in browser memory only, disappear when the page reloads, and never reach
Supabase. A missing or misspelled production configuration never falls back to
these fixtures. The fixture flag is ignored whenever Vite reports a production
bundle, including a production build created with a custom `--mode` value.

## Verify

```bash
cd admin
npm run typecheck
npm test
npm run build
```

## Deploy at admin.gallrmap.com

Deploy the admin as a second Vercel project from the same
`203Projects/gallr` repository. It does not require another purchased domain:
`admin.gallrmap.com` is a subdomain of the existing `gallrmap.com` domain.
Keep the public website and admin as separate Vercel projects so their build
roots, environment values, release promotion, and rollback stay independent.

Create or import the admin project with these settings:

```text
Project name: gallr-admin
Root Directory: admin
Framework Preset: Vite
Production Branch: main
Install Command: npm ci
Build Command: npm run build
Output Directory: dist
```

The checked-in [`vercel.json`](vercel.json) repeats the build contract and adds
baseline browser protections plus a `noindex` directive. Git pushes to feature
branches and `develop` are preview deployments. Only `main` is the production
branch, matching the repository release policy.

Configure these Vercel project variables for Preview and Production as
appropriate:

```text
VITE_SUPABASE_URL
VITE_SUPABASE_PUBLISHABLE_KEY
VITE_ADMIN_FIXTURE_MODE=false
```

Point Preview at the staging Supabase project. Do not add production Supabase
values until the production admin cutover gate is explicitly approved. Never
put a Supabase secret key or service-role key in a `VITE_` variable.

After a preview build passes manual admin checks:

1. Add `admin.gallrmap.com` under the admin project's **Settings → Domains**.
2. Apply the DNS record Vercel displays. If Vercel already manages
   `gallrmap.com`, it can usually configure the subdomain directly.
3. In the matching Supabase project, open **Authentication → URL
   Configuration** and add `https://admin.gallrmap.com` to **Redirect URLs**.
   The password-reset flow uses that origin. Do not add a broad
   `https://*.vercel.app` redirect wildcard.
4. Confirm a non-staff account is denied and an active staff account can sign
   in, edit a draft, sign out, and complete a password-reset redirect.
5. Promote the already-tested deployment to Production. Roll back by
   reassigning the previous healthy Vercel deployment; database migrations are
   governed separately and are never rolled back by a frontend deployment.

## Environment

Inject the target environment's values from its 1Password item to enable the Supabase adapter.
`.env.example` documents variable names only; do not copy credentials into a persistent environment
file:

```text
VITE_SUPABASE_URL=https://your-project.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=your-publishable-key
VITE_ADMIN_FIXTURE_MODE=false
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
When explicit local fixture mode is enabled without a live geocoder, the UI
uses sample address results; use `서울 용산구 한남대로 28` for its
deterministic candidate flow. Without the explicit fixture flag, missing
Supabase configuration shows the fail-closed configuration screen instead.

## Repository contract

`AdminExhibitionRepository` is the seam between the UI and persistence. Its
Supabase implementation maps operations to narrowly scoped database functions:

- `list` → staff-only exhibition query
- `getExhibitionLookups` → one staff-only query returning reusable venue
  snapshots plus event and editor choices, including inactive associations so
  existing historical assignments remain visible
- `createDraft` → transaction that creates a permanent identity and draft v1
- `saveDraft` → update guarded by exact working-version ID and revision; editing
  a published record first creates/reuses an isolated draft
- `publish` → publisher-only transaction that validates and advances the
  published pointer; ambiguous retries retain the same request UUID
- `archive` / `restore` → publisher-only, reversible, idempotent lifecycle
  commands
- `discardDraft` → publisher-only, revision-checked removal of an unpublished
  working version when a distinct published snapshot exists; the published
  pointer and public catalog stay unchanged
- `deleteDraft` → admin-only permanent deletion for an accidental active draft
  that has never been published and has no retained relationships; the UI
  requires the exact typed confirmation `DELETE`
- `listSubmissions` → staff review DTOs whose published media use their public
  delivery URL and whose unpublished media receive short-lived private previews
- `listMedia` → version-scoped media DTO plus short-lived private previews
- `uploadAndAttachMedia` → reserve an immutable path, create/use a signed upload
  token, finalize the Storage object, and attach it to the exact draft revision
- media metadata/reorder/detach → narrow revision-checked commands that return
  the updated exhibition and ordered media bundle

`EditorPickRepository` is a separate least-privilege seam:

- `list` → `editor_list_pick_candidates`, which returns only published,
  non-archived, currently ongoing exhibitions whose working assignment is empty
  or belongs to the authenticated editor
- `submitCuration` → `editor_submit_curation`, which applies a grouped set of
  optimistic attribution changes as unpublished drafts and submits them with
  the collection's bilingual curatorial statement in one admin review request;
  statement-only requests are supported
- `getProfile` / `submitProfile` → reads the membership-derived editor and
  submits only `bio_ko` / `bio_en`; public profile data is unchanged until an
  active admin approves the exact request
- `submitExhibition` → creates an `editor_workspace` record in the canonical
  exhibition Submissions queue; acceptance creates an attributed unpublished
  draft, not a public exhibition

Editor membership alone never satisfies `admin_assert_staff`, so hiding staff
controls in React is not the authorization boundary.

## Invite an editor account

1. Sign in with an active `admin` staff role and open **Editors**. The
   destination is disabled for contributors and publishers, while editor
   accounts remain in the separate **My curation** portal.
2. Submit the invitation email, stable lowercase slug, bilingual personal bio,
   distinct bilingual curatorial statement, and visibility schedule. The
   `invite-editor` Edge Function checks the admin role
   before reading the profile or calling the server-side Auth Admin API.
3. The database command independently checks the active admin, creates the
   `public.editors` and `content.editor_memberships` rows atomically, and writes
   `editor.created` audit evidence. The browser receives no server credential.
4. The invitation link returns the editor to this portal to set their first
   password, then opens **My curation** with the expected editor name.
5. To offboard an editor later, use **Editors → Manage editors → Deactivate**.
   The revision-checked command disables the membership and public profile but
   preserves the Auth account, editor identity, attribution, requests, and
   audit history. Do not delete or rotate credentials as part of routine
   offboarding without separate authorization.

## Manage existing editors

The admin-only editor directory loads through `admin_list_editors`; browser
code never selects `public.editors`, `content.editor_memberships`, or
`auth.users` directly. **Edit** updates the bilingual identity copy, biography,
curatorial statement, active dates, and public visibility through
`admin_update_editor`. The permanent slug and account email are read-only.

Every profile or access mutation supplies the displayed editor revision. A
stale command fails with a revision conflict and reloads the directory instead
of overwriting another administrator's work. **Deactivate** hides the profile
and removes workspace access atomically. **Restore access** re-enables the
workspace but intentionally leaves the public profile unpublished until an
administrator explicitly enables it in Edit. Editors without a linked account,
including a legacy or house identity, remain profile-editable but have no
access action.

## Editable details and associations

Incomplete drafts may leave the map location blank, but publication requires a
nonblank Korean address plus latitude and longitude. Latitude must be between
-90 and 90 and longitude between -180 and 180. Controlled form blanks cross
the save boundary as database `NULL`, not empty coordinate strings. Changing
the searchable Korean road or parcel address clears both coordinates so a stale
pin cannot be published for a different venue. Floor and unit suffix edits
preserve the confirmed coordinate pair.

**Find coordinates** sends the Korean address to the authenticated
`geocode-address` Edge Function. The function verifies active staff access,
calls NAVER Cloud Geocoding with server-held credentials, and returns at most
three candidates. The editor must choose a result before the address and WGS-84
coordinate pair enter the normal revision-guarded autosave. Manual paired
coordinate correction remains available for gallery entrances. English address
copy stays optional; choosing a result replaces it with the provider's English
address so the Korean address and selected coordinates remain one coherent
location result.

The Venue tab can search locations used by previous exhibition versions. It
deduplicates normalized venue name and address pairs, prefers a maintained
`content.venues` default when one exists, and otherwise uses the most recent
matching exhibition snapshot. Selecting a result replaces only bilingual venue,
city, region, address, and coordinate fields. Exhibition identity, descriptions,
schedule, ticket URL, curation, and media remain unchanged; copied fields remain
editable through the normal autosave flow.

`ticketUrl` belongs to the exhibition. When present it must be an absolute
`http://` or `https://` URL; leaving it blank stores `NULL`. The editor and
compatibility projection do not fall back to the associated event's ticket URL.
If a client wants an event-level ticket link, it must fetch and present that
event value explicitly.

Reception end time is optional and versioned independently from reception start
time. Admin accepts `HH:MM` values and stores a blank as `NULL`. New canonical
exhibition drafts default to homepage placement enabled; staff can opt out in
Curation before publication.

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
