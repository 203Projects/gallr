<!--
## Sync Impact Report

**Version**: 1.1.2 → 1.2.0 (MINOR — added Principle VII, Mobile-First Product; the owner's
2026-10-08 direction: mobile first, routes created in the app only, the public web for
presentation with two approved web flows)

### Modified Principles
- None redefined. Added VII. Mobile-First Product (NON-NEGOTIABLE).

### Updated Project Context
- `Platform Targets`: the public web row now reads as presentation only.

### Added Sections
- `VII. Mobile-First Product`.

### Removed Sections
- None.

### Templates Reviewed
- ✅ `.specify/templates/plan-template.md` — Constitution Check gates are derived from this file;
  Principle VII becomes a gate for consumer features. No template text change required.
- ✅ `.specify/templates/spec-template.md` — Generic; consumer specs state their mobile path
  under VII. No template change required.
- ✅ `.specify/templates/tasks-template.md` — No change required; mobile tasks are part of a
  consumer story's own phase.
- ✅ `CLAUDE.md` and `web/AGENTS.md` — updated to point to Principle VII.

### Follow-up TODOs
- None. Individual exhibition submission and launch-event RSVP are recorded in VII as approved
  web flows.
-->

# gallr Constitution

## About gallr

gallr is a cross-platform exhibitions discovery app. It allows users to see ongoing and
upcoming art and cultural exhibitions in their city, bookmark exhibitions of interest, and
filter by region, featured picks, editor's picks, opening this week, and closing this week.

The product ships as:
- **Android app** and **iOS app** — built from a single Kotlin Multiplatform (KMP)
  codebase.
- **Public web** — an Eleventy static marketing and exhibition-catalog site.
- **Staff Admin** and **gallery-owner workspace** — separate React/Vite applications using
  role-scoped Supabase contracts.
- **Supabase backend** — Postgres, Auth, Storage, and Edge Functions. It is the canonical data
  system; the former Google Sheets/Apps Script writer is retired. Legacy import tooling may retain
  historical compatibility diagnostics but MUST NOT become an active writer.

## Platform Targets

| Target       | Tech                         | Notes                              |
|--------------|------------------------------|------------------------------------|
| Android      | Kotlin Multiplatform + Compose Multiplatform | Primary delivery platform  |
| iOS          | Kotlin Multiplatform + Compose Multiplatform (or SwiftUI interop) | Primary delivery platform |
| Web (public) | Eleventy static build | Presentation (Principle VII): marketing, catalog, map, shared route page, owner handoff; approved flows: individual submission, RSVP |
| Web (staff) | React + Vite | Staff-only editorial Admin |
| Web (gallery owner) | React + Vite | Owner-scoped publishing workspace |
| Backend | Supabase | Postgres, Auth, Storage, and Edge Functions |

## Core Principles

### I. Spec-First Development

Every feature MUST begin with a written specification (`spec.md`) before any code is
written. Specifications MUST define user stories with explicit acceptance criteria before
planning or implementation starts. Ad-hoc coding that bypasses the spec → plan → tasks
workflow is not permitted.

**Rationale**: Prevents scope creep, ensures shared understanding of requirements, and
avoids wasted implementation effort on misunderstood problems.

### II. Test-First (NON-NEGOTIABLE)

When tests are included in a feature, they MUST be written and verified to fail before
implementation begins. The Red-Green-Refactor cycle is mandatory. Implementation MUST NOT
begin on any tested path until at least one failing test exists for that path. Shared KMP
module logic MUST have unit tests; platform-specific UI layers are exempt from this
requirement unless otherwise specified.

**Rationale**: Catches regressions early, forces testable design, and provides executable
specifications that document intended behavior precisely.

### III. Simplicity & YAGNI

Solutions MUST start at the simplest viable implementation. Complexity MUST be justified
by present, concrete requirements — never by hypothetical future needs. Every additional
abstraction, pattern, or dependency MUST earn its inclusion by solving a real, current
problem. Violations MUST be tracked in the Complexity Tracking table in `plan.md`.

**Rationale**: Complex code costs more to read, maintain, and debug than simple code.
The minimum design that satisfies today's requirements is almost always correct.

### IV. Incremental Delivery

Features MUST be decomposed into independently deliverable user stories. Each story MUST
be independently testable, demonstrable, and deployable without depending on incomplete
later stories. A story is not complete until it can be validated in isolation.

**Rationale**: Delivers value faster, surfaces integration problems earlier, and enables
meaningful stakeholder feedback at every increment.

### V. Observability

All significant operations MUST emit structured, machine-readable log output. Errors MUST
include sufficient context (input values, system state, operation attempted) to reproduce
and diagnose without attaching a debugger. Silent failures are not acceptable. Mobile
releases MUST integrate a crash-reporting mechanism before shipping to production.

**Rationale**: Systems that cannot be observed in production cannot be reliably maintained.
Observability is a design requirement, not a post-shipping concern.

### VI. Shared-First Architecture (NON-NEGOTIABLE for KMP)

All business logic, domain models, repository interfaces, and networking MUST live in
`shared/src/commonMain`. Shared Compose UI and ViewModel orchestration live in
`composeApp/src/commonMain`; reusable business decisions MUST NOT live there. Native APIs with no
KMP equivalent are permitted only in `composeApp/src/androidMain`, `composeApp/src/iosMain`, or the
minimal Swift host in `iosApp/`, and MUST remain thin adapters. The web applications are separate,
independent artifacts with no shared-module dependency.

**Rationale**: The core value of KMP is a single source of truth for logic across
platforms. Leaking business logic into platform modules destroys that value, doubles
maintenance cost, and introduces platform divergence bugs.

### VII. Mobile-First Product (NON-NEGOTIABLE)

gallr is a mobile product. Consumer features are built in the Android and iOS apps and are
complete only when they work there. The public web (`web/` and the root `api/` functions) is for
presentation: it shows published and shared content read-only to people without the app and
points them to it. A consumer capability that exists only on the web is not complete, and specs,
plans and release notes MUST NOT describe it as delivered.

- **Routes are created in the app only.** Composing, editing, saving, listing and copying routes
  happen in the app: a route serves the person planning their next outing and gives other users
  insight from routes people made. The web shows an individual shared route read-only at
  `/route/{id}`; popular routes appear in the app only.
- **Approved web flows.** Individual exhibition submission (`web/submit/individual/`) and
  launch-event RSVP (`web/rsvp/`) stay on the web by owner decision (2026-10-08). Any other web
  flow that creates or changes content needs an explicit owner decision recorded here.
- Every consumer feature spec MUST define its app path; the plan's Constitution Check MUST
  confirm it and that any web surface is read-only unless listed above.
- Staff Admin and the gallery-owner workspace are operator tools, not consumer surfaces; this
  principle does not apply to them.

**Rationale**: The app is where gallr's users discover, plan and act, and where the community
loop around routes forms. Building consumer capability on the web splits the product and
bypasses that loop; the web's job is to present what app users made.

## Quality Standards

- All automated tests MUST pass before a branch is merged.
- Breaking changes to public interfaces MUST be documented with a migration guide before
  the change ships.
- External dependencies MUST be evaluated for KMP compatibility (not just JVM/Android)
  before adoption.
- All public interfaces (repository interfaces, API contracts, exported types) MUST be
  documented at the point of definition.
- Platform-specific implementations of shared interfaces MUST be kept as thin wrappers
  with no business logic.

## Development Workflow

- All work follows the speckit lifecycle:
  `/speckit.specify` → `/speckit.plan` → `/speckit.tasks` → `/speckit.implement`
- Each feature branch requires a passing Constitution Check in `plan.md` before Phase 0
  research begins, and again after Phase 1 design.
- The Constitution Check MUST explicitly verify Principle VI (Shared-First): confirm that proposed
  code placement puts reusable logic in `shared/commonMain`, shared UI/orchestration in
  `composeApp/commonMain`, and only thin native adapters in platform source sets.
- Complexity violations MUST be logged in the `plan.md` Complexity Tracking table with
  rationale and rejected simpler alternatives.
- PRs MUST include at least one independently testable user story with evidence of
  passing tests before requesting review.
- The constitution supersedes all other project guidance documents when conflicts arise.

## Governance

This constitution supersedes all other practices and guidelines in the gallr project.
Amendments require: (1) documented rationale for the change, (2) a version bump per the
policy below, and (3) propagation of impacts to dependent templates via the Sync Impact
Report.

**Versioning policy**:
- **MAJOR** bump: removing or fundamentally redefining an existing principle.
- **MINOR** bump: adding a new principle or materially expanding guidance.
- **PATCH** bump: clarifications, wording improvements, typo fixes with no semantic change.

All feature implementations MUST pass the Constitution Check in `plan.md` before
implementation begins. Deviations require written justification in the Complexity Tracking
table and explicit approval before proceeding.

**Version**: 1.2.0 | **Ratified**: 2026-03-18 | **Last Amended**: 2026-10-08
