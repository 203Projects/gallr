-- Atomic distributed quotas for the staff-only NAVER geocoding helper.
-- Counters live outside exposed schemas and can only be changed through the
-- staff-checked SECURITY DEFINER implementation below.

create table if not exists content_private.geocode_rate_limit_windows (
  scope text not null
    check (scope in ('project', 'staff')),
  subject_key text not null
    check (char_length(subject_key) between 1 and 64),
  window_started_at timestamptz not null,
  request_count integer not null
    check (request_count between 1 and 30),
  updated_at timestamptz not null default now(),
  primary key (scope, subject_key, window_started_at),
  check (
    (scope = 'project' and subject_key = 'project')
    or (scope = 'staff' and subject_key <> 'project')
  )
);

comment on table content_private.geocode_rate_limit_windows is
  'Fixed one-minute NAVER geocoding counters. Private implementation enforces 10 requests per staff member and 30 per project.';

create index if not exists geocode_rate_limit_windows_started_at_idx
  on content_private.geocode_rate_limit_windows (window_started_at);

alter table content_private.geocode_rate_limit_windows enable row level security;

revoke all on table content_private.geocode_rate_limit_windows
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_consume_geocode_rate_limit_impl()
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  -- One PostgREST RPC is one transaction, so transaction_timestamp() gives a
  -- stable window and retry value throughout this atomic operation.
  v_now timestamptz := transaction_timestamp();
  v_window_started_at timestamptz := date_trunc('minute', v_now);
  v_retry_after_seconds integer := greatest(
    1,
    least(
      60,
      ceil(
        extract(
          epoch from (date_trunc('minute', v_now) + interval '1 minute' - v_now)
        )
      )::integer
    )
  );
  v_project_count integer := 0;
  v_staff_count integer := 0;
  v_staff_subject text;
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  v_staff_subject := v_user_id::text;

  -- Every invocation takes locks in the same order. Transaction-level advisory
  -- locks span the complete PostgREST RPC transaction, so separate Edge
  -- workers cannot both observe and consume the final available slot.
  perform pg_catalog.pg_advisory_xact_lock(1744830465, 1);
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtext(v_staff_subject),
    2
  );

  -- The cleanup uses an indexed timestamp predicate and never touches the
  -- active window. At the enforced project ceiling the table grows by at most
  -- 31 rows per minute before this rolling retention removes them.
  delete from content_private.geocode_rate_limit_windows as rate_window
  where rate_window.window_started_at
    < v_window_started_at - interval '24 hours';

  select rate_window.request_count
  into v_project_count
  from content_private.geocode_rate_limit_windows as rate_window
  where rate_window.scope = 'project'
    and rate_window.subject_key = 'project'
    and rate_window.window_started_at = v_window_started_at;
  v_project_count := coalesce(v_project_count, 0);

  select rate_window.request_count
  into v_staff_count
  from content_private.geocode_rate_limit_windows as rate_window
  where rate_window.scope = 'staff'
    and rate_window.subject_key = v_staff_subject
    and rate_window.window_started_at = v_window_started_at;
  v_staff_count := coalesce(v_staff_count, 0);

  if v_project_count >= 30 then
    return jsonb_build_object(
      'allowed', false,
      'retry_after_seconds', v_retry_after_seconds,
      'limited_by', 'project'
    );
  end if;

  if v_staff_count >= 10 then
    return jsonb_build_object(
      'allowed', false,
      'retry_after_seconds', v_retry_after_seconds,
      'limited_by', 'staff'
    );
  end if;

  insert into content_private.geocode_rate_limit_windows (
    scope,
    subject_key,
    window_started_at,
    request_count,
    updated_at
  )
  values (
    'project',
    'project',
    v_window_started_at,
    1,
    v_now
  )
  on conflict (scope, subject_key, window_started_at)
  do update set
    request_count = geocode_rate_limit_windows.request_count + 1,
    updated_at = excluded.updated_at;

  insert into content_private.geocode_rate_limit_windows (
    scope,
    subject_key,
    window_started_at,
    request_count,
    updated_at
  )
  values (
    'staff',
    v_staff_subject,
    v_window_started_at,
    1,
    v_now
  )
  on conflict (scope, subject_key, window_started_at)
  do update set
    request_count = geocode_rate_limit_windows.request_count + 1,
    updated_at = excluded.updated_at;

  return jsonb_build_object(
    'allowed', true,
    'retry_after_seconds', 0,
    'limited_by', null
  );
end;
$$;

revoke all on function content_private.admin_consume_geocode_rate_limit_impl()
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_consume_geocode_rate_limit_impl()
  to authenticated;

create or replace function public.admin_consume_geocode_rate_limit()
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_consume_geocode_rate_limit_impl();
$$;

revoke all on function public.admin_consume_geocode_rate_limit()
  from public, anon, authenticated, service_role;
grant execute on function public.admin_consume_geocode_rate_limit()
  to authenticated;
