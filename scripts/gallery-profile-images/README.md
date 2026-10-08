# Gallery profile images

Publishes the staff-reviewed gallery images from spec 089. Each gallery gets its
own logo or a photo of its space, re-hosted as a 512×512 JPEG in the public
`gallery-profile-images` bucket. Metadata lives in the private
`content.gallery_profile_images` table and reaches the apps through
`public.list_gallery_profile_images()`.

`approved-manifest.json` is the reviewed set from 2026-10-09: 76 galleries
(46 logos, 30 space photos). Sources are the galleries' official sites or
Wikimedia Commons. Exhibition artwork and exhibition covers are never used.
Attribution-requiring licenses (CC BY, CC BY-SA, KOGL) carry a `credit`, which
the gallery detail screen displays. To change the set, edit the manifest in a
reviewed pull request.

The script is dependency-free Node 22 and uses macOS `/usr/bin/sips` to
normalize images: logos are fitted to 416 px and padded on white, and photos are
scaled and centre-cropped. Transparent areas become white.

## Commands

Run the network-free checks first:

```sh
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/gallery-profile-images/gallery-profile-images.test.mjs
node scripts/gallery-profile-images/gallery-profile-images.mjs validate scripts/gallery-profile-images/approved-manifest.json
```

`prepare` downloads each source image from its third-party host and writes
square JPEGs plus `bundle.json` into an empty directory outside the checkout.
Entries that fail (HTTP error, SVG, photo too small, output over 256 KiB) are
listed under `skipped` and are not published. Review the skipped list and spot
check a few files before continuing.

```sh
node scripts/gallery-profile-images/gallery-profile-images.mjs prepare \
  scripts/gallery-profile-images/approved-manifest.json \
  /private/tmp/gallr-gallery-images
```

## Staging, then production

The migration `20261009120000_gallery_profile_images.sql` must already be
applied to the target through the normal migration release path.

1. Verify the target project reference. Inject the service-role key from that
   environment's 1Password item only for this command; never export it in a
   shell profile or pass it as an argument.

   ```sh
   GALLR_EXPECTED_PROJECT_REF='<20-character-ref>' \
   GALLR_SUPABASE_URL='https://<20-character-ref>.supabase.co' \
   op run --env-file=<private env file referencing op://…> -- \
     node scripts/gallery-profile-images/gallery-profile-images.mjs upload /private/tmp/gallr-gallery-images/bundle.json
   ```

   Objects are content-addressed (`<gallery_id>/<sha256>.jpg`), so re-running
   reports `exists` instead of overwriting.

2. Generate and apply the metadata transaction. It fails as a whole if any
   gallery id is missing from the target.

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

## Rollback

Delete the affected rows from `content.gallery_profile_images`; the apps fall
back to monograms immediately. Unreferenced objects can stay in the bucket or
be removed separately; they are never listed.
