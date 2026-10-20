# Research: Required app update

Grounded in `composeApp/.../App.kt` (the ON_RESUME observer and the `SplashOverlay` root), `platform/AppVersion.kt`
(`appVersionName()` on both platforms), `MostViewedExhibitionApiClient` (the pattern for an anonymous RPC read),
`DataStoreRouteVisitTimeRepository` (the pattern for a small persisted value), the 092 migration
(`security definer` RPCs with explicit grants, `content_private.has_staff_role`) and Admin's `AdminSection` list.

## R1. The policy lives in one Supabase table, read through one anonymous RPC

**Decision**: Migration `20261020100000_app_version_policy.sql` creates `content.app_version_policy` with one row per
platform (`platform text primary key check (platform in ('android','ios'))`, `minimum_version text`,
`recommended_version text`, `latest_released_version text null`, `updated_at timestamptz`, `updated_by uuid`), a
check that each version matches `^\d+\.\d+\.\d+$`, RLS on, no table grants to `anon`/`authenticated`. Reads go
through `public.get_app_version_policy(p_platform text)` (`security definer`, granted to `anon, authenticated`),
returning `{minimum_version, recommended_version}` or null when no row exists. Writes go through
`public.admin_set_app_version_policy(p_platform, p_minimum, p_recommended, p_latest_released)` which calls
`content_private.admin_assert_staff()` and stamps `updated_by`/`updated_at`. The migration seeds both platforms at
`1.13.1` (the current store version), so nothing is gated until staff raise it (spec Assumptions).

**Rationale**: Matches the repository's established shape (092: private table, definer-rights RPCs, explicit grants,
pgTAP for success and for unauthorized roles). One row per platform gives staff independent control (spec US4). No
Firebase or other configuration service (FR-010).

**Alternatives considered**: a JSON `app_config` table with free-form keys (no per-field validation, harder to test);
a static file on the public web (no attribution, no staff gate, and the web deploys on its own cadence).

## R2. Versions compare as three numbers in `shared`

**Decision**: `shared/.../app/AppVersion.kt`: `data class AppVersion(major, minor, patch) : Comparable` with
`parse(text): AppVersion?` that takes the first three dot-separated integers and ignores any suffix ("1.14.0-debug"
→ 1.14.0; "1.13" → null). `UpdatePolicy(minimum, recommended)` and `fun updateRequirement(installed, policy):
UpdateRequirement` (`REQUIRED` when installed < minimum, `RECOMMENDED` when minimum ≤ installed < recommended, `NONE`
otherwise). Pure functions, unit-tested (constitution VI).

## R3. The app reads at launch and on return after a minute away; a failed read keeps the last answer

**Decision**: `AppUpdateRepository` (shared interface) with `val requirement: StateFlow<UpdateRequirement>` and
`suspend fun refresh()`. `AppUpdateRepositoryImpl` takes the installed version, the platform, an `AppVersionPolicyApi`
(RPC client) and a `DataStore<Preferences>`: `refresh()` reads the RPC; on success it stores the policy under the
private key `app_version_policy` (value `min|recommended`) and emits the requirement; on failure (network, non-200,
unparseable) it logs `app_version_policy_read_failed` and keeps whatever the DataStore held, which is also what the
flow starts from at launch. `App.kt`'s existing ON_RESUME observer calls `refresh()` when the app has been away for
at least 60 seconds (remember the last ON_PAUSE/ON_STOP instant), and once at start.

**Rationale**: FR-001, FR-005; the remembered answer means going offline does not lift a gate already shown (US2
scenario 3), while a visitor who has never been told cannot be blocked by an outage.

## R4. The gate is one composable at the root, above the tabs and below the splash

**Decision**: `RequiredUpdateScreen(language, onUpdate)` fills the screen (Home's style: `titleLarge` heading
"업데이트가 필요해요" / "UPDATE REQUIRED", a `bodyMedium` line, one standard black button "업데이트" / "UPDATE",
nothing else; the accent is not needed since there is one action). In `App.kt`, when `requirement == REQUIRED` the
root shows this screen instead of `TabNavigationShell` and installs a `PlatformBackHandler` that does nothing, so
Android back cannot leave it. The splash overlay stays above it. Nothing is drawn before the first read completes:
the app starts on the remembered requirement (NONE on a fresh install), so the first screen is never delayed (FR-009).

**Store link**: `AppStoreLink` interface in `composeApp/.../platform` with Android `market://details?id=com.gallr.app`
(falling back to the `https://play.google.com/store/apps/details?id=com.gallr.app` page) and iOS
`https://apps.apple.com/app/gallr/id6760855059` (the id already used by `ShareHandler.ios.kt`). Opening is best
effort through `UriHandler`; the screen stays whatever happens (edge case "store cannot open").

## R5. The nudge is a Home banner, once per calendar day

**Decision**: `UpdateNudge(language, onUpdate, onLater)` at the top of the Home tab's rails: `bodyMedium` "새 버전이
있어요" / "A NEW VERSION IS AVAILABLE" with two text actions. `AppUpdateRepository` also exposes `nudgeDismissedOn:
Flow<LocalDate?>` and `suspend fun dismissNudge(today)`, stored under `app_update_nudge_dismissed` (`yyyy-MM-dd`);
the banner shows when `requirement == RECOMMENDED && dismissedOn != today` (Seoul calendar date, as the rest of the
app). The required gate always wins (US3 scenario 4).

## R6. Play's immediate in-app update is a thin Android adapter, off by default in the plan

**Decision**: Define `InAppUpdater` (interface, `suspend fun startImmediateUpdate(): Boolean`) with a no-op default on
both platforms; the Android implementation using `com.google.android.play:app-update-ktx` is a follow-up task
(T-last), because it cannot be exercised on an emulator or a sideloaded build (Play only updates apps it installed)
and would be the only new dependency. The baseline that the spec requires, the store listing, works everywhere.

**Rationale**: FR-003 is a MAY; the owner's interest is noted, and the interface keeps the door open without making
this release's verification depend on a Play-distributed build.

## R7. Admin: a small "App versions" panel under Routes, staff only

**Decision**: An `AppVersionsWorkspace` in Admin (new `AdminSection` "App versions" / "앱 버전") with two rows
(Android, iOS): minimum, recommended, last released, who changed it and when, and a Save per row that calls
`admin_set_app_version_policy`. The field refuses anything but `N.N.N` client-side and shows the RPC's error
otherwise; it warns inline when minimum > last released. Vitest covers the validation and the warning; the RPC's
authorization is covered by pgTAP. Until the panel ships, staff may set the row through the SQL editor (spec
Assumptions); the panel is in this feature's scope because US4 is P2.

## R8. What does not change

The splash, sign-in, analytics (no new event), the public web, the store builds' version numbers (the release bump
is separate), and builds older than this release (unreachable by design, spec Edge Cases).
