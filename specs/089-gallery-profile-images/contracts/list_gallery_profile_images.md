# Contract: `public.list_gallery_profile_images()`

`POST /rest/v1/rpc/list_gallery_profile_images` with the publishable key, empty JSON body.

Response `200`:

```json
[
  {
    "gallery_id": "46afce0d-c8a9-4b77-bc99-1c5638b35e54",
    "name_ko": "국제갤러리",
    "name_en": "Kukje Gallery",
    "kind": "logo",
    "storage_path": "46afce0d-c8a9-4b77-bc99-1c5638b35e54/<sha256>.jpg",
    "credit": null
  }
]
```

- `kind` ∈ `logo | photo`; clients drop rows with any other value.
- `credit` is non-null only when attribution is required.
- Rows exist only for active, unmerged galleries.
- `anon` and `authenticated` may execute; no other access to the underlying table.
- Image bytes: `GET /storage/v1/object/public/gallery-profile-images/<storage_path>` → 512×512 `image/jpeg`.
