\set ON_ERROR_STOP on
\set VERBOSITY verbose
\pset pager off
\timing on

begin;
set local role service_role;
set local statement_timeout = '5min';
set local idle_in_transaction_session_timeout = '30s';
select pg_catalog.set_config('gallr.migration_probe_target_id', :'target_id', true)
\gset

do $probe$
declare
  v_started_at timestamptz := pg_catalog.clock_timestamp();
  v_finished_at timestamptz;
  v_row_count bigint;
begin
  update public.exhibitions
  set updated_at = updated_at
  where id = pg_catalog.current_setting('gallr.migration_probe_target_id');

  get diagnostics v_row_count = row_count;
  if v_row_count <> 1 then
    raise exception using
      errcode = 'P0002',
      message = 'migration_writer_probe_target_row_count_invalid',
      detail = pg_catalog.format('expected=1 actual=%s', v_row_count);
  end if;

  v_finished_at := pg_catalog.clock_timestamp();
  raise notice
    'GALLR_MIGRATION_WRITER_PROBE backend_pid=% started_at_utc=% finished_at_utc=% database_statement_elapsed_ms=% outcome=rollback_pending',
    pg_catalog.pg_backend_pid(),
    pg_catalog.to_char(v_started_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    pg_catalog.to_char(v_finished_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    pg_catalog.round(
      extract(epoch from (v_finished_at - v_started_at)) * 1000,
      3
    );
end
$probe$;

rollback;
\echo GALLR_MIGRATION_WRITER_PROBE transaction_outcome=rolled_back
