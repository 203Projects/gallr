# Contract: tab navigation shell

The app has no public API for this feature. The contract is between `AppNavigationState`, the root shell in
`App.kt` and the screens it hosts.

## Shell layout

```text
Box (root)
├── Scaffold(contentWindowInsets = WindowInsets(0), bottomBar = GallrNavigationBar if isTabBarVisible)
│   └── content: Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
│       └── SaveableStateProvider(key = entry key)            // R2
│           ├── trail empty  → tab top screen (inner Scaffold with the logo TopAppBar, no bottom bar)
│           └── trail top    → destination screen (unchanged screens, own Scaffold / insets)
├── crop tool (zIndex 2f), sign-up nudge sheet, splash        // still cover the bar
```

- `isTabBarVisible(imeBottomPx: Int): Boolean` is `imeBottomPx == 0` (R4).
- The entry key is `"tab-${tab.ordinal}"` for a tab's top screen and `"entry-${entry.id}"` otherwise.
- Content crossfades on a change of entry key, 150ms, as the tab content does today. It is an opacity change only,
  which DESIGN.md's Motion section sanctions for content swaps.

## Screen obligations

- A destination screen's back control calls the shell's `onBack`, which is `navigation.back()`.
- A screen with an inner layer (share preview, sheet, full-screen map, add-visits mode) registers its own
  `PlatformBackHandler` for that layer only.
- A screen with its own bottom action bar keeps `navigationBarsPadding()`; with the inset consumed it sits directly
  above the tab bar.
- No screen sets its own `WindowInsets` for the tab bar or reads the bar's height.

## Navigation calls (unchanged names, new semantics)

| Call | Today | After |
|------|-------|-------|
| `selectTab(i)` | Tab root, drops any destination | Switch tab; same tab clears its trail |
| `showExhibition(e, returnTo = …)` | Replaces the destination, remembers `returnTo` | Pushes; `returnTo` removed |
| `returnFromExhibition()`, `returnFromGallery()`, `returnFromRouteComposer()`, `returnFromPublicRoute()`, `showTabs()` | Restore a remembered destination | Replaced by `back()` |
| `showSignIn()` | Switch to MY, open the account view, remember the origin | Push `Account` on the current trail |
| `returnFromSignIn()` | Restore the origin | `back()`; the caller cancels the composer's pending action when still signed out |
| `showRecommendations()`, `showCollection()`, `showRoute()` | Force a tab, replace the destination | Push on the current trail |
| `showMyRoutes()`, `showAddPastVisits()` | Select MY with a request | Select MY, clear its trail, set the request |
