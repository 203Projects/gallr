begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(9);

select has_function(
  'public',
  'outbox_claim_media_events',
  array['text', 'integer', 'integer'],
  'media-only outbox claim RPC exists'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.outbox_claim_media_events(text,integer,integer)',
    'EXECUTE'
  ),
  'service role can claim media events'
);

select ok(
  not has_function_privilege(
    'authenticated',
    'public.outbox_claim_media_events(text,integer,integer)',
    'EXECUTE'
  )
    and not has_function_privilege(
      'anon',
      'public.outbox_claim_media_events(text,integer,integer)',
      'EXECUTE'
    ),
  'browser roles cannot claim media events'
);

insert into content.outbox_events (
  id,
  aggregate_type,
  aggregate_id,
  event_type,
  payload,
  deduplication_key,
  available_at,
  created_at
)
values
  (
    '52000000-0000-0000-0000-000000000001'::uuid,
    'exhibition',
    'claim-filter-lifecycle',
    'exhibition.published',
    '{}'::jsonb,
    'claim-filter:lifecycle',
    now() - interval '3 hours',
    now() - interval '3 hours'
  ),
  (
    '52000000-0000-0000-0000-000000000002'::uuid,
    'media_asset',
    '52000000-0000-0000-0000-000000000012',
    'media.cleanup_requested',
    '{}'::jsonb,
    'claim-filter:cleanup',
    now() - interval '2 hours',
    now() - interval '2 hours'
  ),
  (
    '52000000-0000-0000-0000-000000000003'::uuid,
    'media_asset',
    '52000000-0000-0000-0000-000000000013',
    'media.publish_requested',
    '{}'::jsonb,
    'claim-filter:publish',
    now() - interval '1 hour',
    now() - interval '1 hour'
  );

create temporary table media_claim_filter_state (
  claim_order integer primary key,
  payload jsonb not null
) on commit drop;
grant select, insert on media_claim_filter_state to service_role;

set local role service_role;

select throws_ok(
  $$ select * from public.outbox_claim_media_events('media-batch', 2, 30) $$,
  '22023',
  'outbox_claim_batch_size_must_equal_one',
  'media claim preserves the single-event lease boundary'
);

insert into pg_temp.media_claim_filter_state (claim_order, payload)
select 1, claimed
from public.outbox_claim_media_events('media-one', 1, 30) as claimed;

insert into pg_temp.media_claim_filter_state (claim_order, payload)
select 2, claimed
from public.outbox_claim_media_events('media-two', 1, 30) as claimed;

select is(
  (
    select payload ->> 'event_type'
    from pg_temp.media_claim_filter_state
    where claim_order = 1
  ),
  'media.cleanup_requested',
  'the oldest eligible media event is claimed first'
);

select is(
  (
    select payload ->> 'event_type'
    from pg_temp.media_claim_filter_state
    where claim_order = 2
  ),
  'media.publish_requested',
  'the next media event is claimed without touching lifecycle delivery'
);

select is(
  (
    select count(*)::integer
    from public.outbox_claim_media_events('media-empty', 1, 30)
  ),
  0,
  'the media claim returns no row when only non-media delivery remains'
);

reset role;

select is(
  (
    select status::text || ':' || attempts::text
    from content.outbox_events
    where id = '52000000-0000-0000-0000-000000000001'::uuid
  ),
  'pending:0',
  'the older lifecycle event remains pending and unattempted'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where id in (
      '52000000-0000-0000-0000-000000000002'::uuid,
      '52000000-0000-0000-0000-000000000003'::uuid
    )
      and status = 'processing'::content.outbox_status
      and attempts = 1
      and lease_token is not null
  ),
  2,
  'media events receive independent leases'
);

select * from finish();
rollback;
