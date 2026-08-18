# Implementation Plan: My Gallr gallery following

## Architecture

- Add `FollowedGallery` and `FollowedGalleryRepository` in `shared/commonMain`.
- Persist a versioned payload in the existing multiplatform Preferences DataStore.
- Extend `MyGallrViewModel` to derive gallery candidates and followed presentation state from the
  catalogue plus persisted records.
- Extend the existing My Gallr Compose surfaces; platform roots only construct and inject the new
  repository.
- No network schema or Supabase changes.

## Shared-first gate

Business rules—identity derivation, idempotent follow, baseline IDs, acknowledgement, and newness—are
shared Kotlin and covered before UI implementation.

## Test-first gate

1. Add failing repository tests for persistence, idempotency, acknowledgement, and malformed data.
2. Add failing ViewModel tests for bilingual search, gallery grouping, baseline behavior, newness,
   and navigation acknowledgement.
3. Implement only after both suites demonstrate expected compile/test failures.

## Identity limitation

The public mobile catalogue omits the canonical `gallery_id`. This slice keys a gallery by normalized
`venueNameKo` and `venueNameEn`. This is deterministic for the current data but cannot merge renamed
venues or distinguish two galleries with identical bilingual names. Stable gallery IDs should be
added to the public catalogue before cloud sync or push notification delivery.

## Constitution gates

- Shared-first: PASS.
- Thin platform adapters: PASS.
- Offline-first and explicit failure: PASS.
- Minimal permissions: PASS; no notification permission added.
- Spec and test first: REQUIRED.
