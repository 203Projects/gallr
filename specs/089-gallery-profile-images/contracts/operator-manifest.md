# Contract: approved manifest and operator bundle

`scripts/gallery-profile-images/approved-manifest.json` — array of:

```json
{
  "gallery_id": "uuid",
  "name_ko": "string",
  "name_en": "string",
  "kind": "logo | photo",
  "image_url": "http(s) URL of the source image",
  "source_page": "http(s) URL",
  "license": "non-blank",
  "credit": "string | null"
}
```

Validation (network-free): unique `gallery_id`, valid UUIDs, known `kind`, http(s) URLs,
`credit` present iff the license is not `official site…` and not CC0.

`prepare <manifest> <outDir>` downloads each `image_url`, normalizes to 512×512 JPEG, and
writes `<outDir>/<gallery_id>/<sha256>.jpg` plus `<outDir>/bundle.json` (entries with
`storage_path`, `content_sha256`, and a `skipped` list with reasons).

`sql <outDir>/bundle.json` prints one transaction that upserts
`content.gallery_profile_images` on `gallery_id` and asserts every referenced gallery exists.
