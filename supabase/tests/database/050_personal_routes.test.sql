-- Spec 089 personal routes: tables, row-level security and functions (contracts/database-functions.md).
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(57);

select has_table('public', 'personal_routes', 'routes table exists');
select has_table('public', 'personal_route_stops', 'stops table exists');
select has_table('public', 'route_page_daily', 'daily page counts table exists');
select ok((select relrowsecurity from pg_class where oid = 'public.personal_routes'::regclass), 'routes have RLS');
select ok((select relrowsecurity from pg_class where oid = 'public.personal_route_stops'::regclass), 'stops have RLS');
select ok((select relrowsecurity from pg_class where oid = 'public.route_page_daily'::regclass), 'page counts have RLS');
select ok(not has_function_privilege('anon', 'public.save_personal_route(uuid,text,text[])', 'EXECUTE'), 'anon cannot save');
select ok(has_function_privilege('authenticated', 'public.save_personal_route(uuid,text,text[])', 'EXECUTE'), 'signed-in users can save');
select ok(not has_function_privilege('anon', 'public.revoke_personal_route(uuid)', 'EXECUTE'), 'anon cannot revoke');
select ok(has_function_privilege('anon', 'public.record_route_page_event(uuid,text,boolean)', 'EXECUTE'), 'anon can count page events');
select ok(not has_table_privilege('authenticated', 'public.personal_routes', 'INSERT,UPDATE,DELETE'), 'no direct route writes');
select ok(not has_table_privilege('authenticated', 'public.personal_route_stops', 'INSERT,UPDATE,DELETE'), 'no direct stop writes');
select ok(not has_table_privilege('anon', 'public.route_page_daily', 'SELECT'), 'page counts are not readable by anon');
select ok(not has_table_privilege('authenticated', 'public.personal_route_recipient_loop', 'SELECT'), 'recipient loop view is not readable by app users');

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-000000008901', 'route-author@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-000000008902', 'route-stranger@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-000000008903', 'route-staff@example.invalid', now(), false, '{}');
update public.profiles set display_name = '작가' where id = '00000000-0000-4000-8000-000000008901';
insert into content.staff_members (user_id, role, active) values ('00000000-0000-4000-8000-000000008903', 'admin', true);

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  'pr-' || lpad(n::text, 2, '0'), '전시 ' || n, 'Show ' || n, '갤러리 ' || n, 'Gallery ' || n,
  '서울', 'Seoul', '종로구', 'Jongno-gu', current_date - 1, current_date + 30, false,
  37.57 + n / 1000.0, 126.98 + n / 1000.0, '', '', '서울 종로구 삼청로 ' || n, '', false, now(), false, repeat('0', 64)
from generate_series(1, 11) as n;
insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
) values (
  'pr-nolocation', '위치 없는 전시', 'No location', '어딘가', 'Somewhere', '서울', 'Seoul', '중구', 'Jung-gu',
  current_date - 1, current_date + 30, false, null, null, '', '', '', '', false, now(), false, repeat('0', 64)
);

-- Saving
set local role authenticated;
select set_config('request.jwt.claim.sub', '', true);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000001', '산책', array['pr-01','pr-02'])$$,
  '42501', 'personal_route_unauthenticated', 'saving needs an account'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008901', true);
select lives_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000001', '  종로 산책  ', array['pr-01','pr-02'])$$,
  'author creates a route under an app-chosen id'
);
select is(
  (select name from public.personal_routes where id = '10000000-0000-4000-8000-000000000001'),
  '종로 산책', 'the stored name is trimmed'
);
select is(
  (select name_ko || '|' || venue_name_ko || '|' || region_ko || '|' || city_ko
   from public.personal_route_stops where route_id = '10000000-0000-4000-8000-000000000001' and position = 0),
  '전시 1|갤러리 1|종로구|서울', 'the stop snapshot is copied from the catalogue'
);
select is(
  (public.save_personal_route('10000000-0000-4000-8000-000000000001', '종로 산책', array['pr-01','pr-02'])->'stops'->1->>'exhibition_id'),
  'pr-02', 'save returns the stored stops in order'
);
select is(
  (select count(*)::integer from public.personal_routes where id = '10000000-0000-4000-8000-000000000001'),
  1, 'saving the same id again does not create a second route'
);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000002', '하나', array['pr-01'])$$,
  '22023', 'personal_route_invalid_stop_count', 'one stop is refused'
);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000002', '열하나',
    array['pr-01','pr-02','pr-03','pr-04','pr-05','pr-06','pr-07','pr-08','pr-09','pr-10','pr-11'])$$,
  '22023', 'personal_route_invalid_stop_count', 'eleven stops are refused'
);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000002', '중복', array['pr-01','pr-01'])$$,
  '22023', 'personal_route_duplicate_stop', 'duplicate stops are refused'
);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000002', repeat('가', 61), array['pr-01','pr-02'])$$,
  '22023', 'personal_route_invalid_name', 'a name over 60 characters is refused'
);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000002', '   ', array['pr-01','pr-02'])$$,
  '22023', 'personal_route_invalid_name', 'a blank name is refused'
);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000002', '위치', array['pr-01','pr-nolocation'])$$,
  '22023', 'personal_route_missing_location', 'an exhibition without a location is refused'
);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000002', '없음', array['pr-01','pr-unknown'])$$,
  '22023', 'personal_route_unavailable_stops', 'an id that is not in the catalogue is refused'
);

-- Ownership and reading
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008902', true);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000001', '탈취', array['pr-01','pr-02'])$$,
  '42501', 'personal_route_not_owner', 'another account cannot save over a route'
);
select is(
  (select count(*)::integer from public.personal_routes where id = '10000000-0000-4000-8000-000000000001'),
  0, 'another account cannot read a private route'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008901', true);
select is(
  (select count(*)::integer from public.personal_route_stops where route_id = '10000000-0000-4000-8000-000000000001'),
  2, 'the author reads their private route stops'
);
select throws_ok(
  $$update public.personal_routes set is_published = true where id = '10000000-0000-4000-8000-000000000001'$$,
  '42501', null, 'the author cannot publish by writing the table'
);
select throws_ok(
  $$insert into public.personal_route_stops (route_id, position, exhibition_id, name_ko, name_en, venue_name_ko,
    venue_name_en, latitude, longitude, region_ko, region_en, city_ko)
    values ('10000000-0000-4000-8000-000000000001', 5, 'fake', '가짜', '', '', '', 37.5, 127.0, '', '', '')$$,
  '42501', null, 'the author cannot insert forged stops'
);

-- Publishing
select ok(
  (public.publish_personal_route('10000000-0000-4000-8000-000000000001')->>'is_published')::boolean,
  'the author publishes the route'
);
create temporary table first_publication as
  select published_at from public.personal_routes where id = '10000000-0000-4000-8000-000000000001';
select lives_ok($$select public.publish_personal_route('10000000-0000-4000-8000-000000000001')$$, 'publishing again is harmless');
select is(
  (select published_at from public.personal_routes where id = '10000000-0000-4000-8000-000000000001'),
  (select published_at from first_publication), 'the first publication time is kept'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008902', true);
select is(
  public.get_published_route('10000000-0000-4000-8000-000000000001')->>'id',
  '10000000-0000-4000-8000-000000000001', 'another account reads a published route'
);
reset role;
set local role anon;
select is(
  jsonb_array_length(public.get_published_route('10000000-0000-4000-8000-000000000001')->'stops'),
  2, 'anyone reads a published route without an account'
);
reset role;

-- A stop that left the catalogue survives a reorder of the same route
delete from public.exhibition_catalog_v2 where id = 'pr-01';
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008901', true);
select lives_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000001', '종로 산책', array['pr-02','pr-01'])$$,
  'reordering keeps a stop whose exhibition left the catalogue'
);
select is(
  (select name_ko from public.personal_route_stops where route_id = '10000000-0000-4000-8000-000000000001' and position = 1),
  '전시 1', 'the departed stop keeps its saved snapshot'
);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000003', '새 동선', array['pr-01','pr-03'])$$,
  '22023', 'personal_route_unavailable_stops', 'a new route cannot take a stop that left the catalogue'
);

-- Moderation
select throws_ok(
  $$select public.revoke_personal_route('10000000-0000-4000-8000-000000000001')$$,
  '42501', 'personal_route_not_staff', 'an author cannot revoke'
);
select throws_ok(
  $$select public.get_route_for_moderation('10000000-0000-4000-8000-000000000001')$$,
  '42501', 'personal_route_not_staff', 'an author cannot use the moderation read'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008903', true);
select is(
  (public.get_route_for_moderation('10000000-0000-4000-8000-000000000001')->>'author_display_name'),
  '작가', 'staff see the author before revoking'
);
select lives_ok($$select public.revoke_personal_route('10000000-0000-4000-8000-000000000001')$$, 'staff revoke a route');
select ok(
  (public.get_route_for_moderation('10000000-0000-4000-8000-000000000001')->>'revoked_at') is not null,
  'the moderation read shows the revocation'
);
reset role;
set local role anon;
select is(
  public.get_published_route('10000000-0000-4000-8000-000000000001'),
  null, 'a revoked route is no longer public'
);
reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008901', true);
select throws_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000001', '종로 산책', array['pr-02','pr-03'])$$,
  '55000', 'personal_route_revoked', 'a revoked route cannot be saved'
);
select throws_ok(
  $$select public.publish_personal_route('10000000-0000-4000-8000-000000000001')$$,
  '55000', 'personal_route_revoked', 'a revoked route cannot be published'
);

-- Listing and deleting
select lives_ok(
  $$select public.save_personal_route('10000000-0000-4000-8000-000000000004', '두 번째', array['pr-03','pr-04'])$$,
  'author saves a second route'
);
select is(
  (select jsonb_array_length(public.list_my_personal_routes())),
  2, 'the author lists both of their routes'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008902', true);
select throws_ok(
  $$select public.delete_personal_route('10000000-0000-4000-8000-000000000004')$$,
  '42501', 'personal_route_not_owner', 'another account cannot delete a route'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008901', true);
select lives_ok($$select public.delete_personal_route('10000000-0000-4000-8000-000000000004')$$, 'the author deletes a route');
reset role;
select is(
  (select count(*)::integer from public.personal_route_stops where route_id = '10000000-0000-4000-8000-000000000004'),
  0, 'deleting a route removes its stops'
);

-- Page counts
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000008901', true);
select public.save_personal_route('10000000-0000-4000-8000-000000000005', '공개 동선', array['pr-05','pr-06']);
select public.publish_personal_route('10000000-0000-4000-8000-000000000005');
select public.save_personal_route('10000000-0000-4000-8000-000000000006', '비공개 동선', array['pr-05','pr-06']);
reset role;
set local role anon;
select public.record_route_page_event('10000000-0000-4000-8000-000000000005', 'route_page_opened', true);
select public.record_route_page_event('10000000-0000-4000-8000-000000000005', 'route_page_opened', true);
select public.record_route_page_event('10000000-0000-4000-8000-000000000005', 'route_page_started', false);
select public.record_route_page_event('10000000-0000-4000-8000-000000000005', 'something_else', true);
select public.record_route_page_event('10000000-0000-4000-8000-000000000006', 'route_page_opened', true);
select public.record_route_page_event('10000000-0000-4000-8000-000000000001', 'route_page_opened', true);
reset role;
select is(
  (select count from public.route_page_daily
   where route_id = '10000000-0000-4000-8000-000000000005' and event = 'route_page_opened' and shared),
  2, 'shared opens are counted per day'
);
select is(
  (select count(*)::integer from public.route_page_daily where event not in ('route_page_opened', 'route_page_started')),
  0, 'unknown event names are ignored'
);
select is(
  (select count(*)::integer from public.route_page_daily
   where route_id in ('10000000-0000-4000-8000-000000000006', '10000000-0000-4000-8000-000000000001')),
  0, 'private and revoked routes are not counted'
);
select is(
  (select routes_opened_within_7_days::integer from public.personal_route_recipient_loop
   where published_week = date_trunc('week', (now() at time zone 'Asia/Seoul'))::date),
  1, 'the recipient loop counts the route opened through a shared link'
);

select * from finish();
rollback;
