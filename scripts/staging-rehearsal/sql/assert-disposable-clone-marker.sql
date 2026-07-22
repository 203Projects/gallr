-- Read-only marker evidence. The caller supplies default_transaction_read_only
-- and wraps this file in a single read-only transaction. Output is intentionally
-- limited to two machine-checked status rows; target labels and credentials are
-- never printed.

select
  'relation',
  count(*)::text,
  coalesce(bool_and(
    c.relkind = 'r'
    and c.relpersistence = 'p'
    and c.relowner = to_regrole('postgres')
    and not c.relrowsecurity
    and not has_schema_privilege('anon', n.oid, 'USAGE')
    and not has_schema_privilege('authenticated', n.oid, 'USAGE')
    and not has_schema_privilege('service_role', n.oid, 'USAGE')
    and not has_table_privilege('anon', c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
    and not has_table_privilege('authenticated', c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
    and not has_table_privilege('service_role', c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
  ), false)::text
from pg_catalog.pg_class c
join pg_catalog.pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'gallr_rehearsal_private'
  and c.relname = 'disposable_clone_marker';

select
  'marker',
  count(*)::text,
  count(*) filter (where
    singleton
    and purpose = 'gallr_disposable_staging_clone'
    and marker_id = :'expected_marker_id'::uuid
    and policy_issued_at = :'expected_policy_issued_at_utc'::timestamptz
    and policy_issued_at <= clock_timestamp() + interval '5 minutes'
    and valid_until = :'expected_valid_until_utc'::timestamptz
    and valid_until > clock_timestamp()
    and staging_project_ref_sha256 = :'expected_staging_ref_sha256'
    and production_project_ref_sha256 = :'expected_production_ref_sha256'
    and staging_project_ref_sha256 <> production_project_ref_sha256
    and repository_commit = :'expected_repository_commit'
    and operator_manifest_sha256 = :'expected_operator_manifest_sha256'
    and policy_sha256 = :'expected_policy_sha256'
    and change_record = :'expected_change_record'
    and approver_one = :'expected_approver_one'
    and approver_two = :'expected_approver_two'
    and lower(approver_one) <> lower(approver_two)
    and created_at >= policy_issued_at
    and created_at < valid_until
    and created_at <= clock_timestamp()
    and created_by = 'postgres'
  )::text
from gallr_rehearsal_private.disposable_clone_marker;
