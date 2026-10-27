# Research: Taste listening and reliable catalogue data

Feature: [spec.md](spec.md) · Design: [docs/designs/2026-10-26-mobile-taste-ai-design.md](../../docs/designs/2026-10-26-mobile-taste-ai-design.md)

All facts below come from a static read of the repository on 2026-10-26 (branch `101-taste-listening`). Live
production counts were not read: the permission layer refused a read-only production query in this session, so
coverage figures are from the 2026-10-02 catalogue fixture.

## R1. Where visitor-entered taste lives

- **Decision**: a new `TastePreferenceRepository` interface in `shared/.../repository` with a
  `DataStoreTastePreferenceRepository` implementation in the same app preferences DataStore. Three private keys:
  `taste_seed` (JSON: term ids and exhibition ids, picked-at date), `taste_pick_skipped` (boolean) and
  `taste_dismissals` (JSON list of dismissal records). Exposes `Flow`s plus suspending writes, like
  `DataStoreRouteVisitTimeRepository`; a malformed value decodes to empty and logs `taste_preference_read_failed`.
- **Rationale**: the existing pattern (private `UPPER_SNAKE_CASE` keys, plain flows, `AppLog.tagged`) already holds
  per-device state such as `route_visit_time` and the account nudge. `AuthRepositoryImpl.signOut` does not clear the
  preferences DataStore, so seeds and dismissals survive sign-out and account switch as FR-003 requires; a reinstall
  clears them.
- **Alternatives**: a Supabase table synced per account (rejected: spec forbids sending taste off the device);
  in-memory only, as Codex's prototype suggested (rejected: a pick that vanishes on restart is not a pick; FR-027
  amends spec 088 FR-018 to permit explicit visitor-entered preferences).

## R2. How picks and dismissals enter the ranker

- **Decision**: extend `RecommendationContext` with `tasteSeed: TasteSeed = TasteSeed.EMPTY` and
  `dismissals: TasteDismissals = TasteDismissals.EMPTY`. Add `RecommendationSignalSource.CHOSEN` and one new evidence
  type `RecommendationEvidence.ChosenTerm(term: ArtTerm)`. A chosen cover reuses `ArtistMatch`, `ArtTermMatch` and
  `TextSimilarity` with `source = CHOSEN`, so the existing anchor machinery (noisy-OR across anchors, strongest anchor
  named) applies unchanged. Chosen covers join the excluded set like saves.
- **Weights** (constants in `LocalExhibitionRecommender`, next to the existing ones):
  `CHOSEN_TERM 0.30` (reviewed term), `CHOSEN_DETECTED_TERM 0.08` (rarity-scaled like `SAVED_DETECTED_TERM`),
  chosen-cover weights at 0.6 × saved: artist 0.30, title artist 0.24, art term 0.21, detected term 0.05,
  text 0.12. `MAX_SCORE` gains `CHOSEN_TERM` so quantised scores stay inside 0..10000 bp; a constant divisor change
  scales every score equally, so existing relative order is unaffected (asserted by the existing ordering tests).
- **Dismissal penalty**: computed after the positive score as today's `score` is formed and before quantisation:
  `penalty(id) = min(0.25 × count(id), 0.5 × BASE)`, BASE 0.35 for a term id and 0.50 for an artist id; summed over
  the candidate's reviewed term ids and credited artist ids; `score = max(0, positive − penalty)`. The tier key
  (`hasPersonalEvidence`) is computed from evidence before the penalty, so a penalised candidate keeps its tier.
  The zero-evidence drop is decided on evidence presence, not on score.
- **Hidden exhibitions** (standing dismissal records) join the excluded set for `recommend()` only; route ranking
  ignores dismissals (routes are out of scope and hidden for 1.14/1.15).
- **Reason order**: `MAX_EVIDENCE` stays 2. Tier order becomes CHOSEN, SAVED, VISITED (source), then the existing
  kind order artist, term, text, then followed gallery and editorial kinds; within a tier the larger contribution
  wins, as today.
- **Alternatives**: a separate "seed recommender" merged with the history recommender (rejected: two rankers would
  disagree on ties and reasons); storing a derived profile vector (forbidden by spec 073 and FR-027).

## R3. Which cards may sit under a taste heading

- **Decision**: add `ExhibitionRecommendation.hasPersonalEvidence` (public, in `shared`) using the same predicate the
  ranker's tier uses (any evidence with a CHOSEN, SAVED or VISITED source, `ChosenTerm`, or `FollowedGallery`).
  `HomePresentation` filters the Home rail to those cards; `RecommendationPresentation` keeps its two sections.
  `homeLead` leads with 내 취향 when at least one personal card exists, else the prompt card (`TastePrompt`) replaces
  the rail and the featured banner leads.
- **Rationale**: the predicate is a business rule reused by Home, 모두 보기 and the eval; it belongs in `shared`
  (Principle VI).

## R4. Taste pick sheet content and gate

- **Decision**: a pure function `tastePickChoices(catalogue, featured, today)` in `shared/.../taste/TastePick.kt`
  returns up to ten term chips (reviewed terms carried by ≥3 running or upcoming exhibitions, ranked by count with at
  most three per category, at least four required else none) and the first six featured exhibitions in Home order.
  A pure function `shouldOfferTastePick(basisEmpty, seed, skipped, blockers)` in
  `composeApp/.../viewmodel/TastePickGate.kt` decides when the sheet opens on its own; blockers are the
  update-required screen, a visible update nudge and an open `Account` destination.
- **Rationale**: the choice set is catalogue logic (shared); the gate depends on app screens (composeApp).
- **Alternatives**: a fixed hand-curated chip list (rejected: chips must exist in the running catalogue, or a pick
  produces nothing).

## R5. Reason on the exhibition page

- **Decision**: `AppDestination.ExhibitionDetail` gains `tasteReasons: List<RecommendationEvidence> = emptyList()`;
  For You entry points pass the card's evidence, every other entry point passes nothing. The detail screen formats
  it with the same `RecommendationPresentation` formatter under the title (`labelSmall`, `onSurfaceVariant`).
- **Rationale**: evidence is immutable data already held by the card; no re-ranking on the detail screen, no
  analytics change (FR-028). `DiscoveryAttribution` is analytics-only and stays unchanged.

## R6. Vocabulary expansion and old builds

- **Decision**: one additive migration inserting about 25 rows into `content.art_taxonomy_terms` with stable ids in
  the existing categories (`medium:ceramics`, `style:dansaekhwa`, `theme:body`, `mood:dark`, …; final list from the
  owner). `unique (category, sort_order)` means new rows take the next sort orders. `ArtTermVocabulary` gains Korean
  substrings and English whole words for the same ids, with ambiguous words left out as today.
- **Old builds**: the catalogue sends each term with its category and both labels (`ExhibitionArtMetadataDto`), so a
  1.14 client shows a new term's label and matches it by id without knowing it; only keyword detection of the new
  term in free text needs 1.15. This corrects the design doc's worry that 1.14 cannot label new ids.
- **Ordering constraint** (TODOS.md): deploy Admin and the gallery workspace before applying, because their editors
  reject unknown categories; new terms use existing categories, so this is a precaution, not a blocker.

## R7. Provenance on terms and credits

- **Decision**: add nullable `grounding text` (≤ 400 chars) to `content.exhibition_version_terms` and
  `content.exhibition_version_artists`. `created_by` already records who applied the row. `apply_exhibition_art_metadata`
  accepts an optional `grounding` per term and per credit; `clone_exhibition_art_metadata` copies it to the next
  version. Admin's Art section shows the grounding under each ticked term and credit and lets staff paste it.
- **Rationale**: the smallest change that records "what supports it and who applied it" without a new table.
- **Alternatives**: a separate provenance table keyed by (version, term) (rejected: two writes per tick, no gain).

## R8. Freshness: stale metadata after an exhibition edit

- **Decision**: mirror curator notes (`curator_note_version_is_stale`, migration 20261013110000). Add to
  `content.exhibition_versions`: `metadata_source_hash text` and `metadata_reviewed_at timestamptz`. The source hash is
  SHA-256 over `description_ko`, `description_en`, `opening_date`, `closing_date` (and `hours` for confirmed hours,
  R10). `admin_confirm_exhibition_metadata(exhibition_id, expected_updated_at)` stamps the current hash on the working
  version; `clone` carries hash and timestamp forward; `content_private.exhibition_metadata_is_stale(version_id)` is
  true when the version has terms or credits and its hash differs from the current source hash. A staff RPC
  `admin_list_stale_exhibition_metadata()` lists them for Admin's queue. Readers are unaffected (FR-021).
- **Rationale**: one proven pattern instead of a new one; no trigger needed because publishing already creates a
  version.

## R9. Duplicate canonical artists

- **Decision**: `content_private.normalize_artist_name(text)` (NFKC, lower, collapse whitespace, strip punctuation
  and spaces between Hangul syllables). Two migrations:
  1. `admin_find_duplicate_artists()` (report) and `admin_merge_artists(survivor_id, merged_id, reason)`: re-points
     `exhibition_version_artists.artist_id` on draft and published versions to the survivor, leaves the snapshot labels
     as they are (published credits are snapshots by design), archives the merged artist, writes `content.audit_log`.
     Refused when the merged artist is the survivor, either is archived, or a credit would duplicate the survivor on
     the same version (then the duplicate row is removed and reported).
  2. After the owner merges what the report lists: unique partial indexes on `normalize_artist_name(name_ko)` and
     `normalize_artist_name(name_en)` where `archived_at is null`. The migration aborts with the duplicate list if any
     remain, so it cannot half-apply. `admin_create_artist` / `owner_create_artist` catch the unique violation and
     raise `artist_duplicate` with the existing id, which Admin and Gallery turn into "Link to existing".
- **Rationale**: the backfill README states "The directory has no uniqueness constraint on bilingual names";
  enforcing it in the database is the only rule that holds for Admin, Gallery and SQL alike.
- **Alternatives**: client-side search-before-create only (exists in spec 086; does not prevent races or SQL).

## R10. Confirmed weekly hours

- **Decision**: add to `content.exhibition_versions` `hours_weekly jsonb` (seven optional `{opens, closes}` entries),
  `hours_confirmed_by uuid`, `hours_confirmed_at timestamptz`, `hours_source_hash text` (hash of the free-text `hours`
  at confirmation). Project `hours_weekly` into `public.exhibition_catalog_v2` only while its hash matches the current
  `hours` text. Admin proposes a reading with a TypeScript port of the app's `OpeningHoursParser` placed in
  `admin/src/hours/`, held to parity by the 21 golden cases in
  `specs/088-discovery-accuracy-fixes/contracts/opening-hours-grammar.md`; staff confirm or correct seven rows.
  In the app, `WeeklyOpeningHours` gains a `CONFIRMED` completeness used when the catalogue carries `hours_weekly`;
  `isVerified` is true for `CONFIRMED` and, as today, for `COMPLETE` parses.
- **Finding**: the fixture's hand-checked expectations show the parser already reads most listings as `COMPLETE`
  (treated as verified today); confirmation adds value for `PARTIAL` and unreadable listings, for parser mistakes,
  and by going stale when the text changes. SC-006 is measured on confirmed venues only.
- **Alternatives**: hours drafted by the desk tool and typed into Admin (rejected: every new exhibition would need a
  desk run; FR-023 asks Admin to propose).

## R11. Coverage and freshness check

- **Decision**: `scripts/catalogue-coverage/coverage.sql` (read-only, public catalogue plus the staff stale lists) and
  `coverage.mjs` (Node, no dependencies) that turns `supabase db query --linked` JSON into a Markdown table for the
  release PR and exits non-zero below the floor. Floor (spec Assumptions): 100% of running and upcoming exhibitions
  published more than seven days before the release have ≥3 reviewed terms, and ≥90% overall; credits where the
  source names an artist, confirmed or complete hours, coordinates, current dates and an English description are
  reported, not enforced, in 1.15. The owner runs it (agent production reads are blocked).
- **Rationale**: no CI secret for production; the release PR is the existing checkpoint (CLAUDE.md release workflow).

## R12. Desk tool `tag-exhibition`

- **Decision**: a skill `.claude/skills/tag-exhibition/SKILL.md` plus a Deno CLI `scripts/tag-exhibition/` modelled on
  `draft-curator-notes/desk_cli.ts`: `candidates-sql` (running and upcoming exhibitions missing terms or stale),
  `brief` (vocabulary, rules and one packet per exhibition), `check` (refuses unknown term ids, more than six or fewer
  than three terms, a grounding sentence that is not a substring of the description, a new artist without both
  names), and `sheet` (a per-exhibition Markdown checklist of what to tick and paste in Admin). It never writes to a
  database. Writing happens in the Claude session, as for notes; no API key.
- **Effort estimate**: about two minutes per exhibition in Admin once drafted (search artist, tick terms, paste
  groundings, save, publish); about 80 exhibitions is three hours of the owner's time, split over a week.
- **Alternatives**: a store step that writes drafts for Admin to apply in one click (deferred: needs a draft table and
  review UI; revisit if the weekly tagging load exceeds an hour).

## R13. Offline accuracy eval

- **Decision**: `shared/src/commonTest/.../recommendation/TasteEvalTest.kt` with `TasteEvalScenarios.kt` (fifteen or
  more owner-labelled scenarios) over a regenerated catalogue fixture. The fixture is regenerated with the existing
  generator (`specs/088-discovery-accuracy-fixes/fixture/to-kotlin.mjs` from `export.sql`) after the tagging pass, into
  a new `TaggedCatalogueFixture.kt` (reference date of the export), leaving the 2026-10-02 fixture and its tests intact.
  The dismissal and lift arithmetic is asserted in a separate `TasteDismissalTest.kt` over small hand-built
  catalogues, so it does not depend on labels.
- **Policy on a miss** (FR-025): the test fails; the owner relabels a wrong scenario or records a lower bar in the
  scenarios file with a dated comment; weights are not tuned to a label set without the owner's review.

## R14. Loading skeleton

- **Decision**: reuse the existing skeleton primitives (as used by `PublicRoutesSection`) to draw rail-sized
  placeholders (184 dp portrait cards, 16 dp shape) in the 내 취향 slot while `RecommendationUiState.Loading`, and
  skeleton rows on the 모두 보기 screen. Heights equal the final layout so content below does not move.

## R15. Contract amendments and records

- Spec 088 FR-018 and spec 073 US1 criterion 4 are amended by this spec's FR-027; completed specs are history and are
  not edited. DESIGN.md gains entries for the taste sheet, the prompt card, the 덜 보기 menu and the detail reason
  line, and a decisions-log row for 2026-10-26. CHANGELOG gets the 1.15 entries at release.
