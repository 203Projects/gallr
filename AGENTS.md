# gallr

**See [`CLAUDE.md`](./CLAUDE.md) for the canonical agent guide** — architecture, build/test
commands, KMP conventions, and subsystem gotchas.

gallr is a Kotlin Multiplatform + Compose Multiplatform mobile app (Android + iOS) for discovering
art exhibitions in Seoul, plus an Eleventy web companion, a Google Apps Script sync pipeline, and a
Supabase backend. When working inside `web/` or `gas/`, read the nearest `AGENTS.md` in that subtree.

The one rule to never skip: **read `DESIGN.md` before any UI change** (brutally minimal monochrome,
0dp corners, single `#FF5400` accent, Inter + Gothic A1 on an 8pt grid).

<!--
  This file is intentionally a pointer to CLAUDE.md to avoid maintaining two copies that drift.
  Spec-kit (`.specify/scripts/bash/update-agent-context.sh`) appends to `## Recent Changes` below
  when run; the canonical guidance lives in CLAUDE.md.
-->

## Recent Changes
- 043-android-editor-screen-fix: Editor selector + detail screens wrapped in `Scaffold` with `WindowInsets.safeDrawing`; `EditorTopBar` rewritten as a Material3 `TopAppBar`. Android-only chrome fix, no behavior/data changes.
- 041-editor-hub: Migration `20260513110749` unifies `guest_editors` → `editors`; `editor_id` FK replaces the legacy fields, whose generated compatibility aliases are retained by the recorded May lineage (v1.6.0).
- 040-guest-editor: `guest_editors` table + FK; GuestEditor shared slice + banner/chip in ListScreen (v1.5.0).
