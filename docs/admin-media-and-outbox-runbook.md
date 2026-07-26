# Admin media and delivery runbook

This runbook describes the post-Sheet workflow for exhibition images and the
reliable delivery queue. It is written for editors, application maintainers,
and the person operating a production cutover.

The implementation is additive. Do not disable Google Sheets, Apps Script, or
the legacy `public.exhibitions` reader until the cutover gates in
`docs/exhibition-content-architecture.md` pass.

## Storage boundaries

| Location | Access | Purpose |
| --- | --- | --- |
| `exhibition-media` | Private | Immutable original uploads awaiting validation or attached to drafts |
| `exhibition-images` | Public, read-only to clients | Stable delivery objects consumed by the existing mobile and web clients |
| `content.media_assets` | Private database metadata | Byte identity, state, source and delivery paths, MIME, size, dimensions, checksum, lifecycle timestamps |
| `content.exhibition_version_media` | Private database metadata | Version-specific cover/gallery role, order, focal point, alt text, credit, and rights |

Image bytes are never stored in PostgreSQL. Public URLs are never accepted in a
generic exhibition patch. Published paths are immutable: a replacement receives
a new asset ID and a new object path.

## Add an image

1. Open a saved, non-archived draft in the admin and select **Media**.
2. Choose a JPEG, PNG, or WebP no larger than 10 MiB. The browser rejects other
   types and oversized files before making a reservation.
3. The admin calls `admin_request_media_upload` with the exhibition identity,
   exact working-version UUID, revision, original filename, detected MIME, and
   byte size.
4. The database verifies active contributor access, locks the working draft,
   checks the exact revision, creates a `pending_upload` asset, and returns a
   server-generated private path. User filenames never become object paths.
5. The browser asks Storage for a short-lived signed upload token scoped to that
   pre-registered path, then uploads directly to `exhibition-media`. The admin
   server never proxies the image bytes and the upload does not use upsert.
6. The admin calls `admin_finalize_media_upload`. The database verifies that the
   matching Storage object exists and that its stored MIME and size match the
   reservation. Valid positive dimensions and an optional SHA-256 checksum are
   recorded, then the asset becomes `ready`.
7. The admin attaches the asset as `cover` or `gallery`. This is the first step
   that changes the exhibition version, so it checks and increments the draft
   revision, writes an audit record, and enqueues one deduplicated
   `media.publish_requested` event.
8. The outbox worker claims one event with a lease token, downloads the private
   original, validates the complete JPEG/PNG/WebP container, rejects animation,
   caps either side at 8192px and decoded area at 12 megapixels, fully decodes
   the pixels in a restricted ImageMagick-WASM runtime, and computes SHA-256.
   It then uploads once to the deterministic public path
   `exhibition-images/cms/{asset_id}/original.{ext}`. A retry may accept an
   already-existing immutable destination, but it must never overwrite it.
9. The worker calls the service-only completion command. The command verifies
   the expected source and destination, stores the stable public URL, and moves
   the asset from `ready` to `published`.
10. The admin refreshes the media status. An exhibition version cannot publish
    while any attached image is still pending or ready. An exhibition with no
    image remains valid unless product policy later makes a cover mandatory.

If upload or finalization fails, the attachment is not created and the draft
revision is unchanged. The reserved object is eligible for stale-upload cleanup
after the configured retention period.

## Edit media metadata

Alt text, credit, rights URL, focal point, cover role, and gallery order belong
to `content.exhibition_version_media`, not to the shared byte asset. The current
admin edits alt text, credit, and rights URL; focal-point editing remains a
future UI control over the already version-scoped columns.

1. Edit the fields in the Media panel.
2. Save the metadata explicitly.
3. The command verifies the exact working-version UUID and revision.
4. The command updates only the draft attachment, increments the draft revision
   once, and writes an audit entry.
5. Existing published versions retain their previous metadata even when the
   same byte asset is reused by a new draft.

## Replace a cover

1. Upload and finalize the new image using the add-image steps.
2. Attach the new image as the cover.
3. In one transaction, the new image becomes cover at order `0`, the old cover
   becomes the first gallery image, and the remaining gallery order is
   normalized.
4. Confirm the previous cover in the gallery. Remove it explicitly if it should
   no longer be associated with the draft.

This preservation rule prevents a simple **Replace** action from silently
discarding an editor's previous image.

## Reorder gallery images

1. Use **Move up** or **Move down** in the Media panel.
2. The admin sends the exact ordered set of gallery asset IDs. The cover is not
   included and remains at order `0`.
3. The database rejects missing, duplicate, foreign, or extra IDs.
4. A valid update normalizes galleries to `1..N`, increments the revision once,
   and writes an audit entry.

## Remove an image

1. Select **Remove** on the cover or gallery item.
2. The command detaches only the current draft's attachment and normalizes the
   remaining order.
3. If another draft, published version, or submission still references the
   asset, the byte and asset remain unchanged.
4. If no reference remains, the asset becomes `orphaned` and one
   `media.cleanup_requested` event is queued. The database transaction never
   deletes Storage bytes.
5. The worker deletes the private source and, when present, the public delivery
   object through the Storage API.
6. The worker completion command rechecks that the asset is still orphaned and
   unreferenced before setting `purged_at`. It retains the metadata row for
   auditability.

If the asset was reattached while cleanup was in flight, completion fails
safely and the bytes must not be considered purged.

## Publish, archive, and restore retries

Publish, archive, and restore calls carry a client-generated request UUID.

1. The admin keeps the same UUID while retrying an ambiguous request for the
   same exhibition, working version, revision, and command.
2. The database serializes the request by actor and request UUID.
3. A completed identical request returns its stored response without repeating
   the mutation, audit entry, or outbox event.
4. Reusing the UUID for different parameters or a different command is rejected.
5. A successful response clears the pending UUID in the client. A version or
   revision change starts a new command with a new UUID.

## Outbox lease lifecycle

1. A trusted worker invocation claims exactly one event using
   `FOR UPDATE SKIP LOCKED`. Without `OUTBOX_DELIVERY_URL`, it claims only media
   publish/cleanup events and leaves lifecycle delivery pending. With a receiver
   configured, it claims the complete queue in order. Single-event claims
   prevent later items in a sequential batch from losing their leases before
   work begins.
2. Claiming marks the row `processing`, increments its attempt count, and
   assigns an unguessable lease token plus an expiry.
3. Concurrent workers receive disjoint rows.
4. Completion and failure require both the event ID and its current lease token.
   A stale worker cannot acknowledge a reclaimed event.
5. A transient failure records a bounded error message and schedules a bounded
   exponential retry.
6. Once the maximum attempt count is reached, the event is dead-lettered and is
   no longer claimable. An exhausted media-publication event also changes a
   still-`ready` asset to `rejected`, records the diagnostic, stops admin
   polling, and directs the editor to remove and upload again.
7. Delivered and dead-lettered events remain queryable for diagnosis.

Downstream rebuild requests carry the outbox event ID and deduplication key as
idempotency headers. The receiver must treat repeats as the same operation.

The Edge endpoint disables gateway JWT verification because it is invoked by a
private scheduler, then performs its own constant-time bearer-token check. Use
an opaque `OUTBOX_WORKER_TOKEN` with at least 32 diverse characters and keep it,
the Supabase server secret, and the legacy service-role fallback out of every
browser bundle.

## Local verification

Run these commands from the repository root:

```bash
supabase start
supabase db reset --local --no-seed
supabase test db supabase/tests/database --local
supabase db lint --local --schema public,content,content_private --fail-on error
supabase db advisors --local --type security --fail-on error
```

Then run the admin checks:

```bash
cd admin
npm run typecheck
npm test
npm run build
```

For a live local exercise:

1. Create a disposable Auth user and active contributor or publisher staff row.
2. Sign in to the local admin.
3. Create and save a draft before selecting an image.
4. Upload one valid image and confirm `pending_upload -> ready`.
5. Attach it and invoke the outbox worker.
6. Confirm the public object exists and the asset becomes `published`.
7. Edit alt text and verify the draft revision increments.
8. Add two gallery images, reorder them, and verify cover `0`, gallery `1..N`.
9. Publish with a fixed request UUID, then repeat the exact command and verify no
   duplicate audit or outbox row.
10. Edit the published exhibition to create a new draft, change its media
    metadata, and verify the published version remains unchanged.
11. Detach an otherwise unreferenced image, run cleanup, and confirm `purged_at`
    is recorded while the metadata row remains.

Use disposable records only. Do not run this exercise against production.

## Operational checks

Before enabling production writes, verify all of the following:

- No attached asset is stuck in `pending_upload` or `ready` beyond the agreed
  processing window.
- No outbox event has an expired processing lease.
- No event is dead-lettered without an assigned owner and resolution note.
- Every published attachment has a stable HTTPS public URL in the approved
  delivery bucket.
- Public image URLs load from both the website and mobile app.
- Retrying publish/archive/restore with the same request UUID creates no
  duplicate audit or outbox rows.
- Authenticated browser clients cannot call worker RPCs or delete Storage
  objects.
- The service credential used by the worker is absent from browser bundles,
  source control, logs, and screenshots.

## Failure handling

| Failure | Expected behavior | Operator action |
| --- | --- | --- |
| Upload token expires | No attachment or revision change | Choose the file again; stale reservation is cleaned later |
| Stored MIME/size differs | Finalization rejects the object | Inspect the file; reject or remove the staged object |
| Revision conflict | Media mutation has no side effects | Reload the draft and repeat against the current revision |
| Public copy times out | Event retries with its lease and backoff | Check Storage health; do not manually overwrite the destination |
| Destination already exists | Worker verifies the immutable retry path | Continue mark-published; investigate if byte identity is inconsistent |
| Downstream rebuild fails | Exhibition transaction stays committed; event retries | Repair receiver and replay the event |
| Cleanup races with reattach | Completion refuses to purge referenced metadata | Keep the asset and close the cleanup event with investigation notes |
| Maximum media-publication attempts reached | Event is dead-lettered; asset becomes `rejected` | Remove the rejected attachment and upload again after fixing the root cause |
| Maximum non-media attempts reached | Event becomes dead-lettered | Fix the root cause, then use an audited replay procedure |
