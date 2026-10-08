# Gallery profile images

Publishes the staff-reviewed gallery images from spec 089. Each gallery gets its
own logo or a photo of its space, re-hosted as a 512×512 JPEG in the public
`gallery-profile-images` bucket. Metadata lives in the private
`content.gallery_profile_images` table and reaches the apps through
`public.list_gallery_profile_images()`.

`approved-manifest.json` is the reviewed set from 2026-10-09: 76 galleries
(46 logos, 30 space photos). Sources are the galleries' official sites or
Wikimedia Commons. Exhibition artwork and exhibition covers are never used.
Attribution-requiring licenses (CC BY, CC BY-SA, KOGL) carry a `credit` naming
the author, license and the crop; official photos may also name their
photographer. The gallery detail screen displays the credit. Each entry pins
`source_sha256`, the SHA-256 of the exact source bytes that were reviewed; a
source that changes later is skipped instead of being published unseen. To
change the set, edit the manifest in a reviewed pull request and re-pin the
changed entries after looking at the new image.

The script is dependency-free Node 22 and uses macOS `/usr/bin/sips` to
normalize images: logos are fitted to 416 px and padded on white, and photos are
scaled and centre-cropped. Transparent areas become white. Only JPEG, PNG, GIF,
WebP and ICO sources are accepted, and every output is checked to be a real JPEG.

## Commands

Run the network-free checks first:

```sh
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/gallery-profile-images/gallery-profile-images.test.mjs
node scripts/gallery-profile-images/gallery-profile-images.mjs validate scripts/gallery-profile-images/approved-manifest.json
```

`prepare` downloads each source image from its third-party host and writes
square JPEGs plus `bundle.json` into a new directory outside the checkout (it
refuses an existing one). Entries that fail (HTTP error, changed source bytes,
unsupported format, photo too small, output over 256 KiB) are listed under
`skipped` and are not published. Review the skipped list and look at the output
images before continuing.

```sh
node scripts/gallery-profile-images/gallery-profile-images.mjs prepare \
  scripts/gallery-profile-images/approved-manifest.json \
  /private/tmp/gallr-gallery-images
```

## Staging, then production

The migration `20261009120000_gallery_profile_images.sql` must already be
applied to the target through the normal migration release path.

1. Name the environment and its project reference. The script refuses a
   production reference labelled `staging` and anything else labelled
   `production`, using the committed
   `scripts/staging-rehearsal/production-project-ref.sha256`. Inject the
   service-role key from that environment's 1Password item only for this
   command; never export it in a shell profile or pass it as an argument.

   ```sh
   GALLR_TARGET_ENVIRONMENT='staging' \
   GALLR_EXPECTED_PROJECT_REF='<20-character-ref>' \
   GALLR_SUPABASE_URL='https://<20-character-ref>.supabase.co' \
   op run --env-file=<private env file referencing op://…> -- \
     node scripts/gallery-profile-images/gallery-profile-images.mjs upload /private/tmp/gallr-gallery-images/bundle.json
   ```

   Objects are content-addressed (`<gallery_id>/<sha256>.jpg`), so re-running
   reports `exists` instead of overwriting.

2. Generate and apply the metadata transaction. It fails as a whole if any
   gallery id or uploaded object is missing from the target, so SQL cannot
   publish rows for objects uploaded to a different project.

   ```sh
   node scripts/gallery-profile-images/gallery-profile-images.mjs sql \
     /private/tmp/gallr-gallery-images/bundle.json > /private/tmp/gallr-gallery-images/upsert.sql
   supabase db query --linked --project-ref '<20-character-ref>' \
     -f /private/tmp/gallr-gallery-images/upsert.sql < /dev/null
   ```

3. Verify with the public contract:
   `select count(*) from public.list_gallery_profile_images();`
   Then open My Gallr on a device against that environment.

Production follows the same steps with the production reference, and only after
staging has been checked and the release owner has explicitly approved it.

## Removal and rollback

Removing an entry from the manifest does not unpublish it; the SQL only inserts
and updates. To take an image down (for example at a gallery's request):

1. Delete its row from `content.gallery_profile_images`. The apps stop listing it
   on their next launch and show the monogram.
2. Delete its object through the Storage API with the service-role key
   (`DELETE /storage/v1/object/gallery-profile-images/<storage_path>`), because
   the bucket is public and the URL stays reachable until the object is gone.
   Objects are uploaded with a one-day cache lifetime; devices may keep a
   cached copy until their image cache expires.
3. Remove the entry from the manifest in the same pull request that records the
   takedown.

A full rollback repeats the same steps for every row.
