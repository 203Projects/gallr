begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(17);

select has_function(
  'content_private',
  'enqueue_public_site_rebuild_request',
  array[]::text[],
  'private rebuild enqueue trigger function exists'
);

select has_trigger(
  'content',
  'outbox_events',
  'outbox_events_enqueue_public_site_rebuild',
  'lifecycle outbox inserts enqueue a public-site rebuild'
);

select ok(
  not has_function_privilege(
    'service_role',
    'content_private.enqueue_public_site_rebuild_request()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'content_private.enqueue_public_site_rebuild_request()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'content_private.enqueue_public_site_rebuild_request()',
    'EXECUTE'
  )
  and not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    where procedure.oid =
      'content_private.enqueue_public_site_rebuild_request()'::regprocedure
      and privilege.grantee = 0
  ),
  'the trigger helper exposes no callable client or service-role surface'
);

select ok(
  (
    select not procedure.prosecdef
      and procedure.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as procedure
    where procedure.oid =
      'content_private.enqueue_public_site_rebuild_request()'::regprocedure
  ),
  'the trigger helper is security-invoker and pins an empty search path'
);

delete from content.outbox_events
where event_type = 'public_site.rebuild_requested'
   or id in (
     '80000000-0000-4000-8000-000000000001'::uuid,
     '80000000-0000-4000-8000-000000000002'::uuid,
     '80000000-0000-4000-8000-000000000003'::uuid,
     '80000000-0000-4000-8000-000000000004'::uuid,
     '80000000-0000-4000-8000-000000000005'::uuid,
     '80000000-0000-4000-8000-000000000006'::uuid
   );

insert into content.outbox_events (
  id, aggregate_type, aggregate_id, event_type, payload, deduplication_key
) values (
  '80000000-0000-4000-8000-000000000001',
  'exhibition',
  'exhibition-one',
  'exhibition.published',
  '{}'::jsonb,
  'test-rebuild-published'
);

select is(
  (
    select payload ->> 'public_site_rebuild_queued'
    from content.outbox_events
    where id = '80000000-0000-4000-8000-000000000001'::uuid
  ),
  'true',
  'the lifecycle event records that its durable rebuild was queued'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
  ),
  1,
  'the first lifecycle event creates one rebuild request'
);

select ok(
  (
    select available_at >= created_at + interval '29 seconds'
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
  ),
  'the rebuild request waits for the fixed quiet window'
);

select is(
  (
    select payload ->> 'source_event_count'
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
  ),
  '1',
  'the first request records one bounded source event'
);

update content.outbox_events
set available_at = clock_timestamp() - interval '1 minute'
where event_type = 'public_site.rebuild_requested';

insert into content.outbox_events (
  id, aggregate_type, aggregate_id, event_type, payload, deduplication_key
) values (
  '80000000-0000-4000-8000-000000000002',
  'exhibition',
  'exhibition-two',
  'exhibition.archived',
  '{}'::jsonb,
  'test-rebuild-archived'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
  ),
  1,
  'a second lifecycle event reuses the pending rebuild request'
);

select ok(
  (
    select available_at >= clock_timestamp() + interval '29 seconds'
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
  ),
  'a newer lifecycle event extends the quiet window'
);

select is(
  (
    select payload ->> 'source_event_count'
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
  ),
  '2',
  'the coalesced request records both source events'
);

insert into content.outbox_events (
  id, aggregate_type, aggregate_id, event_type, payload, deduplication_key
) values (
  '80000000-0000-4000-8000-000000000003',
  'gallery',
  'gallery-one',
  'gallery.claim_requested',
  '{}'::jsonb,
  'test-rebuild-non-lifecycle'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
  ),
  1,
  'known non-lifecycle events do not request a public rebuild'
);

update content.outbox_events
set
  status = 'processing'::content.outbox_status,
  attempts = 1,
  lease_token = '80000000-0000-4000-8000-000000000010'::uuid,
  lease_owner = 'rebuild-test',
  locked_at = clock_timestamp(),
  locked_until = clock_timestamp() + interval '5 minutes'
where event_type = 'public_site.rebuild_requested';

insert into content.outbox_events (
  id, aggregate_type, aggregate_id, event_type, payload, deduplication_key
) values (
  '80000000-0000-4000-8000-000000000004',
  'exhibition',
  'exhibition-three',
  'exhibition.restored',
  '{}'::jsonb,
  'test-rebuild-restored'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
      and status = 'processing'::content.outbox_status
  ),
  1,
  'the in-flight rebuild remains untouched'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
      and status = 'pending'::content.outbox_status
  ),
  1,
  'an edit during processing creates one follow-up rebuild'
);

insert into content.outbox_events (
  id, aggregate_type, aggregate_id, event_type, payload, deduplication_key
) values (
  '80000000-0000-4000-8000-000000000005',
  'exhibition',
  'exhibition-four',
  'exhibition.published',
  '{}'::jsonb,
  'test-rebuild-burst-one'
);

insert into content.outbox_events (
  id, aggregate_type, aggregate_id, event_type, payload, deduplication_key
) values (
  '80000000-0000-4000-8000-000000000006',
  'exhibition',
  'exhibition-five',
  'exhibition.archived',
  '{}'::jsonb,
  'test-rebuild-burst-two'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
      and status = 'pending'::content.outbox_status
  ),
  1,
  'a multi-row burst retains exactly one pending rebuild request'
);

select is(
  (
    select payload ->> 'source_event_count'
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
      and status = 'pending'::content.outbox_status
  ),
  '3',
  'the follow-up request coalesces every event in the later burst'
);

select is(
  (
    select payload ->> 'latest_event_id'
    from content.outbox_events
    where event_type = 'public_site.rebuild_requested'
      and status = 'pending'::content.outbox_status
  ),
  '80000000-0000-4000-8000-000000000006',
  'the coalesced request records the latest committed lifecycle event'
);

select * from finish();
rollback;
