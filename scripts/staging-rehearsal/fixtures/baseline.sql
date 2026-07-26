\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on

set timezone = 'UTC';

with canonical as (
  select
    count(*)::bigint as row_count,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(exhibition.id, 'UTF8'))::text
                || ':' || exhibition.id,
              '' order by exhibition.id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ) as id_checksum_sha256
  from content.exhibitions as exhibition
),
v2 as (
  select * from public.exhibition_catalog_v2_integrity(null, false)
),
legacy as (
  select * from public.exhibition_reader_integrity(null, false)
),
legacy_catalog as (
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
  ) as catalog_checksum_sha256
  from public.exhibitions as exhibition
),
tracked_resources(resource) as (
  values
    ('canonical'),
    ('versions'),
    ('events'),
    ('editors'),
    ('media'),
    ('attachments'),
    ('curations'),
    ('submissions'),
    ('submission_media'),
    ('import_rows'),
    ('import_links')
),
tracked_rows(resource, row_key, row_payload) as (
  select 'canonical', exhibition.id, to_jsonb(exhibition)::text
  from content.exhibitions as exhibition
  union all
  select 'versions', version.id::text, to_jsonb(version)::text
  from content.exhibition_versions as version
  union all
  select 'events', event.id, to_jsonb(event)::text
  from public.events as event
  union all
  select 'editors', editor.id, to_jsonb(editor)::text
  from public.editors as editor
  union all
  select 'media', asset.id::text, to_jsonb(asset)::text
  from content.media_assets as asset
  union all
  select
    'attachments',
    jsonb_build_array(attachment.version_id, attachment.media_id)::text,
    to_jsonb(attachment)::text
  from content.exhibition_version_media as attachment
  union all
  select 'curations', placement.id::text, to_jsonb(placement)::text
  from content.curation_placements as placement
  union all
  select 'submissions', submission.id::text, to_jsonb(submission)::text
  from content.exhibition_submissions as submission
  union all
  select
    'submission_media',
    jsonb_build_array(attachment.submission_id, attachment.media_id)::text,
    to_jsonb(attachment)::text
  from content.submission_media as attachment
  union all
  select
    'import_rows',
    jsonb_build_array(imported.batch_id, imported.row_ordinal)::text,
    to_jsonb(imported)::text
  from content.legacy_import_rows as imported
  union all
  select
    'import_links',
    jsonb_build_array(link.source_system, link.source_id)::text,
    to_jsonb(link)::text
  from content.legacy_import_links as link
),
tracked as (
  select
    resource.resource,
    count(row.row_key)::bigint as row_count,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(row.row_key, 'UTF8'))::text
                || ':' || row.row_key
                || octet_length(convert_to(row.row_payload, 'UTF8'))::text
                || ':' || row.row_payload,
              '' order by row.row_key
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ) as catalog_checksum_sha256
  from tracked_resources as resource
  left join tracked_rows as row using (resource)
  group by resource.resource
)
select
  concat_ws(
    chr(9),
    canonical.row_count::text,
    canonical.id_checksum_sha256,
    (select catalog_checksum_sha256 from tracked where resource = 'canonical'),
    (select row_count::text from tracked where resource = 'versions'),
    (select catalog_checksum_sha256 from tracked where resource = 'versions'),
    v2.row_count::text,
    v2.id_checksum_sha256,
    v2.catalog_checksum_sha256,
    legacy.row_count::text,
    legacy.id_checksum_sha256,
    legacy_catalog.catalog_checksum_sha256,
    (select row_count::text from tracked where resource = 'events'),
    (select catalog_checksum_sha256 from tracked where resource = 'events'),
    (select row_count::text from tracked where resource = 'editors'),
    (select catalog_checksum_sha256 from tracked where resource = 'editors'),
    (select row_count::text from tracked where resource = 'media'),
    (select catalog_checksum_sha256 from tracked where resource = 'media'),
    (select row_count::text from tracked where resource = 'attachments'),
    (select catalog_checksum_sha256 from tracked where resource = 'attachments'),
    (select row_count::text from tracked where resource = 'curations'),
    (select catalog_checksum_sha256 from tracked where resource = 'curations'),
    (select row_count::text from tracked where resource = 'submissions'),
    (select catalog_checksum_sha256 from tracked where resource = 'submissions'),
    (select row_count::text from tracked where resource = 'submission_media'),
    (select catalog_checksum_sha256 from tracked where resource = 'submission_media'),
    (select row_count::text from tracked where resource = 'import_rows'),
    (select catalog_checksum_sha256 from tracked where resource = 'import_rows'),
    (select row_count::text from tracked where resource = 'import_links'),
    (select catalog_checksum_sha256 from tracked where resource = 'import_links'),
    (select count(*)::text from content.audit_log),
    to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
  )
from canonical
cross join v2
cross join legacy
cross join legacy_catalog;
