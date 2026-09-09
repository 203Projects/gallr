begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(34);

select has_table('content', 'mobile_analytics_daily', 'daily mobile aggregates exist');
select has_table('content_private', 'mobile_analytics_receipts', 'idempotency receipts are private');
select has_table('content_private', 'mobile_analytics_quotas', 'source quotas are private');
select has_column(
  'content',
  'mobile_analytics_daily',
  'result_count',
  'recommendation result count has a bounded aggregate dimension'
);

select ok(
  (
    select relrowsecurity
    from pg_catalog.pg_class
    where oid = 'content.mobile_analytics_daily'::regclass
  ) and (
    select relrowsecurity
    from pg_catalog.pg_class
    where oid = 'content_private.mobile_analytics_receipts'::regclass
  ) and (
    select relrowsecurity
    from pg_catalog.pg_class
    where oid = 'content_private.mobile_analytics_quotas'::regclass
  ),
  'analytics tables retain RLS as defense in depth'
);

select ok(
  not has_table_privilege('anon', 'content.mobile_analytics_daily', 'SELECT, INSERT, UPDATE, DELETE')
  and not has_table_privilege('authenticated', 'content.mobile_analytics_daily', 'SELECT, INSERT, UPDATE, DELETE')
  and not has_table_privilege('anon', 'content_private.mobile_analytics_receipts', 'SELECT, INSERT, UPDATE, DELETE')
  and not has_table_privilege('authenticated', 'content_private.mobile_analytics_quotas', 'SELECT, INSERT, UPDATE, DELETE'),
  'client roles cannot read or forge analytics state'
);

select ok(
  has_function_privilege('service_role', 'public.service_record_mobile_analytics(jsonb,text)', 'EXECUTE')
  and not has_function_privilege('service_role', 'content_private.consume_mobile_analytics_quota(text,integer)', 'EXECUTE')
  and not has_function_privilege('service_role', 'content_private.prune_mobile_analytics_state()', 'EXECUTE')
  and not has_function_privilege('anon', 'public.service_record_mobile_analytics(jsonb,text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.service_record_mobile_analytics(jsonb,text)', 'EXECUTE'),
  'service role can only enter through the aggregate recorder'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    where procedure.oid in (
      to_regprocedure('public.service_record_mobile_analytics(jsonb,text)'),
      to_regprocedure('content_private.consume_mobile_analytics_quota(text,integer)'),
      to_regprocedure('content_private.prune_mobile_analytics_state()')
    )
      and privilege.grantee = 0
  ),
  'no analytics function remains executable by PUBLIC'
);

select ok(
  (
    select bool_and(
      procedure.prosecdef
      and procedure.proconfig = array['search_path=""']::text[]
    )
    from pg_catalog.pg_proc as procedure
    where procedure.oid in (
      to_regprocedure('public.service_record_mobile_analytics(jsonb,text)'),
      to_regprocedure('content_private.consume_mobile_analytics_quota(text,integer)'),
      to_regprocedure('content_private.prune_mobile_analytics_state()')
    )
  ),
  'privileged analytics functions pin an empty search path'
);

select is(
  content_private.consume_mobile_analytics_quota(repeat('a', 64), 1),
  1,
  'first source quota hit succeeds'
);
select is(
  content_private.consume_mobile_analytics_quota(repeat('a', 64), 1),
  2,
  'second source quota hit succeeds'
);

insert into content_private.mobile_analytics_quotas (
  scope, quota_key, window_start, hits
) values (
  'source', repeat('f', 64), date_trunc('hour', now()), 199
);

select throws_ok(
  $$select content_private.consume_mobile_analytics_quota(repeat('f', 64), 2)$$,
  'P0001',
  'mobile_analytics_rate_limited',
  'source quota fails closed above its limit'
);

select is(
  public.service_record_mobile_analytics(
    jsonb_build_array(
      jsonb_build_object(
        'event_id', 'a1000000-0000-4000-8000-000000000001',
        'occurred_on', current_date::text,
        'platform', 'android',
        'app_major', 1,
        'event_name', 'surface_viewed',
        'surface', 'featured',
        'entry_point', 'tab'
      )
    ),
    repeat('b', 64)
  ) ->> 'accepted',
  '1',
  'one valid surface event increments aggregates'
);

select is(
  (
    select event_count::text
    from content.mobile_analytics_daily
    where occurred_on = current_date
      and platform = 'android'
      and event_name = 'surface_viewed'
      and surface = 'featured'
  ),
  '1',
  'surface aggregate contains one event'
);

select is(
  public.service_record_mobile_analytics(
    jsonb_build_array(jsonb_build_object(
      'event_id', 'a1000000-0000-4000-8000-000000000010',
      'occurred_on', current_date::text,
      'platform', 'ios',
      'app_major', 1,
      'event_name', 'exhibition_opened',
      'surface', 'map',
      'exhibition_id', 'exhibition-one',
      'discovery_kind', 'nearby',
      'position_bucket', 'unranked'
    )),
    repeat('b', 64)
  ) ->> 'accepted',
  '1',
  'unranked map open is accepted without a fabricated position'
);

select is(
  public.service_record_mobile_analytics(
    jsonb_build_array(
      jsonb_build_object(
        'event_id', 'a1000000-0000-4000-8000-000000000001',
        'occurred_on', current_date::text,
        'platform', 'android',
        'app_major', 1,
        'event_name', 'surface_viewed',
        'surface', 'featured',
        'entry_point', 'tab'
      )
    ),
    repeat('b', 64)
  ) ->> 'accepted',
  '0',
  'retry receipt prevents a duplicate increment'
);

select is(
  (
    select event_count::text
    from content.mobile_analytics_daily
    where occurred_on = current_date
      and event_name = 'surface_viewed'
  ),
  '1',
  'aggregate remains unchanged after replay'
);

select is(
  public.service_record_mobile_analytics(
    jsonb_build_array(
      jsonb_build_object(
        'event_id', 'a1000000-0000-4000-8000-000000000002',
        'occurred_on', current_date::text,
        'platform', 'ios',
        'app_major', 1,
        'event_name', 'route_created',
        'route_mode', 'for_you',
        'stop_count', 3,
        'distance_band', 'two_to_five_km',
        'duration_band', 'two_to_four_hours'
      )
    ),
    repeat('c', 64)
  ) ->> 'accepted',
  '1',
  'coarse route event is accepted without coordinates'
);

select is(
  (
    select event_count::text
    from content.mobile_analytics_daily
    where event_name = 'route_created'
      and route_mode = 'for_you'
      and stop_count = 3
  ),
  '1',
  'route aggregate preserves only coarse dimensions'
);

select is(
  public.service_record_mobile_analytics(
    jsonb_build_array(
      jsonb_build_object(
        'event_id', 'a1000000-0000-4000-8000-000000000006',
        'occurred_on', current_date::text,
        'platform', 'android',
        'app_major', 1,
        'event_name', 'recommendations_shown',
        'surface', 'featured',
        'discovery_kind', 'recommendation',
        'result_count', 6
      )
    ),
    repeat('c', 64)
  ) ->> 'accepted',
  '1',
  'bounded recommendation result count is accepted'
);

select is(
  (
    select result_count::text
    from content.mobile_analytics_daily
    where event_name = 'recommendations_shown'
      and surface = 'featured'
  ),
  '6',
  'recommendation aggregate preserves its result count'
);

select is(
  public.service_record_mobile_analytics(
    jsonb_build_array(
      jsonb_build_object(
        'event_id', 'a1000000-0000-4000-8000-000000000008',
        'occurred_on', current_date::text,
        'platform', 'ios',
        'app_major', 1,
        'event_name', 'recommendations_shown',
        'surface', 'featured',
        'discovery_kind', 'recommendation',
        'result_count', 0
      )
    ),
    repeat('c', 64)
  ) ->> 'accepted',
  '1',
  'zero-result recommendation runs remain measurable'
);

select throws_ok(
  $$select public.service_record_mobile_analytics(
    jsonb_build_array(jsonb_build_object(
      'event_id', 'a1000000-0000-4000-8000-000000000009',
      'occurred_on', current_date::text,
      'platform', 'android',
      'app_major', 1,
      'event_name', 'exhibition_opened',
      'surface', 'list',
      'exhibition_id', 'person@example.com',
      'discovery_kind', 'organic',
      'position_bucket', 'top_three'
    )),
    repeat('c', 64)
  )$$,
  '22023',
  'mobile_analytics_event_invalid',
  'free-form or personal data cannot enter the exhibition identifier dimension'
);

select throws_ok(
  format(
    'select public.service_record_mobile_analytics(%L::jsonb, repeat(''c'', 64))',
    jsonb_build_array(jsonb_build_object(
      'event_id', 'a1000000-0000-4000-8000-000000000007',
      'occurred_on', current_date::text,
      'platform', 'android',
      'app_major', 1,
      'event_name', 'recommendations_shown',
      'surface', 'featured',
      'discovery_kind', 'recommendation'
    ))
  ),
  '22023',
  'mobile_analytics_event_invalid',
  'recommendation event without result count is rejected'
);

select throws_ok(
  format(
    'select public.service_record_mobile_analytics(%L::jsonb, repeat(''d'', 64))',
    jsonb_build_array(jsonb_build_object(
      'event_id', 'a1000000-0000-4000-8000-000000000003',
      'occurred_on', current_date::text,
      'platform', 'ios',
      'app_major', 1,
      'event_name', 'surface_viewed',
      'surface', 'map',
      'entry_point', 'tab',
      'latitude', 37.5
    ))
  ),
  '22023',
  'mobile_analytics_event_invalid',
  'unknown precise-location field is rejected'
);

select throws_ok(
  $$select public.service_record_mobile_analytics('[]'::jsonb, repeat('d', 64))$$,
  '22023',
  'mobile_analytics_batch_invalid',
  'empty batch is rejected'
);

select throws_ok(
  $$select public.service_record_mobile_analytics(
    jsonb_build_array(jsonb_build_object(
      'event_id', 'a1000000-0000-4000-8000-000000000004',
      'occurred_on', (current_date - 8)::text,
      'platform', 'android',
      'app_major', 1,
      'event_name', 'surface_viewed',
      'surface', 'list',
      'entry_point', 'tab'
    )),
    repeat('e', 64)
  )$$,
  '22023',
  'mobile_analytics_event_invalid',
  'event older than queue retention is rejected'
);

select is(
  (
    select count(*)::integer
    from information_schema.columns
    where table_schema in ('content', 'content_private')
      and table_name like 'mobile_analytics%'
      and column_name in (
        'user_id', 'installation_id', 'session_id', 'latitude', 'longitude',
        'coordinates', 'geometry', 'search', 'url', 'contact', 'taste', 'score'
      )
  ),
  0,
  'analytics tables have no identity, location, route, search, or taste columns'
);

insert into content_private.mobile_analytics_receipts (event_id, received_at)
values ('a1000000-0000-4000-8000-000000000099', now() - interval '8 days');

insert into content_private.mobile_analytics_quotas (
  scope, quota_key, window_start, hits
) values (
  'source', repeat('9', 64), date_trunc('hour', now()) - interval '25 hours', 1
);

insert into content.mobile_analytics_daily (
  occurred_on, platform, app_major, event_name, surface, entry_point
) values (
  (current_date - interval '25 months')::date,
  'android',
  1,
  'surface_viewed',
  'featured',
  'tab'
);

select is(
  content_private.prune_mobile_analytics_state(),
  jsonb_build_object('receipts', 1, 'quotas', 1, 'aggregates', 1),
  'state pruning enforces receipt, source-digest, and aggregate retention'
);

select is(
  (
    select count(*)::integer
    from content_private.mobile_analytics_receipts
    where event_id = 'a1000000-0000-4000-8000-000000000099'
  ),
  0,
  'expired receipt no longer exists'
);

select is(
  (
    select count(*)::integer
    from content_private.mobile_analytics_quotas
    where quota_key = repeat('9', 64)
  ),
  0,
  'expired source quota digest no longer exists'
);

select is(
  (
    select count(*)::integer
    from content.mobile_analytics_daily
    where occurred_on = (current_date - interval '25 months')::date
  ),
  0,
  'identity-free aggregates older than 24 months no longer exist'
);

select is(
  (
    select count(*)::integer
    from cron.job
    where jobname = 'gallr-mobile-analytics-prune-v1'
      and schedule = '17 * * * *'
      and command = 'select content_private.prune_mobile_analytics_state();'
  ),
  1,
  'hourly retention cleanup is scheduled independently of analytics traffic'
);

select throws_ok(
  $$set local role anon; select public.service_record_mobile_analytics(
    '[{"event_id":"a1000000-0000-4000-8000-000000000005"}]'::jsonb,
    repeat('f', 64)
  )$$,
  '42501',
  null,
  'anonymous clients cannot call the service recorder directly'
);

select * from finish();
rollback;
