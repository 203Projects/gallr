# Contract: approved manifest and operator bundle

`scripts/gallery-profile-images/approved-manifest.json` — array of:

```json
{
  "gallery_id": "uuid",
  "name_ko": "string",
  "name_en": "string",
  "kind": "logo | photo",
  "image_url": "http(s) URL of the source image",
  "source_sha256": "SHA-256 of the reviewed source bytes",
  "source_page": "http(s) URL",
  "license": "non-blank",
  "credit": "string | null"
}
```

Validation (network-free): unique `gallery_id`, valid UUIDs, known `kind`, http(s) URLs,
a 64-hex `source_sha256`, and a non-blank `credit` whenever the license is not
`official site` or CC0 (official photos may still carry a photographer credit).

`prepare <manifest> <outDir>` downloads each `image_url`, skips any source whose bytes
differ from `source_sha256` or are not JPEG/PNG/GIF/WebP/ICO, normalizes to a verified
512×512 JPEG, and writes `<outDir>/<gallery_id>/<sha256>.jpg` plus `<outDir>/bundle.json`
(entries with `storage_path`, `content_sha256`, and a `skipped` list with reasons).

`upload <bundle>` requires `GALLR_TARGET_ENVIRONMENT` (`staging`/`production`) to agree
with the committed production project fingerprint.

`sql <outDir>/bundle.json` prints one transaction that upserts
`content.gallery_profile_images` on `gallery_id` and asserts every referenced gallery and
uploaded storage object exists in the target.
