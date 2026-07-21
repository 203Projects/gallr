\set ON_ERROR_STOP on
\set VERBOSITY verbose
set timezone = 'UTC';
set statement_timeout = :'statement_timeout';
set lock_timeout = :'lock_timeout';

begin;
select set_config('gallr.writer_app', :'writer_app', true) as writer_setting
\gset
select set_config(
  'gallr.wait_timeout_seconds',
  :'wait_timeout_seconds',
  true
) as timeout_setting
\gset
select set_config(
  'gallr.approval_reason',
  :'approval_reason',
  true
) as reason_setting
\gset

-- Take the same locks as the deployed command before checking the starting
-- state. The function takes them again reentrantly; this closes the gap between
-- shell preflight and activation without introducing a different lock order.
select pg_catalog.pg_advisory_xact_lock(73241, 1);
lock table public.exhibitions in share mode;
lock table public.exhibition_catalog_v2 in share mode;

do $locked_start_guard$
declare
  v_reason text := current_setting('gallr.approval_reason');
begin
  if not exists (
    select 1
    from content_private.exhibition_catalog_runtime as runtime
    where runtime.singleton
      and not runtime.legacy_mirror_enabled
      and not runtime.legacy_writes_blocked
      and runtime.legacy_mirror_enabled_at is null
  ) or (
    select count(*)
    from content_private.exhibition_catalog_runtime
  ) <> 1 then
    raise exception 'locked_runtime_is_not_sheet_owned';
  end if;

  if not pg_catalog.has_table_privilege(
    'service_role', 'public.exhibitions', 'UPDATE'
  ) then
    raise exception 'locked_service_role_update_is_not_authorized';
  end if;

  if exists (
    select 1
    from content_private.exhibition_catalog_legacy_write_context
  ) then
    raise exception 'locked_legacy_write_context_is_not_empty';
  end if;

  if exists (
    select 1
    from content.audit_log as audit
    where audit.action = 'legacy_exhibition_mirror.enabled'
      and audit.entity_type = 'system_setting'
      and audit.entity_id = 'legacy_exhibition_mirror'
      and audit.metadata ->> 'reason' = v_reason
  ) then
    raise exception 'locked_approval_reason_was_already_used';
  end if;
end
$locked_start_guard$;

set local role service_role;
select public.admin_enable_legacy_exhibition_mirror(
  :'expected_row_count'::bigint,
  :'expected_id_checksum_sha256',
  :'expected_catalog_checksum_sha256',
  :'approval_reason'
);
reset role;

do $lock_assertion$
begin
  if not exists (
    select 1
    from pg_catalog.pg_locks as held
    where held.pid = pg_catalog.pg_backend_pid()
      and held.locktype = 'advisory'
      and held.classid = 73241::oid
      and held.objid = 1::oid
      and held.objsubid = 2
      and held.mode = 'ExclusiveLock'
      and held.granted
  ) then
    raise exception 'activation_missing_deployed_advisory_lock';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_locks as held
    where held.pid = pg_catalog.pg_backend_pid()
      and held.locktype = 'relation'
      and held.relation = 'public.exhibitions'::regclass
      and held.mode = 'ShareLock'
      and held.granted
  ) then
    raise exception 'activation_missing_deployed_legacy_share_lock';
  end if;

  if not exists (
    select 1
    from pg_catalog.pg_locks as held
    where held.pid = pg_catalog.pg_backend_pid()
      and held.locktype = 'relation'
      and held.relation = 'public.exhibition_catalog_v2'::regclass
      and held.mode = 'ShareLock'
      and held.granted
  ) then
    raise exception 'activation_missing_deployed_v2_share_lock';
  end if;

  raise notice 'activation holds deployed advisory and relation locks';
end
$lock_assertion$;

do $wait_for_writer$
declare
  v_writer_app text := current_setting('gallr.writer_app');
  v_deadline timestamptz :=
    clock_timestamp() + make_interval(
      secs => current_setting('gallr.wait_timeout_seconds')::integer
    );
begin
  loop
    perform pg_catalog.pg_stat_clear_snapshot();

    if exists (
      select 1
      from pg_catalog.pg_stat_activity as activity
      where activity.application_name = v_writer_app
        and activity.state = 'active'
        and activity.wait_event_type = 'Lock'
        and activity.query like '%update public.exhibitions as legacy%'
        and pg_catalog.pg_backend_pid() = any(
          pg_catalog.pg_blocking_pids(activity.pid)
        )
    ) then
      raise notice 'queued legacy writer observed behind activation locks';
      return;
    end if;

    if clock_timestamp() >= v_deadline then
      raise exception 'activation_timed_out_waiting_for_queued_legacy_writer';
    end if;

    perform pg_catalog.pg_sleep(0.05);
  end loop;
end
$wait_for_writer$;

commit;
