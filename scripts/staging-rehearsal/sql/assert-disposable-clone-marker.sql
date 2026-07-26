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
    and governance_mode = :'expected_governance_mode'
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
    and created_at >= policy_issued_at
    and created_at < valid_until
    and created_at <= clock_timestamp()
    and created_by = 'postgres'
    and case governance_mode
      when 'separated_humans' then
        valid_until <= policy_issued_at + interval '7 days'
        and approver_one = :'expected_approver_one'
        and approver_two = :'expected_approver_two'
        and lower(approver_one) <> lower(approver_two)
        and operator_identity is null
        and first_confirmation_sha256 is null
        and second_confirmation_sha256 is null
        and effective_first_attestation_at is null
        and second_confirmation_at is null
        and minimum_cooldown_seconds is null
        and destructive_actions is null
        and :'expected_operator_identity' = ''
        and :'expected_first_confirmation_sha256' = ''
        and :'expected_second_confirmation_sha256' = ''
        and :'expected_effective_first_attestation_utc' = ''
        and :'expected_minimum_cooldown_seconds' = ''
        and :'expected_destructive_actions' = ''
      when 'solo_operator' then
        valid_until <= policy_issued_at + interval '1 day'
        and approver_one is null
        and approver_two is null
        and :'expected_approver_one' = ''
        and :'expected_approver_two' = ''
        and operator_identity = :'expected_operator_identity'
        and first_confirmation_sha256 = :'expected_first_confirmation_sha256'
        and second_confirmation_sha256 = :'expected_second_confirmation_sha256'
        and effective_first_attestation_at
          = :'expected_effective_first_attestation_utc'::timestamptz
        and second_confirmation_at
          >= effective_first_attestation_at + interval '900 seconds'
        and second_confirmation_at <= created_at
        and minimum_cooldown_seconds
          = :'expected_minimum_cooldown_seconds'::integer
        and minimum_cooldown_seconds = 900
        and destructive_actions = :'expected_destructive_actions'
        and destructive_actions = 'forbidden'
      else false
    end
  )::text
from gallr_rehearsal_private.disposable_clone_marker;
