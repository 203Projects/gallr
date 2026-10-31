# Data model: Taste listening and reliable catalogue data

## On the device (shared/commonMain, never transmitted)

### TasteSeed
| Field | Type | Rule |
|---|---|---|
| termIds | Set<String> | vocabulary term ids the visitor picked; unknown ids are kept and ignored by ranking |
| exhibitionIds | Set<String> | picked covers; excluded from recommendations like saves |

- Valid to start only when `termIds.size + exhibitionIds.size >= 3` (sheet rule); an edited seed may drop below 3
  (it still ranks; the sheet asks for 3 only when starting from empty).
- `TasteSeed.EMPTY` when nothing is stored.

### TasteDismissal (one per dismissed exhibition)
| Field | Type | Rule |
|---|---|---|
| exhibitionId | String | unique among standing records |
| closingDate | LocalDate | record expires the day after |
| termIds | Set<String> | reviewed term ids carried at dismissal; a per-term undo removes ids |
| artistIds | Set<String> | credited artist ids carried at dismissal |

### TasteDismissals
- A list of records. Derived, never stored: `count(id)` = number of standing records containing the id; `hidden` =
  record exhibition ids; `dismissedTermIds` = term ids with count ≥ 1 (excluded from chips, shown in the sheet).
- Transitions:
  - `dismiss(exhibition)`: add a record (replace if one exists for the exhibition).
  - `remove(exhibitionIds)`: the snackbar undo, or a lift (a save, visit or follow of it or its gallery made after
    the hide; `liftedDismissals` in `shared/taste/TasteLift.kt` picks the ids): remove the records.
  - `releaseTerm(termId)`: remove the id from every record; records stay (hides stand).
  - `expire(today)`: drop records with `closingDate < today`.
- Invariant: no count below zero (counts are derived).

### TastePickState
| Field | Type |
|---|---|
| seed | TasteSeed |
| skipped | Boolean |
| dismissals | TasteDismissals |

### Recommendation additions
- `RecommendationSignalSource.CHOSEN`.
- `RecommendationEvidence.ChosenTerm(term: ArtTerm)`.
- `RecommendationContext.tasteSeed`, `RecommendationContext.dismissals` (defaults empty, so existing callers and tests
  are unchanged).
- `ExhibitionRecommendation.hasPersonalEvidence: Boolean`.

### TastePickChoices
| Field | Type | Rule |
|---|---|---|
| terms | List<ArtTerm> | 4–10 reviewed terms held by ≥3 running/upcoming exhibitions; ≤3 per category; empty if fewer than 4 qualify |
| covers | List<Exhibition> | first 6 featured exhibitions in Home order that are still running or upcoming |

### WeeklyOpeningHours (change)
- `OpeningHoursCompleteness.CONFIRMED` added; built from the catalogue's `hours_weekly` when present.
  `isVerified` = `CONFIRMED` or `COMPLETE`.

## In Supabase (content schema, staff-written, public read through the catalogue)

### content.art_taxonomy_terms (rows added)
- About 25 new rows in existing categories, new stable ids, next free `sort_order` per category. No id or label of an
  existing row changes.

### content.exhibition_version_terms (column added)
| Column | Type | Rule |
|---|---|---|
| grounding | text null | ≤ 400 chars; the supporting sentence; copied on clone |

`created_by` (existing) = who applied it.

### content.exhibition_version_artists (column added)
| Column | Type | Rule |
|---|---|---|
| grounding | text null | ≤ 400 chars; the source line naming the artist; copied on clone |

### content.exhibition_versions (columns added)
| Column | Type | Rule |
|---|---|---|
| metadata_source_hash | text null | SHA-256 of description_ko, description_en, opening_date, closing_date at confirmation |
| metadata_reviewed_at | timestamptz null | set by confirm; copied on clone |
| hours_weekly | jsonb null | `{ "MONDAY": {"opens":"10:00","closes":"18:00"} \| null, … }`, seven keys |
| hours_confirmed_by | uuid null | staff user |
| hours_confirmed_at | timestamptz null | |
| hours_source_hash | text null | SHA-256 of `hours` text at confirmation |

- Stale metadata: version has ≥1 term or credit and `metadata_source_hash` is null or differs from the current hash.
- Stale hours: `hours_weekly` not null and `hours_source_hash` differs from the hash of the current `hours`.

### content.artists (constraint added after merges)
- Unique partial indexes on `normalize_artist_name(name_ko)` and `normalize_artist_name(name_en)` where
  `archived_at is null`.
- Merge: credits re-pointed to the survivor; merged row archived; `content.audit_log` row
  action `artist.merged` (the existing dotted naming) with survivor, merged, reason and the moved and removed credit counts.

### public.exhibition_catalog_v2 (column added)
| Column | Type | Rule |
|---|---|---|
| hours_weekly | jsonb null | projected only while not stale; old clients ignore it (`ignoreUnknownKeys`) |

`artists` and `art_terms` stay as they are (grounding is staff-only and not projected).

## Coverage report (per release, not stored)
Running and upcoming exhibitions: total; with ≥3 reviewed terms; published >7 days ago with ≥3 terms; with credits;
with confirmed or complete hours; with coordinates; with current dates; with English description; stale metadata;
stale hours; duplicate-artist groups. Floor pass/fail.
