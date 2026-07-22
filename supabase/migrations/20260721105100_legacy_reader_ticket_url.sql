-- Restore a legacy reader column that existed in the catalog design and in
-- deployed environments but was never captured by a tracked migration.
-- The canonical content.exhibition_versions table already owns the validated
-- field; this keeps fresh local/CI public-reader schemas compatible until the
-- public projection cutover is complete.

alter table public.exhibitions
  add column if not exists ticket_url text;

comment on column public.exhibitions.ticket_url is
  'Legacy public reader ticket URL. Canonical writes validate this field in content.exhibition_versions.';
