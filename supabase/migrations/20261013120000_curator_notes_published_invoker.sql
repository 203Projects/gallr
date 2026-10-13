-- Spec 090 follow-up: public.curator_notes_published ran with the view owner's rights (the Postgres default, which
-- the Supabase security advisor flags as a SECURITY DEFINER view). The rows now come from a security definer
-- function with a fixed search_path, and the view runs with the caller's rights over that function, so no reader
-- gains access to the private note tables and the view keeps its name and columns for the app.
begin;

create or replace function content_private.published_curator_notes()
returns table (
  exhibition_id text,
  version_number integer,
  hook_ko text,
  hook_en text,
  insights_ko jsonb,
  insights_en jsonb,
  tip_ko text,
  tip_en text,
  reviewer_name_ko text,
  reviewer_name_en text,
  approved_at timestamptz,
  closing_date date
)
language sql
stable
security definer
set search_path = ''
as $$
  select
    version.exhibition_id,
    version.version_number,
    version.hook_ko,
    version.hook_en,
    version.insights_ko,
    version.insights_en,
    version.tip_ko,
    version.tip_en,
    version.reviewer_name_ko,
    version.reviewer_name_en,
    version.approved_at,
    catalog.closing_date
  from content.curator_notes as note
  join content.curator_note_versions as version on version.id = note.approved_version_id
  join public.exhibition_catalog_v2 as catalog on catalog.id = note.exhibition_id
  where version.status = 'approved';
$$;
revoke all on function content_private.published_curator_notes() from public;
grant execute on function content_private.published_curator_notes() to anon, authenticated, service_role;
comment on function content_private.published_curator_notes() is
  'Approved curator notes of exhibitions in the public catalogue; the body of public.curator_notes_published.';

create or replace view public.curator_notes_published
with (security_invoker = true)
as
select
  exhibition_id,
  version_number,
  hook_ko,
  hook_en,
  insights_ko,
  insights_en,
  tip_ko,
  tip_en,
  reviewer_name_ko,
  reviewer_name_en,
  approved_at,
  closing_date
from content_private.published_curator_notes();

comment on view public.curator_notes_published is
  'Approved curator notes of exhibitions in the public catalogue; the only read path for visitors (spec 090). Runs with the caller''s rights over a security definer function.';

revoke all on public.curator_notes_published from public, anon, authenticated, service_role;
grant select on public.curator_notes_published to anon, authenticated, service_role;

commit;
