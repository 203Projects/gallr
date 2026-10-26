# Threat Model

gallr is a monorepo containing Android and iOS consumer apps, a static Eleventy public site with a Vercel serverless route renderer, Vite staff/editor and gallery-owner portals, and a Supabase backend comprising Auth, Postgres/RLS/RPCs, Storage, and 15 Deno Edge Functions. Public clients carry publishable configuration while database membership/RPC controls own authorization. Several anonymous Edge Functions deliberately hold service authority and rely on independent origin, capability-token, installation-secret, kill-switch, quota, and input-bound controls. Privileged CI and operator workflows govern releases, deploy hooks, migrations, cross-project mirroring, media upload, and legacy retirement.

## Assets

- Supabase Auth identities and sessions, OAuth/password-recovery callbacks, verified-email state, and separate consumer, active gallery-owner, editor, contributor, publisher, and admin authority.
- Canonical/draft exhibition identities and versions, publication/archive state, staff review decisions, revision and idempotency state, audit records, and outbox events.
- Gallery claims and profiles, owner drafts/submissions, launch kits, RSVP guests, local promotions, curator notes, app-version policy, and personal routes.
- Public catalogue and shared-route publication integrity, visibility/revocation state, public metrics, and generated static build artifacts.
- Private/user-linked profiles, bookmarks, thoughts, visits/follows, account archive/deletion state, gallery-alert installations and push tokens, analytics receipts/quotas, and PII.
- Private and public Storage objects, signed upload/read capabilities, media metadata, export artifacts, and provider push credentials.
- Supabase secret/service credentials, provider keys, worker/delivery/mirror/scheduler tokens, database credentials/CA trust, deployment hooks, and Android/iOS signing material.
- Release integrity: reviewed production commit, immutable tag/release, branch provenance, Vercel source, signed mobile artifacts, migration lineage, and cutover evidence.

## Trust Boundaries

- Android and iOS resolve Supabase URL/key/catalogue settings into shipped apps; the SDK persists sessions and private calls add a user token. RPC/RLS rather than UI state must enforce ownership. Android validates public-key type and store release settings, while iOS accepts nonblank overrides and may fall back to a checked-in production publishable pair (androidApp/build.gradle.kts:11-72,188-228; iosApp/iosApp/Config.swift:3-30).
- Mobile durable preferences live in app-private Documents/files storage while exhibition cache and analytics retry queues use purgeable caches; this OS sandbox is a confidentiality boundary but not server authorization (shared/src/androidMain/kotlin/com/gallr/shared/platform/DataStorePath.android.kt:17-35; shared/src/iosMain/kotlin/com/gallr/shared/platform/DataStorePath.ios.kt:17-71).
- Admin/editor browsers persist Supabase sessions and call `admin_current_staff`; portal routing is presentation-only and database functions/grants own active-role enforcement, publication roles, revisions, and idempotency (admin/src/lib/supabase.ts:3-14; admin/src/components/AuthGate.tsx:179-208; supabase/migrations/20260721051120_admin_command_api.sql:1391-1505).
- Gallery authentication creates an identity only. Owner RPCs bind membership, gallery, object, version, revision, and request identity; media uses server-reserved owner paths, Storage RLS, and completion checks (gallery/src/auth/SupabaseOwnerAuth.ts:38-90; gallery/src/data/SupabaseOwnerRepository.ts:653-894; supabase/migrations/20260922023714_complete_workflow_email_delivery.sql:113-188).
- Eleventy build scripts fetch public catalogue/showcase data with a publishable key and select legacy or canonical readers; Vercel/live builds fail instead of using local seeds. Generated JSON becomes static public output (web/scripts/lib/exhibition-reader-source.js:13-41; web/scripts/fetch-exhibitions.js:626-677).
- Public `/route/{id}` requests reach a Vercel function that validates route/event inputs, calls a narrow published-route RPC, reads current catalogue rows, renders HTML, and records a closed event set. Database publication filtering and context-appropriate escaping are critical (vercel.json:3-8; web/api/_lib/route-handler.js:28-130; web/api/_lib/route-data.js:28-80).
- Six intentionally anonymous Edge surfaces and other public flows use service clients internally. CORS Origin is not caller authentication; independent opaque/install tokens, source digests, kill switches, body/shape limits, DB preconditions, source/project quotas, and non-enumerating responses must protect service authority (supabase/config.toml:454-499; supabase/functions/launch-rsvp/handler.ts:63-152; supabase/functions/mobile-analytics/handler.ts:266-360).
- Authenticated elevated Edge paths for account deletion, geocoding, editor invitations, and single curator-note drafting must bind the bearer to a live identity/role before Auth Admin, provider, or service RPC use; scheduled drafting instead uses a timing-safe independent token (supabase/functions/delete-account/backend.ts:137-223; supabase/functions/invite-editor/backend.ts:75-148; supabase/functions/draft-curator-notes/handler.ts:248-341).
- Outbox worker authenticates a static capability, leases one event, mutates Storage/account state or forwards a bounded idempotency-bound event; delivery reauthenticates and dispatches to narrowly validated Vercel, email, push, or mirror destinations (supabase/functions/outbox-worker/index.ts:157-200,632-810; supabase/functions/outbox-delivery/handler.ts:95-143,185-225,292-390).
- Migrations own the public schema, RLS, grants, privileged helpers, Storage policies, audit/outbox triggers, and role transitions. Search-path or grant mistakes can cross anonymous, user, tenant, editor, staff, and service boundaries for all clients (supabase/config.toml:7-18; supabase/migrations/20260731120000_gallery_owner_foundation.sql:717-825).
- CI and operator scripts cross from repository, PR, CLI, environment, filesystem, and evidence inputs into secrets, production databases/projects, providers, releases, and signing. Exact target/revision, trusted executable, protected credential transport, authorization, immutable input, and readback are privileged controls (scripts/staging-rehearsal/lib/run-psql-with-validated-target.mjs:69-139,764-791,983-1055; .github/workflows/release.yml:19-92).

## Attacker Capabilities

- A signed-out caller knows public Supabase configuration and can send arbitrary headers, methods, URLs, JSON bodies, IDs, events, and repeated requests to public Vercel, Edge, anon RPC, catalogue, RSVP, submission, and analytics surfaces.
- A registered consumer controls its own profile and permitted state and may invoke any exposed RPC/Storage endpoint, but starts without owner/editor/staff/service/release authority.
- A gallery claimant or active owner controls its own claims, drafts, media, submissions, launch inputs, guests, and request IDs, but must not cross galleries or publish without reviewed authority.
- An invited editor or lower-ranked staff member controls permitted content/workflows but must not acquire another role or use higher-ranked sibling operations.
- A public RSVP/link recipient or mobile installation controls its issued opaque token/installation secret and form/provider-token inputs, but not owner authority or another installation.
- A repository/PR author controls source and test inputs but is not assumed to control protected CI secrets, manual workflow dispatch, production approval, signing keys, cloud IAM, or operator evidence.
- A source reader can inspect committed current files and obtain any embedded secret; the repository is not assumed public and secret validity is not assumed.
- The attacker is not assumed to already possess operator credentials, a service key, provider secrets, the victim's session, production database access, or privileged deployment configuration.

## Security Objectives

- Grant authority only from server-owned current identity, membership, role, status, tenant, and ownership state; never from UI, profile metadata, client-selected objects, or Origin.
- Bind every privileged mutation to exact target/object/version/revision/actor/request identity, transactional idempotency, audit/outbox effects, and replay behavior.
- Expose only reviewed published fields/routes and keep unpublished, revoked, cross-tenant, private Storage, user-linked state, and identifiers non-enumerable.
- Keep public clients free of server/service/provider credentials; reject privileged-key forms in every packaging path and separate production/staging configuration.
- Require each service-key Edge function to fail closed without its own auth/capability, origin response policy, kill switch, secrets, bounds, quotas, target checks, and provider controls.
- Restrict uploads and reads to reserved actor/object paths, allowed size/type/status, no unsafe overwrite, and server-verified completion before publication.
- Context-encode all generated/rendered content and URLs; bound request/response/decompression/render work; validate outbound schemes/hosts/recipients; never forward credentials to untrusted destinations.
- Pin privileged SQL search paths, schema-qualify objects, revoke default grants, maintain RLS, and compare every overload/sibling concrete operation.
- Bind builds, releases, migrations, mirrors, cutovers, and destructive scripts to a reviewed environment, project, revision, executable, payload, approval, evidence, and readback while keeping credentials out of source/argv/logs.

## Assumptions

- No live deployment configuration, Supabase/Vercel settings, cloud IAM, branch protection, scheduler state, external policy/evidence, or secret-store contents were supplied; source proves accepted chains and checks, not deployed values.
- Local `supabase/config.toml` settings such as local redirects and network CIDRs are not treated as production state.
- Android and public web reject known Supabase secret/service-role key forms, while iOS, Admin, and Gallery accept any nonblank configured key; harmful exposure requires a privileged-key deployment input (README.md:97-101; androidApp/build.gradle.kts:11-33; web/scripts/supabase-api-headers.js:17-33; iosApp/iosApp/Config.swift:7-30; admin/src/lib/supabase.ts:3-14; gallery/src/lib/supabase.ts:3-13).
- Public web documentation still names an old anonymous-key compatibility variable, but current reader code consumes only `SUPABASE_PUBLISHABLE_KEY`; a mismatch would fail a live build rather than silently use that key (web/README.md:53-72; web/scripts/supabase-public-api-key.js:3-5).
- Catalogue sources differ by deployment: Android non-store/iOS Debug/public static web can default legacy; store/release mobile selects canonical-v2; dynamic route pages always use canonical-v2.
- Some Edge functions and legacy workflows are conditional on deployment secrets, schedulers, or feature flags; source-supported findings must state those prerequisites.
- The outbox-worker component-key resolver can select the first string from `SUPABASE_SECRET_KEYS` after preferred names, unlike the shared resolver; impact depends on the deployed secret-map shape (supabase/functions/outbox-worker/index.ts:111-145; supabase/functions/_shared/supabase_keys.ts:7-66).
- Production cutover guard is a read-only validation step, not a sandbox or reusable authorization for the separately reviewed command that follows.
- The current worktree contains a pre-existing modification to `gradlew.bat`; findings use the exact current state without attributing that change to this scan.
- Architecture mapping is complete enough for focused audit packets but does not represent exhaustive review of all 2,061 tracked files.

---

## Saved Model Context

- Source: scan
- Scan: 8fc559f0-7a1c-4ff8-8503-b99567394a76
- Target: gallr
- Revision: b822ebe15e01946282a225a8c60944389429bb64
- Snapshot: codex-security-snapshot/v1:sha256:5ff5ba9720b9f97c089ca31f5f9a0ed48c0e57e71c5a0a886907d5c2b96aee56
- Result status: completed
- Model origin: not recorded
- Model scope: not recorded
- Scan scope: .
- Scan scope description: Source-only Standard security scan of the entire 2061-file repository at registered revision b822ebe15e01946282a225a8c60944389429bb64.
