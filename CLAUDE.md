# gallr — Agent Guide

gallr is a **Kotlin Multiplatform + Compose Multiplatform** mobile app (Android + iOS) for
discovering art exhibitions in Seoul — "Letterboxd for exhibitions." Bilingual KO/EN.
Companion subsystems: an Eleventy static **web** site, a Google Apps Script **sync** pipeline,
and a **Supabase** Postgres backend. Current version is in `VERSION` (1.7.3).

> This file is the canonical agent guide. `AGENTS.md` is a thin pointer to it.
> `web/` and `gas/` have their own nested `AGENTS.md` — read the nearest one when working there.

## Read first (non-negotiable)

- **`DESIGN.md` is the single source of truth for ALL visual/UI work. Read it before any change to
  UI, layout, color, spacing, typography, or motion.** Do not deviate without explicit user approval.
  The aesthetic is brutally minimal: monochrome (black/white/gray); **every shape is
  `RoundedCornerShape(0.dp)`** except avatars (`CircleShape`); one accent `#FF5400` restricted to
  exactly three roles — `ctaPrimary` buttons, `activeIndicator`, `interactionFeedback` — never
  backgrounds, large surfaces, or text on small targets; Inter (Latin) + Gothic A1 (Korean) on an 8pt grid.
- **Branching: `develop` is the integration base and default branch. `main` is production-only and is
  promoted exclusively through a PR — never fast-forward push `main`.** Branch features off `develop`.
- **The constitution (`.specify/memory/constitution.md`, v1.1.0) supersedes other guidance.**
  Non-negotiable principles: **Test-First (TDD)** and **Shared-First Architecture** (business logic
  lives in `commonMain`, never in platform modules).

## Module map

| Path | What it is |
|------|-----------|
| `shared/` | KMP library. Package root `com.gallr.shared`: `data/model`, `data/network` (+ `dto/`), `repository`, `notifications`, `platform`, `util`. All models, DTOs, ApiClients, Repositories, and business logic (filtering, status calc, notification trigger rules). No UI. |
| `composeApp/` | Android + iOS app. Package root `com.gallr.app`. **All Compose UI AND all ViewModels live in `src/commonMain`**: `ui/tabs/{featured,list,map}`, `ui/{detail,editor,event,profile,components,theme}`, `viewmodel/`, `platform/`. Platform entry points in `androidMain` (`MainActivity`) / `iosMain` (`MainViewController`). |
| `iosApp/` | Xcode project (Swift, minimal). Bridges to KMP via `MainViewControllerKt`; Naver Map iOS SDK via SPM. Fastlane for App Store screenshots. |
| `web/` | Eleventy 3.x static companion site. See `web/AGENTS.md`. |
| `gas/` | Google Apps Script sync (Sheet → Supabase). See `gas/AGENTS.md`. |
| `supabase/migrations/` | Canonical SQL migration lineage: numeric `001`–`014`, recorded May/June timestamps, then the catalog stack. See `docs/database-migration-lineage.md`. |
| `specs/`, `.specify/` | Spec-kit feature folders (`NNN-name/`) + templates and the constitution. |

## Commands

Run Gradle from the repo root. **`commonTest` is the primary test surface**
(kotlin-test + kotlinx-coroutines-test `runTest`); the bulk of tests live there in both modules.

```bash
# Tests
./gradlew shared:allTests                 # shared unit tests (aggregated, all targets)
./gradlew composeApp:allTests             # composeApp unit tests (aggregated)
./gradlew composeApp:testDebugUnitTest    # faster: Android debug variant only
./gradlew allTests                        # everything, all modules + targets

# Android
./gradlew composeApp:assembleDebug        # debug APK
./gradlew composeApp:assembleRelease      # release APK (needs key.properties)

# iOS framework (macOS + Xcode required)
./gradlew composeApp:linkReleaseFrameworkIosArm64           # device
./gradlew composeApp:linkReleaseFrameworkIosSimulatorArm64  # simulator
```

- **iOS app:** build/run via Xcode (`iosApp/iosApp.xcodeproj`); the Xcode build phase calls
  `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`.
  **Open `iosApp` in Xcode and do one build first** — Gradle cinterop locates the NMapsMap SPM
  xcframework by walking Xcode DerivedData and hard-errors if SPM is unresolved. A cold Gradle iOS
  build will fail with a confusing error otherwise.
- **iOS / `allTests` need macOS + Xcode.** On Linux/CI, run the JVM-side test tasks only.
- **Web:** see `web/AGENTS.md` (`cd web` first; `npm run dev` / `npm run build` / `npm test`).

## KMP conventions (follow exactly)

- **Source-set discipline.** Data/business logic AND ViewModels go in `commonMain`. Put code in
  `androidMain` (`*.android.kt`) / `iosMain` (`*.ios.kt`) only when it genuinely needs a platform API.
- **Prefer interface + injected dependency over `expect`/`actual`** for anything shareable. Reserve
  `expect`/`actual` for thin platform shims. The existing shims are the pattern to follow:
  `DataStorePath`, `MapView`, `ImagePicker`, `ImageCropper`, `ShareHandler`, `PlatformBackHandler`,
  `LocationPermission`, `ReduceMotion`, `SplashLogoSize`, `UserLocation`.
  (Note `NotificationScheduler` is a plain `interface` with DI-wired Android/iOS impls — **not**
  `expect`/`actual`. Follow that style for platform behaviors that can be expressed as an interface.)
- **ViewModels** extend `androidx.lifecycle.ViewModel` from the **JetBrains KMP fork**
  `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` (catalog `jetbrains-lifecycle`),
  **not** plain `androidx.lifecycle` (that won't resolve for iOS). Expose `StateFlow<T>` (never a
  public `MutableStateFlow`) via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)`.
  Inject deps through the constructor + `viewModelFactory { initializer { } }`.
  **On iOS always call `viewModel { ... }` with an initializer — the no-arg overload does not work.**
  (Tests must keep a subscriber alive, e.g. `backgroundScope.launch { vm.state.collect {} }`, or
  `WhileSubscribed` stops the upstream.)
- **Navigation** is state-driven `mutableStateOf` in `App.kt` (no NavController); set a state value to
  null to pop. Android wraps screens in `Scaffold` with `WindowInsets.safeDrawing` for safe-area insets.
- **Repositories.** Interface + mirror `Impl` in `com.gallr.shared.repository`.
  **Network-backed repos (`Exhibition`, `Editor`, `Event`) return `Result<T>` via `runCatching`.**
  DataStore/local repos (`Bookmark`, `Language`, `Theme`, `Auth`, `Profile`, `Thought`) expose plain
  `Flow`/values, **not** `Result`; their flows default to empty collections, never null.
  Storage keys are **private module-level** `*PreferencesKey` constants named `INTENT_KEY`
  (e.g. `BOOKMARKED_IDS_KEY`).
- **ApiClients** are separate from repositories, constructed with `supabaseUrl`/`anonKey`. Ktor
  `HttpClient` + ContentNegotiation (`ignoreUnknownKeys = true`, `coerceInputValues = true`) + Logging.
  Supabase REST uses snake_case query params (`is_featured=eq.true`, `order=is_active.desc`).
- **DTOs** are suffixed `Dto`, `@Serializable`, with `@SerialName` for snake_case DB columns; implement
  `toDomain()`; parse dates defensively (fall back to null on parse failure).
- **Dates/times:** `kotlinx.datetime` only (never `java.util.Date`/`Calendar`). Inject the reference
  date (`today`/`Clock`/`TimeZone`) as a parameter and extract pure functions (`exhibitionStatus`,
  `FilterState.matches`, `promoteHouseEditor`, `Event.*On`) so tests can fix the date.
- **Bilingual:** `AppLanguage` enum (KO default, EN fallback). UI strings are inline
  `when (lang) { KO -> …; EN -> … }` — no i18n library or string resources. Models carry
  `localizedName()`-style helpers.
- **Networking/image engines are per-platform.** Ktor engine: `ktor-client-okhttp` in `androidMain`,
  `ktor-client-darwin` in `iosMain`. Coil 3 `AsyncImage` in `commonMain` with `coil-network-okhttp`
  (Android) and `coil-network-ktor3` (iOS). **Do not add an engine to `commonMain`.** The catalog
  alias `coil-network-ktor` intentionally maps to `coil-network-ktor3` — don't "fix" it.
- **Versions.** Route every dependency/plugin through `gradle/libs.versions.toml` via `libs.*`; don't
  hardcode versions in build files. Two intentional off-catalog/split refs exist: `activity-compose`
  is pinned inline, and lifecycle is split (`jetbrains-lifecycle` for KMP vs `lifecycle` for androidx) —
  leave them as-is.

## Subsystem gotchas (data-affecting)

- **GAS `KNOWN_COLUMNS`.** `SyncExhibitions.gs` / `SyncEvents.gs` are header-driven and write only the
  columns listed in their `KNOWN_COLUMNS` array. **A new Supabase/sheet column that isn't added there is
  never synced** (existing rows keep their current value under the upsert; new rows get the column
  default). Treat a schema change and its `KNOWN_COLUMNS` update as one atomic change. See `gas/AGENTS.md`.
  (The sync does upsert + scoped stale-row diff-delete — *not* delete-all/insert-all, despite older
  wording in `README.md`/`gas/README.md`.)
- **Supabase migrations** follow the production-recorded version IDs — no CI auto-apply. Run
  `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` before database work and never
  rename, reorder, or repair versions to bypass a mismatch. Version `005` contains the documented
  clean-replay exception for the historical CLI-skipped `005b`; otherwise treat historical bytes as
  immutable. Write concrete, idempotent SQL (`IF NOT EXISTS` / `IF EXISTS`), never placeholder tokens.
  Buckets `exhibition-images` and `avatars` are migration-created; `event-images` and `submissions`
  must be created manually in the dashboard.
- **Schema field names:** exhibitions use `name_ko`/`venue_name_ko` (not `title`/`venue`); bilingual
  `_ko`/`_en` pairs throughout.

## Secrets & CI

- `local.properties` (`supabase.url`, `supabase.anon.key` → `BuildConfig`) and `key.properties`
  (release signing) are gitignored and must exist locally for Android builds. Never commit them.
- CI includes `.github/workflows/codex-pr-review.yml`, migration-triggered
  `.github/workflows/database-tests.yml`, the web rebuild workflow, and
  `.github/workflows/product-surfaces.yml`. Product-surface pull requests test
  the Admin and gallery workspaces, public web, every Edge Function with a
  `deno.json`, Android/shared JVM targets, and an iOS simulator compile. Full
  KMP `allTests` remain a local responsibility because the CI workflow runs the
  bounded Android/JVM test tasks plus the iOS compile gate.

## Release & feature workflow

- Features follow spec-kit: `/speckit.specify` → `/speckit.plan` → `/speckit.tasks` →
  `/speckit.implement`; branches and specs are named `NNN-name`. The Constitution Check in `plan.md`
  must verify Principle VI (Shared-First) before implementation.
- On release: bump `VERSION` and `composeApp` `versionCode`/`versionName` together, and update
  `CHANGELOG.md` (`## [X.Y.Z] - YYYY-MM-DD`, sections Added/Changed/Fixed/Infrastructure).

## Keep out of this file

Don't inline: per-feature tech-stack lists (they live in build files + `gradle/libs.versions.toml`),
code/API signatures, design rationale (`DESIGN.md`), transient TODOs (`TODOS.md`), credentials, or
generic Kotlin/Compose tutorials. Keep only project-specific, non-inferable, command-level facts.

<!--
  Spec-kit ownership note: `.specify/scripts/bash/update-agent-context.sh` only mutates a
  `## Active Technologies` and `## Recent Changes` section (and the date stamp) when they exist.
  This file intentionally omits `## Active Technologies` to avoid the unbounded per-feature dump.
  The `## Recent Changes` section below is spec-kit's append target; leave the heading in place.
-->

## Recent Changes
- 043-android-editor-screen-fix: Editor selector + detail screens wrapped in `Scaffold` with `WindowInsets.safeDrawing`; `EditorTopBar` rewritten as a Material3 `TopAppBar`. Android-only chrome fix, no behavior/data changes.
- 041-editor-hub: Migration `20260513110749` unifies `guest_editors` → `editors`; `editor_id` FK replaces the legacy fields, whose generated compatibility aliases are retained by the recorded May lineage (v1.6.0).
- 040-guest-editor: `guest_editors` table + FK; GuestEditor shared slice + banner/chip in ListScreen (v1.5.0).
