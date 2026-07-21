-- Caller owns the transaction and sets TimeZone=UTC before including this file.
-- The result fingerprints every pre-existing table that fixture cleanup can
-- mutate through DML/cascades or whose references can make deletion unsafe.
create temp table fixture_tracked_state (
  resource text primary key,
  row_count bigint not null,
  catalog_checksum_sha256 text not null
) on commit drop;

insert into fixture_tracked_state (
  resource,
  row_count,
  catalog_checksum_sha256
)
with tracked_resources(resource) as (
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
)
select
  resource.resource,
  count(row.row_key)::bigint,
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
  )
from tracked_resources as resource
left join tracked_rows as row using (resource)
group by resource.resource;
