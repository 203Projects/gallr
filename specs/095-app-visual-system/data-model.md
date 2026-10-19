# Data Model: Home style across the app

No stored data. The "model" is the set of visual tokens the app reads.

## GallrShapeTokens (new, `ui/theme/GallrShapeTokens.kt`)

| Token | Value | Used for |
|-------|-------|----------|
| `cardRadius` | 16dp | cards, tiles, framed panels, the route map panel, dialogs, bottom sheets' top corners |
| `smallRadius` | 12dp | buttons, fields, chips, pills, counters, badges, toggles, checkbox boxes, small thumbnails, menus, snackbars, map controls, skeleton bars |
| `card` | `RoundedCornerShape(cardRadius)` | |
| `small` | `RoundedCornerShape(smallRadius)` | |
| `sheetTop` | `RoundedCornerShape(topStart = cardRadius, topEnd = cardRadius)` | bottom sheets |

## GallrShapes (changed, `ui/theme/GallrTheme.kt`)

| Material slot | Before | After |
|---------------|--------|-------|
| `extraSmall`, `small`, `medium` | 0dp | `smallRadius` |
| `large`, `extraLarge` | 0dp | `cardRadius` |

## HomeVisualTokens (changed)

`cornerRadius` and `innerRadius` read `GallrShapeTokens.cardRadius` / `smallRadius`; values unchanged (16dp, 12dp).
Scrim alphas, dot sizes and `DOT_LIMIT` stay.

## Scrim modifiers (moved)

`ui/tabs/home/HomeScrim.kt` → `ui/theme/CaptionScrim.kt`, `internal`, same behaviour: `bottomScrim`, `topScrim`, `flatScrim`,
pressed alpha. Home's imports change; nothing it draws changes.

## Map label language

Unchanged from `c6a8e605`: `localizedMapStyle(styleJson, language)` and `rememberQuietSeoulMapStyle(language)`.
