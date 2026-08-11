# Implementation Plan: Personal Exhibition Map

**Branch**: `053-personal-exhibition-map` | **Date**: 2026-08-09 | **Spec**: [spec.md](spec.md)
**Input**: Direct-to-Seoul exhibition map with exact venue pins, proximity grouping, district
customization, private visit history, and best-effort photo matching.

## Summary

Replace the platform-specific Naver map surface with a common Compose MapLibre city map backed by
explicit country/city scope data, opening directly in Seoul while retaining reusable scope contracts.
Preserve exact exhibition coordinates for shared Haversine
nearby ranking and provider-neutral external navigation. Add an appendable, private visit diary with
immutable catalog snapshots, anonymous DataStore/app-file persistence, idempotent authenticated
Supabase migration, and optional sanitized photos in a new owner-only bucket. Add explicit
`country_code` through the catalog pipeline so the Korea-first implementation can add countries later
without changing its scope contracts.

## Technical Context

**Language/Version**: Kotlin 2.1.20, Compose Multiplatform 1.8.0, PostgreSQL/Supabase migrations,
minimal Android/iOS native interop
**Primary Dependencies**: kotlinx.serialization 1.7.3, kotlinx.datetime 0.6.1, coroutines 1.9.0,
DataStore 1.1.1, Supabase Kotlin 3.1.0, Coil 3.1.0; AndroidX ExifInterface to be added through the
version catalog for Android metadata decoding
**Storage**: Preferences DataStore plus app-private files for anonymous/offline visits; Supabase
PostgreSQL and a new private Storage bucket for authenticated visits/photos
**Testing**: kotlin-test/coroutines-test common tests, Compose/JVM unit tests, platform fixture tests,
Supabase pgTAP, DB reset/lint, GAS tests
**Target Platform**: Android API 26+ and iOS 16+
**Project Type**: Kotlin Multiplatform mobile app plus Supabase backend and catalog sync pipeline
**Performance Goals**: Near Me result/scope within two seconds with cached location; deterministic
cross-platform ordering; smooth batched country/city canvas on representative release devices
**Constraints**: Offline-capable personal state; foreground/on-demand location only; no raw photo
metadata persistence; private owner-only photos; no per-background-dot Compose/semantics nodes;
bilingual KO/EN; DESIGN.md
**Scale/Scope**: Korea and Seoul geometry in the initial release; current verified catalog processed
on device; schema/contracts prepared for additional countries without shipping a world map

## Constitution Check — Before Phase 0

- **I Spec-first**: PASS — approved visual references, `spec.md`, and `handoff.md` precede planning
  and implementation.
- **II Test-first**: PASS — each shared, Compose, platform, and database slice begins with a focused
  failing test/contract before implementation.
- **III Simplicity/YAGNI**: PASS — only Korea/Seoul geometry ships; nearby uses the already-loaded
  catalog instead of PostGIS; no global service, continuous tracking, or social-photo system is added.
- **IV Incremental delivery**: PASS — abstract discovery, Near Me, manual visits, photo matching, and
  diary refinement can be delivered and demonstrated independently in that order.
- **V Observability**: PASS — typed operation outcomes are logged without private notes, photo data,
  exact coordinates, private paths, or signed URLs.
- **VI Shared-first**: PASS — distance, scopes, projection, aggregation, matching, visit repositories,
  and sync live in shared/common code; Compose common owns UI; platform code only wraps location,
  camera/library, metadata/image encoding, private files, and external intents/URLs.

## Project Structure

### Documentation

```text
specs/053-personal-exhibition-map/
├── assets/
├── contracts/
│   ├── database.md
│   └── mobile.md
├── data-model.md
├── handoff.md
├── plan.md
├── quickstart.md
├── research.md
└── spec.md
```

### Source code

```text
shared/src/
├── commonMain/kotlin/com/gallr/shared/
│   ├── data/model/                 # country, scope, visit, nearby domain models
│   ├── data/network/dto/           # catalog and private visit DTOs
│   ├── map/                        # distance, scope, projection, aggregation, matcher
│   └── repository/                 # local/cloud visit data sources and sync service
├── commonTest/kotlin/com/gallr/shared/
├── androidMain/kotlin/com/gallr/shared/platform/  # UUID/private-file/media primitives if needed
└── iosMain/kotlin/com/gallr/shared/platform/

composeApp/src/
├── commonMain/kotlin/com/gallr/app/
│   ├── ui/tabs/map/                # map screen, canvas, panels, accessible list
│   ├── ui/visit/                   # log visit and private memory screens
│   └── viewmodel/                  # PersonalMapViewModel / LogVisitViewModel
├── commonTest/kotlin/com/gallr/app/
├── androidMain/kotlin/com/gallr/app/platform/     # location, media, sanitizer, map launcher
└── iosMain/kotlin/com/gallr/app/platform/

supabase/
├── migrations/                     # positive country/visit/private-bucket migration
└── tests/database/                 # pgTAP catalog, RLS, and Storage contracts

gas/                                # country_code sync column + focused tests
iosApp/                             # remove Naver SPM package after replacement verification
gradle/libs.versions.toml           # Android metadata dependency; remove Naver aliases when unused
```

**Structure Decision**: Keep business/domain/data logic in `shared/commonMain`, cross-platform UI in
`composeApp/commonMain`, and only irreducible OS media/location/navigation/file work in platform source
sets. Supabase remains the private authenticated persistence boundary; GAS changes only propagate the
new catalog country field.

## Phase 0: Research and Risk Closure

1. Record renderer, scope, nearby, visit, privacy, metadata, and navigation decisions in
   [research.md](research.md).
2. Generate Korea and Seoul normalized dot-mask fixtures from Natural Earth public-domain source data
   with a deterministic script or documented transformation; check provenance/version beside output.
3. Prototype one synthetic metadata fixture per platform before committing the photo flow:
   Android original-URI permission behavior and iOS Image I/O decoding must degrade to missing metadata
   without blocking manual logging.
4. Confirm no remaining app feature consumes `MapView`/Naver after the new tab is wired before removing
   dependencies and iOS cinterop.

## Phase 1: Foundation — Country Identity and Abstract Discovery

1. Write failing DTO/cache/database/GAS tests for `country_code`; implement the positive catalog
   migration and shared model propagation.
2. Write failing pure tests for scope registry, aggregation, projection, collision behavior, and
   coordinate-unavailable results.
3. Add checked-in geometry/provenance and implement `DotMapProjector` in shared code.
4. Write failing Compose tests for All/To Visit modes, Korea→Seoul→district navigation, grouped venues,
   selection, and equivalent-list state.
5. Implement the common screen/canvas/panels using handoff tokens and replace platform `MapView`.
6. Verify TalkBack/VoiceOver list equivalence and canvas summary semantics.

**Independent delivery**: A user can explore Korea and Seoul, saved/unexplored state, and grouped
exhibitions without location or visit infrastructure.

## Phase 2: Near Me and External Directions

1. Write failing shared tests for Haversine edge cases, deterministic ordering, scope resolution,
   missing coordinates, and unsupported locations.
2. Refactor current location access behind an injected one-shot/cached result while preserving
   on-action permission behavior.
3. Add Near Me ViewModel/UI states and the approximate projected user indicator; distances use only
   source coordinates.
4. Add provider-neutral external map launchers. Prefer installed Naver Map in Korea when supported,
   then system/universal fallback; keep provider choice outside shared business logic.
5. Remove Naver embedded SDK/cinterop/SPM/config only after Android and iOS direction handoff and the
   abstract tab pass acceptance.

**Independent delivery**: A fixed or real current position opens the supported scope, ranks results,
and hands exact coordinates to an external map; denial preserves manual discovery.

## Phase 3: Manual Private Visits

1. Write failing pgTAP for owner-only repeat visits, immutable snapshots, updates, deletes, and catalog
   independence; implement `exhibition_visits` and RLS.
2. Write failing shared tests for local create/edit/delete, derived visited state, historical snapshots,
   UUID idempotency, interrupted migration, and tombstones.
3. Implement local DataStore/app-file contracts, typed cloud source, and merge service.
4. Wire dependencies in Android/iOS entry points; keep synchronization decisions in shared code.
5. Add Log Visit manual screen, Visited mode, historical detail, and immediate local state update.

**Independent delivery**: An anonymous user can log, edit, delete, and review repeat visits without a
photo; sign-in migration is retryable and duplicate-free.

## Phase 4: Confirmed Photo Matching

1. Add synthetic source/expected-derivative fixtures and failing metadata/sanitization privacy tests.
2. Implement visit-specific Android library/camera and iOS library/camera gateways. Metadata absence or
   redaction returns a normal empty-metadata result.
3. Write failing shared candidate tests for date overlap, real distance, ambiguity, missing fields,
   unsupported geography, and deterministic ties; implement the pure matcher.
4. Write failing pgTAP for `exhibition_visit_photos`, private bucket, path validation, and cross-owner
   denial; implement schema and policies.
5. Add confirmation UI, private-photo copy, upload retry/save-without-photo paths, and deletion cleanup.

**Independent delivery**: A user can choose or take one photo, explicitly confirm a candidate, and save
one sanitized private derivative; manual logging remains fully functional.

## Phase 5: Hardening and Release Verification

1. Test loading, empty, offline, denied/revoked permission, corrupt photo, failed upload, failed
   migration, deleted catalog row, dense venue, and incomplete coordinate states.
2. Profile canvas draw/hit-test and state recomposition on representative Android/iOS devices; retain
   one background draw surface and bounded semantic marks.
3. Verify dark theme, large text, reduced motion, TalkBack, VoiceOver, phone, tablet, and landscape.
4. Run all commands in [quickstart.md](quickstart.md), migration lineage validation, clean DB reset,
   pgTAP, DB lint, GAS checks, Android assembly, and iOS framework compile.
5. Update privacy disclosures and release notes before production shipping. Do not deploy or mutate
   production credentials as part of implementation.

## Constitution Check — After Phase 1 Design

PASS. The data model and contracts preserve a single shared source of truth, retain exact coordinates
separately from visual cells, avoid premature global/PostGIS systems, provide independently shippable
stories, and define privacy-safe observability and test-first gates. Platform seams contain only OS API
work and return typed results to common logic.

## Complexity Tracking

No constitution violation is planned. Explicit country persistence is the smallest durable identity
needed for the requested country/city structure; the feature does not ship unused world geometry or a
global catalog service.
