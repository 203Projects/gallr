-- Public route browse reads (spec 092; contracts/browse-rpc.md): the same eligibility as list_public_routes, two
-- sorts, a district filter, offset paging and up to three stop covers per row. The dated implementations take the
-- Seoul date so every date rule is fixed here.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(29);

-- Privileges
select ok(
  has_function_privilege('anon', 'public.browse_public_routes(text,text,integer,integer)', 'EXECUTE'),
  'anyone browses the public routes'
);
select ok(
  has_function_privilege('authenticated', 'public.browse_public_routes(text,text,integer,integer)', 'EXECUTE'),
  'signed-in readers browse the public routes'
);
select ok(
  has_function_privilege('anon', 'public.list_public_route_districts()', 'EXECUTE'),
  'anyone reads the district list'
);
select ok(
  not has_function_privilege('anon', 'content_private.browse_public_routes_impl(text,text,integer,integer,date)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'content_private.browse_public_routes_impl(text,text,integer,integer,date)', 'EXECUTE'),
  'the dated browse implementation is private'
);
select ok(
  not has_function_privilege('anon', 'content_private.list_public_route_districts_impl(date)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'content_private.list_public_route_districts_impl(date)', 'EXECUTE'),
  'the dated district implementation is private'
);
select ok(
  (select proconfig @> array['search_path=""'] from pg_proc
   where oid = 'public.browse_public_routes(text,text,integer,integer)'::regprocedure)
  and (select proconfig @> array['search_path=""'] from pg_proc
   where oid = 'public.list_public_route_districts()'::regprocedure),
  'both reads pin an empty search_path'
);

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000c801', 'browse-author@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000c803', 'browse-reader-1@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000c804', 'browse-reader-2@example.invalid', now(), false, '{}');
update public.profiles set display_name = '둘러보기 작가' where id = '00000000-0000-4000-8000-00000000c801';

create temporary table seoul as select (now() at time zone 'Asia/Seoul')::date as today;
grant select on seoul to authenticated, anon;

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256, cover_image_url
)
select
  stop.id, stop.id, stop.id, '갤러리 ' || stop.id, 'Gallery ' || stop.id, '서울', 'Seoul',
  stop.region, stop.region || '-en',
  seoul.today + stop.opens, seoul.today + stop.closes, false, 37.57, 126.98, '', '', '', '', false, now(), false,
  repeat('0', 64), stop.cover
from seoul,
  (values ('pb-a', -5, 30, '한남동', 'https://img.invalid/a.jpg'),
          ('pb-b', -5, 30, '이태원동', 'https://img.invalid/b.jpg'),
          ('pb-c', -5, 30, '성수동', null),
          ('pb-d', -5, 30, '성수동', 'https://img.invalid/a.jpg'),
          ('pb-e', -5, 30, '중구', 'https://img.invalid/e.jpg'),
          ('pb-f', -5, 30, '서촌', '  '),
          ('pb-g', -5, 30, '서촌', 'https://img.invalid/g.jpg'),
          ('pb-ended', -20, -1, '삼청동', 'https://img.invalid/x.jpg'),
          ('pb-late', 3, 20, '연희동', 'https://img.invalid/late.jpg')) as stop(id, opens, closes, region, cover);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000c801', true);
select public.save_personal_route(route.id, route.name, route.stops)
from (values
  ('50000000-0000-4000-8000-000000000001'::uuid, '한남 산책', array['pb-a', 'pb-b']),
  ('50000000-0000-4000-8000-000000000002'::uuid, '성수 두 곳', array['pb-c', 'pb-d', 'pb-a', 'pb-b', 'pb-f', 'pb-g']),
  ('50000000-0000-4000-8000-000000000003'::uuid, '빈 지역', array['pb-e', 'pb-a']),
  ('50000000-0000-4000-8000-000000000004'::uuid, '다음 주부터', array['pb-late', 'pb-c']),
  ('50000000-0000-4000-8000-000000000005'::uuid, '종료', array['pb-a', 'pb-ended']),
  ('50000000-0000-4000-8000-000000000006'::uuid, '검토 중', array['pb-a', 'pb-b']),
  ('50000000-0000-4000-8000-000000000007'::uuid, '내려짐', array['pb-a', 'pb-b']),
  ('50000000-0000-4000-8000-000000000008'::uuid, '비공개', array['pb-a', 'pb-b'])
) as route(id, name, stops);
select public.publish_personal_route(id) from unnest(array[
  '50000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000002',
  '50000000-0000-4000-8000-000000000003', '50000000-0000-4000-8000-000000000004',
  '50000000-0000-4000-8000-000000000005', '50000000-0000-4000-8000-000000000006',
  '50000000-0000-4000-8000-000000000007'
]::uuid[]) as id;
reset role;

-- The catalogue never has a blank region, but an older stop snapshot may; such a stop names no district.
update public.personal_route_stops set region_ko = '', region_en = '' where exhibition_id = 'pb-e';

-- Routes 3 and 4 share one approval time, so the id decides between them.
create temporary table approved_at as select now() - interval '3 days' as tied;
update public.personal_routes as route
set listing_state = 'approved', listing_decided_at = decided.at, listing_last_approved_at = decided.at
from (values
  ('50000000-0000-4000-8000-000000000001'::uuid, now() - interval '2 days'),
  ('50000000-0000-4000-8000-000000000002'::uuid, now() - interval '1 day'),
  ('50000000-0000-4000-8000-000000000003'::uuid, (select tied from approved_at)),
  ('50000000-0000-4000-8000-000000000004'::uuid, (select tied from approved_at)),
  ('50000000-0000-4000-8000-000000000005'::uuid, now() - interval '1 day'),
  ('50000000-0000-4000-8000-000000000007'::uuid, now() - interval '1 day'),
  ('50000000-0000-4000-8000-000000000008'::uuid, now() - interval '1 day')
) as decided(id, at)
where route.id = decided.id;
update public.personal_routes set listing_state = 'requested', listing_requested_at = now()
where id = '50000000-0000-4000-8000-000000000006';
update public.personal_routes set revoked_at = now() where id = '50000000-0000-4000-8000-000000000007';

-- Route 1 has two copies and route 2 one, so popularity and approval time disagree.
insert into public.route_saves (route_id, account_id, approved_at)
select route.id, reader.id, route.listing_decided_at
from public.personal_routes as route,
  (values ('00000000-0000-4000-8000-00000000c803'::uuid), ('00000000-0000-4000-8000-00000000c804'::uuid)) as reader(id)
where route.id = '50000000-0000-4000-8000-000000000001'
   or (route.id = '50000000-0000-4000-8000-000000000002' and reader.id = '00000000-0000-4000-8000-00000000c803');

create temporary table browse_ids (label text, ids text[]);
grant select on browse_ids to anon;
insert into browse_ids
select 'popular', array_agg(r->>'id' order by n)
from jsonb_array_elements(
  content_private.browse_public_routes_impl('popular', null, 20, 0, (select today from seoul))->'routes'
) with ordinality as page(r, n);

-- Eligibility and order
select is(
  (select ids from browse_ids where label = 'popular'),
  array['50000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000002',
        '50000000-0000-4000-8000-000000000003', '50000000-0000-4000-8000-000000000004'],
  'only eligible routes, by 30-day copies, then newer approval, then id'
);
select is(
  (select ids from browse_ids where label = 'popular'),
  (select array_agg(r->>'id' order by n)
   from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul)))
     with ordinality as list(r, n)),
  'the popular order is the 추천 동선 ranking'
);
select is(
  (select array_agg(r->>'id' order by n)
   from jsonb_array_elements(
     content_private.browse_public_routes_impl('newest', null, 20, 0, (select today from seoul))->'routes'
   ) with ordinality as page(r, n)),
  array['50000000-0000-4000-8000-000000000002', '50000000-0000-4000-8000-000000000001',
        '50000000-0000-4000-8000-000000000003', '50000000-0000-4000-8000-000000000004'],
  'newest orders by approval time, the id breaking ties'
);
select is(
  (content_private.browse_public_routes_impl('popular', null, 20, 0, (select today from seoul))->>'has_more')::boolean,
  false, 'a page holding every route has no more'
);

-- Rows
select is(
  (select r - 'cover_urls'
   from jsonb_array_elements(
     content_private.browse_public_routes_impl('popular', null, 20, 0, (select today from seoul))->'routes'
   ) r where r->>'id' = '50000000-0000-4000-8000-000000000001'),
  (select r from jsonb_array_elements(content_private.list_public_routes_impl(10, (select today from seoul))) r
   where r->>'id' = '50000000-0000-4000-8000-000000000001'),
  'a browse row carries exactly the 추천 동선 row, plus covers'
);
select is(
  (select r->'cover_urls'
   from jsonb_array_elements(
     content_private.browse_public_routes_impl('popular', null, 20, 0, (select today from seoul))->'routes'
   ) r where r->>'id' = '50000000-0000-4000-8000-000000000002'),
  '["https://img.invalid/a.jpg", "https://img.invalid/b.jpg", "https://img.invalid/g.jpg"]'::jsonb,
  'covers follow stop order, skip missing and blank covers, show once, and stop at three'
);
select is(
  (select r->'cover_urls'
   from jsonb_array_elements(
     content_private.browse_public_routes_impl('popular', null, 20, 0, (select today from seoul))->'routes'
   ) r where r->>'id' = '50000000-0000-4000-8000-000000000004'),
  '["https://img.invalid/late.jpg"]'::jsonb,
  'a stop without a cover is skipped'
);
select is(
  (select r->>'first_shared_day'
   from jsonb_array_elements(
     content_private.browse_public_routes_impl('popular', null, 20, 0, (select today from seoul))->'routes'
   ) r where r->>'id' = '50000000-0000-4000-8000-000000000004'),
  ((select today from seoul) + 3)::text, 'a route open together only later carries its first shared day'
);

-- Paging
select is(
  (select array_agg(r->>'id' order by n)
   from jsonb_array_elements(
     content_private.browse_public_routes_impl('popular', null, 3, 0, (select today from seoul))->'routes'
   ) with ordinality as page(r, n))
  || (select array_agg(r->>'id' order by n)
   from jsonb_array_elements(
     content_private.browse_public_routes_impl('popular', null, 3, 3, (select today from seoul))->'routes'
   ) with ordinality as page(r, n)),
  (select ids from browse_ids where label = 'popular'),
  'consecutive pages neither overlap nor skip'
);
select is(
  (content_private.browse_public_routes_impl('popular', null, 3, 0, (select today from seoul))->>'has_more')::boolean,
  true, 'a page with routes after it says so'
);
select is(
  (content_private.browse_public_routes_impl('popular', null, 3, 3, (select today from seoul))->>'has_more')::boolean,
  false, 'the last page says there is no more'
);
select is(
  jsonb_array_length(content_private.browse_public_routes_impl('popular', null, 0, 0, (select today from seoul))->'routes'),
  1, 'the limit is at least one'
);
select is(
  jsonb_array_length(content_private.browse_public_routes_impl('popular', null, 1000, -5, (select today from seoul))->'routes'),
  4, 'a large limit and a negative offset are clamped'
);
select is(
  jsonb_array_length(content_private.browse_public_routes_impl('popular', null, 20, 40, (select today from seoul))->'routes'),
  0, 'an offset past the end is an empty page'
);
select throws_ok(
  $$select content_private.browse_public_routes_impl('random', null, 20, 0, current_date)$$,
  '22023', 'route_browse_invalid_sort', 'an unknown sort is refused'
);

-- District filter
select is(
  (select array_agg(r->>'id' order by n)
   from jsonb_array_elements(
     content_private.browse_public_routes_impl('popular', '성수동', 20, 0, (select today from seoul))->'routes'
   ) with ordinality as page(r, n)),
  array['50000000-0000-4000-8000-000000000002', '50000000-0000-4000-8000-000000000004'],
  'a district keeps routes with any stop there, once each, in the chosen order'
);
select is(
  jsonb_array_length(
    content_private.browse_public_routes_impl('popular', '삼청동', 20, 0, (select today from seoul))->'routes'
  ),
  0, 'a district reached only by hidden routes is empty'
);
select is(
  jsonb_array_length(
    content_private.browse_public_routes_impl('popular', '', 20, 0, (select today from seoul))->'routes'
  ),
  4, 'a blank district means every district'
);

-- District list
select is(
  (select array_agg(d->>'district_ko' || ':' || (d->>'route_count') order by n)
   from jsonb_array_elements(content_private.list_public_route_districts_impl((select today from seoul)))
     with ordinality as list(d, n)),
  array['한남동:3', '성수동:2', '이태원동:2', '서촌:1', '연희동:1'],
  'districts of eligible routes, each route once per district, most routes first, then by name'
);
select is(
  (select d->>'district_en'
   from jsonb_array_elements(content_private.list_public_route_districts_impl((select today from seoul))) d
   where d->>'district_ko' = '성수동'),
  '성수동-en', 'a district carries its English name'
);

-- The public wrappers read today in Seoul
set local role anon;
select is(
  (select array_agg(r->>'id' order by n)
   from jsonb_array_elements(public.browse_public_routes()->'routes') with ordinality as page(r, n)),
  (select ids from browse_ids where label = 'popular'),
  'a reader without an account browses with the defaults'
);
select is(
  jsonb_array_length(public.list_public_route_districts()),
  5, 'a reader without an account reads the districts'
);
select is(
  (select array_agg(r->>'id' order by n)
   from jsonb_array_elements(public.browse_public_routes('newest', '한남동', 2, 0)->'routes')
     with ordinality as page(r, n)),
  array['50000000-0000-4000-8000-000000000002', '50000000-0000-4000-8000-000000000001'],
  'the wrapper passes sort, district, limit and offset'
);
reset role;

select * from finish();
rollback;
