# Implementation Plan: Exhibition Card Image Background

**Branch**: `019-card-image-background` | **Date**: 2026-03-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/019-card-image-background/spec.md`

## Summary

Add installation view images as full-bleed backgrounds to exhibition cards with a semi-transparent scrim overlay ensuring text readability. The change is confined to `ExhibitionCard.kt` — replacing the `Surface` wrapper with a `Box` that layers an `AsyncImage`, a scrim overlay, and the existing text content. Cards without images fall back to a `surfaceVariant` solid background. Press feedback shifts from background-invert to scrim-darken on image cards.

## Technical Context

**Language/Version**: Kotlin 2.1.20 (KMP)
**Primary Dependencies**: Compose Multiplatform 1.8.0, Material3, Coil 3.1.0 (`coil3.compose.AsyncImage`)
**Storage**: N/A — no new persistence
**Testing**: Manual visual verification on both platforms (UI-only change with fixed design tokens)
**Target Platform**: Android + iOS (KMP shared UI via Compose Multiplatform)
**Project Type**: Mobile app (cross-platform)
**Performance Goals**: 60fps scroll performance maintained; no jank from image loading
**Constraints**: Image loading must be async and non-blocking; fallback must be silent (no spinners/placeholders)
**Scale/Scope**: Single file change (`ExhibitionCard.kt`); ~80 lines modified

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Spec-First Development | PASS | Spec written and clarified before planning |
| II. Test-First (NON-NEGOTIABLE) | PASS | UI-only change with fixed design tokens — no shared KMP module logic added; platform UI layers are exempt per constitution. Visual verification plan documented. |
| III. Simplicity & YAGNI | PASS | Single-file change, no new abstractions, no new dependencies. Post-MVP enhancements (crossfade, gradient scrim) explicitly deferred. |
| IV. Incremental Delivery | PASS | Three independently testable stories: image display (P1), fallback (P1), press feedback (P2) |
| V. Observability | PASS | Image load failures handled silently by Coil (existing infrastructure); no new error paths that require logging |
| VI. Shared-First Architecture (NON-NEGOTIABLE) | PASS | All changes in `composeApp/` (shared Compose UI layer). No business logic added — only UI rendering changes. The `Exhibition` model and `coverImageUrl` already exist in `shared/`. |

**Result: ALL GATES PASS** — no violations to track.

## Project Structure

### Documentation (this feature)

```text
specs/019-card-image-background/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
composeApp/src/commonMain/kotlin/com/gallr/app/ui/
├── components/
│   ├── ExhibitionCard.kt    # PRIMARY — all changes here
│   └── BookmarkButton.kt    # UNCHANGED — tintColor param already supports new colors
├── tabs/
│   ├── featured/FeaturedScreen.kt  # UNCHANGED — uses ExhibitionCard
│   └── list/ListScreen.kt         # UNCHANGED — uses ExhibitionCard
└── theme/
    └── GallrColors.kt              # UNCHANGED — existing tokens sufficient
```

**Structure Decision**: This feature modifies a single shared Compose component (`ExhibitionCard.kt`). No new files, modules, or directories are needed. The component is already shared across both tabs, so changes propagate automatically.

## Complexity Tracking

> No violations. All gates pass without justification needed.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| *(none)* | — | — |
