-- Spec 089 (E-D6): personal-route author-loop events join the aggregate mobile analytics pipeline.
-- route_draft_started carries no dimension; route_published and route_shared carry only the stop count
-- (2 to 10). No route id, author or content is stored. Planner route events keep their 2-5 stop bound.
begin;

alter table content.mobile_analytics_daily
  drop constraint if exists mobile_analytics_event_name;
alter table content.mobile_analytics_daily
  add constraint mobile_analytics_event_name check (event_name in (
    'surface_viewed', 'exhibition_impression', 'exhibition_opened',
    'exhibition_intent', 'recommendations_shown', 'route_created',
    'route_started', 'route_draft_started', 'route_published', 'route_shared'
  ));

alter table content.mobile_analytics_daily
  drop constraint if exists mobile_analytics_stop_count;
alter table content.mobile_analytics_daily
  add constraint mobile_analytics_stop_count check (
    stop_count = 0 or stop_count between 2 and 10
  );

create or replace function public.service_record_mobile_analytics(
  p_events jsonb,
  p_source_digest text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_event jsonb;
  v_event_id uuid;
  v_occurred_on date;
  v_platform text;
  v_app_major integer;
  v_event_name text;
  v_surface text;
  v_entry_point text;
  v_exhibition_id text;
  v_discovery_kind text;
  v_position_bucket text;
  v_result_count integer;
  v_action text;
  v_route_mode text;
  v_stop_count integer;
  v_distance_band text;
  v_duration_band text;
  v_accepted integer := 0;
begin
  if p_events is null
     or jsonb_typeof(p_events) <> 'array'
     or jsonb_array_length(p_events) not between 1 and 20 then
    raise exception using
      errcode = '22023', message = 'mobile_analytics_batch_invalid';
  end if;

  perform content_private.consume_mobile_analytics_quota(
    p_source_digest,
    jsonb_array_length(p_events)
  );

  for v_event in select value from jsonb_array_elements(p_events)
  loop
    if jsonb_typeof(v_event) <> 'object'
       or not (v_event ?& array[
         'event_id', 'occurred_on', 'platform', 'app_major', 'event_name'
       ])
       or v_event - array[
         'event_id', 'occurred_on', 'platform', 'app_major', 'event_name',
         'surface', 'entry_point', 'exhibition_id', 'discovery_kind',
         'position_bucket', 'result_count', 'action', 'route_mode', 'stop_count',
         'distance_band', 'duration_band'
       ]::text[] <> '{}'::jsonb then
      raise exception using
        errcode = '22023', message = 'mobile_analytics_event_invalid';
    end if;

    begin
      v_event_id := (v_event ->> 'event_id')::uuid;
      v_occurred_on := (v_event ->> 'occurred_on')::date;
      v_app_major := (v_event ->> 'app_major')::integer;
      v_result_count := coalesce((v_event ->> 'result_count')::integer, 0);
      v_stop_count := coalesce((v_event ->> 'stop_count')::integer, 0);
    exception when others then
      raise exception using
        errcode = '22023', message = 'mobile_analytics_event_invalid';
    end;

    v_platform := coalesce(v_event ->> 'platform', '');
    v_event_name := coalesce(v_event ->> 'event_name', '');
    v_surface := coalesce(v_event ->> 'surface', 'none');
    v_entry_point := coalesce(v_event ->> 'entry_point', 'none');
    v_exhibition_id := coalesce(v_event ->> 'exhibition_id', '');
    v_discovery_kind := coalesce(v_event ->> 'discovery_kind', 'none');
    v_position_bucket := coalesce(v_event ->> 'position_bucket', 'none');
    v_action := coalesce(v_event ->> 'action', 'none');
    v_route_mode := coalesce(v_event ->> 'route_mode', 'none');
    v_distance_band := coalesce(v_event ->> 'distance_band', 'none');
    v_duration_band := coalesce(v_event ->> 'duration_band', 'none');

    if v_occurred_on not between current_date - 7 and current_date + 1
       or v_platform not in ('android', 'ios')
       or v_app_major not between 1 and 999
       or (
         v_exhibition_id <> ''
         and v_exhibition_id !~ '^[[:alnum:]_-]{1,128}$'
       ) then
      raise exception using
        errcode = '22023', message = 'mobile_analytics_event_invalid';
    end if;

    if (
      v_event_name = 'surface_viewed'
      and not (
        v_event ?& array['surface', 'entry_point']
        and not (v_event ?| array[
          'exhibition_id', 'discovery_kind', 'position_bucket', 'action',
          'result_count', 'route_mode', 'stop_count', 'distance_band',
          'duration_band'
        ])
      )
    ) or (
      v_event_name in ('exhibition_impression', 'exhibition_opened')
      and not (
        v_event ?& array[
          'exhibition_id', 'surface', 'discovery_kind', 'position_bucket'
        ]
        and not (v_event ?| array[
          'entry_point', 'action', 'route_mode', 'stop_count',
          'result_count', 'distance_band', 'duration_band'
        ])
      )
    ) or (
      v_event_name = 'exhibition_intent'
      and not (
        v_event ?& array['exhibition_id', 'surface', 'action']
        and not (v_event ?| array[
          'entry_point', 'discovery_kind', 'position_bucket', 'route_mode',
          'result_count', 'stop_count', 'distance_band', 'duration_band'
        ])
      )
    ) or (
      v_event_name = 'recommendations_shown'
      and not (
        v_event ?& array['surface', 'discovery_kind', 'result_count']
        and v_discovery_kind = 'recommendation'
        and v_result_count between 0 and 20
        and not (v_event ?| array[
          'entry_point', 'exhibition_id', 'position_bucket', 'action',
          'route_mode', 'stop_count', 'distance_band', 'duration_band'
        ])
      )
    ) or (
      v_event_name = 'route_draft_started'
      and v_event - array[
        'event_id', 'occurred_on', 'platform', 'app_major', 'event_name'
      ]::text[] <> '{}'::jsonb
    ) or (
      v_event_name in ('route_published', 'route_shared')
      and not (
        v_event ? 'stop_count'
        and v_stop_count between 2 and 10
        and v_event - array[
          'event_id', 'occurred_on', 'platform', 'app_major', 'event_name',
          'stop_count'
        ]::text[] = '{}'::jsonb
      )
    ) or (
      v_event_name in ('route_created', 'route_started')
      and not (
        v_event ?& array[
          'route_mode', 'stop_count', 'distance_band', 'duration_band'
        ]
        and v_stop_count between 2 and 5
        and not (v_event ?| array[
          'surface', 'entry_point', 'exhibition_id', 'discovery_kind',
          'position_bucket', 'result_count', 'action'
        ])
      )
    ) or v_event_name not in (
      'surface_viewed', 'exhibition_impression', 'exhibition_opened',
      'exhibition_intent', 'recommendations_shown', 'route_created',
      'route_started', 'route_draft_started', 'route_published', 'route_shared'
    ) then
      raise exception using
        errcode = '22023', message = 'mobile_analytics_event_invalid';
    end if;

    insert into content_private.mobile_analytics_receipts (event_id)
    values (v_event_id)
    on conflict (event_id) do nothing;

    if not found then
      continue;
    end if;

    insert into content.mobile_analytics_daily (
      occurred_on, platform, app_major, event_name, surface, entry_point,
      exhibition_id, discovery_kind, position_bucket, result_count, action,
      route_mode, stop_count, distance_band, duration_band
    ) values (
      v_occurred_on, v_platform, v_app_major, v_event_name, v_surface,
      v_entry_point, v_exhibition_id, v_discovery_kind, v_position_bucket,
      v_result_count, v_action, v_route_mode, v_stop_count, v_distance_band,
      v_duration_band
    )
    on conflict (
      occurred_on, platform, app_major, event_name, surface, entry_point,
      exhibition_id, discovery_kind, position_bucket, result_count, action,
      route_mode, stop_count, distance_band, duration_band
    ) do update
    set event_count = content.mobile_analytics_daily.event_count + 1,
        updated_at = now();

    v_accepted := v_accepted + 1;
  end loop;

  return jsonb_build_object('accepted', v_accepted);
end;
$function$;

commit;
