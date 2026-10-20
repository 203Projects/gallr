# Research: Always-visible tab bar

All decisions below are grounded in the current code: `AppNavigationState.kt` (one `destination` plus private back
targets), `App.kt` (an `AnimatedContent` over the destination whose `Tabs` branch alone owns the `Scaffold` with
`GallrNavigationBar`), `ProfileTab.kt` (sign-in shown through local `showAccount` state) and the destination screens'
own `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)`.

## R1. A trail per tab inside `AppNavigationState`, not a navigation library

**Decision**: Keep the state-driven navigation the guide prescribes (CLAUDE.md: "Navigation is state-driven
`mutableStateOf` in `App.kt` (no NavController)"). Replace the single `destination` and its private back targets
with four trails, one per tab, each an ordered list of entries `NavEntry(id, destination)`. `destination` becomes a
derived value: the top entry of the selected tab's trail, or `Tabs` when the trail is empty. Every `show…` call pushes
onto the selected tab's trail; `back()` pops it.

**Rationale**: The navigation surface is small (eleven destinations, one class) and already unit-tested as plain
state. A trail per tab expresses the owner's rules directly: switching tabs keeps every trail, re-tapping a tab
clears its trail, back pops only the current trail. The many `returnTo` / `return…` pairs collapse into one `back()`.

**Alternatives considered**: JetBrains `navigation-compose` with one `NavHost` per tab (a new dependency, a second
navigation model next to the existing one, and typed-route serialisation for `Exhibition` arguments); keeping the
single destination and only drawing the bar over it (the owner chose "each tab remembers", which a single destination
cannot express).

## R2. Screen state survives a tab switch through a saveable state holder

**Decision**: Render the visible entry inside `SaveableStateHolder.SaveableStateProvider(key = entry key)`, where
the key is the entry's id (or `tab-N` for a tab's top screen). When an entry is popped or a trail is cleared, remove
its saved state. ViewModels keep their current `viewModel(key = …)` scoping.

**Rationale**: When the visitor switches tabs, the previous tab's screen leaves composition. Its scroll positions
(`rememberLazyListState`, `rememberScrollState`) and `rememberSaveable` values come back through the holder, which
satisfies "the same screen at the same scroll position". Unsaved route input already lives in
`PersonalRouteComposerViewModel` and its draft repository, so it survives regardless.

**Consequence**: Screen-local values held in plain `remember` (for example the exhibition page's
`showSharePreview`) reset after a tab switch. The plan converts only those a visitor would notice
(`showSharePreview`, the full-screen map's `mapExpanded` is already `rememberSaveable`) and leaves transient flags
(`isVisitSaving`) as they are.

**Alternatives considered**: Keeping every tab's top screen composed but hidden (four live maps and pagers at once;
memory and work on the hidden tabs); moving more screen state into ViewModels (a wide change for little gain).

## R3. One root `Scaffold` owns the bar; screens consume its inset

**Decision**: Move `GallrNavigationBar` out of the `Tabs` branch into a root `Scaffold(bottomBar = …,
contentWindowInsets = WindowInsets(0))` that wraps the content area. The content gets
`Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)`. The tab top screens keep their `TopAppBar` in an
inner `Scaffold` without a bottom bar. Destination screens are unchanged: their `safeDrawing` insets and their own
bottom bars' `navigationBarsPadding()` see the bottom inset already consumed, so the composer's 저장 / 공유 bar and the
preview's 복사 bar sit directly above the tab bar without double padding.

**Rationale**: `consumeWindowInsets` is the standard way to tell nested screens that an ancestor already paid for
the inset. It keeps every screen's own inset handling working with no per-screen edits beyond the bar move.

**Overlays**: The crop tool (`zIndex(2f)`), the sign-up nudge sheet and the splash stay siblings of the root
`Scaffold` and keep covering everything, as spec FR-001 allows. `ModalBottomSheet`s and `AlertDialog`s cover the bar
on their own. `RouteMapFullScreen` is drawn inside its screen's `Box`, which now sits in the content area above the
bar, so it fills the space above the bar without changes (FR-012).

## R4. The bar steps aside while the keyboard is open

**Decision**: Hide the bar when the IME inset is non-zero: `WindowInsets.ime.getBottom(density) > 0`, read in common
code. No animation: the bar is simply not laid out, and the root's `innerPadding` drops to zero, so a screen's
`safeDrawing` padding then includes the keyboard.

**Rationale**: Both hosts deliver IME insets to Compose: Android is edge-to-edge with `adjustResize`, and the iOS host
ignores the keyboard safe area so Compose receives it. A pure function `isTabBarVisible(imeBottom: Int, …)` makes the
rule testable.

## R5. Sign-in opens on top of the screen that asked (spec amendment)

**Decision**: Add an `Account` destination that renders today's account view (sign-in when anonymous, the profile
when signed in). `showSignIn()` pushes it onto the **current** tab's trail; leaving it pops back to the screen that
asked, and the composer's pending save or share is cancelled when the visitor leaves still signed out, as today. The
MY tab's own account button pushes the same destination onto MY's trail, replacing `ProfileTab`'s local
`showAccount`.

**Rationale**: Spec FR-008 first said sign-in "switches to MY". With trails that breaks a common path: the composer
is often open in MY's own trail, so MY's top screen cannot show the account view without throwing the composer
away. Opening sign-in where it was asked keeps the owner's actual rule (sign-in returns to where it was requested)
and keeps back inside one tab. Spec FR-008, User Story 3 scenario 4 and the cross-tab edge case are amended to match.

**Alternatives considered**: Switching to MY and pushing the account there, then switching back afterwards (two tab
jumps for one sign-in, and the bar highlights a tab the visitor did not choose).

## R6. Requests that are about MY, and the single route draft

**Decision**:
- `showMyRoutes()` (the route sheet's 모두 보기) and `showAddPastVisits()` switch to MY and clear MY's trail so its
  top screen can honour the request, as re-tapping MY would.
- There is one route draft, so the composer is open in at most one trail: `showRouteComposer()` while it is already
  open in another tab switches to that tab instead of opening a second copy.
- `showRecommendations()`, `showCollection()` and `showRoute()` stop forcing a tab; they open in the current tab
  (they are all opened from Home today, so nothing visible changes).
- Opening the destination already on top of the current trail does nothing (FR-009).
- The notification deep link and the sign-up nudge keep calling `selectTab`.

## R7. Back: one handler for trails, screen handlers stay for their own layers

**Decision**: The root registers one `PlatformBackHandler`, enabled while the current trail is non-empty, that calls
`back()`. Destination branches drop their own `return…` handlers, except where a screen has an inner layer: the
exhibition page's share preview, sheets, the full-screen map and the MY tab's add-visits and add-galleries modes keep
theirs. Those are composed later than the root handler, so they win on Android. At a tab's top screen no handler is
enabled, which keeps today's Android behaviour (FR-007). On iOS `PlatformBackHandler` is a no-op and on-screen back
buttons call `back()`.

## R8. What survives what

**Decision**: Keep `rememberAppNavigationState()` as plain `remember`. Rotation does not recreate the Android activity
(`configChanges="orientation|screenSize|screenLayout|keyboardHidden"`) and iOS does not recreate the view controller,
so trails survive rotation and background (FR-013). After the system ends the process, the app restarts at Home's top,
as today (spec Assumptions); `Exhibition` arguments are not saveable without a serialiser, and nothing in the spec
needs process-death restoration.

## R9. Analytics and the planner

**Decision**: The screen-view effect keyed on `destination` and `selectedTab` keeps working because `destination`
stays a readable property, now derived (FR-015): switching back to a tab whose top screen is an exhibition reports
that screen again, which is what "the screen being viewed" means. The route planner, unreachable today, is converted
like every other destination and stays unreachable.
