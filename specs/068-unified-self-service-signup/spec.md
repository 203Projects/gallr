# Feature Specification: Unified Self-Service Signup

**Feature Branch:** `shin/068-unified-self-service-signup`
**Created:** 2026-08-22
**Status:** Ready for Review
**Input:** Enable real first-time email and OAuth signup for Gallr and Gallery
without granting gallery, editor, or staff privileges at identity creation.

## Product boundary

Gallr mobile, Gallery, Editor, and Admin share one Supabase Auth identity plane
per environment. Self-service signup creates only an authenticated consumer
identity. Gallery ownership, editor access, and staff access remain separate,
server-owned memberships and must never be inferred from the signup surface,
OAuth provider, profile metadata, or email domain.

## User scenarios & testing

### User Story 1 — Create one Gallr identity (Priority: P1)

As a new visitor, I can create a production Gallr identity through supported
email, Google, or Apple flows and use the same identity on every Gallr surface.

**Why this priority:** First-time signup is currently blocked by the global Auth
gate even though Gallr and Gallery render signup-capable controls.

**Independent Test:** With a previously unused identity, complete each supported
provider flow in staging and verify exactly one Auth user is created without a
privileged membership.

**Acceptance Scenarios:**

1. **Given** an unused email, **when** the visitor completes Gallr email signup,
   **then** one consumer identity is created and verification requirements are
   explained accurately.
2. **Given** an unused verified Google or Apple identity, **when** the visitor
   completes OAuth, **then** one consumer identity is created and the client
   returns to the originating environment.
3. **Given** an existing identity, **when** the same provider is used again,
   **then** the existing account signs in instead of creating a duplicate.

---

### User Story 2 — Register for Gallery without receiving ownership (Priority: P1)

As a new gallery representative, I can create the same Gallr identity from the
Gallery portal and then enter gallery claim onboarding, while gallery submission
and publication remain unavailable until staff approves the claim.

**Why this priority:** Public identity creation must not weaken the gallery
verification boundary.

**Independent Test:** Create a new Gallery identity, verify no owner/editor/staff
membership exists, create a pending claim, and prove active-owner RPCs still
deny the account.

**Acceptance Scenarios:**

1. **Given** a new Gallery identity, **when** OAuth or email authentication
   completes, **then** the portal shows gallery search/create onboarding.
2. **Given** that identity has no approved claim, **when** it attempts an
   active-owner operation, **then** the server denies it.
3. **Given** an Auth identity has no editor or staff membership, **when** it opens
   Editor or Admin, **then** those portals remain fail-closed.

---

### User Story 3 — Understand signup and OAuth failures (Priority: P2)

As a person attempting signup, I see an actionable bilingual error instead of
being silently returned to sign-in when signup is disabled, OAuth is rejected,
or the callback cannot create a session.

**Why this priority:** Silent callback failures look like a broken button and
make operator configuration mistakes difficult to diagnose.

**Independent Test:** Return Gallery and Gallr clients from OAuth with bounded
error codes and verify the correct message appears without exposing provider
payloads, tokens, email addresses, or raw upstream errors.

**Acceptance Scenarios:**

1. **Given** Auth signup is disabled, **when** a first-time OAuth callback
   returns, **then** the user sees that account creation is temporarily
   unavailable and can retry later.
2. **Given** an unrelated OAuth callback failure, **when** the portal returns,
   **then** the user sees that Google sign-in could not be completed.
3. **Given** callback error parameters were consumed, **when** the sign-in page
   remains open, **then** sensitive/noisy Auth parameters are removed from the
   visible URL without removing unrelated navigation state.

### Edge cases

- The same verified email is used with email and OAuth providers.
- OAuth is cancelled or returns without a session.
- A stale callback URL is reloaded.
- Signup succeeds immediately because email confirmation is disabled in a
  non-production environment.
- A newly created consumer identity opens Gallery, Editor, or Admin.
- Rate limits, CAPTCHA, SMTP delivery, or redirect validation rejects signup.
- Staging and production redirect URLs or project references are mixed.

## Requirements

### Functional requirements

- **FR-001:** The shared Auth environment MUST permit approved self-service
  email and OAuth identity creation when its release gate is enabled.
- **FR-002:** Signup MUST create no gallery, editor, or staff membership.
- **FR-003:** Gallery MUST route a new authenticated identity to claim onboarding.
- **FR-004:** Gallery MUST parse bounded OAuth callback error codes from query or
  fragment parameters, show bilingual actionable copy, and remove consumed Auth
  parameters from the URL.
- **FR-005:** Gallr mobile MUST classify signup-disabled failures separately from
  invalid credentials, duplicate email, rate limits, and generic failures.
- **FR-006:** Password-based email signup MUST require verified email in
  production, or the client MUST accurately handle an immediate session.
- **FR-007:** Email auth MUST use the environment's reviewed custom SMTP,
  confirmation/OTP expiry, and rate-limit configuration before production
  enablement.
- **FR-008:** OAuth and email redirect allow-lists MUST use exact environment
  origins/deep links without preview wildcards or cross-environment fallback.
- **FR-009:** Auth errors and logs MUST exclude tokens, raw provider payloads,
  email addresses, and other personal data.
- **FR-010:** The production signup configuration change MUST remain a separate,
  read-back-verified rollout after code, staging, privacy, and support gates pass.

### Key entities

- **Auth identity:** One environment-scoped `auth.users` row and its verified
  provider identities.
- **Consumer profile:** RLS-protected consumer data keyed by `auth.users.id`.
- **Gallery membership:** Server-owned pending/active/rejected/suspended/revoked
  authorization independent of provider and signup surface.
- **Editor/staff membership:** Invite-only authorization relations independent
  of consumer or gallery identity creation.

## Success criteria

- **SC-001:** New email, Google, and Apple staging identities complete signup and
  return to the correct client with one Auth user each.
- **SC-002:** Zero new-user tests receive gallery, editor, or staff privileges
  before the matching server-owned membership workflow.
- **SC-003:** Signup-disabled and OAuth callback tests display the intended
  bilingual error on Gallr and Gallery with no silent sign-in loop.
- **SC-004:** Exact redirect, SMTP, confirmation/OTP, rate-limit, and environment
  parity gates pass before hosted production signup is enabled.

## Out of scope

- Automatically approving a gallery claim or provisioning editor/staff access.
- A second Auth project for Gallery or role-specific accounts.
- SMS signup, anonymous accounts, passkeys, or MFA enrollment UI.
- Production Auth configuration mutation before the reviewed rollout gate.
