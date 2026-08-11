begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(30);

select has_table(
  'content_private',
  'account_deletion_rate_limits',
  'account-deletion rate limits live outside exposed schemas'
);
select ok(
  (
    select relation.relrowsecurity
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content_private'
      and relation.relname = 'account_deletion_rate_limits'
  ),
  'the private rate-limit table has defense-in-depth RLS'
);
select ok(
  not has_table_privilege(
    'authenticated',
    'content_private.account_deletion_rate_limits',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'authenticated callers cannot inspect or mutate deletion counters'
);
select has_function(
  'public',
  'account_deletion_prepare',
  array[]::text[],
  'the authenticated preparation RPC exists'
);
select has_function(
  'public',
  'account_deletion_cancel',
  array['uuid'],
  'the service-only cancellation RPC exists'
);
select ok(
  has_function_privilege(
    'service_role',
    'public.account_deletion_finalize_cleanup(uuid, uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.account_deletion_finalize_cleanup(uuid, uuid)',
    'EXECUTE'
  ),
  'only the worker can scrub cleanup identity under a lease'
);
select ok(
  not (
    select procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'account_deletion_prepare'
  )
  and (
    select procedure.proconfig @> array['search_path=""']::text[]
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'account_deletion_prepare'
  ),
  'the public preparation RPC is a fixed-path SECURITY INVOKER wrapper'
);
select ok(
  has_function_privilege(
    'authenticated',
    'public.account_deletion_prepare()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.account_deletion_prepare()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'service_role',
    'public.account_deletion_prepare()',
    'EXECUTE'
  ),
  'only authenticated callers can prepare their own deletion'
);
select ok(
  has_function_privilege(
    'service_role',
    'public.account_deletion_cancel(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.account_deletion_cancel(uuid)',
    'EXECUTE'
  ),
  'only the deletion service can cancel a cleanup request'
);
select is(
  (
    select constraint_record.confdeltype::text
    from pg_constraint as constraint_record
    where constraint_record.conname = 'command_requests_actor_user_id_fkey'
      and constraint_record.conrelid = 'content.command_requests'::regclass
  ),
  'c',
  'command receipts no longer block Auth deletion'
);
select ok(
  exists (
    select 1
    from pg_trigger as trigger_record
    where trigger_record.tgrelid = 'auth.users'::regclass
      and trigger_record.tgname = 'protect_operator_account_deletion'
      and not trigger_record.tgisinternal
  ),
  'a database trigger protects operator identities from races'
);

insert into auth.users (id, email, last_sign_in_at, raw_user_meta_data)
values
  (
    '00000000-0000-0000-0000-000000000901'::uuid,
    'stale-delete@example.invalid',
    now() - interval '1 hour',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000902'::uuid,
    'recent-delete@example.invalid',
    now(),
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000903'::uuid,
    'staff-delete@example.invalid',
    now(),
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000904'::uuid,
    'owner-delete@example.invalid',
    now(),
    '{}'::jsonb
  );

insert into content.staff_members (user_id, role, active)
values (
  '00000000-0000-0000-0000-000000000903'::uuid,
  'admin'::content.staff_role,
  true
);

insert into content.galleries (id, name_ko)
values ('90000000-0000-0000-0000-000000000001'::uuid, '삭제 보호 갤러리');

insert into content.gallery_memberships (gallery_id, user_id, status, claim_note)
values (
  '90000000-0000-0000-0000-000000000001'::uuid,
  '00000000-0000-0000-0000-000000000904'::uuid,
  'pending'::content.gallery_membership_status,
  'account deletion protection fixture'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000901","role":"authenticated"}',
  true
);
select is(
  public.account_deletion_prepare() ->> 'code',
  'account_deletion_reauthentication_required',
  'a stale session requires a fresh sign-in'
);
select is(
  public.account_deletion_prepare() ->> 'code',
  'account_deletion_reauthentication_required',
  'a second stale request remains fail-closed'
);
select is(
  public.account_deletion_prepare() ->> 'code',
  'account_deletion_reauthentication_required',
  'the third stale request remains fail-closed'
);
select is(
  public.account_deletion_prepare() ->> 'code',
  'account_deletion_rate_limited',
  'the fourth request in fifteen minutes is rate limited'
);
reset role;
select is(
  (
    select count(*)::integer
    from content.outbox_events
    where aggregate_id = '00000000-0000-0000-0000-000000000901'
  ),
  0,
  'blocked stale requests never schedule cleanup'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000903","role":"authenticated"}',
  true
);
select is(
  public.account_deletion_prepare() ->> 'code',
  'account_deletion_requires_support',
  'active staff are routed through support'
);
reset role;
select throws_ok(
  $$ delete from auth.users
     where id = '00000000-0000-0000-0000-000000000903'::uuid $$,
  'P0001',
  'account_deletion_requires_support',
  'the Auth trigger also blocks direct staff deletion'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000904","role":"authenticated"}',
  true
);
select is(
  public.account_deletion_prepare() ->> 'code',
  'account_deletion_requires_support',
  'pending gallery owners are routed through support'
);
reset role;
select throws_ok(
  $$ delete from auth.users
     where id = '00000000-0000-0000-0000-000000000904'::uuid $$,
  'P0001',
  'account_deletion_requires_support',
  'the Auth trigger also blocks direct owner deletion'
);

create temporary table account_deletion_test_state (
  request_id uuid primary key
) on commit drop;
grant insert, select on account_deletion_test_state to authenticated;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000902","role":"authenticated"}',
  true
);
with prepared as (
  select public.account_deletion_prepare() as payload
)
insert into account_deletion_test_state (request_id)
select (payload ->> 'request_id')::uuid
from prepared;
reset role;

select is(
  (
    select event.event_type
    from content.outbox_events as event
    join account_deletion_test_state as state on state.request_id = event.id
  ),
  'account.avatar_cleanup_requested',
  'an eligible deletion durably schedules avatar cleanup'
);
select ok(
  (
    select event.aggregate_type = 'account'
      and event.aggregate_id = '00000000-0000-0000-0000-000000000902'
      and event.payload = '{"bucket_id":"avatars"}'::jsonb
      and event.max_attempts = 20
    from content.outbox_events as event
    join account_deletion_test_state as state on state.request_id = event.id
  ),
  'the cleanup event is minimal, bounded, and retryable'
);
select ok(
  exists (
    select 1
    from content.audit_log as audit
    join account_deletion_test_state as state on state.request_id = audit.request_id
    where audit.action = 'account.deletion_requested'
      and audit.metadata = '{"cleanup_scheduled":true}'::jsonb
  ),
  'the deletion request has a non-secret audit record'
);

insert into content.command_requests (
  actor_user_id,
  request_id,
  command_name,
  request_fingerprint
)
values (
  '00000000-0000-0000-0000-000000000902'::uuid,
  '90000000-0000-0000-0000-000000000002'::uuid,
  'test.command',
  repeat('a', 64)
);
select is(
  (
    select count(*)::integer
    from content.command_requests
    where actor_user_id = '00000000-0000-0000-0000-000000000902'::uuid
  ),
  1,
  'the fixture has a command receipt that formerly blocked deletion'
);
select lives_ok(
  $$ delete from auth.users
     where id = '00000000-0000-0000-0000-000000000902'::uuid $$,
  'an eligible Auth identity can be deleted transactionally'
);
select is(
  (
    select count(*)::integer
    from content.command_requests
    where actor_user_id = '00000000-0000-0000-0000-000000000902'::uuid
  ),
  0,
  'command receipts cascade with the deleted consumer identity'
);
select is(
  (
    select count(*)::integer
    from content.outbox_events as event
    join account_deletion_test_state as state on state.request_id = event.id
  ),
  1,
  'the avatar cleanup request survives Auth deletion'
);
select ok(
  (
    select audit.actor_user_id is null
    from content.audit_log as audit
    join account_deletion_test_state as state on state.request_id = audit.request_id
  ),
  'the durable audit record is anonymized by the Auth cascade'
);
select is(
  (
    select count(*)::integer
    from content_private.account_deletion_rate_limits
    where user_id = '00000000-0000-0000-0000-000000000902'::uuid
  ),
  0,
  'the deleted consumer rate-limit row is removed'
);
select ok(
  (
    select pg_get_functiondef(procedure.oid)
      like '%account.avatar_cleanup_requested%'
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname = 'outbox_claim_media_events_impl'
  ),
  'the internal outbox worker can claim account cleanup without a delivery URL'
);

select * from finish();
rollback;
