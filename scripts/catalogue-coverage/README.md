# Catalogue coverage gate

Spec 101 FR-024: before every store release the owner records catalogue coverage in the release pull request,
and the release does not proceed below the enforced floor. `coverage.sql` is one read-only `SELECT`;
`coverage.mjs` is dependency-free Node 22 that turns its JSON output into a Markdown table and an exit code.
Neither writes anything.

## Run before the release PR (owner)

Production reads are the owner's step; agents do not run this against a hosted project. The query needs the
spec 101 provenance, confirmed-hours and artist-merge migrations on the target.

```sh
supabase db query --linked --project-ref <ref> --output-format json \
  -f scripts/catalogue-coverage/coverage.sql < /dev/null \
  | node scripts/catalogue-coverage/coverage.mjs --release 1.15.0 --floor-week 1.0 --floor-all 0.9
```

Paste the printed table into the release pull request. Exit codes: `0` at or above both floors, `1` below a
floor (do not release), `2` malformed query output or arguments.

## Floors (spec 101 Assumptions; the owner may change both)

| Flag | Default | Share that must meet it |
|---|---|---|
| `--floor-week` | `1.0` | running and upcoming exhibitions first published more than 7 days ago that have ≥3 reviewed terms |
| `--floor-all` | `0.9` | all running and upcoming exhibitions that have ≥3 reviewed terms |

A share is compared exactly and printed rounded down, so 99.9% never prints as 100%.

## What is counted

Rows are what readers see in `public.exhibition_catalog_v2`; terms, credits and freshness come from each row's
published version. Running and upcoming means the closing date is today or later in Seoul.

| Key | Meaning |
|---|---|
| `running_upcoming` | running and upcoming exhibitions |
| `terms_3plus` | ≥3 art terms (visit terms excluded) on the published version, and its metadata is not stale |
| `published_week_ago`, `published_week_ago_terms_3plus` | the same, for exhibitions first published more than 7 days ago |
| `credits_named`, `credits_named_linked` | credits text names someone; of those, ≥1 credit linked to an artist |
| `hours_confirmed` | the catalogue carries fresh `hours_weekly` |
| `coordinates`, `english_description` | latitude and longitude; a non-blank English description |
| `listed_total`, `listed_current_dates` | every listed exhibition; those whose closing date is not in the past |
| `metadata_stale`, `hours_stale` | terms or credits, or confirmed hours, whose source text changed since review |
| `duplicate_artist_groups` | groups of active artists sharing a normalised Korean or English name |

Stale terms count as needing review, not as reviewed. Hours the parser reads completely are not counted: the
parser is not in the database, and only confirmed hours are reported (reported, not enforced, in 1.15).

## Tests

Network-free; run with the pinned Node from `.node-version`:

```sh
node --test scripts/catalogue-coverage/coverage.test.mjs
```

The fixtures in `fixtures/` are sample query outputs (`{rows:[{coverage}]}` and a bare row array with the
column as JSON text).
