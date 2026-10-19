-- Walking-route leg cache and quotas (spec 093; contracts/walking-route.md, "Database objects").
-- The cache is written only through service_walking_legs_store, which checks both ends against the catalogue, so
-- a caller cannot plant arbitrary coordinates. Quotas are fixed windows: provider calls per minute and per Seoul
-- day across the project, legs per hour per hashed source.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(73);

-- Schema, RLS and privileges
select has_table('content_private', 'walking_legs', 'the leg cache exists');
select has_table('content_private', 'walking_route_quota', 'the quota windows exist');
select columns_are(
  'content_private', 'walking_legs',
  array['from_key', 'to_key', 'distance_m', 'polyline', 'provider', 'routed_at'],
  'the cache holds venue-pair keys and paths only: no visitor, device, request or time-of-use column (spec 093 FR-004)'
);
select ok(
  (select relrowsecurity from pg_class where oid = 'content_private.walking_legs'::regclass),
  'the leg cache has RLS on'
);
select ok(
  (select relrowsecurity from pg_class where oid = 'content_private.walking_route_quota'::regclass),
  'the quota windows have RLS on'
);
select ok(
  not has_table_privilege('anon', 'content_private.walking_legs', 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER'),
  'anon has no privilege on the leg cache'
);
select ok(
  not has_table_privilege('authenticated', 'content_private.walking_legs', 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER'),
  'authenticated has no privilege on the leg cache'
);
select ok(
  not has_table_privilege('anon', 'content_private.walking_route_quota', 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER'),
  'anon has no privilege on the quota windows'
);
select ok(
  not has_table_privilege('authenticated', 'content_private.walking_route_quota', 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER'),
  'authenticated has no privilege on the quota windows'
);
select is(
  (select array_agg(key_collation.collname::text order by attribute.attname)
   from pg_attribute as attribute join pg_collation as key_collation on key_collation.oid = attribute.attcollation
   where attribute.attrelid = 'content_private.walking_legs'::regclass and attribute.attname in ('from_key', 'to_key')),
  array['C', 'C'], 'both keys order bytewise, as the function does'
);

select ok(not has_function_privilege('anon', 'public.service_walking_legs_lookup(jsonb)', 'EXECUTE'), 'anon cannot read the cache');
select ok(not has_function_privilege('authenticated', 'public.service_walking_legs_lookup(jsonb)', 'EXECUTE'), 'authenticated cannot read the cache');
select ok(has_function_privilege('service_role', 'public.service_walking_legs_lookup(jsonb)', 'EXECUTE'), 'the service role reads the cache');
select ok(not has_function_privilege('anon', 'public.service_walking_legs_store(jsonb)', 'EXECUTE'), 'anon cannot write the cache');
select ok(not has_function_privilege('authenticated', 'public.service_walking_legs_store(jsonb)', 'EXECUTE'), 'authenticated cannot write the cache');
select ok(has_function_privilege('service_role', 'public.service_walking_legs_store(jsonb)', 'EXECUTE'), 'the service role writes the cache');
select ok(not has_function_privilege('anon', 'public.service_walking_route_consume(text,integer,integer)', 'EXECUTE'), 'anon cannot consume quota');
select ok(not has_function_privilege('authenticated', 'public.service_walking_route_consume(text,integer,integer)', 'EXECUTE'), 'authenticated cannot consume quota');
select ok(has_function_privilege('service_role', 'public.service_walking_route_consume(text,integer,integer)', 'EXECUTE'), 'the service role consumes quota');
select ok(
  not has_function_privilege('anon', 'content_private.walking_position_key(double precision,double precision)', 'EXECUTE'),
  'the key helper is private'
);
select is(
  (select count(*)::integer from pg_proc
   where oid in (
     'public.service_walking_legs_lookup(jsonb)'::regprocedure,
     'public.service_walking_legs_store(jsonb)'::regprocedure,
     'public.service_walking_route_consume(text,integer,integer)'::regprocedure
   ) and prosecdef and proconfig @> array['search_path=""']),
  3, 'the three service functions are definer-rights with an empty search_path'
);

set local role anon;
select throws_ok($$select public.service_walking_legs_lookup('[]'::jsonb)$$, '42501', null, 'anon is refused at call time');
reset role;
set local role authenticated;
select throws_ok(
  $$select public.service_walking_route_consume(repeat('a', 64), 1, 0)$$, '42501', null,
  'authenticated is refused at call time'
);
reset role;

-- Position keys: rounded to five decimals, always five digits after the point
select is(content_private.walking_position_key(37.5, 127), '37.50000,127.00000', 'keys keep five decimals');
select is(content_private.walking_position_key(37.544505, 127.056004), '37.54451,127.05600', 'keys round half away from zero');
select is(content_private.walking_position_key(37.5444949, 127.0560049), '37.54449,127.05600', 'keys round down below half');

-- Undirected key order
select throws_ok(
  $$insert into content_private.walking_legs (from_key, to_key, distance_m, polyline, provider)
    values ('37.54450,127.05600', '37.53450,127.00100', 10, 'abc', 'ors')$$,
  '23514', null, 'an unordered pair is rejected'
);
select throws_ok(
  $$insert into content_private.walking_legs (from_key, to_key, distance_m, polyline, provider)
    values ('37.54450,127.05600', '37.54450,127.05600', 10, 'abc', 'ors')$$,
  '23514', null, 'a pair with equal ends is rejected'
);
select throws_ok(
  $$insert into content_private.walking_legs (from_key, to_key, distance_m, polyline, provider)
    values ('37.5,127.0', '37.6,127.0', 10, 'abc', 'ors')$$,
  '23514', null, 'a key that is not a five-decimal position is rejected'
);

-- Catalogue fixtures
create temporary table seoul as select (now() at time zone 'Asia/Seoul')::date as today;
insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  stop.id, stop.id, stop.id, '갤러리 ' || stop.id, 'Gallery ' || stop.id, '서울', 'Seoul', '성동구', 'Seongdong-gu',
  seoul.today - 5, seoul.today + 30, false, stop.lat, stop.lng, '', '', '', '', false, now(), false,
  repeat('0', 64)
from seoul,
  (values ('wr-a', 37.54450::float8, 127.05600::float8), ('wr-b', 37.53450, 127.00100),
          ('wr-c', 37.57000, 126.98000), ('wr-nowhere', null, null)) as stop(id, lat, lng);

-- Store: both ends must match the catalogue
select is(
  public.service_walking_legs_store(jsonb_build_array(jsonb_build_object(
    'from_key', '37.53450,127.00100', 'from_exhibition_id', 'wr-b',
    'to_key', '37.54450,127.05600', 'to_exhibition_id', 'wr-a',
    'distance_m', 5230, 'polyline', 'b-to-a', 'provider', 'ors'))),
  1, 'a verified venue pair is stored'
);
select is(
  (select distance_m from content_private.walking_legs where from_key = '37.53450,127.00100' and to_key = '37.54450,127.05600'),
  5230, 'the stored pair keeps its distance'
);
select is(
  public.service_walking_legs_store(jsonb_build_array(jsonb_build_object(
    'from_key', '37.53451,127.00100', 'from_exhibition_id', 'wr-b',
    'to_key', '37.57000,126.98000', 'to_exhibition_id', 'wr-c',
    'distance_m', 100, 'polyline', 'x', 'provider', 'ors'))),
  0, 'a key that differs from the catalogue coordinates is skipped'
);
select is(
  public.service_walking_legs_store(jsonb_build_array(jsonb_build_object(
    'from_key', '37.53450,127.00100', 'from_exhibition_id', 'wr-gone',
    'to_key', '37.57000,126.98000', 'to_exhibition_id', 'wr-c',
    'distance_m', 100, 'polyline', 'x', 'provider', 'ors'))),
  0, 'an exhibition missing from the catalogue is skipped'
);
select is(
  public.service_walking_legs_store(jsonb_build_array(jsonb_build_object(
    'from_key', '37.53450,127.00100', 'from_exhibition_id', 'wr-b',
    'to_key', '37.57000,126.98000', 'to_exhibition_id', 'wr-nowhere',
    'distance_m', 100, 'polyline', 'x', 'provider', 'ors'))),
  0, 'an exhibition without coordinates is skipped'
);
select is(
  public.service_walking_legs_store(jsonb_build_array(jsonb_build_object(
    'from_key', '37.57000,126.98000', 'from_exhibition_id', 'wr-c',
    'to_key', '37.53450,127.00100', 'to_exhibition_id', 'wr-b',
    'distance_m', 100, 'polyline', 'x', 'provider', 'ors'))),
  0, 'an unordered pair is skipped without an error'
);
select is(
  public.service_walking_legs_store(jsonb_build_array(
    jsonb_build_object(
      'from_key', '37.53450,127.00100', 'from_exhibition_id', 'wr-b',
      'to_key', '37.57000,126.98000', 'to_exhibition_id', 'wr-c',
      'distance_m', 4100, 'polyline', 'b-to-c', 'provider', 'osrm'),
    jsonb_build_object(
      'from_key', '37.54450,127.05600', 'from_exhibition_id', 'wr-c',
      'to_key', '37.57000,126.98000', 'to_exhibition_id', 'wr-c',
      'distance_m', 100, 'polyline', 'x', 'provider', 'ors'),
    jsonb_build_object(
      'from_key', '37.54450,127.05600', 'from_exhibition_id', 'wr-a',
      'to_key', '37.57000,126.98000', 'to_exhibition_id', 'wr-c',
      'distance_m', 12.5, 'polyline', 'x', 'provider', 'ors'),
    jsonb_build_object(
      'from_key', '37.54450,127.05600', 'from_exhibition_id', 'wr-a',
      'to_key', '37.57000,126.98000', 'to_exhibition_id', 'wr-c',
      'distance_m', 100, 'polyline', 'x', 'provider', 'tmap'))),
  1, 'only the verified, well-formed pair of a batch is stored'
);
select is(
  (select count(*)::integer from content_private.walking_legs), 2,
  'skipped pairs leave no rows'
);
select throws_ok(
  $$select public.service_walking_legs_store('{"from_key": "x"}'::jsonb)$$,
  '22023', null, 'a store body that is not an array is refused'
);

-- Store refreshes an existing pair
update content_private.walking_legs set routed_at = now() - interval '400 days'
where from_key = '37.53450,127.00100' and to_key = '37.54450,127.05600';
select is(
  public.service_walking_legs_store(jsonb_build_array(jsonb_build_object(
    'from_key', '37.53450,127.00100', 'from_exhibition_id', 'wr-b',
    'to_key', '37.54450,127.05600', 'to_exhibition_id', 'wr-a',
    'distance_m', 5300, 'polyline', 'b-to-a-2', 'provider', 'osrm'))),
  1, 'a stale pair is refreshed'
);
select results_eq(
  $$select distance_m, polyline, provider, routed_at = now() from content_private.walking_legs
    where from_key = '37.53450,127.00100' and to_key = '37.54450,127.05600'$$,
  $$values (5300, 'b-to-a-2'::text, 'osrm'::text, true)$$,
  'the refresh replaces the path and its date'
);

-- A corrected catalogue coordinate gives a new key; the old key is no longer writable
update public.exhibition_catalog_v2 set latitude = 37.53460 where id = 'wr-b';
select is(
  public.service_walking_legs_store(jsonb_build_array(jsonb_build_object(
    'from_key', '37.53450,127.00100', 'from_exhibition_id', 'wr-b',
    'to_key', '37.54450,127.05600', 'to_exhibition_id', 'wr-a',
    'distance_m', 5300, 'polyline', 'old', 'provider', 'ors'))),
  0, 'the old key of a moved exhibition is no longer stored'
);
select is(
  public.service_walking_legs_store(jsonb_build_array(jsonb_build_object(
    'from_key', '37.53460,127.00100', 'from_exhibition_id', 'wr-b',
    'to_key', '37.54450,127.05600', 'to_exhibition_id', 'wr-a',
    'distance_m', 5290, 'polyline', 'new', 'provider', 'ors'))),
  1, 'the corrected coordinate is stored under its own key'
);

-- Lookup returns exact keys only
select is(
  jsonb_array_length(public.service_walking_legs_lookup(jsonb_build_array(
    jsonb_build_object('from_key', '37.53450,127.00100', 'to_key', '37.54450,127.05600'),
    jsonb_build_object('from_key', '37.53460,127.00100', 'to_key', '37.54450,127.05600')))),
  2, 'both exact pairs are found'
);
select is(
  public.service_walking_legs_lookup(jsonb_build_array(
    jsonb_build_object('from_key', '37.53460,127.00100', 'to_key', '37.54450,127.05600'))) -> 0 ->> 'polyline',
  'new', 'a lookup returns the stored path'
);
select ok(
  (public.service_walking_legs_lookup(jsonb_build_array(
    jsonb_build_object('from_key', '37.53460,127.00100', 'to_key', '37.54450,127.05600'))) -> 0) ?& array[
      'from_key', 'to_key', 'distance_m', 'polyline', 'provider', 'routed_at'],
  'a lookup row carries keys, distance, path, provider and date'
);
select is(
  public.service_walking_legs_lookup(jsonb_build_array(
    jsonb_build_object('from_key', '37.53451,127.00100', 'to_key', '37.54450,127.05600'))),
  '[]'::jsonb, 'a key one step away finds nothing'
);
select is(
  public.service_walking_legs_lookup(jsonb_build_array(
    jsonb_build_object('from_key', '37.54450,127.05600', 'to_key', '37.53460,127.00100'))),
  '[]'::jsonb, 'a reversed pair is not matched; the caller orders keys'
);
select throws_ok(
  $$select public.service_walking_legs_lookup((select jsonb_agg(jsonb_build_object('from_key', 'a', 'to_key', 'b'))
    from generate_series(1, 11)))$$,
  '22023', null, 'a lookup of more than ten pairs is refused'
);

-- Source quota: 120 legs an hour per source
select is(
  public.service_walking_route_consume(repeat('a', 64), 5, 0),
  '{"allowed": true, "retry_after_seconds": 0, "provider_calls_granted": 0}'::jsonb,
  'a first request is allowed'
);
select is(
  (select bool_and((public.service_walking_route_consume(repeat('a', 64), 5, 0) ->> 'allowed')::boolean)
   from generate_series(1, 23)),
  true, 'requests up to 120 legs in the hour are allowed'
);
select is(
  public.service_walking_route_consume(repeat('a', 64), 1, 0) ->> 'allowed',
  'false', 'the 121st leg in the hour is refused'
);
select ok(
  (public.service_walking_route_consume(repeat('a', 64), 1, 0) ->> 'retry_after_seconds')::integer between 1 and 3600,
  'a refusal names a retry within the hour'
);
select is(
  (select count from content_private.walking_route_quota where bucket = 'source:' || repeat('a', 64)),
  120, 'refused requests are not counted'
);
select is(
  public.service_walking_route_consume(repeat('b', 64), 5, 0) ->> 'allowed',
  'true', 'another source has its own quota'
);
update content_private.walking_route_quota set window_start = window_start - interval '1 hour'
where bucket = 'source:' || repeat('a', 64);
select is(
  public.service_walking_route_consume(repeat('a', 64), 5, 0) ->> 'allowed',
  'true', 'the source window rolls over'
);
select is(
  (select count(*)::integer from content_private.walking_route_quota
   where bucket = 'source:' || repeat('a', 64) and window_start < date_trunc('hour', now())),
  0, 'an expired source window is pruned'
);

-- Provider budget: 30 calls a minute, 1,500 a Seoul day, project-wide
select is(
  (select sum((public.service_walking_route_consume(null, 0, 3) ->> 'provider_calls_granted')::integer)::integer
   from generate_series(1, 9)),
  27, 'provider calls are granted under the minute budget'
);
select is(
  public.service_walking_route_consume(null, 0, 5) ->> 'provider_calls_granted',
  '3', 'a request at the minute limit is granted only what is left'
);
select is(
  public.service_walking_route_consume(null, 0, 1),
  '{"allowed": true, "retry_after_seconds": 0, "provider_calls_granted": 0}'::jsonb,
  'the 31st call in the minute is not granted, and the caller is still allowed'
);
select is(
  (select count from content_private.walking_route_quota where bucket = 'provider:day'),
  30, 'the day counts every granted call'
);
select is(
  (select window_start from content_private.walking_route_quota where bucket = 'provider:day'),
  ((now() at time zone 'Asia/Seoul')::date)::timestamp at time zone 'Asia/Seoul',
  'the day window starts at midnight in Seoul'
);
update content_private.walking_route_quota set window_start = window_start - interval '1 minute'
where bucket = 'provider:minute';
select is(
  public.service_walking_route_consume(null, 0, 2) ->> 'provider_calls_granted',
  '2', 'the minute window rolls over'
);
update content_private.walking_route_quota set count = 1499 where bucket = 'provider:day';
select is(
  public.service_walking_route_consume(null, 0, 3) ->> 'provider_calls_granted',
  '1', 'the last call of the Seoul day is granted alone'
);
select is(
  public.service_walking_route_consume(null, 0, 1) ->> 'provider_calls_granted',
  '0', 'no call is granted once the day budget is spent'
);
update content_private.walking_route_quota set window_start = window_start - interval '1 day'
where bucket = 'provider:day';
select is(
  public.service_walking_route_consume(null, 0, 1) ->> 'provider_calls_granted',
  '1', 'the day window rolls over'
);
select is(
  (select count(*)::integer from content_private.walking_route_quota
   where (bucket = 'provider:minute' and window_start < date_trunc('minute', now()))
      or (bucket = 'provider:day'
          and window_start < ((now() at time zone 'Asia/Seoul')::date)::timestamp at time zone 'Asia/Seoul')),
  0, 'expired provider windows are pruned'
);

-- A refused source consumes no provider budget
update content_private.walking_route_quota set count = 0 where bucket = 'provider:minute';
insert into content_private.walking_route_quota (bucket, window_start, count)
values ('source:' || repeat('c', 64), date_trunc('hour', now()), 120);
select is(
  public.service_walking_route_consume(repeat('c', 64), 1, 2) ->> 'provider_calls_granted',
  '0', 'a refused source is granted no provider call'
);
select is(
  (select count from content_private.walking_route_quota where bucket = 'provider:minute'),
  0, 'a refused source leaves the provider budget untouched'
);
select ok(
  (select prosrc ~ 'pg_advisory_xact_lock' from pg_proc
   where oid = 'public.service_walking_route_consume(text,integer,integer)'::regprocedure),
  'the provider budget is checked and spent under one lock'
);

-- Invalid quota calls
select throws_ok(
  $$select public.service_walking_route_consume('not-a-digest', 1, 0)$$,
  '22023', null, 'a source that is not a digest is refused'
);
select throws_ok(
  $$select public.service_walking_route_consume(repeat('a', 64), 6, 0)$$,
  '22023', null, 'more legs than one request can hold are refused'
);
select throws_ok(
  $$select public.service_walking_route_consume(null, 0, -1)$$,
  '22023', null, 'a negative provider call count is refused'
);

-- The service role works through the functions without table privileges
set local role service_role;
select lives_ok(
  $$select public.service_walking_legs_lookup('[]'::jsonb), public.service_walking_route_consume(repeat('d', 64), 1, 0)$$,
  'the service role reads and consumes through the functions'
);
reset role;

select * from finish();
rollback;
