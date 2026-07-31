# Implementation Plan: Gallery Owner Foundation

## Approach

Add the smallest durable organization and membership layer on top of the
existing versioned CMS. Keep organizations (`galleries`), reusable locations
(`venues`), and public exhibition snapshots distinct. Add authenticated owner
RPCs following the existing Admin pattern: SECURITY INVOKER wrappers call
independently authorizing `content_private` implementations with empty search
paths, narrow grants, deterministic validation, audit evidence, and durable
outbox events.

Create a separate `gallery/` React + TypeScript + Vite application. Its first
increment resolves the Supabase session, sends email OTPs, searches/creates
gallery claims, and renders the pending/active/suspended workspace shell. It
does not import staff Admin UI or privileged repository methods.

## Data model

- `content.galleries`: durable gallery identity, optional canonical venue,
  bilingual name, lifecycle status, merge target, and audit timestamps.
- `content.gallery_memberships`: gallery/user relationship, owner role, claim
  status/evidence, review metadata, and audit timestamps.
- `content.exhibitions.gallery_id`: nullable stable ownership link. Versioned
  venue fields remain public snapshots and are not derived at read time.

Partial indexes enforce the two current invariants without designing a team
system: one active owner per gallery and one pending/active workspace per user.
Foreign-key and RLS lookup columns receive matching indexes.

## Command surface

- `owner_current_access()`
- `owner_search_galleries(p_query)`
- `owner_claim_existing_gallery(p_gallery_id, ..., p_request_id)`
- `owner_create_gallery_claim(p_name_ko, p_name_en, ..., p_request_id)`

Claim commands take a transaction-scoped advisory lock for the actor, validate
the confirmed Auth email and allowlisted fields, then write gallery/membership,
audit, and outbox state in one short transaction. A unique outbox deduplication
key provides idempotent replay evidence without adding a second command table.

## Frontend structure

- `gallery/src/auth`: OTP/session boundary.
- `gallery/src/data`: typed owner repository and Supabase adapter.
- `gallery/src/components`: app shell, onboarding, empty dashboard, and access
  states.
- `gallery/src/styles.css`: tokens extracted from `DESIGN.md` and the approved
  owner-dashboard concept.

The accepted primary-screen reference is the generated owner empty dashboard
shown in this task. The existing Admin login concept remains the sign-in family
reference, adapted to email OTP and customer copy.

## Constitution check

- **Spec-first:** This specification, plan, and tasks precede tests and code.
- **Test-first:** A new pgTAP contract and React component tests will be added
  and observed failing before the migration and UI implementation.
- **Simplicity:** Four owner RPCs and one focused SPA solve the present access
  and claim workflow. No team, review, billing, or analytics abstractions land.
- **Incremental delivery:** Pending claim plus authenticated workspace shell is
  independently testable and becomes the base for the exhibition workflow.
- **Observability:** Claim commands emit structured audit and outbox records;
  frontend errors retain actionable operation context without logging evidence.
- **Shared-first:** Not applicable to this standalone React application and
  Supabase backend. No mobile model, networking, ViewModel, or business logic is
  changed.

## Security and privacy

- Authenticated browser roles receive EXECUTE only on public owner wrappers and
  the exact internal implementations those wrappers enter.
- Every implementation resolves `auth.uid()` itself and does not trust a user
  identifier from JSON input.
- RLS is enabled on galleries and memberships; no owner table policies or
  generic DML grants are introduced.
- Claim evidence remains private and is excluded from search/current-access
  DTOs and outbox payloads.
- Existing Admin, service-role intake, public readers, curation, and media
  privileges are unchanged.

## Verification

1. Observe the new pgTAP file fail before the migration exists.
2. Apply/replay the migration locally and run the focused pgTAP contract.
3. Observe owner component/repository tests fail before UI implementation.
4. Run gallery tests, typecheck, and production build.
5. Run the migration-lineage validator and relevant existing database tests.
6. Exercise signed-out, onboarding, pending, active, and suspended states in a
   browser at desktop and mobile sizes.
7. Compare the rendered desktop dashboard with the generated concept using
   `view_image` and record a fidelity ledger.

## Complexity tracking

| Decision | Added complexity | Why it is justified | Simpler alternative rejected |
|---|---|---|---|
| Separate owner SPA | One deployable frontend | Prevents customer code and staff privilege/UI coupling | Role-switching inside Admin |
| Gallery separate from venue | One organization table | Supports off-site exhibitions, branches, and historical snapshots | Treat venue as customer identity |
