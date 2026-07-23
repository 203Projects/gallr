\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on

-- This is a fixed, one-shot mutation used only to prove that the canonical
-- PostgREST reader discards a same-ID content snapshot changed mid-pagination.
-- The coordinator supplies values derived from the sealed fixture/operator
-- manifests, the exact identity-policy marker, and the target-row checksum
-- observed on the first fetch attempt.

begin;

set local timezone = 'UTC';
set local search_path = pg_catalog;
set local statement_timeout = '30s';
set local lock_timeout = '5s';
set local idle_in_transaction_session_timeout = '30s';

create temp table mutation_config (
  staging_ref_sha256 text not null,
  production_ref_sha256 text not null,
  repository_commit text not null,
  operator_manifest_sha256 text not null,
  marker_id uuid not null,
  governance_mode text not null,
  policy_issued_at timestamptz not null,
  valid_until timestamptz not null,
  policy_sha256 text not null,
  fixture_prefix text not null,
  event_id text not null,
  target_id text not null,
  published_version_id text not null,
  content_checksum_sha256 text not null
) on commit drop;

insert into pg_temp.mutation_config values (
  :'expected_staging_ref_sha256',
  :'expected_production_ref_sha256',
  :'expected_repository_commit',
  :'expected_operator_manifest_sha256',
  :'expected_marker_id'::uuid,
  :'expected_governance_mode',
  :'expected_policy_issued_at_utc'::timestamptz,
  :'expected_valid_until_utc'::timestamptz,
  :'expected_policy_sha256',
  :'expected_fixture_prefix',
  :'expected_event_id',
  :'expected_target_id',
  :'expected_published_version_id',
  :'expected_content_checksum_sha256'
);

-- Serialize against the fixture provision/cleanup lifecycle before inspecting
-- or changing any fixture-owned row.
do $fixture_lock$
begin
  perform pg_catalog.pg_advisory_xact_lock(73243, 1205);
end
$fixture_lock$;

do $mutation$
declare
  completion_suffix constant text :=
    ' [gallr-postgrest-same-id-retry-proof:v1]';
  locked_version_id uuid;
  checksum_before text;
  checksum_after text;
  description_after text;
  updated_rows integer;
  config pg_temp.mutation_config%rowtype;
begin
  select * into strict config from pg_temp.mutation_config;

  if current_user <> 'postgres'
     or current_database() <> 'postgres' then
    raise exception using
      errcode = '42501',
      message = 'postgrest_mutation_owner_invalid';
  end if;

  if config.staging_ref_sha256 !~ '^[0-9a-f]{64}$'
     or config.production_ref_sha256 !~ '^[0-9a-f]{64}$'
     or config.staging_ref_sha256 = config.production_ref_sha256
     or config.repository_commit !~ '^([0-9a-f]{40}|[0-9a-f]{64})$'
     or config.operator_manifest_sha256 !~ '^[0-9a-f]{64}$'
     or config.governance_mode not in ('separated_humans', 'solo_operator')
     or config.valid_until <= config.policy_issued_at
     or config.policy_sha256 !~ '^[0-9a-f]{64}$'
     or config.content_checksum_sha256 !~ '^[0-9a-f]{64}$'
     or config.published_version_id
          !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
     or config.fixture_prefix !~ '^gallr-rehearsal-[a-z0-9][a-z0-9-]{7,31}-$'
     or left(config.target_id, length(config.fixture_prefix))
          <> config.fixture_prefix
     or left(config.event_id, length(config.fixture_prefix))
          <> config.fixture_prefix
     or config.target_id <>
          config.fixture_prefix || 'catalog-0750.mutate,(same-id):한글'
     or config.event_id <>
          config.fixture_prefix || 'event.catalog.v2,(load):한글' then
    raise exception using
      errcode = '22023',
      message = 'postgrest_mutation_input_invalid';
  end if;

  if to_regclass('gallr_rehearsal_private.disposable_clone_marker') is null
     or to_regclass('content.exhibitions') is null
     or to_regclass('content.exhibition_versions') is null
     or to_regclass('content_private.exhibition_catalog_runtime') is null
     or to_regclass('public.exhibitions') is null
     or to_regclass('public.exhibition_catalog_v2') is null then
    raise exception using
      errcode = '55000',
      message = 'postgrest_mutation_schema_missing';
  end if;

  -- Recheck the disposable-clone marker in this exact connection and hold its
  -- singleton row through commit. The immediately preceding external target
  -- guard performs the broader policy/schema/privilege validation.
  perform 1
  from gallr_rehearsal_private.disposable_clone_marker as marker
  where marker.singleton
    and marker.purpose = 'gallr_disposable_staging_clone'
    and marker.valid_until > clock_timestamp() + interval '31 seconds'
    and marker.marker_id = config.marker_id
    and marker.governance_mode = config.governance_mode
    and marker.policy_issued_at = config.policy_issued_at
    and marker.valid_until = config.valid_until
    and marker.policy_sha256 = config.policy_sha256
    and marker.staging_project_ref_sha256 = config.staging_ref_sha256
    and marker.production_project_ref_sha256 = config.production_ref_sha256
    and marker.repository_commit = config.repository_commit
    and marker.operator_manifest_sha256 = config.operator_manifest_sha256
  for update;
  if not found then
    raise exception using
      errcode = '42501',
      message = 'postgrest_mutation_marker_mismatch';
  end if;

  -- Lock the exact canonical identity and the manifest-recorded published
  -- version. No draft/version creation or pointer change is permitted.
  select exhibition.published_version_id
  into locked_version_id
  from content.exhibitions as exhibition
  where exhibition.id = config.target_id
    and exhibition.archived_at is null
  for update;

  if not found
     or locked_version_id is null
     or locked_version_id::text <> config.published_version_id then
    raise exception using
      errcode = '55000',
      message = 'postgrest_mutation_published_pointer_mismatch';
  end if;

  perform 1
  from content.exhibition_versions as version
  where version.id = locked_version_id
    and version.exhibition_id = config.target_id
    and version.status = 'published'::content.exhibition_version_status
    and version.event_id = config.event_id
    and version.description_en is not null
    and strpos(version.description_en, completion_suffix) = 0
  for update;
  if not found then
    raise exception using
      errcode = '55000',
      message = 'postgrest_mutation_version_mismatch_or_already_changed';
  end if;

  -- Follow the catalog projector's global -> legacy -> per-ID lock order
  -- before locking its materialized row. Holding these locks through the
  -- version update prevents another source trigger from changing the same
  -- projection between the target-row checksum precondition and our trigger
  -- proof.
  perform pg_catalog.pg_advisory_xact_lock_shared(73241, 1);
  if coalesce(
    (
      select runtime.legacy_mirror_enabled
      from content_private.exhibition_catalog_runtime as runtime
      where runtime.singleton
    ),
    false
  ) then
    perform 1
    from public.exhibitions as legacy
    where legacy.id = config.target_id
    for update;
  end if;
  perform pg_catalog.pg_advisory_xact_lock(
    73242,
    pg_catalog.hashtext(config.target_id)
  );

  -- The target-row checksum came from the first-attempt PostgREST page that
  -- contained this target. Refuse a stale, wrong-target, or wrong-event
  -- mutation.
  select catalog.content_checksum_sha256
  into checksum_before
  from public.exhibition_catalog_v2 as catalog
  where catalog.id = config.target_id
    and catalog.event_id = config.event_id
  for update;
  if not found
     or checksum_before is distinct from config.content_checksum_sha256 then
    raise exception using
      errcode = '40001',
      message = 'postgrest_mutation_pre_checksum_mismatch';
  end if;

  -- Intentionally mutate one business field on one pre-existing published
  -- version. The standard updated_at/projector triggers may update derived
  -- metadata, but this statement assigns only description_en.
  update content.exhibition_versions as version
  set description_en = version.description_en || completion_suffix
  where version.id = locked_version_id
    and version.exhibition_id = config.target_id
    and version.status = 'published'::content.exhibition_version_status
    and version.event_id = config.event_id
    and strpos(version.description_en, completion_suffix) = 0;
  get diagnostics updated_rows = row_count;

  if updated_rows <> 1 then
    raise exception using
      errcode = '55000',
      message = 'postgrest_mutation_row_count_invalid';
  end if;

  select
    catalog.content_checksum_sha256,
    catalog.description_en
  into strict
    checksum_after,
    description_after
  from public.exhibition_catalog_v2 as catalog
  where catalog.id = config.target_id
    and catalog.event_id = config.event_id;

  if checksum_after !~ '^[0-9a-f]{64}$'
     or checksum_after = checksum_before
     or checksum_after is distinct from (
       select content_private.exhibition_catalog_v2_checksum(catalog)
       from public.exhibition_catalog_v2 as catalog
       where catalog.id = config.target_id
     )
     or description_after is null
     or right(description_after, length(completion_suffix))
          <> completion_suffix
     or (
       length(description_after)
         - length(replace(description_after, completion_suffix, ''))
       ) / length(completion_suffix) <> 1 then
    raise exception using
      errcode = '55000',
      message = 'postgrest_mutation_trigger_verification_failed';
  end if;

  if (
    select exhibition.published_version_id
    from content.exhibitions as exhibition
    where exhibition.id = config.target_id
  ) is distinct from locked_version_id then
    raise exception using
      errcode = '55000',
      message = 'postgrest_mutation_published_pointer_changed';
  end if;

  -- The row lock prevents concurrent replacement, but time still advances.
  -- Require one second beyond the 30-second idle-in-transaction timeout so a
  -- stalled client is terminated before it could commit across marker expiry.
  perform 1
  from gallr_rehearsal_private.disposable_clone_marker as marker
  where marker.singleton
    and marker.purpose = 'gallr_disposable_staging_clone'
    and marker.valid_until > clock_timestamp() + interval '31 seconds'
    and marker.marker_id = config.marker_id
    and marker.governance_mode = config.governance_mode
    and marker.policy_issued_at = config.policy_issued_at
    and marker.valid_until = config.valid_until
    and marker.policy_sha256 = config.policy_sha256
    and marker.staging_project_ref_sha256 = config.staging_ref_sha256
    and marker.production_project_ref_sha256 = config.production_ref_sha256
    and marker.repository_commit = config.repository_commit
    and marker.operator_manifest_sha256 = config.operator_manifest_sha256;
  if not found then
    raise exception using
      errcode = '42501',
      message = 'postgrest_mutation_marker_expired_before_commit';
  end if;
end
$mutation$;

commit;

select 'GALLR_POSTGREST_MUTATION_COMPLETE';
