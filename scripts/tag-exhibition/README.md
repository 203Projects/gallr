# Desk tagging tool

Spec 101 US4 (FR-017, FR-019): a Claude session at a staff member's desk drafts,
for each running or upcoming exhibition, three to six reviewed art terms and the
artist credits its own text names, each with the sentence that supports it.
`check` refuses what the text does not support; `sheet` prints a checklist that
a person applies in Admin's Art / 작품 정보 section and publishes. The
`tag-exhibition` skill (`.claude/skills/tag-exhibition/SKILL.md`) is the
operator's guide.

**The tool never writes to a database.** `tag_cli.ts` prints text and reads only
the files it is given. The one SQL statement it prints is a read-only `SELECT`;
the operator runs it under their own Supabase login. Terms and credits reach
readers only when a person ticks, links and publishes them in Admin.

## Commands

```sh
cd scripts/tag-exhibition
work="$(mktemp -d)"   # keep packets and drafts outside the repository

# 1. Read the packets: the queue (default 10, at most 50), or named exhibitions.
deno task -q tag candidates-sql --limit 10 > "$work/candidates.sql"
deno task -q tag candidates-sql --exhibition <id> [--exhibition <id>...] > "$work/candidates.sql"
supabase db query --linked --project-ref '<ref>' --output-format json \
  -f "$work/candidates.sql" < /dev/null > "$work/candidates.json"

# 2. Print the brief the session drafts from, then write "$work/drafts.json".
deno task -q tag brief "$work/candidates.json"

# 3. Check every draft; exit 1 names each refused term, sentence or artist.
deno task -q tag check "$work/candidates.json" "$work/drafts.json"

# 4. Print the Admin checklist for the checked drafts.
deno task -q tag sheet "$work/candidates.json" "$work/drafts.json"
```

Exit codes: `0` done; `1` a draft was refused (one line per problem on stdout:
`<id>\t<code>\t<subject>`); `2` wrong arguments (usage on stderr); `3` an input
file could not be read, is not this tool's query output, or holds nothing to
apply (reason on stderr). For a local stack, run the SQL with `psql -At` in the
database container; the tool reads the bare JSON object as well as the CLI's
envelope.

The query needs the provenance migration
(`20261028100000_art_metadata_provenance.sql`: term and credit `grounding`,
`content_private.exhibition_metadata_is_stale`). On a target without it, the
query fails with a missing column or function: stop, applying migrations is a
separate release step.

## What the query returns

One row, one column `tagging`:

```json
{
  "format_version": 1,
  "vocabulary": [
    {
      "id": "medium:video",
      "category": "medium",
      "name_ko": "비디오",
      "name_en": "Video"
    }
  ],
  "candidates": [{
    "exhibition_id": "…",
    "found": true,
    "metadata_stale": false,
    "has_open_draft": false,
    "packet": {
      "name_ko": "…",
      "name_en": "…",
      "venue_name_ko": "…",
      "venue_name_en": "…",
      "opening_date": "YYYY-MM-DD",
      "closing_date": "YYYY-MM-DD",
      "description_ko": "…",
      "description_en": "…",
      "credits_ko": "…",
      "credits_en": "…"
    },
    "terms": [
      {
        "id": "…",
        "category": "…",
        "name_ko": "…",
        "name_en": "…",
        "grounding": null
      }
    ],
    "credits": [
      {
        "id": "<uuid or null>",
        "name_ko": "…",
        "name_en": "…",
        "grounding": null
      }
    ],
    "artist_matches": [{ "id": "<uuid>", "name_ko": "…", "name_en": "…" }]
  }]
}
```

- **Queue** (no `--exhibition`): published exhibitions whose closing date is
  today or later in Seoul and whose published version has fewer than three art
  terms (visit terms not counted) or stale metadata: the exhibitions the
  coverage gate (`scripts/catalogue-coverage`, `terms_3plus`) does not count,
  plus any whose open draft is stale. Running shows come first, then upcoming,
  soonest closing first.
- **Named** (`--exhibition`, up to 50): exactly those ids in that order,
  whatever their state; `found` is false when an id is not in the published
  catalogue.
- `packet` is the catalogue row readers see. `terms` and `credits` are the
  published version's, with their staff-only grounding.
- `vocabulary` is the active art vocabulary. Visit terms (`visit:*`) are left
  out: they state a fact an editor confirmed (spec 091), never something read
  from a description.
- `artist_matches` is the artist search for that exhibition: active canonical
  artists already credited on it or whose Korean name (2+ characters) or English
  name (3+ characters) appears, case-insensitively, in its names, descriptions
  or credits, credited first, at most 50. It is the reverse of Admin's artist
  search (`search_artists_impl` looks up one typed name), so `check` can verify
  every `existingId` offline.

## Draft and checks

`drafts.json` is one object keyed by exhibition id:

```json
{
  "<exhibition id>": {
    "terms": [
      {
        "id": "theme:memory",
        "grounding": "<sentence copied from description_ko or description_en>"
      }
    ],
    "artists": [
      {
        "existingId": "<uuid from artist_matches>",
        "grounding": "<line from credits or description>"
      },
      {
        "nameKo": "…",
        "nameEn": "…",
        "grounding": "<line from credits or description>"
      }
    ]
  }
}
```

A draft is the whole set of art terms the exhibition should carry, current ones
included. Grounding is compared after Unicode NFC and with every run of
whitespace (line breaks included) read as one space; otherwise it must be
verbatim.

| Code                      | Refused when                                                                                                                                                 |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `term_unknown`            | the id is not in the active art vocabulary (visit terms included)                                                                                            |
| `term_repeated`           | the same term twice                                                                                                                                          |
| `term_count`              | fewer than 3 or more than 6 terms (subject: the count)                                                                                                       |
| `category_limit`          | more than 2 terms in one category                                                                                                                            |
| `grounding_missing`       | a term or new artist has no grounding                                                                                                                        |
| `grounding_too_short`     | a term grounding under 6 characters: a sentence, not a word                                                                                                  |
| `grounding_too_long`      | over 400 characters as pasted (the database counts them before normalising)                                                                                  |
| `grounding_not_in_text`   | a term grounding is not in `description_ko`/`description_en`, or an artist grounding is not in the descriptions or credits                                   |
| `artist_name_missing`     | a new artist lacks the Korean or the English name                                                                                                            |
| `artist_name_not_in_text` | a new artist's name is not in the exhibition's names, descriptions or credits (no invented translation)                                                      |
| `artist_exists`           | a new artist has the name of an artist in `artist_matches` (subject: that id); link it if it is the same person, otherwise report it for staff to tell apart |
| `artist_not_in_search`    | an `existingId` is not in that exhibition's `artist_matches`                                                                                                 |
| `artist_repeated`         | the same existing artist twice                                                                                                                               |
| `parse_failed`            | the entry is not the draft shape (unknown keys, an artist with both `existingId` and names)                                                                  |
| `not_found`               | a draft for an id not in the published catalogue                                                                                                             |
| `no_candidate`            | a draft for an id the query did not return                                                                                                                   |

`no_draft` marks an exhibition left undrafted; it does not fail the run. `sheet`
prints the verdicts instead of the checklist while any written draft fails.

## The sheet

One section per drafted exhibition: the Korean title, English title, id and
dates; a note when the metadata is stale or an open draft is waiting; each term
as a checkbox with its Korean and English labels and its grounding underneath,
marked when already ticked; current art terms the draft leaves out, to untick
only if the text no longer supports them; visit terms left as they are; each
artist as "Link existing" (with id) or "Create new" (both names) with its
grounding; then save, Confirm terms / 설명어 확인 and Publish / 게시.

## Verify

```sh
deno task test    # tag_test.ts, network-free
deno task check   # fmt --check, lint, type check
```

Use the Deno version in the root `.tool-versions`. CI runs both in
`.github/workflows/product-surfaces.yml` (job "Desk tool / tag-exhibition").
