-- Spec 101 (US5): staff confirm a weekly opening-hours table; readers get it only while the free-text
-- hours it was confirmed against are unchanged.

begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(47);

-- == schema and privileges ==
select has_column('content', 'exhibition_versions', 'hours_weekly', 'versions carry confirmed weekly hours');
select has_column('content', 'exhibition_versions', 'hours_confirmed_by', 'versions record who confirmed hours');
select has_column('content', 'exhibition_versions', 'hours_confirmed_at', 'versions record when hours were confirmed');
select has_column('content', 'exhibition_versions', 'hours_source_hash', 'versions record the hours text hash');
select has_column('public', 'exhibition_catalog_v2', 'hours_weekly', 'the public catalogue carries confirmed hours');
select has_function(
  'public', 'admin_confirm_exhibition_hours', array['text', 'uuid', 'integer', 'jsonb'],
  'Admin confirms hours through one RPC'
);
select ok(
  has_function_privilege('authenticated', 'public.admin_confirm_exhibition_hours(text,uuid,integer,jsonb)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.admin_confirm_exhibition_hours(text,uuid,integer,jsonb)', 'EXECUTE'),
  'only signed-in callers reach the confirm RPC (the staff role is checked inside)'
);
select ok(
  has_table_privilege('anon', 'public.exhibition_catalog_v2', 'SELECT')
  and not has_table_privilege('anon', 'public.exhibition_catalog_v2', 'INSERT, UPDATE, DELETE'),
  'the catalogue stays read-only for anonymous readers'
);

-- == fixtures ==
create temp table hours_state (key text primary key, value text not null);
grant all on hours_state to authenticated, anon;
create function pg_temp.act_as(p_user uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
$$;
create function pg_temp.state(p_key text) returns text language sql as $$
  select value from hours_state where key = p_key;
$$;
create function pg_temp.remember(p_key text, p_value text) returns text language sql as $$
  insert into hours_state (key, value) values (p_key, p_value)
  on conflict (key) do update set value = excluded.value
  returning value;
$$;
create function pg_temp.week(p_opens text, p_closes text) returns jsonb language sql as $$
  select jsonb_build_object(
    'MONDAY', null,
    'TUESDAY', jsonb_build_object('opens', p_opens, 'closes', p_closes),
    'WEDNESDAY', jsonb_build_object('opens', p_opens, 'closes', p_closes),
    'THURSDAY', jsonb_build_object('opens', p_opens, 'closes', p_closes),
    'FRIDAY', jsonb_build_object('opens', p_opens, 'closes', p_closes),
    'SATURDAY', jsonb_build_object('opens', p_opens, 'closes', p_closes),
    'SUNDAY', jsonb_build_object('opens', p_opens, 'closes', p_closes)
  );
$$;
grant execute on function pg_temp.act_as(uuid), pg_temp.state(text), pg_temp.remember(text, text), pg_temp.week(text, text) to authenticated, anon;

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('71000000-0000-4000-8000-000000000001', 'hours-reader@example.invalid', now(), '{}'),
  ('71000000-0000-4000-8000-000000000002', 'hours-contributor@example.invalid', now(), '{}'),
  ('71000000-0000-4000-8000-000000000003', 'hours-publisher@example.invalid', now(), '{}');
insert into content.staff_members (user_id, role, active) values
  ('71000000-0000-4000-8000-000000000002', 'contributor', true),
  ('71000000-0000-4000-8000-000000000003', 'publisher', true);

insert into content.exhibitions (id, created_by, updated_by) values
  ('hours-fixture', '71000000-0000-4000-8000-000000000003', '71000000-0000-4000-8000-000000000003');
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours, created_by, updated_by
) values (
  '71000000-0000-4000-8000-000000000101', 'hours-fixture', 1, 1, 'draft',
  '시간 확인 전시', 'Hours Show', '장소', 'Venue', '서울', 'Seoul',
  '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-12-31',
  '화-일 10:00-18:00, 월 휴관',
  '71000000-0000-4000-8000-000000000003', '71000000-0000-4000-8000-000000000003'
);
set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000003');
select public.admin_publish_exhibition(
  'hours-fixture', '71000000-0000-4000-8000-000000000101', 1, '71000000-0000-4000-8000-000000000901'
);
reset role;

select is(
  (select hours_weekly from public.exhibition_catalog_v2 where id = 'hours-fixture'),
  null,
  'an exhibition without confirmed hours projects null'
);

-- == unauthorized callers ==
set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000001');
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101', pg_temp.week('10:00', '18:00')),
  '42501', 'active_staff_membership_required',
  'a signed-in reader cannot confirm hours'
);
select pg_temp.act_as('71000000-0000-4000-8000-000000000002');
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101', pg_temp.week('10:00', '18:00')),
  '42501', 'insufficient_staff_role',
  'a contributor cannot change hours that readers see without a publish step'
);
reset role;
set local role anon;
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101', pg_temp.week('10:00', '18:00')),
  '42501', null,
  'an anonymous caller cannot confirm hours'
);
reset role;

-- == validation ==
set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000003');
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101', pg_temp.week('10:00', '18:00') - 'SUNDAY'),
  '22023', 'hours_weekly_invalid', 'six weekdays are refused'
);
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101',
    pg_temp.week('10:00', '18:00') || '{"HOLIDAY": null}'::jsonb),
  '22023', 'hours_weekly_invalid', 'an eighth key is refused'
);
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101',
    (pg_temp.week('10:00', '18:00') - 'MONDAY') || '{"monday": null}'::jsonb),
  '22023', 'hours_weekly_invalid', 'weekday keys are upper case'
);
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101', pg_temp.week('18:00', '18:00')),
  '22023', 'hours_weekly_invalid', 'opens equal to closes is refused'
);
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101', pg_temp.week('19:00', '18:00')),
  '22023', 'hours_weekly_invalid', 'opens after closes is refused'
);
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101', pg_temp.week('9:00', '18:00')),
  '22023', 'hours_weekly_invalid', 'times are two-digit HH:MM'
);
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101', pg_temp.week('10:00', '24:00')),
  '22023', 'hours_weekly_invalid', 'times are 24-hour clock values up to 23:59'
);
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101',
    pg_temp.week('10:00', '18:00') || '{"MONDAY": "closed"}'::jsonb),
  '22023', 'hours_weekly_invalid', 'a closed day is null, not a string'
);
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 2, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101',
    pg_temp.week('10:00', '18:00') || '{"MONDAY": {"opens": "10:00", "closes": "18:00", "note": "x"}}'::jsonb),
  '22023', 'hours_weekly_invalid', 'a day has exactly opens and closes'
);
select throws_ok(
  $$ select public.admin_confirm_exhibition_hours('hours-fixture', '71000000-0000-4000-8000-000000000101', 2, null) $$,
  '22023', 'hours_weekly_invalid', 'null is refused'
);
select throws_ok(
  $$ select public.admin_confirm_exhibition_hours('hours-fixture', '71000000-0000-4000-8000-000000000101', 2, '[]'::jsonb) $$,
  '22023', 'hours_weekly_invalid', 'an array is refused'
);
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 1, %L::jsonb)',
    'hours-fixture', '71000000-0000-4000-8000-000000000101', pg_temp.week('10:00', '18:00')),
  'PT409', 'exhibition_metadata_stale_revision', 'a stale revision is refused'
);

-- == confirm on the published version ==
select pg_temp.remember('confirmed', public.admin_confirm_exhibition_hours(
  'hours-fixture', '71000000-0000-4000-8000-000000000101', 2, pg_temp.week('10:00', '18:00')
)::text);
reset role;

select is(
  pg_temp.state('confirmed')::jsonb -> 'hours_weekly',
  pg_temp.week('10:00', '18:00'),
  'the response carries the confirmed week'
);
select ok(
  (pg_temp.state('confirmed')::jsonb ->> 'hours_stale') = 'false'
  and (pg_temp.state('confirmed')::jsonb ->> 'hours_confirmed_at') is not null,
  'the response reports fresh hours and when they were confirmed'
);
select is(
  (pg_temp.state('confirmed')::jsonb ->> 'revision'),
  '3',
  'confirming hours is a content change and moves the revision'
);
select is(
  (select hours_source_hash from content.exhibition_versions where id = '71000000-0000-4000-8000-000000000101'),
  encode(extensions.digest(convert_to('화-일 10:00-18:00, 월 휴관', 'UTF8'), 'sha256'), 'hex'),
  'the hours hash is SHA-256 of the free-text hours'
);
select is(
  (select hours_confirmed_by::text from content.exhibition_versions where id = '71000000-0000-4000-8000-000000000101'),
  '71000000-0000-4000-8000-000000000003',
  'the confirming staff member is recorded'
);
select is(
  (select count(*)::integer from content.audit_log
   where action = 'exhibition.hours_confirmed' and entity_id = 'hours-fixture'),
  1,
  'confirming hours writes one audit row'
);
set local role anon;
select is(
  (select hours_weekly from public.exhibition_catalog_v2 where id = 'hours-fixture'),
  pg_temp.week('10:00', '18:00'),
  'readers receive confirmed hours while they are fresh'
);
reset role;

-- == confirm on an open draft ==
set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000002');
with saved as (
  select public.admin_save_exhibition_draft('hours-fixture', '71000000-0000-4000-8000-000000000101', 3, '{}'::jsonb)
    as payload
)
select pg_temp.remember('draft_id', payload ->> 'working_version_id'),
  pg_temp.remember('draft_revision', payload ->> 'revision'),
  pg_temp.remember('draft_payload', payload::text)
from saved;
reset role;
select is(
  pg_temp.state('draft_payload')::jsonb -> 'hours_weekly',
  pg_temp.week('10:00', '18:00'),
  'a new draft carries the confirmed hours'
);
select ok(
  (select hours_source_hash is not null and hours_confirmed_by is not null and hours_confirmed_at is not null
   from content.exhibition_versions where id = pg_temp.state('draft_id')::uuid),
  'a new draft carries who confirmed, when, and against which text'
);

set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000002');
select pg_temp.remember('draft_revision', public.admin_confirm_exhibition_hours(
  'hours-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('draft_revision')::integer,
  pg_temp.week('11:00', '19:00')
) ->> 'revision');
reset role;
select is(
  (select hours_weekly from content.exhibition_versions where id = pg_temp.state('draft_id')::uuid),
  pg_temp.week('11:00', '19:00'),
  'a contributor confirms hours on the open draft'
);
select is(
  (select hours_weekly from public.exhibition_catalog_v2 where id = 'hours-fixture'),
  pg_temp.week('10:00', '18:00'),
  'readers keep the published hours until the draft is published'
);
set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000003');
select public.admin_publish_exhibition(
  'hours-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('draft_revision')::integer,
  '71000000-0000-4000-8000-000000000902'
);
reset role;
select is(
  (select hours_weekly from public.exhibition_catalog_v2 where id = 'hours-fixture'),
  pg_temp.week('11:00', '19:00'),
  'publishing the draft publishes its confirmed hours'
);

-- == a changed hours text makes the week stale ==
select pg_temp.remember('published_revision', (
  select revision::text from content.exhibition_versions where id = pg_temp.state('draft_id')::uuid
));
set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000003');
with saved as (
  select public.admin_save_exhibition_draft(
    'hours-fixture', pg_temp.state('draft_id')::uuid, pg_temp.state('published_revision')::integer,
    '{"hours": "매일 12:00-20:00"}'::jsonb
  ) as payload
)
select pg_temp.remember('second_draft_id', payload ->> 'working_version_id'),
  pg_temp.remember('second_draft_revision', payload ->> 'revision'),
  pg_temp.remember('second_draft_payload', payload::text)
from saved;
reset role;
select is(
  pg_temp.state('second_draft_payload')::jsonb ->> 'hours_stale',
  'true',
  'Admin shows the hours as stale once the free text changes'
);
set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000003');
select is(
  (select item ->> 'hours_stale'
   from jsonb_array_elements(public.admin_list_stale_exhibition_metadata()) as item
   where item ->> 'exhibition_id' = 'hours-fixture'),
  'true',
  'the stale list reports stale hours'
);
select public.admin_publish_exhibition(
  'hours-fixture', pg_temp.state('second_draft_id')::uuid, pg_temp.state('second_draft_revision')::integer,
  '71000000-0000-4000-8000-000000000903'
);
reset role;
set local role anon;
select is(
  (select hours_weekly from public.exhibition_catalog_v2 where id = 'hours-fixture'),
  null,
  'readers stop receiving the week once the published hours text changes'
);
select is(
  (select hours from public.exhibition_catalog_v2 where id = 'hours-fixture'),
  '매일 12:00-20:00',
  'readers still receive the free-text hours'
);
reset role;
select is(
  (select hours_weekly from content.exhibition_versions where id = pg_temp.state('second_draft_id')::uuid),
  pg_temp.week('11:00', '19:00'),
  'the stale week is kept for staff to reconfirm'
);

-- Reconfirming the new text projects again.
select pg_temp.remember('published_revision', (
  select revision::text from content.exhibition_versions where id = pg_temp.state('second_draft_id')::uuid
));
set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000003');
select public.admin_confirm_exhibition_hours(
  'hours-fixture', pg_temp.state('second_draft_id')::uuid, pg_temp.state('published_revision')::integer,
  pg_temp.week('12:00', '20:00') || '{"MONDAY": {"opens": "12:00", "closes": "20:00"}}'::jsonb
);
reset role;
select is(
  (select hours_weekly -> 'MONDAY' from public.exhibition_catalog_v2 where id = 'hours-fixture'),
  '{"opens": "12:00", "closes": "20:00"}'::jsonb,
  'reconfirmed hours project again'
);

-- == an owner's private draft is invisible to staff ==
insert into content.galleries (id, name_ko, name_en, status, created_by, updated_by)
values ('71000000-0000-4000-8000-000000000301', '시간 갤러리', 'Hours Gallery', 'active',
        '71000000-0000-4000-8000-000000000001', '71000000-0000-4000-8000-000000000001');
insert into content.exhibitions (id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by)
values ('hours-owner-private', '71000000-0000-4000-8000-000000000301', 'draft', now(),
        '71000000-0000-4000-8000-000000000001', '71000000-0000-4000-8000-000000000001');
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours, created_by, updated_by
) values (
  '71000000-0000-4000-8000-000000000311', 'hours-owner-private', 1, 1, 'draft',
  '비공개 전시', 'Private Show', '장소', 'Venue', '서울', 'Seoul',
  '종로구', 'Jongno-gu', '주소', 'Address', 37.57, 126.98, '2026-09-01', '2026-12-31',
  '매일 10:00-18:00',
  '71000000-0000-4000-8000-000000000001', '71000000-0000-4000-8000-000000000001'
);
set local role authenticated;
select pg_temp.act_as('71000000-0000-4000-8000-000000000003');
select throws_ok(
  format('select public.admin_confirm_exhibition_hours(%L, %L::uuid, 1, %L::jsonb)',
    'hours-owner-private', '71000000-0000-4000-8000-000000000311', pg_temp.week('10:00', '18:00')),
  'P0002', 'exhibition_not_found',
  'staff cannot confirm hours on an owner''s private draft'
);
reset role;
select is(
  (select revision::text || '|' || coalesce(hours_weekly::text, 'null') from content.exhibition_versions
   where id = '71000000-0000-4000-8000-000000000311'),
  '1|null',
  'the owner draft keeps its revision and has no confirmed week'
);

select is(
  public.admin_reconcile_exhibition_catalog_v2() ->> 'in_sync',
  'true',
  'canonical reconciliation includes confirmed hours and stays in sync'
);
select is(
  (select count(*)::integer from public.exhibition_catalog_v2 as catalog
   where catalog.content_checksum_sha256 is distinct from content_private.exhibition_catalog_v2_checksum(catalog)),
  0,
  'every catalogue row checksum covers the new column'
);
select hasnt_column('public', 'exhibitions', 'hours_weekly', 'the legacy exhibitions reader is structurally unchanged');

select * from finish();
rollback;
