begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(31);

-- Private storage, wrapper shape, and least-privilege boundaries.
select has_table(
  'content_private',
  'geocode_rate_limit_windows',
  'geocoding rate-limit windows are stored outside exposed schemas'
);

select ok(
  (
    select relation.relrowsecurity
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content_private'
      and relation.relname = 'geocode_rate_limit_windows'
  ),
  'the private rate-limit table has defense-in-depth RLS enabled'
);

select ok(
  (
    select bool_and(
      not has_table_privilege(
        'authenticated',
        'content_private.geocode_rate_limit_windows',
        privilege_name
      )
    )
    from unnest(array[
      'SELECT',
      'INSERT',
      'UPDATE',
      'DELETE',
      'TRUNCATE',
      'REFERENCES',
      'TRIGGER',
      'MAINTAIN'
    ]::text[]) as privilege(privilege_name)
  ),
  'authenticated callers cannot inspect or alter counters directly'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'admin_consume_geocode_rate_limit'
      and procedure.pronargs = 0
      and procedure.prokind = 'f'
  ),
  1,
  'one public zero-argument rate-limit RPC exists'
);

select ok(
  not (
    select procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'admin_consume_geocode_rate_limit'
      and procedure.pronargs = 0
      and procedure.prokind = 'f'
  ),
  'the public rate-limit RPC is SECURITY INVOKER'
);

select ok(
  (
    select procedure.proconfig @> array['search_path=""']::text[]
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname = 'admin_consume_geocode_rate_limit'
      and procedure.pronargs = 0
      and procedure.prokind = 'f'
  ),
  'the public rate-limit RPC pins an empty search_path'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.admin_consume_geocode_rate_limit()',
    'EXECUTE'
  ),
  'authenticated callers can invoke the public rate-limit RPC'
);

select ok(
  not has_function_privilege(
    'anon',
    'public.admin_consume_geocode_rate_limit()',
    'EXECUTE'
  ),
  'anonymous callers cannot invoke the rate-limit RPC'
);

select ok(
  not has_function_privilege(
    'service_role',
    'public.admin_consume_geocode_rate_limit()',
    'EXECUTE'
  ),
  'service role receives no implicit rate-limit RPC grant'
);

select ok(
  (
    select procedure.prosecdef
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname = 'admin_consume_geocode_rate_limit_impl'
      and procedure.pronargs = 0
      and procedure.prokind = 'f'
  ),
  'the private implementation owns the privileged counter mutation'
);

select ok(
  (
    select procedure.proconfig @> array['search_path=""']::text[]
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname = 'admin_consume_geocode_rate_limit_impl'
      and procedure.pronargs = 0
      and procedure.prokind = 'f'
  ),
  'the privileged implementation pins an empty search_path'
);

select ok(
  (
    select
      position(
        'pg_advisory_xact_lock(1744830465, 1)'
        in pg_get_functiondef(procedure.oid)
      ) > 0
      and pg_get_functiondef(procedure.oid)
        ~ 'pg_advisory_xact_lock\([[:space:]]*pg_catalog.hashtext'
      and position(
        'pg_advisory_xact_lock(1744830465, 1)'
        in pg_get_functiondef(procedure.oid)
      ) < position(
        'pg_catalog.hashtext'
        in pg_get_functiondef(procedure.oid)
      )
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname = 'admin_consume_geocode_rate_limit_impl'
      and procedure.pronargs = 0
      and procedure.prokind = 'f'
  ),
  'the implementation retains deterministic project-then-staff transaction locks'
);

select ok(
  has_function_privilege(
    'authenticated',
    'content_private.admin_consume_geocode_rate_limit_impl()',
    'EXECUTE'
  )
    and not has_function_privilege(
      'anon',
      'content_private.admin_consume_geocode_rate_limit_impl()',
      'EXECUTE'
    )
    and not has_function_privilege(
      'service_role',
      'content_private.admin_consume_geocode_rate_limit_impl()',
      'EXECUTE'
    ),
  'only authenticated callers can enter the staff-checking implementation'
);

-- Identities used for authorization and both quota scopes.
truncate table content_private.geocode_rate_limit_windows;

insert into auth.users (id, email, raw_user_meta_data)
values
  (
    '00000000-0000-0000-0000-000000000310'::uuid,
    'geocode-normal@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000311'::uuid,
    'geocode-inactive@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000312'::uuid,
    'geocode-a@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000313'::uuid,
    'geocode-b@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000314'::uuid,
    'geocode-c@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000315'::uuid,
    'geocode-d@example.invalid',
    '{}'::jsonb
  );

insert into content.staff_members (user_id, role, active)
values
  (
    '00000000-0000-0000-0000-000000000311'::uuid,
    'contributor'::content.staff_role,
    false
  ),
  (
    '00000000-0000-0000-0000-000000000312'::uuid,
    'contributor'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000313'::uuid,
    'publisher'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000314'::uuid,
    'admin'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000315'::uuid,
    'contributor'::content.staff_role,
    true
  );

create function pg_temp.consume_geocode_n(p_count integer)
returns jsonb
language plpgsql
volatile
security invoker
set search_path = ''
as $$
declare
  v_result jsonb;
begin
  for v_index in 1..p_count loop
    v_result := public.admin_consume_geocode_rate_limit();
  end loop;
  return v_result;
end;
$$;
grant execute on function pg_temp.consume_geocode_n(integer) to authenticated;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000310","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_consume_geocode_rate_limit() $$,
  '42501',
  'active_staff_membership_required',
  'a signed-in non-staff user cannot consume geocoding quota'
);
select throws_ok(
  $$ select content_private.admin_consume_geocode_rate_limit_impl() $$,
  '42501',
  'active_staff_membership_required',
  'direct implementation access cannot bypass the staff check'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000311","role":"authenticated"}',
  true
);
select throws_ok(
  $$ select public.admin_consume_geocode_rate_limit() $$,
  '42501',
  'active_staff_membership_required',
  'inactive staff cannot consume geocoding quota'
);

-- Per-staff quota: ten requests per fixed one-minute window.
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000312","role":"authenticated"}',
  true
);
select is(
  public.admin_consume_geocode_rate_limit() ->> 'allowed',
  'true',
  'an active staff member can consume the first request'
);
select is(
  pg_temp.consume_geocode_n(9) ->> 'allowed',
  'true',
  'the tenth request remains within the per-staff quota'
);
select is(
  public.admin_consume_geocode_rate_limit() ->> 'limited_by',
  'staff',
  'the eleventh request is rejected by the staff quota'
);
select ok(
  (public.admin_consume_geocode_rate_limit() ->> 'retry_after_seconds')::integer
    between 1 and 60,
  'staff rejection returns a bounded retry interval'
);

reset role;
select is(
  (
    select request_count
    from content_private.geocode_rate_limit_windows
    where scope = 'project'
      and window_started_at = date_trunc('minute', transaction_timestamp())
  ),
  10,
  'rejected staff requests do not increment the project counter'
);
select is(
  (
    select request_count
    from content_private.geocode_rate_limit_windows
    where scope = 'staff'
      and subject_key = '00000000-0000-0000-0000-000000000312'
      and window_started_at = date_trunc('minute', transaction_timestamp())
  ),
  10,
  'the staff counter never exceeds its exact ceiling'
);

-- Project quota: thirty accepted requests shared by every staff identity.
truncate table content_private.geocode_rate_limit_windows;
insert into content_private.geocode_rate_limit_windows (
  scope,
  subject_key,
  window_started_at,
  request_count
)
values (
  'project',
  'project',
  date_trunc('minute', clock_timestamp()) - interval '25 hours',
  1
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000312","role":"authenticated"}',
  true
);
select is(
  pg_temp.consume_geocode_n(10) ->> 'allowed',
  'true',
  'staff A consumes ten project requests'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000313","role":"authenticated"}',
  true
);
select is(
  pg_temp.consume_geocode_n(10) ->> 'allowed',
  'true',
  'staff B consumes ten project requests'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000314","role":"authenticated"}',
  true
);
select is(
  pg_temp.consume_geocode_n(10) ->> 'allowed',
  'true',
  'staff C fills the thirtieth project request'
);
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000315","role":"authenticated"}',
  true
);
select is(
  public.admin_consume_geocode_rate_limit() ->> 'limited_by',
  'project',
  'the next staff member is rejected by the shared project quota'
);
select ok(
  (public.admin_consume_geocode_rate_limit() ->> 'retry_after_seconds')::integer
    between 1 and 60,
  'project rejection returns a bounded retry interval'
);

reset role;
select is(
  (
    select request_count
    from content_private.geocode_rate_limit_windows
    where scope = 'project'
      and window_started_at = date_trunc('minute', transaction_timestamp())
  ),
  30,
  'the project counter never exceeds its exact ceiling'
);
select is(
  (
    select sum(request_count)::integer
    from content_private.geocode_rate_limit_windows
    where scope = 'staff'
      and window_started_at = date_trunc('minute', transaction_timestamp())
  ),
  30,
  'accepted project requests have matching per-staff accounting'
);
select is(
  (
    select count(*)::integer
    from content_private.geocode_rate_limit_windows
    where scope = 'staff'
      and subject_key = '00000000-0000-0000-0000-000000000315'
  ),
  0,
  'a project-rejected request creates no staff counter'
);
select is(
  (
    select count(*)::integer
    from content_private.geocode_rate_limit_windows
    where window_started_at < date_trunc('minute', clock_timestamp()) - interval '24 hours'
  ),
  0,
  'successful limiter calls clean expired windows'
);

select * from finish();
rollback;
