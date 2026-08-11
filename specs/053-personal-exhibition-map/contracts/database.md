# Database and Storage Contract: Personal Exhibition Map

## Public catalog rollout

The positive migration adds `country_code` through the canonical and legacy catalog stack. It must:

1. Validate the recorded migration lineage before generation.
2. Backfill every existing relevant row to `KR` before adding/validating non-null constraints.
3. Update canonical source, payload, checksum, refresh, reconcile, and legacy mirror functions.
4. Update legacy snapshot/restore tooling and its tests.
5. Update GAS exhibition `KNOWN_COLUMNS` in the same change.
6. Preserve older mobile readers by adding a field rather than renaming/removing fields.

## `public.exhibition_visits`

Client roles receive only owner-row CRUD. Required policy behavior:

- Anonymous unauthenticated requests cannot read or mutate rows.
- Authenticated user A cannot read, insert for, update, or delete user B's row.
- A valid insert requires `user_id = auth.uid()` and valid immutable snapshot data.
- Update cannot transfer ownership or rewrite immutable identity/snapshot fields.
- Delete cascades photo metadata but Storage object deletion remains an explicit client operation.

The mobile client uses stable UUID upsert for migration. A conflicting ID owned by another user is an
authorization failure, never an update.

## `public.exhibition_visit_photos`

Client roles receive owner-row SELECT/INSERT/DELETE; object path and ownership are immutable. A row is
valid only if:

- Its referenced visit exists and belongs to the same user.
- `bucket_id = 'visit-photos'`.
- `object_path` has exactly the expected owner/visit/photo structure.
- MIME, byte size, and dimensions satisfy the private derivative contract.

## `visit-photos` Storage bucket

- `public = false`.
- JPEG-only allowed MIME list.
- Explicit file-size limit established by sanitized-image tests and product quality review.
- SELECT/INSERT/DELETE policies require `auth.uid()` to equal the first path segment and the referenced
  visit/photo metadata owner.
- No bucket-wide public read, list, update, or unsigned URL policy.
- Objects are immutable; replacing a photo creates a new photo ID/path, then deletes the old object.

## Contract tests

pgTAP must prove:

- Country default, validation, canonical snapshot, catalog checksum, and legacy mirror behavior.
- Visit owner isolation for SELECT/INSERT/UPDATE/DELETE.
- Immutable visit fields and editable date/note behavior.
- Repeat visits to one exhibition are accepted.
- Catalog deletion/unpublishing does not delete visits.
- Photo-row ownership, visit ownership, path validation, uniqueness, and cascade behavior.
- Storage bucket privacy and cross-owner policy denial.
