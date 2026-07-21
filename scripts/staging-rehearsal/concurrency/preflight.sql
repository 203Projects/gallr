\set ON_ERROR_STOP on
set timezone = 'UTC';
begin isolation level repeatable read read only;

select set_config('gallr.target_id', :'target_id', true) as target_setting
\gset
select set_config(
  'gallr.approval_reason',
  :'approval_reason',
  true
) as reason_setting
\gset

do $preflight$
declare
  v_target_id text := current_setting('gallr.target_id');
  v_reason text := current_setting('gallr.approval_reason');
  v_v2 record;
  v_legacy record;
  v_legacy_catalog_checksum text;
  v_reconciliation jsonb;
begin
  if to_regprocedure(
    'public.admin_enable_legacy_exhibition_mirror(bigint,text,text,text)'
  ) is null then
    raise exception 'legacy_mirror_activation_function_is_missing';
  end if;

  if not pg_catalog.pg_has_role(current_user, 'service_role', 'USAGE') then
    raise exception 'database_user_cannot_assume_service_role';
  end if;

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
    raise exception 'staging_runtime_is_not_sheet_owned';
  end if;

  if exists (
    select 1
    from content_private.exhibition_catalog_legacy_write_context
  ) then
    raise exception 'legacy_write_context_is_not_empty';
  end if;

  if not pg_catalog.has_table_privilege(
    'service_role', 'public.exhibitions', 'UPDATE'
  ) then
    raise exception 'service_role_does_not_have_legacy_update';
  end if;

  if not pg_catalog.has_table_privilege(
    'service_role', 'content.audit_log', 'SELECT'
  ) or pg_catalog.has_table_privilege(
    'service_role', 'content.audit_log', 'INSERT'
  ) or pg_catalog.has_table_privilege(
    'service_role', 'content.audit_log', 'UPDATE'
  ) or pg_catalog.has_table_privilege(
    'service_role', 'content.audit_log', 'DELETE'
  ) or pg_catalog.has_table_privilege(
    'service_role', 'content.audit_log', 'TRUNCATE'
  ) or pg_catalog.has_table_privilege(
    'service_role', 'content.audit_log', 'REFERENCES'
  ) or pg_catalog.has_table_privilege(
    'service_role', 'content.audit_log', 'TRIGGER'
  ) or pg_catalog.has_any_column_privilege(
    'service_role', 'content.audit_log', 'INSERT'
  ) or pg_catalog.has_any_column_privilege(
    'service_role', 'content.audit_log', 'UPDATE'
  ) or pg_catalog.has_any_column_privilege(
    'service_role', 'content.audit_log', 'REFERENCES'
  ) then
    raise exception 'service_role_audit_access_is_not_append_only_safe';
  end if;

  select * into strict v_v2
  from public.exhibition_catalog_v2_integrity(null, false);
  select * into strict v_legacy
  from public.exhibition_reader_integrity(null, false);

  select encode(
    extensions.digest(
      convert_to(
        coalesce(
          string_agg(
            octet_length(convert_to(legacy.id, 'UTF8'))::text
              || ':' || legacy.id
              || octet_length(
                convert_to(
                  content_private.legacy_exhibition_catalog_v2_checksum(legacy),
                  'UTF8'
                )
              )::text
              || ':'
              || content_private.legacy_exhibition_catalog_v2_checksum(legacy),
            '' order by legacy.id
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ) into v_legacy_catalog_checksum
  from public.exhibitions as legacy;

  v_reconciliation := public.admin_reconcile_exhibition_catalog_v2();
  if not coalesce((v_reconciliation ->> 'in_sync')::boolean, false) then
    raise exception using
      message = 'canonical_and_v2_are_not_reconciled',
      detail = v_reconciliation::text;
  end if;

  if v_legacy.row_count is distinct from v_v2.row_count
      or v_legacy.id_checksum_sha256 is distinct from v_v2.id_checksum_sha256
      or v_legacy_catalog_checksum is distinct from
        v_v2.catalog_checksum_sha256
      or exists (
        select 1
        from public.exhibition_catalog_v2 as catalog
        full join public.exhibitions as legacy using (id)
        where catalog.id is null
          or legacy.id is null
          or content_private.exhibition_catalog_v2_payload(catalog)
            is distinct from
              content_private.legacy_exhibition_catalog_v2_payload(legacy)
      ) then
    raise exception 'v2_and_legacy_payloads_are_not_exactly_equal';
  end if;

  if (select count(*) from content.exhibitions where id = v_target_id) <> 1
      or (
        select count(*)
        from content_private.exhibition_catalog_v2_source(v_target_id)
      ) <> 1
      or (
        select count(*)
        from public.exhibition_catalog_v2
        where id = v_target_id
      ) <> 1
      or (
        select count(*)
        from public.exhibitions
        where id = v_target_id
      ) <> 1 then
    raise exception 'target_must_be_one_existing_published_exhibition';
  end if;

  if exists (
    select 1
    from content.audit_log as audit
    where audit.action = 'legacy_exhibition_mirror.enabled'
      and audit.entity_type = 'system_setting'
      and audit.entity_id = 'legacy_exhibition_mirror'
      and audit.metadata ->> 'reason' = v_reason
  ) then
    raise exception 'approval_reason_was_already_used';
  end if;
end
$preflight$;

select
  integrity.row_count,
  integrity.id_checksum_sha256,
  integrity.catalog_checksum_sha256,
  catalog.content_checksum_sha256,
  clock_timestamp()
from public.exhibition_catalog_v2_integrity(null, false) as integrity
cross join public.exhibition_catalog_v2 as catalog
where catalog.id = current_setting('gallr.target_id');

commit;
