# gas/ — Google Apps Script Sync Pipeline

This directory contains the Apps Script source for the gallr gallery data sync pipeline.
The deployed copy lives in the Google Apps Script project bound to the Google Sheet.

## Files

- `SyncExhibitions.gs` — Main sync script: reads the Google Sheet, validates rows,
  generates stable IDs, and performs a full replace in Supabase.

## One-time Setup

### Step 1 — Supabase table

Run `supabase/migrations/001_create_exhibitions.sql` in the Supabase SQL editor:
https://supabase.com/dashboard → your project → SQL Editor

### Step 2 — Apps Script project

1. Open your Google Sheet
2. Extensions → Apps Script
3. Paste the contents of `SyncExhibitions.gs` into the editor
4. Click Save

### Step 3 — Script Properties (credentials)

Apps Script editor → Project Settings → Script Properties. Add:

| Property | Value |
|----------|-------|
| `SUPABASE_URL` | `https://<project-ref>.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | `<your service role key>` |

> The service role key bypasses Row Level Security. **Never** put it in the mobile app.
> Find it in: Supabase dashboard → Settings → API → service_role key.

### Step 4 — Install Triggers

**Installable onEdit** (fires on every sheet save):
- Triggers → Add Trigger
- Function: `syncToSupabase`, Event source: From spreadsheet, Type: On edit

**Time-based backup** (guarantees 5-minute propagation SLA):
- Triggers → Add Trigger
- Function: `syncToSupabase`, Event source: Time-driven, Type: Minutes timer, Interval: Every 5 minutes

### Step 5 — Test the sync

1. Add a row to the sheet with all required fields (columns A–F)
2. In Apps Script editor, run `syncToSupabase()` manually
3. Check View → Executions — you should see status `SUCCESS` with correct row counts
4. Open the gallr app — the new exhibition should appear

## Google Sheet Column Layout

Row 1 must be a header row. Data rows start at row 2.

| Column | Field | Required |
|--------|-------|----------|
| A | name | Yes |
| B | venue_name | Yes |
| C | city | Yes |
| D | region | Yes |
| E | opening_date | Yes (YYYY-MM-DD or YYYY.MM.DD) |
| F | closing_date | Yes |
| G | is_featured | No (TRUE/FALSE) |
| — | is_homepage_featured | No (TRUE/FALSE) — flags rows for the gallrmap.com homepage; column position flexible (header-driven) |
| H | latitude | No (decimal degrees) |
| I | longitude | No (decimal degrees) |
| J | description | No |
| K | cover_image_url | No (HTTPS URL) |
| — | editor_id | Optional. Slug pointing at `editors.id`. Use `gallr-editors` for team-curated picks (previously `is_editors_pick=true`). Use a specific editor slug for guest curators. Column position flexible (header-driven). Validated against `editors` table before insert — bad slugs are skipped with a log message. |

## Sync Behaviour

- **Full replace**: Every sync deletes all rows from Supabase and re-inserts all valid rows.
- **Invalid rows are skipped**: Rows with missing required fields, malformed dates, or an unrecognised `editor_id` are
  logged but do not abort the sync.
- **Stable IDs**: Each row's ID is a SHA-256 hash of `name|venue_name|opening_date`.
  The same exhibition keeps the same ID across sync runs as long as these three fields
  don't change. This means bookmarks in the app survive a sync.

## Updating the Script

After editing `SyncExhibitions.gs` locally:
1. Copy the file contents
2. Open the Apps Script editor in your browser
3. Replace the editor contents and save

There is no automated deploy pipeline — the script is small enough for manual copy-paste.

## Migration from v1.5.x to v1.6 (spec 041)

The unified editor model replaces two legacy columns. Before applying
migration `017_unify_editors.sql`, prepare the gallr exhibition sheet:

1. **Rename `guest_editor_id` → `editor_id`** by editing the header row.
   Data is unchanged; existing slugs work as-is.
2. **Delete the `is_editors_pick` column** entirely.
3. **Bulk-fill `gallr-editors`** into `editor_id` for previously-flagged rows.
   Quick formula tip — paste into an unused column to compute the new
   `editor_id` from the legacy state, then paste-values back into `editor_id`:

   ```
   =ARRAYFORMULA(IF(I2:I = TRUE, "gallr-editors", J2:J))
   ```

   where `I` is the legacy `is_editors_pick` column and `J` is the legacy
   `guest_editor_id` column. Output is the new `editor_id` value.

After the sheet is updated, apply the SQL migration and trigger a sync.
Rows whose `editor_id` references an unknown editor are skipped with a
clear log message — insert the editor row in Supabase Studio first if
that happens.
