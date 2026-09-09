# Gallery owner release runbook

**Production status (2026-08-08):** Seoul is the production project. Singapore is retained only for
read-only installed-client compatibility. The anonymous intake and Apps Script writers are retired;
do not recreate them as rollout or rollback steps.

This runbook covers the additive rollout of the gallery-owner publishing loop,
public impact counts, the free-beta Gallery Launch Kit, and the separately paid
transparent local-promotion slice. It also covers the Gallery Info canonical venue defaults added by
`20260805125752_gallery_info.sql`. It does not authorize a production deployment. Use it only after a
named operator has approved the exact target environment.

The rollout preserves three boundaries:

- `gallrmap.com` remains the free visitor-first catalogue.
- `gallery.gallrmap.com` is the owner workspace; `admin.gallrmap.com` remains
  staff-only.
- Editorial Featured and organic discovery are unchanged. Promotion appears
  only in the separately labelled local-promotion surface.

## Environment and credential boundary

Current production (`gallr`, Seoul) and retained Singapore compatibility must use separate
1Password items. A preview branch or other rehearsal target is staging only and never authorizes a
production cutover or permits credentials to be reused across environments.
Use the 1Password CLI with secret references or hidden input; do not copy secret
values into this repository, command arguments, logs, Vercel build output, or
screenshots.

Browser/build configuration:

| Surface | Configuration |
| --- | --- |
| Owner workspace | `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`, `VITE_LAUNCH_KIT_ENABLED=false`, `VITE_OWNER_PROMOTION_ENABLED=false` |
| Staff Admin | `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`, `VITE_ADMIN_FIXTURE_MODE=false`, `VITE_ADMIN_PROMOTIONS_ENABLED=false` |
| Public web | `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `GALLR_EXHIBITION_SOURCE`; enable later slices explicitly with `GALLR_ENABLE_IMPACT`, `GALLR_ENABLE_RSVP`, and `GALLR_ENABLE_PROMOTION`; their optional endpoint overrides default to functions under `SUPABASE_URL` only after the matching slice is enabled |
| Mobile | Existing Supabase URL and publishable-key build configuration; keep `promotion.enabled` / `GALLR_PROMOTION_ENABLED=false` until R4 |

Only a publishable/anon key may reach a browser or mobile bundle. Supabase
server secrets, the legacy service-role key, and RSVP hash
material are server-only.

Hosted Edge Function configuration:

| Function | Additional server-only configuration |
| --- | --- |
| `outbox-delivery` | `OUTBOX_DELIVERY_TOKEN`, `VERCEL_DEPLOY_HOOK_URL`; for owner decision email, `RESEND_API_KEY` and `OWNER_NOTIFICATION_FROM_EMAIL` on a verified Resend sending domain |
| `legacy-catalog-mirror` (Seoul only) | `LEGACY_CATALOG_MIRROR_TOKEN`, exact Singapore `LEGACY_CATALOG_RECEIVER_URL`, `LEGACY_CATALOG_RECEIVER_TOKEN`, `LEGACY_CATALOG_MIRROR_REASON` |
| `legacy-catalog-mirror-receiver` (Singapore only) | `LEGACY_CATALOG_RECEIVER_TOKEN` |
| `launch-rsvp` | `RSVP_HASH_SECRET` (at least 32 characters); optional `RSVP_ALLOWED_ORIGINS` |
| `record-exhibition-view` | Optional `IMPACT_ALLOWED_ORIGINS` |
| `promoted-nearby` | `PROMOTION_DELIVERY_ENABLED=false` until R4; optional `PROMOTION_ALLOWED_ORIGINS` |
| `geocode-address` | `NAVER_MAPS_API_KEY_ID`, `NAVER_MAPS_API_KEY`; server-only and shared by Admin and eligible Gallery Info callers |
| `delete-account` | Component-named `delete_account` publishable and secret keys only; follow `docs/account-deletion-runbook.md` for the irreversible rollout |
| `gallery-alert-enrollment` | `GALLERY_ALERT_HASH_SECRET` (at least 32 characters); confirm the trusted client-address header before relying on per-source budgets |

Supabase supplies the project URL plus named `SUPABASE_PUBLISHABLE_KEYS` and
`SUPABASE_SECRET_KEYS` maps to hosted functions. Each gallery-product function
selects its component-named key when present and otherwise `default`; local CLI
single-key variables and legacy anon/service-role variables remain migration
fallbacks. Do not create custom secrets with the reserved `SUPABASE_` prefix.
The existing geocoder and outbox configuration remain governed by their own
READMEs and `docs/admin-media-and-outbox-runbook.md`.

For production, `VERCEL_DEPLOY_HOOK_URL` must be the hook whose Git ref is the
branch currently serving `gallrmap.com` (`main` in the current release model).
A READY deployment is not sufficient evidence by itself: a hook tied to a
feature branch produces a preview deployment and leaves the production domain
unchanged. Record the hook's project ID and non-secret branch metadata in the
change record, then confirm the resulting deployment target is `production`.

## Release-slice boundary

Rehearse and activate only the approved release slice. Later schema may exist
dark in an environment without authorizing its functions, secrets, UI, paid
entitlements, or customer-visible states.

The free-entitlement migration is the R3 server activation boundary because it
grants authenticated owners the activation command. Do not apply it during an
R1/R2 production change merely because the migration is additive; bind its
application to the approved R3 pilot window and keep the UI/public flags off
until the smoke journey passes.

| Slice | Required runtime surface |
| --- | --- |
| R1 — ownership and free publishing | Owner and Admin workspaces, Gallery Info plus `geocode-address`, public web linkage, `outbox-worker` for media, and `outbox-delivery` for authenticated lifecycle delivery and coalesced public rebuilds after the fixed quiet window; during the mobile compatibility window, the Seoul mirror coordinator and Singapore receiver |
| R2 — public impact | R1 plus `record-exhibition-view` and an impact-enabled public-web build |
| R3 — free Launch Kit beta | R2 plus the free-entitlement migration, `launch-rsvp`, the owner Launch Kit capability, public RSVP capability, environment-matched QR download, private guest list, and check-in |
| R4 — disabled paid-promotion compatibility surface | Outside the active roadmap; retain its paid-only guards and keep every owner/Admin/server/public/mobile control off |

R1 does not require RSVP, impact, or promotion secrets. The three
R2–R4 feature functions should remain undeployed or unconfigured until their
slice is approved.

R3 beta activation writes `entitlement_source=free_beta`. R4 remains paid-only
at the database request and delivery boundaries, so a free-beta Kit cannot be
promoted even if a later-slice client flag is accidentally enabled. The removed
Stripe checkout/webhook code is historical, not an R4 activation path; a future
paid package is not planned. Reconsideration requires an explicit product
decision, a superseding ADR, a new specification, and a release gate.

### R1 gallery-directory bootstrap

Migration `20260804093842_gallery_catalog_directory_sync.sql` seeds the owner
search directory from `public.exhibition_catalog_v2` and keeps it current when
canonical catalogue rows are inserted or their venue names change. One
case-folded, whitespace-normalized Korean venue name maps to one active gallery
organization. The private source mapping makes reconciliation idempotent and
can later be relinked if staff merge spelling aliases.

This bootstrap is evidence that the organization appears in Gallr's published
catalogue; it is not evidence that a person owns it. A matching pending gallery
organization may become active, but its membership remains pending until staff
approval. Addresses and coordinates remain exhibition snapshots: the bootstrap
does not choose one canonical venue for an organization because one gallery may
have multiple branches. Catalogue removal also does not delete the durable
gallery or an established customer workspace.

Before inviting owners, record the expected normalized-name count, inspect
obvious spelling aliases, and smoke-test Korean and English prefix search. A
new alias may be merged through a later reviewed staff workflow; do not repair
identity history by deleting a claimed gallery row.

### R1 Gallery Info boundary

Gallery Info is the canonical default for one gallery workspace. Saving it may
create or update the gallery's canonical venue, but the browser may only call
the reviewed owner RPCs; it must never write `content.galleries` or
`content.venues` directly. An active owner may edit their gallery. A pending
owner may edit only the new pending gallery they personally created; a pending
claim on an existing gallery must remain read/write denied, including through
the geocoder.

The address workflow must remain selection-based: search through the shared
`geocode-address` function, display at most three candidates, explicitly choose
one, and save the returned Korean/English address and coordinate pair. Provider
credentials stay server-side. The database-backed 10-per-caller and
30-per-project one-minute quotas are shared across Edge workers and fail closed
if authorization or quota state cannot be resolved. Active staff access must be
smoke-tested after owner access is enabled.

New exhibition creation copies the then-current Gallery Info venue name, city,
region, address, coordinates, hours, and contact into the new working version.
Those values are an independent snapshot: saving Gallery Info later must not
rewrite an existing draft, submission, or published version. Shared canonical
venue rows use clone-on-write so one gallery cannot change another gallery's
defaults. Every accepted Gallery Info save requires the current revision and
adds an audit record containing changed field names, not contact/address values.

Owners may also remove an exhibition from **My exhibitions**. This is an
owner-workspace soft hide, never a canonical delete: the exhibition, versions,
submission/review history, media, metrics, audit history, published-version
link, and public catalog remain intact. The owner command verifies the displayed
version and revision, records the actor, and filters the record only from the
owner list. Active owners may hide their gallery records; the same restricted
new-pending-gallery exception used by Gallery Info applies, while pending
claimants for existing galleries remain denied.

## Preflight

1. Record the release revision, rehearsal project ref, current production
   project ref, intended production candidate, Vercel project IDs, release
   slice flags, and rollback owners. Confirm every target twice before any
   write. For the Korea migration, record `gallr-korea` as both the rehearsal
   target and production candidate while `gallr` remains current production.
2. Confirm the product-surface and database workflows are green. Locally,
   validate migration lineage, replay a clean database, run all pgTAP tests,
   and run database lint/security advisors. Before linked pgTAP, confirm the
   target has `pgtap` installed in the `extensions` schema. Keep the target's
   database password in its own 1Password item so a privileged test session,
   backup, or transfer never depends on a temporary CLI login role. If that
   password is missing, stop; obtaining or resetting it is a separate
   credential change that requires approval.
3. Confirm the hosted Auth redirect allow-list contains the exact owner and
   Admin origins. Do not add a broad preview-domain wildcard. Confirm Google is
   enabled for the target project and its OAuth callback configuration matches
   that project before exposing the owner portal. Hash and compare the exact
   Supabase project reference compiled or configured for Android, iOS, Gallery,
   Admin, and Editor: every production fingerprint must match, every staging
   fingerprint must match, and the staging fingerprint must differ from
   production. Follow
   [`account identity and access`](account-identity-and-access.md) and stop on
   any mismatch.
4. Decide the owner identity gate separately from the gallery-access gate.
   Email OTP and Google OAuth establish an Auth identity only; neither grants
   owner access until the account submits a gallery claim and staff approves it
   in Admin. The shared production account plane supports self-service consumer
   and Gallery identity creation, so read back global signup, email signup, and
   verified-email settings before release. Prove a new identity receives no
   gallery, editor, or staff membership, then prove a pending gallery claim
   cannot submit or publish. A temporary invite-only owner pilot must be
   enforced through claim approval or a separately reviewed server-side
   invitation policy, not by disabling project-wide signup and breaking Gallr
   consumer account creation.
5. Approve the beta privacy notice plus guest-data retention, deletion, access,
   export, and incident procedure before collecting real names or email
   addresses. Name the pilot gallery/exhibition and its support owner.
6. Confirm no active promotion schedule or paid entitlement exists merely from
   deploying the schema. Also prove there is no pending Launch Kit with a price,
   Checkout Session, payment identifier, amount/currency, activation timestamp,
   entitlement source, or non-zero checkout attempt. The migration stops with
   `launch_kit_pending_payment_state_requires_resolution` rather than stranding
   that evidence. R3 activation must create `free_beta`; R4 remains disabled
   and outside the active roadmap.
7. Inventory hosted Edge Functions and payment-provider webhooks. Removing
   `create-launch-checkout` and `stripe-launch-webhook` from the repository does
   not undeploy an older hosted bundle or unregister an external webhook. If
   either exists, include its explicit retirement in the R3 change record after
   the forward migration removes its database RPCs. Preserve matching
   1Password items and evidence; secret deletion or rotation is a separate
   authorized credential change.

## Staging rehearsal

Apply and validate one layer at a time:

1. Choose the migration path from the target's recorded lineage. For an
   established rehearsal database at the pre-gallery baseline, apply every
   reviewed additive migration in repository order, from
   `20260731120000_gallery_owner_foundation.sql` through the current release
   head, including `20260804075948_owner_submission_media_snapshot.sql`. For a fresh regional
   replacement project with no migration history, first dry-run and then apply
   the complete canonical repository lineage, including those five migrations.
   Do not rename or reorder migrations and do not repair lineage to bypass a
   mismatch.
2. Re-run pgTAP, lint, and security advisors against staging. Verify generic
   canonical-table writes are absent, RLS prevents cross-gallery and private
   reads, and only reviewed `public` wrappers are exposed through the Data API.
   The SECURITY INVOKER wrappers require narrowly granted execution of their
   `content_private` implementations; that implementation schema must not be a
   Data API exposed schema, and every implementation must re-check the caller.
   Use the following baseline commands from the repository root:

   ```sh
   supabase test db --local supabase/tests/database
   supabase db lint --local \
     --schema public,content,content_private \
     --level warning \
     --fail-on error
   supabase test db --linked supabase/tests/database
   supabase db lint --linked \
     --schema public,content,content_private \
     --level warning \
     --fail-on error
   ```

   Some Supabase CLI versions create `cli_login_postgres` with non-inherited
   `postgres` membership. If linked pgTAP then reports that `plan(integer)`
   does not exist, first verify that `extensions.plan(integer)` is present.
   Do not grant the temporary CLI role broader database access. Run the same
   transactional test files through an authenticated `postgres` SQL session,
   or use `pg_prove` with the target's database password injected from
   1Password. Never place that password in a connection-string argument,
   environment file, shell history, or captured test output.
3. Configure server secrets and deploy only the functions required by the
   approved release slice in the table above. Respect each function's
   checked-in `verify_jwt` setting; custom-token, public, and webhook functions
   perform their own authentication, origin, token, signature, or payload
   checks.
4. Deploy preview builds of Admin, gallery, and public web against staging.
   Compile a mobile staging build from the same revision.
5. Run the applicable part of the smoke journey below with disposable staging
   identities. Capture
   request IDs and record counts, never credential values or guest personal
   data.

Do not proceed if a cross-gallery read succeeds, a public role can call a
private helper, a beta activation records payment evidence, a free-beta Kit can
request or serve R4 promotion, an owner can publish without staff, or a promoted
item enters organic/Featured results.

## Regional production replacement gate

Treat rehearsal success and production replacement as separate approvals.
Supabase projects are region-bound, so use the current official
[region-change guidance](https://supabase.com/docs/guides/troubleshooting/change-project-region-eWJo5Z),
[within-Supabase migration guide](https://supabase.com/docs/guides/platform/migrating-within-supabase),
and [Auth-user migration notes](https://supabase.com/docs/guides/troubleshooting/migrating-auth-users-between-projects)
when preparing the reviewed transfer procedure. Re-check those documents at
execution time because platform behavior can change.
Before Seoul replaces Singapore:

1. Freeze a source inventory for database rows, Auth users, Storage buckets and
   objects, Edge Functions, secrets, Auth providers, redirect URLs, scheduled
   jobs, and external webhooks. Never record secret values in the inventory.
   Record project-generated secret names only; their values are bound to their
   project and must never be copied to another project.
2. Classify every source runtime item as `carry`, `replace`, or `retire`. A
   regional replacement must preserve the existing product as well as add the
   approved gallery release slice. For R1 this means carrying the Admin
   geocoder, replacing `outbox-worker` with the reviewed revision, adding
   `outbox-delivery`. The public Submit entry point now uses the owner workspace and the retired
   `submit-exhibition` implementation has been removed from the repository; do not carry or
   recreate it in a replacement project.
   The three R2--R4 functions remain dark.
3. Rehearse the cross-project transfer into Seoul and reconcile counts plus
   representative checksums. Schema migration alone is not a data migration;
   Auth identities and Storage objects require explicit transfer procedures.
4. Define the write-freeze and final-delta window. Switch server jobs and
   webhook destinations before changing visitor or owner clients, and verify
   that no writer remains pointed at both projects.
5. Promote the tested web deployments and release new mobile builds against
   Seoul. Existing installed mobile versions keep their compiled Singapore
   endpoint, so keep the Singapore project available for an approved
   compatibility and rollback window.
6. Reconcile live traffic, Auth, catalogue reads, uploads, submissions, and
   outbox processing in Seoul before declaring it production. Retiring or
   pausing Singapore is a later destructive change with its own approval.

### Regional replacement inventory worksheet

Store the completed worksheet in the restricted change record, not in the
repository. Counts, checksums, and configuration names are evidence; emails,
object paths, tokens, secret values, and guest data are not.

| Area | Source evidence | Seoul evidence | Required disposition |
| --- | --- | --- | --- |
| Project | ref, region, Postgres version, health | same | Exact source and target identities approved |
| Database | byte size; exact counts and ID checksums for Auth, public, content, and catalogue tables | same | Source rows restored; rehearsal-only rows absent |
| Auth | user and identity counts; enabled provider names; Site URL; redirect origins; SMTP mode | same | Users preserved; configuration recreated manually |
| Storage | bucket names; object counts; path checksums | same | Metadata and object bytes both reconciled |
| Functions | name, reviewed bundle revision, JWT mode | same | Every item classified `carry`, `replace`, or `retire` |
| Secrets | names only | names only | Target-specific values sourced from target 1Password items |
| Schedulers | job name, cadence, active state, destination class | same | Exactly one target worker after cutover |
| Writers | Admin, gallery, functions, webhooks, operator jobs; retired writers recorded separately | target equivalents | Named freeze owner and verification for each active writer |
| Clients | Admin, owner, public web deployment IDs; mobile release versions | tested Seoul builds | Promotion order and rollback deployment recorded |

The source and target database passwords must be stored in separate, clearly
named `DEV` vault items before capture or restore. A generic platform token,
publishable key, server key, or a concealed field that has not been verified as
the database password does not satisfy this gate. Creating or resetting either
password is a credential change with its own explicit authorization.

### Existing rehearsal project becomes production

When the Seoul rehearsal project is also the production candidate, its test
users, test sessions, gallery claims, exhibitions, audit rows, outbox rows, and
Storage objects are disposable rehearsal state. Do not merge that state into
the source production data and do not preserve it merely because the project
ref and hosted configuration will be retained.

1. Capture and seal the source and rehearsal inventories before any reset.
2. Block owner/Admin preview writes and disable the Seoul scheduler before the
   destructive rehearsal-data reset. This reset requires separate approval.
3. Restore source production data into the already-tested schema lineage. The
   restore procedure must prove source/target table and column compatibility,
   suppress business triggers during the bulk load, restore sequence values,
   and leave the new R1 tables empty unless an explicitly mapped source row
   exists. Never hand-merge Auth users by email.
4. Transfer Storage object bytes separately and reconcile every source bucket.
   Restoring `storage.objects` metadata does not copy the underlying objects.
   Create any source bucket that was originally dashboard-managed, including
   `event-images`, before object reconciliation.
5. Recreate target-specific Auth, SMTP, provider, redirect, function-secret,
   webhook, and scheduler configuration. Do not copy generated
   `SUPABASE_*` values from Singapore. Existing target-specific SMTP and outbox
   items may be reused only after their exact target is re-confirmed.
6. Re-run the full R1 rehearsal against the restored production snapshot. Test
   identities created for this validation must be separately identified and
   removed or explicitly approved before live promotion.

Auth user rows and identities must retain their source UUIDs so profiles,
bookmarks, thoughts, and staff authorization remain connected. Because signing
keys are project-specific, choose and record one session policy before the
transfer: either require users to sign in again on Seoul, or perform a separately
reviewed signing-key continuity procedure. Re-login is the safer default. A
database restore alone must not be described as preserving active sessions.

### Write-freeze and promotion sequence

Use a short maintenance window rather than attempting an unreviewed dual-write
or live merge. Name one operator for every row in this order:

1. Confirm the retired Apps Script and anonymous submission writers remain absent, stop all operator
   imports, and place source Admin/gallery writes into maintenance mode.
2. Drain Singapore outbox work, record the final database and Storage
   inventory, and capture the final transfer artifacts in a mode-`0700`
   directory outside the repository.
3. Restore the final delta into Seoul, transfer remaining Storage objects, and
   reconcile counts/checksums before enabling any Seoul writer.
4. Configure and invoke exactly one Seoul media/outbox scheduler, then verify
   the queue drains without a duplicate Singapore delivery.
5. Promote the tested Admin and owner deployments, then the public web
   deployment. Update external webhook destinations before those clients can
   create new work.
6. Release mobile builds against Seoul. Keep Singapore healthy for the recorded
   compatibility window because installed older builds still target it.
7. End maintenance only after Auth, catalogue reads, Admin geocoding, owner
   submission/review/publication, uploads, public links, and outbox delivery
   pass on Seoul.

### Installed mobile compatibility after regional replacement

Pre-1.7.7 mobile binaries retain the compiled Singapore endpoint. Store release
and update prompts do not guarantee immediate adoption, so Singapore catalogue
freshness is an explicit compatibility responsibility rather than an assumed
side effect of the Seoul cutover.

Use the compatibility bridge only after its disabled-by-default migration has
been deployed from a reviewed commit and the Singapore owner has enabled the
exact Seoul source ref. The bridge replaces one complete, sanitized snapshot
of `exhibitions`, `events`, and `editors`; it does not mirror accounts or user
writes. Seoul catalogue transactions enqueue durable mirror work through the
existing outbox, while a five-minute Cron invocation reconciles missed work.
The Seoul coordinator and Singapore receiver use an opaque integration token;
each project keeps its own Supabase secret. Preserve the deletion guard and use
the operator dry run for independent verification. Seoul remains the only
catalogue writer.

Keep the bridge and Singapore project until measured supported-version traffic
meets the recorded retirement threshold. Removing or pausing Singapore remains
a separate destructive approval even after the mirror is disabled.

If any count or checksum differs, an Auth relation is broken, an object is
missing, or both projects accept the same writer, keep Singapore authoritative,
disable Seoul writes, preserve the evidence, and investigate. Do not improvise
a partial merge during the maintenance window.

## Mobile 1.7.7 packaging gate

Packaging is reversible; uploading to a store, enabling TestFlight/Play tracks,
or submitting for review is a separate external action that requires explicit
approval. Build Android as an Android App Bundle for the current Play workflow;
Google Play requires bundles for new apps and uses them to generate optimized
device APKs. Build iOS as an App Store Connect archive and export it locally
before any upload.

The `DEV` vault must contain these release items before packaging:

| Item | Required material | Rule |
| --- | --- | --- |
| `gallr-android-release-signing` | Secure Note containing the existing Play-registered `upload keystore` file attachment; `store password`, `key alias`, and `key password` concealed fields | Recover the existing upload key. Never generate a replacement merely because the item is missing. |
| `gallr-firebase-production` | Concealed `project id`, `application id`, `api key`, and `sender id` fields plus the `package name` | Must identify Firebase project `gallr-492618` and Android package `com.gallr.app`; never substitute staging or rehearsal values. |
| `gallr-korea-candidate` | `hostname` and public `credential` fields | These must identify the reviewed Seoul project; never use its server credential. |
| `gallr-app-store-connect` | Issuer ID, API key ID, and private-key document | Required only for an approved automated upload, not for the local archive step. |
| `gallr-google-play` | Service-account JSON document | Required only for an approved automated upload; a manual Play Console upload may be used instead. |

Android packaging uses a mode-`0700` temporary directory and 1Password secret
references. Field values must never be printed or written to `key.properties`:

```sh
release_dir="$(mktemp -d)"
chmod 700 "$release_dir"
trap 'rm -rf "$release_dir"' EXIT

signing_item_id="y3csgv6e5nolifwxdtkz2umffi"
firebase_item_id="$(op item get 'gallr-firebase-production' --vault DEV --format json | jq -r '.id')"
android_sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
test -d "$android_sdk/platform-tools"

op read --out-file "$release_dir/upload-keystore.jks" \
  "op://DEV/$signing_item_id/upload keystore"

ANDROID_HOME="$android_sdk" \
ANDROID_SDK_ROOT="$android_sdk" \
GALLR_ANDROID_STORE_FILE="$release_dir/upload-keystore.jks" \
GALLR_ANDROID_STORE_PASSWORD="op://DEV/$signing_item_id/store password" \
GALLR_ANDROID_KEY_ALIAS="op://DEV/$signing_item_id/key alias" \
GALLR_ANDROID_KEY_PASSWORD="op://DEV/$signing_item_id/key password" \
GALLR_SUPABASE_URL="op://DEV/gallr-korea-candidate/hostname" \
GALLR_SUPABASE_PUBLISHABLE_KEY="op://DEV/gallr-korea-candidate/credential" \
GALLR_FIREBASE_PROJECT_ID="op://DEV/$firebase_item_id/project id" \
GALLR_FIREBASE_APPLICATION_ID="op://DEV/$firebase_item_id/application id" \
GALLR_FIREBASE_API_KEY="op://DEV/$firebase_item_id/api key" \
GALLR_FIREBASE_SENDER_ID="op://DEV/$firebase_item_id/sender id" \
GALLR_EXHIBITION_CATALOG_SOURCE="canonical-v2" \
op run -- ./gradlew :androidApp:bundleRelease
```

Use the active item's stable ID in secret references so an archived item with a
previously reused title cannot shadow the current signing material. The active
item title remains `gallr-android-release-signing` for human lookup.

Expected result: `androidApp/build/outputs/bundle/release/androidApp-release.aab`
exists and `validateStoreRelease` passes before bundling. If the task reports a
missing keystore, stop and recover the exact key already registered in Play.

For iOS, confirm Xcode is signed into team `A5WW8X98HT`, then run from the
repository root without upload credentials:

```sh
cd iosApp
fastlane ios archive
```

Expected result: a signed archive plus `iosApp/build/release/gallr-1.7.7.ipa`.
The Release configuration embeds the reviewed Seoul fallback and selects
`canonical-v2`. Do not add `destination=upload`, `pilot`, `deliver`, `supply`,
or a Play publishing task until the operator separately approves the upload.

## Production activation order

Schema and server code may ship dark because the new tables begin empty and
customer-visible states require explicit actions. Activate in this order:

1. **R1 — ownership and free publishing:** migrations, owner/Admin bundles,
   exact Auth redirects, then the approved account gate. Pilot one gallery
   claim through staff approval, owner draft/submission, staff review, and
   publication.
2. **R2 — public linkage and impact:** deploy `record-exhibition-view`, then the
   public web build. Confirm only published records count and the owner sees
   aggregate, non-unique totals.
3. **R3 — free Launch Kit beta:** apply the free-entitlement migration, deploy
   `launch-rsvp` with its target-specific hash secret, build public web with RSVP
   enabled, retire any inventoried hosted checkout/webhook endpoints, and create
   an R3-on Gallery candidate with the exact target configuration while every
   R4 control remains off. Run the complete disposable smoke journey against
   that exact candidate, then promote the already-tested artifact for one
   approved pilot; do not rebuild merely to change a Vite flag.
4. **R4 — disabled paid-promotion compatibility surface:** keep
   `promoted-nearby` and every owner/Admin/server/public/mobile promotion control
   off. R4 is outside the active roadmap; retained schema and guards do not
   authorize activation or new payment work.

Promote already-tested Vercel deployments rather than rebuilding from a
different revision. DNS changes and Auth redirect changes are separate,
recorded cutover actions.

## Smoke journey

Use one owner, one non-owner, one staff user, and two galleries:

1. The owner signs in by email OTP, requests one gallery, and cannot access the
   other gallery. The non-owner cannot use owner RPCs.
2. Before approval, prove that a pending claimant for an existing gallery cannot
   read, save, or geocode Gallery Info. Prove that the creator of a brand-new
   pending gallery can select an address and save its initial Gallery Info.
3. Staff approves the claim. The active owner saves Gallery Info after explicitly
   selecting a bounded geocoder candidate, then creates an exhibition. Verify
   every venue default, including coordinates, hours, and contact, was copied.
   Edit the draft independently, change Gallery Info, and verify the existing
   draft remains unchanged while the next new draft receives the new defaults.
   Confirm stale revisions and unknown fields fail and the audit row contains no
   address/contact value. Also confirm the same staff account can still geocode.
4. The owner saves, uploads one cover, and submits the complete exhibition. A
   pending claim may draft but may not submit.
   From **My exhibitions**, cancel one removal confirmation and verify no write;
   then confirm removal for submitted and published fixtures. Verify both leave
   the owner list while their canonical rows, review state, published snapshot,
   public page, media, metrics, and audit history remain intact.
5. Staff requests changes once, accepts the resubmission, and publishes it.
   The lifecycle receiver accepts the durable event, triggers one public-web
   rebuild, and the public link works; unpublished and archived records do not
   appear. In production, verify the hook-created Vercel deployment reports
   Git ref `main` and target `production`; a READY preview deployment does not
   pass this step. In the published owner editor, verify the exhibition QR uses
   dark tones sampled from the public poster, retains a white quiet zone, and
   decodes to that exact environment-matched public page. Exercise the
   monochrome fallback once, and regenerate after a published title change.
6. One public detail load records impact without exposing a write RPC or raw
   visitor identity. The owner sees updated aggregate counts.
7. An active owner explicitly activates one `free_beta` Launch Kit without a
   redirect or payment state. The environment-matched public token resolves the
   correct exhibition and displays its published cover, description, exhibition
   period, reception, address, hours, and contact without private fields; the
   downloaded QR encodes that exact URL. RSVP, manual guest add, multi-Kit
   selection, pagination/search, token rotation, and repeated check-in behave
   idempotently. A pending claimant cannot enter R3.
8. An owner requests promotion and staff schedules it. A matching visitor sees
   one clearly labelled placement; the same installation receives no second
   placement that day, a non-matching locality sees none, and Featured/order
   remain unchanged.
9. Staff-only Admin routes reject the owner account. Every claim, Gallery Info
   save, review, entitlement activation, and promotion transition has its expected
   audit record.

For an R1 rehearsal, complete steps 1–5 plus the R1 portions of step 9. Add
step 6 for R2, step 7 for R3, and step 8 for R4. Never create a paid entitlement
or promotion merely to complete an earlier release slice.

## Monitoring and recovery

Monitor function error rates, Launch Kit activation failures,
owner submissions awaiting review, dead-lettered outbox events, RSVP rate-limit
volume, active promotion schedules, and unexpected metric/impression growth.
Do not log guest names/emails, claim evidence, bearer tokens, raw installation
keys or IP addresses.

Frontend rollback is reassignment to the previous healthy Vercel deployment or
mobile release. Function rollback is redeploying the last compatible revision.
Database migrations are additive and are not reversed during an incident;
disable the affected customer-visible entry point, preserve evidence, and ship
a reviewed forward migration. Disabling R3 activation or promotion must not remove
free publishing or visitor discovery.

## Rehearsal history

| Date | Operator | Change record | Target | Slice | Result |
| --- | --- | --- | --- | --- | --- |
| 2026-08-01 | Hanshin | This task | `gallr-korea` (`oqrvbstopuppznxqoonp`) | R1 | Owner/Admin/public preview journey passed; 22 linked pgTAP files and 806 local assertions passed; linked lint clean; advisors had informational findings only; production cutover not authorized. |
| 2026-08-03 | Hanshin | This task | Singapore `gallr` → Seoul `gallr-korea` | R1 | Production replacement completed from revision `f4cef81`; Auth/database/Storage and embedded Storage hosts reconciled; web surfaces and owner OTP passed; Seoul is the sole active scheduler with an empty outbox; mobile 1.7.7 release candidates compile against Seoul; Singapore retained read-only for installed-client compatibility and rollback. |
| 2026-08-22 | Hanshin + Codex | `067-gallery-launch-beta` local implementation | Disposable local PostgreSQL 17, local Vite/Eleventy, Android/iOS builds | R3 | Clean 77-migration replay; 41 pgTAP files/1,284 assertions, schema lint, security advisors, and two-session activation race passed. Gallery 92, Admin 213, all 10 Edge packages, public WCAG AA, 97 public Playwright tests, KMP/Android tests/build, and iOS simulator build passed. English/Korean desktop and mobile activation, image-led public invitation details, failed-cover fallback, environment RSVP URL, 12.9 KB QR decoded to the exact token, guest add, and check-in passed. Local evidence only; no hosted migration, deployment, flag, credential, or account-gate change. |
