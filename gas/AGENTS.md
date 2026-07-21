# gallr GAS sync — Agent Guide

Google Apps Script pipeline that syncs a Google Sheet → the Supabase `exhibitions` / `events` tables,
plus a public submission endpoint. Standalone `.gs` files, deployed as bound/standalone Apps Script
projects — the root `CLAUDE.md` KMP rules do **not** apply here.

- `SyncExhibitions.gs` — Sheet → `exhibitions`. Header-driven; runs on a time trigger (~every 5 min).
- `SyncEvents.gs` — Sheet → `events` (same pattern, separate script bound to the events sheet).
- `FormEndpoint.gs` — public, unauthenticated `/submit` endpoint (HMAC token + rate limit +
  image magic-byte validation + formula-injection escaping + fail-closed review gate).

## The `KNOWN_COLUMNS` trap (read before any schema change)

Each sync writes **only** the columns listed in its `KNOWN_COLUMNS` array (`SyncExhibitions.gs`
~lines 361–380). A header/column **not** in that array is simply **never written**.

- The sync does **upsert** (`Prefer: resolution=merge-duplicates`) for valid sheet rows, then a
  **scoped diff-delete** of only the stale rows whose IDs are no longer in the sheet (with a
  zero-valid-rows safety guard that skips the delete). It is **not** delete-all/insert-all — older
  wording in `README.md` / `gas/README.md` is stale; reconcile it if you touch those docs.
- Consequence: a column missing from `KNOWN_COLUMNS` keeps its existing value on existing rows (upsert
  doesn't clear unwritten columns) and gets the DB default on newly inserted rows — it is never
  populated from the sheet. **Treat a Supabase schema change and its `KNOWN_COLUMNS` update as one
  atomic change**, or the new column silently never syncs.

## Other gotchas

- **Stable IDs:** exhibition ID = SHA-256 of `name_ko | venue_name_ko | city_ko | opening_date`
  (truncated). Same row keeps its ID across syncs as long as those fields don't change — app bookmarks
  depend on this. Two rows colliding on that composite key collide on ID.
- **FK validation:** `event_id` / `editor_id` are validated against the `events` / `editors` tables;
  unrecognized FKs cause the row to be **skipped with a logged reason** (never deleted).
- **Approval gate:** if a `status` column exists, only `approved` rows publish; `pending` stay in the
  sheet. `FormEndpoint.gs` **fails closed** (refuses to append) if the `status` column is absent.
- **Rate limiting is best-effort** (per-execution `CacheService`, not per-client IP).
- The `event-images` / `submissions` storage buckets are referenced here but must be created manually
  in the Supabase dashboard (unlike `exhibition-images` / `avatars`, which migrations create).
