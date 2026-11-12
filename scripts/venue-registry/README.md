# Venue registry

`venues.json` is the tracked list of every gallery and museum gallr follows, with each venue's official
website, exhibitions page and social channels. Staff sweep these channels for new exhibitions; the list
lives in the repository so it survives between sweeps instead of being rebuilt each time.

The database stores venue names and addresses but no links, and nothing here is read by the app or the
website. `venue-registry.mjs` is dependency-free Node 22 that validates the file, compares it with the
catalogue and prints a sweep checklist. It never touches a database.

## What a venue entry holds

| Key | Meaning |
|---|---|
| `name_ko` | Exactly the venue name used in the catalogue (`venue_name_ko`); the key that matches the two |
| `name_en` | The venue's own English name, or `""` |
| `kind` | `museum`, `gallery`, `art_space`, `foundation` or `other` (see below) |
| `city_ko`, `region_ko` | As in Admin: `서울` / `종로구`, `경북` / `경주시` |
| `website`, `exhibitions_page` | Official site and the page that lists current and upcoming shows (`https://`, or `http://` when the site has no working https) |
| `instagram`, `facebook`, `youtube`, `naver_blog`, `x` | Official accounts only, as full URLs, `null` when none is confirmed |
| `other_links` | Anything else official (a ticket shop, an English site, a branch page) |
| `status` | `active`, `unknown` (no channel confirmed), `moved` or `closed` (left out of sweeps) |
| `last_checked` | The date someone last swept this venue's channels, or `null` |
| `notes` | Duplicates, address doubts, renames; `""` when nothing is odd |

A channel counts as official only when the venue runs it: the links in the venue's own site header or footer,
or the account the venue names. Ocula, Artsy, Naver Place, news and blogs can point to it but are not it.
Never record a guessed handle.

**`kind: museum` marks the venues whose exhibitions carry the `🏛️ ` prefix** in the catalogue (the
`add-exhibition` skill). Changing a venue to or from `museum` is the owner's decision.

## Add a venue

When an exhibition is added at a venue the registry does not have, add the venue in the same change: copy
an entry, fill every key, keep the list sorted by `name_ko`, and run `check`.

```sh
node scripts/venue-registry/venue-registry.mjs check
```

## Sweep for new exhibitions

```sh
node scripts/venue-registry/venue-registry.mjs sweep --limit 30            # least recently checked first
node scripts/venue-registry/venue-registry.mjs sweep --kind museum
node scripts/venue-registry/venue-registry.mjs sweep --city 부산
```

Open each venue's exhibitions page, then Instagram (many galleries announce only there). Compare what is
running or announced with the catalogue in Admin. New shows go through the `add-exhibition` skill, which
asks before every step that cannot be undone. Then record the sweep and commit the file:

```sh
node scripts/venue-registry/venue-registry.mjs mark-checked --date 2026-11-04 우양미술관 갤러리현대
```

Fix a link that has moved, and set `status` to `closed` when a venue has closed, in the same commit.

## Keep it in step with the catalogue (owner)

Production reads are the owner's step. `diff` lists catalogue venues that have no entry (exit 1) and tracked
venues with no exhibition yet (for information).

```sh
supabase db query --linked --project-ref <ref> --output-format json \
  -f scripts/venue-registry/catalogue-venues.sql < /dev/null \
  | node scripts/venue-registry/venue-registry.mjs diff
```

Exit codes for every command: `0` fine, `1` catalogue venues missing from the registry, `2` malformed
file, input or arguments.

## Tests

Network-free; run with the pinned Node from `.node-version`. They also validate the committed `venues.json`.

```sh
node --test scripts/venue-registry/venue-registry.test.mjs
```
