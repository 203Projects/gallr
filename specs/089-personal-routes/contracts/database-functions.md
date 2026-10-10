# Contract: route database functions

Owner and staff functions follow the house pattern: a `public` wrapper that runs as the caller with an empty `search_path` and calls a definer-rights implementation in `content_private`. `record_route_page_event` and `get_published_route` are the definer-rights functions in `public`, because the anonymous role has no access to `content_private`. Errors use a standard SQLSTATE plus a snake_case message the app maps (table below); list-valued errors carry the ids as a JSON array in the error detail. Implemented in `supabase/migrations/20261008014900_personal_routes.sql` and `20261008130000_published_route_reads.sql`, covered by `supabase/tests/database/050_personal_routes.test.sql`, `052_published_route_reads.test.sql` and `supabase/tests/personal_routes_concurrency.sh`.

Table reads: only a route's owner may select `personal_routes` and `personal_route_stops` directly (`authenticated`, row-level security on `owner`). `anon` has no table privilege, and no policy lets another account read a published route, so shared routes cannot be listed with the publishable key (090 eng review D4). Everyone else reads one route by id through `get_published_route`.

| SQLSTATE | Message | Meaning |
|---|---|---|
| 42501 | `personal_route_unauthenticated` | No signed-in user |
| 42501 | `personal_route_not_owner` | Route belongs to another account |
| 42501 | `personal_route_not_staff` | Staff-only function |
| 55000 | `personal_route_revoked` | Route was revoked |
| 22023 | `personal_route_invalid_name` | Trimmed name not 1–60 characters |
| 22023 | `personal_route_invalid_stop_count` | Not 2–10 stops |
| 22023 | `personal_route_duplicate_stop` | Same exhibition twice |
| 22023 | `personal_route_missing_location` | Catalogue row has no coordinates (detail: ids) |
| 22023 | `personal_route_unavailable_stops` | Not listed and not already in this route (detail: ids) |
| P0002 | `personal_route_not_found` | Publish, delete or revoke of a missing id |

## `save_personal_route(p_id uuid, p_name text, p_exhibition_ids text[]) returns jsonb`

- EXECUTE: `authenticated` only (revoked from `public` and `anon`). Raises `personal_route_unauthenticated` when `auth.uid()` is null.
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

- EXECUTE: `authenticated`. Owner only. Deletes the route; stops and daily counts cascade.

## `list_my_personal_routes() returns jsonb`

- EXECUTE: `authenticated`. Returns the caller's routes (id, name, stop count, is_published, revoked_at, updated_at), newest first.

## `revoke_personal_route(p_id uuid) returns jsonb`

- EXECUTE: `authenticated`. Requires `auth.uid()` in `content.staff_members` (active), else `personal_route_not_staff`. Sets `revoked_at = coalesce(revoked_at, now())`. Returns the route status (E-D4).

## `get_route_for_moderation(p_id uuid) returns jsonb`

- EXECUTE: `authenticated`, staff only (`personal_route_not_staff`). Returns name, author display name, stops, `is_published`, `published_at`, `revoked_at` for any route, or null when missing (DR-D13).

## `get_published_route(p_id uuid) returns jsonb`

- EXECUTE: `anon`, `authenticated`. Definer rights, empty `search_path`, stable.
- Returns `{ "id", "name", "owner", "updated_at", "stops": [{ "position", "exhibition_id", snapshot fields… }] }` with stops ordered by position, for a route that is published and not revoked.
- Returns null for a missing, unpublished or revoked route, or a null id, so the reader cannot tell them apart.
- Read by the shared route page (`web/api/_lib/route-data.js`).

## `record_route_page_event(p_route_id uuid, p_event text, p_shared boolean) returns void`

- EXECUTE: `anon`, `authenticated`.
- Accepts only `route_page_opened` and `route_page_started`; anything else is ignored without error.
- Ignores routes that are missing, unpublished or revoked.
- Upserts `route_page_daily` for the Asia/Seoul day, incrementing `count` (E-D5).

## pgTAP coverage (minimum)

Owner reads own private route, stranger cannot; anon has no table select and another account cannot list published routes from the tables; `get_published_route` returns a published route with ordered stops to anon and other accounts and null for unpublished, revoked, missing and null ids; no direct insert/update/delete for any role; save rejects 1 and 11 stops, duplicates, over-length name, revoked, foreign-owned id, missing coordinates, unknown id; save copies the catalogue snapshot; a no-longer-published stop survives a reorder; the same id saved twice yields one route; publish sets `published_at` once; publish and save reject revoked; anonymous save/publish/delete refused; staff/non-staff/anonymous for revoke and moderation read; event function accepts the two names only and ignores unpublished and revoked routes. Two-session script `supabase/tests/personal_routes_concurrency.sh`: simultaneous saves both succeed and stops equal one of them; a reader during a save sees old or new, never a mix (E-D12, E-D19).
