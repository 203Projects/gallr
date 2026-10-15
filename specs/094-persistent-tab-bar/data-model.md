# Data Model: Always-visible tab bar

Navigation state only; nothing is stored on disk or on the server. All types live in
`composeApp/src/commonMain/kotlin/com/gallr/app/` next to `AppNavigationState`.

## AppTab

The four tabs, in bar order. Replaces the bare indices `0..3` (the constants `PROFILE_TAB_INDEX` and
`MY_LIST_TAB_INDEX` map onto it).

| Value | Bar label (KO / EN) | Index |
|-------|---------------------|-------|
| `HOME` | 홈 / HOME | 0 |
| `LIST` | 목록 / LIST | 1 |
| `MAP` | 지도 / MAP | 2 |
| `MY` | MY / MY | 3 |

## AppDestination (changed)

The existing sealed interface, with:

- **Removed:** `Tabs`. A tab's top screen is the empty trail, not a destination.
- **Added:** `data object Account`: today's account view (sign-in when anonymous, the profile when signed in),
  previously `ProfileTab`'s local `showAccount` (research R5).
- **Unchanged:** `ExhibitionDetail`, `GalleryDetail`, `EventDetail`, `EditorSelector`, `Settings`,
  `Recommendations`, `RoutePlanner`, `RouteComposer`, `PublicRoutePreview`, `EditorDetail`, `HomeCollection`.

## NavEntry

| Field | Type | Rule |
|-------|------|------|
| `id` | `Long` | Unique for the life of the state, increasing; the saveable-state key is `"entry-$id"` |
| `destination` | `AppDestination` | What the entry shows |

## AppNavigationState (changed)

| Member | Kind | Rule |
|--------|------|------|
| `selectedTab` | `AppTab`, observable | The tab the bar highlights |
| `trail(tab)` | `List<NavEntry>`, observable | Ordered, oldest first; empty means the tab's top screen |
| `currentEntry` | `NavEntry?`, derived | Last entry of the selected tab's trail |
| `destination` | `AppDestination?`, derived | `currentEntry?.destination`; null at a tab's top screen |
| `canGoBack` | `Boolean`, derived | The selected tab's trail is non-empty |
| `myTabRequest` | `MyTabRequest?`, observable | Unchanged, except `SIGN_IN` is removed (sign-in is the `Account` destination) |

### Transitions

| Call | Effect |
|------|--------|
| `selectTab(tab)` | Another tab: select it, trails untouched. The selected tab: clear its trail (FR-006) |
| `open(destination)` (each `show…` call) | Push onto the selected tab's trail, unless it equals the current destination (FR-009) |
| `back()` | Pop the selected tab's trail; returns false at a tab's top screen |
| `showRouteComposer()` | If a trail already holds `RouteComposer`, select that tab; otherwise push |
| `showSignIn()` | Push `Account` onto the selected tab's trail |
| `showMyRoutes()`, `showAddPastVisits()` | Set the request, select MY and clear MY's trail |
| `onEntriesDropped` | Callback with the ids of popped or cleared entries, so their saved state is removed |

### Invariants

- Every entry belongs to exactly one trail.
- `RouteComposer` appears in at most one trail.
- Two adjacent entries in a trail never hold equal destinations.
- Clearing or popping never touches another tab's trail.
