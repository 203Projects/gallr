# Store creatives refresh (1.13.1) — design

Date: 2026-10-14 · Approach chosen by the owner: **A, "Gallery Wall"**, with real production covers.

## Goal

Replace the App Store and Google Play listing assets, which still show the retired
Featured/List/Map app with placeholder data, with a professional bilingual set that matches the
1.13.1 app (홈 · 목록 · 지도 · MY), and add the iOS 27 creative assets (product page header and
search results) that went live in App Store Connect on 2026-10-05.

Success: a reviewer who has never heard of gallr understands from the search result alone that it
shows what art exhibitions are on in Seoul this week, where, and that it plans a gallery walk; the
set reads as one system and obeys `DESIGN.md`; every file passes Apple's and Google's specs.

Out of scope: uploading or publishing anything (a separate owner action), App Store text metadata
(name, subtitle, keywords), In-App Event media, Product Page Optimization tests, and app UI changes.

## Constraints

- `DESIGN.md`: monochrome, 0dp corners outside the app's own Home tab UI, Inter + Gothic A1, accent
  `#FF5400` only where the app itself shows it (active tab underline, saved pins, a route line as the
  map's active selection). No gradients, shadows, glass or blur.
- Only Gothic A1 **Medium** is bundled; headlines use it at display size rather than adding a
  weight the app does not ship.
- Apple: real UI in previews and screenshots; no prices, URLs, awards, Apple badges, other-platform
  logos or unverifiable claims; 4+ content; focal point centred; no alpha channel; videos loop
  cleanly and work muted. Google Play: text under ~20% of a screenshot, no "best/#1/new/free".
- Covers are real production exhibition covers as the app shows them (owner decision 2026-10-14).
  Each captured frame is screened for 4+ suitability; a frame with an unsuitable cover is recaptured
  with different content.
- Captures run on a dedicated simulator created for this work so they never collide with other
  sessions' booted devices.

## Visual system (Approach A)

Canvas white `#FFFFFF`, text black. Each screenshot, top to bottom:

1. Eyebrow — Inter Medium, uppercase, letter-spacing 0.12em, `#525252`, e.g. `CURATED`.
2. Headline — Gothic A1 Medium (KO) / Inter Bold (EN), black, one line where possible
   (KO 6–12 characters, EN 2–5 words), optional sub-line in `#525252`.
3. Real UI capture, frameless, edged with a 1px black hairline, 0dp corners, centred and cropped
   off the bottom edge of the canvas so the screen reads larger. Text area stays under ~18% of the
   canvas height.

Slots 3 and 4 share a continuous element: the route line on the map screen exits the right edge
and re-enters slot 4's left edge (each still reads alone).

## Screenshot sequence (6 slots, KO and EN)

| # | Eyebrow | KO headline | EN headline | Screen captured |
|---|---------|-------------|-------------|-----------------|
| 1 | CURATED | 서울의 전시, 한눈에 | Seoul's shows, curated | Home tab top (내 취향 banner, story cards) |
| 2 | THIS WEEK | 이번 주 끝나는 전시 | Closing this week | 목록 with the closing-this-week filter |
| 3 | MAP | 지도 위의 갤러리 | Every gallery, mapped | 지도 with thumbnail pins, one saved (orange) |
| 4 | ROUTE | 나만의 전시 동선 | Plan a gallery walk | The composer with three Hannam-dong stops (no account needed) |
| 5 | STORY | 큐레이터의 시선 | Read the curator's view | Exhibition detail at the curator's note |
| 6 | DIARY | 본 전시, 볼 전시 | Save it. Log it. | 목록 → 내 전시 with saved exhibitions |

Slots 1–3 answer what, when and where so they work as the search-results fallback.

## iOS 27 creative assets

- **Product page header** — 21:9, 3840×1646. A gallery wall: ten real exhibition covers (screened for
  4+) hung on one centre line in a white cube, each with a small wall label (venue, title, dates), the
  hero work centred in the 1646×661 safe box; no wordmark (the title sits below the header).
  Delivered as a PNG still and a 30 s, 30 fps H.264 pan of exactly one wall period, so it loops
  without a seam. (Revised during build: a line map of the real exhibition coordinates left most of
  the canvas empty, and map tiles would need an OpenStreetMap © attribution that creative assets
  may not carry.)
- **Search results** — 3:2, 3840×2560. A triptych of the real Home, closing-this-week and map
  captures starting inside the centred 2168×1030 safe box, the header's wall continuing above it,
  with one line between them:
  KO "이번 주, 서울에서 볼 전시" / EN "What to see in Seoul this week". PNG still.
- No universal asset: Apple's 16:9 universal file is still-only and its safe box (8.7% of the
  area) is too small for UI.

## App preview (video)

20.5 s, 886×1920, 30 fps, H.264 High ≤ L4.0, ~10–12 Mbps, silent stereo AAC 256 kbps track
(App Store Connect requires all tracks enabled). Real UI recorded with `simctl io recordVideo`,
captions burned in per locale in the same eyebrow/headline style, no cut shorter than 2.5 s:

| Seconds | Shot |
|---------|------|
| 0–3 | Home top, slow scroll (poster frame at 1 s) |
| 3–6.5 | A curator's note on an exhibition page |
| 6.5–10.5 | 목록, closing this week |
| 10.5–14.5 | 지도, saved exhibitions in orange |
| 14.5–20.5 | The composer with three Hannam-dong stops, then a cut back to the first shot |

(Revised during build: saving moved off camera, since the first save raises a notification prompt;
the closing return to Home was dropped because navigating there on camera sent the app to the
background on iPhone, so the loop is a cut from the composer to Home like the cuts between shots.)

The iPad preview (1200×1600) uses the same storyboard recorded on the iPad simulator.

## Google Play

- Feature graphic 1024×500: the header's wall as a lit white panel on surface-variant grey `#F5F5F5`
  (Google advises against pure white/black), small gallr mark and one line, no device.
- Phone screenshots 1080×1920 from the same templates (KO and EN), frameless.
- Promo video: the 24 s preview re-encoded at 1080×1920 for the owner to post on YouTube.

## Outputs

| Asset | Size | Locales |
|-------|------|---------|
| iPhone 6.9" screenshots ×6 | 1320×2868 | ko, en-US |
| iPhone 6.3" screenshots ×6 | 1206×2622 | ko, en-US |
| iPad 13" screenshots ×6 | 2064×2752 | ko, en-US |
| Header still + video | 3840×1646 | ko, en-US (text-free, shared) |
| Search results still | 3840×2560 | ko, en-US |
| App preview iPhone / iPad | 886×1920 / 1200×1600 | ko, en-US |
| Play feature graphic | 1024×500 | ko, en-US |
| Play phone screenshots ×6 | 1080×1920 | ko, en-US |

## Pipeline and files

1. `iosApp/iosAppUITests/ScreenshotTests.swift` — rewritten for the four-tab app, light theme,
   driven by launch language (`ko`, `en-US`); raw captures through fastlane `snapshot` into
   `store-assets/captures/ios/`, so `iosApp/fastlane/screenshots/` (the path `upload_screenshots`
   reads) holds only the composed set. Snapfile devices updated to iPhone 17 Pro Max and
   iPad Pro 13-inch (M5).
2. `store-assets/` (new): `templates/*.html` (one per asset type, CSS tokens from DESIGN.md, fonts
   loaded from `composeApp/.../font`), `copy.json` (all KO/EN captions in one place),
   `render.mjs` (headless Chromium via Playwright renders each template at exact pixel size, then
   strips alpha), `video.sh` (ffmpeg assembly of preview and header loop), `validate.mjs`
   (checks every output's dimensions, format, alpha, duration, fps and audio track against this
   spec), and `README.md` (how to regenerate).
3. Outputs: composed App Store stills to `iosApp/fastlane/screenshots/<locale>/` (fastlane
   `deliver` naming) and everything else to `store-assets/dist/`. Stills are committed; videos are
   gitignored and handed over as files. The retired `screenshots/` captures that showed the old app
   are removed (`screenshots/mobile-nav` web captures stay).

Android captures come from the Android emulator through `adb` (`screencap`, `screenrecord`) on
the existing `gallr_test` AVD.

## Verification

- `validate.mjs` passes for every output.
- Visual review of every still beside `DESIGN.md`, and of each video from start to loop point.
- `ScreenshotTests` runs green for both locales on the dedicated simulator.
- `git diff --check`; iOS host build per `iosApp/AGENTS.md` for the UI-test change.
- Owner previews the header and search asset in App Store Connect's Preview tool before publishing.

## Revision 2: the creative set (owner direction, 2026-10-14/15)

The owner asked for creative displays of the screens rather than captures on white, with the live
1.9.2 listing as reference (black field, heavy Korean headlines, italic English lines, a tilted phone
across frames 1 and 2), and art direction from the taste skill (leonxlnx/taste-skill, updated to
a60a36dc). The draw dealt "sentence-as-layout" (seed 539189386). After review the owner set these
rules, which replace the screenshot sections above:

- Core features only, one per frame: Home (a panorama across frames 1 and 2, with no other screen in
  them), closing this week, the map, a curator's note (the insight it adds to a show) and saved
  exhibitions. The route frame is out of the screenshots and the previews.
- No gallr logo inside or beside the headline text, and no exhibition names, titles or quotes as text;
  exhibitions appear only inside the screenshots.
- Each frame: a two-line headline in Gothic A1 ExtraBold / Inter Bold, the live listing's italic
  English line, and one real screen shown large (tilted, cropped to the edges, or straight).
- Previews (16 s): Home → a curator's note → closing this week → the map, each shot playing in a
  straight device on the same black field under its caption; the loop is a cut back to Home.

The header wall, the search asset and the Play feature graphic are unchanged except that the feature
graphic's line now reads "전시 · 지도 · 큐레이터의 시선" / "Shows · Map · Curators' notes".
