# Implementation Plan: Admin Editor Management

**Branch**: `058-admin-editor-management` | **Date**: 2026-08-13 | **Spec**: [spec.md](./spec.md)

## Summary

Extend the existing admin-only Editors workspace with an editor directory, revision-checked profile editing, and reversible access deactivation. Add narrow Supabase RPCs behind the existing `AdminEditorRepository`; do not expose direct table writes or add a new privileged Edge Function.

## Technical Context

**Language/Version**: TypeScript 7 / React 19; PostgreSQL 15-compatible Supabase SQL
**Primary Dependencies**: Vite, Supabase JS, Vitest, React Testing Library, pgTAP
**Storage**: Supabase Postgres (`public.editors`, `content.editor_memberships`, `content.audit_log`)
**Testing**: Vitest component/repository tests and pgTAP database contracts
**Target Platform**: Modern desktop/mobile web browsers and Supabase Postgres
**Project Type**: Existing staff web app plus backend command API
**Performance Goals**: One bounded editor-directory RPC; editor counts are editorial-team scale
**Constraints**: Admin-only, fail closed, no hard deletion, immutable slug/email, 0px corners, monochrome UI, 8px grid
**Scale/Scope**: One admin workspace, one repository, three RPC contracts, one migration

## Constitution Check

- **Spec-First**: PASS. Acceptance behavior is recorded in `spec.md` before implementation.
- **Test-First**: PASS by plan. UI, adapter, and pgTAP tests will be added and observed failing before implementation.
- **Simplicity/YAGNI**: PASS. Reuses the existing Editors workspace/repository and adds only list, update, and access-state commands.
- **Incremental Delivery**: PASS. Listing, editing, and deactivation are independently testable stories.
- **Observability**: PASS. Database mutations write actor-attributed, copy-free audit events; UI surfaces sanitized failures.
- **Shared-First**: PASS. This is an independent React/Supabase surface; no KMP logic is introduced or misplaced.

Post-design re-check: PASS. The data model preserves public editor identities and separates publication from portal membership; no platform-specific KMP code is involved.

## Project Structure

```text
admin/src/
├── components/EditorOnboardingWorkspace.tsx
├── components/EditorOnboardingWorkspace.test.tsx
└── repositories/
    ├── AdminEditorRepository.ts
    ├── InMemoryAdminEditorRepository.ts
    ├── SupabaseAdminEditorRepository.ts
    └── SupabaseAdminEditorRepository.test.ts
supabase/
├── migrations/20260813*_admin_editor_management.sql
└── tests/database/030_admin_editor_management.test.sql
```

**Structure Decision**: Extend the existing admin editor feature boundary and canonical migration lineage; no new package or service is warranted.

## Complexity Tracking

No constitution violations.
