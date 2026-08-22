# Implementation Plan: Unified Self-Service Signup

**Branch:** `shin/068-unified-self-service-signup` | **Date:** 2026-08-22 | **Spec:** [spec.md](./spec.md)

## Summary

Align the checked-in Supabase Auth contract with the shared account-plane
architecture: allow self-service identity creation while retaining all
privileged access in server-owned memberships. Add bounded OAuth callback error
parsing to Gallery, signup-disabled classification to Gallr mobile, and release
documentation/tests that keep hosted production enablement as a final explicit
operation.

## Technical context

**Language/Version:** Kotlin 2.3.10, TypeScript 7, Python 3, TOML
**Primary Dependencies:** supabase-kt, `@supabase/supabase-js` 2.112.3, React 19
**Storage:** Existing Supabase Auth/Postgres; no new table or migration
**Testing:** kotlin-test, Vitest, Python unittest, product-surface CI
**Target Platform:** Android, iOS, Gallery web, hosted Supabase Auth
**Project Type:** KMP mobile plus independent React portal
**Constraints:** One Auth project per environment; no client-assigned roles;
exact redirects; no secret keys in clients; bilingual error copy
**Scale/Scope:** Signup configuration and failure UX across consumer and Gallery
surfaces; Editor/Admin authorization remains unchanged

## Constitution check — before implementation

- **I Spec-first:** PASS — spec, plan, and tasks precede tests and code.
- **II Test-first:** PASS — Gallery callback, mobile classification, and config
  validator tests will be observed failing before implementation.
- **III Simplicity/YAGNI:** PASS — reuse the shared Auth plane and membership
  model; add no gateway, role claim, or second identity store.
- **IV Incremental delivery:** PASS — callback UX, mobile UX, and hosted signup
  enablement remain independently verifiable; production config is last.
- **V Observability:** PASS — stable error categories only; no personal data or
  provider payloads are added to logs.
- **VI Shared-first:** PASS — mobile classification stays in shared/application
  layers; Gallery remains an independent web artifact; no platform duplication.

## Architecture

```text
Email / Google / Apple
          |
          v
shared environment auth.users
          |
          +--> consumer profile (RLS-scoped)
          +--> Gallery claim onboarding --> staff approval --> owner access
          +--> editor membership required --> Editor access
          +--> staff membership required  --> Admin access
```

The global Auth signup gate must be on for self-service consumer or Gallery
identity creation because all surfaces share the project. Security remains at
the database membership/RLS boundary, not at the choice of client that started
signup.

## Source changes

```text
shared/src/commonMain/.../repository/AuthRepositoryImpl.kt
composeApp/src/commonMain/.../viewmodel/SignInViewModel.kt
composeApp/src/commonMain/.../ui/profile/SignInScreen.kt
composeApp/src/commonTest/.../SignInViewModelTest.kt
gallery/src/domain.ts
gallery/src/auth/SupabaseOwnerAuth.ts
gallery/src/auth/SupabaseOwnerAuth.test.ts
gallery/src/components/OwnerApp.tsx
gallery/src/components/OwnerApp.test.tsx
gallery/src/i18n.tsx
supabase/config.toml
scripts/product-surfaces/validate-config.py
scripts/product-surfaces/validate-config.test.py
docs/account-identity-and-access.md
docs/gallery-owner-release-runbook.md
gallery/README.md
```

## Verification sequence

1. Add failing Gallery tests for query/fragment callback errors, URL cleanup,
   signup-disabled copy, and generic OAuth callback copy.
2. Add failing Gallr ViewModel tests for signup-disabled email/OAuth
   classification and localized message coverage.
3. Add failing configuration tests requiring global signup plus email
   confirmation while SMS/anonymous signup stays off.
4. Implement the smallest code/config/documentation changes.
5. Run focused Gallery, KMP, and configuration tests, then the full Gallery and
   Android/shared verification contracts.
6. Exercise new email/OAuth identities in staging and prove no privileged
   memberships exist.
7. After reviewed code promotion and explicit approval, enable/read back hosted
   production signup and run one disposable end-to-end identity test.

## Complexity tracking

No constitution violations. The only new abstraction is a small bounded OAuth
callback error category on the existing `OwnerAuth` interface, justified because
the Auth adapter must not leak URL/provider payload details into React UI.

## Constitution check — after design

PASS. The design keeps one identity source of truth, retains all authorization
in existing server relations, adds no migration, and leaves hosted production
mutation outside the code implementation step.
