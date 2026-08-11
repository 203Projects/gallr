# Legacy exhibition dry-run bundle

This dependency-free Node.js tool prepares and reviews a legacy
`public.exhibitions` export before any database import. It never opens a network
connection and never connects to Supabase or another database.

The database `id` in the JSON export is always authoritative. The historical
Google Apps Script ID—SHA-256 of
`name_ko|venue_name_ko|city_ko|opening_date`, truncated to the first eight
bytes—is reproduced only in diagnostics. It never replaces an exported ID.

## Inputs

`--legacy-json` is required. A bare JSON array is accepted for diagnostics:

```json
[
  {
    "id": "a05df2e502291128",
    "name_ko": "전시이름",
    "venue_name_ko": "갤러리이름",
    "city_ko": "서울",
    "region_ko": "종로구",
    "opening_date": "2026-04-01",
    "closing_date": "2026-05-01",
    "is_featured": false,
    "is_homepage_featured": false,
    "updated_at": "2026-07-20T12:00:00Z"
  }
]
```

but it is deliberately not import-ready because it has no trustworthy snapshot
time. For an import-ready run, use a wrapped object and supply the timestamp
from the same read-only database snapshot:

```json
{
  "source_snapshot_at": "2026-07-20T12:00:00Z",
  "rows": []
}
```

`--sheet-csv` is optional. Export the exhibition worksheet as CSV and pass it
to compare publishable Sheet rows with the public database export. The parser
supports UTF-8 BOMs, CRLF, quoted commas, escaped quotes, and multiline cells.
As the retired Apps Script did, it trims and lowercases header names before matching them;
headers that collide after normalization are rejected.
It mirrors the historical Apps Script approval gate: when a `status` header exists,
only `approved` rows are compared; form-sourced `image_url_1` through
`image_url_5` rows always require `approved`.

## Run

From the repository root, with both sources:

```sh
node scripts/legacy-import/legacy-import.mjs \
  --legacy-json /absolute/path/exhibitions.json \
  --sheet-csv /absolute/path/exhibitions-sheet.csv \
  --output-dir /private/tmp/gallr-legacy-review
```

Without a Sheet export:

```sh
node scripts/legacy-import/legacy-import.mjs \
  --legacy-json /absolute/path/exhibitions.json \
  --output-dir /private/tmp/gallr-legacy-review
```

The command writes, and may replace, these four files inside `--output-dir`:

- `bundle.json` — versioned import bundle. Every row contains exactly
  `source_row_number` plus the 28 compatibility fields.
- `summary.json` — source fingerprints, counts, and `import_ready`.
- `issues.csv` — blocking errors, warnings, and informational approval-gate
  exclusions.
- `reconciliation.csv` — public/Sheet matches and the non-authoritative GAS ID
  diagnostic. Without `--sheet-csv`, its rows use `not_compared` status.

The compatibility field order is:

```text
id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en,
region_ko, region_en, opening_date, closing_date, is_featured, latitude,
longitude, description_ko, description_en, address_ko, address_en,
cover_image_url, hours, contact, reception_date, opening_time, event_id,
editor_id, is_homepage_featured, ticket_url, updated_at
```

Exit codes:

- `0` — reports were written and no blocking errors were found.
- `2` — reports were written, but `summary.json` has `import_ready: false`.
- `1` — arguments or input structure were invalid, so reports were not written.

Do not import merely because the process exits `0`. Review all four artifacts,
confirm the source SHA-256 and snapshot time, and retain an operator-approved
copy outside the repository.

For a blocked run, malformed boolean or numeric source values remain visible in
`bundle.json` instead of being silently coerced. This lets the database staging
validator reject the same bad value independently. Import-ready bundles contain
only normalized compatibility types.

## Validation

The dry run checks:

- required IDs, Korean identity fields, and opening/closing dates;
- real calendar dates and `closing_date >= opening_date`;
- numeric, paired coordinates and latitude/longitude ranges;
- HTTP/HTTPS schemes for stored public image and ticket URLs;
- duplicate authoritative IDs;
- the required `updated_at` timestamp;
- Sheet-only, public-only, ambiguous, and field-mismatched rows;
- differences between the authoritative database ID and diagnostic GAS ID.

Filename-only Sheet cover values are accepted because the retired Apps Script resolved them into
the public `exhibition-images` bucket. A matching public URL
ending in that filename reconciles as equal without making a network request.
When a Sheet has no explicit `id` column, the diagnostic GAS hash may locate the
corresponding row for comparison; `bundle.json` still keeps the database ID.

## Tests

```sh
node --test scripts/legacy-import/legacy-import.test.mjs
```

Tests use operating-system temporary directories and remove them afterward; no
generated report is written into the repository.
