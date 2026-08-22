# Implementation Plan: Bilingual Admin and Gallery portals

**Branch**: `shin/066-admin-gallery-i18n` | **Date**: 2026-08-22 | **Spec**: [spec.md](./spec.md)

## Summary

Add a small typed locale layer independently to the Admin and Gallery React/Vite applications. Each
portal resolves `ko`/`en` from origin-local persistence and browser language, exposes a consistent
language control at its outer shell, synchronizes the document language, and renders all
interface-owned copy and display formatting through locale-aware helpers. No localization package,
backend contract, authentication behavior, or deployable boundary changes.

## Technical Context

**Language/Version**: TypeScript 7, React 19, Node.js 22.23.1
**Primary Dependencies**: Existing React/Vite stacks only; no new runtime dependency
**Storage**: Origin-scoped `localStorage` preference; existing Supabase data is unchanged
**Testing**: Vitest 4, Testing Library, user-event, rendered Browser-plugin QA
**Target Platform**: Modern desktop and mobile web browsers
**Project Type**: Two independent React/Vite web applications
**Performance Goals**: Synchronous locale changes with no network request and negligible bundle cost
**Constraints**: Preserve in-flight and dirty UI state; support storage failure; no URL routing or
backend migration; maintain DESIGN.md typography, spacing, color, and 0px corners
**Scale/Scope**: All interface-owned copy in `admin/src` and `gallery/src`, plus representative
localized dates, statuses, counts, and bilingual record labels

## Constitution Check

### Before research

- **Spec-first**: PASS — `spec.md` defines prioritized, independently testable user stories and
  explicit acceptance criteria before source changes.
- **Test-first**: PASS — locale and representative portal tests will be added and observed failing
  before implementation in each deployable.
- **Simplicity/YAGNI**: PASS — two tiny deployable-local modules avoid a new package, runtime
  dependency, server preference, or routing system.
- **Incremental delivery**: PASS — Admin and Gallery are independently testable slices built on the
  same specified behavior.
- **Observability**: PASS — this feature adds no significant remote operation; existing error and
  request paths remain unchanged.
- **Shared-first**: PASS — Admin and Gallery are explicitly independent web artifacts with no KMP
  shared-module dependency; no reusable mobile business logic is introduced.

### After design

- **Spec-first**: PASS — design remains within the accepted `ko`/`en` scope.
- **Test-first**: PASS — tests cover pure locale behavior and representative rendered workflows.
- **Simplicity/YAGNI**: PASS — deployable-local context/helpers and typed dictionaries are the only
  new abstraction; duplication is limited to the independent app roots and avoids coupling builds.
- **Incremental delivery**: PASS — either portal implementation provides standalone bilingual value.
- **Observability**: PASS — unsupported/corrupt saved values fail safely to deterministic locale
  resolution; no failure is silently represented as a successful mutation.
- **Shared-first**: PASS — all source placement remains inside `admin/` and `gallery/`.

## Design Decisions

1. Resolve locale in this order: a supported saved value, Korean when the browser's primary locale
   begins with `ko`, then English. This preserves the current default for non-Korean browsers.
2. Keep preference origin-scoped. Cross-subdomain or account synchronization would require a backend
   contract and is outside the requested scope.
3. Use typed, deployable-local message dictionaries and a React context. Locale updates rerender the
   existing tree without remounting it, which preserves forms, selection, and in-flight state.
4. Put the switch in the outermost blocked/auth/shell presentation so it remains available before and
   after authentication. Use short `한국어` and `EN` labels to constrain layout impact.
5. Localize interface-owned fallbacks and known states. Do not machine-translate user or server text.
6. Select read-only bilingual entity copy with active-locale-first fallback; retain paired Korean and
   English fields wherever users author canonical bilingual content.
7. Format display timestamps with the selected locale and `Asia/Seoul`; parse calendar-only
   `YYYY-MM-DD` values without routing them through UTC. Keep native form and API values unchanged.

## Project Structure

```text
specs/066-admin-gallery-i18n/
├── spec.md
├── plan.md
└── tasks.md

admin/src/
├── i18n.tsx
├── i18n.test.tsx
├── App.tsx
├── App.test.tsx
└── components/

gallery/src/
├── i18n.tsx
├── i18n.test.tsx
├── App.tsx
├── App.test.tsx
└── components/
```

**Structure Decision**: Keep localization inside each existing deployable. The apps have independent
package graphs and origins, so a new root JavaScript workspace would create build coupling for a
small stable behavior.

## Verification

1. Add focused locale tests and representative Korean rendering tests; run each and record the
   expected pre-implementation failures.
2. Run focused Vitest files during each portal implementation.
3. Run `npm test`, `npm run typecheck`, and `npm run build` in both `admin/` and `gallery/`.
4. Start both Vite apps and use the in-app Browser plugin to verify page identity, meaningful content,
   console health, language switching/persistence, one target interaction, and desktop/mobile layout.
5. Run `git diff --check` and audit remaining interface-owned English literals.

## Complexity Tracking

No constitutional violations require justification.
