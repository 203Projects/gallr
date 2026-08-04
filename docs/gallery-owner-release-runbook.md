# Gallery owner release runbook

This runbook covers the additive rollout of the gallery-owner publishing loop,
public impact counts, the one-time Gallery Launch Kit, and transparent local
promotion. It does not authorize a production deployment. Use it only after a
named operator has approved the exact target environment.

The rollout preserves three boundaries:

- `gallrmap.com` remains the free visitor-first catalogue.
- `gallery.gallrmap.com` is the owner workspace; `admin.gallrmap.com` remains
  staff-only.
- Editorial Featured and organic discovery are unchanged. Promotion appears
  only in the separately labelled local-promotion surface.

## Environment and credential boundary

Current production (`gallr`, Singapore) and the Korea production candidate
(`gallr-korea`, Seoul) must use separate 1Password items. During rehearsal,
`gallr-korea` is the staging target; that temporary role does not authorize a
production cutover or permit either environment's credentials to be reused.
Use the 1Password CLI with secret references or hidden input; do not copy secret
values into this repository, command arguments, logs, Vercel build output, or
screenshots.

Browser/build configuration:

| Surface | Configuration |
| --- | --- |
| Owner workspace | `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY` |
| Staff Admin | `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`, `VITE_ADMIN_FIXTURE_MODE=false` |
| Public web | `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GALLR_EXHIBITION_SOURCE`; enable later slices explicitly with `GALLR_ENABLE_IMPACT`, `GALLR_ENABLE_RSVP`, and `GALLR_ENABLE_PROMOTION`; their optional endpoint overrides default to functions under `SUPABASE_URL` only after the matching slice is enabled |
| Mobile | Existing Supabase URL and publishable/anon key build configuration; promotion derives its function endpoint from the same URL |

Only a publishable/anon key may reach a browser or mobile bundle. Supabase
server secrets, the legacy service-role key, Stripe credentials, and RSVP hash
material are server-only.

Hosted Edge Function configuration:

| Function | Additional server-only configuration |
| --- | --- |
| `outbox-delivery` | `OUTBOX_DELIVERY_TOKEN`, `VERCEL_DEPLOY_HOOK_URL` |
| `legacy-catalog-mirror` (Seoul only) | `LEGACY_CATALOG_MIRROR_TOKEN`, exact Singapore `LEGACY_CATALOG_RECEIVER_URL`, `LEGACY_CATALOG_RECEIVER_TOKEN`, `LEGACY_CATALOG_MIRROR_REASON` |
| `legacy-catalog-mirror-receiver` (Singapore only) | `LEGACY_CATALOG_RECEIVER_TOKEN` |
| `create-launch-checkout` | `STRIPE_SECRET_KEY`, `STRIPE_LAUNCH_KIT_PRICE_ID`, `GALLERY_WORKSPACE_URL`; optional `LAUNCH_CHECKOUT_ALLOWED_ORIGINS` |
| `stripe-launch-webhook` | `STRIPE_SECRET_KEY`, `STRIPE_LAUNCH_WEBHOOK_SECRET` |
| `launch-rsvp` | `RSVP_HASH_SECRET` (at least 32 characters); optional `RSVP_ALLOWED_ORIGINS` |
| `record-exhibition-view` | Optional `IMPACT_ALLOWED_ORIGINS` |
| `promoted-nearby` | Optional `PROMOTION_ALLOWED_ORIGINS` |

Supabase supplies the project URL plus named `SUPABASE_PUBLISHABLE_KEYS` and
`SUPABASE_SECRET_KEYS` maps to hosted functions. Each gallery-product function
selects its component-named key when present and otherwise `default`; local CLI
single-key variables and legacy anon/service-role variables remain migration
fallbacks. Do not create custom secrets with the reserved `SUPABASE_` prefix.
The existing geocoder and outbox configuration remain governed by their own
READMEs and `docs/admin-media-and-outbox-runbook.md`.

## Release-slice boundary

Rehearse and activate only the approved release slice. Later schema may exist
dark in an environment without authorizing its functions, secrets, UI, paid
entitlements, or customer-visible states.

| Slice | Required runtime surface |
| --- | --- |
| R1 — ownership and free publishing | Owner and Admin workspaces, public web linkage, `outbox-worker` for media, and `outbox-delivery` for authenticated lifecycle delivery and prompt public rebuilds; during the mobile compatibility window, the Seoul mirror coordinator and Singapore receiver |
| R2 — public impact | R1 plus `record-exhibition-view` and impact-enabled public/mobile builds |
| R3 — Launch Kit | R2 plus `create-launch-checkout`, `stripe-launch-webhook`, and `launch-rsvp` with test-mode Stripe during rehearsal |
| R4 — transparent promotion | R3 plus `promoted-nearby` and the separately labelled owner/Admin/public/mobile promotion surfaces |

R1 does not require Stripe, RSVP, impact, or promotion secrets. The five
R2–R4 feature functions should remain undeployed or unconfigured until their
slice is approved.

## Preflight

1. Record the release revision, rehearsal project ref, current production
   project ref, intended production candidate, Vercel project IDs, Stripe
   account mode, and rollback owners. Confirm every target twice before any
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
3. Verify staging uses test-mode Stripe credentials and production uses live
   credentials. Never substitute one environment's item for the other.
4. Confirm Stripe has one immutable one-time Price for the Launch Kit. Record
   its currency and amount as the commercial source of truth.
5. Confirm the hosted Auth redirect allow-list contains the exact owner and
   Admin origins. Do not add a broad preview-domain wildcard.
6. Decide the owner account gate. For an invite-only pilot, keep hosted email
   signup disabled and pre-invite the pilot accounts. Self-service OTP account
   creation requires an explicit approval to enable hosted email signup. For
   local CLI rehearsal, keep `[auth].enable_signup = false` but leave
   `[auth.email].enable_signup = true`: the global setting blocks unknown
   accounts while the provider-specific setting keeps OTP login available to
   admin-provisioned owners.
7. Confirm no active promotion schedule or paid entitlement exists merely from
   deploying the schema. Customer-visible activation must remain an explicit
   staff/payment action.

## Staging rehearsal

Apply and validate one layer at a time:

1. Choose the migration path from the target's recorded lineage. For an
   established rehearsal database at the pre-gallery baseline, apply the five
   additive migrations in repository order, from
   `20260731120000_gallery_owner_foundation.sql` through
   `20260731233000_transparent_local_promotion.sql`. For a fresh regional
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
   identities. Stripe test mode is required only for R3 and later. Capture
   request IDs and record counts, never credential values or guest personal
   data.

Do not proceed if a cross-gallery read succeeds, a public role can call a
private helper, a webhook activates an unpaid session, an owner can publish
without staff, or a promoted item enters organic/Featured results.

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
   `outbox-delivery`, and retiring the legacy `submit-exhibition` function only
   after the public Submit entry point is verified to use the owner workspace.
   The five R2--R4 functions remain dark.
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
| Writers | Admin, Apps Script, public submission, functions, webhooks, operator jobs | target equivalents | Named freeze owner and verification for each writer |
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

1. Stop Apps Script catalogue sync and all operator imports; place Singapore
   Admin writes and the legacy public submission path into maintenance mode.
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
GALLR_SUPABASE_ANON_KEY="op://DEV/gallr-korea-candidate/credential" \
GALLR_EXHIBITION_CATALOG_SOURCE="canonical-v2" \
op run -- ./gradlew :composeApp:bundleRelease
```

Use the active item's stable ID in secret references so an archived item with a
previously reused title cannot shadow the current signing material. The active
item title remains `gallr-android-release-signing` for human lookup.

Expected result: `composeApp/build/outputs/bundle/release/composeApp-release.aab`
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
   public web build and mobile build. Confirm only published records count and
   the owner sees aggregate, non-unique totals.
3. **R3 — Launch Kit:** deploy checkout, webhook, and RSVP functions; register
   the exact Stripe webhook URL and event; verify its live signing secret; then
   expose the paid action. Make one approved live-mode purchase and refund only
   through the agreed operational process.
4. **R4 — transparent promotion:** deploy `promoted-nearby` and the owner/Admin
   promotion surfaces. Staff may approve a narrowly scheduled placement only
   after labels, locality, daily frequency cap, and unchanged organic results
   are verified on web and mobile.

Promote already-tested Vercel deployments rather than rebuilding from a
different revision. DNS changes and Auth redirect changes are separate,
recorded cutover actions.

## Smoke journey

Use one owner, one non-owner, one staff user, and two galleries:

1. The owner signs in by email OTP, requests one gallery, and cannot access the
   other gallery. The non-owner cannot use owner RPCs.
2. Staff approves the claim. The owner creates, saves, uploads one cover, and
   submits a complete exhibition. A pending claim may draft but may not submit.
3. Staff requests changes once, accepts the resubmission, and publishes it.
   The lifecycle receiver accepts the durable event, triggers one public-web
   rebuild, and the public link works; unpublished and archived records do not
   appear.
4. One public detail load records impact without exposing a write RPC or raw
   visitor identity. The owner sees updated aggregate counts.
5. A Launch Kit checkout activates only after a verified Stripe webhook. The
   public token resolves the correct exhibition; RSVP, manual guest add,
   pagination/search, and repeated check-in behave idempotently.
6. An owner requests promotion and staff schedules it. A matching visitor sees
   one clearly labelled placement; the same installation receives no second
   placement that day, a non-matching locality sees none, and Featured/order
   remain unchanged.
7. Staff-only Admin routes reject the owner account. Every claim, review,
   payment activation, and promotion transition has its expected audit record.

For an R1 rehearsal, complete steps 1–3 plus the R1 portions of step 7. Add
step 4 for R2, step 5 for R3, and step 6 for R4. Never create a paid entitlement
or promotion merely to complete an earlier release slice.

## Monitoring and recovery

Monitor function error rates, Stripe webhook delivery, pending Launch Kits,
owner submissions awaiting review, dead-lettered outbox events, RSVP rate-limit
volume, active promotion schedules, and unexpected metric/impression growth.
Do not log guest names/emails, claim evidence, bearer tokens, raw installation
keys, IP addresses, or Stripe secrets.

Frontend rollback is reassignment to the previous healthy Vercel deployment or
mobile release. Function rollback is redeploying the last compatible revision.
Database migrations are additive and are not reversed during an incident;
disable the affected customer-visible entry point, preserve evidence, and ship
a reviewed forward migration. Disabling checkout or promotion must not remove
free publishing or visitor discovery.

## Rehearsal history

| Date | Operator | Change record | Target | Slice | Result |
| --- | --- | --- | --- | --- | --- |
| 2026-08-01 | Hanshin | This task | `gallr-korea` (`oqrvbstopuppznxqoonp`) | R1 | Owner/Admin/public preview journey passed; 22 linked pgTAP files and 806 local assertions passed; linked lint clean; advisors had informational findings only; production cutover not authorized. |
| 2026-08-03 | Hanshin | This task | Singapore `gallr` → Seoul `gallr-korea` | R1 | Production replacement completed from revision `f4cef81`; Auth/database/Storage and embedded Storage hosts reconciled; web surfaces and owner OTP passed; Seoul is the sole active scheduler with an empty outbox; mobile 1.7.7 release candidates compile against Seoul; Singapore retained read-only for installed-client compatibility and rollback. |
