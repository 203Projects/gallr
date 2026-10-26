# Security Review: gallr

## Scope

Source-only Standard security scan of the entire 2061-file repository at registered revision b822ebe15e01946282a225a8c60944389429bb64.

- Scan mode: repository
- Target kind: git\_worktree
- Target ID: target\_sha256\_dca7d96886c1cb9d12fe9314b43127eaebb0d81c46d5ef9aecce4885f1f4630e
- Revision: b822ebe15e01946282a225a8c60944389429bb64
- Snapshot digest: codex-security-snapshot/v1:sha256:5ff5ba9720b9f97c089ca31f5f9a0ed48c0e57e71c5a0a886907d5c2b96aee56
- Inventory strategy: repository
- Included paths: .
- Excluded paths: none
- Artifacts reviewed: 390 distinct source, configuration, migration, workflow, and test files received completed focused review across all eight threat surfaces.

Limitations and exclusions:
- No live deployment inspection, external credential validation, provider calls, or production configuration testing was performed.
- Unreviewed files are not claimed as reviewed; coverage concentrates on trust boundaries and high-risk entry points.

### Scan Summary

| Field | Value |
| --- | --- |
| Scan outcome | completed |
| Reportable findings | 14 |
| Severity mix | high: 1, medium: 9, low: 4 |
| Confidence mix | high: 14 |
| Validation mode | Independent baseline auditor and focused investigators, followed by parent source verification, counterevidence reconciliation, and attack-path analysis. |

Canonical artifacts: `scan-manifest.json`, `findings.json`, and `coverage.json`. This report is a deterministic projection of those files.

## Threat Model

gallr is a monorepo containing Android and iOS consumer apps, a static Eleventy public site with a Vercel serverless route renderer, Vite staff/editor and gallery-owner portals, and a Supabase backend comprising Auth, Postgres/RLS/RPCs, Storage, and 15 Deno Edge Functions. Public clients carry publishable configuration while database membership/RPC controls own authorization. Several anonymous Edge Functions deliberately hold service authority and rely on independent origin, capability-token, installation-secret, kill-switch, quota, and input-bound controls. Privileged CI and operator workflows govern releases, deploy hooks, migrations, cross-project mirroring, media upload, and legacy retirement.

### Assets

- Supabase Auth identities and sessions, OAuth/password-recovery callbacks, verified-email state, and separate consumer, active gallery-owner, editor, contributor, publisher, and admin authority.
- Canonical/draft exhibition identities and versions, publication/archive state, staff review decisions, revision and idempotency state, audit records, and outbox events.
- Gallery claims and profiles, owner drafts/submissions, launch kits, RSVP guests, local promotions, curator notes, app-version policy, and personal routes.
- Public catalogue and shared-route publication integrity, visibility/revocation state, public metrics, and generated static build artifacts.
- Private/user-linked profiles, bookmarks, thoughts, visits/follows, account archive/deletion state, gallery-alert installations and push tokens, analytics receipts/quotas, and PII.
- Private and public Storage objects, signed upload/read capabilities, media metadata, export artifacts, and provider push credentials.
- Supabase secret/service credentials, provider keys, worker/delivery/mirror/scheduler tokens, database credentials/CA trust, deployment hooks, and Android/iOS signing material.
- Release integrity: reviewed production commit, immutable tag/release, branch provenance, Vercel source, signed mobile artifacts, migration lineage, and cutover evidence.

### Trust Boundaries

- Android and iOS resolve Supabase URL/key/catalogue settings into shipped apps; the SDK persists sessions and private calls add a user token. RPC/RLS rather than UI state must enforce ownership. Android validates public-key type and store release settings, while iOS accepts nonblank overrides and may fall back to a checked-in production publishable pair (androidApp/build.gradle.kts:11-72,188-228; iosApp/iosApp/Config.swift:3-30).
- Mobile durable preferences live in app-private Documents/files storage while exhibition cache and analytics retry queues use purgeable caches; this OS sandbox is a confidentiality boundary but not server authorization (shared/src/androidMain/kotlin/com/gallr/shared/platform/DataStorePath.android.kt:17-35; shared/src/iosMain/kotlin/com/gallr/shared/platform/DataStorePath.ios.kt:17-71).
- Admin/editor browsers persist Supabase sessions and call `admin_current_staff`; portal routing is presentation-only and database functions/grants own active-role enforcement, publication roles, revisions, and idempotency (admin/src/lib/supabase.ts:3-14; admin/src/components/AuthGate.tsx:179-208; supabase/migrations/20260721051120\_admin\_command\_api.sql:1391-1505).
- Gallery authentication creates an identity only. Owner RPCs bind membership, gallery, object, version, revision, and request identity; media uses server-reserved owner paths, Storage RLS, and completion checks (gallery/src/auth/SupabaseOwnerAuth.ts:38-90; gallery/src/data/SupabaseOwnerRepository.ts:653-894; supabase/migrations/20260922023714\_complete\_workflow\_email\_delivery.sql:113-188).
- Eleventy build scripts fetch public catalogue/showcase data with a publishable key and select legacy or canonical readers; Vercel/live builds fail instead of using local seeds. Generated JSON becomes static public output (web/scripts/lib/exhibition-reader-source.js:13-41; web/scripts/fetch-exhibitions.js:626-677).
- Public `/route/{id}` requests reach a Vercel function that validates route/event inputs, calls a narrow published-route RPC, reads current catalogue rows, renders HTML, and records a closed event set. Database publication filtering and context-appropriate escaping are critical (vercel.json:3-8; web/api/\_lib/route-handler.js:28-130; web/api/\_lib/route-data.js:28-80).
- Six intentionally anonymous Edge surfaces and other public flows use service clients internally. CORS Origin is not caller authentication; independent opaque/install tokens, source digests, kill switches, body/shape limits, DB preconditions, source/project quotas, and non-enumerating responses must protect service authority (supabase/config.toml:454-499; supabase/functions/launch-rsvp/handler.ts:63-152; supabase/functions/mobile-analytics/handler.ts:266-360).
- Authenticated elevated Edge paths for account deletion, geocoding, editor invitations, and single curator-note drafting must bind the bearer to a live identity/role before Auth Admin, provider, or service RPC use; scheduled drafting instead uses a timing-safe independent token (supabase/functions/delete-account/backend.ts:137-223; supabase/functions/invite-editor/backend.ts:75-148; supabase/functions/draft-curator-notes/handler.ts:248-341).
- Outbox worker authenticates a static capability, leases one event, mutates Storage/account state or forwards a bounded idempotency-bound event; delivery reauthenticates and dispatches to narrowly validated Vercel, email, push, or mirror destinations (supabase/functions/outbox-worker/index.ts:157-200,632-810; supabase/functions/outbox-delivery/handler.ts:95-143,185-225,292-390).
- Migrations own the public schema, RLS, grants, privileged helpers, Storage policies, audit/outbox triggers, and role transitions. Search-path or grant mistakes can cross anonymous, user, tenant, editor, staff, and service boundaries for all clients (supabase/config.toml:7-18; supabase/migrations/20260731120000\_gallery\_owner\_foundation.sql:717-825).
- CI and operator scripts cross from repository, PR, CLI, environment, filesystem, and evidence inputs into secrets, production databases/projects, providers, releases, and signing. Exact target/revision, trusted executable, protected credential transport, authorization, immutable input, and readback are privileged controls (scripts/staging-rehearsal/lib/run-psql-with-validated-target.mjs:69-139,764-791,983-1055; .github/workflows/release.yml:19-92).

### Attacker Capabilities

- A signed-out caller knows public Supabase configuration and can send arbitrary headers, methods, URLs, JSON bodies, IDs, events, and repeated requests to public Vercel, Edge, anon RPC, catalogue, RSVP, submission, and analytics surfaces.
- A registered consumer controls its own profile and permitted state and may invoke any exposed RPC/Storage endpoint, but starts without owner/editor/staff/service/release authority.
- A gallery claimant or active owner controls its own claims, drafts, media, submissions, launch inputs, guests, and request IDs, but must not cross galleries or publish without reviewed authority.
- An invited editor or lower-ranked staff member controls permitted content/workflows but must not acquire another role or use higher-ranked sibling operations.
- A public RSVP/link recipient or mobile installation controls its issued opaque token/installation secret and form/provider-token inputs, but not owner authority or another installation.
- A repository/PR author controls source and test inputs but is not assumed to control protected CI secrets, manual workflow dispatch, production approval, signing keys, cloud IAM, or operator evidence.
- A source reader can inspect committed current files and obtain any embedded secret; the repository is not assumed public and secret validity is not assumed.
- The attacker is not assumed to already possess operator credentials, a service key, provider secrets, the victim's session, production database access, or privileged deployment configuration.

### Security Objectives

- Grant authority only from server-owned current identity, membership, role, status, tenant, and ownership state; never from UI, profile metadata, client-selected objects, or Origin.
- Bind every privileged mutation to exact target/object/version/revision/actor/request identity, transactional idempotency, audit/outbox effects, and replay behavior.
- Expose only reviewed published fields/routes and keep unpublished, revoked, cross-tenant, private Storage, user-linked state, and identifiers non-enumerable.
- Keep public clients free of server/service/provider credentials; reject privileged-key forms in every packaging path and separate production/staging configuration.
- Require each service-key Edge function to fail closed without its own auth/capability, origin response policy, kill switch, secrets, bounds, quotas, target checks, and provider controls.
- Restrict uploads and reads to reserved actor/object paths, allowed size/type/status, no unsafe overwrite, and server-verified completion before publication.
- Context-encode all generated/rendered content and URLs; bound request/response/decompression/render work; validate outbound schemes/hosts/recipients; never forward credentials to untrusted destinations.
- Pin privileged SQL search paths, schema-qualify objects, revoke default grants, maintain RLS, and compare every overload/sibling concrete operation.
- Bind builds, releases, migrations, mirrors, cutovers, and destructive scripts to a reviewed environment, project, revision, executable, payload, approval, evidence, and readback while keeping credentials out of source/argv/logs.

### Assumptions

- No live deployment configuration, Supabase/Vercel settings, cloud IAM, branch protection, scheduler state, external policy/evidence, or secret-store contents were supplied; source proves accepted chains and checks, not deployed values.
- Local `supabase/config.toml` settings such as local redirects and network CIDRs are not treated as production state.
- Android and public web reject known Supabase secret/service-role key forms, while iOS, Admin, and Gallery accept any nonblank configured key; harmful exposure requires a privileged-key deployment input (README.md:97-101; androidApp/build.gradle.kts:11-33; web/scripts/supabase-api-headers.js:17-33; iosApp/iosApp/Config.swift:7-30; admin/src/lib/supabase.ts:3-14; gallery/src/lib/supabase.ts:3-13).
- Public web documentation still names an old anonymous-key compatibility variable, but current reader code consumes only `SUPABASE_PUBLISHABLE_KEY`; a mismatch would fail a live build rather than silently use that key (web/README.md:53-72; web/scripts/supabase-public-api-key.js:3-5).
- Catalogue sources differ by deployment: Android non-store/iOS Debug/public static web can default legacy; store/release mobile selects canonical-v2; dynamic route pages always use canonical-v2.
- Some Edge functions and legacy workflows are conditional on deployment secrets, schedulers, or feature flags; source-supported findings must state those prerequisites.
- The outbox-worker component-key resolver can select the first string from `SUPABASE_SECRET_KEYS` after preferred names, unlike the shared resolver; impact depends on the deployed secret-map shape (supabase/functions/outbox-worker/index.ts:111-145; supabase/functions/\_shared/supabase\_keys.ts:7-66).
- Production cutover guard is a read-only validation step, not a sandbox or reusable authorization for the separately reviewed command that follows.
- The current worktree contains a pre-existing modification to `gradlew.bat`; findings use the exact current state without attributing that change to this scan.
- Architecture mapping is complete enough for focused audit packets but does not represent exhaustive review of all 2,061 tracked files.

## Findings

| Finding | Severity | Confidence | Detailed write-up |
| --- | --- | --- | --- |
| [Production cutover exposes the database URI to an inherited-PATH Node executable](#finding-1) | high | high | inline below |
| [AI draft regeneration has no quota or concurrency guard on provider spend](#finding-2) | medium | high | inline below |
| [Sign-out and account deletion leave account-bound push subscriptions active](#finding-3) | medium | high | inline below |
| [Unsolicited OAuth deep links can replace the mobile session](#finding-4) | medium | high | inline below |
| [Published catalogue text can execute JavaScript through the map JSON island](#finding-5) | medium | high | inline below |
| [Route-report resolution is not bound to the approved route version under review](#finding-6) | medium | high | inline below |
| [Promoted-nearby accepts unlimited caller-minted installation identities](#finding-7) | medium | high | inline below |
| [Launch RSVP throttling does not bound guest-list creation](#finding-8) | medium | high | inline below |
| [Anonymous mobile analytics can manipulate the public most-viewed ranking](#finding-9) | medium | high | inline below |
| [Bookmark cache exposes the previous account’s state after account switching](#finding-10) | medium | high | inline below |
| [Direct anonymous route-event RPC permits arbitrary metric inflation](#finding-11) | low | high | inline below |
| [Owner draft uploads bypass reservation and gallery-membership authorization](#finding-12) | low | high | inline below |
| [The public avatar bucket accepts unbounded names and unrestricted file types](#finding-13) | low | high | inline below |
| [Public page-load recording permits unbounded analytics inflation](#finding-14) | low | high | inline below |

### Confidence Scale

| Label | Meaning |
| --- | --- |
| high | Direct evidence supports the finding with no material unresolved blocker. |
| medium | Evidence supports a plausible issue, but material runtime or reachability proof remains. |
| low | Evidence is incomplete and the item is retained only for explicit follow-up. |

<a id="finding-1"></a>

### [1] Production cutover exposes the database URI to an inherited-PATH Node executable

| Field | Value |
| --- | --- |
| Severity | high |
| Confidence | high |
| Confidence rationale | The source and test harness both show the final invocation uses PATH-selected node and receives the validation URI; live operator PATH contents are unknown. |
| Category | Supply chain |
| CWE | CWE-426 |
| Affected lines | scripts/production-cutover/assert-production-target.sh:327-344, scripts/production-cutover/assert-production-target.sh:710-723, scripts/production-cutover/tests/assert-production-target.test.sh:106-125 |

#### Summary

The production target guard resolves a nominal trusted Node binary for one check, but the final credential-bearing validator invokes bare `node` through the inherited PATH. A substituted executable can steal the production PostgreSQL URI and forge successful attestation.

#### Root Cause

A hardened workflow validates script bytes but does not bind the interpreter used for the secret-bearing step to immutable provenance.

#### Validation

The source and test harness both show the final invocation uses PATH-selected node and receives the validation URI; live operator PATH contents are unknown. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- SAFE\_PATH copies inherited PATH; TRUSTED\_NODE is resolved only in solo mode; the final call is bare node; tests deliberately prepend a node wrapper and observe the validation environment.

#### Dataflow

The canonical finding records the affected path at scripts/production-cutover/assert-production-target.sh:327-344, scripts/production-cutover/assert-production-target.sh:710-723, scripts/production-cutover/tests/assert-production-target.test.sh:106-125, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Guard preserves inherited PATH.
- Final validator launches bare node with the production URI in its environment.
- Substitute captures the URI and exits successfully.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- The operator runs the production cutover guard with the poisoned PATH.
- The production database URL is supplied.

#### Severity

**High** — The scan assigned high severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Production database credential disclosure, forged target attestation, and code execution with operator privileges.

#### Remediation

Build an immutable absolute toolchain before accepting secrets, invoke the verified Node path for every validator, and bind the interpreter and dependencies to independently reviewed hashes or a non-writable installation. Add a regression proving a PATH-prepended node is never invoked.

Preventive controls:
- Reviewed validator digest
- Git environment cleanup
- Production target identity checks

<a id="finding-2"></a>

### [2] AI draft regeneration has no quota or concurrency guard on provider spend

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Focused Edge review traced the manual request from role check to provider call and searched the database/API path for leases and budgets; direct source confirms the provider call per request. |
| Category | Resource consumption |
| CWE | CWE-770 |
| Affected lines | supabase/functions/draft-curator-notes/handler.ts:168-220, supabase/functions/draft-curator-notes/handler.ts:305-326, supabase/functions/draft-curator-notes/backend.ts:192-224 |

#### Summary

Any publisher/admin can repeatedly or concurrently request a single curator-note regeneration. Each call reaches Anthropic before any lease, cooldown, idempotency key, or per-user/project budget is enforced.

#### Root Cause

Authorization to author content is conflated with unlimited authority to consume an external paid provider.

#### Validation

Focused Edge review traced the manual request from role check to provider call and searched the database/API path for leases and budgets; direct source confirms the provider call per request. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- Scheduled-loop count/time controls do not cover single mode; no idempotency key, cooldown, concurrency lease, or provider budget is checked before api.draft.

#### Dataflow

The canonical finding records the affected path at supabase/functions/draft-curator-notes/handler.ts:168-220, supabase/functions/draft-curator-notes/handler.ts:305-326, supabase/functions/draft-curator-notes/backend.ts:192-224, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Send repeated or parallel single/regenerate requests.
- Each request obtains a packet and invokes Anthropic.
- Only later storage determines which draft state remains.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- A qualifying exhibition exists.
- The attacker has a valid publisher/admin JWT.
- Anthropic credentials are configured.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Arbitrary provider spend, long-running concurrent work, and repeated draft/audit churn.

#### Remediation

Atomically claim a per-exhibition generation lease; require idempotency keys; enforce per-user and project token/request budgets; account actual provider usage and expire failed leases.

Preventive controls:
- Publisher/admin authorization
- Eligibility checks
- Output validation
- Human publication approval

<a id="finding-3"></a>

### [3] Sign-out and account deletion leave account-bound push subscriptions active

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Focused mobile review traced client teardown, registration APIs, server binding, fan-out eligibility, and deletion semantics; parent confirmed the sign-out path and identity-conflict check. |
| Category | Privacy and session isolation |
| CWE | CWE-459 |
| Affected lines | composeApp/src/commonMain/kotlin/com/gallr/app/App.kt:460-478, composeApp/src/commonMain/kotlin/com/gallr/app/App.kt:1287-1293, shared/src/commonMain/kotlin/com/gallr/shared/repository/GalleryAlertRegistrationRepositoryImpl.kt:80-145, supabase/migrations/20260829065400\_bound\_gallery\_alert\_enrollment.sql:283-333 |

#### Summary

Authentication teardown only clears the Supabase session. Existing gallery alert installation, token, and enabled subscriptions remain registered, so the device can receive account A's follow-derived notifications after logout or account switching.

#### Root Cause

Remote notification registration lifecycle is not coupled to the owning authentication session lifecycle.

#### Validation

Focused mobile review traced client teardown, registration APIs, server binding, fan-out eligibility, and deletion semantics; parent confirmed the sign-out path and identity-conflict check. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- The app enables subscriptions on auth changes but has no inverse transition; server registrations remain active and reject rebinding to a different non-null user.

#### Dataflow

The canonical finding records the affected path at composeApp/src/commonMain/kotlin/com/gallr/app/App.kt:460-478, composeApp/src/commonMain/kotlin/com/gallr/app/App.kt:1287-1293, shared/src/commonMain/kotlin/com/gallr/shared/repository/GalleryAlertRegistrationRepositoryImpl.kt:80-145, supabase/migrations/20260829065400\_bound\_gallery\_alert\_enrollment.sql:283-333, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Logout clears only Auth state.
- Server retains token and enabled subscriptions.
- Publication fan-out continues to the device.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- Account A enabled gallery alerts.
- A signs out or deletes the account.
- The installation remains active.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Follow-derived notification context from account A is disclosed after session end; account B may also be unable to bind the existing installation.

#### Remediation

Add an atomic authenticated revoke-installation operation; invoke it before sign-out/deletion and on identity changes; rotate local installation credentials; queue safe retry for offline cleanup; test A-to-B and deletion flows.

Preventive controls:
- Installation secret
- Account conflict rejection
- Manual per-gallery disable
- Recent-activity delivery bounds

<a id="finding-4"></a>

### [4] Unsolicited OAuth deep links can replace the mobile session

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Independent mobile and baseline reviews plus direct source inspection confirm the exported callback and unconditional implicit-token import. |
| Category | Authentication |
| CWE | CWE-384 |
| Affected lines | shared/src/commonMain/kotlin/com/gallr/shared/data/network/SupabaseProvider.kt:96-145, androidApp/src/main/AndroidManifest.xml:36-51, iosApp/iosApp/iOSApp.swift:50-55 |

#### Summary

The shared callback handler accepts fragment access and refresh tokens without validating the callback origin, state, or a pending login transaction, then imports them as the current session.

#### Root Cause

Legacy implicit-flow support bypasses the PKCE transaction binding configured for normal login.

#### Validation

Independent mobile and baseline reviews plus direct source inspection confirm the exported callback and unconditional implicit-token import. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- Android exports the browsable callback activity and both platforms forward URLs; the fragment branch has no scheme, host, state, or pending-flow check.

#### Dataflow

The canonical finding records the affected path at shared/src/commonMain/kotlin/com/gallr/shared/data/network/SupabaseProvider.kt:96-145, androidApp/src/main/AndroidManifest.xml:36-51, iosApp/iosApp/iOSApp.swift:50-55, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Attacker obtains their own Supabase session tokens.
- Craft callback fragment with those tokens.
- Victim device dispatches it to Gallr.
- Gallr imports the attacker's session.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- Victim opens or receives the crafted custom-scheme callback.
- Attacker supplies valid tokens for the attacker's account.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Victim actions are performed in an attacker-controlled account and the victim's current app session is replaced.

#### Remediation

Remove fragment-token import; accept only PKCE authorization-code callbacks; validate exact scheme/host/path and single-use state tied to a locally pending transaction; prefer verified HTTPS links.

Preventive controls:
- PKCE code exchange for the normal branch
- Manifest callback host filter
- Backend authorization remains tied to the imported account

<a id="finding-5"></a>

### [5] Published catalogue text can execute JavaScript through the map JSON island

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Two independent reviewers and direct source inspection establish controllable text, the raw-text sink, and lack of script-context escaping. |
| Category | Cross-site scripting |
| CWE | CWE-79 |
| Affected lines | web/map/index.html:95-97, web/\_data/mapData.js:10-27, supabase/migrations/20260731143000\_gallery\_owner\_publishing\_loop.sql:293-339 |

#### Summary

Published exhibition strings are JSON-serialized, marked safe, and embedded in a script raw-text element. A value containing `</script><script>…` terminates the data island and creates executable markup on the public map page.

#### Root Cause

A generic JSON serializer is treated as safe for an HTML script-element context.

#### Validation

Two independent reviewers and direct source inspection establish controllable text, the raw-text sink, and lack of script-context escaping. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- mapData includes owner-controlled name, venue, address, and slug fields; owner validation only bounds strings; the template uses dump|safe; deployment config provides no compensating CSP.

#### Dataflow

The canonical finding records the affected path at web/map/index.html:95-97, web/\_data/mapData.js:10-27, supabase/migrations/20260731143000\_gallery\_owner\_publishing\_loop.sql:293-339, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Submit a field containing a closing script tag and executable markup.
- Publisher approves the draft.
- Build embeds the literal terminator in the script island.
- Browser executes the injected script.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- The crafted exhibition is published.
- The static site rebuild includes it.
- A visitor opens /map/.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Persistent JavaScript execution on the public gallr origin, including page tampering and access to origin-readable browser state.

#### Remediation

Use an HTML-script-safe JSON serializer that escapes at least `<`, `>`, `&`, U+2028, and U+2029; remove the generic `safe` filter; add `</script>` regression fixtures and deploy a restrictive CSP.

Preventive controls:
- Publication review
- Nunjucks autoescape outside this sink
- textContent in the map client

<a id="finding-6"></a>

### [6] Route-report resolution is not bound to the approved route version under review

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | The versioned report model and unversioned resolver are explicit in SQL; the related listing decision demonstrates the intended stale-revision guard pattern. |
| Category | Workflow integrity |
| CWE | CWE-841 |
| Affected lines | supabase/migrations/20261010150000\_public\_route\_ranking.sql:25-48, supabase/migrations/20261010150000\_public\_route\_ranking.sql:197-227, supabase/migrations/20261010160000\_public\_route\_review.sql:151-188, supabase/migrations/20261010160000\_public\_route\_review.sql:191-231 |

#### Summary

Reports record the approval timestamp they concern, but the moderation queue groups only by route ID and resolution updates every open report while mutating the current route without expected-version checks.

#### Root Cause

The moderation command loses the report's approved\_at identity between queue presentation and resolution.

#### Validation

The versioned report model and unversioned resolver are explicit in SQL; the related listing decision demonstrates the intended stale-revision guard pattern. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- Reports are unique per account and approved\_at; queue grouping omits approved\_at; resolver accepts only route ID and resolution; no updated\_at or listing\_decided\_at guard exists.

#### Dataflow

The canonical finding records the affected path at supabase/migrations/20261010150000\_public\_route\_ranking.sql:25-48, supabase/migrations/20261010150000\_public\_route\_ranking.sql:197-227, supabase/migrations/20261010160000\_public\_route\_review.sql:151-188, supabase/migrations/20261010160000\_public\_route\_review.sql:191-231, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Report stores version A approved\_at.
- New approval changes the current route.
- Queue presents current route with reports aggregated by ID.
- Resolution removes version B or dismisses reports across versions.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- A report exists for approval version A.
- The owner edits and obtains approval version B.
- Staff resolves the aggregated report row.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** A newly reviewed valid route can be wrongly removed, or reports for multiple approval versions can be erased together.

#### Remediation

Group and resolve by route\_id plus approved\_at; require expected approved\_at and route updated\_at; update only version-specific reports; mutate current listing state only when reviewed values still match; drop old overloads.

Preventive controls:
- Publisher-only resolver
- Row lock
- Audit trail
- Restricted resolution values

<a id="finding-7"></a>

### [7] Promoted-nearby accepts unlimited caller-minted installation identities

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Focused Edge and public-abuse reviews plus direct handler inspection establish caller control; database uniqueness and missing aggregate quota/retention were independently traced. Activation is feature-flag dependent. |
| Category | Resource consumption |
| CWE | CWE-770 |
| Affected lines | supabase/functions/promoted-nearby/handler.ts:74-83, supabase/functions/promoted-nearby/handler.ts:96-143, supabase/migrations/20260731233000\_transparent\_local\_promotion.sql:74-100, supabase/migrations/20260822023330\_activate\_free\_gallery\_launch\_kit.sql:552-665 |

#### Summary

When delivery is enabled, callers choose arbitrary installation keys. Each new key creates a new frequency-cap identity, bypassing the once-per-day limit and allowing unbounded durable impression rows.

#### Root Cause

Frequency limiting trusts a self-asserted stable client identifier without server issuance or aggregate quotas.

#### Validation

Focused Edge and public-abuse reviews plus direct handler inspection establish caller control; database uniqueness and missing aggregate quota/retention were independently traced. Activation is feature-flag dependent. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- The database cap is viewer\_digest plus day; every new digest may insert an impression; no source/project quota or retention policy was found.

#### Dataflow

The canonical finding records the affected path at supabase/functions/promoted-nearby/handler.ts:74-83, supabase/functions/promoted-nearby/handler.ts:96-143, supabase/migrations/20260731233000\_transparent\_local\_promotion.sql:74-100, supabase/migrations/20260822023330\_activate\_free\_gallery\_launch\_kit.sql:552-665, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Generate a fresh installation\_key per request.
- Handler hashes it into a fresh viewer digest.
- Database treats each digest as a new installation and records another impression.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- Promotion delivery is enabled.
- An active paid placement matches the supplied locality.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Unbounded impression-state growth, corrupted promotion evidence, and bypass of the stated once-per-installation daily cap.

#### Remediation

Issue installation credentials server-side; add atomic source/project quotas and retention; keep the kill switch until deployed.

Preventive controls:
- Feature kill switch
- Paid-entitlement and publication checks
- Per-digest daily uniqueness

<a id="finding-8"></a>

### [8] Launch RSVP throttling does not bound guest-list creation

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Two focused reviewers independently identified the missing aggregate bound; source confirms header-derived identity and the public token workflow. Trusted ingress header rewriting remains deployment-dependent. |
| Category | Resource consumption |
| CWE | CWE-770 |
| Affected lines | supabase/functions/launch-rsvp/handler.ts:49-60, supabase/functions/launch-rsvp/handler.ts:89-147, supabase/migrations/20260731223000\_gallery\_launch\_kit.sql:717-781 |

#### Summary

The public RSVP endpoint limits only a digest derived from a request header and token to ten submissions per hour. It has no per-Kit, project-wide, or lifetime guest ceiling, so source rotation permits durable list pollution and table growth.

#### Root Cause

A local per-source rate bucket is used as the sole control for a durable public write workflow.

#### Validation

Two focused reviewers independently identified the missing aggregate bound; source confirms header-derived identity and the public token workflow. Trusted ingress header rewriting remains deployment-dependent. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- The database limits ten hits per source digest/hour; distinct emails create durable guest rows; repository review found no Kit/project/lifetime cap or cleanup.

#### Dataflow

The canonical finding records the affected path at supabase/functions/launch-rsvp/handler.ts:49-60, supabase/functions/launch-rsvp/handler.ts:89-147, supabase/migrations/20260731223000\_gallery\_launch\_kit.sql:717-781, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Submit unique fabricated guest emails.
- Rotate trusted source identities or continue at ten per hour.
- Rows accumulate without a total ceiling.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- A launch Kit is active and its intentionally public link is known.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Private operational guest-list corruption, misleading attendance totals, and unbounded guest/quota state growth.

#### Remediation

Use only platform-guaranteed client identity; add atomic per-Kit and project hourly/daily/lifetime ceilings, retention cleanup, abuse monitoring, and optional challenge/verification.

Preventive controls:
- Capability token
- Per-source hourly quota
- Input/state validation
- Service-role-only database mutations

<a id="finding-9"></a>

### [9] Anonymous mobile analytics can manipulate the public most-viewed ranking

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Public-abuse review and direct source verification establish unauthenticated ingestion, backdating, quota/ranking math, and public consumption. Activation is feature-flag dependent. |
| Category | Data integrity |
| CWE | CWE-345 |
| Affected lines | supabase/functions/mobile-analytics/handler.ts:151-175, supabase/functions/mobile-analytics/handler.ts:301-359, supabase/migrations/20261012120000\_most\_viewed\_exhibitions.sql:10-44 |

#### Summary

The JWT-free analytics endpoint accepts caller-chosen exhibition IDs, UUIDs, and dates up to seven days back. Its bounded but attacker-usable quota is sufficient to fill all ranking days and reach the maximum effective score for any published exhibition.

#### Root Cause

An untrusted telemetry signal is reused as an integrity-sensitive public ranking input.

#### Validation

Public-abuse review and direct source verification establish unauthenticated ingestion, backdating, quota/ranking math, and public consumption. Activation is feature-flag dependent. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- Handler accepts arbitrary UUID event\_id, exhibition\_id, and occurred\_on in a -7/+1 day window; source quota is 200/hour; ranking needs only two days and counts at most 20/day.

#### Dataflow

The canonical finding records the affected path at supabase/functions/mobile-analytics/handler.ts:151-175, supabase/functions/mobile-analytics/handler.ts:301-359, supabase/migrations/20261012120000\_most\_viewed\_exhibitions.sql:10-44, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Submit unique exhibition\_opened events backdated across ranking days.
- Stay within the 200-event source-hour quota.
- Aggregates assign up to 140 effective points.
- Public list orders the target near the top.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- MOBILE\_ANALYTICS\_ENABLED is true.
- The target exhibition is published.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Manipulation of the public discovery ranking, corrupted analytics, and possible exhaustion of the project-hour telemetry budget.

#### Remediation

Do not use anonymous caller-asserted events for public ordering; use authenticated/server-observed signals or device-bound attestations; use receipt time; keep the kill switch off until integrity is established.

Preventive controls:
- Feature kill switch
- Strict event grammar
- UUID deduplication
- Source and project quotas

<a id="finding-10"></a>

### [10] Bookmark cache exposes the previous account’s state after account switching

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Direct source review confirms one global cache, auth-state routing to that cache for every authenticated user, and a retry failure path that leaves it unchanged. |
| Category | Privacy and session isolation |
| CWE | CWE-226 |
| Affected lines | shared/src/commonMain/kotlin/com/gallr/shared/repository/CloudBookmarkRepository.kt:11-24, shared/src/commonMain/kotlin/com/gallr/shared/repository/SyncBookmarkRepository.kt:41-47, shared/src/commonMain/kotlin/com/gallr/shared/repository/SyncBookmarkRepository.kt:123-139 |

#### Summary

A process-global bookmark StateFlow is not keyed by user identity or cleared on auth transitions. Account B can observe account A's cached bookmarks while refresh is pending, and indefinitely if refresh fails.

#### Root Cause

Account-private presentation state is cached by process rather than keyed and invalidated by authenticated user identity.

#### Validation

Direct source review confirms one global cache, auth-state routing to that cache for every authenticated user, and a retry failure path that leaves it unchanged. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- SyncBookmarkRepository exposes the same cloud flow for every Authenticated state; refresh updates only after success and logs/returns after all retries fail.

#### Dataflow

The canonical finding records the affected path at shared/src/commonMain/kotlin/com/gallr/shared/repository/CloudBookmarkRepository.kt:11-24, shared/src/commonMain/kotlin/com/gallr/shared/repository/SyncBookmarkRepository.kt:41-47, shared/src/commonMain/kotlin/com/gallr/shared/repository/SyncBookmarkRepository.kt:123-139, but no expanded source-to-sink narrative was recorded.

Attack steps:
- The auth state switches to B.
- UI subscribes to the unchanged cloud StateFlow.
- A's exhibition IDs remain visible until successful refresh.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- Account A has bookmarks cached.
- A signs out and B signs in without process restart.
- The B refresh is pending or fails.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Disclosure of account A's bookmarked exhibitions to account B.

#### Remediation

Key cached state by user ID; synchronously publish empty/loading state on identity change; cancel and identity-check in-flight refreshes; clear account-scoped state on sign-out/deletion; test offline A-to-B transitions.

Preventive controls:
- Backend bookmark RLS
- Successful refresh replaces state
- Process restart initializes an empty cache

<a id="finding-11"></a>

### [11] Direct anonymous route-event RPC permits arbitrary metric inflation

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | Web, public-abuse, and baseline reviewers independently traced the same path; direct SQL inspection confirms anon grant and count+1 with no quota. |
| Category | Data integrity |
| CWE | CWE-345 |
| Affected lines | web/api/\_lib/route-handler.js:71-83, supabase/migrations/20261010014900\_personal\_routes.sql:557-588 |

#### Summary

The public route endpoint and its anon-granted definer RPC accept unlimited recognized event replays for any published route, allowing arbitrary manipulation of recipient-loop metrics.

#### Root Cause

An explicitly untrusted analytics primitive is directly exposed to anonymous callers without a bounded ingestion layer.

#### Validation

Web, public-abuse, and baseline reviewers independently traced the same path; direct SQL inspection confirms anon grant and count+1 with no quota. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- Only event vocabulary and publication state are checked; route UUID is public; RPC accepts the publishable key and increments every time.

#### Dataflow

The canonical finding records the affected path at web/api/\_lib/route-handler.js:71-83, supabase/migrations/20261010014900\_personal\_routes.sql:557-588, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Call record\_route\_page\_event directly or replay endpoint POSTs.
- Each call increments opened/started aggregates.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- The route is published and unrevoked.

#### Severity

**Low** — The scan assigned low severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Fabricated shared-open and direction-start metrics plus repeated database writes.

#### Remediation

Revoke anon execution on the raw RPC; issue route-bound short-lived event nonces; deduplicate per nonce; add source/route/project daily ceilings.

Preventive controls:
- Two-event allowlist
- Publication/revocation check
- Small HTTP body
- Preview-agent suppression in one transport

<a id="finding-12"></a>

### [12] Owner draft uploads bypass reservation and gallery-membership authorization

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | Database-focused review and direct policy inspection confirm the effective prefix-only policy and the stricter intended reservation/finalization flow. |
| Category | Authorization |
| CWE | CWE-862 |
| Affected lines | supabase/migrations/20260731143000\_gallery\_owner\_publishing\_loop.sql:97-113, supabase/migrations/20260731143000\_gallery\_owner\_publishing\_loop.sql:515-577, supabase/migrations/20260721043214\_cms\_foundation.sql:70-92 |

#### Summary

The effective Storage INSERT policy checks only an authenticated user's UID path prefix. Any signed-in account can bypass the reservation RPC and upload unattached objects without gallery membership or lifecycle/quota records.

#### Root Cause

Storage authorization enforces namespace ownership but not the application's reservation and membership invariants.

#### Validation

Database-focused review and direct policy inspection confirm the effective prefix-only policy and the stricter intended reservation/finalization flow. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- No later policy replacement was found; reservation/finalization functions check membership, exact path, status, expiry, MIME, and size, but direct Storage upload bypasses them.

#### Dataflow

The canonical finding records the affected path at supabase/migrations/20260731143000\_gallery\_owner\_publishing\_loop.sql:97-113, supabase/migrations/20260731143000\_gallery\_owner\_publishing\_loop.sql:515-577, supabase/migrations/20260721043214\_cms\_foundation.sql:70-92, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Choose any owner-drafts/\<uid\>/ path.
- Upload directly through Storage without creating a reservation.
- Object persists outside application lifecycle tracking.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- The attacker has an ordinary account and the public Storage endpoint/key.

#### Severity

**Low** — The scan assigned low severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Private Storage/cost abuse and orphaned object growth; no cross-tenant attachment or read was established.

#### Remediation

Require an exact, unexpired, caller-owned pending media\_assets reservation and applicable gallery membership in the INSERT policy; add quotas, cleanup, and negative tests.

Preventive controls:
- Private bucket
- Per-object MIME/size bounds
- UID namespace isolation
- Finalizer binding

<a id="finding-13"></a>

### [13] The public avatar bucket accepts unbounded names and unrestricted file types

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | Database and baseline reviews plus direct migration inspection establish the public bucket and extension-flexible, multiplicity-unbounded policy; deployment-wide provider limits remain unknown. |
| Category | Unrestricted upload |
| CWE | CWE-434 |
| Affected lines | supabase/migrations/008\_create\_avatars\_bucket.sql:1-28, supabase/migrations/20260725070256\_security\_advisor\_hardening.sql:15-35, supabase/migrations/011\_cleanup\_duplicate\_policies.sql:1-15 |

#### Summary

The public avatars bucket has no bucket-specific size or MIME restrictions, and its policy accepts every name whose first dot-delimited segment is the caller UID, enabling general public file hosting and unbounded per-account object creation.

#### Root Cause

A naming convention is treated as both ownership and upload-policy enforcement without canonical cardinality, MIME, or size constraints.

#### Validation

Database and baseline reviews plus direct migration inspection establish the public bucket and extension-flexible, multiplicity-unbounded policy; deployment-wide provider limits remain unknown. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- No later migration adds file\_size\_limit/allowed\_mime\_types or canonical name enforcement; public delivery and owner upsert remain enabled.

#### Dataflow

The canonical finding records the affected path at supabase/migrations/008\_create\_avatars\_bucket.sql:1-28, supabase/migrations/20260725070256\_security\_advisor\_hardening.sql:15-35, supabase/migrations/011\_cleanup\_duplicate\_policies.sql:1-15, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Upload many distinct UID-prefixed object names with arbitrary content types.
- Public bucket serves the resulting objects.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- The attacker has a normal account.

#### Severity

**Low** — The scan assigned low severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Public file hosting and storage/cost consumption; script execution was not established.

#### Remediation

Set a small bucket-specific size limit and image MIME allowlist; require one canonical object path per account or server-issued reservations; validate decoded content and clean legacy variants.

Preventive controls:
- Authentication
- UID ownership prefix
- Unknown provider-wide object limits

<a id="finding-14"></a>

### [14] Public page-load recording permits unbounded analytics inflation

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | Baseline, Edge, and public-abuse reviews independently agree on the dataflow; direct inspection confirms the unconditional increment. Metrics are explicitly directional, limiting severity. |
| Category | Data integrity |
| CWE | CWE-345 |
| Affected lines | supabase/functions/record-exhibition-view/handler.ts:64-128, supabase/migrations/20260731203000\_gallery\_public\_impact.sql:50-89 |

#### Summary

A JWT-free handler gates only on a forgeable Origin header and invokes a service-role RPC that increments owner-visible page-load totals on every request without source quota or deduplication.

#### Root Cause

CORS origin validation is used as an abuse control for a privileged public write path.

#### Validation

Baseline, Edge, and public-abuse reviews independently agree on the dataflow; direct inspection confirms the unconditional increment. Metrics are explicitly directional, limiting severity. Validation details were not recorded separately.

Validation method: Independent focused review plus parent source verification

Evidence:
- Non-browser callers can forge allowed Origin; valid published IDs reach the service-only RPC; no source/project quota, deduplication, or kill switch was found.

#### Dataflow

The canonical finding records the affected path at supabase/functions/record-exhibition-view/handler.ts:64-128, supabase/migrations/20260731203000\_gallery\_public\_impact.sql:50-89, but no expanded source-to-sink narrative was recorded.

Attack steps:
- Replay valid POST requests for the exhibition ID.
- Handler calls the service-role backend each time.
- Daily aggregate increments without bound.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

Preconditions:
- A target exhibition is published.

#### Severity

**Low** — The scan assigned low severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Arbitrary inflation of owner-facing 30-day/all-time page-load totals and sustained database write load.

#### Remediation

Add database-enforced source/project quotas or bounded deduplication tokens, daily saturation ceilings, monitoring, and an operational kill switch.

Preventive controls:
- Publication-state check
- Bounded schema/body
- Service-only raw RPC
- Directional metric labeling

## Reviewed Surfaces

| Surface | Risk Area | Outcome | Notes |
| --- | --- | --- | --- |
| Supabase identity, memberships, RLS, RPC grants, and role hierarchy | Authentication and authorization | Reported | Version-binding report flaw validated; broader RLS/grant review found no cross-tenant table bypass. |
| Admin and editor browser portals | Privileged browser workflows | No issue found | Role checks and server-side authority were reviewed; no independent portal authorization bypass established. |
| Gallery owner portal and publishing workflow | Tenant isolation and content lifecycle | Reported | Owner draft Storage authorization and AI provider-spend controls are reportable. |
| Public Eleventy site and Vercel route renderer | Public web rendering and analytics | Reported | Stored XSS and unbounded route-event integrity issues validated. |
| Supabase Edge Functions and external providers | Public endpoints and privileged backends | Reported | Ranking integrity, RSVP/promotion allocation, AI spend, and page-load integrity issues validated. |
| Android, iOS, and shared Kotlin mobile clients | Mobile sessions and local account separation | Reported | OAuth fixation, account-switch cache, push cleanup, and avatar allocation issues validated. |
| CI, production cutover, release, and operator tooling | Supply chain and deployment safety | Reported | Credential-bearing inherited-PATH interpreter use validated. |
| Repository credentials and public/private configuration | Secret exposure | No issue found | No embedded private credential or key was established; public client identifiers and synthetic fixtures were distinguished. |
| Public Edge request-body pre-buffering candidate | Availability | Rejected | Handlers materialize bodies before local byte checks, but repository source does not establish the upstream runtime cap or a concrete availability threshold; retained as hardening, not a finding. |
| Legacy archive/restore local toolchain candidate | Operator tooling | Rejected | Replaceable operator-owned executables are visible, but no attacker boundary or incremental capability beyond same-user local code execution was established. |
| Gallery profile bundle approval-binding candidate | Operator tooling | Rejected | The bundle is weakly bound to the reviewed manifest, but source does not establish an attacker who can replace the operator-selected local bundle without equivalent operator compromise. |
| CI release and production-source policy candidate | CI authorization | Rejected | Repository workflows omit some explicit gates, but deployed collaborator roles, branch protection, protected environments, and required-status configuration are unavailable, so a concrete unauthorized release or merge path was not established. |

## Open Questions And Follow Up

- Live feature-flag values, ingress header rewriting, upstream request-body limits, deployed migration state, and GitHub branch/environment protections were not established by repository source.
- Findings describe the registered repository snapshot; production exposure depends on the corresponding code and migrations being deployed.
