begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(33);

-- Storage and least privilege ------------------------------------------------

select has_table(
  'content_private',
  'gallery_alert_enrollment_quotas',
  'enrollment counters are stored outside the exposed schema'
);
select has_pk(
  'content_private',
  'gallery_alert_enrollment_quotas',
  'one counter exists per scope, key, and window'
);
select ok(
  (
    select relrowsecurity
    from pg_catalog.pg_class
    where oid = 'content_private.gallery_alert_enrollment_quotas'::regclass
  ),
  'the quota table retains RLS as defense in depth'
);
select ok(
  not has_table_privilege(
    'anon',
    'content_private.gallery_alert_enrollment_quotas',
    'SELECT, INSERT, UPDATE, DELETE'
  ) and not has_table_privilege(
    'authenticated',
    'content_private.gallery_alert_enrollment_quotas',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'client roles cannot read or forge enrollment counters'
);
select ok(
  not has_function_privilege(
    'anon',
    'content_private.consume_gallery_alert_enrollment_quota(text,text,integer)',
    'EXECUTE'
  ) and not has_function_privilege(
    'authenticated',
    'content_private.consume_gallery_alert_enrollment_budget(text,boolean)',
    'EXECUTE'
  ),
  'client roles cannot spend or reset enrollment budgets directly'
);
select ok(
  (
    select bool_and(
      procedure.prosecdef
        and procedure.proconfig = array['search_path=""']::text[]
    )
    from pg_catalog.pg_proc as procedure
    where procedure.oid in (
      to_regprocedure(
        'content_private.consume_gallery_alert_enrollment_quota(text,text,integer)'
      ),
      to_regprocedure(
        'content_private.consume_gallery_alert_enrollment_budget(text,boolean)'
      ),
      to_regprocedure(
        'public.service_register_gallery_alert_installation('
          || 'text,uuid,text,text,text,integer,uuid)'
      )
    )
  ),
  'every new privileged function is hardened SECURITY DEFINER code'
);
select ok(
  has_function_privilege(
    'service_role',
    'public.service_register_gallery_alert_installation('
      || 'text,uuid,text,text,text,integer,uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.service_register_gallery_alert_installation('
      || 'text,uuid,text,text,text,integer,uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.service_register_gallery_alert_installation('
      || 'text,uuid,text,text,text,integer,uuid)',
    'EXECUTE'
  ),
  'the trusted enrollment entry point is reachable only by the service role'
);
select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    where procedure.oid in (
      to_regprocedure(
        'content_private.consume_gallery_alert_enrollment_quota(text,text,integer)'
      ),
      to_regprocedure(
        'content_private.consume_gallery_alert_enrollment_budget(text,boolean)'
      ),
      to_regprocedure(
        'public.service_register_gallery_alert_installation('
          || 'text,uuid,text,text,text,integer,uuid)'
      )
    )
      and privilege.grantee = 0
  ),
  'no new function is left executable by PUBLIC'
);

-- The generic counter ---------------------------------------------------------

select is(
  content_private.consume_gallery_alert_enrollment_quota(
    'source', repeat('a', 64), 2
  ),
  1,
  'the first call in a window records one hit'
);
select is(
  content_private.consume_gallery_alert_enrollment_quota(
    'source', repeat('a', 64), 2
  ),
  2,
  'a second call increments the same window'
);
select throws_ok(
  $$select content_private.consume_gallery_alert_enrollment_quota(
    'source', repeat('a', 64), 2
  )$$,
  'P0001',
  'gallery_alert_rate_limited',
  'exceeding the limit is refused'
);
select is(
  content_private.consume_gallery_alert_enrollment_quota(
    'source', repeat('b', 64), 2
  ),
  1,
  'a different source keeps an independent budget'
);
select throws_ok(
  $$select content_private.consume_gallery_alert_enrollment_quota(
    'source', 'not-a-digest', 5
  )$$,
  '22023',
  'gallery_alert_quota_invalid',
  'a malformed quota key is refused'
);
select throws_ok(
  $$select content_private.consume_gallery_alert_enrollment_quota(
    'source', repeat('c', 64), 0
  )$$,
  '22023',
  'gallery_alert_quota_invalid',
  'a non-positive limit is refused'
);
select throws_ok(
  $$select content_private.consume_gallery_alert_enrollment_budget(
    'not-a-digest', true
  )$$,
  '22023',
  'gallery_alert_source_digest_invalid',
  'a trusted caller must present a server-derived source key'
);
select throws_ok(
  $$select content_private.consume_gallery_alert_enrollment_budget(
    null, true
  )$$,
  '22023',
  'gallery_alert_source_digest_invalid',
  'a trusted caller cannot omit the source key'
);
select lives_ok(
  $$select content_private.consume_gallery_alert_enrollment_budget(null, false)$$,
  'the legacy path spends only the aggregate budget'
);
select is(
  (
    select hits
    from content_private.gallery_alert_enrollment_quotas
    where scope = 'legacy_total'
      and window_start = date_trunc('hour', now())
  ),
  1,
  'legacy enrollment is metered separately from the trusted total'
);
select is(
  (
    select count(*)::integer
    from content_private.gallery_alert_enrollment_quotas
    where scope = 'trusted_total'
  ),
  0,
  'legacy traffic cannot exhaust the trusted budget'
);
select lives_ok(
  $$select content_private.consume_gallery_alert_enrollment_budget(
    repeat('d', 64), true
  )$$,
  'a trusted caller with a valid source key is accepted'
);
select is(
  (
    select hits
    from content_private.gallery_alert_enrollment_quotas
    where scope = 'trusted_total'
      and window_start = date_trunc('hour', now())
  ),
  1,
  'a trusted enrollment also spends the trusted total'
);

-- Registration metering --------------------------------------------------------

insert into content.galleries (id, name_ko, name_en, status)
values (
  'b1000000-0000-0000-0000-000000000001',
  '쿼터 테스트 갤러리',
  'Quota Test Gallery',
  'active'
);

insert into auth.users (id, email, raw_user_meta_data)
values (
  'b3000000-0000-0000-0000-000000000001',
  'quota-owner@example.invalid',
  '{}'::jsonb
);

select is(
  public.service_register_gallery_alert_installation(
    repeat('e', 64),
    'b2000000-0000-0000-0000-000000000001',
    'trusted-installation-secret-0000000000001',
    'ios',
    'ko-KR',
    0,
    'b3000000-0000-0000-0000-000000000001'
  ) ->> 'revision',
  '1',
  'the trusted entry point registers a new installation'
);
select is(
  (
    select user_id
    from content_private.gallery_alert_installations
    where id = 'b2000000-0000-0000-0000-000000000001'
  ),
  'b3000000-0000-0000-0000-000000000001'::uuid,
  'the verified account from the Edge Function is associated'
);
select is(
  (
    select hits
    from content_private.gallery_alert_enrollment_quotas
    where scope = 'source'
      and quota_key = repeat('e', 64)
      and window_start = date_trunc('hour', now())
  ),
  1,
  'creating a durable installation spends the source budget'
);
select is(
  public.service_register_gallery_alert_installation(
    repeat('e', 64),
    'b2000000-0000-0000-0000-000000000001',
    'trusted-installation-secret-0000000000001',
    'ios',
    'ko-KR',
    0,
    'b3000000-0000-0000-0000-000000000001'
  ) ->> 'revision',
  '1',
  'an unchanged refresh remains idempotent'
);
select is(
  (
    select hits
    from content_private.gallery_alert_enrollment_quotas
    where scope = 'source'
      and quota_key = repeat('e', 64)
      and window_start = date_trunc('hour', now())
  ),
  1,
  'a returning device refreshing itself is never rate limited'
);

set local role anon;

select is(
  public.register_gallery_alert_installation(
    'b2000000-0000-0000-0000-000000000002',
    'legacy-installation-secret-00000000000001',
    'android',
    'ko-KR',
    0
  ) ->> 'revision',
  '1',
  'an installed 1.9.x client still enrols through the public RPC'
);

reset role;

select is(
  (
    select hits
    from content_private.gallery_alert_enrollment_quotas
    where scope = 'legacy_total'
      and window_start = date_trunc('hour', now())
  ),
  2,
  'the legacy RPC path is metered against the legacy total'
);

-- Subscription ceiling ----------------------------------------------------------

select is(
  (
    content_private.gallery_alert_enrollment_limits()
      ->> 'installation_subscriptions'
  )::integer,
  200,
  'the per-installation subscription ceiling is published as one constant'
);

with ceiling_gallery as (
  insert into content.galleries (name_ko, name_en, status)
  select
    format('한도 갤러리 %s', generated.index),
    format('Ceiling Gallery %s', generated.index),
    'active'
  from generate_series(1, 200) as generated(index)
  returning id
)
insert into content_private.gallery_alert_subscriptions (
  installation_id, gallery_id, enabled
)
select
  'b2000000-0000-0000-0000-000000000001',
  ceiling_gallery.id,
  false
from ceiling_gallery;

select throws_ok(
  $$select public.set_gallery_alert_subscription(
    'b2000000-0000-0000-0000-000000000001',
    'trusted-installation-secret-0000000000001',
    'b1000000-0000-0000-0000-000000000001',
    true,
    0
  )$$,
  'P0001',
  'gallery_alert_subscription_limit_reached',
  'an installation cannot exceed its subscription ceiling'
);

delete from content_private.gallery_alert_subscriptions
where installation_id = 'b2000000-0000-0000-0000-000000000001'
  and gallery_id in (
    select gallery_id
    from content_private.gallery_alert_subscriptions
    where installation_id = 'b2000000-0000-0000-0000-000000000001'
    limit 1
  );

select is(
  public.set_gallery_alert_subscription(
    'b2000000-0000-0000-0000-000000000001',
    'trusted-installation-secret-0000000000001',
    'b1000000-0000-0000-0000-000000000001',
    true,
    0
  ) ->> 'revision',
  '1',
  'freeing a slot lets a genuine follow succeed again'
);

-- Publication fan-out ceiling -----------------------------------------------------

select is(
  (
    content_private.gallery_alert_enrollment_limits()
      ->> 'publication_fanout'
  )::integer,
  50000,
  'the per-publication fan-out ceiling is published as one constant'
);
select ok(
  (
    select pg_catalog.pg_get_functiondef(
      to_regprocedure(
        'content_private.claim_gallery_alert_delivery_jobs_impl('
          || 'uuid,text,integer,integer)'
      )
    ) like '%limit greatest(0, v_fanout_limit - v_existing_jobs)%'
  ),
  'delivery fan-out for one publication is bounded by the ceiling'
);

select * from finish();
rollback;
