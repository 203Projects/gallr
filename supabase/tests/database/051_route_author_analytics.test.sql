-- Spec 089 (E-D6): the personal-route author-loop events join the aggregate analytics pipeline as counts only.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(9);

create function pg_temp.route_event(p_id text, p_name text, p_extra jsonb default '{}'::jsonb)
returns jsonb
language sql
as $$
  select jsonb_build_array(
    jsonb_build_object(
      'event_id', p_id,
      'occurred_on', current_date::text,
      'platform', 'android',
      'app_major', 1,
      'event_name', p_name
    ) || p_extra
  );
$$;

select is(
  public.service_record_mobile_analytics(
    pg_temp.route_event('b1000000-0000-4000-8000-000000000001', 'route_draft_started'),
    repeat('d', 64)
  ) ->> 'accepted',
  '1',
  'a new draft is counted with no other dimension'
);

select is(
  public.service_record_mobile_analytics(
    pg_temp.route_event('b1000000-0000-4000-8000-000000000002', 'route_published', '{"stop_count": 10}'),
    repeat('d', 64)
  ) ->> 'accepted',
  '1',
  'a ten-stop publish is counted'
);

select is(
  public.service_record_mobile_analytics(
    pg_temp.route_event('b1000000-0000-4000-8000-000000000003', 'route_shared', '{"stop_count": 2}'),
    repeat('d', 64)
  ) ->> 'accepted',
  '1',
  'a two-stop share is counted'
);

select is(
  (
    select string_agg(event_name || ':' || stop_count || ':' || surface || ':' || exhibition_id, ',' order by event_name)
    from content.mobile_analytics_daily
    where event_name in ('route_draft_started', 'route_published', 'route_shared')
  ),
  'route_draft_started:0:none:,route_published:10:none:,route_shared:2:none:',
  'route aggregates carry only the stop count'
);

select throws_ok(
  $$ select public.service_record_mobile_analytics(
       pg_temp.route_event('b1000000-0000-4000-8000-000000000004', 'route_published'),
       repeat('d', 64)) $$,
  '22023',
  'mobile_analytics_event_invalid',
  'a publish without a stop count is refused'
);

select throws_ok(
  $$ select public.service_record_mobile_analytics(
       pg_temp.route_event('b1000000-0000-4000-8000-000000000005', 'route_shared', '{"stop_count": 11}'),
       repeat('d', 64)) $$,
  '22023',
  'mobile_analytics_event_invalid',
  'more than ten stops is refused'
);

select throws_ok(
  $$ select public.service_record_mobile_analytics(
       pg_temp.route_event('b1000000-0000-4000-8000-000000000006', 'route_draft_started', '{"surface": "map"}'),
       repeat('d', 64)) $$,
  '22023',
  'mobile_analytics_event_invalid',
  'a new-draft event carries no other dimension'
);

select throws_ok(
  $$ select public.service_record_mobile_analytics(
       pg_temp.route_event('b1000000-0000-4000-8000-000000000007', 'route_shared', '{"stop_count": 3, "exhibition_id": "e1"}'),
       repeat('d', 64)) $$,
  '22023',
  'mobile_analytics_event_invalid',
  'a share never names an exhibition'
);

select throws_ok(
  $$ select public.service_record_mobile_analytics(
       pg_temp.route_event(
         'b1000000-0000-4000-8000-000000000008',
         'route_created',
         '{"route_mode": "for_you", "stop_count": 6, "distance_band": "under_two_km", "duration_band": "under_two_hours"}'
       ),
       repeat('d', 64)) $$,
  '22023',
  'mobile_analytics_event_invalid',
  'planner routes keep their five-stop bound'
);

select * from finish();
rollback;
