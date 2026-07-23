-- Migration 019 — Fix v1.5.x compat shim null handling
-- The previous generated column `(editor_id = 'gallr-editors')` returns NULL
-- when editor_id IS NULL (SQL three-valued logic). v1.5.1's ExhibitionDto
-- requires is_editors_pick to be non-null Boolean — null breaks deserialization.
-- Wrap with COALESCE so untagged rows return false.

alter table exhibitions drop column is_editors_pick;

alter table exhibitions
  add column is_editors_pick boolean generated always as (
    coalesce(editor_id = 'gallr-editors', false)
  ) stored;
