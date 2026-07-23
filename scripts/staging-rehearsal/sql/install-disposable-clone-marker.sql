\set ON_ERROR_STOP on

-- Clone-preparation SQL only. This is intentionally not a migration: the
-- marker must never be created in production or copied into production. The
-- shell coordinator validates either the separated-human schema-1 policy or
-- the explicitly risk-accepted solo-operator schema-2 policy before invoking
-- this file. No raw project reference is stored here.

select
  :'installation_confirmation' = 'INSTALL_GALLR_DISPOSABLE_CLONE_MARKER'
    as installation_confirmed,
  current_user = 'postgres' as installer_is_postgres,
  :'governance_mode' in ('separated_humans', 'solo_operator') as governance_mode_valid,
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
  case :'governance_mode'
    when 'separated_humans' then
      :'valid_until_utc'::timestamptz
        <= :'policy_issued_at_utc'::timestamptz + interval '7 days'
    when 'solo_operator' then
      :'valid_until_utc'::timestamptz
        <= :'policy_issued_at_utc'::timestamptz + interval '1 day'
    else false
  end as lifetime_bounded,
  case :'governance_mode'
    when 'separated_humans' then
      length(:'approver_one') between 3 and 160
      and length(:'approver_two') between 3 and 160
      and lower(:'approver_one') <> lower(:'approver_two')
      and :'operator_identity' = ''
      and :'first_confirmation_sha256' = ''
      and :'second_confirmation_sha256' = ''
      and :'effective_first_attestation_utc' = ''
      and :'minimum_cooldown_seconds' = ''
      and :'destructive_actions' = ''
    when 'solo_operator' then
      :'approver_one' = ''
      and :'approver_two' = ''
      and length(:'operator_identity') between 3 and 160
      and :'first_confirmation_sha256' ~ '^[0-9a-f]{64}$'
      and :'second_confirmation_sha256' ~ '^[0-9a-f]{64}$'
      and :'minimum_cooldown_seconds' = '900'
      and :'destructive_actions' = 'forbidden'
    else false
  end as governance_fields_valid,
  case :'governance_mode'
    when 'solo_operator' then
      nullif(:'effective_first_attestation_utc', '')::timestamptz
        <= clock_timestamp() - interval '900 seconds'
    when 'separated_humans' then true
    else false
  end as cooldown_elapsed
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
\if :marker_input_governance_mode_valid
\else
  \echo 'ERROR: unsupported governance mode'
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
  \echo 'ERROR: marker lifetime exceeds the governance-mode limit'
  \quit 3
\endif
\if :marker_input_governance_fields_valid
\else
  \echo 'ERROR: governance fields do not match the selected mode'
  \quit 3
\endif
\if :marker_input_cooldown_elapsed
\else
  \echo 'ERROR: solo-operator cooldown has not elapsed'
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
  governance_mode text not null
    check (governance_mode in ('separated_humans', 'solo_operator')),
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
  approver_one text check (approver_one is null or length(approver_one) between 3 and 160),
  approver_two text check (approver_two is null or length(approver_two) between 3 and 160),
  operator_identity text
    check (operator_identity is null or length(operator_identity) between 3 and 160),
  first_confirmation_sha256 text
    check (first_confirmation_sha256 is null or first_confirmation_sha256 ~ '^[0-9a-f]{64}$'),
  second_confirmation_sha256 text
    check (second_confirmation_sha256 is null or second_confirmation_sha256 ~ '^[0-9a-f]{64}$'),
  effective_first_attestation_at timestamptz,
  second_confirmation_at timestamptz,
  minimum_cooldown_seconds integer,
  destructive_actions text,
  created_at timestamptz not null,
  created_by name not null,
  check (staging_project_ref_sha256 <> production_project_ref_sha256),
  check (valid_until > policy_issued_at),
  check (
    (governance_mode = 'separated_humans'
      and valid_until <= policy_issued_at + interval '7 days')
    or
    (governance_mode = 'solo_operator'
      and valid_until <= policy_issued_at + interval '1 day')
  ),
  check (created_at >= policy_issued_at),
  check (created_at < valid_until),
  check (
    (governance_mode = 'separated_humans'
      and approver_one is not null
      and approver_two is not null
      and lower(approver_one) <> lower(approver_two)
      and operator_identity is null
      and first_confirmation_sha256 is null
      and second_confirmation_sha256 is null
      and effective_first_attestation_at is null
      and second_confirmation_at is null
      and minimum_cooldown_seconds is null
      and destructive_actions is null)
    or
    (governance_mode = 'solo_operator'
      and approver_one is null
      and approver_two is null
      and operator_identity is not null
      and first_confirmation_sha256 is not null
      and second_confirmation_sha256 is not null
      and effective_first_attestation_at is not null
      and second_confirmation_at is not null
      and minimum_cooldown_seconds = 900
      and destructive_actions = 'forbidden'
      and second_confirmation_at
        >= effective_first_attestation_at + interval '900 seconds'
      and second_confirmation_at <= created_at)
  )
);

revoke all on gallr_rehearsal_private.disposable_clone_marker from public;
revoke all on gallr_rehearsal_private.disposable_clone_marker
  from anon, authenticated, service_role;

with installation_clock as (
  select clock_timestamp() as installed_at
)
insert into gallr_rehearsal_private.disposable_clone_marker (
  singleton,
  purpose,
  marker_id,
  governance_mode,
  policy_issued_at,
  valid_until,
  staging_project_ref_sha256,
  production_project_ref_sha256,
  repository_commit,
  operator_manifest_sha256,
  policy_sha256,
  change_record,
  approver_one,
  approver_two,
  operator_identity,
  first_confirmation_sha256,
  second_confirmation_sha256,
  effective_first_attestation_at,
  second_confirmation_at,
  minimum_cooldown_seconds,
  destructive_actions,
  created_at,
  created_by
)
select
  true,
  'gallr_disposable_staging_clone',
  :'marker_id'::uuid,
  :'governance_mode',
  :'policy_issued_at_utc'::timestamptz,
  :'valid_until_utc'::timestamptz,
  :'staging_ref_sha256',
  :'production_ref_sha256',
  :'repository_commit',
  :'operator_manifest_sha256',
  :'policy_sha256',
  :'change_record',
  nullif(:'approver_one', ''),
  nullif(:'approver_two', ''),
  nullif(:'operator_identity', ''),
  nullif(:'first_confirmation_sha256', ''),
  nullif(:'second_confirmation_sha256', ''),
  nullif(:'effective_first_attestation_utc', '')::timestamptz,
  case when :'governance_mode' = 'solo_operator' then installed_at end,
  nullif(:'minimum_cooldown_seconds', '')::integer,
  nullif(:'destructive_actions', ''),
  installed_at,
  current_user
from installation_clock;

comment on schema gallr_rehearsal_private is
  'Disposable clone identity marker; never install in production';
comment on table gallr_rehearsal_private.disposable_clone_marker is
  'Single expiring marker bound to a sealed staging-governance policy';

commit;

-- The shell coordinator requires this exact post-commit token before it can
-- seal successful installation evidence. A client that exits zero before the
-- committed marker is queryable therefore fails closed.
select 'GALLR_DISPOSABLE_CLONE_MARKER_INSTALL_COMPLETE'
from gallr_rehearsal_private.disposable_clone_marker
where singleton
  and purpose = 'gallr_disposable_staging_clone'
  and marker_id = :'marker_id'::uuid
  and governance_mode = :'governance_mode'
  and repository_commit = :'repository_commit'
  and operator_manifest_sha256 = :'operator_manifest_sha256'
  and policy_sha256 = :'policy_sha256'
  and created_by = current_user;
