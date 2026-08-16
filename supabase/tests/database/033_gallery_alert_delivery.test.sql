begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(26);

select has_table(
  'content_private',
  'gallery_alert_push_tokens',
  'provider addresses are stored in the private schema'
);
select has_table(
  'content_private',
  'gallery_alert_delivery_jobs',
  'publication fan-out uses a durable private queue'
);
select has_pk(
  'content_private',
  'gallery_alert_push_tokens',
  'one current provider address exists per installation'
);
select col_is_fk(
  'content_private',
  'gallery_alert_delivery_jobs',
  'installation_id',
  'delivery jobs retain installation ownership'
);
select ok(
  (
    select relrowsecurity
    from pg_catalog.pg_class
    where oid = 'content_private.gallery_alert_push_tokens'::regclass
  ) and (
    select relrowsecurity
    from pg_catalog.pg_class
    where oid = 'content_private.gallery_alert_delivery_jobs'::regclass
  ),
  'push tokens and delivery jobs retain RLS defense in depth'
);
select ok(
  not has_table_privilege(
    'anon',
    'content_private.gallery_alert_push_tokens',
    'SELECT, INSERT, UPDATE, DELETE'
  ) and not has_table_privilege(
    'authenticated',
    'content_private.gallery_alert_delivery_jobs',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'client roles cannot read tokens or delivery state directly'
);
select ok(
  to_regprocedure(
    'public.register_gallery_alert_push_token(uuid,text,text,text,text,integer)'
  ) is not null
  and to_regprocedure(
    'public.claim_gallery_alert_delivery_jobs(uuid,text,integer,integer)'
  ) is not null
  and to_regprocedure(
    'public.complete_gallery_alert_delivery_job(bigint,uuid)'
  ) is not null
  and to_regprocedure(
    'public.fail_gallery_alert_delivery_job(bigint,uuid,text,boolean,boolean)'
  ) is not null,
  'narrow client registration and service delivery RPCs exist'
);
select ok(
  has_function_privilege(
    'anon',
    'public.register_gallery_alert_push_token(uuid,text,text,text,text,integer)',
    'EXECUTE'
  ) and not has_function_privilege(
    'anon',
    'public.claim_gallery_alert_delivery_jobs(uuid,text,integer,integer)',
    'EXECUTE'
  ) and has_function_privilege(
    'service_role',
    'public.claim_gallery_alert_delivery_jobs(uuid,text,integer,integer)',
    'EXECUTE'
  ),
  'registration is client-callable while delivery remains service-only'
);
select ok(
  exists (
    select 1
    from pg_catalog.pg_trigger
    where tgrelid = 'content.outbox_events'::regclass
      and tgname = 'outbox_events_enrich_gallery_publication'
      and not tgisinternal
  ),
  'future publication events are enriched before delivery'
);

insert into content.galleries (id, name_ko, name_en, status)
values (
  'b1000000-0000-0000-0000-000000000001',
  '푸시 테스트 갤러리',
  'Push Test Gallery',
  'active'
);

set local role anon;

select is(
  public.register_gallery_alert_installation(
    'b2000000-0000-0000-0000-000000000001',
    'push-installation-secret-000000000000000001',
    'ios',
    'ko-KR',
    0
  ) ->> 'revision',
  '1',
  'delivery fixture can register an anonymous installation'
);
select is(
  public.set_gallery_alert_subscription(
    'b2000000-0000-0000-0000-000000000001',
    'push-installation-secret-000000000000000001',
    'b1000000-0000-0000-0000-000000000001',
    true,
    0
  ) #>> '{subscriptions,0,enabled}',
  'true',
  'delivery requires an explicit enabled gallery subscription'
);
select is(
  public.register_gallery_alert_push_token(
    'b2000000-0000-0000-0000-000000000001',
    'push-installation-secret-000000000000000001',
    'apns',
    repeat('a', 64),
    'sandbox',
    0
  ) ->> 'push_token_revision',
  '1',
  'the proven installation can register its APNs address'
);
select is(
  public.register_gallery_alert_push_token(
    'b2000000-0000-0000-0000-000000000001',
    'push-installation-secret-000000000000000001',
    'apns',
    repeat('a', 64),
    'sandbox',
    0
  ) ->> 'push_token_revision',
  '1',
  'an ambiguous token-registration retry is idempotent'
);
select throws_ok(
  $$select public.register_gallery_alert_push_token(
    'b2000000-0000-0000-0000-000000000001',
    'wrong-push-installation-secret-000000000001',
    'apns', repeat('b', 64), 'sandbox', 1
  )$$,
  '42501',
  'gallery_alert_installation_unauthorized',
  'a wrong installation secret cannot rotate a token'
);
select throws_ok(
  $$select public.register_gallery_alert_push_token(
    'b2000000-0000-0000-0000-000000000001',
    'push-installation-secret-000000000000000001',
    'apns', 'not-a-device-token', 'sandbox', 1
  )$$,
  '22023',
  'push_token_invalid',
  'malformed provider addresses are rejected'
);

reset role;

select ok(
  (
    select provider_token = repeat('a', 64)
      and token_digest <> provider_token
    from content_private.gallery_alert_push_tokens
    where installation_id = 'b2000000-0000-0000-0000-000000000001'
  ),
  'the private worker retains the address and a non-reversible digest'
);

insert into content.outbox_events (
  id,
  aggregate_type,
  aggregate_id,
  event_type,
  payload,
  deduplication_key
) values (
  'b3000000-0000-4000-8000-000000000001',
  'exhibition',
  'push-exhibition-one',
  'exhibition.published',
  jsonb_build_object(
    'exhibition_id', 'push-exhibition-one',
    'version_id', 'b4000000-0000-4000-8000-000000000001',
    'gallery_id', 'b1000000-0000-0000-0000-000000000001',
    'gallery_name_ko', '푸시 테스트 갤러리',
    'gallery_name_en', 'Push Test Gallery',
    'exhibition_name_ko', '새로운 전시',
    'exhibition_name_en', 'A New Exhibition'
  ),
  'push-test-publication-one'
);

set local role service_role;

create temporary table claimed_gallery_alerts as
select public.claim_gallery_alert_delivery_jobs(
  'b3000000-0000-4000-8000-000000000001',
  'test-worker-one',
  120,
  25
) as result;

select is(
  (select jsonb_array_length(result -> 'jobs') from claimed_gallery_alerts),
  1,
  'one opted-in installation receives one idempotent fan-out job'
);
select is(
  (select result #>> '{jobs,0,provider}' from claimed_gallery_alerts),
  'apns',
  'the claim returns the provider only to service role'
);
select is(
  (select result #>> '{jobs,0,provider_token}' from claimed_gallery_alerts),
  repeat('a', 64),
  'the service claim receives the current private address'
);
select is(
  (select result #>> '{jobs,0,deduplication_key}' from claimed_gallery_alerts),
  'gallery:b1000000-0000-0000-0000-000000000001:exhibition:push-exhibition-one:version:b4000000-0000-4000-8000-000000000001:installation:b2000000-0000-0000-0000-000000000001',
  'the job identity is stable across retries'
);
select lives_ok(
  format(
    'select public.complete_gallery_alert_delivery_job(%s, %L)',
    (select result #>> '{jobs,0,job_id}' from claimed_gallery_alerts),
    (select result #>> '{jobs,0,lease_token}' from claimed_gallery_alerts)
  ),
  'the current lease can complete a provider delivery'
);
select is(
  public.claim_gallery_alert_delivery_jobs(
    'b3000000-0000-4000-8000-000000000001',
    'test-worker-two',
    120,
    25
  ) #>> '{jobs}',
  '[]',
  'a delivered event does not fan out twice'
);

reset role;

update content_private.gallery_alert_delivery_jobs
set status = 'pending',
    attempts = 0,
    available_at = now(),
    lease_token = null,
    lease_owner = null,
    locked_until = null,
    delivered_at = null
where outbox_event_id = 'b3000000-0000-4000-8000-000000000001';

set local role service_role;

create temporary table failed_gallery_alerts as
select public.claim_gallery_alert_delivery_jobs(
  'b3000000-0000-4000-8000-000000000001',
  'test-worker-three',
  120,
  25
) as result;

select lives_ok(
  format(
    'select public.fail_gallery_alert_delivery_job(%s, %L, %L, false, true)',
    (select result #>> '{jobs,0,job_id}' from failed_gallery_alerts),
    (select result #>> '{jobs,0,lease_token}' from failed_gallery_alerts),
    'apns_unregistered'
  ),
  'an invalid-token provider result is durably recorded'
);

reset role;

select is(
  (
    select status
    from content_private.gallery_alert_push_tokens
    where installation_id = 'b2000000-0000-0000-0000-000000000001'
  ),
  'invalid',
  'invalid provider responses disable the rotated address'
);
select is(
  (
    select status
    from content_private.gallery_alert_delivery_jobs
    where outbox_event_id = 'b3000000-0000-4000-8000-000000000001'
  ),
  'dead',
  'invalid-token jobs are dead-lettered without retry'
);
select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    where procedure.oid in (
      to_regprocedure(
        'public.claim_gallery_alert_delivery_jobs(uuid,text,integer,integer)'
      ),
      to_regprocedure(
        'public.complete_gallery_alert_delivery_job(bigint,uuid)'
      ),
      to_regprocedure(
        'public.fail_gallery_alert_delivery_job(bigint,uuid,text,boolean,boolean)'
      )
    )
      and privilege.grantee in (
        0,
        (select oid from pg_catalog.pg_roles where rolname = 'anon'),
        (select oid from pg_catalog.pg_roles where rolname = 'authenticated')
      )
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC and client roles receive no worker RPC execution'
);

select * from finish();
rollback;
