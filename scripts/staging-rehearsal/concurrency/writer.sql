\set ON_ERROR_STOP on
\set VERBOSITY verbose
set timezone = 'UTC';
set statement_timeout = :'statement_timeout';
set lock_timeout = :'lock_timeout';

begin;
set local role service_role;

do $authorization$
begin
  if not pg_catalog.has_table_privilege(
    current_user,
    'public.exhibitions',
    'UPDATE'
  ) then
    raise exception 'queued_writer_did_not_observe_update_authorization';
  end if;

  raise notice 'queued writer observed service_role UPDATE authorization';
end
$authorization$;

update public.exhibitions as legacy
set name_ko = 'queued-writer-must-fail-' || :'run_id'
where legacy.id = :'target_id';

commit;
