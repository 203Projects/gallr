# Implementation Plan: UI Polish and Uniform Theme Across Tabs

**Branch**: `015-ui-polish-uniformity` | **Date**: 2026-03-24 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/015-ui-polish-uniformity/spec.md`

## Summary

Standardize typography, spacing, and visual hierarchy across all three tabs (Featured, List, Map) and the detail screen. Replace hardcoded dp values with design tokens, unify tab header styles, and establish a consistent typography hierarchy for exhibition metadata (name, venue, dates).

## Technical Context

**Language/Version**: Kotlin 2.1.20 (KMP)
**Primary Dependencies**: Compose Multiplatform 1.8.0, Material3
**Storage**: N/A — UI-only changes
**Testing**: Visual inspection on both platforms
**Target Platform**: Android 8+ (API 26), iOS 15+
**Project Type**: Mobile app (KMP cross-platform)
**Performance Goals**: No performance impact — token replacement only
**Constraints**: Must not introduce visual regressions in light or dark theme
**Scale/Scope**: ~6 files modified, 0 new files (except potentially adding spacing tokens)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Spec-First | PASS | spec.md completed and validated before planning |
| II. Test-First | PASS | UI-only changes; visual inspection is the test method |
| III. Simplicity & YAGNI | PASS | Replacing hardcoded values with existing tokens; no new abstractions |
| IV. Incremental Delivery | PASS | US1 (headers) is independently deliverable; US2 (spacing) and US3 (typography) can follow |
| V. Observability | PASS | No new operations to observe — UI styling only |
| VI. Shared-First (KMP) | PASS | All changes are in composeApp/ (UI layer); no business logic affected |

## Project Structure

### Documentation (this feature)

```text
specs/015-ui-polish-uniformity/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (files to modify)

```text
composeApp/src/commonMain/kotlin/com/gallr/app/
├── ui/theme/
│   └── GallrSpacing.kt              # MODIFIED: add missing tokens if needed
├── ui/tabs/
│   ├── featured/FeaturedScreen.kt    # MODIFIED: standardize header
│   ├── list/ListScreen.kt           # MODIFIED: add header if missing
│   └── map/MapScreen.kt             # MODIFIED: standardize header, replace hardcoded dp
├── ui/components/
│   ├── GallrNavigationBar.kt        # MODIFIED: replace hardcoded 14.dp
│   └── ExhibitionCard.kt            # REVIEW: verify typography consistency
├── ui/detail/
│   └── ExhibitionDetailScreen.kt    # REVIEW: verify typography hierarchy
└── ui/tabs/map/
    └── MapScreen.kt                 # MODIFIED: replace hardcoded spacing values
```

**Structure Decision**: All changes within the existing composeApp/src/commonMain UI layer. No new files needed except potentially a spacing token addition.

## Complexity Tracking

No violations. Pure refactoring — replacing inconsistent values with consistent tokens.
