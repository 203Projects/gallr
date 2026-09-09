begin;

create table content.mobile_analytics_daily (
  occurred_on date not null,
  platform text not null,
  app_major smallint not null,
  event_name text not null,
  surface text not null default 'none',
  entry_point text not null default 'none',
  exhibition_id text not null default '',
  discovery_kind text not null default 'none',
  position_bucket text not null default 'none',
  result_count smallint not null default 0,
  action text not null default 'none',
  route_mode text not null default 'none',
  stop_count smallint not null default 0,
  distance_band text not null default 'none',
  duration_band text not null default 'none',
  event_count bigint not null default 1,
  updated_at timestamptz not null default now(),
  primary key (
    occurred_on, platform, app_major, event_name, surface, entry_point,
    exhibition_id, discovery_kind, position_bucket, result_count, action, route_mode,
    stop_count, distance_band, duration_band
  ),
  constraint mobile_analytics_platform check (platform in ('android', 'ios')),
  constraint mobile_analytics_app_major check (app_major between 1 and 999),
  constraint mobile_analytics_event_name check (event_name in (
    'surface_viewed', 'exhibition_impression', 'exhibition_opened',
    'exhibition_intent', 'recommendations_shown', 'route_created',
    'route_started'
  )),
  constraint mobile_analytics_surface check (surface in (
    'none', 'featured', 'list', 'map', 'my_gallr', 'exhibition_detail',
    'gallery_detail', 'event_detail', 'editor_detail', 'settings'
  )),
  constraint mobile_analytics_entry_point check (entry_point in (
    'none', 'tab', 'card', 'notification', 'deep_link', 'recommendation',
    'route'
  )),
  constraint mobile_analytics_exhibition_id check (
    exhibition_id = ''
    or exhibition_id ~ '^[[:alnum:]_-]{1,128}$'
  ),
  constraint mobile_analytics_discovery_kind check (discovery_kind in (
    'none', 'featured', 'organic', 'search', 'editor', 'event', 'gallery',
    'nearby', 'saved', 'notification', 'recommendation', 'route'
  )),
  constraint mobile_analytics_position_bucket check (position_bucket in (
    'none', 'unranked', 'top_three', 'four_to_ten', 'after_ten'
  )),
  constraint mobile_analytics_result_count check (
    result_count = 0 or result_count between 1 and 20
  ),
  constraint mobile_analytics_action check (action in (
    'none', 'bookmark_add', 'bookmark_remove', 'share', 'open_maps',
    'ticket', 'contact', 'visit_recorded', 'gallery_open', 'follow_gallery'
  )),
  constraint mobile_analytics_route_mode check (route_mode in (
    'none', 'neighborhood', 'for_you', 'closing_soon', 'saved'
  )),
  constraint mobile_analytics_stop_count check (
    stop_count = 0 or stop_count between 2 and 5
  ),
  constraint mobile_analytics_distance_band check (distance_band in (
    'none', 'under_two_km', 'two_to_five_km', 'over_five_km'
  )),
  constraint mobile_analytics_duration_band check (duration_band in (
    'none', 'under_two_hours', 'two_to_four_hours', 'over_four_hours'
  )),
  constraint mobile_analytics_event_count check (event_count > 0)
);

create table content_private.mobile_analytics_receipts (
  event_id uuid primary key,
  received_at timestamptz not null default now()
);

create index mobile_analytics_receipts_received_idx
  on content_private.mobile_analytics_receipts (received_at);

create table content_private.mobile_analytics_quotas (
  scope text not null,
  quota_key text not null,
  window_start timestamptz not null,
  hits integer not null,
  updated_at timestamptz not null default now(),
  primary key (scope, quota_key, window_start),
  constraint mobile_analytics_quota_scope check (
    scope in ('source', 'project')
  ),
  constraint mobile_analytics_quota_key check (
    quota_key ~ '^[0-9a-f]{64}$'
  ),
  constraint mobile_analytics_quota_hits check (hits > 0)
);

create index mobile_analytics_quotas_window_idx
  on content_private.mobile_analytics_quotas (window_start);

alter table content.mobile_analytics_daily enable row level security;
alter table content_private.mobile_analytics_receipts enable row level security;
alter table content_private.mobile_analytics_quotas enable row level security;

revoke all on table
  content.mobile_analytics_daily,
  content_private.mobile_analytics_receipts,
  content_private.mobile_analytics_quotas
from public, anon, authenticated, service_role;

create or replace function content_private.mobile_analytics_limits()
returns jsonb
language sql
immutable
set search_path = ''
as $function$
  select jsonb_build_object(
    'source_hourly', 200,
    'project_hourly', 10000,
    'batch_size', 20,
    'receipt_days', 7,
    'quota_hours', 24,
    'aggregate_months', 24
  );
$function$;

create or replace function content_private.prune_mobile_analytics_state()
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_receipts_deleted integer;
  v_quotas_deleted integer;
  v_aggregates_deleted integer;
begin
  delete from content_private.mobile_analytics_receipts
  where received_at < now() - make_interval(
    days => (
      content_private.mobile_analytics_limits() ->> 'receipt_days'
    )::integer
  );
  get diagnostics v_receipts_deleted = row_count;

  delete from content_private.mobile_analytics_quotas
  where window_start <= date_trunc('hour', now()) - make_interval(
    hours => (
      content_private.mobile_analytics_limits() ->> 'quota_hours'
    )::integer
  );
  get diagnostics v_quotas_deleted = row_count;

  delete from content.mobile_analytics_daily
  where occurred_on < current_date - make_interval(
    months => (
      content_private.mobile_analytics_limits() ->> 'aggregate_months'
    )::integer
  );
  get diagnostics v_aggregates_deleted = row_count;

  return jsonb_build_object(
    'receipts', v_receipts_deleted,
    'quotas', v_quotas_deleted,
    'aggregates', v_aggregates_deleted
  );
end;
$function$;

create or replace function content_private.consume_mobile_analytics_quota(
  p_source_digest text,
  p_count integer
)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_window timestamptz := date_trunc('hour', now());
  v_limits jsonb := content_private.mobile_analytics_limits();
  v_source_hits integer;
  v_project_hits integer;
begin
  if p_source_digest is null
     or p_source_digest !~ '^[0-9a-f]{64}$'
     or p_count is null
     or p_count not between 1 and (v_limits ->> 'batch_size')::integer then
    raise exception using
      errcode = '22023', message = 'mobile_analytics_quota_invalid';
  end if;

  insert into content_private.mobile_analytics_quotas (
    scope, quota_key, window_start, hits
  ) values ('source', p_source_digest, v_window, p_count)
  on conflict (scope, quota_key, window_start) do update
  set hits = content_private.mobile_analytics_quotas.hits + excluded.hits,
      updated_at = now()
  where content_private.mobile_analytics_quotas.hits + excluded.hits
    <= (v_limits ->> 'source_hourly')::integer
  returning hits into v_source_hits;

  if v_source_hits is null then
    raise exception using
      errcode = 'P0001', message = 'mobile_analytics_rate_limited';
  end if;

  insert into content_private.mobile_analytics_quotas (
    scope, quota_key, window_start, hits
  ) values ('project', repeat('0', 64), v_window, p_count)
  on conflict (scope, quota_key, window_start) do update
  set hits = content_private.mobile_analytics_quotas.hits + excluded.hits,
      updated_at = now()
  where content_private.mobile_analytics_quotas.hits + excluded.hits
    <= (v_limits ->> 'project_hourly')::integer
  returning hits into v_project_hits;

  if v_project_hits is null then
    raise exception using
      errcode = 'P0001', message = 'mobile_analytics_rate_limited';
  end if;

  if v_project_hits = p_count then
    perform content_private.prune_mobile_analytics_state();
  end if;

  return v_source_hits;
end;
$function$;

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
      'route_started'
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

revoke all on function content_private.mobile_analytics_limits()
  from public, anon, authenticated, service_role;
revoke all on function content_private.consume_mobile_analytics_quota(text, integer)
  from public, anon, authenticated, service_role;
revoke all on function content_private.prune_mobile_analytics_state()
  from public, anon, authenticated, service_role;
revoke all on function public.service_record_mobile_analytics(jsonb, text)
  from public, anon, authenticated, service_role;

grant execute on function public.service_record_mobile_analytics(jsonb, text)
  to service_role;

comment on table content.mobile_analytics_daily is
  'Identity-free daily mobile product aggregates. No raw behavioral rows or precise location.';
comment on table content_private.mobile_analytics_receipts is
  'Seven-day event UUID receipts used only to make ambiguous mobile analytics retries idempotent.';
comment on function content_private.prune_mobile_analytics_state() is
  'Removes expired retry receipts, source quota digests, and identity-free daily aggregates.';
comment on function public.service_record_mobile_analytics(jsonb, text) is
  'Service-only strict mobile analytics recorder. Stores receipts plus allowlisted daily aggregates.';

select cron.schedule(
  'gallr-mobile-analytics-prune-v1',
  '17 * * * *',
  'select content_private.prune_mobile_analytics_state();'
);

commit;
