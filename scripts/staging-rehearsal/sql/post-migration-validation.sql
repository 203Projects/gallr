\set ON_ERROR_STOP on
\pset pager off
\pset null '(null)'
\if :{?expected_runtime}
\else
  \set expected_runtime sheet-owned
\endif
\if :{?require_representative_data}
\else
  \set require_representative_data false
\endif
\if :{?expected_legacy_payload_sha256}
\else
  \set expected_legacy_payload_sha256 not-checked
\endif

-- Trusted direct-database evidence. The script does not mutate application
-- data; it exits non-zero when the projection or access contract is invalid.
begin transaction isolation level repeatable read read only;

select
  pg_catalog.clock_timestamp() at time zone 'UTC' as captured_at_utc,
  pg_catalog.current_database() as database_name,
  current_user as database_user,
  pg_catalog.current_setting('server_version') as server_version;

with legacy_payloads as (
  select
    exhibition.id,
    (to_jsonb(exhibition) - 'ticket_url')::text as payload
  from public.exhibitions as exhibition
)
select encode(
  extensions.digest(
    pg_catalog.convert_to(
      coalesce(
        string_agg(
          pg_catalog.octet_length(
            pg_catalog.convert_to(payload.id, 'UTF8')
          )::text || ':' || payload.id
            || pg_catalog.octet_length(
              pg_catalog.convert_to(payload.payload, 'UTF8')
            )::text || ':' || payload.payload,
          '' order by payload.id
        ),
        ''
      ),
      'UTF8'
    ),
    'sha256'
  ),
  'hex'
) as legacy_full_payload_sha256
from legacy_payloads as payload
\gset

\echo legacy_full_payload_sha256=:legacy_full_payload_sha256
select
  :'legacy_full_payload_sha256'::text as legacy_full_payload_sha256,
  :'expected_legacy_payload_sha256'::text as expected_legacy_payload_sha256;

select case
  when :'expected_legacy_payload_sha256' = 'not-checked' then true
  else :'legacy_full_payload_sha256' = :'expected_legacy_payload_sha256'
end as legacy_payload_preserved
\gset

\if :legacy_payload_preserved
  \echo 'Legacy business payload matches the retained pre-migration fingerprint.'
\else
  \echo 'ERROR: legacy business payload changed after migration.'
  select 1 / 0 as staging_validation_failed;
\endif

select
  pg_catalog.to_regclass('content.exhibitions') is not null
    and pg_catalog.to_regclass('content.exhibition_versions') is not null
    and pg_catalog.to_regclass('public.exhibition_catalog_v2') is not null
    and pg_catalog.to_regprocedure(
      'public.exhibition_catalog_v2_integrity(text,boolean)'
    ) is not null
    and pg_catalog.to_regprocedure(
      'public.exhibition_reader_integrity(text,boolean)'
    ) is not null
    and pg_catalog.to_regprocedure(
      'public.admin_reconcile_exhibition_catalog_v2()'
    ) is not null
    as required_objects_exist
\gset

\if :required_objects_exist
  \echo 'Required canonical catalog objects are installed.'
\else
  \echo 'ERROR: required canonical catalog objects are missing.'
  select 1 / 0 as staging_validation_failed;
\endif

with expected as (
  select exhibition.id
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = exhibition.published_version_id
  where exhibition.archived_at is null
    and version.status::text = 'published'
), actual as (
  select catalog.id
  from public.exhibition_catalog_v2 as catalog
), differences as (
  select
    coalesce(expected.id, actual.id) as id,
    case
      when expected.id is null then 'only_in_projection'
      when actual.id is null then 'only_in_canonical'
    end as status
  from expected
  full join actual using (id)
  where expected.id is null or actual.id is null
)
select
  (select count(*) from expected)::bigint as expected_published_count,
  (select count(*) from actual)::bigint as catalog_v2_count,
  (select count(*) from differences)::bigint as membership_difference_count,
  not exists (select 1 from differences) as membership_ok
\gset

select
  :'expected_published_count'::bigint as expected_published_count,
  :'catalog_v2_count'::bigint as catalog_v2_count,
  :'membership_difference_count'::bigint as membership_difference_count,
  :'membership_ok'::boolean as membership_ok;

\if :membership_ok
  \echo 'Canonical identity membership matches catalog V2.'
\else
  \echo 'ERROR: canonical identity membership differs from catalog V2.'
  with expected as (
    select exhibition.id
    from content.exhibitions as exhibition
    join content.exhibition_versions as version
      on version.exhibition_id = exhibition.id
     and version.id = exhibition.published_version_id
    where exhibition.archived_at is null
      and version.status::text = 'published'
  ), actual as (
    select catalog.id
    from public.exhibition_catalog_v2 as catalog
  )
  select
    coalesce(expected.id, actual.id) as id,
    case
      when expected.id is null then 'only_in_projection'
      when actual.id is null then 'only_in_canonical'
    end as status
  from expected
  full join actual using (id)
  where expected.id is null or actual.id is null
  order by id
  limit 100;
  select 1 / 0 as staging_validation_failed;
\endif

select *
from public.exhibition_reader_integrity(null, false);

select *
from public.exhibition_catalog_v2_integrity(null, false);

select public.admin_reconcile_exhibition_catalog_v2() as reconciliation;

select
  coalesce(
    (public.admin_reconcile_exhibition_catalog_v2() ->> 'in_sync')::boolean,
    false
  ) as reconciliation_ok
\gset

\if :reconciliation_ok
  \echo 'Canonical source and catalog V2 reconcile exactly.'
\else
  \echo 'ERROR: canonical source and catalog V2 do not reconcile.'
  select 1 / 0 as staging_validation_failed;
\endif

select
  runtime.legacy_mirror_enabled,
  runtime.legacy_writes_blocked,
  runtime.legacy_mirror_enabled_at,
  runtime.baseline_row_count,
  runtime.baseline_id_checksum_sha256,
  runtime.baseline_catalog_checksum_sha256,
  runtime.reason
from content_private.exhibition_catalog_runtime as runtime
where runtime.singleton;

select case :'expected_runtime'
  when 'sheet-owned' then
    count(*) = 1
    and coalesce(bool_and(
      not runtime.legacy_mirror_enabled
      and not runtime.legacy_writes_blocked
      and runtime.legacy_mirror_enabled_at is null
      and runtime.baseline_row_count is null
      and runtime.baseline_id_checksum_sha256 is null
      and runtime.baseline_catalog_checksum_sha256 is null
    ), false)
  when 'canonical-owned' then
    count(*) = 1
    and coalesce(bool_and(
      runtime.legacy_mirror_enabled
      and runtime.legacy_writes_blocked
      and runtime.legacy_mirror_enabled_at is not null
      and runtime.baseline_row_count is not null
      and runtime.baseline_id_checksum_sha256 is not null
      and runtime.baseline_catalog_checksum_sha256 is not null
    ), false)
  else false
end as expected_runtime_ok
from content_private.exhibition_catalog_runtime as runtime
where runtime.singleton
\gset

\if :expected_runtime_ok
  \echo 'Bridge runtime matches the requested ownership state.'
\else
  \echo 'ERROR: bridge runtime does not match the requested ownership state.'
  select 1 / 0 as staging_validation_failed;
\endif

-- These checks prove the Data API roles can read only the intended projection
-- and integrity function. Direct behavioral checks live in separate sessions.
select
  pg_catalog.has_table_privilege(
    'anon', 'public.exhibition_catalog_v2', 'SELECT'
  )
  and pg_catalog.has_table_privilege(
    'anon', 'public.exhibitions', 'SELECT'
  )
  and not pg_catalog.has_table_privilege(
    'anon', 'public.exhibition_catalog_v2', 'INSERT'
  )
  and not pg_catalog.has_table_privilege(
    'anon', 'public.exhibition_catalog_v2', 'UPDATE'
  )
  and not pg_catalog.has_table_privilege(
    'anon', 'public.exhibition_catalog_v2', 'DELETE'
  )
  and not pg_catalog.has_table_privilege(
    'anon', 'public.exhibition_catalog_v2', 'TRUNCATE'
  )
  and not pg_catalog.has_any_column_privilege(
    'anon', 'public.exhibition_catalog_v2', 'INSERT'
  )
  and not pg_catalog.has_any_column_privilege(
    'anon', 'public.exhibition_catalog_v2', 'UPDATE'
  )
  and not pg_catalog.has_any_column_privilege(
    'anon', 'public.exhibition_catalog_v2', 'REFERENCES'
  )
  and pg_catalog.has_function_privilege(
    'anon',
    'public.exhibition_catalog_v2_integrity(text,boolean)',
    'EXECUTE'
  )
  and not pg_catalog.has_schema_privilege('anon', 'content', 'USAGE')
  and not pg_catalog.has_schema_privilege('anon', 'content_private', 'USAGE')
  and not pg_catalog.has_function_privilege(
    'anon',
    'public.admin_reconcile_exhibition_catalog_v2()',
    'EXECUTE'
  )
  and not pg_catalog.has_function_privilege(
    'anon',
    'public.admin_enable_legacy_exhibition_mirror(bigint,text,text,text)',
    'EXECUTE'
  )
  and not pg_catalog.has_function_privilege(
    'anon',
    'public.admin_disable_legacy_exhibition_mirror(text)',
    'EXECUTE'
  )
  and not pg_catalog.has_table_privilege(
    'anon', 'public.exhibitions', 'INSERT'
  )
  and not pg_catalog.has_table_privilege(
    'anon', 'public.exhibitions', 'UPDATE'
  )
  and not pg_catalog.has_table_privilege(
    'anon', 'public.exhibitions', 'DELETE'
  )
  and not pg_catalog.has_table_privilege(
    'anon', 'public.exhibitions', 'TRUNCATE'
  )
  and not pg_catalog.has_any_column_privilege(
    'anon', 'public.exhibitions', 'INSERT'
  )
  and not pg_catalog.has_any_column_privilege(
    'anon', 'public.exhibitions', 'UPDATE'
  )
  and pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibition_catalog_v2', 'SELECT'
  )
  and pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibitions', 'SELECT'
  )
  and pg_catalog.has_function_privilege(
    'authenticated',
    'public.exhibition_catalog_v2_integrity(text,boolean)',
    'EXECUTE'
  )
  and not pg_catalog.has_function_privilege(
    'authenticated',
    'public.admin_reconcile_exhibition_catalog_v2()',
    'EXECUTE'
  )
  and not pg_catalog.has_function_privilege(
    'authenticated',
    'public.admin_enable_legacy_exhibition_mirror(bigint,text,text,text)',
    'EXECUTE'
  )
  and not pg_catalog.has_function_privilege(
    'authenticated',
    'public.admin_disable_legacy_exhibition_mirror(text)',
    'EXECUTE'
  )
  and not pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibition_catalog_v2', 'INSERT'
  )
  and not pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibition_catalog_v2', 'UPDATE'
  )
  and not pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibition_catalog_v2', 'DELETE'
  )
  and not pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibition_catalog_v2', 'TRUNCATE'
  )
  and not pg_catalog.has_any_column_privilege(
    'authenticated', 'public.exhibition_catalog_v2', 'INSERT'
  )
  and not pg_catalog.has_any_column_privilege(
    'authenticated', 'public.exhibition_catalog_v2', 'UPDATE'
  )
  and not pg_catalog.has_any_column_privilege(
    'authenticated', 'public.exhibition_catalog_v2', 'REFERENCES'
  )
  and not pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibitions', 'INSERT'
  )
  and not pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibitions', 'UPDATE'
  )
  and not pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibitions', 'DELETE'
  )
  and not pg_catalog.has_table_privilege(
    'authenticated', 'public.exhibitions', 'TRUNCATE'
  )
  and not pg_catalog.has_any_column_privilege(
    'authenticated', 'public.exhibitions', 'INSERT'
  )
  and not pg_catalog.has_any_column_privilege(
    'authenticated', 'public.exhibitions', 'UPDATE'
  )
    as data_api_access_contract_ok
\gset

\if :data_api_access_contract_ok
  \echo 'Data API catalog grants match the intended access contract.'
\else
  \echo 'ERROR: Data API catalog grants violate the access contract.'
  select 1 / 0 as staging_validation_failed;
\endif

-- Representative-data matrix. Early post-migration/import phases record it
-- without requiring non-zero categories. The final-representative phase runs
-- after reviewed admin lifecycle cases and staging fixtures and fails unless
-- every category is represented.
with published as (
  select version.*
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = exhibition.published_version_id
  where exhibition.archived_at is null
    and version.status::text = 'published'
)
select
  count(*) filter (
    where exists (
      select 1
      from content.exhibition_version_media as attachment
      join content.media_assets as asset on asset.id = attachment.media_id
      where attachment.version_id = published.id
        and attachment.role::text = 'cover'
        and asset.status::text = 'published'
        and asset.public_url is not null
    )
  )::bigint as published_with_cover_media,
  count(*) filter (
    where not exists (
      select 1
      from content.exhibition_version_media as attachment
      join content.media_assets as asset on asset.id = attachment.media_id
      where attachment.version_id = published.id
        and attachment.role::text = 'cover'
        and asset.status::text = 'published'
        and asset.public_url is not null
    )
  )::bigint as published_without_cover_media,
  count(*) filter (
    where coalesce(
      (
        select placement.enabled
        from content.curation_placements as placement
        where placement.exhibition_id = published.exhibition_id
          and placement.surface::text = 'app_featured'
      ),
      published.is_featured
    )
  )::bigint as featured,
  count(*) filter (
    where coalesce(
      (
        select placement.enabled
        from content.curation_placements as placement
        where placement.exhibition_id = published.exhibition_id
          and placement.surface::text = 'homepage'
      ),
      published.is_homepage_featured
    )
  )::bigint
    as homepage_featured,
  count(*) filter (where published.event_id is not null)::bigint
    as event_linked,
  count(*) filter (where published.editor_id is not null)::bigint
    as editor_linked,
  count(*) filter (
    where pg_catalog.octet_length(published.exhibition_id)
      > pg_catalog.length(published.exhibition_id)
      or pg_catalog.octet_length(published.name_ko)
        > pg_catalog.length(published.name_ko)
  )::bigint as non_ascii_identity_or_text,
  count(*) filter (
    where published.latitude is null and published.longitude is null
  )::bigint as null_coordinates,
  count(*) filter (
    where published.legacy_cover_image_url is not null
      and not exists (
        select 1
        from content.exhibition_version_media as attachment
        join content.media_assets as asset on asset.id = attachment.media_id
        where attachment.version_id = published.id
          and attachment.role::text = 'cover'
          and asset.status::text = 'published'
          and asset.public_url is not null
      )
  )::bigint as legacy_cover_fallback
from published
\gset matrix_

select
  count(*) filter (
    where exhibition.published_version_id is null
      and exists (
        select 1
        from content.exhibition_versions as version
        where version.exhibition_id = exhibition.id
          and version.status::text = 'draft'
      )
  )::bigint as draft_only,
  count(*) filter (where exhibition.archived_at is not null)::bigint as archived
from content.exhibitions as exhibition
\gset matrix_

select
  :'matrix_published_with_cover_media'::bigint as published_with_cover_media,
  :'matrix_published_without_cover_media'::bigint as published_without_cover_media,
  :'matrix_featured'::bigint as featured,
  :'matrix_homepage_featured'::bigint as homepage_featured,
  :'matrix_event_linked'::bigint as event_linked,
  :'matrix_editor_linked'::bigint as editor_linked,
  :'matrix_non_ascii_identity_or_text'::bigint as non_ascii_identity_or_text,
  :'matrix_null_coordinates'::bigint as null_coordinates,
  :'matrix_legacy_cover_fallback'::bigint as legacy_cover_fallback,
  :'matrix_draft_only'::bigint as draft_only,
  :'matrix_archived'::bigint as archived;

select case when :'require_representative_data'::boolean then
  :'matrix_published_with_cover_media'::bigint > 0
  and :'matrix_published_without_cover_media'::bigint > 0
  and :'matrix_featured'::bigint > 0
  and :'matrix_homepage_featured'::bigint > 0
  and :'matrix_event_linked'::bigint > 0
  and :'matrix_editor_linked'::bigint > 0
  and :'matrix_non_ascii_identity_or_text'::bigint > 0
  and :'matrix_null_coordinates'::bigint > 0
  and :'matrix_legacy_cover_fallback'::bigint > 0
  and :'matrix_draft_only'::bigint > 0
  and :'matrix_archived'::bigint > 0
else true end as representative_matrix_ok
\gset

\if :representative_matrix_ok
  \echo 'Representative-data matrix matches this evidence phase.'
\else
  \echo 'ERROR: final representative-data matrix contains a zero category.'
  select 1 / 0 as staging_validation_failed;
\endif

commit;
