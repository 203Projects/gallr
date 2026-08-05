begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(65);

-- -------------------------------------------------------------------------
-- Durable receipt, public surface, and least-privilege boundaries.
-- -------------------------------------------------------------------------

select has_table(
  'content',
  'command_requests',
  'durable command receipt table exists'
);

select ok(
  (
    select relation.relrowsecurity
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname = 'command_requests'
  ),
  'command receipts have RLS enabled'
);

select ok(
  not has_table_privilege(
    'authenticated',
    'content.command_requests',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'authenticated callers cannot inspect or alter command receipts'
);

select ok(
  has_table_privilege('service_role', 'content.command_requests', 'SELECT, DELETE')
    and not has_table_privilege(
      'service_role',
      'content.command_requests',
      'INSERT, UPDATE'
    ),
  'service role receives receipt maintenance access without mutation access'
);

select is(
  (select public from storage.buckets where id = 'exhibition-images'),
  true,
  'the immutable exhibition delivery bucket is public'
);

select ok(
  (
    select file_size_limit = 10485760::bigint
      and allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp']::text[]
    from storage.buckets
    where id = 'exhibition-images'
  ),
  'the delivery bucket enforces the worker size and MIME contract'
);

select is(
  (
    select count(*)::integer
    from (
      values
        (to_regprocedure('public.admin_publish_exhibition(text,uuid,integer)')),
        (to_regprocedure('public.admin_archive_exhibition(text,uuid,integer)')),
        (to_regprocedure('public.admin_restore_exhibition(text,uuid,integer)'))
    ) as old_signature(procedure_oid)
    where old_signature.procedure_oid is not null
  ),
  0,
  'historical non-idempotent public lifecycle overloads are absent'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'admin_publish_exhibition',
        'admin_archive_exhibition',
        'admin_restore_exhibition'
      )
      and procedure.pronargs = 4
      and not procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  3,
  'idempotent lifecycle wrappers are fixed-path SECURITY INVOKER functions'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_publish_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_archive_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_restore_exhibition(text,uuid,integer,uuid)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  3,
  'authenticated publishers can execute all idempotent lifecycle wrappers'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_publish_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_archive_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_restore_exhibition(text,uuid,integer,uuid)')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
  ),
  0,
  'anon can execute no lifecycle command'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'outbox_claim_events',
        'outbox_complete_event',
        'outbox_fail_event',
        'outbox_mark_media_published',
        'outbox_reject_media',
        'outbox_prepare_media_cleanup',
        'outbox_finalize_media_cleanup',
        'outbox_sweep_stale_media'
      )
      and not procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  8,
  'all public worker RPCs are fixed-path SECURITY INVOKER functions'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.outbox_claim_events(text,integer,integer)'),
        ('public.outbox_complete_event(uuid,uuid)'),
        ('public.outbox_fail_event(uuid,uuid,text)'),
        ('public.outbox_mark_media_published(uuid,text,text,text,text,text,text,bigint,integer,integer,text,text)'),
        ('public.outbox_reject_media(uuid,text,text,text,text)'),
        ('public.outbox_prepare_media_cleanup(uuid,text,text,text,text)'),
        ('public.outbox_finalize_media_cleanup(uuid,uuid,text,text,text,text)'),
        ('public.outbox_sweep_stale_media(timestamptz,integer)')
    ) as signature(value)
    where has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  8,
  'service role can execute every narrow worker RPC'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.outbox_claim_events(text,integer,integer)'),
        ('public.outbox_complete_event(uuid,uuid)'),
        ('public.outbox_fail_event(uuid,uuid,text)'),
        ('public.outbox_mark_media_published(uuid,text,text,text,text,text,text,bigint,integer,integer,text,text)'),
        ('public.outbox_reject_media(uuid,text,text,text,text)'),
        ('public.outbox_prepare_media_cleanup(uuid,text,text,text,text)'),
        ('public.outbox_finalize_media_cleanup(uuid,uuid,text,text,text,text)'),
        ('public.outbox_sweep_stale_media(timestamptz,integer)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  0,
  'authenticated users can execute no worker RPC'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'outbox_claim_events_impl',
        'outbox_complete_event_impl',
        'outbox_fail_event_impl',
        'outbox_mark_media_published_impl',
        'outbox_reject_media_impl',
        'outbox_prepare_media_cleanup_impl',
        'outbox_finalize_media_cleanup_impl',
        'outbox_sweep_stale_media_impl'
      )
      and procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  8,
  'private worker implementations are hardened SECURITY DEFINER functions'
);

select is(
  (
    select count(*)::integer
    from pg_indexes
    where schemaname = 'content'
      and indexname in (
        'outbox_events_available_claim_idx',
        'outbox_events_expired_lease_idx'
      )
      and indexdef ilike '% WHERE %'
  ),
  2,
  'ready/failed and expired-lease claim paths have partial indexes'
);

-- -------------------------------------------------------------------------
-- Actor-scoped lifecycle idempotency.
-- -------------------------------------------------------------------------

insert into auth.users (id, email, raw_user_meta_data)
values
  (
    '00000000-0000-0000-0000-000000000401'::uuid,
    'idempotency-publisher@example.invalid',
    '{"full_name":"Idempotency Publisher"}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000402'::uuid,
    'idempotency-publisher-two@example.invalid',
    '{"full_name":"Second Publisher"}'::jsonb
  );

insert into content.staff_members (user_id, role, active)
values
  (
    '00000000-0000-0000-0000-000000000401'::uuid,
    'publisher'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000402'::uuid,
    'publisher'::content.staff_role,
    true
  );

insert into content.exhibitions (id, created_by, updated_by)
values
  (
    'idem-exhibition',
    '00000000-0000-0000-0000-000000000401'::uuid,
    '00000000-0000-0000-0000-000000000401'::uuid
  ),
  (
    'idem-exhibition-two',
    '00000000-0000-0000-0000-000000000402'::uuid,
    '00000000-0000-0000-0000-000000000402'::uuid
  );

insert into content.exhibition_versions (
  id,
  exhibition_id,
  version_number,
  revision,
  status,
  name_ko,
  venue_name_ko,
  city_ko,
  region_ko,
  address_ko,
  opening_date,
  closing_date,
  latitude,
  longitude,
  created_by,
  updated_by
)
values
  (
    '40000000-0000-0000-0000-000000000001'::uuid,
    'idem-exhibition',
    1,
    7,
    'draft'::content.exhibition_version_status,
    '멱등성 전시',
    '테스트 전시장',
    '서울',
    '용산구',
    '서울 용산구 테스트로 1',
    '2026-07-21'::date,
    '2026-08-21'::date,
    37.5343,
    126.9946,
    '00000000-0000-0000-0000-000000000401'::uuid,
    '00000000-0000-0000-0000-000000000401'::uuid
  ),
  (
    '40000000-0000-0000-0000-000000000002'::uuid,
    'idem-exhibition-two',
    1,
    3,
    'draft'::content.exhibition_version_status,
    '두 번째 전시',
    '두 번째 전시장',
    '서울',
    '종로구',
    '서울 종로구 테스트로 2',
    '2026-07-22'::date,
    '2026-08-22'::date,
    37.5735,
    126.9788,
    '00000000-0000-0000-0000-000000000402'::uuid,
    '00000000-0000-0000-0000-000000000402'::uuid
  );

create temporary table worker_test_state (
  key text primary key,
  payload jsonb not null
) on commit drop;
grant select, insert, update, delete on worker_test_state
  to authenticated, service_role;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000401","role":"authenticated"}',
  true
);

insert into pg_temp.worker_test_state (key, payload)
values (
  'publish',
  public.admin_publish_exhibition(
    'idem-exhibition',
    '40000000-0000-0000-0000-000000000001'::uuid,
    7,
    '41000000-0000-0000-0000-000000000001'::uuid
  )
);

select is(
  (select payload ->> 'status' from pg_temp.worker_test_state where key = 'publish'),
  'published',
  'the first publish request performs the lifecycle mutation'
);

insert into pg_temp.worker_test_state (key, payload)
values (
  'publish-replay',
  public.admin_publish_exhibition(
    'idem-exhibition',
    '40000000-0000-0000-0000-000000000001'::uuid,
    7,
    '41000000-0000-0000-0000-000000000001'::uuid
  )
);

select is(
  (select payload from pg_temp.worker_test_state where key = 'publish-replay'),
  (select payload from pg_temp.worker_test_state where key = 'publish'),
  'same actor, request ID, and parameters replay the stored response exactly'
);

reset role;

select is(
  (
    select count(*)::integer
    from content.command_requests
    where actor_user_id = '00000000-0000-0000-0000-000000000401'::uuid
      and request_id = '41000000-0000-0000-0000-000000000001'::uuid
      and completed_at is not null
      and response is not null
      and request_fingerprint ~ '^[0-9a-f]{64}$'
  ),
  1,
  'publish stores one complete canonical receipt'
);

select is(
  (
    select count(*)::integer
    from content.audit_log
    where action = 'exhibition.published'
      and entity_id = 'idem-exhibition'
      and request_id = '41000000-0000-0000-0000-000000000001'::uuid
  ),
  1,
  'the lifecycle audit row carries the request ID exactly once'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'exhibition.published'
      and aggregate_id = 'idem-exhibition'
  ),
  1,
  'publish replay creates no duplicate outbox event'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000401","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    select public.admin_publish_exhibition(
      'idem-exhibition',
      '40000000-0000-0000-0000-000000000001'::uuid,
      8,
      '41000000-0000-0000-0000-000000000001'::uuid
    )
  $$,
  '22023',
  'idempotency_key_reused_with_different_request',
  'the same request ID rejects altered parameters'
);

select throws_ok(
  $$
    select public.admin_archive_exhibition(
      'idem-exhibition',
      '40000000-0000-0000-0000-000000000001'::uuid,
      7,
      '41000000-0000-0000-0000-000000000001'::uuid
    )
  $$,
  '22023',
  'idempotency_key_reused_with_different_request',
  'the same request ID cannot be reused for a different command'
);

reset role;

select ok(
  (
    select count(*) = 1
    from content.audit_log
    where action = 'exhibition.published'
      and entity_id = 'idem-exhibition'
  )
  and (
    select count(*) = 1
    from content.outbox_events
    where event_type = 'exhibition.published'
      and aggregate_id = 'idem-exhibition'
  ),
  'rejected and replayed requests leave publish side effects singular'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000401","role":"authenticated"}',
  true
);

insert into pg_temp.worker_test_state (key, payload)
values (
  'archive',
  public.admin_archive_exhibition(
    'idem-exhibition',
    '40000000-0000-0000-0000-000000000001'::uuid,
    8,
    '41000000-0000-0000-0000-000000000002'::uuid
  )
);

select is(
  (select payload ->> 'status' from pg_temp.worker_test_state where key = 'archive'),
  'archived',
  'the first archive request performs the lifecycle mutation'
);

insert into pg_temp.worker_test_state (key, payload)
values (
  'archive-replay',
  public.admin_archive_exhibition(
    'idem-exhibition',
    '40000000-0000-0000-0000-000000000001'::uuid,
    8,
    '41000000-0000-0000-0000-000000000002'::uuid
  )
);

select is(
  (select payload from pg_temp.worker_test_state where key = 'archive-replay'),
  (select payload from pg_temp.worker_test_state where key = 'archive'),
  'archive replay returns the original response'
);

reset role;

select ok(
  (
    select count(*) = 1
    from content.audit_log
    where action = 'exhibition.archived'
      and entity_id = 'idem-exhibition'
      and request_id = '41000000-0000-0000-0000-000000000002'::uuid
  )
  and (
    select count(*) = 1
    from content.outbox_events
    where event_type = 'exhibition.archived'
      and aggregate_id = 'idem-exhibition'
  ),
  'archive replay creates one audit row and one outbox event'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000401","role":"authenticated"}',
  true
);

insert into pg_temp.worker_test_state (key, payload)
values (
  'restore',
  public.admin_restore_exhibition(
    'idem-exhibition',
    '40000000-0000-0000-0000-000000000001'::uuid,
    8,
    '41000000-0000-0000-0000-000000000003'::uuid
  )
);

select is(
  (select payload ->> 'status' from pg_temp.worker_test_state where key = 'restore'),
  'published',
  'the first restore request performs the lifecycle mutation'
);

insert into pg_temp.worker_test_state (key, payload)
values (
  'restore-replay',
  public.admin_restore_exhibition(
    'idem-exhibition',
    '40000000-0000-0000-0000-000000000001'::uuid,
    8,
    '41000000-0000-0000-0000-000000000003'::uuid
  )
);

select is(
  (select payload from pg_temp.worker_test_state where key = 'restore-replay'),
  (select payload from pg_temp.worker_test_state where key = 'restore'),
  'restore replay returns the original response'
);

reset role;

select ok(
  (
    select count(*) = 1
    from content.audit_log
    where action = 'exhibition.restored'
      and entity_id = 'idem-exhibition'
      and request_id = '41000000-0000-0000-0000-000000000003'::uuid
  )
  and (
    select count(*) = 1
    from content.outbox_events
    where event_type = 'exhibition.restored'
      and aggregate_id = 'idem-exhibition'
  ),
  'restore replay creates one audit row and one outbox event'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000402","role":"authenticated"}',
  true
);

insert into pg_temp.worker_test_state (key, payload)
values (
  'second-actor-publish',
  public.admin_publish_exhibition(
    'idem-exhibition-two',
    '40000000-0000-0000-0000-000000000002'::uuid,
    3,
    '41000000-0000-0000-0000-000000000001'::uuid
  )
);

select is(
  (
    select payload ->> 'status'
    from pg_temp.worker_test_state
    where key = 'second-actor-publish'
  ),
  'published',
  'a second actor can independently use the same request UUID'
);

reset role;

select is(
  (
    select count(*)::integer
    from content.command_requests
    where request_id = '41000000-0000-0000-0000-000000000001'::uuid
  ),
  2,
  'receipt uniqueness is scoped to actor plus request ID'
);

delete from content.outbox_events
where aggregate_id in ('idem-exhibition', 'idem-exhibition-two');

-- -------------------------------------------------------------------------
-- Lease identity, reclaim, completion, retry, and dead-letter behavior.
-- -------------------------------------------------------------------------

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
    '42000000-0000-0000-0000-000000000001'::uuid,
    'test',
    'lease-one',
    'test.lease',
    '{"number":1}'::jsonb,
    'test:lease:one',
    now() - interval '4 hours',
    now() - interval '4 hours'
  ),
  (
    '42000000-0000-0000-0000-000000000002'::uuid,
    'test',
    'lease-two',
    'test.lease',
    '{"number":2}'::jsonb,
    'test:lease:two',
    now() - interval '3 hours',
    now() - interval '3 hours'
  );

set local role service_role;

select throws_ok(
  $$ select * from public.outbox_claim_events('batch-worker', 2, 30) $$,
  '22023',
  'outbox_claim_batch_size_must_equal_one',
  'one invocation cannot pre-lease a sequential event batch'
);

insert into pg_temp.worker_test_state (key, payload)
select 'claim-one', claimed
from public.outbox_claim_events('worker-one', 1, 30) as claimed;

insert into pg_temp.worker_test_state (key, payload)
select 'claim-two', claimed
from public.outbox_claim_events('worker-two', 1, 30) as claimed;

select is(
  (select payload ->> 'id' from pg_temp.worker_test_state where key = 'claim-one'),
  '42000000-0000-0000-0000-000000000001',
  'first worker claims the oldest available event'
);

select is(
  (select payload ->> 'id' from pg_temp.worker_test_state where key = 'claim-two'),
  '42000000-0000-0000-0000-000000000002',
  'a second claim skips the first lease and receives disjoint work'
);

select ok(
  (
    select left_state.payload ->> 'lease_token'
      <> right_state.payload ->> 'lease_token'
      and left_state.payload ->> 'lease_owner' = 'worker-one'
      and right_state.payload ->> 'lease_owner' = 'worker-two'
    from pg_temp.worker_test_state as left_state
    cross join pg_temp.worker_test_state as right_state
    where left_state.key = 'claim-one'
      and right_state.key = 'claim-two'
  ),
  'each claim has a distinct lease identity and owner'
);

select public.outbox_complete_event(
  '42000000-0000-0000-0000-000000000001'::uuid,
  (select (payload ->> 'lease_token')::uuid from pg_temp.worker_test_state where key = 'claim-one')
);

reset role;

select is(
  (
    select status::text
    from content.outbox_events
    where id = '42000000-0000-0000-0000-000000000001'::uuid
      and delivered_at is not null
      and lease_token is null
  ),
  'delivered',
  'valid completion terminalizes and clears the lease'
);

set local role service_role;

select throws_ok(
  format(
    'select public.outbox_complete_event(%L::uuid, %L::uuid)',
    '42000000-0000-0000-0000-000000000001',
    (select payload ->> 'lease_token' from pg_temp.worker_test_state where key = 'claim-one')
  ),
  'P0002',
  'outbox_lease_not_found_or_expired',
  'a terminal event rejects reuse of its old lease token'
);

select public.outbox_complete_event(
  '42000000-0000-0000-0000-000000000002'::uuid,
  (select (payload ->> 'lease_token')::uuid from pg_temp.worker_test_state where key = 'claim-two')
);

reset role;

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where id in (
      '42000000-0000-0000-0000-000000000001'::uuid,
      '42000000-0000-0000-0000-000000000002'::uuid
    )
      and status = 'delivered'::content.outbox_status
  ),
  2,
  'both disjoint claims can complete independently'
);

insert into content.outbox_events (
  id,
  aggregate_type,
  aggregate_id,
  event_type,
  payload,
  deduplication_key,
  available_at
)
values (
  '42000000-0000-0000-0000-000000000003'::uuid,
  'test',
  'expired-lease',
  'test.expired',
  '{}'::jsonb,
  'test:lease:expired',
  now() - interval '1 hour'
);

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
select 'expired-old', claimed
from public.outbox_claim_events('worker-old', 1, 30) as claimed;

reset role;

update content.outbox_events
set
  locked_at = now() - interval '20 seconds',
  locked_until = now() - interval '1 second'
where id = '42000000-0000-0000-0000-000000000003'::uuid;

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
select 'expired-new', claimed
from public.outbox_claim_events('worker-new', 1, 30) as claimed;

select is(
  (select payload ->> 'id' from pg_temp.worker_test_state where key = 'expired-new'),
  '42000000-0000-0000-0000-000000000003',
  'an expired processing lease is reclaimable'
);

select ok(
  (
    select old_claim.payload ->> 'lease_token' <> new_claim.payload ->> 'lease_token'
      and (new_claim.payload ->> 'attempts')::integer = 2
    from pg_temp.worker_test_state as old_claim
    cross join pg_temp.worker_test_state as new_claim
    where old_claim.key = 'expired-old'
      and new_claim.key = 'expired-new'
  ),
  'reclaim assigns a new token and increments attempts'
);

select throws_ok(
  format(
    'select public.outbox_complete_event(%L::uuid, %L::uuid)',
    '42000000-0000-0000-0000-000000000003',
    (select payload ->> 'lease_token' from pg_temp.worker_test_state where key = 'expired-old')
  ),
  'P0002',
  'outbox_lease_not_found_or_expired',
  'a reclaimed event denies the stale worker token'
);

select public.outbox_complete_event(
  '42000000-0000-0000-0000-000000000003'::uuid,
  (select (payload ->> 'lease_token')::uuid from pg_temp.worker_test_state where key = 'expired-new')
);

reset role;

select is(
  (
    select status::text
    from content.outbox_events
    where id = '42000000-0000-0000-0000-000000000003'::uuid
      and delivered_at is not null
  ),
  'delivered',
  'the replacement lease can complete the reclaimed event'
);

insert into content.outbox_events (
  id,
  aggregate_type,
  aggregate_id,
  event_type,
  payload,
  deduplication_key,
  max_attempts,
  available_at
)
values (
  '42000000-0000-0000-0000-000000000004'::uuid,
  'test',
  'retry-event',
  'test.retry',
  '{}'::jsonb,
  'test:retry:event',
  2,
  now() - interval '1 hour'
);

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
select 'retry-one', claimed
from public.outbox_claim_events('worker-retry', 1, 30) as claimed;

select public.outbox_fail_event(
  '42000000-0000-0000-0000-000000000004'::uuid,
  (select (payload ->> 'lease_token')::uuid from pg_temp.worker_test_state where key = 'retry-one'),
  'transient receiver failure'
);

reset role;

select ok(
  (
    select status = 'failed'::content.outbox_status
      and attempts = 1
      and available_at > now()
      and dead_lettered_at is null
      and lease_token is null
    from content.outbox_events
    where id = '42000000-0000-0000-0000-000000000004'::uuid
  ),
  'a transient failure clears the lease and applies positive backoff'
);

update content.outbox_events
set available_at = now() - interval '1 second'
where id = '42000000-0000-0000-0000-000000000004'::uuid;

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
select 'retry-two', claimed
from public.outbox_claim_events('worker-retry', 1, 30) as claimed;

select is(
  (
    select (payload ->> 'attempts')::integer
    from pg_temp.worker_test_state
    where key = 'retry-two'
  ),
  2,
  'a due failed event can be claimed for its bounded final attempt'
);

select public.outbox_fail_event(
  '42000000-0000-0000-0000-000000000004'::uuid,
  (select (payload ->> 'lease_token')::uuid from pg_temp.worker_test_state where key = 'retry-two'),
  'permanent receiver failure'
);

reset role;

select ok(
  (
    select status = 'failed'::content.outbox_status
      and attempts = max_attempts
      and dead_lettered_at is not null
      and lease_token is null
      and last_error = 'permanent receiver failure'
    from content.outbox_events
    where id = '42000000-0000-0000-0000-000000000004'::uuid
  ),
  'the maximum failed attempt enters terminal dead-letter state'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where id in (
      '42000000-0000-0000-0000-000000000001'::uuid,
      '42000000-0000-0000-0000-000000000002'::uuid,
      '42000000-0000-0000-0000-000000000003'::uuid,
      '42000000-0000-0000-0000-000000000004'::uuid
    )
      and delivered_at is null
      and dead_lettered_at is null
  ),
  0,
  'delivered and dead-lettered fixture events are terminal and ineligible for reclaim'
);

-- -------------------------------------------------------------------------
-- Service-only media publication, rejection, purge races, and stale sweep.
-- -------------------------------------------------------------------------

insert into content.exhibitions (id, created_by, updated_by)
values (
  'worker-media',
  '00000000-0000-0000-0000-000000000401'::uuid,
  '00000000-0000-0000-0000-000000000401'::uuid
);

insert into content.exhibition_versions (
  id,
  exhibition_id,
  version_number,
  revision,
  status,
  name_ko,
  created_by,
  updated_by
)
values (
  '44000000-0000-0000-0000-000000000100'::uuid,
  'worker-media',
  1,
  1,
  'draft'::content.exhibition_version_status,
  '워커 미디어',
  '00000000-0000-0000-0000-000000000401'::uuid,
  '00000000-0000-0000-0000-000000000401'::uuid
);

insert into content.media_assets (
  id,
  status,
  bucket_id,
  object_path,
  delivery_bucket_id,
  delivery_object_path,
  mime_type,
  byte_size,
  metadata,
  uploaded_by
)
values
  (
    '44000000-0000-0000-0000-000000000001'::uuid,
    'ready'::content.media_asset_status,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000001/original.png',
    'exhibition-images',
    'cms/44000000-0000-0000-0000-000000000001/original.png',
    'image/png',
    24,
    '{"fixture":"publication"}'::jsonb,
    '00000000-0000-0000-0000-000000000401'::uuid
  ),
  (
    '44000000-0000-0000-0000-000000000002'::uuid,
    'ready'::content.media_asset_status,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000002/original.jpg',
    null,
    null,
    'image/jpeg',
    100,
    '{"fixture":"rejection"}'::jsonb,
    '00000000-0000-0000-0000-000000000401'::uuid
  ),
  (
    '44000000-0000-0000-0000-000000000003'::uuid,
    'orphaned'::content.media_asset_status,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000003/original.webp',
    'exhibition-images',
    'cms/44000000-0000-0000-0000-000000000003/original.webp',
    'image/webp',
    200,
    '{"fixture":"cleanup"}'::jsonb,
    '00000000-0000-0000-0000-000000000401'::uuid
  );

insert into content.exhibition_version_media (
  version_id,
  media_id,
  role,
  sort_order,
  created_by
)
values (
  '44000000-0000-0000-0000-000000000100'::uuid,
  '44000000-0000-0000-0000-000000000001'::uuid,
  'gallery'::content.media_role,
  1,
  '00000000-0000-0000-0000-000000000401'::uuid
);

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
values (
  'media-published',
  public.outbox_mark_media_published(
    '44000000-0000-0000-0000-000000000001'::uuid,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000001/original.png',
    'exhibition-images',
    'cms/44000000-0000-0000-0000-000000000001/original.png',
    'https://project.example/storage/v1/object/public/exhibition-images/cms/44000000-0000-0000-0000-000000000001/original.png',
    'image/png',
    24,
    1600,
    1067,
    repeat('a', 64),
    repeat('a', 64)
  )
);

select is(
  (select payload ->> 'status' from pg_temp.worker_test_state where key = 'media-published'),
  'published',
  'service worker transitions a referenced ready asset to published'
);

reset role;

select ok(
  (
    select status = 'published'::content.media_asset_status
      and mime_type = 'image/png'
      and byte_size = 24
      and width = 1600
      and height = 1067
      and checksum_sha256 = repeat('a', 64)
      and published_at is not null
      and public_url is not null
    from content.media_assets
    where id = '44000000-0000-0000-0000-000000000001'::uuid
  ),
  'detected media metadata becomes authoritative in one locked transition'
);

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
values (
  'media-published-replay',
  public.outbox_mark_media_published(
    '44000000-0000-0000-0000-000000000001'::uuid,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000001/original.png',
    'exhibition-images',
    'cms/44000000-0000-0000-0000-000000000001/original.png',
    'https://project.example/storage/v1/object/public/exhibition-images/cms/44000000-0000-0000-0000-000000000001/original.png',
    'image/png',
    24,
    1600,
    1067,
    repeat('a', 64),
    repeat('a', 64)
  )
);

select is(
  (select payload ->> 'replayed' from pg_temp.worker_test_state where key = 'media-published-replay'),
  'true',
  'an exact publication retry is a no-op replay'
);

reset role;

select is(
  (
    select count(*)::integer
    from content.audit_log
    where action = 'media.published'
      and entity_id = '44000000-0000-0000-0000-000000000001'
  ),
  1,
  'publication replay creates no duplicate audit entry'
);

set local role service_role;

select throws_ok(
  $$
    select public.outbox_mark_media_published(
      '44000000-0000-0000-0000-000000000001'::uuid,
      'exhibition-media',
      'drafts/worker-media/44000000-0000-0000-0000-000000000001/original.png',
      'exhibition-images',
      'cms/44000000-0000-0000-0000-000000000001/original.png',
      'https://project.example/storage/v1/object/public/exhibition-images/cms/44000000-0000-0000-0000-000000000001/original.png',
      'image/png',
      24,
      1600,
      1067,
      repeat('a', 64),
      repeat('b', 64)
    )
  $$,
  '22023',
  'media_delivery_checksum_mismatch',
  'publication refuses a destination whose bytes do not match the source'
);

insert into pg_temp.worker_test_state (key, payload)
values (
  'media-rejected',
  public.outbox_reject_media(
    '44000000-0000-0000-0000-000000000002'::uuid,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000002/original.jpg',
    'jpeg_invalid_segment',
    'invalid JPEG fixture'
  )
);

select is(
  (select payload ->> 'status' from pg_temp.worker_test_state where key = 'media-rejected'),
  'rejected',
  'deterministically invalid bytes enter terminal rejected state'
);

insert into pg_temp.worker_test_state (key, payload)
values (
  'media-rejected-replay',
  public.outbox_reject_media(
    '44000000-0000-0000-0000-000000000002'::uuid,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000002/original.jpg',
    'jpeg_invalid_segment',
    'invalid JPEG fixture'
  )
);

reset role;

select ok(
  (
    select payload ->> 'replayed' = 'true'
    from pg_temp.worker_test_state
    where key = 'media-rejected-replay'
  )
  and (
    select count(*) = 1
    from content.audit_log
    where action = 'media.rejected'
      and entity_id = '44000000-0000-0000-0000-000000000002'
  ),
  'rejection replay is terminal and creates one audit entry'
);

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
values (
  'cleanup-prepared',
  public.outbox_prepare_media_cleanup(
    '44000000-0000-0000-0000-000000000003'::uuid,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000003/original.webp',
    'exhibition-images',
    'cms/44000000-0000-0000-0000-000000000003/original.webp'
  )
);

select ok(
  (
    select payload ->> 'purge_token' is not null
      and payload ->> 'already_purged' = 'false'
    from pg_temp.worker_test_state
    where key = 'cleanup-prepared'
  ),
  'cleanup preparation locks the orphan and returns a purge identity'
);

reset role;

insert into content.exhibition_version_media (
  version_id,
  media_id,
  role,
  sort_order,
  created_by
)
values (
  '44000000-0000-0000-0000-000000000100'::uuid,
  '44000000-0000-0000-0000-000000000003'::uuid,
  'gallery'::content.media_role,
  2,
  '00000000-0000-0000-0000-000000000401'::uuid
);

set local role service_role;

select throws_ok(
  format(
    $sql$
      select public.outbox_finalize_media_cleanup(
        '44000000-0000-0000-0000-000000000003'::uuid,
        %L::uuid,
        'exhibition-media',
        'drafts/worker-media/44000000-0000-0000-0000-000000000003/original.webp',
        'exhibition-images',
        'cms/44000000-0000-0000-0000-000000000003/original.webp'
      )
    $sql$,
    (select payload ->> 'purge_token' from pg_temp.worker_test_state where key = 'cleanup-prepared')
  ),
  '55000',
  'media_asset_is_still_referenced',
  'cleanup finalization rechecks references after Storage deletion could occur'
);

reset role;

delete from content.exhibition_version_media
where version_id = '44000000-0000-0000-0000-000000000100'::uuid
  and media_id = '44000000-0000-0000-0000-000000000003'::uuid;

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
values (
  'cleanup-finalized',
  public.outbox_finalize_media_cleanup(
    '44000000-0000-0000-0000-000000000003'::uuid,
    (select (payload ->> 'purge_token')::uuid from pg_temp.worker_test_state where key = 'cleanup-prepared'),
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000003/original.webp',
    'exhibition-images',
    'cms/44000000-0000-0000-0000-000000000003/original.webp'
  )
);

select is(
  (select payload ->> 'replayed' from pg_temp.worker_test_state where key = 'cleanup-finalized'),
  'false',
  'the valid purge token finalizes an unreferenced orphan'
);

reset role;

select ok(
  (
    select purged_at is not null
      and public_url is null
      and bucket_id = 'exhibition-media'
      and object_path = 'drafts/worker-media/44000000-0000-0000-0000-000000000003/original.webp'
      and delivery_bucket_id = 'exhibition-images'
      and delivery_object_path = 'cms/44000000-0000-0000-0000-000000000003/original.webp'
      and metadata = '{"fixture":"cleanup"}'::jsonb
    from content.media_assets
    where id = '44000000-0000-0000-0000-000000000003'::uuid
  ),
  'purge retains canonical paths and technical metadata while stamping purged_at'
);

set local role service_role;

select is(
  public.outbox_finalize_media_cleanup(
    '44000000-0000-0000-0000-000000000003'::uuid,
    (select (payload ->> 'purge_token')::uuid from pg_temp.worker_test_state where key = 'cleanup-prepared'),
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000003/original.webp',
    'exhibition-images',
    'cms/44000000-0000-0000-0000-000000000003/original.webp'
  ) ->> 'replayed',
  'true',
  'an exact cleanup finalization retry is a no-op replay'
);

reset role;

insert into content.media_assets (
  id,
  status,
  bucket_id,
  object_path,
  mime_type,
  byte_size,
  metadata,
  uploaded_by,
  created_at,
  updated_at
)
values
  (
    '44000000-0000-0000-0000-000000000004'::uuid,
    'pending_upload'::content.media_asset_status,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000004/original.jpg',
    'image/jpeg',
    300,
    '{"fixture":"stale"}'::jsonb,
    '00000000-0000-0000-0000-000000000401'::uuid,
    now() - interval '48 hours',
    now() - interval '48 hours'
  ),
  (
    '44000000-0000-0000-0000-000000000005'::uuid,
    'pending_upload'::content.media_asset_status,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000005/original.png',
    'image/png',
    400,
    '{"fixture":"recent"}'::jsonb,
    '00000000-0000-0000-0000-000000000401'::uuid,
    now() - interval '1 hour',
    now() - interval '1 hour'
  ),
  (
    '44000000-0000-0000-0000-000000000006'::uuid,
    'ready'::content.media_asset_status,
    'exhibition-media',
    'drafts/worker-media/44000000-0000-0000-0000-000000000006/original.webp',
    'image/webp',
    500,
    '{"fixture":"referenced"}'::jsonb,
    '00000000-0000-0000-0000-000000000401'::uuid,
    now() - interval '48 hours',
    now() - interval '48 hours'
  );

insert into content.exhibition_version_media (
  version_id,
  media_id,
  role,
  sort_order,
  created_by
)
values (
  '44000000-0000-0000-0000-000000000100'::uuid,
  '44000000-0000-0000-0000-000000000006'::uuid,
  'gallery'::content.media_role,
  2,
  '00000000-0000-0000-0000-000000000401'::uuid
);

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
select 'sweep-result', swept
from public.outbox_sweep_stale_media(now() - interval '24 hours', 10) as swept;

select is(
  (select payload ->> 'asset_id' from pg_temp.worker_test_state where key = 'sweep-result'),
  '44000000-0000-0000-0000-000000000004',
  'stale sweep selects only the old unreferenced upload'
);

reset role;

select ok(
  (
    select status = 'orphaned'::content.media_asset_status
    from content.media_assets
    where id = '44000000-0000-0000-0000-000000000004'::uuid
  )
  and (
    select count(*) = 1
    from content.outbox_events
    where deduplication_key =
      'media:44000000-0000-0000-0000-000000000004:cleanup_requested'
      and event_type = 'media.cleanup_requested'
  ),
  'stale sweep orphans the asset and emits one deduplicated cleanup event'
);

set local role service_role;

select is(
  (
    select count(*)::integer
    from public.outbox_sweep_stale_media(now() - interval '24 hours', 10)
  ),
  0,
  'repeating the stale sweep creates no duplicate transition or event'
);

reset role;

select ok(
  (
    select status = 'pending_upload'::content.media_asset_status
    from content.media_assets
    where id = '44000000-0000-0000-0000-000000000005'::uuid
  )
  and (
    select status = 'ready'::content.media_asset_status
    from content.media_assets
    where id = '44000000-0000-0000-0000-000000000006'::uuid
  ),
  'recent and referenced media remain untouched by the stale sweep'
);

insert into content.media_assets (
  id,
  status,
  bucket_id,
  object_path,
  delivery_bucket_id,
  delivery_object_path,
  mime_type,
  byte_size,
  metadata,
  uploaded_by
)
values (
  '44000000-0000-0000-0000-000000000007'::uuid,
  'ready'::content.media_asset_status,
  'exhibition-media',
  'drafts/worker-media/44000000-0000-0000-0000-000000000007/original.jpg',
  'exhibition-images',
  'cms/44000000-0000-0000-0000-000000000007/original.jpg',
  'image/jpeg',
  600,
  '{"fixture":"dead-letter"}'::jsonb,
  '00000000-0000-0000-0000-000000000401'::uuid
);

insert into content.exhibition_version_media (
  version_id,
  media_id,
  role,
  sort_order,
  created_by
)
values (
  '44000000-0000-0000-0000-000000000100'::uuid,
  '44000000-0000-0000-0000-000000000007'::uuid,
  'gallery'::content.media_role,
  3,
  '00000000-0000-0000-0000-000000000401'::uuid
);

insert into content.outbox_events (
  id,
  aggregate_type,
  aggregate_id,
  event_type,
  payload,
  deduplication_key,
  max_attempts,
  available_at,
  created_at
)
values (
  '44000000-0000-0000-0000-000000000107'::uuid,
  'media_asset',
  '44000000-0000-0000-0000-000000000007',
  'media.publish_requested',
  jsonb_build_object(
    'asset_id', '44000000-0000-0000-0000-000000000007'::uuid,
    'source_bucket_id', 'exhibition-media',
    'source_object_path',
      'drafts/worker-media/44000000-0000-0000-0000-000000000007/original.jpg',
    'delivery_bucket_id', 'exhibition-images',
    'delivery_object_path',
      'cms/44000000-0000-0000-0000-000000000007/original.jpg'
  ),
  'media:44000000-0000-0000-0000-000000000007:publish_requested',
  1,
  now() - interval '72 hours',
  now() - interval '72 hours'
);

set local role service_role;

insert into pg_temp.worker_test_state (key, payload)
select 'media-dead-letter-claim', claimed
from public.outbox_claim_events('worker-dead-letter', 1, 30) as claimed;

select is(
  (
    select payload ->> 'id'
    from pg_temp.worker_test_state
    where key = 'media-dead-letter-claim'
  ),
  '44000000-0000-0000-0000-000000000107',
  'publication event is claimed for its only configured attempt'
);

select public.outbox_fail_event(
  '44000000-0000-0000-0000-000000000107'::uuid,
  (
    select (payload ->> 'lease_token')::uuid
    from pg_temp.worker_test_state
    where key = 'media-dead-letter-claim'
  ),
  'storage receiver remained unavailable'
);

reset role;

select ok(
  (
    select status = 'failed'::content.outbox_status
      and attempts = max_attempts
      and dead_lettered_at is not null
      and last_error = 'storage receiver remained unavailable'
    from content.outbox_events
    where id = '44000000-0000-0000-0000-000000000107'::uuid
  ),
  'exhausted media publication enters terminal outbox dead-letter state'
);

select ok(
  (
    select status = 'rejected'::content.media_asset_status
      and metadata ->> 'rejection_reason' = 'publish_delivery_exhausted'
      and metadata ->> 'editor_instruction' = 'Remove this image and upload it again.'
    from content.media_assets
    where id = '44000000-0000-0000-0000-000000000007'::uuid
  )
  and (
    select count(*) = 1
    from content.audit_log
    where action = 'media.publish_dead_lettered'
      and entity_id = '44000000-0000-0000-0000-000000000007'
      and metadata ->> 'next_action' = 'remove_and_reupload'
  ),
  'dead-letter rejection stops UI polling and records remove/re-upload guidance'
);

select * from finish();
rollback;
