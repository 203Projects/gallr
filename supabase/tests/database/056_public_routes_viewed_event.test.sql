-- Spec 089 US11 (P12): 추천 동선 views join the aggregate analytics pipeline, counting only the rows shown.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(6);

create function pg_temp.view_event(p_id text, p_extra jsonb default '{}'::jsonb)
returns jsonb
language sql
as $$
  select jsonb_build_array(
    jsonb_build_object(
      'event_id', p_id,
      'occurred_on', current_date::text,
      'platform', 'ios',
      'app_major', 1,
      'event_name', 'public_routes_viewed'
    ) || p_extra
  );
$$;

select is(
  public.service_record_mobile_analytics(
    pg_temp.view_event('c1000000-0000-4000-8000-000000000001', '{"result_count": 3}'),
    repeat('e', 64)
  ) ->> 'accepted',
  '1',
  'a collapsed section view is counted'
);

select is(
  public.service_record_mobile_analytics(
    pg_temp.view_event('c1000000-0000-4000-8000-000000000002', '{"result_count": 10}'),
    repeat('e', 64)
  ) ->> 'accepted',
  '1',
  'a fully expanded section view is counted'
);

select is(
  (
    select string_agg(result_count || ':' || surface || ':' || stop_count || ':' || exhibition_id, ',' order by result_count)
    from content.mobile_analytics_daily
    where event_name = 'public_routes_viewed'
  ),
  '3:none:0:,10:none:0:',
  'section views carry only the rows shown'
);

select throws_ok(
  $$ select public.service_record_mobile_analytics(
       pg_temp.view_event('c1000000-0000-4000-8000-000000000003'),
       repeat('e', 64)) $$,
  '22023',
  'mobile_analytics_event_invalid',
  'a view without its row count is refused'
);

select throws_ok(
  $$ select public.service_record_mobile_analytics(
       pg_temp.view_event('c1000000-0000-4000-8000-000000000004', '{"result_count": 11}'),
       repeat('e', 64)) $$,
  '22023',
  'mobile_analytics_event_invalid',
  'more than ten rows is refused'
);

select throws_ok(
  $$ select public.service_record_mobile_analytics(
       pg_temp.view_event('c1000000-0000-4000-8000-000000000005', '{"result_count": 3, "surface": "map"}'),
       repeat('e', 64)) $$,
  '22023',
  'mobile_analytics_event_invalid',
  'a view names no surface or other dimension'
);

select * from finish();
rollback;
