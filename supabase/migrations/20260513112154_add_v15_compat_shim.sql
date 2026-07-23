-- Migration 018 — v1.5.x backwards-compat shim
-- v1.5.1's ExhibitionDto requires is_editors_pick (non-null Boolean) and
-- guest_editor_id (nullable String). v1.6.0 (PR #59) reads editor_id instead.
-- These GENERATED columns let v1.5.1 keep parsing while v1.6.0 store review
-- completes. They're computed from editor_id on every query, so they stay
-- in sync automatically with zero sync-pipeline involvement.
--
-- Drop these in a follow-up migration once v1.5.x install share approaches 0%
-- (visible in Play Console / App Store Connect analytics).

alter table exhibitions
  add column is_editors_pick boolean generated always as (editor_id = 'gallr-editors') stored;

alter table exhibitions
  add column guest_editor_id text generated always as (
    case when editor_id = 'gallr-editors' then null else editor_id end
  ) stored;
