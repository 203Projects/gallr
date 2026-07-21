\set ON_ERROR_STOP on

-- Clone-preparation SQL only. This is intentionally not a migration: the
-- marker must never be created in production or copied into production. A
-- separate identity operator runs it once on a newly restored disposable
-- clone after independently checking the target in the Supabase dashboard.
--
-- Required psql variables:
--   installation_confirmation (exactly INSTALL_GALLR_DISPOSABLE_CLONE_MARKER)
--   marker_id, policy_issued_at_utc, valid_until_utc
--   staging_ref_sha256, production_ref_sha256
--   repository_commit, operator_manifest_sha256, policy_sha256
--   change_record, approver_one, approver_two

select
  :'installation_confirmation' = 'INSTALL_GALLR_DISPOSABLE_CLONE_MARKER'
    as installation_confirmed,
  current_user = 'postgres' as installer_is_postgres,
  :'staging_ref_sha256' ~ '^[0-9a-f]{64}$' as staging_hash_valid,
  :'production_ref_sha256' ~ '^[0-9a-f]{64}$' as production_hash_valid,
  :'staging_ref_sha256' <> :'production_ref_sha256' as hashes_differ,
  :'operator_manifest_sha256' ~ '^[0-9a-f]{64}$' as manifest_hash_valid,
  :'policy_sha256' ~ '^[0-9a-f]{64}$' as policy_hash_valid,
  :'repository_commit' ~ '^([0-9a-f]{40}|[0-9a-f]{64})$' as commit_valid,
  :'marker_id' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    as marker_id_valid,
  :'policy_issued_at_utc'::timestamptz <= clock_timestamp() + interval '5 minutes'
    as issue_time_valid,
  :'valid_until_utc'::timestamptz > clock_timestamp() as expiry_is_future,
  :'valid_until_utc'::timestamptz > :'policy_issued_at_utc'::timestamptz
    as lifetime_positive,
  :'valid_until_utc'::timestamptz <= :'policy_issued_at_utc'::timestamptz + interval '7 days'
    as lifetime_bounded,
  lower(:'approver_one') <> lower(:'approver_two') as approvers_differ
\gset marker_input_

\if :marker_input_installation_confirmed
\else
  \echo 'ERROR: explicit disposable-clone installation confirmation is required'
  \quit 3
\endif
\if :marker_input_installer_is_postgres
\else
  \echo 'ERROR: the disposable-clone marker must be installed by postgres'
  \quit 3
\endif
\if :marker_input_staging_hash_valid
\else
  \echo 'ERROR: invalid staging fingerprint'
  \quit 3
\endif
\if :marker_input_production_hash_valid
\else
  \echo 'ERROR: invalid production fingerprint'
  \quit 3
\endif
\if :marker_input_hashes_differ
\else
  \echo 'ERROR: staging and production fingerprints must differ'
  \quit 3
\endif
\if :marker_input_manifest_hash_valid
\else
  \echo 'ERROR: invalid operator-manifest fingerprint'
  \quit 3
\endif
\if :marker_input_policy_hash_valid
\else
  \echo 'ERROR: invalid policy fingerprint'
  \quit 3
\endif
\if :marker_input_commit_valid
\else
  \echo 'ERROR: invalid reviewed commit'
  \quit 3
\endif
\if :marker_input_marker_id_valid
\else
  \echo 'ERROR: invalid marker ID'
  \quit 3
\endif
\if :marker_input_issue_time_valid
\else
  \echo 'ERROR: policy issue time is invalid'
  \quit 3
\endif
\if :marker_input_expiry_is_future
\else
  \echo 'ERROR: marker expiry must be in the future'
  \quit 3
\endif
\if :marker_input_lifetime_positive
\else
  \echo 'ERROR: marker lifetime must be positive'
  \quit 3
\endif
\if :marker_input_lifetime_bounded
\else
  \echo 'ERROR: marker lifetime must not exceed seven days'
  \quit 3
\endif
\if :marker_input_approvers_differ
\else
  \echo 'ERROR: two distinct approvers are required'
  \quit 3
\endif

begin;

create schema gallr_rehearsal_private;
revoke all on schema gallr_rehearsal_private from public;
revoke all on schema gallr_rehearsal_private from anon, authenticated, service_role;

create table gallr_rehearsal_private.disposable_clone_marker (
  singleton boolean primary key default true check (singleton),
  purpose text not null check (purpose = 'gallr_disposable_staging_clone'),
  marker_id uuid not null unique,
  policy_issued_at timestamptz not null,
  valid_until timestamptz not null,
  staging_project_ref_sha256 text not null
    check (staging_project_ref_sha256 ~ '^[0-9a-f]{64}$'),
  production_project_ref_sha256 text not null
    check (production_project_ref_sha256 ~ '^[0-9a-f]{64}$'),
  repository_commit text not null
    check (repository_commit ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
  operator_manifest_sha256 text not null
    check (operator_manifest_sha256 ~ '^[0-9a-f]{64}$'),
  policy_sha256 text not null
    check (policy_sha256 ~ '^[0-9a-f]{64}$'),
  change_record text not null check (length(change_record) between 3 and 160),
  approver_one text not null check (length(approver_one) between 3 and 160),
  approver_two text not null check (length(approver_two) between 3 and 160),
  created_at timestamptz not null default clock_timestamp(),
  created_by name not null default current_user,
  check (staging_project_ref_sha256 <> production_project_ref_sha256),
  check (lower(approver_one) <> lower(approver_two)),
  check (valid_until > policy_issued_at),
  check (valid_until <= policy_issued_at + interval '7 days'),
  check (created_at >= policy_issued_at),
  check (created_at < valid_until)
);

revoke all on gallr_rehearsal_private.disposable_clone_marker from public;
revoke all on gallr_rehearsal_private.disposable_clone_marker
  from anon, authenticated, service_role;

insert into gallr_rehearsal_private.disposable_clone_marker (
  singleton,
  purpose,
  marker_id,
  policy_issued_at,
  valid_until,
  staging_project_ref_sha256,
  production_project_ref_sha256,
  repository_commit,
  operator_manifest_sha256,
  policy_sha256,
  change_record,
  approver_one,
  approver_two
) values (
  true,
  'gallr_disposable_staging_clone',
  :'marker_id'::uuid,
  :'policy_issued_at_utc'::timestamptz,
  :'valid_until_utc'::timestamptz,
  :'staging_ref_sha256',
  :'production_ref_sha256',
  :'repository_commit',
  :'operator_manifest_sha256',
  :'policy_sha256',
  :'change_record',
  :'approver_one',
  :'approver_two'
);

comment on schema gallr_rehearsal_private is
  'Disposable clone identity marker; never install in production';
comment on table gallr_rehearsal_private.disposable_clone_marker is
  'Single expiring marker bound to an external two-approver identity policy';

commit;
