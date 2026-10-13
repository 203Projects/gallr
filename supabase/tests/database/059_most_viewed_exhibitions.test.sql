-- Spec 091 FR-019: the most viewed read returns an ordering of published exhibition ids from the aggregate
-- analytics, and nothing else: no counts, no unpublished ids, no rows for impressions, old days, one-day bursts
-- or thinly opened exhibitions.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(11);

create function pg_temp.seoul_today()
returns date
language sql
as $$ select (now() at time zone 'Asia/Seoul')::date; $$;

-- The ranking is judged on this suite's rows alone.
delete from content.mobile_analytics_daily;

-- Every id below is published except "mv-withdrawn".
insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, description_ko, description_en, address_ko, address_en,
  is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  published.id, published.id, published.id, '모스트뷰 테스트 갤러리', 'Most Viewed Test Gallery',
  '서울', 'Seoul', '종로구', 'Jongno-gu', current_date - 30, current_date + 30, false, '', '', '', '',
  false, now(), false, repeat('0', 64)
from unnest(array[
  'mv-popular', 'mv-burst', 'mv-busy', 'mv-steady', 'mv-also-steady', 'mv-thin', 'mv-one-day',
  'mv-last-week', 'mv-seen-only'
]) as published(id);

insert into content.mobile_analytics_daily
  (occurred_on, platform, app_major, event_name, surface, exhibition_id, event_count)
values
  -- "popular" is opened steadily: 15 + 15 + 15 = 45.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'mv-popular', 15),
  (pg_temp.seoul_today() - 1, 'ios', 1, 'exhibition_opened', 'featured', 'mv-popular', 15),
  (pg_temp.seoul_today() - 2, 'ios', 1, 'exhibition_opened', 'featured', 'mv-popular', 15),
  -- "burst" has 500 opens in one day and one the day before: a day counts for at most 20, so 20 + 1 = 21.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'mv-burst', 500),
  (pg_temp.seoul_today() - 1, 'ios', 1, 'exhibition_opened', 'featured', 'mv-burst', 1),
  -- "busy" is opened on three days and two platforms: 4 + 3 + 2 = 9.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'mv-busy', 4),
  (pg_temp.seoul_today() - 1, 'android', 1, 'exhibition_opened', 'list', 'mv-busy', 3),
  (pg_temp.seoul_today() - 6, 'ios', 1, 'exhibition_opened', 'map', 'mv-busy', 2),
  -- "steady" and "also-steady" both total 6 over two days: ties order by id.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'mv-steady', 5),
  (pg_temp.seoul_today() - 2, 'ios', 1, 'exhibition_opened', 'featured', 'mv-steady', 1),
  (pg_temp.seoul_today(), 'android', 1, 'exhibition_opened', 'featured', 'mv-also-steady', 5),
  (pg_temp.seoul_today() - 3, 'android', 1, 'exhibition_opened', 'featured', 'mv-also-steady', 1),
  -- Two opens is below the floor of three.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'mv-thin', 1),
  (pg_temp.seoul_today() - 1, 'ios', 1, 'exhibition_opened', 'featured', 'mv-thin', 1),
  -- Opened a lot, but on a single day.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'mv-one-day', 40),
  -- Opened a lot on two days, but eight and nine days ago: outside the default window. 20 + 10 = 30.
  (pg_temp.seoul_today() - 7, 'ios', 1, 'exhibition_opened', 'featured', 'mv-last-week', 40),
  (pg_temp.seoul_today() - 8, 'ios', 1, 'exhibition_opened', 'featured', 'mv-last-week', 10),
  -- Impressions are not opens.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_impression', 'featured', 'mv-seen-only', 90),
  (pg_temp.seoul_today() - 1, 'ios', 1, 'exhibition_impression', 'featured', 'mv-seen-only', 90),
  -- Opened on two days, but no longer in the published catalogue.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'mv-withdrawn', 9),
  (pg_temp.seoul_today() - 1, 'ios', 1, 'exhibition_opened', 'featured', 'mv-withdrawn', 9),
  -- A row without an exhibition never ranks.
  (pg_temp.seoul_today(), 'ios', 1, 'surface_viewed', 'featured', '', 500);

select results_eq(
  $$ select * from public.list_most_viewed_exhibition_ids() $$,
  $$ values ('mv-popular'::text), ('mv-burst'), ('mv-busy'), ('mv-also-steady'), ('mv-steady') $$,
  'the default read ranks seven Seoul days of opens with each day capped, most opened first, ties by id'
);

select is(
  (
    select count(*)
    from public.list_most_viewed_exhibition_ids(p_days => 30, p_limit => 50) as id
    where id in ('mv-thin', 'mv-seen-only', 'mv-one-day', 'mv-withdrawn', '')
  ),
  0::bigint,
  'thin, impression-only, single-day, unpublished and blank rows never rank'
);

select results_eq(
  $$ select * from public.list_most_viewed_exhibition_ids(p_days => 9) $$,
  $$ values ('mv-popular'::text), ('mv-last-week'), ('mv-burst'), ('mv-busy'), ('mv-also-steady'), ('mv-steady') $$,
  'a wider window reaches older days'
);

select results_eq(
  $$ select * from public.list_most_viewed_exhibition_ids(p_days => 1) $$,
  $$ select * from public.list_most_viewed_exhibition_ids() $$,
  'a window shorter than seven days is read as seven, so one day cannot be isolated'
);

select results_eq(
  $$ select * from public.list_most_viewed_exhibition_ids(p_limit => 1) $$,
  $$ values ('mv-popular'::text) $$,
  'the limit truncates'
);

select is(
  (select count(*) from public.list_most_viewed_exhibition_ids(p_days => 0, p_limit => 0)),
  1::bigint,
  'a non-positive window and limit clamp to seven days and one row'
);

select is(
  (select count(*) from public.list_most_viewed_exhibition_ids(p_days => null, p_limit => null)),
  5::bigint,
  'null arguments fall back to the defaults'
);

select ok(
  has_function_privilege('anon', 'public.list_most_viewed_exhibition_ids(integer, integer)', 'execute'),
  'visitors without an account can read the ranking'
);

select ok(
  has_function_privilege('authenticated', 'public.list_most_viewed_exhibition_ids(integer, integer)', 'execute'),
  'signed-in visitors can read the ranking'
);

select ok(
  not has_table_privilege('anon', 'content.mobile_analytics_daily', 'select')
    and not has_table_privilege('authenticated', 'content.mobile_analytics_daily', 'select'),
  'the aggregate table itself stays unreadable to visitors'
);

set local role anon;
select results_eq(
  $$ select * from public.list_most_viewed_exhibition_ids(p_limit => 2) $$,
  $$ values ('mv-popular'::text), ('mv-burst') $$,
  'the anon role gets ids through the function'
);
reset role;

select * from finish();
rollback;
