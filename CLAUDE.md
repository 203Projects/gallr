# gallr — Agent Guide

gallr is a **Kotlin Multiplatform + Compose Multiplatform** mobile app (Android + iOS) for
discovering art exhibitions in Seoul — "Letterboxd for exhibitions." Bilingual KO/EN.
Companion subsystems: an Eleventy static **web** site, staff **Admin** and gallery-owner web apps,
and a **Supabase** Postgres backend. Read the current release version from `VERSION`; do not
duplicate it in guidance files.

> This file is the canonical engineering guide. `AGENTS.md` is the bootstrap entry point and owns
> the credential policy that applies before credential-dependent work.
> `iosApp/`, `web/`, `admin/`, `gallery/`, `supabase/`, and `scripts/` have nested
> `AGENTS.md` files — read the nearest one.

## Read first (non-negotiable)

- **`DESIGN.md` is the single source of truth for ALL visual/UI work. Read it before any change to
  UI, layout, color, spacing, typography, or motion.** Do not deviate without explicit user approval.
  The aesthetic is brutally minimal: monochrome (black/white/gray); **every shape is
  `RoundedCornerShape(0.dp)`** except avatars (`CircleShape`); one accent `#FF5400` restricted to
  exactly three roles — `ctaPrimary` buttons, `activeIndicator`, `interactionFeedback` — never
  backgrounds, large surfaces, or text on small targets; Inter (Latin) + Gothic A1 (Korean) on an 8pt grid.
- **Branching: `develop` is the integration base and default branch. `main` is production-only and is
  promoted exclusively through a PR — never fast-forward push `main`.** Branch features off `develop`.
- **The constitution (`.specify/memory/constitution.md`, v1.1.1) supersedes other guidance.**
  Non-negotiable principles: **Test-First (TDD)** and **Shared-First Architecture** (business logic
  lives in `shared/commonMain`, never in UI or platform source sets).

## Module map

| Path | What it is |
|------|-----------|
| `shared/` | KMP library. Package root `com.gallr.shared`: `data/model`, `data/network` (+ `dto/`), `repository`, `notifications`, `observability`, `platform`, `util`. All models, DTOs, ApiClients, Repositories, and business logic (filtering, status calc, notification trigger rules). No UI. |
| `androidApp/` | Thin Android application host. Owns `MainActivity`, the launcher manifest, application ID, BuildConfig, versioning, signing, and APK/AAB tasks. Depends on `:composeApp`; do not move portable UI or feature logic here. |
| `composeApp/` | KMP Compose library for Android + iOS. Package root `com.gallr.app`. **All Compose UI AND all ViewModels live in `src/commonMain`**: `ui/tabs/{featured,list,map}`, `ui/{detail,editor,event,profile,components,theme}`, `viewmodel/`, `platform/`. Android adapters remain in `androidMain`; the iOS entry point is `iosMain/MainViewController`. |
| `iosApp/` | Minimal Swift/Xcode host, MapLibre SPM resolution, signing/export configuration, and App Store screenshots. See `iosApp/AGENTS.md`. |
| `web/` | Eleventy 3.x static companion site. See `web/AGENTS.md`. |
| `admin/` | Vite/React staff editorial workspace. See `admin/AGENTS.md`. |
| `gallery/` | Vite/React gallery-owner workspace. See `gallery/AGENTS.md`. |
| `supabase/` | Canonical SQL lineage, pgTAP suites, and Deno Edge Functions. See `supabase/AGENTS.md`; each function keeps operational details in its local `README.md`. |
| `scripts/` | Migration rehearsal, cutover guards, legacy import/mirror, and product-surface validation. See `scripts/AGENTS.md`. |
| `specs/`, `.specify/` | Spec-kit feature folders (`NNN-name/`) + templates and the constitution. |

## Architecture boundaries

Account-bearing surfaces share one Supabase Auth identity plane per
environment; consumer, gallery-owner, editor, and staff access is granted by
separate server-owned membership relations. Follow
[`docs/account-identity-and-access.md`](docs/account-identity-and-access.md) and
verify project-reference parity across mobile, Gallery, Admin, and Editor before
promotion. Staging and production remain separate projects.

Use clean architecture as a dependency rule, not as a reason to add layers. The intended direction is:

`Compose UI → ViewModel/application orchestration → shared domain + repository interfaces`

Repository implementations depend on API/DataStore clients, map DTOs or stored values into domain
models, and are supplied at the composition root.

- **Domain (`shared/src/commonMain`).** Domain models and reusable business rules must not depend on
  Compose, DTOs, networking, storage, or platform APIs. Prefer pure functions for deterministic rules.
- **Data (`shared/src/commonMain`).** API clients, DTOs, storage implementations, and repository
  implementations stay behind repository interfaces. DTOs and Supabase/Ktor types must not escape
  into ViewModels or UI.
- **Application (`composeApp/src/commonMain/.../viewmodel`).** ViewModels coordinate repositories,
  screen state, and user actions. Move reusable decisions and transformations into `shared`; do not
  hide domain logic in callbacks or composables.
- **Presentation (`composeApp/src/commonMain/.../ui`).** Route-level composables may obtain a
  ViewModel; content composables receive immutable state and event callbacks. UI must not call API
  clients or repositories directly.
- **Platform adapters (`androidMain` / `iosMain`).** Keep native API access thin and inject it behind
  an interface when the behavior can be expressed portably.
- **Android KMP build model.** `shared` and `composeApp` use the official single-variant
  `com.android.kotlin.multiplatform.library` plugin with `kotlin.android {}` and explicit host tests.
  Do not reintroduce the deprecated `com.android.library` + `androidTarget` integration or apply the
  redundant Kotlin Android plugin to `androidApp`.
- **Abstractions must earn their cost.** Add a use-case/service type only when orchestration is reused,
  independently testable, or would otherwise give a ViewModel multiple responsibilities.
- Apply these boundaries to new and modified code. Existing violations are technical debt: do not
  expand them, but do not perform a repository-wide reorganization without an approved plan.

## Commands

Run Gradle from the repo root. **`commonTest` is the primary test surface**
(kotlin-test + kotlinx-coroutines-test `runTest`); the bulk of tests live there in both modules.

```bash
# Tests
./gradlew shared:allTests                 # shared unit tests (aggregated, all targets)
./gradlew composeApp:allTests             # composeApp unit tests (aggregated)
./gradlew composeApp:testAndroidHostTest  # faster: Android host tests only
./gradlew allTests                        # everything, all modules + targets

# Kotlin style
./gradlew shared:ktlintCheck composeApp:ktlintCheck androidApp:ktlintCheck

# Android
./gradlew androidApp:assembleDebug         # debug APK
./gradlew androidApp:lintDebug             # Android lint
./gradlew androidApp:assembleRelease       # local release APK; may be unsigned
./gradlew androidApp:bundleRelease         # store bundle; enforces production config + signing

# iOS framework (macOS + Xcode required)
./gradlew composeApp:linkReleaseFrameworkIosArm64           # device
./gradlew composeApp:linkReleaseFrameworkIosSimulatorArm64  # simulator
```

- **iOS app:** build/run via Xcode (`iosApp/iosApp.xcodeproj`); the Xcode build phase calls
  `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`.
  **Open `iosApp` in Xcode and resolve packages once first** — direct Gradle iOS links locate the
  MapLibre SPM xcframework in Xcode DerivedData and hard-error if the package is unresolved.
- **iOS / `allTests` need macOS + Xcode.** On Linux/CI, run the JVM-side test tasks only.
- **Android tasks need an SDK path** through `ANDROID_HOME` or `sdk.dir` in gitignored
  `local.properties`.
- **Web:** see `web/AGENTS.md` (`cd web` first; `npm run dev` / `npm run build` / `npm test`).
- **Node workspaces:** use the root `.node-version`; each package declares the supported major in
  `engines`. Do not verify with an unsupported host Node version.
- **Edge Functions:** use the Deno version in the root `.tool-versions`; CI reads the same file.

## Verification contract

Run the smallest relevant gate while iterating, then broaden verification in proportion to the
affected surface before handoff.

| Changed surface | Minimum relevant verification |
|-----------------|-------------------------------|
| `shared/commonMain` logic or contracts | Focused `commonTest`, then `./gradlew shared:ktlintCheck shared:allTests` |
| `composeApp/commonMain` ViewModel or state logic | Focused `commonTest`, then `./gradlew composeApp:ktlintCheck composeApp:allTests` |
| Android host, Compose UI, or Android adapter | `./gradlew androidApp:ktlintCheck composeApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug` |
| iOS adapter/framework integration | Relevant common tests plus the matching simulator/device link task; manually verify native behavior when automation cannot exercise it |
| `iosApp/` host, Xcode project, archive, or screenshots | Follow `iosApp/AGENTS.md` |
| `web/`, `admin/`, `gallery/`, `supabase/`, or `scripts/` | Follow the nearest `AGENTS.md` |
| Supabase migration | Run the migration-lineage validator first, then the database checks documented in `docs/database-migration-lineage.md` |
| Documentation only | `git diff --check`; verify changed commands, paths, links, versions, and cross-references against the repository |

- A change is complete only when implementation, tests, and affected documentation agree.
- Test behavior and public contracts, not private implementation details. Every regression fix needs
  a test that fails for the original defect when the surface is automatable.
- Do not claim a check passed unless it ran successfully. At handoff, name checks that ran and any
  checks not run, including the reason.
- Keep verification changes scoped: do not weaken, skip, or delete unrelated tests to make a change green.

## KMP conventions (follow exactly)

- **Source-set discipline.** Data and business logic go in `shared/commonMain`; ViewModels and shared
  Compose UI go in `composeApp/commonMain`. Put code in `androidMain` (`*.android.kt`) / `iosMain`
  (`*.ios.kt`) only when it genuinely needs a platform API.
- **Prefer interface + injected dependency over `expect`/`actual`** for anything shareable. Reserve
  `expect`/`actual` for thin platform shims. The existing shims are the pattern to follow:
  `DataStorePath`, `MapView`, `ImagePicker`, `ImageCropper`, `ShareHandler`, `PlatformBackHandler`,
  `LocationPermission`, `ReduceMotion`, `SplashLogoSize`, `UserLocation`.
  (Note `NotificationScheduler` is a plain `interface` with DI-wired Android/iOS impls — **not**
  `expect`/`actual`. Follow that style for platform behaviors that can be expressed as an interface.)
- **ViewModels** extend `androidx.lifecycle.ViewModel` from the **JetBrains KMP fork**
  `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` (catalog `jetbrains-lifecycle`),
  **not** plain `androidx.lifecycle` (that won't resolve for iOS). Expose `StateFlow<T>` (never a
  public `MutableStateFlow`). Use a private `MutableStateFlow` plus `asStateFlow()` for ViewModel-owned
  event state; use `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)` when adapting
  an upstream flow.
  Inject deps through the constructor + `viewModelFactory { initializer { } }`.
  **On iOS always call `viewModel { ... }` with an initializer — the no-arg overload does not work.**
  Tests of `WhileSubscribed` state must keep a subscriber alive, e.g.
  `backgroundScope.launch { vm.state.collect {} }`, or the upstream stops.
- **Navigation** is state-driven `mutableStateOf` in `App.kt` (no NavController); set a state value to
  null to pop. Android wraps screens in `Scaffold` with `WindowInsets.safeDrawing` for safe-area insets.
- **Repositories.** Interface + mirror `Impl` in `com.gallr.shared.repository` where an implementation
  seam exists. Catalog/editor/event/promotion repositories return `Result<T>` and contain their
  failure boundary with `runCatching`. Auth, profile, thought, bookmark, language, theme, and nudge
  contracts expose plain flows/values and may throw from suspending operations; callers must convert
  failures into explicit UI state or structured logs. Do not wrap a `Flow` itself in `Result`.
  DataStore preference keys are private module-level values with descriptive `UPPER_SNAKE_CASE`
  names. Never rename their persisted string values without a migration.
- **ApiClients** are separate from repositories, constructed with `supabaseUrl`/`supabaseApiKey`. Ktor
  `HttpClient` + ContentNegotiation (`ignoreUnknownKeys = true`, `coerceInputValues = true`) + Logging.
  Supabase REST uses snake_case query params (`is_featured=eq.true`, `order=is_active.desc`).
- **DTOs** are suffixed `Dto`, `@Serializable`, with `@SerialName` for snake_case DB columns; implement
  `toDomain()`. Make malformed required/optional field behavior explicit and tested, and do not let
  one malformed row crash an otherwise valid response unless the integrity contract requires failure.
- **Dates/times:** use `kotlin.time.Clock` and `kotlin.time.Instant` for clocks and instants; use
  `kotlinx.datetime` for calendar values, time zones, arithmetic, and conversions. Never use
  `java.util.Date`/`Calendar` in common code. Inject the reference date, clock, or time zone and
  extract pure functions (`exhibitionStatus`, `FilterState.matches`, `promoteHouseEditor`,
  `Event.*On`) so tests can fix time.
- **Bilingual:** `AppLanguage` enum (KO default, EN fallback). UI strings are inline
  `when (lang) { KO -> …; EN -> … }` — no i18n library or string resources. Models carry
  `localizedName()`-style helpers.
- **Networking/image engines are per-platform.** Ktor engine: `ktor-client-okhttp` in `androidMain`,
  `ktor-client-darwin` in `iosMain`. Coil 3 `AsyncImage` in `commonMain` with `coil-network-okhttp`
  (Android) and `coil-network-ktor3` (iOS). **Do not add an engine to `commonMain`.** The catalog
  alias `coil-network-ktor` intentionally maps to `coil-network-ktor3` — don't "fix" it.
- **Versions.** Route every dependency/plugin through `gradle/libs.versions.toml` via `libs.*`; don't
  hardcode versions in build files. Lifecycle remains split between `jetbrains-lifecycle` for KMP
  and `lifecycle` for AndroidX even when their release numbers currently match.

## Kotlin and Compose code style

- Follow the official Kotlin coding conventions. Format touched code consistently and avoid
  unrelated mass-formatting changes.
- ktlint 1.8.0 gates `shared`, `composeApp`, and `androidApp` in CI. Run
  `./gradlew shared:ktlintCheck composeApp:ktlintCheck androidApp:ktlintCheck` locally. No baseline is configured: every
  violation fails the gate. `ktlintFormat` rewrites a whole module, so use it only for a deliberate
  module-wide formatting cleanup.
- Give each type and file one primary responsibility. File length is a warning signal, not a target;
  split by cohesive behavior or feature boundary rather than arbitrary line counts.
- Keep functions focused at one level of abstraction. Prefer early returns and named helpers over
  deeply nested conditionals, collection chains, or callbacks.
- Prefer immutable values, data classes, pure transformations, and the narrowest useful visibility.
  Mutable state stays private and is exposed as an immutable `StateFlow` or value.
- Use descriptive domain names. Avoid vague types or variables such as `Manager`, `Helper`, `data`,
  or `item` when a precise name exists.
- Use named arguments when adjacent parameters share a type or the call is not self-explanatory.
  Retain trailing commas in multiline declarations and calls.
- Model a screen with a cohesive immutable `UiState` or sealed state hierarchy when separate flows
  can become inconsistent. Model user input as explicit event methods or callbacks.
- Keep composables stateless where practical. Route composables own lifecycle and ViewModel wiring;
  reusable content composables render state and emit events.
- Extract constants or design tokens for repeated or meaningful values. UI dimensions, colors,
  typography, and motion must come from `DESIGN.md` and the theme tokens.
- Comments and KDoc explain rationale, invariants, compatibility constraints, and public contracts.
  Do not narrate syntax or preserve dead code in comments.
- Remove deprecated code, flags, aliases, tests, and current-documentation references once their
  replacement is live and the documented rollback window has closed. Do not keep compatibility
  paths "just in case" or preserve dead implementations in comments. Before retiring a production
  integration, verify ownership transfer and rollback criteria; changing its external deployment,
  triggers, data, or credentials remains a separately authorized operation. Preserve immutable
  migrations, changelogs, completed specs, and other historical records as history.
- Do not swallow failures with empty `catch` blocks or silently substitute success. Represent an
  expected failure in state/result; log unexpected failures with operation context and redacted data.
- Route application logs through `AppLog.tagged(component)`; do not call `println()` or Kermit
  directly. Use stable `snake_case` operation names and never interpolate IDs, URLs, tokens, user
  content, or exception messages. The shared boundary records only the exception type.
- Prefer the smallest complete change. Do not introduce speculative interfaces, base classes,
  wrappers, or generic utilities for a single use.

## Subsystem gotchas (data-affecting)

- **Supabase migrations** follow the production-recorded version IDs — no CI auto-apply. Run
  `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` before database work and never
  rename, reorder, or repair versions to bypass a mismatch. Version `005` contains the documented
  clean-replay exception for the historical CLI-skipped `005b`; otherwise treat historical bytes as
  immutable. Write concrete, idempotent SQL (`IF NOT EXISTS` / `IF EXISTS`), never placeholder tokens.
  Buckets `exhibition-images`, `avatars`, and the private source bucket `exhibition-media` are
  migration-created. `event-images` remains operator-managed for legacy event media. Current
  submissions use `exhibition-media`; no supported path uses a separate `submissions` bucket.
- **Schema field names:** exhibitions use `name_ko`/`venue_name_ko` (not `title`/`venue`); bilingual
  `_ko`/`_en` pairs throughout.

## Secrets & CI

- Configure the Android SDK with `ANDROID_HOME` or `sdk.dir` in gitignored `local.properties`.
  Supabase configuration may come from Gradle `-P` properties, `GALLR_SUPABASE_*` environment
  variables, or `local.properties` (in that precedence order). Store signing must use
  1Password-injected `GALLR_ANDROID_*` environment variables. `bundleRelease` fails closed unless
  the production URL, `canonical-v2`, a public API key, and the existing Play-registered upload key
  are configured. Never commit local config or generate a replacement signing key for the existing
  app.
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
- On release: bump `VERSION` and `androidApp` `versionCode`/`versionName` together, and update
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
- 067-gallery-launch-beta: Gallery owners can activate free-beta RSVP, QR, guest-list, and check-in tools while paid promotion remains independently gated.
- 052-owner-hide-exhibitions: Gallery owners can remove an exhibition from their workspace through a revision-checked soft hide without deleting canonical, review, or published records.
- 051-gallery-info: Gallery owners maintain a revisioned canonical identity and venue profile; new exhibition drafts copy an independent venue snapshot.
- 050-transparent-local-promotion: Launch Kit promotion stays labelled, locality-scoped, staff-reviewed, frequency-capped, and isolated from organic catalogue ordering.
