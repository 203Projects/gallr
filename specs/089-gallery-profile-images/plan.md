# Implementation Plan: Gallery Profile Images

**Branch**: `089-gallery-profile-images` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/089-gallery-profile-images/spec.md`

## Summary

Replace the text-only gallery monogram with a staff-curated logo or space photo. Curated
metadata lives in a private `content.gallery_profile_images` table; square JPEGs live in a
new public, migration-created `gallery-profile-images` bucket. Anonymous clients read one
`SECURITY DEFINER` RPC that returns only active, unmerged galleries. `shared` adds a
pure lookup index (gallery id first, exact normalized name key second, ambiguous names
never match), an API client, and a `Result`-returning repository. `composeApp` loads the
index once per session, resolves image URLs in ViewModels, and renders them in the
existing 56dp square slot with the monogram as loading/error fallback; the gallery
detail screen shows the image and, where required, the attribution credit. A
dependency-free Node operator script normalizes the approved manifest with macOS `sips`
and emits upload and idempotent SQL bundles; staging first, production only after
explicit approval.

## Technical Context

**Language/Version**: Kotlin 2.x (KMP), SQL (Postgres 17 / Supabase), Node 22 (operator script)
**Primary Dependencies**: Ktor client, kotlinx.serialization, Coil 3 `AsyncImage`, Compose Multiplatform — all already in `gradle/libs.versions.toml`; no new dependency
**Storage**: Supabase Postgres (`content.gallery_profile_images`), Supabase Storage public bucket `gallery-profile-images`
**Testing**: `commonTest` (kotlin-test, coroutines `runTest`, Ktor `MockEngine`), pgTAP, `node --test`
**Target Platform**: Android + iOS app; Supabase staging and production
**Project Type**: Mobile app + backend
**Performance Goals**: One small metadata request per app session (≤ ~200 rows, < 30 KB); images ≤ 512×512 JPEG, ≤ 120 KB each, disk-cached by Coil
**Constraints**: Lists must never wait for image metadata (FR-012); no third-party hotlinks (FR-007); DESIGN.md square corners and monochrome palette
**Scale/Scope**: 186 gallery records today, 76 curated images initially

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Spec-First | PASS | `spec.md` with prioritized stories and acceptance scenarios precedes this plan. |
| II. Test-First | PASS | Failing tests first for: index matching (`shared/commonTest`), DTO/API parsing, repository failure containment, ViewModel URL resolution (`composeApp/commonTest`), pgTAP RPC/grant contract, Node manifest validation. UI rendering is exempt but verified manually. |
| III. Simplicity | PASS | One table, one RPC, one bucket, one client/repository pair, one pure index. No DataStore cache (Coil disk cache suffices), no owner upload, no new library. |
| IV. Incremental Delivery | PASS | Backend contract + seed (US4) ships independently and is invisible to old clients; app P1 (My Gallr) works alone; P2/P3 add surfaces on the same data. |
| V. Observability | PASS | Repository failures logged via `AppLog.tagged("GalleryProfileImages")` with stable operation names and exception type only; operator script prints per-entry skip reasons and writes a receipt. |
| VI. Shared-First | PASS | Model, DTO, API client, repository, and matching rules in `shared/src/commonMain`; ViewModel resolution in `composeApp/commonMain/viewmodel`; rendering in `composeApp/commonMain/ui`; zero platform-source-set code. |

Post-design re-check (after Phase 1): PASS — the contracts add no platform code and no
business logic in composables; the only UI decision is "image else monogram".

## Project Structure

### Documentation (this feature)

```text
specs/089-gallery-profile-images/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── list_gallery_profile_images.md
│   └── operator-manifest.md
└── tasks.md            # /speckit.tasks
```

### Source Code (repository root)

```text
supabase/
├── migrations/<timestamp>_gallery_profile_images.sql   # table, bucket, RPC, grants
└── tests/database/050_gallery_profile_images.test.sql  # pgTAP

shared/src/commonMain/kotlin/com/gallr/shared/
├── data/model/GalleryProfileImage.kt          # model + GalleryProfileImages index
├── data/network/GalleryProfileImageApiClient.kt
├── data/network/dto/GalleryProfileImageDto.kt
└── repository/GalleryProfileImageRepository.kt (+ Impl)
shared/src/commonTest/kotlin/com/gallr/shared/...  # index, DTO, client, repository tests

composeApp/src/commonMain/kotlin/com/gallr/app/
├── App.kt                                     # load index once, pass StateFlow down
├── viewmodel/MyGallrViewModel.kt              # profileImageUrl on candidates/followed rows
├── viewmodel/GalleryDetailViewModel.kt        # profileImage on detail state
├── ui/components/GalleryAvatar.kt             # square image with monogram fallback (moved from AddGalleriesScreen)
├── ui/mygallr/{AddGalleriesScreen,MyGallrScreen}.kt
└── ui/gallery/GalleryDetailScreen.kt          # image + credit line
composeApp/src/commonTest/...                  # ViewModel tests
androidApp/.../MainActivity.kt, composeApp/src/iosMain/.../MainViewController.kt  # wire repository

scripts/gallery-profile-images/
├── README.md
├── gallery-profile-images.mjs                 # validate | prepare | sql
├── gallery-profile-images.test.mjs
└── approved-manifest.json                     # reviewed 2026-10-09 (76 entries)
```

**Structure Decision**: Existing KMP + Supabase + scripts layout; no new modules.

## Complexity Tracking

No constitution violations.
