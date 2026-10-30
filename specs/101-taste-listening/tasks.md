# Tasks: Taste listening and reliable catalogue data

**Input**: Design documents from `/specs/101-taste-listening/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R15), data-model.md, contracts/shared-taste-api.md,
contracts/taste-ui.md, contracts/catalogue-reliability.md, quickstart.md

**Tests**: Required (constitution II, Test-First). Each test task is written and seen failing before the
implementation task that follows it.

**Organization**: by user story. US1–US5 are P1; US6 is P2; US7 is P3. US4 and US5 (catalogue data, no app release)
do not depend on any app code and can run beside US1–US3.

Paths: `shared/` = `shared/src/commonMain/kotlin/com/gallr/shared/`, `sharedTest/` =
`shared/src/commonTest/kotlin/com/gallr/shared/`, `app/` = `composeApp/src/commonMain/kotlin/com/gallr/app/`,
`appTest/` = `composeApp/src/commonTest/kotlin/com/gallr/app/`, `iosUiTest/` =
`composeApp/src/iosTest/kotlin/com/gallr/app/`, `admin/` = `admin/src/`, `gallery/` = `gallery/src/`,
`migrations/` = `supabase/migrations/`, `pgtap/` = `supabase/tests/database/`.

In a worktree, prefix Gradle with `ANDROID_HOME=$HOME/Library/Android/sdk`. Migration timestamps below are
placeholders `2026MMDDhhmmss`; pick real ones that sort after the newest migration on `develop` when the task runs.

## Format: `[ID] [P?] [Story?] Description`

## Baseline (2026-10-26, branch `101-taste-listening`, before any change)

| Gate | Result |
|---|---|
| `shared:testAndroidHostTest` | 715 tests, 0 failures |
| `composeApp:testAndroidHostTest` | 693 tests, 0 failures |
| Migration lineage validator | PASS (116 files, 7 recovered versions) |
| Admin `npm test` (Node 22.23.1) | 440 tests pass; 5 files fail to load: the worktree's `admin/node_modules` lacks `fflate` and `uqr` (stale install, pre-existing, not a code fault) |
| Gallery `npm test` (Node 22.23.1) | 131 tests, 14 files, all pass |

---

## Phase 1: Setup

- [X] T001 Record the baseline on this branch: `./gradlew shared:testAndroidHostTest composeApp:testAndroidHostTest`
  test counts, `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs`, `(cd admin && npm test)` and
  `(cd gallery && npm test)`; write the counts at the top of this file under "Baseline" (no code change).
- [X] T002 [P] Create empty packages and test folders named in plan.md: `shared/taste/TastePick.kt` (file header only),
  `sharedTest/taste/`, `scripts/tag-exhibition/`, `scripts/catalogue-coverage/`, `admin/hours/`.

---

## Phase 2: Foundational (blocks US1, US2, US3, US6)

**Purpose**: the shared taste types, the ranker's new inputs with no behaviour change, and the device repository
(research R1, R2; contracts/shared-taste-api.md).

- [X] T003 [P] Write `sharedTest/taste/TasteDismissalsTest.kt`: derived counts from records, `dismiss` replaces a
  record for the same exhibition, `undo`/`lift` remove it, `releaseTerm` removes the id from every record and keeps
  the records, `expire(today)` drops records whose closing date is before today, no count below zero after
  `releaseTerm` then `lift`, `hidden` and `dismissedTermIds` sets.
- [X] T004 Implement `TasteSeed`, `TasteDismissal`, `TasteDismissals` (with the transitions in data-model.md) and
  `TastePickState` in `shared/taste/TastePick.kt`; make T003 pass.
- [X] T005 [P] Write `sharedTest/repository/DataStoreTastePreferenceRepositoryTest.kt` against an in-memory
  preferences DataStore: round-trip of seed, skipped flag and dismissals; `saveSeed` clears `skipped`; a malformed
  stored value decodes to empty and logs `taste_preference_read_failed`; keys are exactly `taste_seed`,
  `taste_pick_skipped`, `taste_dismissals`.
- [X] T006 Add `TastePreferenceRepository` in `shared/repository/TastePreferenceRepository.kt` and
  `DataStoreTastePreferenceRepository` in `shared/repository/DataStoreTastePreferenceRepository.kt` (JSON via
  kotlinx.serialization in string preferences, `AppLog.tagged("TastePreferences")`, exception type only); make T005
  pass.
- [X] T007 Add `RecommendationSignalSource.CHOSEN`, `RecommendationEvidence.ChosenTerm(term)`,
  `RecommendationContext.tasteSeed` and `.dismissals` (both defaulting to empty) and the public extension
  `ExhibitionRecommendation.hasPersonalEvidence` in `shared/recommendation/ExhibitionRecommendation.kt`; handle the new
  cases as no-ops in `shared/recommendation/LocalExhibitionRecommender.kt` and in every `when` over evidence in
  `app/ui/discovery/RecommendationPresentation.kt` (temporary plain label) so the build is green.
- [X] T008 Run `./gradlew shared:allTests composeApp:testAndroidHostTest` and confirm every pre-existing
  recommender, fixture and presentation test still passes unchanged (contract guarantee 1).
- [X] T009 Wire `DataStoreTastePreferenceRepository` at the composition root in `app/App.kt` (same DataStore as
  `DataStoreRouteVisitTimeRepository`) and pass it to `LocalDiscoveryViewModel` in
  `app/viewmodel/LocalDiscoveryViewModel.kt`, which combines `state` into its inputs and fills
  `RecommendationContext.tasteSeed`/`dismissals` (still no ranking effect until US1/US3).

**Checkpoint**: shared types, repository and plumbing exist; behaviour is unchanged.

---

## Phase 3: User Story 1 - A visitor tells gallr their taste on first open (Priority: P1) 🎯 MVP

**Goal**: first-open sheet, picks stored on the device, picks rank as `CHOSEN`, prompt card instead of editorial cards
under a taste heading, chips open the sheet.

**Independent Test**: fresh install → sheet → three picks → 내 취향 rail whose cards name a pick; reinstall → skip →
prompt card, no sheet on next open (spec US1).

### Tests for User Story 1

- [X] T010 [P] [US1] Write `sharedTest/recommendation/ChosenAnchorTest.kt`: a chosen reviewed term yields
  `ChosenTerm` with contribution 0.30; a chosen detected term yields 0.08 × rarity and only above the existing reason
  threshold; a chosen cover is excluded and contributes artist 0.30, title artist 0.24, art term 0.21, detected 0.05,
  text 0.12 with source `CHOSEN`; a seed alone makes `hasPersonalEvidence` true; determinism and input-order
  independence with a seed.
- [X] T011 [P] [US1] Write `sharedTest/taste/TastePickChoicesTest.kt` for `tastePickChoices`: only reviewed terms on
  ≥3 running or upcoming exhibitions; ordered by count, then category, then vocabulary order; ≤3 per category; ≤10;
  empty when fewer than 4 qualify; covers are the first 6 featured still running or upcoming.
- [X] T012 [P] [US1] Extend `sharedTest/taste/TasteProfileTest.kt`: picked term ids count as anchors for chip
  ranking; `excluded` ids never appear among the four chips.
- [X] T013 [P] [US1] Write `appTest/viewmodel/TastePickGateTest.kt` for `shouldOfferTastePick`: true only with an
  empty basis, no seed and not skipped; false while the update-required screen shows, while the update nudge is
  unanswered, or while the `Account` destination is open.
- [X] T014 [P] [US1] Write `appTest/viewmodel/TastePickViewModelTest.kt`: start disabled below 3 selections with the
  remaining count; `start` saves the seed; `skip` marks skipped once; opening from a chip preselects the current seed;
  `save` replaces the seed (keep a subscriber alive for `WhileSubscribed` state).
- [X] T015 [P] [US1] Extend `appTest/ui/tabs/home/HomePresentationTest.kt`: `homeLead` returns the taste prompt when
  there is no signal or after a skip; the seeded-but-empty prompt when a seed exists and no card is personal; the
  subtitle counts picks first and omits zero counts, in KO and EN.

### Implementation for User Story 1

- [X] T016 [US1] Implement chosen-term and chosen-cover evidence, the `CHOSEN` exclusion and the new weights (and
  `MAX_SCORE` including `CHOSEN_TERM`) in `shared/recommendation/LocalExhibitionRecommender.kt`; make T010 pass.
- [X] T017 [US1] Implement `tastePickChoices` and `TastePickChoices` in `shared/taste/TastePick.kt`; make T011 pass.
- [X] T018 [US1] Add `excluded: Set<String>` and seed-as-anchor counting to `tasteTerms()` in
  `shared/taste/TasteProfile.kt`; make T012 pass.
- [X] T019 [US1] Implement `shouldOfferTastePick` in `app/viewmodel/TastePickGate.kt`; make T013 pass.
- [X] T020 [US1] Implement `TastePickViewModel` in `app/viewmodel/TastePickViewModel.kt` (constructor injection,
  `viewModelFactory { initializer { } }`, private `MutableStateFlow` + `asStateFlow()`); make T014 pass.
- [X] T021 [US1] Add `TastePrompt` leads and pick counts to `app/ui/tabs/home/HomePresentation.kt` and the CHOSEN
  reason strings from contracts/taste-ui.md to `app/ui/discovery/RecommendationPresentation.kt` (replacing T007's
  temporary label); make T015 pass and extend `appTest/ui/discovery/RecommendationPresentationTest.kt` for the four
  new reason strings in KO and EN.
- [X] T022 [P] [US1] Build `TastePickSheet` in `app/ui/discovery/TastePickSheet.kt` per contracts/taste-ui.md (title,
  subline, chip toggles, 3 × 2 cover grid, 건너뛰기, 시작하기 (n) / 저장, disabled count text, toggle-button semantics,
  12 dp and 16 dp shapes from `GallrShapeTokens`, no accent).
- [X] T023 [P] [US1] Build `TastePromptCard` in `app/ui/discovery/TastePromptCard.kt` for both prompt variants (Home
  16 dp card, hairline `outlineVariant`, black primary button).
- [X] T024 [US1] Host the sheet and the prompt in `app/ui/tabs/home/HomeScreen.kt` and `app/App.kt`: open on its own
  when the gate is true, open from the prompt button and from every 내 취향 chip (chips become 44 dp buttons plus
  편집), back dismisses without recording a skip.
- [X] T025 [US1] Write `iosUiTest/ui/discovery/TastePickSheetTest.kt` (`runComposeUiTest` v2): start enables at 3,
  skip records once, a chip reopens with selections, English copy when `AppLanguage.EN`; run
  `./gradlew composeApp:iosSimulatorArm64Test`.

**Checkpoint**: US1 works on its own: picks drive 내 취향, skipped visitors see the prompt.

**Implementation notes (2026-10-26, Phases 1–3)**

- T007 also delivered the final CHOSEN reason strings (T021's string part), so no temporary label shipped.
- The ranker's `MAX_SCORE` normalisation is unchanged (research R2 suggested adding the chosen weight): scores
  already clamp at 10000 bp, and keeping it leaves every existing basis-point assertion valid.
- The repository exposes one `removeDismissals(exhibitionIds)` for both the snackbar undo and the lift, instead of
  separate undo and lift methods (contracts/shared-taste-api.md updated).
- A chosen term's reason reads `좋아하는 {분류}: {용어}` / `YOUR PICK · {CATEGORY}: {TERM}` instead of a hashtag:
  multi-part labels such as "고요함 · 명상적" do not survive a hashtag (contracts/taste-ui.md updated).
- Home's 내 취향 slot is now a section state (`ForYouSection`: hidden, personal picks, or prompt). This already
  makes the Home rail personal-only, which is the Home half of T027/T030 (US2); the 모두 보기 list half and the
  reason order are still US2's.
- The picked-term check `hasPersonalEvidence` moved from composeApp into `shared` (one rule for Home, 모두 보기 and
  the eval).
- Two existing Home tests changed their expectation on purpose: they asserted that a cold start shows editorial
  cards under 내 취향, which is the defect this spec removes.

**Visual check (2026-10-26, Android 16 emulator `gallr_test` and iPhone 17 simulator, iOS 26.5, production catalogue)**

Walked through: first open, picking covers, Home after picks, the edit chip, skip and relaunch, and the prompt card.
Four defects were found and fixed test-first:

1. The sheet read the catalogue once at opening, and on first launch it opened before the catalogue had loaded, so
   it showed no covers. It now waits for both lists to finish loading; Home's own first-run offer waits for a
   successful catalogue load and is not made when fewer than three terms and covers are on offer.
2. Picked covers did not count toward the taste chips, and the chip row (with 편집) only showed when there were
   chip terms, so a visitor who picked only covers had no way back into the sheet. Picked covers now count like
   saves, and the row shows whenever picks exist.
3. With no term chips the covers label said "또는 끌리는 전시를 고르세요" (an alternative to nothing); it now says
   "끌리는 전시를 고르세요".
4. When the third pick removed the "n개 더 골라 주세요" line, the sheet shrank and the covers jumped under the finger;
   the line now keeps its space on a first-run sheet.

Also fixed from the same check: a card's first reason could be editorial ("곧 종료 · 좋아하는 …"); personal reasons
now always come first (the minimal part of T029; the CHOSEN/SAVED/VISITED order is still US2).

Observed and left as is: production carries reviewed terms on too few exhibitions for four or more to qualify, so
the sheet shows covers only until the Stage 0 tagging pass (US4); the term chips appear after it.


---

## Phase 4: User Story 2 - Every pick is explained by the visitor's own choices (Priority: P1)

**Goal**: personal-only Home rail, reason order CHOSEN → SAVED → VISITED, editorial only in its own section, reason on
the exhibition page.

**Independent Test**: one pick and one save → every 내 취향 기반 card's first reason starts with 좋아하는 or 저장한; opening
a card shows the reason under the title; opening from List shows none (spec US2).

### Tests for User Story 2

- [X] T026 [P] [US2] Write `sharedTest/recommendation/ReasonOrderTest.kt`: at most two reasons; a CHOSEN reason
  precedes a SAVED one, which precedes VISITED; within a source the kind order is artist, term, text; each names the
  strongest source of its kind; editorial kinds never precede personal ones.
- [X] T027 [P] [US2] Extend `appTest/ui/tabs/home/HomePresentationTest.kt`: the Home 내 취향 rail keeps only
  `hasPersonalEvidence` cards (1–6) and never pads with editorial cards; with zero personal cards the prompt from
  US1 shows.
- [X] T028 [P] [US2] Extend `appTest/ui/discovery/RecommendationPresentationTest.kt`: 내 취향 기반 holds only personal
  cards; editorial cards appear only under 이번 주 볼 만한 전시 with their own reasons.

### Implementation for User Story 2

- [X] T029 [US2] Change the evidence ordering in `shared/recommendation/LocalExhibitionRecommender.kt` to the tier order
  in research R2; make T026 pass.
- [X] T030 [US2] Filter the Home rail by `hasPersonalEvidence` in `app/ui/tabs/home/HomePresentation.kt` and split
  sections by the same predicate in `app/ui/discovery/RecommendationPresentation.kt`; make T027 and T028 pass.
- [X] T031 [US2] Add `tasteReasons: List<RecommendationEvidence> = emptyList()` to `AppDestination.ExhibitionDetail` in
  `app/AppNavigationState.kt`; pass the card's evidence from the Home 내 취향 rail, the For You banner and
  `app/ui/discovery/RecommendationsScreen.kt`; every other caller keeps the default.
- [X] T032 [US2] Show the joined reasons under the title in `app/ui/detail/ExhibitionDetailScreen.kt` (`labelSmall`,
  `onSurfaceVariant`, included in the title's content description); add `appTest` coverage for the formatter call and
  an `iosUiTest/ui/detail/ExhibitionDetailReasonTest.kt` for present vs absent.

**Checkpoint**: US1 + US2 give a first-run visitor a taste rail that only ever explains itself with their own choices.

---

## Phase 5: User Story 3 - A visitor can correct a pick (Priority: P1)

**Goal**: 덜 보기 with undo, lift and per-term release; capped penalty; dismissed terms leave the chips.

**Independent Test**: dismiss a card → gone next refresh → 되돌리기 restores; dismiss then save → restored; its term
leaves the chips and appears in the sheet's 덜 보기 row; a card sharing the term moves down and stays personal (spec
US3).

### Tests for User Story 3

- [X] T033 [P] [US3] Write `sharedTest/recommendation/TasteDismissalPenaltyTest.kt`: one dismissal lowers a candidate
  carrying the term by exactly 0.175 (artist 0.25); a second dismissal of the same id adds nothing; the penalty is
  summed over a candidate's ids and the score floors at 0; tier and evidence are unchanged; a standing dismissal
  excludes its exhibition from `recommend()`; detected terms and title artists are not penalised; a zero-scored
  personal candidate is kept, not dropped.
- [X] T034 [P] [US3] Extend `appTest/viewmodel/LocalDiscoveryViewModelTest.kt` (or the existing test file for it):
  `dismiss(card)` records the exhibition's reviewed term and artist ids and closing date; `undoDismissal` and a later
  save, visit, follow of the exhibition or follow of its gallery call `liftDismissal`; expired records are dropped on
  refresh.
- [X] T035 [P] [US3] Extend `appTest/viewmodel/TastePickViewModelTest.kt`: the sheet lists dismissed terms in a 덜 보기
  row; `releaseTerm` clears the term and leaves the hides.

### Implementation for User Story 3

- [X] T036 [US3] Apply dismissals in `shared/recommendation/LocalExhibitionRecommender.kt` (penalty after positive
  evidence, floor, tier from pre-penalty evidence, exclusion of hidden ids); make T033 pass.
- [X] T037 [US3] Add `dismiss`, `undoDismissal` and the lift hooks to `app/viewmodel/LocalDiscoveryViewModel.kt`;
  call `liftDismissal` from the save, visit and follow paths in `app/App.kt`; call `expire(today)` on refresh; make
  T034 pass.
- [X] T038 [US3] Add the 덜 보기 row and per-term release to `app/viewmodel/TastePickViewModel.kt` and
  `app/ui/discovery/TastePickSheet.kt`; pass dismissed term ids as `excluded` to `tasteTerms()`; make T035 pass.
- [X] T039 [P] [US3] Build `RecommendationOverflowMenu` in `app/ui/discovery/RecommendationOverflowMenu.kt` (44 dp
  `⋯`, 저장/저장 취소, 덜 보기, 공유) and add it to 내 취향 cards in `app/ui/discovery/RecommendationsScreen.kt` and the Home
  rail in `app/ui/tabs/home/ExhibitionRail.kt` (not on editorial sections).
- [X] T040 [US3] Show the snackbar `숨겼어요 · 종료일까지` / `Hidden until it closes` with 되돌리기 and keep the dismissed
  card at 50% opacity until the next refresh in `app/ui/discovery/RecommendationsScreen.kt` and
  `app/ui/tabs/home/HomeScreen.kt`.
- [X] T041 [US3] Write `iosUiTest/ui/discovery/LessLikeThisTest.kt`: overflow → 덜 보기 → snackbar → 되돌리기 restores;
  run `./gradlew composeApp:iosSimulatorArm64Test`.

**Checkpoint**: the feature listens and can be corrected; Stage 1 app behaviour is complete apart from US6/US7.

**Implementation notes (2026-10-26, Phases 4–5)**

- T027, T028 and T030 landed with Phase 3 (the personal-only rail and the section split). T032's formatter is covered
  by `appTest/ui/discovery/TasteReasonLineTest.kt`; the planned iOS detail-reason UI test was not written.
- The penalty reuses the saved-match weights (`SAVED_ART_TERM_WEIGHT`, `SAVED_ARTIST_WEIGHT`) as its bases.
- Lifting a hide (T037) happens in `LocalDiscoveryViewModel` as saves, visits and follows arrive, not from each
  save, visit and follow path in `App.kt`, so every path lifts it. Only a save, visit or follow made after the hide
  lifts it; that rule is `liftedDismissals` in `shared/taste/TasteLift.kt`. Expiry runs at start and on refresh.
- The ⋯ menu is a `trailingAction` slot beside the save button on `ExhibitionCard` and `HeroExhibitionCard`, and a
  second white circle beside the save button on the Home banner. 공유 opens the exhibition with its share preview
  (`ExhibitionDetail.openShare`) and records no exhibition open or page view.
- Checked on the Android emulator: menu, dimmed card, snackbar, 되돌리기, and the card leaving on refresh. The
  sheet's 덜 보기 row could not be shown there because the hidden production exhibition has no reviewed terms;
  `iosTest/.../TastePickSheetTest` covers it.

---

## Phase 6: User Story 4 - Every running exhibition carries reviewed terms and artists (Priority: P1)

**Goal**: vocabulary expansion, desk tool and skill, tagging pass. No app release needed.

**Independent Test**: desk tool drafts ten exhibitions, `check` passes, staff apply in Admin, publish; a 1.14 visitor
who saved one of them sees the term named in 내 취향 after refresh (spec US4).

### Tests for User Story 4

- [ ] T042 [P] [US4] Write `pgtap/069_art_vocabulary_expansion.test.sql`: every new id exists once with its category
  and both labels; existing ids, labels and sort orders unchanged; `unique (category, sort_order)` holds; re-running
  the insert is a no-op.
- [ ] T043 [P] [US4] Write `scripts/tag-exhibition/tag_test.ts`: `check` refuses an unknown term id, fewer than 3 or
  more than 6 terms, more than 2 per category, a grounding not found verbatim in the packet text, a new artist missing
  either name, and an `existingId` outside the packet's search results; `sheet` prints one checklist per exhibition;
  `candidates-sql` selects running and upcoming exhibitions with fewer than 3 reviewed terms or stale metadata.
- [ ] T044 [P] [US4] Extend `sharedTest/taste/ArtTermDetectorTest.kt` and
  `sharedTest/fixture/DiscoveryFixtureTasteTermsTest.kt` for the new ids' Korean substrings and English whole words;
  the ≥70% detection floor holds.

### Implementation for User Story 4

- [ ] T045 [US4] Owner step: finalise the term list after tagging ten exhibitions (quickstart, Assignment 2); record
  it in `specs/101-taste-listening/vocabulary.md` with ids, categories and labels (blocks T046–T047).
- [ ] T046 [US4] Write `migrations/2026MMDDhhmmss_art_vocabulary_expansion.sql` (idempotent inserts, next free sort
  orders) from `vocabulary.md`; run the lineage validator; make T042 pass on a scratch stack.
- [ ] T047 [US4] Add the new ids to `shared/taste/ArtTermVocabulary.kt` (unambiguous synonyms only); make T044 pass.
- [ ] T048 [US4] Implement `scripts/tag-exhibition/tag.ts` and `tag_cli.ts` with `deno.json` tasks (`test`, `check`,
  `tag`), reusing the packet and fencing approach of `supabase/functions/draft-curator-notes/desk.ts`; make T043 pass.
- [ ] T049 [US4] Write `.claude/skills/tag-exhibition/SKILL.md`: drafting rules (terms only when the text states them,
  grounding verbatim, never invent translations, never write to the database), the CLI steps, and the Admin
  application steps; reference it from `.claude/skills/add-exhibition/SKILL.md` step 6.
- [ ] T050 [US4] Rollout (owner-authorised steps): deploy Admin and Gallery, apply the vocabulary migration to
  staging then production, then run the tagging pass in batches of ten through the skill and Admin; record progress
  in `TODOS.md`.

**Checkpoint**: every running exhibition has reviewed terms; 1.14 readers already benefit.

---

## Phase 7: User Story 5 - Catalogue data a visitor can trust (Priority: P1)

**Goal**: provenance, stale flags, unique artists with a merge path, confirmed hours, coverage gate.

**Independent Test**: edit a tagged exhibition's description → stale in Admin, still visible to readers → confirm
clears it; creating a duplicate artist is refused; confirmed hours remove the unverified mark (spec US5).

### Tests for User Story 5

- [ ] T051 [P] [US5] Write `pgtap/070_art_metadata_provenance.test.sql`: `grounding` stored and cloned to the next
  version; `created_by` is the applier; `exhibition_metadata_is_stale` false after confirm, true after a description or
  date change is published, false for versions without terms or credits; `admin_confirm_exhibition_metadata` requires
  staff and a current revision; `admin_list_stale_exhibition_metadata` lists stale exhibitions; anonymous readers see
  no grounding and no change in `exhibition_catalog_v2` artists/terms.
- [ ] T052 [P] [US5] Write `pgtap/071_confirmed_opening_hours.test.sql`: valid seven-day JSON accepted, bad keys or
  `opens >= closes` raise `hours_weekly_invalid`; `exhibition_catalog_v2.hours_weekly` projected while fresh and null
  after the `hours` text changes; staff-only.
- [ ] T053 [P] [US5] Write `pgtap/072_artist_merge.test.sql`: `normalize_artist_name` folds case, width, spaces and
  punctuation; `admin_find_duplicate_artists` groups by normalised name; `admin_merge_artists` re-points draft and
  published credits, keeps published labels, archives the merged artist, writes `content.audit_log`, refuses same id,
  archived or missing artists, and removes a credit that would duplicate the survivor on one version.
- [ ] T054 [P] [US5] Write `pgtap/073_artist_name_uniqueness.test.sql`: creating an artist whose normalised Korean or
  English name matches an active one raises `artist_duplicate` with the existing id (Admin and owner paths); archived
  artists do not block; the migration aborts with the group list when duplicates remain.
- [ ] T055 [P] [US5] Write `admin/hours/openingHours.test.ts` that runs the 21 golden cases in
  `specs/088-discovery-accuracy-fixes/contracts/opening-hours-grammar.md` and requires the same readings as the Kotlin
  `OpeningHoursParser`.
- [ ] T056 [P] [US5] Write `sharedTest/hours/ConfirmedHoursTest.kt`: a catalogue row with `hours_weekly` maps to
  `CONFIRMED`, `isVerified` true; without it the parser result is unchanged; route stop disclosure no longer marks a
  confirmed venue unverified.
- [ ] T057 [P] [US5] Write `scripts/catalogue-coverage/coverage.test.mjs`: Markdown table from a sample JSON; exit 1
  below `--floor-week` or `--floor-all`; counts for credits, hours, coordinates, dates, English, stale and duplicate
  groups.
- [ ] T058 [P] [US5] Extend Admin tests (`admin/components/ExhibitionArtMetadataEditor.test.tsx`,
  `admin/components/ExhibitionInspector.test.tsx`, `admin/repositories/SupabaseAdminExhibitionRepository.test.ts`) for
  grounding fields, the Confirm terms action, the Stale filter, the weekly hours editor and Confirm hours,
  `artist_duplicate` → "Link to existing", and the merge dialog (admin role only).
- [ ] T059 [P] [US5] Extend the Gallery workspace tests for `artist_duplicate` → link to the existing artist.

### Implementation for User Story 5

- [ ] T060 [US5] Write `migrations/2026MMDDhhmmss_art_metadata_provenance.sql` per contracts/catalogue-reliability.md
  (columns, `apply_`/`clone_exhibition_art_metadata` changes, stale function, confirm and list RPCs with revision
  checks and audit); make T051 pass on a scratch stack.
- [ ] T061 [US5] Write `migrations/2026MMDDhhmmss_confirmed_opening_hours.sql` (columns, confirm RPC, catalogue
  projection of fresh `hours_weekly`); make T052 pass.
- [ ] T062 [US5] Write `migrations/2026MMDDhhmmss_artist_merge.sql` (normalise, report, merge); make T053 pass.
- [ ] T063 [US5] Write `migrations/2026MMDDhhmmss_artist_name_uniqueness.sql` (abort with groups if any remain, then
  indexes; create functions raise `artist_duplicate`); make T054 pass. Do not apply to a hosted project until T068.
- [ ] T064 [US5] Port the parser to `admin/hours/openingHours.ts`; make T055 pass.
- [ ] T065 [US5] Map `hours_weekly` in the catalogue DTO under `shared/data/network/dto/` and add `CONFIRMED` to
  `shared/hours/WeeklyOpeningHours.kt`; make T056 pass.
- [ ] T066 [US5] Implement the Admin changes in `admin/components/ExhibitionArtMetadataEditor.tsx`,
  `admin/components/ExhibitionInspector.tsx`, a new `admin/components/ArtistMergeDialog.tsx` and
  `admin/repositories/SupabaseAdminExhibitionRepository.ts` (+ in-memory repository), with KO/EN strings in
  `admin/staff-i18n-messages.ts`; make T058 pass.
- [ ] T067 [US5] Handle `artist_duplicate` in the Gallery workspace artist creation; make T059 pass.
- [ ] T068 [US5] Rollout (owner-authorised steps): apply provenance, hours and merge migrations to staging then
  production; deploy Admin and Gallery; owner runs the duplicate report and merges every group; then apply the
  uniqueness migration to staging and production.
- [ ] T069 [US5] Implement `scripts/catalogue-coverage/coverage.sql` (read-only) and `coverage.mjs`; make T057 pass;
  document the release-PR step in `scripts/catalogue-coverage/README.md` and `scripts/AGENTS.md`.

**Checkpoint**: the catalogue records provenance, flags stale data, cannot grow duplicate artists, carries confirmed
hours, and has a release gate.

---

## Phase 8: User Story 6 - Recommendations are measured before release (Priority: P2)

**Goal**: offline precision@3 eval over the tagged catalogue with owner labels; dismissal rules asserted.

**Independent Test**: the eval reports precision and the missed scenarios, and fails on any rule violation (spec
US6).

- [ ] T070 [US6] Regenerate a tagged fixture after the tagging pass: run the export in
  `specs/088-discovery-accuracy-fixes/fixture/` against the public catalogue (owner runs the read) and
  `to-kotlin.mjs` into `sharedTest/fixture/TaggedCatalogueFixture.kt` (generated header, reference date of the
  export); keep `PublishedCatalogueFixture.kt` and its tests unchanged.
- [ ] T071 [US6] Owner step: label at least fifteen scenarios (picks or saves → expected top three) in
  `sharedTest/recommendation/TasteEvalScenarios.kt`, with a dated header comment naming the labeller.
- [ ] T072 [US6] Write `sharedTest/recommendation/TasteEvalTest.kt`: precision@3 across scenarios ≥ 0.6, every shown
  reason supported by the scenario's picks, saves, visits or follows, and a report of missed scenarios in the failure
  message; run it; if it fails, follow the miss policy in research R13 (relabel or a dated lower bar from the owner,
  no silent weight tuning).

---

## Phase 9: User Story 7 - Loading reads as work (Priority: P3)

- [X] T073 [P] [US7] Extend `appTest/ui/tabs/home/HomePresentationTest.kt`: while `RecommendationUiState.Loading` the
  내 취향 slot reports a skeleton layout of the final height instead of switching leads.
- [X] T074 [US7] Draw rail-sized skeleton cards (184 dp portrait, 16 dp shape) in the 내 취향 slot of
  `app/ui/tabs/home/HomeScreen.kt` and skeleton rows in `app/ui/discovery/RecommendationsScreen.kt`, reusing the
  existing skeleton primitives; make T073 pass.

**Implementation notes (2026-10-26, Phase 9)**

- The 내 취향 slot leads as a banner since spec 091, so the skeleton is banner-shaped, not rail-sized:
  `BannerPagerSkeleton` reuses the banner's pager geometry (3:4, 16 dp shape, second card peeking) and the list
  skeletons' pulsing fill (`skeletonFill`, still under reduced motion or a screen reader). The header keeps its title
  and action, with a skeleton bar holding the basis line (`SectionHeader(subtitlePlaceholder = …)`: an invisible
  basis line in the visitor's language sets the height, since Hangul and Latin lines differ by about 2 dp; found on
  the emulator with a throttled network).
- The skeleton shows only when picks are expected (`PendingTaste` from `hasHistory` and the stored seed): a visitor
  with nothing gets the featured banner at once, so nothing swaps. With a picked taste the chip row is held too, with
  only `편집`. Unknown history counts as none.
- 모두 보기 shows three `SkeletonCard`s in place of the loading line, read aloud as one polite line.
- Tests: `HomePresentationTest` (three cases) and iOS `BannerSkeletonTest` (skeleton and banner heights equal at
  phone width).

---

## Phase 10: Polish & cross-cutting

- [X] T075 [P] Update `DESIGN.md`: taste pick sheet, prompt card, chips as buttons, 덜 보기 menu and snackbar, detail
  reason line, skeleton slot; decisions-log row for 2026-10-26 (owner chose Approach B staged; local picks; no AI
  wording).
- [X] T076 [P] Update `TODOS.md` (tagging progress, Stage 2 gate and its observation rule, owner label set) and add a
  `CLAUDE.md` Recent Changes line for 101 by hand (do not run the agent-context script, which truncates that section).
- [X] T077 [P] Privacy review: confirm no analytics allowlist change, no new network call carries picks, dismissals,
  reasons or scores, and the Settings 사용 분석 copy and `web/privacy.html` stay accurate; record the result in this
  file.

  **T077 result (2026-10-26): pass.** On `101-taste-listening` against `develop`: no analytics file changed (event
  names, allowlisted fields and `positionBucket` untouched); the new call sites (share and taps from 내 취향) reuse
  the existing `DiscoveryAttribution` fields (surface, kind, position bucket) and never pass picks, dismissals,
  reasons or scores; `tasteReasons` travels only in the in-app destination. `DataStoreTastePreferenceRepository`
  imports DataStore and serialization only, and taste logs carry operation names and the exception type. No Supabase
  or Edge Function change touches taste (the branch's function changes are the 합니다체 note prompt). The Settings
  사용 분석 disclosure ("추천 취향 정보 … 전송하지 않습니다" / "recommendation profile") and `web/privacy.html` ("recommendation
  profile, scores or reasons" never sent; personalisation on the device) stay accurate. Note for the owner: picks
  and dismissals live in the same on-device store as bookmarks, so Android Auto Backup (`allowBackup="true"`) and
  iCloud device backup carry them as they carry bookmarks; gallr itself sends nothing. The sheet's `이 기기에만 저장`
  stays true in that sense; excluding the store from OS backups would be a separate decision covering bookmarks too.
  Since the pre-landing review (2026-10-27) 공유 from the ⋯ menu passes no attribution and records no open or page
  view, so only taps from 내 취향 use the attribution fields.
- [ ] T078 Full verification per CLAUDE.md: `./gradlew shared:ktlintCheck composeApp:ktlintCheck
  androidApp:ktlintCheck shared:allTests composeApp:allTests composeApp:testAndroidHostTest androidApp:lintDebug
  androidApp:assembleDebug`, `composeApp:iosSimulatorArm64Test`, `(cd admin && npm test)`, `(cd gallery && npm
  test)`, `(cd scripts/tag-exhibition && deno task test)`, `node --test scripts/catalogue-coverage/coverage.test.mjs`,
  pgTAP 069–073 on an isolated scratch stack, `git diff --check`.
- [ ] T079 Manual pass on Android and iOS in Korean and English following quickstart.md steps 1–6; record results here.
- [ ] T080 Release gate for 1.15: coverage check at or above the floor (owner runs), eval green, then bump `VERSION`,
  `androidApp` `versionCode`/`versionName` and `CHANGELOG.md` together.

---

## Dependencies & execution order

- Phase 1 → Phase 2 → US1 → US2 → US3 (app work is sequential where it touches the ranker and Home files).
- US4 and US5 depend only on Phase 1 and can run beside US1–US3. Within US5, T068 needs T060–T063 and T066–T067;
  T063 is applied only after merges.
- US6 needs US1 and US3 (ranker behaviour) and US4's tagging pass (T050) for the regenerated fixture; T072 needs the
  owner's labels (T071).
- US7 needs Phase 2 only.
- Polish runs last; T080 needs T069, T072 and T078.

### Owner-gated tasks

T045 (vocabulary list), T050 and T068 (hosted migrations, deploys, tagging, merges), T070 (production read for the
fixture), T071 (labels), T079 and T080 (manual pass, release). Agents never apply hosted migrations or approve on the
owner's behalf.

## Parallel examples

- Phase 2: T003 and T005 together, then T004 and T006.
- US1 tests: T010, T011, T012, T013, T014, T015 together; then T022 and T023 together after T020–T021.
- US5 tests: T051–T059 together; migrations T060–T063 in order on one scratch stack; T064, T065, T069 in parallel.

## Implementation strategy

1. **MVP**: Phases 1–3 (US1). A first-time visitor picks a taste and sees a rail built from it; no editorial card under
   a taste heading.
2. Add US2 and US3 for explained and correctable picks: Stage 1 app behaviour complete.
3. Run US4 and US5 in parallel from day one; they improve 1.14 for readers before 1.15 exists.
4. Close with US6's eval gate, US7, then Polish and the 1.15 release.
