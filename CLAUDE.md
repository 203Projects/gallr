# gallr Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-04-07

## Active Technologies
- Kotlin 2.1.x, Compose Multiplatform 1.8.0 + Compose Multiplatform, Material3, compose-resources (CMP 1.8.0 (002-monochrome-design-system)
- N/A — design tokens are compile-time Kotlin objects; font TTF files bundled (002-monochrome-design-system)
- HTML5 / CSS3, JavaScript ES2022 (build tooling only — no runtime JS shipped) + Astro 5.x (static site generator), `@fontsource/playfair-display`, `@fontsource/ibm-plex-mono` (self-hosted web fonts) (003-gallr-presentation-web)
- N/A — fully static (003-gallr-presentation-web)
- HTML5 / CSS3, JavaScript ES2022 (build tooling only — no runtime JS shipped) + Eleventy 3.x (static site generator), self-hosted WOFF2 fonts (Playfair Display, JetBrains Mono) (003-gallr-presentation-web)
- Kotlin 2.1.20 (KMP), Swift 5.9 (iOS entry point only) (004-naver-map-api)
- N/A — no new persistence (004-naver-map-api)
- Kotlin 2.1.20 (KMP), Swift 5.9 (iOS entry point — no changes needed) + Compose Multiplatform 1.8.0, compose-resources (font loading) — no new dependencies (005-reductionist-design-system)
- N/A — compile-time token objects only, no runtime persistence (005-reductionist-design-system)
- HTML5 / CSS3, JavaScript ES2022 (build scripts only — no runtime JS) + Eleventy 3.x (static site generator), `@fontsource/inter` (replace `@fontsource/playfair-display` + `@fontsource/jetbrains-mono`), pa11y 8.x (accessibility), Playwright 1.49 (visual acceptance tests) (006-web-reductionist-theme)
- Kotlin 2.1.20 (KMP shared + platform modules); Google Apps Script V8 runtime (sync pipeline) (007-gallery-data-sync)
- Supabase Postgres (hosted); `exhibitions` table (see `data-model.md` for schema) (007-gallery-data-sync)
- Kotlin 2.1.20 (Kotlin/Native, iosSimulatorArm64 / iosArm64 targets) + Compose Multiplatform 1.8.0 (`UIKitView` interop), NMapsMap iOS SDK 3.23.0 (via SPM + cinterop) (008-fix-ios-map-render)
- Kotlin 2.1.20 (KMP composeApp module); HTML5/CSS3 (web/privacy.html) + Compose Multiplatform 1.8.0 (`LocalUriHandler` from `androidx.compose.ui.platform`); Eleventy 3.x (web static site, existing) (009-privacy-policy-url)
- N/A — static URL constant; no persistence (009-privacy-policy-url)
- Kotlin 2.1.20 (KMP); Compose Multiplatform 1.8.0 + No new dependencies — existing `BookmarkRepository`, `bookmarkedIds: StateFlow<Set<String>>`, and `MutableStateFlow<MapDisplayMode>` are sufficien (010-mylist-filter-map)
- N/A — bookmark persistence already implemented via DataStore; no schema changes (010-mylist-filter-map)
- Kotlin 2.1.20 (KMP), Google Apps Script V8, SQL (Supabase Postgres) + Compose Multiplatform 1.8.0, Ktor 2.9+, DataStore Preferences 1.1+, kotlinx.serialization 1.7+, compose-resources (CMP string resources) (012-bilingual-data-pipeline)
- Supabase Postgres (exhibitions table), DataStore Preferences (language preference + bookmarks) (012-bilingual-data-pipeline)
- Kotlin 2.1.20 (KMP) + Compose Multiplatform 1.8.0, Ktor 2.9+ (for image loading via URL), coil3 or similar (async image loading) (013-city-filter-detail-page)
- Supabase Postgres (existing), DataStore Preferences (existing) (013-city-filter-detail-page)
- Kotlin 2.1.20 (KMP), Swift 5.9 (iOS entry point only) + Compose Multiplatform 1.8.0, DataStore Preferences 1.1+, Material3 (014-dark-theme)
- DataStore Preferences (existing — add theme preference key) (014-dark-theme)
- Kotlin 2.1.20 (KMP) + Compose Multiplatform 1.8.0, Material3 (015-ui-polish-uniformity)
- N/A — UI-only changes (015-ui-polish-uniformity)
- Kotlin 2.1.20 (KMP) + Compose Multiplatform 1.8.0, Material3, Coil 3.1.0 (image loading), kotlinx-datetime (016-ui-improvements)
- N/A — no new persistence (search is client-side filtering) (016-ui-improvements)
- Swift 5.9 (iOS entry point), Kotlin 2.1.20 (KMP shared module — no changes needed) + None — this is a plist configuration change only (017-fix-ios-app-name)
- Kotlin 2.1.20 (KMP), composeApp Android targe + androidx.activity:activity-compose 1.9.3, Compose Multiplatform 1.8.0, Material3 (018-fix-android-insets)
- Kotlin 2.1.20 (KMP) + Compose Multiplatform 1.8.0, Material3, Coil 3.1.0 (`coil3.compose.AsyncImage`) (019-card-image-background)
- Kotlin 2.1.20 (KMP) + Compose Multiplatform 1.8.0, Material3, kotlinx-datetime (022-status-labels-map-filter)
- N/A — no new persistence; status is computed from existing `openingDate` / `closingDate` (022-status-labels-map-filter)
- Kotlin 2.1.20 (KMP shared + composeApp), Google Apps Script V8 (gas/), SQL (Supabase) + kotlinx-serialization 1.7.3, kotlinx-datetime 0.6.1, Compose Multiplatform 1.8.0, Material3 (025-opening-time-display)
- Supabase Postgres (exhibitions table), Google Sheets (gallr_gallery_list) (025-opening-time-display)

- Kotlin 2.0+ (2.3.0 recommended), Compose Multiplatform 1.8.0+ + Ktor 2.9+ (networking), DataStore Preferences 1.1+ (bookmarks), AndroidX ViewModel 2.8.0+, kotlinx.serialization 1.7+, kotlinx-datetime (001-exhibition-tabs)

## Project Structure

```text
src/
tests/
```

## Commands

# Add commands for Kotlin 2.0+ (2.3.0 recommended), Compose Multiplatform 1.8.0+

## Code Style

Kotlin 2.0+ (2.3.0 recommended), Compose Multiplatform 1.8.0+: Follow standard conventions

## Design System
Always read DESIGN.md before making any visual or UI decisions.
All font choices, colors, spacing, and aesthetic direction are defined there.
Do not deviate without explicit user approval.
In QA mode, flag any code that doesn't match DESIGN.md.

## Recent Changes
- 025-opening-time-display: Added Kotlin 2.1.20 (KMP shared + composeApp), Google Apps Script V8 (gas/), SQL (Supabase) + kotlinx-serialization 1.7.3, kotlinx-datetime 0.6.1, Compose Multiplatform 1.8.0, Material3
- 022-status-labels-map-filter: Added Kotlin 2.1.20 (KMP) + Compose Multiplatform 1.8.0, Material3, kotlinx-datetime
- 019-card-image-background: Added Kotlin 2.1.20 (KMP) + Compose Multiplatform 1.8.0, Material3, Coil 3.1.0 (`coil3.compose.AsyncImage`)


<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
