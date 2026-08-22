# Implementation Plan: Free Gallery Launch Beta

## Architecture

Port the useful free-activation idea from the stale R2-R4 branch onto current
`develop`; do not merge the stale branch or reuse its historical migration ID.
Add one new chronological migration that removes callable payment activation,
preserves historical payment fields, and adds an owner-authorized
`owner_activate_launch_kit` command.

Add a provider-independent entitlement-source enum to the existing Kit. Backfill
the previously payment-constrained active rows as `paid`; set new activations to
`free_beta`. Reassert R4's paid-only invariant inside both the owner request RPC
and the service selection query rather than trusting frontend flags.

Keep the established Launch Kit, RSVP, guest, and check-in data model. Replace
the Gallery checkout adapter with the direct activation RPC. Derive one RSVP URL
from the environment-matched public-site base and use it for the visible link,
copy/share actions, and the dynamically imported zero-dependency `uqr` encoder.
Generate a scalable SVG in the browser; do not persist it.

Extend the service-only public Launch Kit projection with the same presentation
fields already available on the canonical public exhibition: published cover,
bilingual descriptions, exhibition dates, hours, and contact. The Edge handler
passes that bounded JSON contract through without exposing owner/payment/review
or guest data. The progressive RSVP page renders an image-led editorial hero,
an essential-information grid, description, and the existing form; optional
empty fields collapse and image failure falls back to the text-only layout.

Add separate default-off R4 capability flags to Gallery owner requests, Admin
review, the server endpoint, and mobile delivery; public web already has its own
presentation flag. When a surface is false, do not invoke promotion data or
render promotion controls. R2 impact, R3 owner, R3 public RSVP, and every R4
surface remain independent.

Remove the unused checkout/webhook Edge Function packages and configuration.
Keep the RSVP Edge Function and its privacy/rate-limit contract unchanged except
for documentation and verification required by the free-beta product wording.

## Constitution check — before implementation

- **I Spec-first:** PASS — this spec, plan, ADR, and task list precede code.
- **II Test-first:** PASS — new pgTAP, repository, component, QR, and release-
  control tests will be observed failing before implementation.
- **III Simplicity/YAGNI:** PASS — one explicit free activation command, no new
  entitlement table/tier, no server QR storage, and no speculative payment layer.
- **IV Incremental delivery:** PASS — database activation, owner UI/QR, public
  RSVP, and R4 isolation each have isolated tests and default-off release gates.
- **V Observability:** PASS — activation uses the existing audit/command-request
  records; guest or token data is never logged.
- **VI Shared-first:** PASS — no KMP business logic changes are needed for R3;
  Gallery and public web remain independent artifacts as permitted.

## Data and permission changes

- Drop the historical constraint that requires payment proof for every active
  Kit, but retain nullable Stripe columns and recorded webhook rows.
- Add `free_beta | paid` entitlement source, backfill existing active rows as
  paid, require a source plus activation timestamp for active Kits, and require
  payment evidence when the source is paid.
- Drop public/private checkout preparation, checkout attachment, and payment
  activation functions.
- Add private `owner_activate_launch_kit_impl(text, uuid)` plus a narrow public
  security-invoker wrapper.
- Reuse `command_requests`, owner membership assertions, one-Kit-per-exhibition
  uniqueness, `owner_launch_kit_json`, and audit logging.
- Replace the R4 owner-request and service-selection functions so only paid Kits
  can create or deliver a paid placement.
- Explicitly revoke default execution and grant only the wrapper/implementation
  permissions required by authenticated owners.

## Frontend changes

- Replace `startLaunchCheckout`/`LaunchCheckoutResult` with
  `activateLaunchKit(): Promise<LaunchKit>`.
- Remove checkout-return URL state and payment-pending copy.
- Pass `publicSiteUrl` through the Launch Kit route and derive the RSVP URL once.
- Add a multi-Kit exhibition selector that resets guest query/pagination on
  change and keeps updates associated with the selected Kit.
- Add copy/open/download QR actions with accessible busy/error feedback.
- Dynamically import pinned `uqr` only when the owner requests a QR.
- Gate pending claimants out of R3 even when the environment flag is enabled.
- Gate owner, Admin, server, public, and mobile promotion surfaces independently.

## Verification sequence

1. Run network-free lineage checks.
2. Add failing pgTAP tests for free activation, replay, denial, old RPC removal,
   grants, and retained historical fields.
3. Generate a migration with the installed Supabase CLI and implement it.
4. Add failing Gallery repository/component/QR/release-control tests, then
   implement the owner flow.
5. Update/remove Edge packages and run every remaining function's test/check.
6. Run focused then full Gallery and web tests, typechecks, builds, accessibility,
   and existing Playwright suites.
7. Clean-replay the database and run the full pgTAP/lint/security sequence.
8. Render the target flow at desktop and mobile widths: activate Kit → open RSVP
   link/QR → submit RSVP → see guest → check in → verify repeated check-in.

## Complexity tracking

| Decision | Added complexity | Why justified | Simpler alternative rejected |
| --- | --- | --- | --- |
| Additive activation migration | One new RPC, entitlement source, R4 guards, and migration | Preserves immutable production lineage, owner authorization, and paid-only R4 | Client-side status toggle or backdated stale migration |
| On-demand QR dependency | One dynamically loaded, pinned zero-dependency package | Standards-compliant QR encoding is interop-sensitive | Hand-written QR encoder or remote QR service |
| Separate R4 surface flags | Small configuration seams across existing consumers | Prevents R3 activation from leaking an unapproved later slice | Hiding only the owner button while other R4 clients still call the endpoint |

## Constitution check — after design

PASS. The design reuses the canonical schema and command patterns, adds no
parallel source of truth, introduces no mobile platform logic, and keeps every
later release slice disabled by default.
