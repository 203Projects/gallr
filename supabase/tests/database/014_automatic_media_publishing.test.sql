begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(11);

select has_function(
  'content_private',
  'invoke_media_outbox_worker',
  array[]::text[],
  'private scheduled media-worker invocation exists'
);

select function_returns(
  'content_private',
  'invoke_media_outbox_worker',
  array[]::text[],
  'bigint',
  'scheduled invocation returns the pg_net request id'
);

select ok(
  not has_function_privilege(
    'anon',
    'content_private.invoke_media_outbox_worker()',
    'EXECUTE'
  )
    and not has_function_privilege(
      'authenticated',
      'content_private.invoke_media_outbox_worker()',
      'EXECUTE'
    )
    and not has_function_privilege(
      'service_role',
      'content_private.invoke_media_outbox_worker()',
      'EXECUTE'
    ),
  'API roles cannot invoke the scheduled worker'
);

select is(
  (
    select prosecdef
    from pg_proc
    where oid = 'content_private.invoke_media_outbox_worker()'::regprocedure
  ),
  true,
  'scheduled invocation is security definer'
);

select is(
  (
    select proconfig
    from pg_proc
    where oid = 'content_private.invoke_media_outbox_worker()'::regprocedure
  ),
  array['search_path=""'],
  'scheduled invocation uses an empty search path'
);

select ok(
  position(
    'gallr_outbox_worker_url'
    in pg_get_functiondef(
      'content_private.invoke_media_outbox_worker()'::regprocedure
    )
  ) > 0
    and position(
      'gallr_outbox_worker_token'
      in pg_get_functiondef(
        'content_private.invoke_media_outbox_worker()'::regprocedure
      )
    ) > 0,
  'scheduled invocation reads only the named Vault configuration'
);

select ok(
  position(
    'net.http_post'
    in pg_get_functiondef(
      'content_private.invoke_media_outbox_worker()'::regprocedure
    )
  ) > 0
    and position(
      'Authorization'
      in pg_get_functiondef(
        'content_private.invoke_media_outbox_worker()'::regprocedure
      )
    ) > 0,
  'scheduled invocation queues an authenticated pg_net request'
);

select throws_ok(
  $$ select content_private.invoke_media_outbox_worker() $$,
  '55000',
  'media_worker_schedule_not_configured',
  'the scheduler fails closed until both Vault values are configured'
);

do $$
begin
  perform vault.create_secret(
    'http://wrong-project.test/functions/v1/outbox-worker',
    'gallr_outbox_worker_url',
    'pgTAP automatic media publishing URL'
  );
  perform vault.create_secret(
    'short',
    'gallr_outbox_worker_token',
    'pgTAP automatic media publishing token'
  );
end;
$$;

select throws_ok(
  $$ select content_private.invoke_media_outbox_worker() $$,
  '22023',
  'media_worker_url_is_invalid',
  'the scheduler rejects a non-Supabase or non-HTTPS worker URL'
);

do $$
begin
  perform vault.update_secret(
    (
      select id
      from vault.secrets
      where name = 'gallr_outbox_worker_url'
    ),
    'https://testproject.supabase.co/functions/v1/outbox-worker'
  );
end;
$$;

select throws_ok(
  $$ select content_private.invoke_media_outbox_worker() $$,
  '22023',
  'media_worker_token_is_invalid',
  'the scheduler rejects an unsafe worker token'
);

do $$
begin
  perform vault.update_secret(
    (
      select id
      from vault.secrets
      where name = 'gallr_outbox_worker_token'
    ),
    'test-worker-token-with-32-diverse-characters'
  );
end;
$$;

select ok(
  content_private.invoke_media_outbox_worker() is not null,
  'valid Vault configuration queues one pg_net request'
);

select * from finish();
rollback;
