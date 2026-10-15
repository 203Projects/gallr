# Store assets

The App Store and Google Play listing set, composed from real captures of the app. Design and rules:
[the store creatives spec](../docs/superpowers/specs/2026-10-14-store-creatives-refresh-design.md).
Nothing here uploads or publishes; that stays a separate owner action.

The screenshots follow the live listing's look: a black field, a heavy two-line headline per frame,
the listing's italic English line under it, and one real screen shown large (tilted, cropped to the
edges, or straight). Six frames: Home as one panorama across frames 1 and 2, closing this week, the
map, a curator's note and saved exhibitions. Captions name the feature only; exhibitions appear in
the screens, never as text. The art direction came from the taste skill's "sentence-as-layout" draw
(seed 539189386), without its inline device.

## What it produces

| Asset | Size | Where it goes | File |
|-------|------|---------------|------|
| iPhone 6.9" screenshots ×6 | 1320×2868 | App Store Connect → version → iPhone | `iosApp/fastlane/screenshots/<locale>/iPhone_6.9-*.png` |
| iPhone 6.3" screenshots ×6 | 1206×2622 | App Store Connect → version → iPhone 6.3" (optional; scaled from 6.9" when absent) | `iosApp/fastlane/screenshots/<locale>/iPhone_6.3-*.png` |
| iPad 13" screenshots ×6 | 2064×2752 | App Store Connect → version → iPad | `iosApp/fastlane/screenshots/<locale>/iPad_13-*.png` |
| Product page header | 3840×1646 still + 30 s loop | App Store Connect → Header and Search Results → Header | `dist/appstore/<locale>/header-21x9.png`, `dist/video/<locale>/header-21x9.mp4` |
| Search results | 3840×2560 | App Store Connect → Header and Search Results → Search Results | `dist/appstore/<locale>/search-3x2.png` |
| App preview, iPhone | 886×1920, 16 s | App Store Connect → version → iPhone → App Previews | `dist/video/<locale>/app-preview-iphone.mp4` |
| App preview, iPad | 1200×1600, 16 s | App Store Connect → version → iPad → App Previews | `dist/video/<locale>/app-preview-ipad.mp4` |
| Play feature graphic | 1024×500 | Play Console → Main store listing → Feature graphic | `dist/play/<locale>/feature-graphic.png` |
| Play phone screenshots ×6 | 1080×1920 | Play Console → Main store listing → Phone screenshots | `dist/play/<locale>/phone-*.png` |
| Play promo video | 1080×1920, 16 s | Upload to YouTube (unlisted, ads off), paste the URL in Play Console | `dist/video/<locale>/play-promo.mp4` |

`<locale>` is `ko` or `en-US` (App Store Connect's folder names, which `fastlane upload_screenshots`
reads). Every output, and the raw captures, is gitignored (the PNG set alone is over 100 MB):
regenerate it with the commands below rather than committing it. Set the app preview poster
frame at 1 s (the Home tab) in App Store Connect, and check the header and search assets in its
Preview tool on iPhone and iPad before submitting.

## Regenerate

Node 22 (`.node-version`), ffmpeg, Xcode and the two dedicated simulators, created once:

```bash
xcrun simctl create "Gallr Store iPhone 17 Pro Max" "iPhone 17 Pro Max"
xcrun simctl create "Gallr Store iPad Pro 13 M5" "iPad Pro 13-inch (M5)"
```

Then, from `store-assets/`:

```bash
npm install
node fetch-catalogue.mjs        # public catalogue + covers for the header wall (data/, gitignored)
./capture-ios.sh --build        # raw UI captures, both devices and locales (captures/ios/)
./record-preview.sh             # preview footage and shot markers (captures/preview/, gitignored)
node build.mjs                  # screenshots, header, search, feature graphic
node video.mjs                  # app previews, Play promo, header loop
node validate.mjs               # every output against the store specs
npm test
```

Captures run against production data on a fresh install per locale, in light appearance, with the
location set to Hannam-dong. Every script stops on a missing or failed capture, so a partial set
never reaches `iosApp/fastlane/screenshots/`, whose upload lane overwrites the whole live set.

**Required before any upload: look at every frame.** Open each raw capture and each video from start
to loop point. The validator checks sizes, alpha, codecs, frame rates, tracks and that every slot
exists; it cannot see a screen that had not finished loading, an error banner, or a cover that does
not suit the App Store's 4+ rating. Home, List, Map and My show whatever production holds that day, so
screen those covers as well as the header wall's; recapture on another day if one is unsuitable.

## Changing things

- **Captions:** `copy.json` holds every headline, English line and creative-asset caption in both
  languages. A headline wider than its frame fails the render rather than shipping clipped.
- **Screens:** the capture flow is `iosApp/iosAppUITests/ScreenshotTests.swift`
  (`testStoreCaptures` for the five stills, `testPreviewFlow` for the preview storyboard, whose shots
  and lengths live in `video.mjs`: Home, a curator's note, closing this week, the map).
- **Frame compositions:** `COMPOSITIONS` in `creative.mjs` places each screen, per phone and tablet
  layout, in 1320-wide units; `templates/creative.html` draws them.
- **Header wall:** `WALL_WORKS` in `creatives.mjs` lists the covers on the wall. Every cover shown must
  suit the App Store's 4+ rating whatever the app's own rating; exhibitions that close drop out of the
  catalogue and the build names the one to replace.
- **Sizes:** canvas sizes are in `targets.mjs`. Every canvas is laid out in the same 1320-wide units
  and rendered at its own zoom, so nothing is resampled. The header, search and feature graphic
  templates use the DESIGN.md tokens in `templates/base.css`.

## Rules the set follows

Apple: real UI in screenshots and previews, no prices, URLs, awards, Apple badges or other
platforms, and no copyright symbols in the header and search assets (the map's own OpenStreetMap
attribution stays in the Map screenshot, where the real UI shows it), focal content inside the safe box (header 1646×661, search 2168×1030, centred),
no alpha, previews 15–30 s at ≤30 fps with an audio track, loops without a seam. Google: little text
on screenshots, no "best/#1/new/free", no plain white or black feature graphic. DESIGN.md: monochrome
chrome, the accent only where the app itself shows it, Inter and Gothic A1 (ExtraBold for the
headlines, loaded from Google Fonts at render; the app bundles Medium) with the bundled Playfair
Display Italic for the English line.
