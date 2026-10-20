# Implementation Plan: Required app update

**Branch**: `098-required-app-update` | **Date**: 2026-10-20 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/098-required-app-update/spec.md`

## Summary

Staff set a minimum and a recommended app version per platform in one Supabase table; the app reads them through an
anonymous RPC at launch and when it returns after a minute away, compares them with its installed version in `shared`,
and shows a full-screen required-update notice (store button, back disabled) or a once-a-day Home nudge. A failed read
keeps the last answer, so an outage never blocks anyone and going offline never lifts a gate. Admin gets a small
"App versions" panel over a staff-only write RPC. Google Play's in-app update stays behind an interface for a later
task; the store listing is the baseline on both platforms. The release carrying this seeds both platforms at the
current store version, so it gates nobody and becomes the floor for the next release.

## Technical Context

**Language/Version**: Kotlin 2.x Multiplatform, Compose Multiplatform; Postgres (Supabase); Vite/React (Admin)
**Primary Dependencies**: present: Ktor client, DataStore preferences, JetBrains lifecycle (`LocalLifecycleOwner`
already in `App.kt`); none new (the Play in-app update library is deferred, research R6)
**Storage**: `content.app_version_policy` (one row per platform); two private DataStore keys on the device
**Testing**: `shared` commonTest (version parsing, requirement rule, repository with an in-memory DataStore and a fake
API), `composeApp` commonTest (presentation strings), iosTest Compose UI test (the notice's back handler and button),
pgTAP `068` (read for anon, write for staff only, format checks), Admin Vitest (validation, warning)
**Target Platform**: Android and iOS through `shared` and `composeApp`; Admin web
**Project Type**: mobile app + database + operator web
**Performance Goals**: the first screen is not delayed; the read is one small RPC after the first frame
**Constraints**: free services only (FR-010); never blocked by a failed read (FR-005); DESIGN.md
**Scale/Scope**: 1 migration + pgTAP, 3 shared files, 1 repository, 2 composables, root wiring, 1 Admin panel

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate | Status |
|-----------|------|--------|
| I. Spec-First | `spec.md` committed (`f259d992`) before planning | PASS |
| II. Test-First | Version rule, repository and RPC behaviour get failing tests first; pgTAP covers anon read and non-staff write refusal | PASS (enforced in tasks) |
| III. Simplicity & YAGNI | One table, two RPCs, one rule; no remote-config service; Play in-app update deferred behind an interface | PASS |
| IV. Incremental Delivery | US1 + US2 (gate and fail-open) first; US3 nudge; US4 Admin panel | PASS |
| V. Observability | Read failures log `app_version_policy_read_failed` with the exception type only; no new analytics | PASS |
| VI. Shared-First | Version parsing, comparison, requirement rule and the repository live in `shared`; composables only render state | PASS |
| VII. Mobile-First | App feature; Admin is an operator tool (Principle VII exemption) | PASS |

**Post-design re-check**: unchanged, all PASS.

## Project Structure

### Documentation (this feature)

```text
specs/098-required-app-update/
├── plan.md, research.md (R1–R8), data-model.md, quickstart.md
├── contracts/app-version-policy.md, contracts/update-screens.md
├── checklists/requirements.md
└── tasks.md             # /speckit.tasks
```

### Source Code (repository root)

```text
supabase/migrations/20261020100000_app_version_policy.sql   # table, RPCs, grants, seed
supabase/tests/database/068_app_version_policy.test.sql
shared/src/commonMain/kotlin/com/gallr/shared/
├── app/AppVersion.kt                          # AppVersion, UpdatePolicy, UpdateRequirement, updateRequirement()
├── data/network/AppVersionPolicyApiClient.kt  # get_app_version_policy
└── repository/AppUpdateRepository.kt, repository/AppUpdateRepositoryImpl.kt
shared/src/commonTest/kotlin/com/gallr/shared/{app,data/network,repository}/…Test.kt
composeApp/src/commonMain/kotlin/com/gallr/app/
├── platform/AppStoreLink.kt (+ androidMain/iosMain actuals)   # the listing URL per platform
├── platform/InstalledAppVersion.kt                           # installed version for the rule (non-composable)
├── ui/update/RequiredUpdateScreen.kt, ui/update/UpdateNudge.kt, ui/update/UpdatePresentation.kt
└── App.kt                                                    # gate at the root; refresh on resume after 60 s
composeApp/src/iosTest/kotlin/com/gallr/app/ui/update/RequiredUpdateScreenTest.kt
androidApp/.../MainActivity.kt, composeApp/src/iosMain/.../MainViewController.kt   # construct the repository
admin/src/components/AppVersionsWorkspace.tsx (+ test), admin/src/data/…, domain.ts, i18n.tsx, PrimaryNavigation
DESIGN.md                                      # Required update screen and the Home nudge
```

**Structure Decision**: Shared-first; composeApp renders; the two entry points construct the repository beside the
other DataStore repositories; Admin follows its workspace pattern.

## Risks

- **Locking everyone out by mistake**: the Admin field warns when minimum > last released, the value can be lowered at
  once, and a failed read never blocks. Still, the first production value must be set with care; the migration seeds
  the current store version.
- **Clock and time zone**: the nudge uses the Seoul calendar date like the rest of the app; a device clock set far
  ahead only shows the nudge once more.
- **Store link on an emulator**: Play may not be installed; the `https` fallback opens a browser. The notice stays
  regardless.
- **Play in-app update**: deferred (R6); when added it needs a Play-distributed build to verify.

## Complexity Tracking

No violations to justify.
