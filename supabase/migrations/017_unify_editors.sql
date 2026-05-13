-- Migration 017 — Unify editors (spec 041)
-- Rename guest_editors → editors. Seed the hardcoded gallr-editors row.
-- Replace exhibitions.is_editors_pick + guest_editor_id with a single editor_id FK.
--
-- ROLLBACK: Restore from backup. This migration drops two columns; reverting
-- the migration restores them as NULL — the original boolean/FK data is lost
-- unless restored from backup. Take a backup before applying.

alter table guest_editors rename to editors;

alter index guest_editors_active_idx rename to editors_active_idx;
alter policy "active guest_editors are readable by anyone" on editors
  rename to "active editors are readable by anyone";

-- Hardcoded gallr-editors seed row. Always active, immutable identity.
insert into editors (
  id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
  is_active, active_from, active_to
) values (
  'gallr-editors',
  'gallr 에디터즈', 'gallr Editors',
  '하우스 에디터', 'House Editor',
  'gallr 팀이 선정한 상시 큐레이션.', 'Always-on selection by the gallr team.',
  true, '2026-01-01', null
);

-- New unified FK column on exhibitions.
alter table exhibitions
  add column editor_id text references editors(id) on delete set null;

-- Backfill: existing isEditorsPick=true rows point at the seed row.
update exhibitions set editor_id = 'gallr-editors' where is_editors_pick = true;

-- Backfill: existing guest_editor_id rows migrate to editor_id.
-- If a row had both is_editors_pick=true AND guest_editor_id, this overwrites
-- the gallr-editors assignment with the more specific guest editor — correct,
-- since tagging a specific guest is more deliberate than the team-pick flag.
update exhibitions set editor_id = guest_editor_id where guest_editor_id is not null;

-- Drop the two redundant columns.
alter table exhibitions drop column is_editors_pick;
alter table exhibitions drop column guest_editor_id;

-- Replace the partial index.
drop index if exists exhibitions_guest_editor_idx;
create index exhibitions_editor_idx
  on exhibitions (editor_id) where editor_id is not null;
