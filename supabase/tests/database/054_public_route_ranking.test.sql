-- Public routes ranking, eligibility, copies and reports (spec 089 US9, US10; contracts/public-routes-functions.md).
-- list_public_routes_impl takes the Seoul date so every date rule is fixed here.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(60);

-- Schema and privileges
select has_table('public', 'route_saves', 'copies table exists');
select has_table('public', 'route_reports', 'reports table exists');
select ok(has_function_privilege('anon', 'public.list_public_routes(integer)', 'EXECUTE'), 'anyone reads the public list');
select ok(has_function_privilege('anon', 'public.get_listed_route(uuid)', 'EXECUTE'), 'anyone reads a listed route by id');
select ok(
  (select proconfig @> array['search_path=""'] from pg_proc where oid = 'public.get_listed_route(uuid)'::regprocedure),
  'the listed read pins an empty search_path'
);
select has_column('public', 'route_reports', 'approved_at', 'a report names the approved version it was filed on');
select ok(not has_function_privilege('anon', 'public.save_public_route(uuid)', 'EXECUTE'), 'anon cannot copy');
select ok(not has_function_privilege('anon', 'public.report_route(uuid,text)', 'EXECUTE'), 'anon cannot report');
select ok(has_function_privilege('authenticated', 'public.save_public_route(uuid)', 'EXECUTE'), 'signed-in users copy');
select ok(not has_table_privilege('authenticated', 'public.route_saves', 'SELECT'), 'copies are not readable directly');
select ok(not has_table_privilege('authenticated', 'public.route_reports', 'SELECT'), 'reports are not readable directly');
select ok(
  not has_function_privilege('anon', 'content_private.list_public_routes_impl(integer,date)', 'EXECUTE'),
  'the dated implementation is private'
);

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000b801', 'rank-author@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000b802', 'rank-editor@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000b803', 'rank-reader-1@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000b804', 'rank-reader-2@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000b805', 'rank-reader-3@example.invalid', now(), false, '{}');
update public.profiles set display_name = '동선 작가' where id = '00000000-0000-4000-8000-00000000b801';
insert into public.editors (id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en, is_active, active_from)
values ('ranking-editor', '에디터', 'Editor', '에디터', 'Editor', '소개', 'Bio', true, current_date);
insert into content.editor_memberships (user_id, editor_id, active)
values ('00000000-0000-4000-8000-00000000b802', 'ranking-editor', true);

create temporary table seoul as select (now() at time zone 'Asia/Seoul')::date as today;
grant select on seoul to authenticated, anon;

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  stop.id, stop.id, stop.id, '갤러리 ' || stop.id, 'Gallery ' || stop.id, '서울', 'Seoul', stop.region, stop.region || '-en',
  seoul.today + stop.opens, seoul.today + stop.closes, false, 37.57, 126.98, '', '', '', '', false, now(), false,
  repeat('0', 64)
from seoul,
  (values ('pr-a', -5, 30, '한남동'), ('pr-b', -5, 30, '이태원동'), ('pr-c', -5, 30, '성수동'),
          ('pr-ended', -20, -1, '삼청동'), ('pr-future', 40, 60, '삼청동'), ('pr-late', 3, 20, '연희동'),
          ('pr-t1', -5, 10, '을지로'), ('pr-t2', 10, 20, '을지로'), ('pr-gone', -5, 30, '한남동'),
          ('pr-today', -5, 0, '서촌')) as stop(id, opens, closes, region);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000b801', true);
select public.save_personal_route(route.id, route.name, route.stops)
from (values
  ('40000000-0000-4000-8000-000000000001'::uuid, '한남 산책', array['pr-a', 'pr-b']),
  ('40000000-0000-4000-8000-000000000003'::uuid, '종료', array['pr-a', 'pr-ended']),
  ('40000000-0000-4000-8000-000000000004'::uuid, '엇갈림', array['pr-a', 'pr-future']),
  ('40000000-0000-4000-8000-000000000005'::uuid, '다음 주부터', array['pr-late', 'pr-c']),
  ('40000000-0000-4000-8000-000000000006'::uuid, '하루 겹침', array['pr-t1', 'pr-t2']),
  ('40000000-0000-4000-8000-000000000007'::uuid, '사라진 전시', array['pr-a', 'pr-gone']),
  ('40000000-0000-4000-8000-000000000008'::uuid, '검토 중', array['pr-a', 'pr-b']),
  ('40000000-0000-4000-8000-000000000009'::uuid, '비공개', array['pr-a', 'pr-b']),
  ('40000000-0000-4000-8000-000000000010'::uuid, '내려짐', array['pr-a', 'pr-b']),
  ('40000000-0000-4000-8000-000000000011'::uuid, '오늘 마감', array['pr-today', 'pr-b'])
) as route(id, name, stops);
select public.publish_personal_route(id) from unnest(array[
  '40000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000003',
  '40000000-0000-4000-8000-000000000004', '40000000-0000-4000-8000-000000000005',
  '40000000-0000-4000-8000-000000000006', '40000000-0000-4000-8000-000000000007',
  '40000000-0000-4000-8000-000000000008', '40000000-0000-4000-8000-000000000010',
  '40000000-0000-4000-8000-000000000011'
]::uuid[]) as id;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000b802', true);
select public.save_personal_route('40000000-0000-4000-8000-000000000002', '에디터 픽', array['pr-a', 'pr-c']);
select public.publish_personal_route('40000000-0000-4000-8000-000000000002');
reset role;

delete from public.exhibition_catalog_v2 where id = 'pr-gone';
update public.personal_routes as route
set listing_state = 'approved', listing_decided_at = now() - decided.age, listing_last_approved_at = now() - decided.age
from (values
  ('40000000-0000-4000-8000-000000000001'::uuid, interval '2 days'),
  ('40000000-0000-4000-8000-000000000002'::uuid, interval '1 day'),
  ('40000000-0000-4000-8000-000000000003'::uuid, interval '1 day'),
  ('40000000-0000-4000-8000-000000000004'::uuid, interval '1 day'),
  ('40000000-0000-4000-8000-000000000005'::uuid, interval '3 days'),
  ('40000000-0000-4000-8000-000000000006'::uuid, interval '4 days'),
  ('40000000-0000-4000-8000-000000000007'::uuid, interval '1 day'),
  ('40000000-0000-4000-8000-000000000009'::uuid, interval '1 day'),
  ('40000000-0000-4000-8000-000000000010'::uuid, interval '1 day'),
  ('40000000-0000-4000-8000-000000000011'::uuid, interval '5 days')
) as decided(id, age)
where route.id = decided.id;
update public.personal_routes set listing_state = 'requested', listing_requested_at = now()
where id = '40000000-0000-4000-8000-000000000008';
update public.personal_routes set revoked_at = now() where id = '40000000-0000-4000-8000-000000000010';

-- Copies made before the ranking checks
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000b803', true);
select is(
  jsonb_array_length(public.save_public_route('40000000-0000-4000-8000-000000000001')->'stops'),
  2, 'a copy returns the route stops'
);
select lives_ok($$select public.save_public_route('40000000-0000-4000-8000-000000000001')$$, 'copying again is harmless');
select lives_ok($$select public.save_public_route('40000000-0000-4000-8000-000000000002')$$, 'a reader copies another route');
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000b804', true);
select public.save_public_route('40000000-0000-4000-8000-000000000001');
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000b801', true);
select lives_ok($$select public.save_public_route('40000000-0000-4000-8000-000000000001')$$, 'an author may copy their own route');
select throws_ok(
  $$select public.save_public_route('40000000-0000-4000-8000-000000000008')$$,
  'PT409', 'route_not_listed', 'a route waiting for review cannot be copied'
);
select throws_ok(
  $$select public.save_public_route('40000000-0000-4000-8000-000000000003')$$,
  'PT409', 'route_not_listed', 'a route with an ended stop cannot be copied'
);
reset role;
insert into public.route_saves (route_id, account_id, approved_at, created_at)
select id, '00000000-0000-4000-8000-00000000b805', listing_decided_at, now() - interval '31 days'
from public.personal_routes where id = '40000000-0000-4000-8000-000000000002';

-- Eligibility and ranking
select is(
  (select array_agg(r->>'id' order by ordinality)
   from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) with ordinality as list(r, ordinality)),
  array['40000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000002',
        '40000000-0000-4000-8000-000000000005', '40000000-0000-4000-8000-000000000006',
        '40000000-0000-4000-8000-000000000011'],
  'only eligible routes are listed, by 30-day copies then newer approval'
);
select is(
  (select (r->>'copy_count_30d')::integer from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000001'),
  2, 'repeat copies and the owner''s copy are not counted'
);
select is(
  (select (r->>'copy_count_30d')::integer from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000002'),
  1, 'copies older than 30 days are not counted'
);
select is(
  (select r->>'first_shared_day' from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000005'),
  ((select today from seoul) + 3)::text, 'a route open together only later lists its first shared day'
);
select is(
  (select r->>'first_shared_day' from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000006'),
  ((select today from seoul) + 10)::text, 'stops that overlap on one day are eligible from that day'
);
select is(
  (select r->>'first_shared_day' from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000001'),
  (select today from seoul)::text, 'a route open today starts today'
);
select ok(
  not exists (
    select 1 from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul) + 1)) r
    where r->>'id' = '40000000-0000-4000-8000-000000000011'
  ),
  'a stop closing today leaves the list at Seoul midnight'
);
select is(
  (select r->>'is_editor' || ' ' || (r->>'author_display_name')
   from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000001'),
  'false 동선 작가', 'a row carries the author name and no editor label'
);
select is(
  (select r->>'is_editor' from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000002'),
  'true', 'an editor''s route carries the editor label'
);
select is(
  (select r->>'first_district_ko' || '–' || (r->>'last_district_ko') || ' ' || (r->>'stop_count')
   from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000001'),
  '한남동–이태원동 2', 'a row carries its first and last districts and stop count'
);
update content.editor_memberships set active = false where user_id = '00000000-0000-4000-8000-00000000b802';
select is(
  (select r->>'is_editor' from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000002'),
  'false', 'a former editor''s route stays listed without the label'
);
select is(jsonb_array_length(content_private.list_public_routes_impl(2, (select today from seoul))), 2, 'the limit is honoured');
select is(jsonb_array_length(content_private.list_public_routes_impl(0, (select today from seoul))), 1, 'the limit is at least one');
select is(jsonb_array_length(content_private.list_public_routes_impl(50, (select today from seoul))), 5, 'the limit is at most ten');

-- Re-approval starts a new count
update public.personal_routes set listing_decided_at = now() where id = '40000000-0000-4000-8000-000000000001';
select is(
  (select (r->>'copy_count_30d')::integer from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000001'),
  0, 're-approval resets the counted copies'
);
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000b803', true);
select public.save_public_route('40000000-0000-4000-8000-000000000001');
reset role;
select is(
  (select (r->>'copy_count_30d')::integer from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000001'),
  1, 'an earlier copier counts again for the new version'
);

-- Reports
set local role authenticated;
select lives_ok(
  $$select public.report_route('40000000-0000-4000-8000-000000000001', 'promotional')$$, 'a reader reports a route'
);
select throws_ok(
  $$select public.report_route('40000000-0000-4000-8000-000000000001', 'other')$$,
  'PT409', 'route_report_exists', 'one report per reader and approved version'
);
select throws_ok(
  $$select public.report_route('40000000-0000-4000-8000-000000000002', 'spam')$$,
  '22023', 'route_listing_invalid_reason', 'an unknown reason is refused'
);
select throws_ok(
  $$select public.report_route('40000000-0000-4000-8000-000000000008', 'other')$$,
  'PT409', 'route_not_listed', 'a route that is not shown cannot be reported'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000b801', true);
select throws_ok(
  $$select public.report_route('40000000-0000-4000-8000-000000000001', 'other')$$,
  '42501', 'route_report_own_route', 'an author cannot report their own route'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000b804', true);
select public.report_route('40000000-0000-4000-8000-000000000001', 'inappropriate');
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000b805', true);
select public.report_route('40000000-0000-4000-8000-000000000001', 'wrong_information');
reset role;
select ok(
  exists (
    select 1 from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
    where r->>'id' = '40000000-0000-4000-8000-000000000001'
  ),
  'reports never hide a route'
);
select is(
  (select count(*)::integer from public.route_reports where route_id = '40000000-0000-4000-8000-000000000001' and resolved_at is null),
  3, 'each report is kept for staff'
);

-- The listed-route read behind the preview answers only while the route is shown; the shared link outlives it
set local role anon;
select is(
  public.get_listed_route('40000000-0000-4000-8000-000000000001')->>'id',
  '40000000-0000-4000-8000-000000000001', 'anyone reads a listed route by its id'
);
select is(
  public.get_listed_route('40000000-0000-4000-8000-000000000001')->>'author_display_name',
  '동선 작가', 'the listed read names the author'
);
select is(
  public.get_listed_route('40000000-0000-4000-8000-000000000001')->>'is_mine',
  'false', 'the listed read carries is_mine like the shared read'
);
select is(public.get_listed_route('40000000-0000-4000-8000-000000000008'), null, 'a route waiting for review is not listed');
select is(
  public.get_published_route('40000000-0000-4000-8000-000000000008')->>'id',
  '40000000-0000-4000-8000-000000000008', 'its shared link still answers'
);
select is(public.get_listed_route('40000000-0000-4000-8000-000000000009'), null, 'an unpublished route is not listed');
select is(public.get_listed_route('40000000-0000-4000-8000-000000000010'), null, 'a revoked route is not listed');
select is(public.get_listed_route('40000000-0000-4000-8000-000000000003'), null, 'a route with an ended stop is not listed');
select is(
  public.get_published_route('40000000-0000-4000-8000-000000000003')->>'id',
  '40000000-0000-4000-8000-000000000003', 'a route that can no longer be walked keeps its shared link'
);
select is(public.get_listed_route('40000000-0000-4000-8000-0000000000ff'), null, 'a missing route is not listed');
reset role;
update public.personal_routes set listing_state = 'unlisted' where id = '40000000-0000-4000-8000-000000000001';
select is(public.get_listed_route('40000000-0000-4000-8000-000000000001'), null, 'a withdrawn route is not listed');
update public.personal_routes set listing_state = 'declined', listing_decline_reason = 'other'
where id = '40000000-0000-4000-8000-000000000001';
select is(public.get_listed_route('40000000-0000-4000-8000-000000000001'), null, 'a declined route is not listed');
update public.personal_routes set listing_state = 'removed', listing_decline_reason = null
where id = '40000000-0000-4000-8000-000000000001';
select is(public.get_listed_route('40000000-0000-4000-8000-000000000001'), null, 'a removed route is not listed');
select is(
  public.get_published_route('40000000-0000-4000-8000-000000000001')->>'id',
  '40000000-0000-4000-8000-000000000001', 'the shared link outlives the listing'
);
update public.personal_routes set listing_state = 'approved', listing_author_name = '승인 당시 이름'
where id = '40000000-0000-4000-8000-000000000001';
select is(
  public.get_listed_route('40000000-0000-4000-8000-000000000001')->>'author_display_name',
  '승인 당시 이름', 'the listed read names the author as approved'
);
select is(
  (select r->>'author_display_name' from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '40000000-0000-4000-8000-000000000001'),
  '승인 당시 이름', 'the list names the author as approved'
);
select is(
  public.get_published_route('40000000-0000-4000-8000-000000000001')->>'author_display_name',
  '동선 작가', 'the shared link names the author as they are now'
);

-- The public wrapper
set local role anon;
select ok(jsonb_array_length(public.list_public_routes(10)) >= 1, 'anon reads the public list');
select throws_ok(
  $$select public.save_public_route('40000000-0000-4000-8000-000000000001')$$,
  '42501', null, 'anon cannot copy through the wrapper'
);
reset role;

select * from finish();
rollback;
