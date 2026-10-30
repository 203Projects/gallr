# Contract: catalogue reliability (Supabase, Admin, Gallery)

All RPCs are `security definer` in `content_private` with thin `public` wrappers, staff-role checked inside the
function, revision-checked the way `admin_save_exhibition_draft` is (`p_expected_version_id uuid`,
`p_expected_revision integer`), and audited, following the existing art-metadata and curator-note functions.
Exhibition ids are `text`. pgTAP suites continue the numbering after `068_app_version_policy`.

## Migrations (order)

1. `…_art_vocabulary_expansion.sql`: insert the owner's term list (idempotent `on conflict (id) do nothing`).
   Applied after Admin and Gallery deploy. pgTAP 069.
2. `…_art_metadata_provenance.sql`: `grounding` on version terms and artists; `metadata_source_hash`,
   `metadata_reviewed_at` on versions; `apply_` and `clone_exhibition_art_metadata` carry grounding and hashes;
   `exhibition_metadata_is_stale(version_id)`; `admin_confirm_exhibition_metadata(p_exhibition_id text,
   p_expected_version_id uuid, p_expected_revision integer)`; `admin_list_stale_exhibition_metadata()`. pgTAP 070.
3. `…_confirmed_opening_hours.sql`: `hours_weekly`, `hours_confirmed_by`, `hours_confirmed_at`, `hours_source_hash`
   on versions; `admin_confirm_exhibition_hours(p_exhibition_id text, p_expected_version_id uuid, p_expected_revision
   integer, p_hours_weekly jsonb)` validating seven weekday keys and `HH:MM` with opens < closes; catalogue projection of
   `hours_weekly` only while fresh. pgTAP 071.
4. `…_artist_merge.sql`: `normalize_artist_name(text)`, `admin_find_duplicate_artists()`, `admin_merge_artists(
   p_survivor_id uuid, p_merged_id uuid, p_reason text)`. pgTAP 072.
5. `…_artist_name_uniqueness.sql` (only after the owner has merged every reported group): unique partial indexes;
   aborts listing remaining duplicate groups; create functions raise `artist_duplicate` with the existing id.
   pgTAP 073.

Each migration is applied to staging, then production, as separately authorized steps (CLAUDE.md, recorded
migrations are immutable). Run `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` first.

## Exact interface (shared by the database, Admin, Gallery and the app)

### Draft patch (`admin_save_exhibition_draft`, `owner_save_exhibition_draft`)
- `artists[]` items accept an optional `grounding` key: string or null, at most 400 characters, stored on
  `content.exhibition_version_artists.grounding`.
- New optional top-level key `art_term_grounding`: an object from term id to string or null (at most 400
  characters). Keys must be ids present in `art_term_ids` (when sent) or already on the draft; others raise
  `art_metadata_term_grounding_invalid`. `art_term_ids` stays an array of strings, so existing clients keep working.
- Owners may send `grounding` too; it is never shown to readers.

### Which version a confirm touches
- `admin_confirm_exhibition_metadata` stamps the published version and the open draft (when there is one), each with
  the SHA-256 of its own `description_ko`, `description_en`, `opening_date` and `closing_date`
  (`content_private.exhibition_metadata_source_hash(version_id)`), and sets `metadata_reviewed_at = now()`. The
  revision check is against the open draft when there is one, otherwise against the published version.
- `admin_confirm_exhibition_hours` writes `hours_weekly`, `hours_confirmed_by`, `hours_confirmed_at` and
  `hours_source_hash` (SHA-256 of that version's `hours` text) on the open draft when there is one, otherwise on the
  published version. Publishing and cloning carry the four columns, so readers see confirmed hours once the version
  that carries them is published and its `hours` text is unchanged.
- Hashes: `encode(extensions.digest(convert_to(<text>, 'UTF8'), 'sha256'), 'hex')` over the fields joined with
  a unit separator (`chr(31)`), nulls as empty strings, dates as ISO text.

### `hours_weekly` JSON
Seven keys exactly: `MONDAY` … `SUNDAY`. Each value is `null` (closed) or `{"opens":"HH:MM","closes":"HH:MM"}` with
24-hour times and `opens < closes`. Anything else raises `hours_weekly_invalid`.

### Responses
- `content_private.admin_exhibition_json` gains, per version object: `metadata_stale boolean`,
  `metadata_reviewed_at`, `hours_weekly`, `hours_confirmed_at`, `hours_stale boolean`; each `artists[]` item gains
  `grounding`; a new `art_term_grounding` object (term id → text) sits beside the existing term list.
- `admin_list_stale_exhibition_metadata()` → `jsonb` array of
  `{exhibition_id, name_ko, name_en, metadata_stale, hours_stale}` for exhibitions whose published version or open
  draft is stale in either way, newest change first.
- `admin_find_duplicate_artists()` → `jsonb` array of groups
  `{key, artists: [{id, name_ko, name_en, credit_count, archived: false}]}` for active artists sharing a normalised
  Korean or English name; admin role only.
- `admin_merge_artists(p_survivor_id uuid, p_merged_id uuid, p_reason text)` → `{survivor_id, merged_id,
  moved_credits, removed_duplicate_credits}`; admin role only; `p_reason` 1–400 characters.
- `artist_duplicate` is raised with `errcode = 'P0001'`, `message = 'artist_duplicate'` and
  `detail = <existing artist id>`, from `admin_create_artist` and from any path that creates an artist row.
- `public.exhibition_catalog_v2` gains `hours_weekly jsonb`, the published version's value only while its
  `hours_source_hash` matches its current `hours` text, else null.

## Error codes (new)

| Code | Raised by | Admin/Gallery behaviour |
|---|---|---|
| `artist_duplicate` | create artist | show "이미 있는 작가예요 · 연결" with the existing artist |
| `artist_merge_invalid` | merge | `detail` is `same_artist`, `reason_invalid`, `survivor_not_found`, `merged_not_found`, `survivor_archived` or `merged_archived`; show the matching reason |
| `hours_weekly_invalid` | confirm hours | highlight the bad row |
| `exhibition_metadata_stale_revision` | confirm metadata or hours | reload, as other revision conflicts |
| `art_metadata_term_grounding_invalid` | save draft | highlight the term |
| `art_metadata_artist_grounding_invalid` | save draft | the input already stops at 400 characters |

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
