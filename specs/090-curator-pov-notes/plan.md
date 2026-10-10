# Implementation Plan: Curator POV Notes

**Branch**: `090-curator-pov-notes` | **Date**: 2026-10-11 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/090-curator-pov-notes/spec.md`

## Summary

Curator's notes are short bilingual pieces about one exhibition, drafted automatically from the
exhibition's own published data and reviewed artist and term metadata, reviewed by a staff editor
in Admin, published as versioned content under the editor's name with an assisted-drafting line,
and shown on the mobile home tab (큐레이터의 시선 carousel) and the exhibition detail screen; staff
can export an approved note as an Instagram carousel. Decisions are in [research.md](research.md)
(R1–R14) with the survey of comparable products in its Part B.

- **Database**: `content.curator_notes` and `content.curator_note_versions` under RLS with
  definer-rights review functions, a `pg_cron` scheduler, and the public read view
  `curator_notes_published` that drops a note with its exhibition
  ([contracts/database-functions.md](contracts/database-functions.md)).
- **Edge Function** `draft-curator-notes`: Claude Opus 5.5 through the TypeScript SDK with a Zod
  structured output, a versioned prompt with reference examples, a source packet and hash, and
  deterministic grounding checks that mark a bad draft `unusable`
  ([contracts/draft-curator-notes-function.md](contracts/draft-curator-notes-function.md)).
- **Shared (KMP)**: `CuratorNote` model, `curatorEyeCards` pure selection, `CuratorNoteApiClient`,
  `CuratorNoteRepository`, one new `DiscoveryKind`
  ([contracts/shared-note-api.md](contracts/shared-note-api.md)).
- **App**: `HomeFeed.curatorEye` and the `CuratorEyePager` between hero and rails,
  `CuratorNoteViewModel` and `CuratorNoteBlock` on the detail screen with scroll-to-note.
- **Admin**: `CuratorNotesWorkspace` (queue, side-by-side review, unsupported markers, approve /
  decline / regenerate with revision guards) and the browser-rendered carousel export
  ([contracts/carousel-export.md](contracts/carousel-export.md)).

## Technical Context

**Language/Version**: Kotlin Multiplatform and Compose Multiplatform (versions from
`gradle/libs.versions.toml`); SQL (Postgres via Supabase, pgTAP); Deno from the root
`.tool-versions` for the Edge Function; TypeScript/React with Node.js 22.23.1 for `admin/`
**Primary Dependencies**: existing: kotlinx-datetime, kotlinx-serialization, Ktor client, Coil,
JetBrains lifecycle ViewModel, `@supabase/supabase-js`, `pg_cron`, `pg_net`, Vault; new:
`npm:@anthropic-ai/sdk` and `npm:zod` in the Edge Function (pinned in `deno.json` and lockfile),
`uqr` 0.1.3 and `fflate` (current stable) in `admin/`
**Storage**: Supabase Postgres (`content.curator_notes`, `content.curator_note_versions`, audit log
rows, the `curator_notes_published` view); no device storage beyond the existing catalogue cache
**Testing**: kotlin-test + kotlinx-coroutines-test in `commonTest`; pgTAP suite
`057_curator_notes.test.sql`; `deno task test` for `draft-curator-notes` and `mobile-analytics`;
Vitest in `admin/`; emulator and simulator walkthroughs per [quickstart.md](quickstart.md)
**Target Platform**: Android and iOS apps; Admin at admin.gallrmap.com; Supabase staging then
production; the public web untouched
**Project Type**: Mobile app (KMP) plus database, Edge Function and staff web app
**Performance Goals**: on-demand draft under two minutes (one Claude call, about 20–40 s at
`effort: high`); scheduled run drafts up to 20 exhibitions inside a 50 s budget and leaves the rest
for the next day; home tab adds one PostgREST read combined with the catalogue off the main thread;
carousel export under 30 s in the browser (SC-006)
**Constraints**: no draft readable outside staff (FR-006, R14); drafts only from the packet
(FR-003, R3); approval refused with unsupported sentences (FR-007); attribution and disclosure line
on every visitor surface (FR-020, R8); generation never on the device or in the web project
(FR-019, constitution VII); DESIGN.md monochrome rules with the export exception; no Claude key
outside the function's secrets; cost about USD 0.04 per draft (R2)
**Scale/Scope**: about 80 live exhibitions, a few catalogue changes a week, at most 10 carousel
cards; 2 app surfaces, 1 Admin workspace with an export dialog, 1 Edge Function, 11 database
functions, 1 view

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Spec-First | `spec.md` with four prioritized stories, acceptance scenarios, edge cases, FR-001–FR-020 and SC-001–SC-006; checklist complete; FR-020 resolved by the owner (option A) | PASS |
| II. Test-First | pgTAP for every function and the view before the migration; Deno tests for the handler, each validation rule and the hash before the function code; `commonTest` for `curatorEyeCards`, the DTO, the repository and both ViewModels before implementation; Vitest for the repository, the workspace and the pure carousel layout before the components | PASS |
| III. Simplicity & YAGNI | One Edge Function, two tables plus the existing audit log, one view, no new event name (one enum value), no new app screen (a section and a block); new dependencies limited to the Claude SDK and Zod in the function and `uqr` + `fflate` in Admin, each logged below | PASS (dependencies logged) |
| IV. Incremental Delivery | US1 (database + function + Admin review) ships first and is useful alone; US2 and US3 read only approved rows and hide while none exist; US4 depends on approved notes only; the analytics kind lands with US2 | PASS |
| V. Observability | Function logs one structured line per exhibition with provenance ids, token usage and rejection codes, never text (R12); app logs through `AppLog.tagged("CuratorNotes")` with `curator_notes_load_failed`, `curator_note_load_failed`, `curator_note_row_skipped`; Admin shows stale, unsupported and denial states explicitly; every review action is an audit row | PASS |
| VI. Shared-First | `CuratorNote`, language fallback, `curatorEyeCards`, DTO, ApiClient and repository in `shared/commonMain`; ViewModels and Compose UI in `composeApp/commonMain`; no platform code; `admin/` and the function are independent artifacts with no shared-module dependency | PASS |
| VII. Mobile-First | Visitors meet notes only in the app (home carousel, detail block); the public web is untouched and gains no surface in this feature; Admin is an operator tool; drafting and export never run on a visitor's device | PASS |
| Quality: KMP dependency check | No new KMP dependency | PASS |
| Quality: public interfaces documented | Contracts in `contracts/`; KDoc at definitions | PASS |

Post-design re-check (after Phase 1): unchanged, all PASS. Every business rule (qualification,
status transitions, approval guards, reviewer snapshot, published visibility) lives in SQL or
`shared`; the function holds only drafting and validation; composables render state.

## Project Structure

### Documentation (this feature)

```text
specs/090-curator-pov-notes/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── database-functions.md
│   ├── draft-curator-notes-function.md
│   ├── shared-note-api.md
│   └── carousel-export.md
├── checklists/requirements.md
└── tasks.md              # /speckit.tasks
```

### Source Code (repository root)

```text
supabase/migrations/20261011XXXXXX_curator_notes.sql   # tables, enum, RLS, functions, view, cron, analytics constraint
supabase/tests/database/057_curator_notes.test.sql
supabase/functions/draft-curator-notes/
├── index.ts  handler.ts  drafting.ts  backend.ts  schema.ts
├── prompt/system.md  prompt/examples.json
├── handler_test.ts  drafting_test.ts
├── deno.json  deno.lock  README.md
supabase/functions/mobile-analytics/                   # curator_note discovery kind + test

shared/src/commonMain/kotlin/com/gallr/shared/
├── curatornote/            # CuratorNote, CuratorEyeCard, curatorEyeCards
├── home/                   # HomeFeed.curatorEye
├── data/network/           # CuratorNoteApiClient + dto/CuratorNoteDto
├── repository/             # CuratorNoteRepository(+Impl)
└── analytics/              # DiscoveryKind.CURATOR_NOTE
shared/src/commonTest/…      # cards, DTO, repository tests

composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/tabs/home/           # CuratorEyePager.kt; HomeScreen + HomePresentation additions
├── ui/detail/              # CuratorNoteBlock.kt; ExhibitionDetailScreen scrollToNote
├── viewmodel/              # HomeViewModel (notes), CuratorNoteViewModel
└── App.kt                  # wiring, scrollToNote navigation state
composeApp/src/commonTest/…  # HomeViewModel, CuratorNoteViewModel, presentation tests

admin/src/
├── repositories/AdminCuratorNoteRepository.ts (+ test)
├── components/CuratorNotesWorkspace.tsx (+ test), CarouselExportDialog.tsx
├── export/carousel.ts, carouselZip.ts (+ tests)
├── components/PrimaryNavigation.tsx, App.tsx, staff-i18n-messages.ts   # Notes item and view
└── package.json            # uqr, fflate

DESIGN.md                   # curator's eye section, note block, export type sizes, Decisions Log row
CHANGELOG.md                # Unreleased
docs/                       # function runbook link from supabase/functions/draft-curator-notes/README.md
```

**Structure Decision**: Existing repository layout; no new module. Lanes: A database (migration
and pgTAP, first because every other lane reads its contract); B Edge Function (on A's packet and
store contract, testable on fakes before A lands); C shared then composeApp (on fakes, then the
view); D Admin (on A's functions and B's `single` mode); E analytics kind (A's constraint, the Edge
allow-list and the shared enum together).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `npm:@anthropic-ai/sdk` + `npm:zod` in the function | Validated structured output and typed errors from the Claude API (R2) | Raw `fetch` loses schema parsing, retries, typed errors and the refusal fallback plumbing |
| `uqr` in Admin | The closing slide's scannable code (FR-015) | A hand-written QR encoder; `uqr` is already the gallery workspace's choice |
| `fflate` in Admin | One downloadable archive (FR-015) | A hand-written stored-zip writer risks corrupt downloads for a few saved kilobytes |
