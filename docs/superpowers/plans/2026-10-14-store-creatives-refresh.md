# Store Creatives Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce the 1.13.1 App Store and Google Play listing set (screenshots, iOS 27 header and search assets, app previews, Play feature graphic) from real captures of the current app.

**Architecture:** Real UI is captured from dedicated simulators/emulator (XCUITest + `simctl`/`adb`), composed into store layouts by HTML templates rendered at exact pixel sizes with headless Chromium, videos are cut with ffmpeg, and a validator checks every output against the store specs.

**Tech Stack:** XCTest/fastlane snapshot, Node 24 + Playwright (Chromium), ffmpeg, `node:test`.

**Spec:** `docs/superpowers/specs/2026-10-14-store-creatives-refresh-design.md`

## Global Constraints

- Monochrome; `#FF5400` only where the app's own UI shows it; 0dp corners on composed chrome; no gradients, shadows, blur.
- Fonts: Inter Regular/Medium/Bold and Gothic A1 Medium from `composeApp/src/commonMain/composeResources/font/`.
- No prices, URLs, awards, Apple badges, other-platform logos; 4+ content; no alpha channel in any still.
- Header 3840×1646 (safe box 1646×661 centred); search 3840×2560 (safe box 2168×1030 centred).
- App preview 886×1920 (iPhone) / 1200×1600 (iPad), 15–30 s, ≤30 fps, H.264 High ≤ L4.0, stereo AAC 256 kbps track.
- Screenshots: iPhone 1320×2868 and 1206×2622, iPad 2064×2752, Play 1080×1920; Play feature graphic 1024×500.
- Captures run only on the dedicated simulators `Gallr Store iPhone 17 Pro Max` and `Gallr Store iPad Pro 13 M5`.
- Nothing is uploaded or published.

## Review Focus

- A capture with a loading spinner, empty rail or error banner must not reach a composed asset: `validate.mjs` cannot see this, so every raw capture is inspected by eye before composing (Task 4 step).
- A Korean or English headline longer than the layout must not overflow or clip: `render.mjs` fails when a caption element overflows its box (Task 2 test).
- A still exported with an alpha channel is rejected by App Store Connect: `validate.mjs` checks the PNG colour type (Task 2 test).
- A video without an audio track or above 30 fps is rejected: `validate.mjs` checks via ffprobe (Task 2 test).
- A cover unsuitable for 4+ in any frame: recapture with other content (Task 4 step).

---

### Task 1: Capture test for the four-tab app

**Files:**
- Modify: `iosApp/iosAppUITests/ScreenshotTests.swift` (full rewrite)
- Modify: `iosApp/fastlane/Snapfile` (devices, `output_directory("../store-assets/captures/ios")`, `dark_mode(false)`)

**Interfaces:**
- Produces: raw captures named `01_Home`, `02_ClosingThisWeek`, `03_Map`, `04_Route`, `05_CuratorNote`, `06_My` per device and locale under `store-assets/captures/ios/<locale>/`.

- [ ] Explore each flow interactively on `Gallr Store iPhone 17 Pro Max` (light appearance) and note the accessibility labels that reach each screen.
- [ ] Rewrite the test: launch, wait for content, set language through MY → 설정 → 언어 when the launch locale is `en-US`, then one test per capture using the noted labels (`홈/HOME`, `목록/LIST`, `이번 주 종료/CLOSING THIS WEEK`, `지도/MAP`, `MY`).
- [ ] Run: `xcodebuild test-without-building … -only-testing:iosAppUITests/ScreenshotTests` on the dedicated iPhone; expected all tests pass and six PNGs per locale.
- [ ] Run `cd iosApp && fastlane screenshots`; expected captures for both devices and locales.

### Task 2: Renderer and validator

**Files:**
- Create: `store-assets/package.json`, `store-assets/copy.json`, `store-assets/templates/base.css`, `store-assets/render.mjs`, `store-assets/validate.mjs`, `store-assets/test/validate.test.mjs`, `store-assets/test/render.test.mjs`, `store-assets/.gitignore` (`node_modules/`, `dist/video/`, `captures/**/*.mov`)

**Interfaces:**
- `validate.mjs`: `export async function checkImage(path, {width, height})` → `{ok, problems: string[]}`; `export async function checkVideo(path, {width, height, minSeconds, maxSeconds, maxFps})` → same; CLI `node validate.mjs` checks the whole `dist/` manifest.
- `render.mjs`: `export async function renderTemplate({template, data, width, height, out})` → writes an opaque PNG; throws `Error('overflow: <selector>')` when an element marked `data-fit` overflows.

- [ ] Write failing tests: an RGBA PNG fails `checkImage` with `alpha channel`; wrong size fails with `expected 1320x2868`; a 60 fps or silent clip fails `checkVideo`; a template with an overlong `data-fit` headline makes `renderTemplate` throw `overflow`.
- [ ] Run `node --test store-assets/test` → FAIL.
- [ ] Implement (PNG IHDR parse for size/colour type; ffprobe JSON for video; Playwright page at `deviceScaleFactor: 1`, screenshot with `omitBackground: false`, then flatten through ffmpeg to `rgb24`).
- [ ] Run `node --test store-assets/test` → PASS.

### Task 3: Templates

**Files:**
- Create: `store-assets/templates/screenshot.html`, `search.html`, `header.html`, `feature-graphic.html`

- [ ] `screenshot.html`: eyebrow, headline, optional sub-line, capture with 1px black hairline, cropped off the bottom; continuous route-line element for slots 3–4.
- [ ] `search.html`: line above a triptych of three captures inside the 2168×1030 safe box.
- [ ] `header.html` / `feature-graphic.html`: the map composition built from a real map capture, route line in `#FF5400`, nothing meaningful outside the safe box.
- [ ] Render every template with sample data; inspect the PNGs.

### Task 4: Compose the stills

- [ ] Inspect every raw capture (Review Focus items 1 and 5); recapture any bad frame.
- [ ] `node store-assets/render.mjs` → App Store sets into `iosApp/fastlane/screenshots/<locale>/` and the rest into `store-assets/dist/`.
- [ ] Android: boot `gallr_test`, install `androidApp:assembleDebug`, capture the same six screens with `adb exec-out screencap -p`, render Play screenshots and the feature graphic.
- [ ] `node store-assets/validate.mjs` → all pass.

### Task 5: Videos

**Files:**
- Create: `store-assets/video.sh`

- [ ] Record the storyboard with `xcrun simctl io <udid> recordVideo --codec h264` on iPhone and iPad, per locale.
- [ ] `video.sh`: trim/concatenate shots (each ≥ 2.5 s), scale/pad to 886×1920 and 1200×1600, burn captions rendered by `render.mjs` as transparent overlays, 30 fps, add `anullsrc` stereo AAC 256 kbps, H.264 High L4.0 ~11 Mbps; header loop 12 s at 3840×1646 with a matching first and last frame.
- [ ] `node store-assets/validate.mjs` → videos pass; watch each to the loop point.

### Task 6: Cleanup and docs

- [ ] Remove retired captures in `screenshots/` (keep `screenshots/mobile-nav/`).
- [ ] `store-assets/README.md`: regenerate commands, sizes, where each file goes in App Store Connect and Play Console.
- [ ] `iosApp/AGENTS.md` screenshot paragraph points to `store-assets/README.md`.
- [ ] `git diff --check`; iOS host build per `iosApp/AGENTS.md`.
