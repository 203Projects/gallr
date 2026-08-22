begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(37);

select has_table(
  'content_private',
  'gallery_alert_installations',
  'gallery alert installations are stored outside the exposed schema'
);
select has_table(
  'content_private',
  'gallery_alert_subscriptions',
  'per-gallery alert preferences are private'
);
select has_pk(
  'content_private',
  'gallery_alert_installations',
  'installation identity is unique'
);
select has_pk(
  'content_private',
  'gallery_alert_subscriptions',
  'one preference exists per installation and gallery'
);
select col_is_fk(
  'content_private',
  'gallery_alert_subscriptions',
  'gallery_id',
  'subscriptions use stable gallery identities'
);
select ok(
  (
    select relrowsecurity
    from pg_catalog.pg_class
    where oid = 'content_private.gallery_alert_installations'::regclass
  ) and (
    select relrowsecurity
    from pg_catalog.pg_class
    where oid = 'content_private.gallery_alert_subscriptions'::regclass
  ),
  'private alert tables retain RLS as defense in depth'
);
select ok(
  not has_table_privilege(
    'anon',
    'content_private.gallery_alert_installations',
    'SELECT, INSERT, UPDATE, DELETE'
  ) and not has_table_privilege(
    'authenticated',
    'content_private.gallery_alert_subscriptions',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'client roles have no direct alert-table privileges'
);
select ok(
  has_table_privilege(
    'service_role',
    'content_private.gallery_alert_installations',
    'SELECT'
  ) and has_table_privilege(
    'service_role',
    'content_private.gallery_alert_subscriptions',
    'SELECT'
  ),
  'the future delivery worker can read active private subscriptions'
);

select ok(
  to_regprocedure(
    'public.register_gallery_alert_installation(uuid,text,text,text,integer)'
  ) is not null
  and to_regprocedure(
    'public.set_gallery_alert_subscription(uuid,text,uuid,boolean,integer)'
  ) is not null
  and to_regprocedure(
    'public.get_gallery_alert_installation(uuid,text)'
  ) is not null,
  'the narrow public registration, preference, and state RPCs exist'
);
select ok(
  (
    select procedure.prosecdef
      and procedure.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as procedure
    where procedure.oid = to_regprocedure(
      'public.register_gallery_alert_installation(uuid,text,text,text,integer)'
    )
  ),
  'the explicitly granted public endpoint is hardened SECURITY DEFINER code'
);
select ok(
  (
    select procedure.prosecdef
      and procedure.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as procedure
    where procedure.oid = to_regprocedure(
      'content_private.register_gallery_alert_installation_impl('
        || 'uuid,text,text,text,integer,text,boolean,uuid)'
    )
  ),
  'the private registration implementation is search-path hardened'
);
select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral aclexplode(procedure.proacl) as privilege
    where procedure.oid in (
      to_regprocedure(
        'content_private.register_gallery_alert_installation_impl('
          || 'uuid,text,text,text,integer,text,boolean,uuid)'
      ),
      to_regprocedure(
        'public.register_gallery_alert_installation(uuid,text,text,text,integer)'
      )
    )
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC receives no implicit command execution'
);
select ok(
  has_function_privilege(
    'anon',
    'public.register_gallery_alert_installation(uuid,text,text,text,integer)',
    'EXECUTE'
  ) and has_function_privilege(
    'authenticated',
    'public.set_gallery_alert_subscription(uuid,text,uuid,boolean,integer)',
    'EXECUTE'
  ),
  'anonymous and authenticated clients can use only the narrow RPCs'
);
select ok(
  not has_function_privilege(
    'anon',
    'content_private.register_gallery_alert_installation_impl('
      || 'uuid,text,text,text,integer,text,boolean,uuid)',
    'EXECUTE'
  ) and not has_function_privilege(
    'authenticated',
    'content_private.set_gallery_alert_subscription_impl(uuid,text,uuid,boolean,integer)',
    'EXECUTE'
  ),
  'client roles cannot bypass public validation through private helpers'
);

insert into content.galleries (id, name_ko, name_en, status)
values (
  'a1000000-0000-0000-0000-000000000001',
  '알림 테스트 갤러리',
  'Alert Test Gallery',
  'active'
);

set local role anon;

select is(
  public.register_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'IOS',
    'ko_KR',
    0
  ) ->> 'revision',
  '1',
  'an anonymous device can register without an account'
);
select is(
  public.get_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001'
  ) ->> 'locale',
  'ko-KR',
  'registration normalizes locale separators'
);
select is(
  public.register_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'ios',
    'ko-KR',
    0
  ) ->> 'revision',
  '1',
  'an ambiguous retry with the desired state is idempotent'
);
select throws_ok(
  $$select public.register_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'wrong-installation-secret-0000000000000001',
    'ios', 'ko-KR', 1
  )$$,
  '42501',
  'gallery_alert_installation_unauthorized',
  'a wrong installation secret cannot mutate registration'
);
select throws_ok(
  $$select public.register_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'ios', 'en-US', 0
  )$$,
  '40001',
  'revision_conflict',
  'a changed registration rejects a stale revision'
);
select is(
  public.register_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'ios',
    'en-US',
    1
  ) ->> 'revision',
  '2',
  'a revision-checked registration update advances once'
);
select throws_ok(
  $$select public.register_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000002',
    'too-short', 'ios', 'ko-KR', 0
  )$$,
  '22023',
  'installation_secret_invalid',
  'short installation secrets are rejected before storage'
);
select throws_ok(
  $$select public.register_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000002',
    'another-installation-secret-00000000000002',
    'desktop', 'ko-KR', 0
  )$$,
  '22023',
  'platform_invalid',
  'unsupported platforms are rejected'
);
select throws_ok(
  $$select public.set_gallery_alert_subscription(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'a1000000-0000-0000-0000-000000000099',
    true, 0
  )$$,
  '22023',
  'gallery_not_alertable',
  'preferences require a stable active gallery identity'
);
select is(
  public.set_gallery_alert_subscription(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'a1000000-0000-0000-0000-000000000001',
    true,
    0
  ) #>> '{subscriptions,0,revision}',
  '1',
  'an anonymous device can explicitly opt into one gallery'
);
select is(
  public.set_gallery_alert_subscription(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'a1000000-0000-0000-0000-000000000001',
    true,
    0
  ) #>> '{subscriptions,0,revision}',
  '1',
  'a repeated preference command is idempotent'
);
select throws_ok(
  $$select public.set_gallery_alert_subscription(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'a1000000-0000-0000-0000-000000000001',
    false, 0
  )$$,
  '40001',
  'revision_conflict',
  'a changed preference rejects a stale revision'
);
select is(
  public.set_gallery_alert_subscription(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'a1000000-0000-0000-0000-000000000001',
    false,
    1
  ) #>> '{subscriptions,0,revision}',
  '2',
  'a revision-checked preference update advances once'
);
select is(
  public.get_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001'
  ) #>> '{subscriptions,0,enabled}',
  'false',
  'the installation state returns the explicit disabled preference'
);
select throws_ok(
  $$select public.get_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'wrong-installation-secret-0000000000000001'
  )$$,
  '42501',
  'gallery_alert_installation_unauthorized',
  'installation state is also protected by the bearer secret'
);

reset role;

select ok(
  (
    select secret_digest <> 'anonymous-installation-secret-000000000001'
      and secret_digest not like '%anonymous-installation-secret%'
    from content_private.gallery_alert_installations
    where id = 'a2000000-0000-0000-0000-000000000001'
  ),
  'only a salted installation-secret digest is stored'
);
select is(
  (
    select count(*)::integer
    from content_private.gallery_alert_subscriptions
    where installation_id = 'a2000000-0000-0000-0000-000000000001'
  ),
  1,
  'idempotent commands retain exactly one subscription row'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  (
    'a3000000-0000-0000-0000-000000000001',
    'gallery-alert-one@example.invalid',
    '{}'::jsonb
  ),
  (
    'a3000000-0000-0000-0000-000000000002',
    'gallery-alert-two@example.invalid',
    '{}'::jsonb
  );

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"a3000000-0000-0000-0000-000000000001","role":"authenticated"}',
  true
);
select is(
  public.register_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'ios', 'en-US', 2
  ) ->> 'account_linked',
  'true',
  'sign-in can associate the proven anonymous installation'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"a3000000-0000-0000-0000-000000000002","role":"authenticated"}',
  true
);
select throws_ok(
  $$select public.register_gallery_alert_installation(
    'a2000000-0000-0000-0000-000000000001',
    'anonymous-installation-secret-000000000001',
    'ios', 'en-US', 3
  )$$,
  '42501',
  'installation_account_conflict',
  'a linked installation cannot be reassigned to another account'
);
reset role;

select is(
  (
    select user_id
    from content_private.gallery_alert_installations
    where id = 'a2000000-0000-0000-0000-000000000001'
  ),
  'a3000000-0000-0000-0000-000000000001'::uuid,
  'the first authenticated association remains authoritative'
);
select is(
  (
    select revision
    from content_private.gallery_alert_installations
    where id = 'a2000000-0000-0000-0000-000000000001'
  ),
  3,
  'account association participates in installation revision control'
);
select ok(
  exists (
    select 1
    from pg_catalog.pg_indexes
    where schemaname = 'content_private'
      and tablename = 'gallery_alert_subscriptions'
      and indexname = 'gallery_alert_subscriptions_enabled_gallery_idx'
      and indexdef ilike '%WHERE enabled%'
  ),
  'future publication fan-out has an enabled-subscription index'
);
select ok(
  (
    select confdeltype = 'c'::"char"
    from pg_catalog.pg_constraint
    where conrelid =
      'content_private.gallery_alert_subscriptions'::regclass
      and confrelid =
        'content_private.gallery_alert_installations'::regclass
      and contype = 'f'
  ),
  'deleting an installation cascades its private preferences'
);

select * from finish();
rollback;
