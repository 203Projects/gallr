# Android Editor Screen — Inset, Background, and Theme Fix

**Date:** 2026-05-13
**Priority:** P1
**Base branch:** `develop` (PR #59 merged spec 041 into develop on 2026-05-13)
**Source:** Bug report against the current `develop` build (post spec 041 merge). Screenshot showed `EditorSelectorScreen` rendering under the system status bar with a dim/translucent appearance on Android dark mode.

## Problem

When the user taps the `Editors ›` chip on the List tab, `EditorSelectorScreen` opens with four visual defects on Android:

1. **Status bar overlaps content.** The back arrow and `Editors` label render *underneath* the system clock, signal, and battery icons. The label is barely readable.
2. **Display cutouts not respected.** On notched / punch-hole devices the top bar can be clipped. In landscape the side cutouts would clip the back arrow.
3. **No bottom navigation-bar inset.** The last tile in a long list can sit under the gesture pill / 3-button nav bar.
4. **Dim / translucent appearance.** The body of the screen reads as a faded grey block rather than a solid background. In dark mode this looks like an "off" or half-loaded state.

The same defects apply to `EditorDetailScreen` (same composition pattern).

These screens replaced `GuestEditorBanner` in spec 041 and are the only screens in the app rendered *outside* the top-level `Scaffold` without inset handling. Every other detail screen (`ExhibitionDetailScreen`, `EventDetailScreen`) uses `Scaffold` + `TopAppBar` and renders correctly.

## Root cause

`composeApp/src/commonMain/kotlin/com/gallr/app/App.kt` renders `EditorSelectorScreen` and `EditorDetailScreen` as siblings of the main `Scaffold` inside an `AnimatedContent`. The screens themselves use:

```kotlin
Column(modifier = modifier.fillMaxSize()) {
  EditorTopBar(label = "Editors" / "에디터", onBack = onBack)
  // ... LazyColumn content
}
```

with `EditorTopBar` being a plain `Row` + `HorizontalDivider`. None of:

- `WindowInsets.systemBars` / `displayCutout` / `safeDrawing`
- `Scaffold` or `TopAppBar` (which auto-handle insets)
- A background fill matching `MaterialTheme.colorScheme.background`

…is applied. Because `MainActivity` calls `enableEdgeToEdge()`, the activity draws under the system bars by default. With no inset reservation, content slides under the status bar. With no background fill on the Column, areas the LazyColumn hasn't covered show through to the activity's default window background, which under edge-to-edge can read as a translucent overlay.

## Outcome

Both editor screens are wrapped in `Scaffold` with a Material3 `TopAppBar` (visually identical to the current `EditorTopBar`: back arrow + small uppercase label + hairline divider). The Scaffold:

- Applies `containerColor = MaterialTheme.colorScheme.background` — paints the window edge-to-edge in one solid color in both themes.
- Sets `contentWindowInsets = WindowInsets.safeDrawing` — reserves status bar, navigation bar, display cutouts, and IME on every side.
- Lets the `TopAppBar` consume the top + horizontal portion of those insets, forwarding the remainder (bottom + remaining horizontal) to the content slot as `innerPadding`.

Visual result, verified against the existing design tokens in `GallrColors`:

- **Light mode:** white (`#FFFFFF`) background; dark status-bar icons (driven by `MainActivity.enableEdgeToEdge` which already toggles `SystemBarStyle.auto` based on `isDarkTheme`).
- **Dark mode:** near-black (`#0A0A0A`, `DarkBackground`) background; light status-bar icons.
- Notched / punch-hole devices: top bar starts below the cutout; landscape side cutouts get horizontal padding.
- Gesture-nav and 3-button devices: last tile reserves bottom inset; gesture pill / nav bar never overlaps content.

## Decisions

Locked during brainstorming:

1. **Approach A — per-screen Scaffold.** Both `EditorSelectorScreen` and `EditorDetailScreen` wrap their content in their own `Scaffold` (rejected: refactoring `App.kt` to share the main Scaffold; rejected: applying `windowInsetsPadding` directly without Scaffold).
   - Mirrors `ExhibitionDetailScreen`. No new pattern introduced.
   - No changes in `App.kt`, ViewModels, repositories, or theme tokens.

2. **Keep minimal visual style.** The Material3 `TopAppBar` is configured to look identical to the current `EditorTopBar`:
   - Container: `MaterialTheme.colorScheme.background`
   - Title: small uppercase label (`labelMedium`, color `onSurfaceVariant`)
   - Navigation icon: `Icons.Outlined.ArrowBack`, color `onBackground`
   - Hairline `HorizontalDivider` directly below the bar, `outline.copy(alpha = 0.2f)`

3. **`WindowInsets.safeDrawing`** (not `systemBars`) — covers cutout + IME alongside status/nav bars.

4. **Background painted by Scaffold's `containerColor`**, not by an explicit `.background()` modifier on the Column — keeps one source of truth and matches `ExhibitionDetailScreen`.

5. **No new tokens.** All colors come from `GallrColors` (existing `LightColors` / `DarkColors` schemes).

6. **No behavior changes.** ViewModels, navigation wiring, data flow are untouched. This is a chrome-only fix.

7. **iOS verification is "open and glance."** iOS already respects safe areas through Scaffold; this fix only resolves Android-specific defects. No iOS-specific changes are made.

8. **No unit tests added.** Inset and Scaffold behavior is framework-owned (Material3 + AndroidX activity-compose). Layout testing in Compose against these contracts would be testing the framework.

## Information architecture

No change. Same nav graph as spec 041:

- List tab → `Editors ›` chip → `EditorSelectorScreen` (no selected chip state — portal).
- `EditorSelectorScreen` → tap tile → `EditorDetailScreen`.
- Back from `EditorDetailScreen` → `EditorSelectorScreen`.
- Back from `EditorSelectorScreen` → List tab.

The two sections within the selector (`Currently curating`, `Past editors`) and their sort order are unchanged.

## Components

### `EditorTopBar` (rewritten)

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        TopAppBar(
            title = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
            windowInsets = TopAppBarDefaults.windowInsets,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }
}
```

### `EditorSelectorScreen`

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSelectorScreen(
    viewModel: EditorSelectorViewModel,
    onBack: () -> Unit,
    onEditorTap: (editorId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val lang by viewModel.language.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            EditorTopBar(
                label = if (lang == AppLanguage.KO) "에디터" else "Editors",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            is EditorSelectorState.Loading -> {
                Box(Modifier.padding(innerPadding).fillMaxSize())
            }
            is EditorSelectorState.Error -> {
                GallrEmptyState(
                    message = if (lang == AppLanguage.KO) "에디터를 불러오지 못했습니다."
                              else "Could not load editors.",
                    actionLabel = if (lang == AppLanguage.KO) "다시 시도" else "Retry",
                    onAction = { viewModel.loadEditors() },
                    modifier = Modifier.padding(innerPadding).fillMaxSize(),
                )
            }
            is EditorSelectorState.Success -> {
                LazyColumn(
                    modifier = Modifier.padding(innerPadding).fillMaxSize(),
                    contentPadding = PaddingValues(bottom = GallrSpacing.md),
                ) {
                    item {
                        Text(
                            text = if (lang == AppLanguage.KO) "현재 큐레이션" else "Currently curating",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = GallrSpacing.screenMargin,
                                top = GallrSpacing.lg,
                                bottom = GallrSpacing.sm,
                            ),
                        )
                    }
                    items(s.active, key = { it.id }) { editor ->
                        EditorTile(
                            editor = editor,
                            lang = lang,
                            exhibitionCount = s.exhibitionCounts[editor.id] ?: 0,
                            isPast = false,
                            onClick = { onEditorTap(editor.id) },
                        )
                    }
                    if (s.past.isNotEmpty()) {
                        item {
                            Text(
                                text = if (lang == AppLanguage.KO) "지난 에디터" else "Past editors",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = GallrSpacing.screenMargin,
                                    top = GallrSpacing.lg,
                                    bottom = GallrSpacing.sm,
                                ),
                            )
                        }
                        items(s.past, key = { it.id }) { editor ->
                            EditorTile(
                                editor = editor,
                                lang = lang,
                                exhibitionCount = s.exhibitionCounts[editor.id] ?: 0,
                                isPast = true,
                                onClick = { onEditorTap(editor.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
```

### `EditorDetailScreen`

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorDetailScreen(
    viewModel: EditorDetailViewModel,
    bookmarkedIds: Set<String>,
    onToggleBookmark: (String) -> Unit,
    onBack: () -> Unit,
    onExhibitionTap: (Exhibition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor by viewModel.editor.collectAsState()
    val exhibitions by viewModel.exhibitions.collectAsState()
    val lang by viewModel.language.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            EditorTopBar(
                label = if (lang == AppLanguage.KO) "에디터" else "Editor",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            editor?.let { ed ->
                EditorBanner(editor = ed, lang = lang, exhibitionCount = exhibitions.size)
            }
            if (exhibitions.isEmpty()) {
                GallrEmptyState(
                    message = if (lang == AppLanguage.KO) "선택된 전시가 없습니다"
                              else "No exhibitions in this list",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = GallrSpacing.md),
                ) {
                    items(exhibitions, key = { it.id }) { exhibition ->
                        ExhibitionCard(
                            exhibition = exhibition,
                            isBookmarked = exhibition.id in bookmarkedIds,
                            onBookmarkToggle = { onToggleBookmark(exhibition.id) },
                            onTap = { onExhibitionTap(exhibition) },
                            lang = lang,
                        )
                    }
                }
            }
        }
    }
}
```

## What does not change

- `EditorSelectorViewModel`, `EditorDetailViewModel`
- `EditorRepository`, `EditorRepositoryImpl`, `EditorApiClient`
- `Editor` model, classification, localization
- `EditorTile`, `EditorBanner` internals
- `App.kt` navigation wiring (`editorSelectorOpen`, `selectedEditorId`, `AnimatedContent` transitions)
- Theme tokens in `GallrColors`, `GallrTypography`, `GallrSpacing`

## Files changed

- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorTopBar.kt`
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorSelectorScreen.kt`
- `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/EditorDetailScreen.kt`

Three files, all in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/editor/`.

## Testing

### Unit tests

None added. Existing tests (`EditorSelectorViewModelTest`, `EditorDetailViewModelTest`, `EditorClassificationTest`, `EditorLocalizationTest`) remain untouched.

### Manual QA (Android)

Run on at least one device per row, both themes.

| Device class                  | Selector — checks                                                 | Detail — checks                                                  |
| ----------------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------- |
| Gesture-nav phone (no cutout) | Back arrow below status icons; solid background; last tile clears gesture bar | EditorBanner below status icons; last ExhibitionCard clears gesture bar |
| Gesture-nav phone (notched)   | Status-bar icons contrast against background; no clipping under notch in portrait or landscape | Same                                                              |
| 3-button nav phone            | List reserves space above three-button row                        | Same                                                              |
| Landscape                     | Back arrow not under side cutout; horizontal content not clipped  | Same                                                              |

### iOS

Open `EditorSelectorScreen` and `EditorDetailScreen` once in each theme. Confirm no visual regression. iOS safe areas are honored automatically by `Scaffold`.

## Out of scope

- Animated transitions when entering/leaving the editor screens (already handled by `AnimatedContent` in `App.kt`).
- The Editors chip in `ListScreen`.
- Any change to other detail screens (`ExhibitionDetailScreen` is already correct; `EventDetailScreen` is not in scope for this bug).
- iOS-specific changes (the reported defects are Android-only).
- Unit tests for layout/inset behavior (framework-owned contracts).

## Risks

- **None to data or business logic** — chrome-only fix.
- **Visual regression on other detail screens?** No — only `EditorTopBar`, `EditorSelectorScreen`, `EditorDetailScreen` are touched. `EditorTopBar` is private to the editor screens (no other consumer).
- **Material3 TopAppBar default height differs from current EditorTopBar height** — verified during QA; if the bar is noticeably taller, we can switch to `CenterAlignedTopAppBar` with a custom `windowInsets` and a more compact title row. Default `TopAppBar` height matches Android Material3 standards which is what we want.
