# gallr

**Bilingual art-exhibition discovery for Korea — find, bookmark, and track shows across Seoul and beyond.**

gallr is a gallery and exhibition discovery app for Korea that helps art lovers find, bookmark, and track exhibitions at galleries and cultural venues — with a focus on indie and smaller spaces in Seoul. The product is fully bilingual (Korean / English, defaulting to Korean on first launch) and ships as a Kotlin Multiplatform client on **Android** and **iOS**, alongside a static **web** presence. It is built around four core tabs — Featured, List, Map, and Profile — with city/region filtering, exhibition bookmarking, guest-editor curation, city-wide event discovery, local push reminders, and an account-backed publishing workspace for galleries.

The project release version is tracked in [`VERSION`](VERSION); the release workflow keeps platform
package metadata synchronized with it.

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

gallr is a monorepo composed of five subsystems. Production content is authored through the
versioned Admin and gallery-owner workflows. Mobile and web read the canonical published catalog;
the legacy public table remains only as a canonical-to-legacy compatibility mirror for installed
clients.

| Subsystem | What it is |
|-----------|------------|
| **KMP client** (`androidApp/`, `composeApp/`, `shared/`, `iosApp/`) | Kotlin Multiplatform app with a Compose Multiplatform UI, targeting Android and iOS. Business logic lives in `:shared`, portable UI and platform adapters live in `:composeApp`, and `:androidApp` is the thin Android application host. |
| **Web** (`web/`) | An Eleventy 3.x static site — homepage, exhibition catalog, map, owner-workspace handoff, RSVP, and informational pages. Fully static (no runtime JS framework). |
| **Gallery** (`gallery/`) | Customer-facing React/Vite workspace for gallery claims, exhibition drafts and review, impact, Launch Kit guests/check-in, and promotion requests. |
| **Admin** (`admin/`) | Staff-only React editor for drafts, revision-safe saves, preview, publishing, archive/restore, and signed media workflows. |
| **Backend** (`supabase/`) | Supabase Postgres with a private versioned content model, transactional public projection, audit/outbox records, RLS, Auth, and Storage. |

### Publishing and reader flow

```
Gallery owner ──► gallery workspace ──► staff review ──┐
Staff editor  ──────────────────────► Admin ──────────────├─► canonical publish command
                                                       │
                                                       └─► public catalog + compatibility mirror
                                                                  │
                                               ┌─────────────────────┴──────────────────┐
                                               ▼                                       ▼
                                      KMP client (Ktor)                    Web build-time fetch
```

The KMP client reads live data over HTTP via Ktor; the web site fetches a featured showcase and
catalog data at **build time**. Bundled seed JSON is an offline/local fallback; verified and
production builds fail closed when live data is unavailable. The canonical editorial flow, image
lifecycle, data model, and historical rollout/rollback decisions are documented in
[Exhibition content architecture](docs/exhibition-content-architecture.md) and the
[public catalog cutover runbook](docs/public-exhibition-catalog-cutover-runbook.md). The current
owner flow is governed by the [gallery owner release runbook](docs/gallery-owner-release-runbook.md);
the former anonymous intake is documented only as
[archived removal history](docs/gallery-submission-workflow.md).

### Repository layout

```
gallr/
├── androidApp/   Thin Android application host
├── composeApp/   KMP Compose library — shared UI + Android/iOS adapters
├── shared/       KMP shared module — domain models, API clients, repositories, sync/notification logic
├── iosApp/       iOS native entry point (Swift) — NMapsMap auth, deeplink routing, Compose wrapper
├── web/          Eleventy 3.x static marketing + catalog site (Vercel)
├── admin/        Staff exhibition CMS
├── gallery/      Gallery-owner workspace
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

Storage buckets `exhibition-images` (public delivery), `avatars`, and the private source bucket
`exhibition-media` are created by migrations. `event-images` is operator-managed legacy event
media. No current submission path uses a separate `submissions` bucket.

---

## Tech Stack

### Client (Kotlin Multiplatform)

The client uses Kotlin and Compose Multiplatform, Ktor, Coil, Supabase, DataStore, and Naver Maps.
Networking and maps use platform adapters: OkHttp and the Android Maps SDK on Android; Darwin,
Swift Package Manager, and cinterop on iOS.

Targets: Android KMP library (JVM 11), `iosArm64`, and `iosSimulatorArm64`. Application package:
`com.gallr.app`; shared module: `com.gallr.shared`.

### Web

The public site uses Eleventy with self-hosted fonts. Its verification stack includes Node tests,
Playwright browser projects, and pa11y accessibility checks. Admin and gallery-owner workspaces use
Vite and React.

### Backend

- **Supabase Postgres** (hosted), ordered migrations under `supabase/migrations/`, row-level security throughout.

Exact dependency versions are owned by [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and
the Node workspace lockfiles; update those sources rather than copying versions into new guidance.

### Tooling

- **Gradle wrapper** and version-catalog-managed Android/Kotlin plugins.
- **CI**: GitHub Actions — database replay/security checks plus product-surface gates for Admin, gallery, public web, Edge Functions, Android/shared tests, and iOS compilation.
- **Spec-driven development** via Speckit (`specs/`).
- **Design system** documented in `DESIGN.md`.

---

## Getting Started / Development

### Prerequisites

- JDK 17+, Android SDK (compileSdk 37), and Gradle (use the wrapper).
- Xcode with Swift toolchain for iOS builds (Naver Maps resolved via SPM).
- Node.js 22+ and npm for the web, Admin, and gallery workspaces.
- A Supabase project with a URL and publishable key for live data.

### Required local config (names only — never commit secret values)

| File | Holds | Notes |
|------|-------|-------|
| `local.properties` / Gradle `-P` / CI environment | `sdk.dir`; `supabase.url` / `GALLR_SUPABASE_URL`; `supabase.publishable.key` / `GALLR_SUPABASE_PUBLISHABLE_KEY`; optional `exhibition.catalog.source` or `GALLR_EXHIBITION_CATALOG_SOURCE` | Gradle properties take precedence over environment variables, then `local.properties`. The deprecated `supabase.anon.key` / `GALLR_SUPABASE_ANON_KEY` names remain lower-priority migration fallbacks. Secret/service-role keys are rejected. Debug and unsigned verification builds may use either reader; `bundleRelease` requires the reviewed Seoul URL with `canonical-v2`. |
| Xcode build settings | `GALLR_EXHIBITION_CATALOG_SOURCE`, optional `GALLR_SUPABASE_URL`, `GALLR_SUPABASE_PUBLISHABLE_KEY` | Use a publishable key, never a secret/service-role key. `GALLR_SUPABASE_ANON_KEY` remains a lower-priority migration fallback. iOS Debug defaults to `legacy`; Release defaults to `canonical-v2`; endpoint/key fall back to Seoul production when unset. A staging canary must override all three preferred values. |
| 1Password-injected Android signing environment | `GALLR_ANDROID_STORE_FILE`, `GALLR_ANDROID_STORE_PASSWORD`, `GALLR_ANDROID_KEY_ALIAS`, `GALLR_ANDROID_KEY_PASSWORD` | Required store-build path. The file must be the existing Play-registered upload keystore; never generate a replacement for an existing app. |
| 1Password-injected web build environment | `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` (required for production data), optional `GALLR_EXHIBITION_SOURCE` and public impact/RSVP/promotion endpoint overrides | The deprecated `SUPABASE_ANON_KEY` name remains a lower-priority migration fallback. Public readers reject secret/service-role keys. `web/.env.local.example` documents names only; do not persist credentials in a copied file. |

### KMP app (Android + iOS)

```bash
# Android debug APK
./gradlew :androidApp:assembleDebug

# Android release APK for local verification (may be unsigned)
./gradlew :androidApp:assembleRelease

# Play Store AAB (fails closed without Seoul canonical-v2 config and signing)
./gradlew :androidApp:bundleRelease

# Shared and app tests across configured targets
./gradlew :shared:allTests
./gradlew :composeApp:allTests

# Faster Android unit-test and lint loop
./gradlew :composeApp:testAndroidHostTest :androidApp:lintDebug

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
npm ci
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

## Project Conventions

- **Spec-driven development.** Features are defined as numbered Speckit specs in `specs/`. Implementation follows the relevant specification and task list.
- **Branching model.** `develop` is the integration base and default branch; **`main` is production-only and is promoted exclusively through a PR**. Never fast-forward push `main`.
- **Migration lineage is immutable — no placeholders or history repair.** Preserve the production-recorded version IDs and historical bytes documented in `docs/database-migration-lineage.md`. Never leave placeholder tokens; use concrete, predicate-based SQL so every new migration runs cleanly without manual edits.
- **Read `DESIGN.md` before any UI change.** The design system — monochrome, brutally minimal, sharp 0dp corners, single orange accent `#FF5400`, Inter + Gothic A1 typography on an 8pt grid — is the source of truth for all visual decisions.

---

## Deployment

- **Web → Vercel.** Configured by `vercel.json` at the repo root (`buildCommand: cd web && npm run build`, `outputDirectory: web/dist`). Build-time data is fetched from Supabase; production fails closed instead of publishing the local/offline seed fallback.
- **Android → Play Store.** Signed Android App Bundle via `./gradlew :androidApp:bundleRelease`, with public backend and existing upload-key values injected from 1Password. The task rejects the wrong backend, legacy catalogue, blank credentials, and a missing keystore.
- **iOS → App Store.** Run `fastlane ios archive` from `iosApp/` to create and export a signed App Store Connect archive without uploading it. Upload and App Review submission remain separate approved actions.
- **Supabase.** Migrations in `supabase/migrations/` are applied in order; storage buckets not covered by migrations are provisioned manually.

---

gallr — gallery and exhibition discovery for Korea.
