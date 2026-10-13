# Contract: Home visual tokens (revision 2, 2026-10-12)

`composeApp/src/commonMain/kotlin/com/gallr/app/ui/theme/HomeVisualTokens.kt` is the only place these
values live in code; DESIGN.md "Home tab" states the same values and
`HomeTokensDocumentationTest` fails when they drift apart. They apply to the Home tab only
(`HomeOnlyShapesTest` fails on a rounded shape or a Home token anywhere else).

| Token | Value | Meaning |
|---|---|---|
| `cornerRadius` | 16dp | Every Home card and tile |
| `innerRadius` | 12dp | Counter pill, chips, stop covers |
| `SCRIM_ALPHA` | 0.55 | Black scrim under a caption, reached at the caption's edge and held under it |
| `SCRIM_PRESSED_ALPHA` | 0.70 | The scrim while a banner is pressed |
| `SCRIM_LEAD` | 0.30 | How far beyond the caption's edge the scrim fades, as a fraction of the cover |
| `SCRIM_START` | 0.44 | Latest start of a bottom scrim, as a fraction from the top |
| `dotSize` / `dotSpacing` | 6dp / 6dp | Position dots |
| `activeDotWidth` | 18dp | The current story's pill |
| `DOT_LIMIT` | 10 | No dots beyond this many cards |

## Scrim geometry

```kotlin
data class ScrimBand(val fadeEdge: Float, val captionEdge: Float)
fun bottomScrimBand(coverHeightPx: Int, captionHeightPx: Int): ScrimBand
fun topScrimBand(coverHeightPx: Int, captionHeightPx: Int): ScrimBand
```

`bottomScrimBand`: full strength from the measured caption's top edge down, fading in over
`SCRIM_LEAD` above it and starting no lower than `SCRIM_START`; before measurement it covers the
default band. `topScrimBand` mirrors it for a caption at the top of a cover. The drawing modifiers
(`bottomScrim`, `topScrim`, `flatScrim` in `ui/tabs/home/HomeScrim.kt`) draw over their content, so
a placeholder is darkened as well as a loaded cover.

## Promises tested

- White text over a pure white cover under `SCRIM_ALPHA` is at least 4.5:1
  (`HomeVisualTokensTest.theScrimKeepsWhiteCaptionsReadableOnTheWorstCover`).
- A caption taller than the default band moves the fade edge up; a caption as tall as the cover
  clamps it (`aBottomScrimIsFull…`, `aTopScrimMirrorsIt`).
- Pressing only darkens (`SCRIM_PRESSED_ALPHA > SCRIM_ALPHA`).

## Withdrawn in revision 2

Blur radii, the darkening floor on the theme background, the poster tint alpha and the tab bar
tint: there is no blur, tint or translucent surface on Home. The Haze dependency, `HomeBlur.kt`,
`PosterTint.kt` and the grain-wash shader (`GrainWash*.kt`) are removed.
