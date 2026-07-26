\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on

begin;
set local lock_timeout = '10s';
set local statement_timeout = '10min';
set local idle_in_transaction_session_timeout = '2min';
set local timezone = 'UTC';

create temp table fixture_config (
  staging_ref_sha256 text not null,
  production_ref_sha256 text not null,
  confirmation_ref_sha256 text not null,
  run_id text not null,
  fixture_prefix text not null,
  load_event_id text not null,
  empty_event_id text not null,
  editor_id text not null,
  boundary_id text not null,
  mutation_id text not null,
  media_object_path text not null,
  fixture_version_id_hash text not null,
  fixture_media_id_hash text not null,
  fixture_attachment_id_hash text not null,
  fixture_curation_id_hash text not null,
  baseline_canonical_count bigint not null,
  baseline_canonical_id_hash text not null,
  baseline_canonical_catalog_hash text not null,
  baseline_version_count bigint not null,
  baseline_version_catalog_hash text not null,
  baseline_v2_count bigint not null,
  baseline_v2_id_hash text not null,
  baseline_v2_catalog_hash text not null,
  baseline_legacy_count bigint not null,
  baseline_legacy_id_hash text not null,
  baseline_legacy_catalog_hash text not null,
  baseline_event_count bigint not null,
  baseline_event_catalog_hash text not null,
  baseline_editor_count bigint not null,
  baseline_editor_catalog_hash text not null,
  baseline_media_count bigint not null,
  baseline_media_catalog_hash text not null,
  baseline_attachment_count bigint not null,
  baseline_attachment_catalog_hash text not null,
  baseline_curation_count bigint not null,
  baseline_curation_catalog_hash text not null,
  baseline_submission_count bigint not null,
  baseline_submission_catalog_hash text not null,
  baseline_submission_media_count bigint not null,
  baseline_submission_media_catalog_hash text not null,
  baseline_import_row_count bigint not null,
  baseline_import_row_catalog_hash text not null,
  baseline_import_link_count bigint not null,
  baseline_import_link_catalog_hash text not null,
  baseline_audit_count bigint not null
) on commit drop;

insert into fixture_config values (
  :'staging_ref_sha256',
  :'production_ref_sha256',
  :'confirmation_ref_sha256',
  :'run_id',
  :'fixture_prefix',
  :'load_event_id',
  :'empty_event_id',
  :'editor_id',
  :'boundary_id',
  :'mutation_id',
  :'media_object_path',
  :'fixture_version_id_hash',
  :'fixture_media_id_hash',
  :'fixture_attachment_id_hash',
  :'fixture_curation_id_hash',
  :'baseline_canonical_count'::bigint,
  :'baseline_canonical_id_hash',
  :'baseline_canonical_catalog_hash',
  :'baseline_version_count'::bigint,
  :'baseline_version_catalog_hash',
  :'baseline_v2_count'::bigint,
  :'baseline_v2_id_hash',
  :'baseline_v2_catalog_hash',
  :'baseline_legacy_count'::bigint,
  :'baseline_legacy_id_hash',
  :'baseline_legacy_catalog_hash',
  :'baseline_event_count'::bigint,
  :'baseline_event_catalog_hash',
  :'baseline_editor_count'::bigint,
  :'baseline_editor_catalog_hash',
  :'baseline_media_count'::bigint,
  :'baseline_media_catalog_hash',
  :'baseline_attachment_count'::bigint,
  :'baseline_attachment_catalog_hash',
  :'baseline_curation_count'::bigint,
  :'baseline_curation_catalog_hash',
  :'baseline_submission_count'::bigint,
  :'baseline_submission_catalog_hash',
  :'baseline_submission_media_count'::bigint,
  :'baseline_submission_media_catalog_hash',
  :'baseline_import_row_count'::bigint,
  :'baseline_import_row_catalog_hash',
  :'baseline_import_link_count'::bigint,
  :'baseline_import_link_catalog_hash',
  :'baseline_audit_count'::bigint
);

do $lock$
begin
  perform pg_catalog.pg_advisory_xact_lock(73243, 1205);
end
$lock$;

do $guard$
declare
  config fixture_config%rowtype;
begin
  select * into strict config from fixture_config;
  if config.staging_ref_sha256 !~ '^[0-9a-f]{64}$'
     or config.production_ref_sha256 !~ '^[0-9a-f]{64}$'
     or config.staging_ref_sha256 = config.production_ref_sha256
     or config.fixture_version_id_hash !~ '^[0-9a-f]{64}$'
     or config.fixture_media_id_hash !~ '^[0-9a-f]{64}$'
     or config.fixture_attachment_id_hash !~ '^[0-9a-f]{64}$'
     or config.fixture_curation_id_hash !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'distinct_staging_ref_required';
  end if;
  if config.confirmation_ref_sha256 <> config.staging_ref_sha256 then
    raise exception using errcode = '42501', message = 'fixture_cleanup_confirmation_invalid';
  end if;
  if config.run_id !~ '^[a-z0-9][a-z0-9-]{7,31}$'
     or config.fixture_prefix <> 'gallr-rehearsal-' || config.run_id || '-' then
    raise exception using errcode = '22023', message = 'fixture_identity_invalid';
  end if;
  if to_regclass('content.exhibitions') is null
     or to_regclass('content.exhibition_versions') is null
     or to_regclass('content.media_assets') is null
     or to_regclass('content.exhibition_version_media') is null
     or to_regclass('content.curation_placements') is null
     or to_regclass('content.exhibition_submissions') is null
     or to_regclass('content.submission_media') is null
     or to_regclass('content.legacy_import_rows') is null
     or to_regclass('content.legacy_import_links') is null
     or to_regclass('content.audit_log') is null
     or to_regclass('content_private.exhibition_catalog_runtime') is null
     or to_regclass('public.exhibition_catalog_v2') is null
     or to_regclass('public.exhibitions') is null
     or to_regclass('public.events') is null
     or to_regclass('public.editors') is null
     or to_regprocedure('public.exhibition_catalog_v2_integrity(text,boolean)') is null
     or to_regprocedure('public.exhibition_reader_integrity(text,boolean)') is null
     or to_regprocedure('public.admin_reconcile_exhibition_catalog_v2()') is null then
    raise exception using errcode = '55000', message = 'required_catalog_schema_not_deployed';
  end if;
  if (select count(*) from content_private.exhibition_catalog_runtime
      where singleton) <> 1 then
    raise exception using errcode = '55000', message = 'catalog_runtime_singleton_invalid';
  end if;
end
$guard$;

-- Hold the same tracked/reference tables quiet through manifest validation,
-- exact deletion, and the final restored-baseline hashes.
lock table
  content_private.exhibition_catalog_runtime,
  content.exhibitions,
  content.exhibition_versions,
  content.media_assets,
  content.exhibition_version_media,
  content.curation_placements,
  content.exhibition_submissions,
  content.submission_media,
  content.legacy_import_rows,
  content.legacy_import_links,
  content.audit_log,
  public.events,
  public.editors,
  public.exhibition_catalog_v2,
  public.exhibitions
in share mode;

create temp table fixture_rows (
  ordinal integer primary key,
  exhibition_id text not null unique
) on commit drop;

insert into fixture_rows (ordinal, exhibition_id)
select
  series.ordinal,
  case series.ordinal
    when 500 then config.boundary_id
    when 750 then config.mutation_id
    else config.fixture_prefix || 'catalog-' || lpad(series.ordinal::text, 4, '0')
  end
from fixture_config as config
cross join generate_series(1, 1205) as series(ordinal);

-- Refuse partial, expanded, or colliding fixture sets. Cleanup is allowed only
-- for the exact deterministic manifest identity created by provision.sql.
do $manifest_guard$
declare
  config fixture_config%rowtype;
  load_integrity record;
  version_id_hash text;
  media_id_hash text;
  attachment_id_hash text;
  curation_id_hash text;
begin
  select * into strict config from fixture_config;
  select * into strict load_integrity
  from public.exhibition_catalog_v2_integrity(config.load_event_id, false);

  select encode(
    extensions.digest(
      convert_to(
        coalesce(
          string_agg(
            octet_length(convert_to(version.id::text, 'UTF8'))::text
              || ':' || version.id::text,
            '' order by version.id
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ) into version_id_hash
  from content.exhibition_versions as version
  join fixture_rows as row on row.exhibition_id = version.exhibition_id;

  select encode(
    extensions.digest(
      convert_to(
        coalesce(
          string_agg(
            octet_length(convert_to(asset.id::text, 'UTF8'))::text
              || ':' || asset.id::text,
            '' order by asset.id
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ) into media_id_hash
  from content.media_assets as asset
  where asset.object_path = config.media_object_path;

  select encode(
    extensions.digest(
      convert_to(
        coalesce(
          string_agg(
            octet_length(
              convert_to(
                jsonb_build_array(attachment.version_id, attachment.media_id)::text,
                'UTF8'
              )
            )::text
              || ':'
              || jsonb_build_array(attachment.version_id, attachment.media_id)::text,
            '' order by attachment.version_id, attachment.media_id
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ) into attachment_id_hash
  from content.exhibition_version_media as attachment
  join content.exhibition_versions as version on version.id = attachment.version_id
  join fixture_rows as row on row.exhibition_id = version.exhibition_id;

  select encode(
    extensions.digest(
      convert_to(
        coalesce(
          string_agg(
            octet_length(convert_to(placement.id::text, 'UTF8'))::text
              || ':' || placement.id::text,
            '' order by placement.id
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ) into curation_id_hash
  from content.curation_placements as placement
  join fixture_rows as row on row.exhibition_id = placement.exhibition_id;

  if (select count(*) from fixture_rows) <> 1205
     or exists (
       select row.exhibition_id from fixture_rows as row
       except
       select exhibition.id from content.exhibitions as exhibition
     )
     or exists (
       select exhibition.id
       from content.exhibitions as exhibition
       where left(exhibition.id, length(config.fixture_prefix)) = config.fixture_prefix
       except
       select row.exhibition_id from fixture_rows as row
     )
     or (select count(*) from content.exhibition_versions as version
         join fixture_rows as row on row.exhibition_id = version.exhibition_id) <> 1205
     or version_id_hash is distinct from config.fixture_version_id_hash
     or (select count(*) from content.curation_placements as placement
         join fixture_rows as row on row.exhibition_id = placement.exhibition_id) <> 2
     or curation_id_hash is distinct from config.fixture_curation_id_hash
     or (select count(*) from content.exhibition_version_media as attachment
         join content.exhibition_versions as version on version.id = attachment.version_id
         join fixture_rows as row on row.exhibition_id = version.exhibition_id) <> 1
     or attachment_id_hash is distinct from config.fixture_attachment_id_hash
     or (select count(*) from content.media_assets as asset
         where asset.object_path = config.media_object_path) <> 1
     or media_id_hash is distinct from config.fixture_media_id_hash
     or not exists (
       select 1
       from content.curation_placements as placement
       join fixture_rows as row on row.exhibition_id = placement.exhibition_id
       where row.ordinal = 6
         and placement.surface = 'app_featured'::content.curation_surface
         and placement.position = 0
         and placement.enabled
     )
     or not exists (
       select 1
       from content.curation_placements as placement
       join fixture_rows as row on row.exhibition_id = placement.exhibition_id
       where row.ordinal = 7
         and placement.surface = 'homepage'::content.curation_surface
         and placement.position = 0
         and placement.enabled
     )
     or not exists (
       select 1
       from content.exhibition_version_media as attachment
       join content.exhibition_versions as version on version.id = attachment.version_id
       join fixture_rows as row on row.exhibition_id = version.exhibition_id
       join content.media_assets as asset on asset.id = attachment.media_id
       where row.ordinal = 5
         and attachment.role = 'cover'::content.media_role
         and attachment.sort_order = 0
         and asset.object_path = config.media_object_path
         and asset.metadata @> jsonb_build_object(
           'fixture_run_id', config.run_id,
           'bytes_uploaded', false
         )
     )
     or exists (
       select 1
       from content.exhibition_submissions as submission
       join fixture_rows as row
         on row.exhibition_id = submission.accepted_exhibition_id
     )
     or exists (
       select 1
       from content.exhibition_versions as version
       where (
         version.event_id in (config.load_event_id, config.empty_event_id)
         or version.editor_id = config.editor_id
       )
         and not exists (
           select 1 from fixture_rows as row
           where row.exhibition_id = version.exhibition_id
         )
     )
     or exists (
       select 1
       from public.exhibitions as legacy
       where (
         legacy.event_id in (config.load_event_id, config.empty_event_id)
         or legacy.editor_id = config.editor_id
       )
         and not exists (
           select 1 from fixture_rows as row
           where row.exhibition_id = legacy.id
         )
     )
     or exists (
       select 1
       from content.exhibition_version_media as attachment
       join content.media_assets as asset on asset.id = attachment.media_id
       join content.exhibition_versions as version on version.id = attachment.version_id
       where asset.object_path = config.media_object_path
         and not exists (
           select 1 from fixture_rows as row
           where row.exhibition_id = version.exhibition_id
         )
     )
     or exists (
       select 1
       from content.submission_media as attachment
       join content.media_assets as asset on asset.id = attachment.media_id
       where asset.object_path = config.media_object_path
     )
     or exists (
       select 1
       from content.legacy_import_rows as imported
       join content.exhibition_versions as version
         on version.id = imported.applied_version_id
       join fixture_rows as row on row.exhibition_id = version.exhibition_id
     )
     or exists (
       select 1
       from content.legacy_import_links as link
       join fixture_rows as row on row.exhibition_id = link.exhibition_id
     )
     or (select count(*) from public.events
         where id in (config.load_event_id, config.empty_event_id)) <> 2
     or (select count(*) from public.editors where id = config.editor_id) <> 1
     or load_integrity.row_count <> 1205
     or (select count(*)
         from public.exhibitions as legacy
         where left(legacy.id, length(config.fixture_prefix)) = config.fixture_prefix)
          <> case when (
               select runtime.legacy_mirror_enabled
               from content_private.exhibition_catalog_runtime as runtime
               where runtime.singleton
             ) then 1205 else 0 end then
    raise exception using errcode = '23514', message = 'fixture_manifest_drift_detected';
  end if;
end
$manifest_guard$;

create temp table cleanup_counts (
  resource text primary key,
  affected_count bigint not null
) on commit drop;

with deleted as (
  delete from content.curation_placements as placement
  using fixture_rows as row
  where placement.exhibition_id = row.exhibition_id
  returning placement.id
)
insert into cleanup_counts
select 'curation_placements', count(*)::bigint from deleted;

with deleted as (
  delete from content.exhibition_version_media as attachment
  using content.exhibition_versions as version, fixture_rows as row
  where attachment.version_id = version.id
    and version.exhibition_id = row.exhibition_id
  returning attachment.media_id
)
insert into cleanup_counts
select 'version_media', count(*)::bigint from deleted;

with updated as (
  update content.exhibitions as exhibition
  set published_version_id = null, updated_at = clock_timestamp()
  from fixture_rows as row
  where exhibition.id = row.exhibition_id
  returning exhibition.id
)
insert into cleanup_counts
select 'publication_pointers', count(*)::bigint from updated;

with deleted as (
  delete from content.exhibition_versions as version
  using fixture_rows as row
  where version.exhibition_id = row.exhibition_id
  returning version.id
)
insert into cleanup_counts
select 'exhibition_versions', count(*)::bigint from deleted;

with deleted as (
  delete from content.exhibitions as exhibition
  using fixture_rows as row
  where exhibition.id = row.exhibition_id
  returning exhibition.id
)
insert into cleanup_counts
select 'canonical_exhibitions', count(*)::bigint from deleted;

with deleted as (
  delete from content.media_assets as asset
  using fixture_config as config
  where asset.object_path = config.media_object_path
  returning asset.id
)
insert into cleanup_counts
select 'media_assets', count(*)::bigint from deleted;

with deleted as (
  delete from public.events as event
  using fixture_config as config
  where event.id in (config.load_event_id, config.empty_event_id)
  returning event.id
)
insert into cleanup_counts
select 'events', count(*)::bigint from deleted;

with deleted as (
  delete from public.editors as editor
  using fixture_config as config
  where editor.id = config.editor_id
  returning editor.id
)
insert into cleanup_counts
select 'editors', count(*)::bigint from deleted;

do $deletion_guard$
begin
  if (select affected_count from cleanup_counts where resource = 'curation_placements') <> 2
     or (select affected_count from cleanup_counts where resource = 'version_media') <> 1
     or (select affected_count from cleanup_counts where resource = 'publication_pointers') <> 1205
     or (select affected_count from cleanup_counts where resource = 'exhibition_versions') <> 1205
     or (select affected_count from cleanup_counts where resource = 'canonical_exhibitions') <> 1205
     or (select affected_count from cleanup_counts where resource = 'media_assets') <> 1
     or (select affected_count from cleanup_counts where resource = 'events') <> 2
     or (select affected_count from cleanup_counts where resource = 'editors') <> 1 then
    raise exception using errcode = '23514', message = 'fixture_cleanup_count_mismatch';
  end if;
end
$deletion_guard$;

\ir tracked-state.sql

-- The canonical projector owns any compatibility-table removal when mirroring
-- is active. Never alter runtime state, grants, or the legacy table directly.
do $restore_guard$
declare
  config fixture_config%rowtype;
  canonical_count bigint;
  canonical_hash text;
  v2 record;
  legacy record;
  legacy_catalog_hash text;
  reconciliation jsonb;
begin
  select * into strict config from fixture_config;

  if exists (
    select 1 from content.exhibitions
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from public.exhibition_catalog_v2
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from public.exhibitions
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from public.events
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from public.editors
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from content.media_assets
    where object_path = config.media_object_path
  ) then
    raise exception using errcode = '23514', message = 'fixture_rows_remain_after_cleanup';
  end if;

  select
    count(*)::bigint,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(exhibition.id)::text || ':' || exhibition.id,
              '' order by exhibition.id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    )
  into canonical_count, canonical_hash
  from content.exhibitions as exhibition;
  select * into strict v2 from public.exhibition_catalog_v2_integrity(null, false);
  select * into strict legacy from public.exhibition_reader_integrity(null, false);
  select public.admin_reconcile_exhibition_catalog_v2() into strict reconciliation;
  select encode(
    extensions.digest(
      convert_to(
        coalesce(
          string_agg(
            octet_length(convert_to(exhibition.id, 'UTF8'))::text
              || ':' || exhibition.id
              || octet_length(
                convert_to(
                  content_private.legacy_exhibition_catalog_v2_checksum(exhibition),
                  'UTF8'
                )
              )::text
              || ':'
              || content_private.legacy_exhibition_catalog_v2_checksum(exhibition),
            '' order by exhibition.id
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ) into legacy_catalog_hash
  from public.exhibitions as exhibition;

  if canonical_count <> config.baseline_canonical_count
     or canonical_hash <> config.baseline_canonical_id_hash
     or (select row_count from fixture_tracked_state where resource = 'canonical')
          <> config.baseline_canonical_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'canonical')
          <> config.baseline_canonical_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'versions')
          <> config.baseline_version_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'versions')
          <> config.baseline_version_catalog_hash
     or v2.row_count <> config.baseline_v2_count
     or v2.id_checksum_sha256 <> config.baseline_v2_id_hash
     or v2.catalog_checksum_sha256 <> config.baseline_v2_catalog_hash
     or legacy.row_count <> config.baseline_legacy_count
     or legacy.id_checksum_sha256 <> config.baseline_legacy_id_hash
     or legacy_catalog_hash <> config.baseline_legacy_catalog_hash
     or not coalesce((reconciliation ->> 'in_sync')::boolean, false)
     or (select row_count from fixture_tracked_state where resource = 'events')
          <> config.baseline_event_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'events')
          <> config.baseline_event_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'editors')
          <> config.baseline_editor_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'editors')
          <> config.baseline_editor_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'media')
          <> config.baseline_media_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'media')
          <> config.baseline_media_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'attachments')
          <> config.baseline_attachment_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'attachments')
          <> config.baseline_attachment_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'curations')
          <> config.baseline_curation_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'curations')
          <> config.baseline_curation_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'submissions')
          <> config.baseline_submission_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'submissions')
          <> config.baseline_submission_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'submission_media')
          <> config.baseline_submission_media_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'submission_media')
          <> config.baseline_submission_media_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'import_rows')
          <> config.baseline_import_row_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'import_rows')
          <> config.baseline_import_row_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'import_links')
          <> config.baseline_import_link_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'import_links')
          <> config.baseline_import_link_catalog_hash
     or (select count(*) from content.audit_log) < config.baseline_audit_count then
    raise exception using errcode = '40001', message = 'staging_baseline_not_restored';
  end if;
end
$restore_guard$;

select jsonb_build_object(
  'state', 'cleaned',
  'captured_at', to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  'fixture_count', 1205,
  'baseline_restored', true,
  'audit_rows_retained', (select count(*)::bigint from content.audit_log),
  'deleted', (
    select jsonb_object_agg(counts.resource, counts.affected_count order by counts.resource)
    from cleanup_counts as counts
  ),
  'v2_integrity', (
    select to_jsonb(integrity)
    from public.exhibition_catalog_v2_integrity(null, false) as integrity
  ),
  'legacy_integrity', (
    select to_jsonb(integrity)
    from public.exhibition_reader_integrity(null, false) as integrity
  ),
  'reconciliation', public.admin_reconcile_exhibition_catalog_v2()
);

commit;
