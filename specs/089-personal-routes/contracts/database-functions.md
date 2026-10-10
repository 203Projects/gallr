# Contract: route database functions

Owner and staff functions follow the house pattern: a `public` wrapper that runs as the caller with an empty `search_path` and calls a definer-rights implementation in `content_private`. `record_route_page_event`, `get_published_route` and `get_listed_route` are the definer-rights functions in `public`, because the anonymous role has no access to `content_private`. Errors use a SQLSTATE plus a snake_case message the app maps (table below); list-valued errors carry the ids as a JSON array in the error detail. Expected outcomes are raised with the house codes `PT409` (conflict or refused transition, HTTP 409 through PostgREST) and `PT404` (not found, HTTP 404), never `55000`/`P0002`, which PostgREST would answer with 500. Clients map by message only. Implemented in `supabase/migrations/20261010014900_personal_routes.sql` and `20261010130000_published_route_reads.sql`, covered by `supabase/tests/database/050_personal_routes.test.sql`, `052_published_route_reads.test.sql` and `supabase/tests/personal_routes_concurrency.sh`.

Table reads: only a route's owner may select `personal_routes` and `personal_route_stops` directly (`authenticated`, row-level security on `owner`). The grant on `personal_routes` is column-level: `id`, `owner`, `name`, `is_published`, `published_at`, `revoked_at`, `created_at`, `updated_at` (what `PersonalRouteApiClient.ROUTE_SELECT` reads plus what the policies and the owner list need); the listing columns added by `20261010140000`, including `listing_decided_by` (the staff account), are read through the route functions only. `anon` has no table privilege at any point in the migration sequence, and no policy lets another account read a published route, so shared routes cannot be listed with the publishable key (090 eng review D4). Everyone else reads one route by id through `get_published_route`.

Deleted ids: `personal_route_tombstones (id, owner, deleted_at)` is filled by a `before delete` trigger on `personal_routes` (so it also records account-deletion cascades, with `owner` null when the account row is already gone, and `owner` is set null when the account is deleted later). It has no app-role grant. Route ids are chosen by the app, so without it anyone holding a shared link or QR card could save a new route under the old id and publish their own content behind it.

Staff tiers: `content_private.require_route_staff(p_role content.staff_role)` uses `content_private.has_staff_role` (`contributor < publisher < admin`) and raises `personal_route_not_staff` for a missing, inactive or too-low membership. Moderation read and listing review need `publisher`; `revoke_personal_route` needs `admin`. Every staff action inserts a `content.audit_log` row (`entity_type = 'personal_route'`, `entity_id` = route id) through `content_private.record_route_audit`.

| SQLSTATE | Message | Meaning |
|---|---|---|
| 42501 | `personal_route_unauthenticated` | No signed-in user |
| 42501 | `personal_route_not_owner` | Route belongs to another account |
| 42501 | `personal_route_not_staff` | Staff-only function, or the caller's tier is too low |
| PT409 | `personal_route_revoked` | Route was revoked (save, publish, delete, listing request) |
| 22023 | `personal_route_id_required` | `p_id` is null |
| 22023 | `personal_route_invalid_name` | Trimmed name not 1–60 characters |
| 22023 | `personal_route_invalid_stop_count` | Not 2–10 stops |
| 22023 | `personal_route_duplicate_stop` | Same exhibition twice |
| 22023 | `personal_route_missing_location` | Catalogue row has no coordinates (detail: ids) |
| 22023 | `personal_route_unavailable_stops` | Not listed and not already in this route (detail: ids) |
| PT404 | `personal_route_not_found` | Publish, delete, revoke or decision on a missing id; a save under an id deleted by another account or by a deleted account |

## `save_personal_route(p_id uuid, p_name text, p_exhibition_ids text[]) returns jsonb`

- EXECUTE: `authenticated` only (revoked from `public` and `anon`). Raises `personal_route_unauthenticated` when `auth.uid()` is null, `personal_route_id_required` when `p_id` is null.
- Refuses an id that has a tombstone whose owner is null or another account (`personal_route_not_found`); the same owner may recreate their own deleted id.
- Inserts the route for the caller if the id is new, then locks the row (`select … for update`) so saves of one route run one at a time (E-D12).
- When `p_id` does not exist: creates it with `owner = auth.uid()`, unpublished.
- When `p_id` exists and is owned by the caller: replaces name and stops in the same transaction.
- When `p_id` is owned by another account: raises `personal_route_not_owner`.
- When the route is revoked: raises `personal_route_revoked`.
- Name: trimmed length 1–60, else `personal_route_invalid_name`.
- Stops: 2–10 distinct ids, else `personal_route_invalid_stop_count` or `personal_route_duplicate_stop`.
- For each id, copies `name_ko`, `name_en`, `venue_name_ko`, `venue_name_en`, `latitude`, `longitude`, `region_ko`, `region_en`, `city_ko` from the currently published row of `exhibition_catalog_v2` (E-D7, RR2). An id with null coordinates raises `personal_route_missing_location` with the ids (RO5). An id not currently published is accepted only if this route already holds it, keeping its stored snapshot; otherwise raises `personal_route_unavailable_stops` with the ids (E-D18).
- Bumps `updated_at`.
- Returns `{ "id", "revision": updated_at, "name", "is_published", "published_at", "stops": [{ "position", "exhibition_id", snapshot fields… }] }` (E-D16).

## `publish_personal_route(p_id uuid) returns jsonb`

- EXECUTE: `authenticated`. Owner only (`personal_route_not_owner`), refuses revoked (`personal_route_revoked`).
- Sets `is_published = true` and `published_at = coalesce(published_at, now())`; never changes `published_at` once set (E-D14).
- Returns the same shape as `save_personal_route`.

## `delete_personal_route(p_id uuid) returns void`

- EXECUTE: `authenticated`. Owner only. Refuses a revoked route (`personal_route_revoked`) so a link staff took down cannot be recreated by deleting and re-saving. Deletes the route; stops and daily counts cascade; the tombstone trigger records the id and owner.

## `list_my_personal_routes() returns jsonb`

- EXECUTE: `authenticated`. Returns the caller's routes (id, name, stop count, is_published, revoked_at, updated_at), newest first.

## `revoke_personal_route(p_id uuid) returns jsonb`

- EXECUTE: `authenticated`. Requires an active `admin` row in `content.staff_members`, else `personal_route_not_staff`. Locks the row (`personal_route_not_found` when missing), sets `revoked_at = coalesce(revoked_at, now())`, writes an audit row `route_revoked` (metadata: `already_revoked`, and from `20261010140000` `listing_state_before`/`listing_state_after`). Returns the route status (E-D4).

## `get_route_for_moderation(p_id uuid) returns jsonb`

- EXECUTE: `authenticated`, `publisher` or `admin` staff only (`personal_route_not_staff`). Returns name, author display name, stops, `is_published`, `published_at`, `revoked_at` for any route, or null when missing (DR-D13).

## `get_published_route(p_id uuid) returns jsonb`

- EXECUTE: `anon`, `authenticated`. Definer rights, empty `search_path`, stable.
- Returns `{ "id", "name", "author_display_name", "is_mine", "updated_at", "stops": [{ "position", "exhibition_id", snapshot fields… }] }` with stops ordered by position, for a route that is published and not revoked. `author_display_name` is the author's trimmed profile display name (empty string when unset); `is_mine` is true only when the caller is the author (false without an account). The author's account id is never returned.
- Returns null for a missing, unpublished or revoked route, or a null id, so the reader cannot tell them apart.
- Read by the shared route page (`web/api/_lib/route-data.js`) and returned by `save_public_route`. The app's preview of a listed route uses `get_listed_route` (public-routes-functions.md).

## `record_route_page_event(p_route_id uuid, p_event text, p_shared boolean) returns void`

- EXECUTE: `anon`, `authenticated`.
- Accepts only `route_page_opened` and `route_page_started`; anything else is ignored without error.
- Ignores routes that are missing, unpublished or revoked.
- Upserts `route_page_daily` for the Asia/Seoul day, incrementing `count` (E-D5).

## pgTAP coverage (minimum)

Owner reads own private route, stranger cannot; anon has no table select and another account cannot list published routes from the tables; owners have no table-wide select and cannot read `listing_decided_by`, while the columns the app selects still read; `get_published_route` returns a published route with ordered stops, `author_display_name` and `is_mine` (false for anon and other accounts, true for the owner, no `owner` key) and null for unpublished, revoked, missing and null ids; no direct insert/update/delete for any role; save rejects a null id, 1 and 11 stops, duplicates, over-length name, revoked, foreign-owned id, missing coordinates, unknown id; save copies the catalogue snapshot; a no-longer-published stop survives a reorder; the same id saved twice yields one route; publish sets `published_at` once; publish, save and delete reject revoked; a deleted id leaves a tombstone naming its owner, another account cannot save under it, the owner can, the tombstone survives the owner's account deletion and then blocks everyone; anonymous save/publish/delete refused; staff tiers for revoke (admin) and the moderation read (publisher), author refused on both; a revoke leaves one audit row; event function accepts the two names only and ignores unpublished and revoked routes. Two-session script `supabase/tests/personal_routes_concurrency.sh`: simultaneous saves both succeed and stops equal one of them; a reader during a save sees old or new, never a mix (E-D12, E-D19).
