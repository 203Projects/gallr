# Data Model: Gallery Profile Images

## `content.gallery_profile_images` (private)

| Column | Type | Rules |
|--------|------|-------|
| `gallery_id` | uuid PK | FK → `content.galleries(id)` on delete cascade; at most one image per gallery |
| `kind` | text | `logo` or `photo` |
| `storage_path` | text | unique; `^<gallery_id>/[0-9a-f]{64}\.jpg$` inside bucket `gallery-profile-images` |
| `source_page_url` | text | `http(s)://` URL of the page the image came from |
| `license` | text | non-blank, e.g. `official site`, `CC BY-SA 4.0 (Wikimedia Commons)` |
| `credit` | text null | required (non-blank) when `requires_attribution`; optional for official photos that name a photographer |
| `requires_attribution` | boolean | |
| `content_sha256` | text | 64 lowercase hex; equals the file name stem |
| `created_at`, `updated_at` | timestamptz | default `now()`; `updated_at` set on upsert |

RLS enabled with no policies; all privileges revoked from `public`, `anon`,
`authenticated`. Writes happen only through operator SQL (service/postgres role).

## Storage bucket `gallery-profile-images`

Public read; `allowed_mime_types = {image/jpeg}`; `file_size_limit = 262144` (re-applied on
replay so drifted settings are corrected). No
`storage.objects` insert/update/delete policy for client roles (operator uses service role).

## `public.list_gallery_profile_images()` → setof

| Field | Source |
|-------|--------|
| `gallery_id` | image row |
| `name_ko`, `name_en` | `content.galleries` |
| `kind` | image row |
| `storage_path` | image row |
| `credit` | image row (null when not required) |

Only galleries with `status = 'active'` and `merged_into_gallery_id is null`; ordered by
`gallery_id`.

## Shared domain (`com.gallr.shared.data.model`)

- `GalleryProfileImage(galleryId: String, nameKo: String, nameEn: String, kind: Kind,
  imageUrl: String, credit: String?)`, `enum Kind { LOGO, PHOTO }`.
- `GalleryProfileImages` — immutable index built from a list:
  - `find(galleryId: String?, nameKo: String, nameEn: String): GalleryProfileImage?`
  - id match wins; else name key match only when the key maps to exactly one image.
  - `GalleryProfileImages.EMPTY`.
- DTO → domain: `imageUrl = "$supabaseUrl/storage/v1/object/public/gallery-profile-images/$storagePath"`;
  rows with blank id/path or unknown kind are dropped (one malformed row never fails the list).

## App state

- `GalleryCandidate.profileImageUrl: String?`, `FollowedGalleryUi.profileImageUrl: String?`
- `GalleryDetailUiState.profileImage: GalleryProfileImage?`
