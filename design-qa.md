# Design QA — 1.8.0 stacked marker outline

## Source truth and state

- Expected marker: `/var/folders/v_/h8z5mkcs6lz5bvzlpn3xgssw0000gn/T/TemporaryItems/NSIRD_screencaptureui_AiOiWd/Screenshot 2026-08-13 at 10.45.05 AM.png` (156 × 190 px).
- User-reported broken marker: `/var/folders/v_/h8z5mkcs6lz5bvzlpn3xgssw0000gn/T/TemporaryItems/NSIRD_screencaptureui_s17z6R/Screenshot 2026-08-13 at 10.53.30 AM.png` (106 × 114 px).
- Historical source: release 1.8.0 commit `3e1c102`, `StackedGallrPinGlyph` and `GallrPinGlyphWithHalo`.
- Implementation state: iPhone 17 simulator, Map tab, `전체 전시`, initial zoom, native 3× capture.

## Evidence

- Full implementation: `/tmp/gallr-map-outline-final-measured.png` (1206 × 2622 px).
- Focused map crop: `/tmp/gallr-map-outline-final-measured-crop.png` (240 × 240 px).
- Tight marker crop: `/tmp/gallr-marker-outline-final-measured-tight.png` (116 × 116 px).
- Three-way comparison: `/tmp/gallr-marker-outline-final-measured-qa.png` (480 × 160 px; expected, broken, fixed).
- Density normalization: each tight marker was scaled to a 160 × 160 comparison cell. Positional measurements use component-relative centers, so crop translation and 2×/3× screenshot density do not affect the result.

## Pixel measurements

After aligning on the rear pin center, expected versus fixed relative centers are:

| Element | Expected (px) | Fixed (px) | Delta (px) |
|---|---:|---:|---:|
| Rear pin | (0.0, 0.0) | (0.0, 0.0) | (0.0, 0.0) |
| Front pin | (24.5, 27.4) | (24.0, 27.0) | (-0.5, -0.4) |
| Count badge | (47.8, 5.8) | (47.9, 6.3) | (+0.1, +0.5) |

- Maximum positional deviation: 0.5 px after normalization.
- Expected white-center bounds: 15 × 15 px rear and 16 × 15 px front.
- Fixed white-center bounds: 14 × 14 px for both; the remaining 1–2 px difference is antialiasing/density rounding, not layout drift.
- Outline geometry: each 24dp black pin is centered inside its 28dp white backing pin. The MapLibre bottom-anchor correction moves the black layer 2dp upward, so black and white layer centers are identical on both axes. The white backing therefore forms a uniform 2dp outline rather than a translated shadow.

## Findings

- Outline/shadow: passed. The detached white silhouette from the broken capture is removed; white follows the pin perimeter evenly, including the point.
- Stacking/layout: passed. Front and rear center deltas match the expected marker within 0.5 px.
- Count badge: passed. Its relative center matches within 0.5 px and its size/outline is visually consistent with the expected marker.
- Fonts/typography: passed. Count uses the existing map font at 8sp; digit antialiasing differences are density-dependent only.
- Spacing/layout rhythm: passed. The 1.8.0 diagonal stack is preserved and no caption spacing changed.
- Colors/tokens: passed. White outline, black unsaved pin, and existing orange saved-pin state retain the established tokens.
- Image quality/assets: passed. Existing vector/SDF location assets remain crisp; no raster substitute or shadow effect is used.
- Copy/content: passed. Titles, counts, and accessibility labels remain unchanged.

## Comparison history

1. Earlier MapLibre conversion bottom-anchored both the 28dp white pin and 24dp black pin. Unlike 1.8.0's centered Compose boxes, this displaced the larger white layer upward and created the reported shadow (P1).
2. Centered each black pin inside its white backing by applying the required 2dp vertical correction. First measured capture removed the shadow but showed the front center 3px high and badge center 5.5px high (P2).
3. Adjusted the front stack by 1dp, badge by 2dp, and white-center radii to 2.5dp. Final normalized center deltas are all ≤0.5 px, with no remaining actionable P0/P1/P2 finding.

## Verification

- `ANDROID_HOME=/Users/hanshin/Library/Android/sdk ./gradlew composeApp:ktlintCheck composeApp:testAndroidHostTest` — passed.
- XcodeBuildMCP Debug build/install/launch on iPhone 17 — passed.
- Runtime accessibility snapshot and 9-item group sheet interaction — passed.

final result: passed
