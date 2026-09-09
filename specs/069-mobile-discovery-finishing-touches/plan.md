# Plan: Mobile discovery finishing touches

## Technical approach

- Put coordinate validation and the provider-neutral destination value in `shared/commonMain` with
  common tests.
- Define an injected `ExternalMapLauncher` contract in `composeApp/commonMain`; keep Android and iOS
  implementations as thin native adapters. Wire it at the existing composition root and pass an
  `onOpenMap` callback to the detail screen.
- Derive localized badge descriptors from `Exhibition` in shared code, test the derivation, and use
  one reusable square, monochrome Compose badge row in cards and detail.
- Re-run focused My Gallr/Profile tests to prove the visit-history backlog item is already complete.

## Constitution check

- **Spec-First:** `spec.md` defines independently testable acceptance criteria before code changes.
- **Test-First:** shared destination validation and badge derivation receive failing tests before
  implementation. Native wrappers remain thin platform code.
- **Simplicity:** no provider chooser, routing SDK, new dependency, schema change, or ViewModel is
  introduced.
- **Incremental delivery:** native map handoff, badges, and visit-history verification are separate
  stories.
- **Observability:** only stable failure operation names are logged.
- **Shared-First:** validation and curation decisions live in `shared/commonMain`; shared UI and
  orchestration live in `composeApp/commonMain`; platform code only launches a URI/intent.

## Complexity tracking

The injected launcher adds one interface because native Android and iOS URL/intent APIs differ and
must be testable without placing platform decisions in shared UI. No additional abstraction is
planned.

## Verification

1. Focused shared tests for map destinations and badge descriptors.
2. Focused Compose tests for callback visibility/wiring where the existing test surface permits.
3. `./gradlew shared:ktlintCheck shared:allTests`.
4. `./gradlew composeApp:ktlintCheck composeApp:allTests`.
5. Android lint/assemble and iOS simulator framework link per `CLAUDE.md`.
6. Manual Android/iOS handoff smoke check remains explicit if simulators cannot prove installed-app
   dispatch.
