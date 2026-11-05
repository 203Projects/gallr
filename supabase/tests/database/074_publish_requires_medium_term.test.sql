-- Spec 101 FR-019a (owner decision 2026-11-04): Admin's publish command refuses a version without a medium term,
-- keeps the existing refusals first, leaves nothing behind when it refuses, and accepts the same request once a
-- term is set. Approving an editor's curation republishes through the private implementation and is not blocked.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(11);

create function pg_temp.act_as(p_user uuid) returns void language sql as $$
  select set_config(
    'request.jwt.claims',
    json_build_object('sub', p_user, 'role', 'authenticated')::text,
    true
  );
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated;

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('74000000-0000-4000-8000-000000000001', 'medium-publisher@example.invalid', now(), '{}');
insert into content.staff_members (user_id, role, active) values
  ('74000000-0000-4000-8000-000000000001', 'publisher', true);

insert into content.exhibitions (id, created_by, updated_by) values
  ('medium-none', '74000000-0000-4000-8000-000000000001', '74000000-0000-4000-8000-000000000001'),
  ('medium-mood-only', '74000000-0000-4000-8000-000000000001', '74000000-0000-4000-8000-000000000001'),
  ('medium-nameless', '74000000-0000-4000-8000-000000000001', '74000000-0000-4000-8000-000000000001'),
  ('medium-editor-path', '74000000-0000-4000-8000-000000000001', '74000000-0000-4000-8000-000000000001');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours, created_by, updated_by
)
select
  version_id, exhibition_id, 1, 1, 'draft',
  name_ko, 'Medium Show', '장소', 'Venue', '서울', 'Seoul',
  '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-12-31', '10:00-18:00',
  '74000000-0000-4000-8000-000000000001', '74000000-0000-4000-8000-000000000001'
from (values
  ('74000000-0000-4000-8000-000000000101'::uuid, 'medium-none', '매체 없는 전시'),
  ('74000000-0000-4000-8000-000000000102'::uuid, 'medium-mood-only', '분위기만 있는 전시'),
  ('74000000-0000-4000-8000-000000000103'::uuid, 'medium-nameless', ''),
  ('74000000-0000-4000-8000-000000000104'::uuid, 'medium-editor-path', '편집자 경로 전시')
) as fixture(version_id, exhibition_id, name_ko);

insert into content.exhibition_version_terms (version_id, term_id, sort_order) values
  ('74000000-0000-4000-8000-000000000102', 'mood:quiet-meditative', 0);

set local role authenticated;
select pg_temp.act_as('74000000-0000-4000-8000-000000000001');

select throws_ok(
  $$ select public.admin_publish_exhibition(
       'medium-none', '74000000-0000-4000-8000-000000000101', 1, '74000000-0000-4000-8000-000000000901'
     ) $$,
  '23514',
  'medium_term_is_required',
  'a version without any term is not published'
);
select throws_ok(
  $$ select public.admin_publish_exhibition(
       'medium-mood-only', '74000000-0000-4000-8000-000000000102', 1, '74000000-0000-4000-8000-000000000902'
     ) $$,
  '23514',
  'medium_term_is_required',
  'a version with terms in other categories only is not published'
);
select throws_ok(
  $$ select public.admin_publish_exhibition(
       'medium-nameless', '74000000-0000-4000-8000-000000000103', 1, '74000000-0000-4000-8000-000000000903'
     ) $$,
  '23514',
  'name_ko_is_required',
  'the existing required-field refusal still comes first'
);
reset role;

select is(
  (select status::text from content.exhibition_versions where id = '74000000-0000-4000-8000-000000000101'),
  'draft',
  'a refused version stays a draft'
);
select is(
  (select published_version_id from content.exhibitions where id = 'medium-none'),
  null,
  'a refused exhibition has no published version'
);
select is(
  (select count(*)::integer from content.audit_log
   where entity_id = 'medium-none' and action = 'exhibition.published')
  + (select count(*)::integer from content.outbox_events
     where aggregate_id = 'medium-none' and event_type = 'exhibition.published'),
  0,
  'a refused publish leaves no audit row and no outbox event'
);

insert into content.exhibition_version_terms (version_id, term_id, sort_order) values
  ('74000000-0000-4000-8000-000000000101', 'medium:painting', 0),
  ('74000000-0000-4000-8000-000000000102', 'medium:sculpture', 1);

set local role authenticated;
select pg_temp.act_as('74000000-0000-4000-8000-000000000001');
select lives_ok(
  $$ select public.admin_publish_exhibition(
       'medium-none', '74000000-0000-4000-8000-000000000101', 1, '74000000-0000-4000-8000-000000000901'
     ) $$,
  'the same request publishes once a medium term is set'
);
select lives_ok(
  $$ select public.admin_publish_exhibition(
       'medium-mood-only', '74000000-0000-4000-8000-000000000102', 1, '74000000-0000-4000-8000-000000000902'
     ) $$,
  'a medium term next to other categories publishes'
);
reset role;

select is(
  (select published_version_id from content.exhibitions where id = 'medium-none'),
  '74000000-0000-4000-8000-000000000101'::uuid,
  'the exhibition now points at its published version'
);

-- Approving an editor's curation calls the private implementation directly; an untagged exhibition still moves.
select pg_temp.act_as('74000000-0000-4000-8000-000000000001');
select lives_ok(
  $$ select content_private.admin_publish_exhibition_impl(
       'medium-editor-path', '74000000-0000-4000-8000-000000000104', 1
     ) $$,
  'the editor-curation path is not blocked by the rule'
);

select ok(
  (select prosecdef and proconfig @> array['search_path=""'] from pg_proc
   where oid = 'content_private.admin_lifecycle_idempotent_impl(text,text,uuid,integer,uuid)'::regprocedure),
  'the publish command stays security definer with an empty search_path'
);

select * from finish();
rollback;
