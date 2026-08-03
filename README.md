# gallr

**Bilingual art-exhibition discovery for Korea — find, bookmark, and track shows across Seoul and beyond.**

gallr is a gallery and exhibition discovery app for Korea that helps art lovers find, bookmark, and track exhibitions at galleries and cultural venues — with a focus on indie and smaller spaces in Seoul. The product is fully bilingual (Korean / English, defaulting to Korean on first launch) and ships as a Kotlin Multiplatform client on **Android** and **iOS**, alongside a static **web** presence. It is built around four core tabs — Featured, List, Map, and Profile — with city/region filtering, exhibition bookmarking, guest-editor curation, city-wide event discovery, local push reminders, and an account-backed publishing workspace for galleries.

**Current version: 1.7.3** (release prepared 2026-07-19)

---

## Features

- **Discovery across three views** — browse exhibitions through the Featured, List, and Map tabs.
- **City filter with sub-regions** — narrow results by city and region.
- **Bookmarks / My List** — heart an exhibition to save it; bookmarks persist locally and surface in a dedicated My List filter.
- **Fully bilingual UI (KO / EN)** — all text, reception labels, status labels, and notification copy are translated. Language resolves via app preference → device locale → Korean default.
- **Runtime status labels** — Coming Soon, Opening Today/Tomorrow/on [date], Open, Closing Soon/Today, and Closed are computed from each show's dates.
- **Guest editor curation** — a unified editor hub with a selector screen and detail pages for current and past guest editors.
- **City-wide events** — themed event support with event-colored map pins and a detail FAB (e.g. Loop Lab Busan 2025).
- **Exhibition detail** — cover image, venue, dates, address, hours, and contact.
- **Share preview** — generate a photo + text card to share an exhibition to social (Android).
- **Local push notifications** — closing-soon, opening-soon, reception-reminder, and inactivity reminders, all bilingual.
- **Gallery owner workspace** — gallery operators claim one gallery, create and submit free exhibition drafts, follow staff review, and manage post-publication Launch Kit tools without receiving staff access.
- **Profile & accounts** — email plus Google / Apple OAuth sign-in, profile photo upload with crop/zoom.
- **Dark theme** and a splash screen on cold launch.

---

## Architecture

gallr is a monorepo composed of six subsystems. The versioned CMS is
implemented locally, but production readers deliberately remain on the legacy
source until the staged cutover gates pass.

| Subsystem | What it is |
|-----------|------------|
| **KMP client** (`composeApp/`, `shared/`, `iosApp/`) | Kotlin Multiplatform app with a Compose Multiplatform UI, targeting Android and iOS. Business logic lives in `:shared`; the application and platform UI live in `:composeApp`. |
| **Web** (`web/`) | An Eleventy 3.x static site — homepage, exhibition catalog, map, owner-workspace handoff, RSVP, and informational pages. Fully static (no runtime JS framework). |
| **Gallery** (`gallery/`) | Customer-facing React/Vite workspace for gallery claims, exhibition drafts and review, impact, Launch Kit guests/check-in, and promotion requests. |
| **Admin** (`admin/`) | Staff-only React editor for drafts, revision-safe saves, preview, publishing, archive/restore, and signed media workflows. |
| **Legacy sync** (`gas/`) | Temporary Google Apps Script compatibility path retained only through the migration rollback window. |
| **Backend** (`supabase/`) | Supabase Postgres with a private versioned content model, transactional public projection, audit/outbox records, RLS, Auth, and Storage. |

### Current production/rollback data flow

```
                     onEdit + 5-min timer
Google Sheet  ──────────────────────────────►  Apps Script (gas/)
(gallr_gallery_list)                            SyncExhibitions.gs  (upsert + stale diff-delete)
                                                SyncEvents.gs       (upsert + diff-delete)
                                                FormEndpoint.gs     (legacy rollback only)
                                                        │
                                                        │  Supabase REST API
                                                        ▼
                                              Supabase Postgres
                                              (exhibitions, events, editors,
                                               profiles, bookmarks, thoughts; RLS)
                                                        │
                                  ┌─────────────────────┴─────────────────────┐
                                  │  Ktor (KMP app)                            │  build-time fetch
                                  ▼                                            ▼
                          KMP client (Android / iOS)                   Web (Eleventy static build)
```

The KMP client reads live data over HTTP via Ktor; the web site fetches a featured showcase and catalog data at **build time** (falling back to bundled seed JSON when Supabase env vars are absent). The target Sheet-free flow, editorial steps, image lifecycle, data model, rollout gates, and rollback procedure are documented in [Exhibition content architecture](docs/exhibition-content-architecture.md) and the [public catalog cutover runbook](docs/public-exhibition-catalog-cutover-runbook.md). The current owner flow is governed by the [gallery owner release runbook](docs/gallery-owner-release-runbook.md); the former anonymous intake is documented only as [rollback history](docs/gallery-submission-workflow.md).

### Repository layout

```
gallr/
├── composeApp/   KMP application module — Compose UI + Android/iOS entry points
├── shared/       KMP shared module — domain models, API clients, repositories, sync/notification logic
├── iosApp/       iOS native entry point (Swift) — NMapsMap auth, deeplink routing, Compose wrapper
├── web/          Eleventy 3.x static marketing + catalog site (Vercel)
├── admin/        Staff exhibition CMS
├── gallery/      Gallery-owner workspace
├── gas/          Temporary legacy sync + retired submission rollback
├── supabase/     Versioned Postgres, command API, projection, worker, and tests
├── specs/        Numbered, spec-driven feature definitions (Speckit)
└── docs/         Project documentation
```

### Backend schema (key tables)

- **exhibitions** — `id`, `name_ko/_en`, `venue_name_ko/_en`, `city_ko/_en`, `region_ko/_en`, `address_ko/_en`, `description_ko/_en`, `opening_date`, `closing_date`, `reception_date`, `opening_time`, `cover_image_url`, `hours`, `contact`, `latitude`, `longitude`, `is_featured`, `is_homepage_featured`, `event_id` (FK → events), `editor_id` (FK → editors), `updated_at`. Bilingual data uses `_ko` / `_en` column pairs.
- **events** — `id`, `name_ko/_en`, `description_ko/_en`, `location_label_ko/_en`, `start_date`, `end_date`, `brand_color`, `accent_color`, `ticket_url`, `is_active`, `cover_image_url`, `updated_at`.
- **editors** — `id` (slug), `name_ko/_en`, `title_ko/_en`, `bio_ko/_en`, `is_active`, `active_from`, `active_to`. Renamed from `guest_editors` in recorded migration `20260513110749`; seed row `gallr-editors` is the house editor.
- **profiles** — UUID PK → `auth.users(id)`, `display_name`, `avatar_url`, `bio`, `is_admin`.
- **bookmarks** — `user_id` FK → `auth.users`, `exhibition_id`, unique per `(user_id, exhibition_id)`.
- **thoughts** — `user_id` FK, `exhibition_id`, `content` (280-char max), `is_approved` (default false), unique per `(user_id, exhibition_id)`.

Storage bucket `exhibition-images` (public, filenames only) and `avatars` (public, `user-id.ext`) are created via migrations. The `event-images` and `submissions` buckets are referenced by code/docs but must be created manually in the Supabase dashboard.

---

## Tech Stack

### Client (Kotlin Multiplatform)

| Component | Version |
|-----------|---------|
| Kotlin (KMP compiler) | 2.1.20 |
| Compose Multiplatform | 1.8.0 |
| Ktor (HTTP — OkHttp on Android, Darwin on iOS) | 3.0.3 |
| Coil (async images) | 3.1.0 |
| Supabase (auth + postgrest) | 3.1.0 |
| DataStore Preferences (KMP) | 1.1.1 |
| kotlinx-serialization / datetime / coroutines | 1.7.3 / 0.6.1 / 1.9.0 |
| AndroidX ViewModel / Lifecycle | 2.8.7 / 2.8.4 |
| Naver Maps SDK (Android + iOS via SPM/cinterop) | 3.23.0 |
| naver-map-compose | 1.8.1 |
| Android SDK | compileSdk 35, minSdk 26, targetSdk 35 |
| iOS | Swift entry point, UIKitView interop, `NMapsMap.def` cinterop |

Targets: `androidTarget` (JVM 11), `iosArm64`, `iosSimulatorArm64`, `iosX64`. Application package `com.gallr.app` (versionCode 24, versionName 1.7.7); shared module `com.gallr.shared`.

### Web

| Component | Version |
|-----------|---------|
| Eleventy (static site generator) | ^3.0.0 |
| Inter font (self-hosted WOFF2, weights 400/500/700) | @fontsource/inter ^5.1.0 |
| Playwright (4 projects: smoke / editorial / mobile / catalog) | 1.49.0 |
| pa11y (WCAG 2.1 AA audit) | 8.0.0 |

### Backend & Pipeline

- **Supabase Postgres** (hosted), ordered migrations under `supabase/migrations/`, row-level security throughout.
- **Google Apps Script (V8, temporary legacy path)** — `SyncExhibitions.gs`, `SyncEvents.gs`, and retired `FormEndpoint.gs` rollback history.
- **Legacy anonymous submission gate** — dormant Supabase Edge Function retained and tested as rollback history; the live public CTA enters the authenticated gallery-owner workspace.

### Tooling

- **Gradle** 8.11 (wrapper); **Android Gradle Plugin** 8.5.2.
- **CI**: GitHub Actions — database replay/security checks plus product-surface gates for Admin, gallery, public web, Edge Functions, Android/shared tests, and iOS compilation.
- **Spec-driven development** via Speckit (`specs/`).
- **Design system** documented in `DESIGN.md`.

---

## Getting Started / Development

### Prerequisites

- JDK 11+, Android SDK (compileSdk 35), and Gradle (use the wrapper).
- Xcode with Swift toolchain for iOS builds (Naver Maps resolved via SPM).
- Node.js + npm for the web subsystem.
- A Supabase project (URL + publishable key or legacy anon key) for live data.

### Required local config (names only — never commit secret values)

| File | Holds | Notes |
|------|-------|-------|
| `local.properties` / Gradle `-P` / CI environment | `sdk.dir`; `supabase.url` / `GALLR_SUPABASE_URL`; `supabase.anon.key` / `GALLR_SUPABASE_ANON_KEY`; optional `exhibition.catalog.source` or `GALLR_EXHIBITION_CATALOG_SOURCE` | Gradle properties take precedence over environment variables, then `local.properties`. The key accepts a publishable key or legacy anon key; secret/service-role keys are rejected. Reader source is `legacy` (default) or `canonical-v2`. |
| Xcode build settings | `GALLR_EXHIBITION_CATALOG_SOURCE`, optional `GALLR_SUPABASE_URL`, `GALLR_SUPABASE_ANON_KEY` | Use a publishable key or legacy anon key, never a secret/service-role key. iOS reader source defaults to `legacy`; endpoint/key fall back to production when unset. A staging canary must override all three values. |
| `key.properties` | Android keystore signing config | gitignored; `upload-keystore.jks` also gitignored |
| `web/.env.local` | `SUPABASE_URL`, `SUPABASE_ANON_KEY` (required for prod data), optional `GALLR_EXHIBITION_SOURCE` and public impact/RSVP/promotion endpoint overrides | `SUPABASE_ANON_KEY` accepts a publishable key or legacy anon key and rejects secret/service-role keys. No server secret belongs in the static web build. |

### KMP app (Android + iOS)

```bash
# Android debug APK
./gradlew :composeApp:assembleDebug

# Android release APK (signed when key.properties is configured)
./gradlew :composeApp:assembleRelease

# Build + run all unit tests (commonTest + androidTest)
./gradlew :composeApp:build
./gradlew :composeApp:test

# Link iOS frameworks
./gradlew :composeApp:linkDebugFrameworkIosArm64
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# Build the shared library across all platforms
./gradlew :shared:build

# Clean build outputs
./gradlew clean
```

iOS is then built/run through the Xcode project in `iosApp/`.

### Web site

```bash
cd web
npm install
npm run dev        # eleventy --serve (live reload; tests use port 4242)
npm run build      # copy-fonts → fetch-showcase → fetch-exhibitions → eleventy (output: web/dist/)
npm run preview    # build, then serve dist/ at localhost:8080
npm run test       # Node tests + build + Playwright (4 projects)
```

Seed regeneration:

```bash
cd web
npm run refresh-seed              # rebuild showcase-seed.json from Supabase
npm run refresh-exhibitions-seed  # rebuild exhibitions seed
```

### Supabase migrations

SQL migrations live in `supabase/migrations/`. Their version IDs are normalized to the production-recorded history; read `docs/database-migration-lineage.md` and run its validator before any linked command. Never apply the new content stack directly to production before completing its staging-clone rehearsal and backup gates.

### Legacy Apps Script sync (temporary)

The current production compatibility pipeline in `gas/` (`SyncExhibitions.gs`, `SyncEvents.gs`, `FormEndpoint.gs`) is deployed into the Apps Script project bound to the source Google Sheet. It runs on an `onEdit` trigger plus a 5-minute timer. Keep it available for rollback until the cutover runbook explicitly freezes the Sheet, applies the final delta, and transfers publication ownership to the admin.

---

## Project Conventions

- **Spec-driven development.** Features are defined as numbered Speckit specs in `specs/`. Implementation follows the relevant specification and task list.
- **Branching model.** `develop` is the integration base and default branch; **`main` is production-only and is promoted exclusively through a PR**. Never fast-forward push `main`.
- **The `KNOWN_COLUMNS` sync trap.** `SyncExhibitions.gs` is header-driven and processes only columns listed in its `KNOWN_COLUMNS` array. Any new Supabase/sheet column **must be added to `KNOWN_COLUMNS`** or the sync will skip it. The writer upserts valid rows, then scoped-diff deletes only stale IDs; an unwritten column keeps its existing value or uses its default on a new row. Treat schema changes and `KNOWN_COLUMNS` updates as a single change.
- **Migration lineage is immutable — no placeholders or history repair.** Preserve the production-recorded version IDs and historical bytes documented in `docs/database-migration-lineage.md`. Never leave placeholder tokens; use concrete, predicate-based SQL so every new migration runs cleanly without manual edits.
- **Read `DESIGN.md` before any UI change.** The design system — monochrome, brutally minimal, sharp 0dp corners, single orange accent `#FF5400`, Inter + Gothic A1 typography on an 8pt grid — is the source of truth for all visual decisions.

---

## Deployment

- **Web → Vercel.** Configured by `vercel.json` at the repo root (`buildCommand: cd web && npm run build`, `outputDirectory: web/dist`). Build-time data is fetched from Supabase; seed JSON is used as a fallback.
- **Android → Play Store.** Signed release APK via `./gradlew :composeApp:assembleRelease`, using the keystore configured in `key.properties` / `upload-keystore.jks`.
- **iOS → App Store.** Built from the `iosApp/` Xcode project; release tooling lives under `iosApp/fastlane/`.
- **Supabase.** Migrations in `supabase/migrations/` are applied in order; storage buckets not covered by migrations are provisioned manually.

---

gallr — gallery and exhibition discovery for Korea.
