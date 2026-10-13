-- Spec 089 read path (090 eng review D4): a shared route is readable only by its id. Nobody but the owner reads
-- the route tables directly, so published routes cannot be listed with the publishable key.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(27);

select ok(not has_table_privilege('anon', 'public.personal_routes', 'SELECT'), 'anon cannot select routes');
select ok(not has_table_privilege('anon', 'public.personal_route_stops', 'SELECT'), 'anon cannot select stops');
select ok(has_function_privilege('anon', 'public.get_published_route(uuid)', 'EXECUTE'), 'anon reads a route by id');
select ok(
  has_function_privilege('authenticated', 'public.get_published_route(uuid)', 'EXECUTE'),
  'signed-in users read a route by id'
);
select ok(
  (select prosecdef from pg_proc where oid = 'public.get_published_route(uuid)'::regprocedure),
  'the route read runs with definer rights'
);
select ok(
  (select proconfig @> array['search_path=""'] from pg_proc where oid = 'public.get_published_route(uuid)'::regprocedure),
  'the route read pins an empty search_path'
);

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-000000009001', 'read-author@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-000000009002', 'read-stranger@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-000000009003', 'read-staff@example.invalid', now(), false, '{}');
insert into content.staff_members (user_id, role, active) values ('00000000-0000-4000-8000-000000009003', 'admin', true);
update public.profiles set display_name = '  읽기 작가 ' where id = '00000000-0000-4000-8000-000000009001';

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  'rd-' || n, '전시 ' || n, 'Show ' || n, '갤러리 ' || n, 'Gallery ' || n,
  '서울', 'Seoul', '종로구', 'Jongno-gu', current_date - 1, current_date + 30, false,
  37.57 + n / 1000.0, 126.98 + n / 1000.0, '', '', '', '', false, now(), false, repeat('0', 64)
from generate_series(1, 3) as n;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000009001', true);
select public.save_personal_route('20000000-0000-4000-8000-000000000001', '공개 동선', array['rd-3', 'rd-1', 'rd-2']);
select public.publish_personal_route('20000000-0000-4000-8000-000000000001');
select public.save_personal_route('20000000-0000-4000-8000-000000000002', '비공개 동선', array['rd-1', 'rd-2']);
select public.save_personal_route('20000000-0000-4000-8000-000000000003', '내려진 동선', array['rd-1', 'rd-2']);
select public.publish_personal_route('20000000-0000-4000-8000-000000000003');
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000009003', true);
select public.revoke_personal_route('20000000-0000-4000-8000-000000000003');
reset role;

-- Without an account
set local role anon;
select throws_ok(
  $$select count(*) from public.personal_routes$$,
  '42501', null, 'anon cannot list routes from the table'
);
select throws_ok(
  $$select count(*) from public.personal_route_stops$$,
  '42501', null, 'anon cannot list stops from the table'
);
select is(
  public.get_published_route('20000000-0000-4000-8000-000000000001')->>'name',
  '공개 동선', 'anon reads a published route by its id'
);
select is(
  public.get_published_route('20000000-0000-4000-8000-000000000001')->>'author_display_name',
  '읽기 작가', 'the route carries the author''s trimmed display name'
);
select is(
  public.get_published_route('20000000-0000-4000-8000-000000000001')->>'is_mine',
  'false', 'a reader without an account never owns the route'
);
select ok(
  not (public.get_published_route('20000000-0000-4000-8000-000000000001') ? 'owner'),
  'the author''s account id is never part of the payload'
);
select ok(
  (public.get_published_route('20000000-0000-4000-8000-000000000001')->>'updated_at') is not null,
  'the route carries its revision'
);
select is(
  (
    select array_agg(stop.value->>'exhibition_id' order by stop.ordinality)
    from jsonb_array_elements(public.get_published_route('20000000-0000-4000-8000-000000000001')->'stops')
      with ordinality as stop(value, ordinality)
  ),
  array['rd-3', 'rd-1', 'rd-2'], 'stops come back in route order'
);
select is(
  (
    select array(select jsonb_object_keys(public.get_published_route('20000000-0000-4000-8000-000000000001')->'stops'->0)
                 order by 1)
  ),
  array['city_ko', 'exhibition_id', 'latitude', 'longitude', 'name_en', 'name_ko', 'position', 'region_en',
        'region_ko', 'venue_name_en', 'venue_name_ko'],
  'each stop carries the snapshot the route page renders'
);
select is(
  public.get_published_route('20000000-0000-4000-8000-000000000002'), null, 'an unpublished route reads as nothing'
);
select is(public.get_published_route('20000000-0000-4000-8000-000000000003'), null, 'a revoked route reads as nothing');
select is(public.get_published_route('20000000-0000-4000-8000-0000000000ff'), null, 'a missing route reads as nothing');
select is(public.get_published_route(null), null, 'no id reads as nothing');
reset role;

-- Another account
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000009002', true);
select is(
  (select count(*)::integer from public.personal_routes),
  0, 'another account cannot list published routes from the table'
);
select is(
  (select count(*)::integer from public.personal_route_stops),
  0, 'another account cannot list published stops from the table'
);
select is(
  public.get_published_route('20000000-0000-4000-8000-000000000001')->>'id',
  '20000000-0000-4000-8000-000000000001', 'another account reads a published route by its id'
);
select is(
  public.get_published_route('20000000-0000-4000-8000-000000000001')->>'is_mine',
  'false', 'another account is told the route is not theirs'
);

-- The owner
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000009001', true);
select is(
  public.get_published_route('20000000-0000-4000-8000-000000000001')->>'is_mine',
  'true', 'the owner is told the route is theirs'
);
select is(
  (select count(*)::integer from public.personal_routes),
  3, 'the owner reads all of their routes, private and revoked included'
);
select lives_ok(
  $$select id, name, is_published, updated_at from public.personal_routes where id = '20000000-0000-4000-8000-000000000002'$$,
  'the owner reads the columns the app selects directly'
);
select is(
  (select count(*)::integer from public.personal_route_stops where route_id = '20000000-0000-4000-8000-000000000002'),
  2, 'the owner reads the stops of their private route'
);
reset role;

select * from finish();
rollback;
