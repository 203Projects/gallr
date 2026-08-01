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
| Public web | `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GALLR_EXHIBITION_SOURCE`; the optional `GALLR_IMPACT_ENDPOINT`, `GALLR_RSVP_ENDPOINT`, and `GALLR_PROMOTION_ENDPOINT` overrides default to functions under `SUPABASE_URL` |
| Mobile | Existing Supabase URL and publishable/anon key build configuration; promotion derives its function endpoint from the same URL |

Only a publishable/anon key may reach a browser or mobile bundle. Supabase
server secrets, the legacy service-role key, Stripe credentials, and RSVP hash
material are server-only.

Hosted Edge Function configuration:

| Function | Additional server-only configuration |
| --- | --- |
| `outbox-delivery` | `OUTBOX_DELIVERY_TOKEN`, `VERCEL_DEPLOY_HOOK_URL` |
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
| R1 — ownership and free publishing | Owner and Admin workspaces, public web linkage, `outbox-worker` for media, and `outbox-delivery` for authenticated lifecycle delivery and prompt public rebuilds |
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
Before Seoul replaces Singapore:

1. Freeze a source inventory for database rows, Auth users, Storage buckets and
   objects, Edge Functions, secrets, Auth providers, redirect URLs, scheduled
   jobs, and external webhooks. Never record secret values in the inventory.
2. Rehearse the cross-project transfer into Seoul and reconcile counts plus
   representative checksums. Schema migration alone is not a data migration;
   Auth identities and Storage objects require explicit transfer procedures.
3. Define the write-freeze and final-delta window. Switch server jobs and
   webhook destinations before changing visitor or owner clients, and verify
   that no writer remains pointed at both projects.
4. Promote the tested web deployments and release new mobile builds against
   Seoul. Existing installed mobile versions keep their compiled Singapore
   endpoint, so keep the Singapore project available for an approved
   compatibility and rollback window.
5. Reconcile live traffic, Auth, catalogue reads, uploads, submissions, and
   outbox processing in Seoul before declaring it production. Retiring or
   pausing Singapore is a later destructive change with its own approval.

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
