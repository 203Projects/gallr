# Implementation Plan: Comprehensive UI Improvements and Polish

**Branch**: `016-ui-improvements` | **Date**: 2026-03-24 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/016-ui-improvements/spec.md`

## Summary

Comprehensive UI polish pass: add pull-to-refresh, skeleton loading states, image placeholders, back gesture support, screen transitions, exhibition search, settings menu improvements, localized date formatting, enhanced empty/error states, accessibility labels, contrast audit, and removal of redundant language toggle from detail screen.

## Technical Context

**Language/Version**: Kotlin 2.1.20 (KMP)
**Primary Dependencies**: Compose Multiplatform 1.8.0, Material3, Coil 3.1.0 (image loading), kotlinx-datetime
**Storage**: N/A — no new persistence (search is client-side filtering)
**Testing**: Visual inspection on both platforms + screen reader verification
**Target Platform**: Android 8+ (API 26), iOS 15+
**Project Type**: Mobile app (KMP cross-platform)
**Performance Goals**: Search filtering < 300ms perceived; transitions at 60fps
**Constraints**: No new dependencies unless absolutely needed; all changes in composeApp/
**Scale/Scope**: ~10 files modified, ~2 new components (skeleton card, search bar)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Spec-First | PASS | spec.md completed and validated before planning |
| II. Test-First | PASS | UI-only changes; visual + screen reader testing |
| III. Simplicity & YAGNI | PASS | Each improvement is minimal — no over-engineering |
| IV. Incremental Delivery | PASS | 8 independent user stories, each deliverable alone |
| V. Observability | PASS | Error messages improved (more specific); no silent failures |
| VI. Shared-First (KMP) | PASS | Date formatting utility in shared/; all UI in composeApp/ |

## Project Structure

### Documentation (this feature)

```text
specs/016-ui-improvements/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (files to modify/create)

```text
shared/src/commonMain/kotlin/com/gallr/shared/
├── data/model/
│   └── Exhibition.kt                  # MODIFIED: add localized date formatting

composeApp/src/commonMain/kotlin/com/gallr/app/
├── App.kt                             # MODIFIED: tab transitions, back handling
├── ui/components/
│   ├── ExhibitionCard.kt              # REVIEW: accessibility labels
│   ├── GallrEmptyState.kt             # REVIEW: already supports optional action
│   ├── GallrLoadingState.kt           # MODIFIED: replace with skeleton cards
│   ├── GallrNavigationBar.kt          # REVIEW: accessibility labels
│   └── SkeletonCard.kt               # NEW: ghost card with shimmer animation
├── ui/tabs/
│   ├── featured/FeaturedScreen.kt     # MODIFIED: pull-to-refresh, skeleton loading
│   ├── list/ListScreen.kt            # MODIFIED: pull-to-refresh, search bar, skeleton
│   └── map/MapScreen.kt              # MODIFIED: pull-to-refresh
├── ui/detail/
│   └── ExhibitionDetailScreen.kt      # MODIFIED: image placeholder, remove lang toggle, back gesture
└── viewmodel/
    └── TabsViewModel.kt              # MODIFIED: add search query state, refresh functions
```

**Structure Decision**: All changes within existing KMP architecture. One new component (SkeletonCard). Date formatting utility in shared/ per Principle VI.

## Complexity Tracking

No violations. All changes are incremental — no new patterns, abstractions, or dependencies.
