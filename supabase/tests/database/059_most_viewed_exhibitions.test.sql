-- Spec 091 FR-019: the most viewed read returns an ordering of exhibition ids from the aggregate analytics, and
-- nothing else: no counts, no rows for impressions, old days or thinly opened exhibitions.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(11);

create function pg_temp.seoul_today()
returns date
language sql
as $$ select (now() at time zone 'Asia/Seoul')::date; $$;

insert into content.mobile_analytics_daily
  (occurred_on, platform, app_major, event_name, surface, exhibition_id, event_count)
values
  -- "busy" is opened on two days and two platforms: 4 + 3 + 2 = 9.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'busy', 4),
  (pg_temp.seoul_today() - 1, 'android', 1, 'exhibition_opened', 'list', 'busy', 3),
  (pg_temp.seoul_today() - 6, 'ios', 1, 'exhibition_opened', 'map', 'busy', 2),
  -- "steady" totals 5, "also-steady" totals 5: ties order by id.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'steady', 5),
  (pg_temp.seoul_today(), 'android', 1, 'exhibition_opened', 'featured', 'also-steady', 5),
  -- Two opens is below the floor of three.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_opened', 'featured', 'thin', 2),
  -- Opened a lot, but eight days ago: outside the default window.
  (pg_temp.seoul_today() - 7, 'ios', 1, 'exhibition_opened', 'featured', 'last-week', 40),
  -- Impressions are not opens.
  (pg_temp.seoul_today(), 'ios', 1, 'exhibition_impression', 'featured', 'seen-only', 90),
  -- A row without an exhibition never ranks.
  (pg_temp.seoul_today(), 'ios', 1, 'surface_viewed', 'featured', '', 500);

select results_eq(
  $$ select * from public.list_most_viewed_exhibition_ids() $$,
  $$ values ('busy'::text), ('also-steady'), ('steady') $$,
  'the default read ranks seven Seoul days of opens, most opened first, ties by id'
);

select is(
  (select count(*) from public.list_most_viewed_exhibition_ids() as id where id in ('thin', 'seen-only', 'last-week', '')),
  0::bigint,
  'thin, impression-only, out-of-window and blank rows never rank'
);

select results_eq(
  $$ select * from public.list_most_viewed_exhibition_ids(p_days => 8) $$,
  $$ values ('last-week'::text), ('busy'), ('also-steady'), ('steady') $$,
  'a wider window reaches older days'
);

select results_eq(
  $$ select * from public.list_most_viewed_exhibition_ids(p_days => 1) $$,
  $$ values ('also-steady'::text), ('steady'), ('busy') $$,
  'a one-day window counts today only'
);

select results_eq(
  $$ select * from public.list_most_viewed_exhibition_ids(p_limit => 1) $$,
  $$ values ('busy'::text) $$,
  'the limit truncates'
);

select is(
  (select count(*) from public.list_most_viewed_exhibition_ids(p_days => 0, p_limit => 0)),
  1::bigint,
  'a non-positive window and limit clamp to one day and one row'
);

select is(
  (select count(*) from public.list_most_viewed_exhibition_ids(p_days => null, p_limit => null)),
  3::bigint,
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
  $$ values ('busy'::text), ('also-steady') $$,
  'the anon role gets ids through the function'
);
reset role;

select * from finish();
rollback;
