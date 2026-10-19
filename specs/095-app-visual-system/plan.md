# Implementation Plan: Home style across the app

**Branch**: `095-app-visual-system` | **Date**: 2026-10-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/095-app-visual-system/spec.md`

## Summary

Home's visual system becomes the app's. A new `GallrShapeTokens` holds Home's two radii (16dp cards, 12dp small
pieces), the Material theme shapes follow it, and each of the 152 hand-passed square shapes outside Home names its
category's token instead (contracts/shape-categories.md). Home's caption scrims move to `ui/theme/` and replace the
other caption treatments, including the exhibition card's light-mode white wash. Home's `SectionHeader` moves to
`ui/components/` and replaces the label-style headings. Colours do not change. `DESIGN.md`, `CLAUDE.md` and
`AGENTS.md` are rewritten where they state the 0dp rule. The map-label change (US3) is already on the branch.

## Technical Context

**Language/Version**: Kotlin 2.x Multiplatform, Compose Multiplatform (versions in `gradle/libs.versions.toml`)
**Primary Dependencies**: Compose material3 / foundation already in `composeApp`; no new dependency
**Storage**: N/A
**Testing**: `commonTest` (tokens, scrim maths, map labels), Android host source-scan test for the shape guard,
manual screenshot pass on both platforms (quickstart)
**Target Platform**: Android and iOS via `composeApp/commonMain`
**Project Type**: mobile app (KMP)
**Performance Goals**: no change; clipping adds no measurable cost at these sizes
**Constraints**: colours unchanged (FR-006); text, order and behaviour unchanged (FR-010); exports unchanged; free only
**Scale/Scope**: about 45 `commonMain` files, 4 docs, 3 tests replaced or moved

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Spec-First | `spec.md` committed (`34ce3a95`) before planning | PASS |
| II. Test-First | The shape guard (`AppShapesTest`) and token tests are written first and fail against today's code; scrim contrast already tested | PASS (enforced in tasks) |
| III. Simplicity & YAGNI | Two tokens from existing values; existing scrim and header code moved, not rewritten; no screenshot-diff tooling | PASS |
| IV. Incremental Delivery | Tokens and guard, then shared components, then screens tab by tab, then docs; each step builds and passes | PASS |
| V. Observability | No runtime behaviour change; the style reader's single failure log already exists (`map_style_read_failed`) | PASS |
| VI. Shared-First | Presentation only; no business logic moves | PASS |
| VII. Mobile-First | App only; web, Admin and Gallery untouched | PASS |

**Post-design re-check**: unchanged, all PASS.

## Project Structure

### Documentation (this feature)

```text
specs/095-app-visual-system/
├── plan.md, research.md (R1–R9), data-model.md, quickstart.md
├── contracts/shape-categories.md
├── checklists/requirements.md
└── tasks.md             # /speckit.tasks
```

### Source Code (repository root)

```text
composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/theme/GallrShapeTokens.kt     # new
├── ui/theme/GallrTheme.kt           # Shapes from the tokens
├── ui/theme/HomeVisualTokens.kt     # radii read the tokens
├── ui/theme/CaptionScrim.kt         # moved from ui/tabs/home/HomeScrim.kt
├── ui/components/SectionHeader.kt   # moved from ui/tabs/home/
├── ui/components/*, ui/detail/*, ui/gallery/*, ui/event/*, ui/discovery/*, ui/mygallr/*, ui/profile/*,
│   ui/route/**, ui/settings/*, ui/tabs/list/*, ui/tabs/map/*, AppMenus.kt   # sites per contracts/
composeApp/src/androidHostTest/kotlin/com/gallr/app/ui/theme/
├── AppShapesTest.kt                 # replaces HomeOnlyShapesTest
└── ShapeTokensDocumentationTest.kt  # replaces HomeTokensDocumentationTest
DESIGN.md, CLAUDE.md, AGENTS.md
```

**Structure Decision**: `composeApp` and the three guidance documents only.

## Owner decisions (2026-10-15)

Home is the style; colours stay; doc and app ship together; free only. Defaults recorded in the spec's assumptions
(Home's values unchanged, buttons and fields are small pieces, square exceptions) are applied as written.

## Risks

- **The exhibition card in light mode changes most**: from a white wash with dark text to white text on a black top
  band. It is what FR-004 asks; the screenshot pass shows it to the owner before merge.
- **Unclipped bordered cards**: a rounded border over a square picture. Mitigation: every card category clips (R3).
- **Overlap with #354 and #355**: this branch stacks on #354 (route map); #355 rewires `App.kt`. Shape edits do not touch
  navigation code, so conflicts are limited to shared screen files and resolve mechanically.
- **Missed sites**: the shape guard fails the build on any unlisted square shape.

## Complexity Tracking

No violations to justify.
