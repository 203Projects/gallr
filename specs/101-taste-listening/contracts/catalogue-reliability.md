# Contract: catalogue reliability (Supabase, Admin, Gallery)

All RPCs are `security definer` in `content_private` with thin `public` wrappers, staff-role checked inside the
function, revision-checked (`expected_updated_at`), and audited, following the existing art-metadata and curator-note
functions. pgTAP suites continue the numbering after `068_app_version_policy`.

## Migrations (order)

1. `…_art_vocabulary_expansion.sql`: insert the owner's term list (idempotent `on conflict (id) do nothing`).
   Applied after Admin and Gallery deploy. pgTAP 069.
2. `…_art_metadata_provenance.sql`: `grounding` on version terms and artists; `metadata_source_hash`,
   `metadata_reviewed_at` on versions; `apply_` and `clone_exhibition_art_metadata` carry grounding and hashes;
   `exhibition_metadata_is_stale(version_id)`; `admin_confirm_exhibition_metadata(p_exhibition_id uuid,
   p_expected_updated_at timestamptz)`; `admin_list_stale_exhibition_metadata()`. pgTAP 070.
3. `…_confirmed_opening_hours.sql`: `hours_weekly`, `hours_confirmed_by`, `hours_confirmed_at`, `hours_source_hash`
   on versions; `admin_confirm_exhibition_hours(p_exhibition_id uuid, p_hours_weekly jsonb, p_expected_updated_at
   timestamptz)` validating seven weekday keys and `HH:MM` with opens < closes; catalogue projection of
   `hours_weekly` only while fresh. pgTAP 071.
4. `…_artist_merge.sql`: `normalize_artist_name(text)`, `admin_find_duplicate_artists()`, `admin_merge_artists(
   p_survivor_id uuid, p_merged_id uuid, p_reason text)`. pgTAP 072.
5. `…_artist_name_uniqueness.sql` (only after the owner has merged every reported group): unique partial indexes;
   aborts listing remaining duplicate groups; create functions raise `artist_duplicate` with the existing id.
   pgTAP 073.

Each migration is applied to staging, then production, as separately authorized steps (CLAUDE.md, recorded
migrations are immutable). Run `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` first.

## Error codes (new)

| Code | Raised by | Admin/Gallery behaviour |
|---|---|---|
| `artist_duplicate` | create artist | show "이미 있는 작가예요 · 연결" with the existing artist |
| `artist_merge_invalid` | merge | show the reason (same id, archived, not found) |
| `hours_weekly_invalid` | confirm hours | highlight the bad row |
| `exhibition_metadata_stale_revision` | confirm metadata | reload, as other revision conflicts |

## Admin

- Art / 작품 정보: each ticked term and credit shows and edits its grounding; a "Confirm terms" action on a stale
  exhibition; a Stale filter in the exhibition list (from `admin_list_stale_exhibition_metadata`).
- Hours: under the free-text hours field, a seven-row weekly editor prefilled by the TypeScript parser
  (`admin/src/hours/openingHours.ts`, parity with the 21 golden cases), "Confirm hours" button, stale badge.
- Artists: duplicate report and a merge dialog (survivor, merged, reason), admin role only.

## Gallery workspace

- `artist_duplicate` handled like Admin when an owner creates an artist. No other change.

## Coverage check

`scripts/catalogue-coverage/coverage.sql` + `coverage.mjs`:

```
supabase db query --linked --project-ref <ref> -f scripts/catalogue-coverage/coverage.sql < /dev/null \
  | node scripts/catalogue-coverage/coverage.mjs --release 1.15.0 --floor-week 1.0 --floor-all 0.9
```

Prints a Markdown table for the release PR; exit 1 below the floor. Read-only.

## Desk tool

`deno task -q tag <subcommand>` in `scripts/tag-exhibition/`: `candidates-sql`, `brief`, `check <drafts.json>`,
`sheet <drafts.json>`. Draft shape:

```json
{ "<exhibition id>": {
    "terms": [{ "id": "theme:body", "grounding": "<verbatim sentence from description_ko or _en>" }],
    "artists": [{ "existingId": "<uuid>" } | { "nameKo": "…", "nameEn": "…", "grounding": "<line from credits or description>" }]
} }
```

`check` fails on: unknown term id; fewer than 3 or more than 6 terms; more than 2 per category; grounding not a
substring of the packet text; a new artist missing either name; an `existingId` not in the packet's artist search
results. It never connects to a database.
