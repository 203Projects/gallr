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
select set_config(
  'gallr.expected_target_checksum',
  :'expected_target_checksum_sha256',
  true
) as target_checksum_setting
\gset
select set_config(
  'gallr.expected_row_count',
  :'expected_row_count',
  true
) as row_count_setting
\gset
select set_config(
  'gallr.expected_id_checksum',
  :'expected_id_checksum_sha256',
  true
) as id_checksum_setting
\gset
select set_config(
  'gallr.expected_catalog_checksum',
  :'expected_catalog_checksum_sha256',
  true
) as catalog_checksum_setting
\gset

do $postflight$
declare
  v_target_id text := current_setting('gallr.target_id');
  v_reason text := current_setting('gallr.approval_reason');
  v_target_checksum text := current_setting('gallr.expected_target_checksum');
  v_expected_row_count bigint :=
    current_setting('gallr.expected_row_count')::bigint;
  v_expected_id_checksum text :=
    current_setting('gallr.expected_id_checksum');
  v_expected_catalog_checksum text :=
    current_setting('gallr.expected_catalog_checksum');
  v_reconciliation jsonb;
begin
  if not exists (
    select 1
    from content_private.exhibition_catalog_runtime as runtime
    where runtime.singleton
      and runtime.legacy_mirror_enabled
      and runtime.legacy_writes_blocked
      and runtime.legacy_mirror_enabled_at is not null
      and runtime.baseline_row_count = v_expected_row_count
      and runtime.baseline_id_checksum_sha256 =
        v_expected_id_checksum
      and runtime.baseline_catalog_checksum_sha256 =
        v_expected_catalog_checksum
      and runtime.reason = v_reason
  ) or (
    select count(*)
    from content_private.exhibition_catalog_runtime
  ) <> 1 then
    raise exception 'runtime_is_not_canonical_owned_with_exact_baseline';
  end if;

  if pg_catalog.has_table_privilege(
    'service_role', 'public.exhibitions', 'INSERT'
  ) or pg_catalog.has_table_privilege(
    'service_role', 'public.exhibitions', 'UPDATE'
  ) or pg_catalog.has_table_privilege(
    'service_role', 'public.exhibitions', 'DELETE'
  ) or pg_catalog.has_table_privilege(
    'service_role', 'public.exhibitions', 'TRUNCATE'
  ) or pg_catalog.has_any_column_privilege(
    'service_role', 'public.exhibitions', 'INSERT'
  ) or pg_catalog.has_any_column_privilege(
    'service_role', 'public.exhibitions', 'UPDATE'
  ) then
    raise exception 'legacy_service_role_dml_was_not_fully_revoked';
  end if;

  if exists (
    select 1
    from content_private.exhibition_catalog_legacy_write_context
  ) then
    raise exception 'legacy_write_context_was_left_open';
  end if;

  if not exists (
    select 1
    from content_private.exhibition_catalog_v2_source(v_target_id) as source
    join public.exhibition_catalog_v2 as catalog using (id)
    join public.exhibitions as legacy using (id)
    where content_private.sha256_canonical_jsonb(to_jsonb(source)) =
        v_target_checksum
      and catalog.content_checksum_sha256 = v_target_checksum
      and content_private.legacy_exhibition_catalog_v2_checksum(legacy) =
        v_target_checksum
      and content_private.exhibition_catalog_v2_payload(catalog) =
        content_private.legacy_exhibition_catalog_v2_payload(legacy)
  ) then
    raise exception 'representative_target_changed_or_diverged';
  end if;

  if exists (
    select 1
    from public.exhibition_catalog_v2 as catalog
    full join public.exhibitions as legacy using (id)
    where catalog.id is null
      or legacy.id is null
      or content_private.exhibition_catalog_v2_payload(catalog)
        is distinct from
          content_private.legacy_exhibition_catalog_v2_payload(legacy)
  ) then
    raise exception 'global_v2_and_legacy_payloads_diverged';
  end if;

  if not exists (
    select 1
    from public.exhibition_catalog_v2_integrity(null, false) as integrity
    where integrity.row_count = v_expected_row_count
      and integrity.id_checksum_sha256 = v_expected_id_checksum
      and integrity.catalog_checksum_sha256 = v_expected_catalog_checksum
  ) then
    raise exception 'global_catalog_changed_during_queued_writer_gate';
  end if;

  v_reconciliation := public.admin_reconcile_exhibition_catalog_v2();
  if not coalesce((v_reconciliation ->> 'in_sync')::boolean, false) then
    raise exception using
      message = 'post_activation_reconciliation_failed',
      detail = v_reconciliation::text;
  end if;

  if (
    select count(*)
    from content.audit_log as audit
    where audit.action = 'legacy_exhibition_mirror.enabled'
      and audit.entity_type = 'system_setting'
      and audit.entity_id = 'legacy_exhibition_mirror'
      and audit.actor_user_id is null
      and audit.metadata ->> 'reason' = v_reason
      and (audit.metadata ->> 'legacy_writes_blocked')::boolean
      and (audit.metadata ->> 'row_count')::bigint =
        v_expected_row_count
      and audit.metadata ->> 'id_checksum_sha256' =
        v_expected_id_checksum
      and audit.metadata ->> 'catalog_checksum_sha256' =
        v_expected_catalog_checksum
  ) <> 1 then
    raise exception 'exact_append_only_activation_audit_event_is_missing';
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
    raise exception 'audit_log_is_not_append_only_for_service_role';
  end if;
end
$postflight$;

select
  runtime.legacy_mirror_enabled,
  runtime.legacy_writes_blocked,
  catalog.content_checksum_sha256,
  audit.id,
  audit.occurred_at,
  public.admin_reconcile_exhibition_catalog_v2() ->> 'in_sync',
  clock_timestamp()
from content_private.exhibition_catalog_runtime as runtime
cross join public.exhibition_catalog_v2 as catalog
cross join content.audit_log as audit
where runtime.singleton
  and catalog.id = current_setting('gallr.target_id')
  and audit.action = 'legacy_exhibition_mirror.enabled'
  and audit.entity_type = 'system_setting'
  and audit.entity_id = 'legacy_exhibition_mirror'
  and audit.metadata ->> 'reason' = current_setting('gallr.approval_reason');

commit;
