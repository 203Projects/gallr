# Plan: Local discovery experience

## Architecture

- `LocalDiscoveryViewModel` combines the organic catalogue state, bookmarks,
  visits, followed galleries, language, and Seoul date on `Dispatchers.Default`.
- It owns the last immutable `ExhibitionRecommendationIndex` and offers it back
  to `prepare`; the recommender itself has no mutable cache.
- Recommendation state updates automatically. Route state is an explicit
  edit → plan → ready/insufficient workflow using `NeighborhoodRoutePlanner`.
- `RecommendationsScreen` and `RoutePlannerScreen` render immutable state only.
- Featured and Map provide entry callbacks. The Map action reads its current
  MapLibre camera target at the moment of the tap.
- Existing `ExternalMapLauncher` opens one ordered stop. No multi-stop provider
  URL or hosted directions adapter is added.

## Presentation

- Follow `DESIGN.md`: sharp geometry, monochrome surfaces, 8pt rhythm, no
  shadows, and orange only for active selection indicators.
- Recommendation cards show localized measured reasons, never numeric scores.
- Route modes use one selectable radio group. Stops are explicitly numbered and
  warnings remain visible rather than hidden in help text.
- Distances below 1 km use rounded metres; other distances use one decimal km.
  Every route distance/time label says estimated or uses `~`.

## Integration

- Organic inputs come only from `TabsViewModel.allExhibitions` and user-owned
  repositories. `PromotionRepository` is not a dependency.
- Recommendation opens use `DiscoveryKind.RECOMMENDATION`; route opens use
  `DiscoveryKind.ROUTE`.
- Existing analytics tracker calls are wired at display, impression, successful
  plan, and successful map-handoff boundaries without schema changes.

## Verification

1. Focused ViewModel, presentation, navigation, and analytics tests.
2. Shared and Compose all-tests plus ktlint.
3. Android unit/lint/assembly and release-manifest checks.
4. iOS simulator host build and bilingual runtime accessibility/visual pass.
5. Confirm both mobile analytics release flags and the server kill switch remain
   disabled, and no recommendation/directions credential exists.
