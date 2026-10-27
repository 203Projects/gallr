# Implementation Plan: Taste listening and reliable catalogue data

**Branch**: `101-taste-listening` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/101-taste-listening/spec.md`; approved design
[docs/designs/2026-10-26-mobile-taste-ai-design.md](../../docs/designs/2026-10-26-mobile-taste-ai-design.md)

## Summary

Make 내 취향 accurate by tagging the running catalogue with grounded, reviewed terms and credits, and make the catalogue
trustworthy (provenance, stale flags, unique artists, confirmed hours, a release coverage gate). Then make 내 취향
listen: a first-open taste pick stored on the device, picks as a new `CHOSEN` anchor source in the existing on-device
ranker, a 덜 보기 correction with per-exhibition dismissal records and a capped penalty, tappable chips, a rail that
holds only personal cards, the reason on the exhibition page, an offline accuracy eval, and skeleton loading.
Everything stays on the device and free; Stage 2 (a generated voice) is not in this plan.

## Technical Context

**Language/Version**: Kotlin 2.x (KMP, versions in `gradle/libs.versions.toml`); TypeScript (Admin, Gallery, Node 22
per `.node-version`); Deno per `.tool-versions` (desk CLI); SQL (Postgres 17, Supabase)
**Primary Dependencies**: Compose Multiplatform, JetBrains lifecycle ViewModel, DataStore Preferences,
kotlinx.serialization, kotlinx.datetime; React/Vite (Admin, Gallery). No new dependency.
**Storage**: device DataStore (picks, skip, dismissals); Supabase `content` schema (terms, credits, provenance,
hashes, confirmed hours) projected through `public.exhibition_catalog_v2`
**Testing**: `shared/commonTest` and `composeApp/commonTest` (kotlin-test, `runTest`), Compose UI tests in
`composeApp/iosTest` (`runComposeUiTest` v2), pgTAP (069–073), Vitest (Admin, Gallery), `deno test` (desk CLI),
`node --test` (coverage script)
**Target Platform**: Android (minSdk 26) and iOS (16.0) app; Admin and Gallery web; Supabase
**Project Type**: mobile app + backend + operator web tools
**Performance Goals**: re-rank with seed and dismissals within the current bound asserted by
`LocalExhibitionRecommenderTest` (bounded performance test, ~80-row catalogue) on the background dispatcher; taste
sheet opens within one frame of Home's first content
**Constraints**: no hosted model, no network call for ranking, no paid service, no API key, no new permission, no
location on the For You screen, analytics allowlist unchanged, no reader-facing AI wording
**Scale/Scope**: ~80 running/upcoming exhibitions; ~53 vocabulary terms after expansion; 7 user stories; 5 migrations

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I Spec-First | `spec.md` with 7 stories and acceptance scenarios exists and its checklist passes | Pass |
| II Test-First | Every shared rule (seed weights, penalty, dismissal transitions, choices, hasPersonalEvidence, hours CONFIRMED) gets a failing `commonTest` first; pgTAP before each migration's functions; parity tests before the TS hours port | Pass (enforced in tasks) |
| III Simplicity | Reuses the existing ranker, evidence types (one new `ChosenTerm`), DataStore pattern, curator-note stale pattern and fixture generator; no new dependency; a single repository for all taste state | Pass |
| IV Incremental | Stories ship independently: US4/US5 are data and operator work usable on 1.14; US1 works without US3; US6 runs on any catalogue; US7 is cosmetic | Pass |
| V Observability | `AppLog.tagged("TastePreferences")` with stable snake_case operations and exception type only; RPCs audit merges and confirmations in `content.audit_log`; desk CLI reports every refusal with its id | Pass |
| VI Shared-First | Seed, dismissals, penalty, choices, personal-evidence predicate, hours model and repository in `shared/commonMain`; sheet, prompt card, overflow menu, gate and wiring in `composeApp/commonMain`; no platform source set touched | Pass |
| VII Mobile-First | Every consumer capability is in the Android and iOS app; no web surface changes; Admin, Gallery and the desk tool are operator tools | Pass |

Post-design re-check (after research, data model and contracts): no new project, framework or platform adapter; the
two-migration artist uniqueness is sequencing, not complexity. **Pass.**

## Project Structure

### Documentation (this feature)

```text
specs/101-taste-listening/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── shared-taste-api.md
│   ├── taste-ui.md
│   └── catalogue-reliability.md
├── checklists/requirements.md
└── tasks.md            # /speckit.tasks
```

### Source Code (repository root)

```text
shared/src/commonMain/kotlin/com/gallr/shared/
├── taste/TastePick.kt                     # TasteSeed, TasteDismissal(s), TastePickState, tastePickChoices
├── taste/TasteProfile.kt                  # tasteTerms(excluded = …), picks count as anchors
├── taste/ArtTermVocabulary.kt             # new ids: KO substrings, EN whole words
├── recommendation/ExhibitionRecommendation.kt   # CHOSEN, ChosenTerm, context fields, hasPersonalEvidence
├── recommendation/LocalExhibitionRecommender.kt # chosen anchors, penalty, exclusions, reason order
├── repository/TastePreferenceRepository.kt
├── repository/DataStoreTastePreferenceRepository.kt
├── hours/WeeklyOpeningHours.kt            # CONFIRMED
└── data/network/dto/…                     # hours_weekly mapping
shared/src/commonTest/kotlin/com/gallr/shared/
├── taste/TastePickTest.kt, TasteDismissalsTest.kt, TasteProfileTest.kt (extended)
├── recommendation/ChosenAnchorTest.kt, TasteDismissalPenaltyTest.kt, TasteEvalTest.kt, TasteEvalScenarios.kt
├── repository/DataStoreTastePreferenceRepositoryTest.kt
└── fixture/TaggedCatalogueFixture.kt      # generated after tagging

composeApp/src/commonMain/kotlin/com/gallr/app/
├── viewmodel/LocalDiscoveryViewModel.kt   # seed + dismissals into context; dismiss/undo/lift
├── viewmodel/TastePickGate.kt             # shouldOfferTastePick
├── viewmodel/TastePickViewModel.kt        # sheet state, choices, save/skip/releaseTerm
├── ui/discovery/TastePickSheet.kt, TastePromptCard.kt, RecommendationOverflowMenu.kt
├── ui/discovery/RecommendationPresentation.kt   # CHOSEN reasons, counts
├── ui/discovery/RecommendationsScreen.kt        # overflow, skeleton
├── ui/tabs/home/HomePresentation.kt, HomeScreen.kt  # personal-only rail, prompt, chips as buttons, skeleton
├── ui/detail/ExhibitionDetailScreen.kt    # reason line
├── AppNavigationState.kt                  # ExhibitionDetail.tasteReasons
└── App.kt                                 # repository wiring, sheet host, lift on save/visit/follow
composeApp/src/commonTest/…                # presentation and gate tests
composeApp/src/iosTest/…                   # sheet and overflow UI tests

supabase/migrations/                       # five migrations (contracts/catalogue-reliability.md)
supabase/tests/database/069–073_*.test.sql
admin/src/hours/openingHours.ts (+ .test.ts parity)
admin/src/components/ExhibitionArtMetadataEditor.tsx, ExhibitionInspector.tsx, ArtistMergeDialog.tsx
admin/src/repositories/SupabaseAdminExhibitionRepository.ts
gallery/src/…                              # artist_duplicate handling
scripts/tag-exhibition/                    # Deno desk CLI + tests
scripts/catalogue-coverage/                # coverage.sql, coverage.mjs, coverage.test.mjs
.claude/skills/tag-exhibition/SKILL.md
DESIGN.md, CHANGELOG.md, TODOS.md, CLAUDE.md (Recent Changes)
```

**Structure Decision**: existing KMP split (shared logic, composeApp UI and ViewModels), Supabase migrations with
pgTAP, Admin/Gallery React apps, and `scripts/` for operator tooling. No new module.

## Delivery order

1. **Stage 0a, no app release**: vocabulary migration (after the owner's list), desk CLI and skill, tagging pass;
   1.14 readers benefit at once (research R6).
2. **Stage 0b, reliability**: provenance and stale migration with Admin; artist merge report, merges, then uniqueness;
   confirmed hours with the TS parser port; coverage script.
3. **Stage 1, release 1.15**: shared model and ranker (test-first), repository, eval with the regenerated fixture,
   then UI (sheet, prompt, chips, overflow, rail filter, detail reason, skeleton), then DESIGN.md and CHANGELOG.
4. **Release gate**: coverage check at the floor, eval green, full verification contract, manual pass on both
   platforms (quickstart).

## Risks

- **Eval labels arrive late**: the shared work and UI do not wait; only the release gate does.
- **Existing duplicate artists** block the uniqueness migration until merged: the report and merge tool ship first.
- **Hours parser parity** in TypeScript: held by the golden cases; any mismatch blocks Admin's proposal, not the app.
- **Owner time**: tagging about 80 exhibitions is about three hours (research R12); split across the week.

## Complexity Tracking

No constitution violations. No entries.
