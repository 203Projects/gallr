# Research: Gallery Profile Images

## Image source

- **Decision**: Each gallery's own logo (apple-touch-icon, site logo) or a photo of its
  space from the official site or Wikimedia Commons. 186 records researched on
  2026-10-09 by six parallel searches; candidates verified as reachable images and reviewed
  on a contact sheet showing the 56dp in-app rendering.
- **Rationale**: The user rejected exhibition covers ("exhibitions track exhibition
  photos") and asked for representative images of the venue.
- **Alternatives**: Exhibition covers (rejected by user); Instagram profile pictures
  (CDN URLs expire, scraping requires sign-in); generated monograms only (status quo).

## Selection rules applied at review

- Low-confidence identity matches → monogram (49).
- Logo used when square-ish (aspect ≤ 1.5) and ≥ 96 px; otherwise a space photo ≥ 300 px;
  otherwise a moderately wide logo (aspect ≤ 2.2, ≥ 64 px); otherwise monogram.
- Eight further rejections after visual review (blank icons, placeholder favicons,
  artwork-like images, a badly cropped card).
- Result: 76 approved (46 logos, 30 photos); 15 photos carry attribution-requiring
  licenses (CC BY, CC BY-SA, KOGL); CC0 needs none. One city-site photo with an unverified
  license was dropped; after normalization, one SVG, one ICO, and six logos unreadable at
  56dp were removed.
- Five production test gallery records excluded.

## Hosting

- **Decision**: Re-host normalized 512×512 JPEGs in a public bucket
  `gallery-profile-images`, path `<gallery_id>/<sha256>.jpg`.
- **Rationale**: Six sources are http-only (blocked by iOS ATS), several CDN URLs are
  signed or rotate, and hotlinking leaks visitor requests to third parties. Content-hash
  paths make republishing idempotent and cache-safe.
- **Alternatives**: Hotlink original URLs (fragile, ATS); Supabase image transformations
  (consumes the quota the app deliberately avoids — see `nativeSupabaseImageUrl`).

## Normalization tooling

- **Decision**: macOS `sips` from a dependency-free Node 22 script: photos
  `--cropToHeightWidth` after scaling the short side to 512; logos scaled to fit 416 and
  `--padToHeightWidth 512 512 --padColor FFFFFF`; output JPEG quality 82. SVG sources are
  rejected and reported.
- **Rationale**: Matches the repository's dependency-free script convention; the operator
  runs on macOS. Pre-normalized squares keep the UI to a single `ContentScale.Crop`.
- **Alternatives**: `sharp` (adds a native npm dependency to scripts), Pillow (not in the
  toolchain contract).

## Public read contract

- **Decision**: `public.list_gallery_profile_images()` — `stable`, `security definer`,
  empty `search_path`, returns rows only for `status = 'active'` and
  `merged_into_gallery_id is null`; `EXECUTE` granted to `anon`, `authenticated`.
- **Rationale**: Exposes the joined gallery names without granting anonymous access to
  `content.galleries`; avoids a security-definer view (flagged by the security advisor in
  `database-tests.yml`).
- **Alternatives**: Public flat table (needs a sync trigger for names/status);
  owner-rights view (advisor warning).

## Matching

- **Decision**: Match by `galleryId` when the exhibition/follow record has one; else by
  `galleryKey(nameKo, nameEn)` (existing exact normalization). A name key that maps to more
  than one distinct image is treated as ambiguous and never matches.
- **Rationale**: Only 4 of 186 galleries are linked by `gallery_id` in the catalogue; 180
  match by name. Ambiguity rule guarantees SC-002 (no wrong image).

## App loading

- **Decision**: Fetch once per app session at the composition root into a
  `StateFlow<GalleryProfileImages>` starting empty; on failure log and stay empty.
- **Rationale**: Lists render immediately with monograms (FR-012); Coil disk cache handles
  image bytes offline. No persisted metadata cache (YAGNI).

## Attribution

- **Decision**: `credit` is non-null only when the license requires attribution; detail
  screen shows `Photo: <credit>` in `labelSmall`, `onSurfaceVariant`.
- **Rationale**: CC BY / BY-SA / KOGL Type 1 require author + license; showing it on the
  gallery detail screen where the image appears large is reasonable attribution.
