<!--
## Sync Impact Report

**Version**: 1.1.1 → 1.1.2 (PATCH — corrected the current backend and retired-writer status;
no principle was removed or redefined)

### Modified Principles
- None.

### Updated Project Context
- `About gallr` now identifies Supabase as the canonical backend and records Google Sheets/Apps
  Script as retired. Historical import diagnostics remain non-authoritative compatibility tooling.

### Added Sections
- None.

### Removed Sections
- None

### Templates Reviewed
- ✅ `.specify/templates/plan-template.md` — Technical Context fields (Language/Version,
  Target Platform, Project Type) will capture KMP specifics per-feature. No template
  changes required.
- ✅ `.specify/templates/spec-template.md` — Generic; no conflicts.
- ✅ `.specify/templates/tasks-template.md` — Path conventions use the repository structure
  (`shared/`, `composeApp/`, `iosApp/`, `web/`, `admin/`, `gallery/`) declared here. No template
  changes required; plan.md will define paths per-feature.
- ✅ `.specify/templates/checklist-template.md` — Generic; no conflicts.
- ✅ `.claude/commands/speckit.constitution.md` — No outdated references.

### Follow-up TODOs
- None. All placeholders resolved.
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
| Web (public) | Eleventy static build | Marketing, catalog, map, and owner handoff |
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

**Version**: 1.1.2 | **Ratified**: 2026-03-18 | **Last Amended**: 2026-08-11
