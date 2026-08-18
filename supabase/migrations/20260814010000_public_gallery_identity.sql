-- Expose the durable gallery organization identity through the canonical
-- public catalogue. Venue names and locations remain immutable exhibition
-- snapshots; gallery_id is only the stable organization link used by follows
-- and future publication-alert subscriptions.

alter table public.exhibition_catalog_v2
  add column gallery_id uuid
  references content.galleries(id) on delete restrict;

create or replace function content_private.assign_exhibition_catalog_gallery_identity()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  new.gallery_id := content_private.sync_gallery_from_catalog(
    new.venue_name_ko,
    new.venue_name_en
  );

  if new.gallery_id is null then
    raise exception using
      errcode = '23502',
      message = 'catalog_gallery_identity_required';
  end if;

  return new;
end;
$function$;

revoke all on function
  content_private.assign_exhibition_catalog_gallery_identity()
  from public, anon, authenticated, service_role;

-- PostgreSQL runs same-event triggers alphabetically. "assign" therefore
-- executes before the existing "derive_checksum" trigger, ensuring gallery_id
-- participates in the canonical row checksum.
create trigger exhibition_catalog_v2_assign_gallery_identity
  before insert or update of venue_name_ko, venue_name_en, gallery_id
  on public.exhibition_catalog_v2
  for each row
  execute function
    content_private.assign_exhibition_catalog_gallery_identity();

-- Route every existing row through the assignment and checksum triggers.
update public.exhibition_catalog_v2
set gallery_id = content_private.sync_gallery_from_catalog(
  venue_name_ko,
  venue_name_en
);

alter table public.exhibition_catalog_v2
  alter column gallery_id set not null;

comment on column public.exhibition_catalog_v2.gallery_id is
  'Stable gallery organization identity. Venue display and location fields remain per-exhibition snapshots.';

-- Legacy rows cannot expose the new column, but integrity comparison still
-- derives the same stable identity so canonical/legacy parity remains valid.
create or replace function content_private.legacy_exhibition_catalog_v2_payload(
  p_row public.exhibitions
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
set timezone = 'UTC'
as $function$
  select to_jsonb(p_row) || jsonb_build_object(
    'is_editors_pick', coalesce(p_row.editor_id = 'gallr-editors', false),
    'guest_editor_id', case
      when p_row.editor_id is null or p_row.editor_id = 'gallr-editors'
        then null
      else p_row.editor_id
    end,
    'gallery_id', (
      select source.gallery_id
      from content.gallery_catalog_sources as source
      where source.source = 'public.exhibition_catalog_v2'
        and source.source_key =
          content_private.normalize_gallery_catalog_name(p_row.venue_name_ko)
    )
  );
$function$;

-- Keep the canonical reconciliation contract aligned with the expanded
-- public payload without changing the long-lived source function's return
-- type. The directory mapping is written transactionally by the catalogue
-- assignment trigger, so this remains a read-only, stable comparison.
create or replace function public.admin_reconcile_exhibition_catalog_v2()
returns jsonb
language sql
stable
security definer
set search_path = ''
set timezone = 'UTC'
as $function$
  with canonical_rows as (
    select
      canonical_source.id,
      canonical_source.payload || jsonb_build_object(
        'gallery_id', gallery_source.gallery_id
      ) as payload,
      content_private.sha256_canonical_jsonb(
        canonical_source.payload || jsonb_build_object(
          'gallery_id', gallery_source.gallery_id
        )
      ) as expected_content_checksum_sha256
    from content_private.exhibition_catalog_v2_source_payload(null)
      as canonical_source
    left join content.gallery_catalog_sources as gallery_source
      on gallery_source.source = 'public.exhibition_catalog_v2'
      and gallery_source.source_key =
        content_private.normalize_gallery_catalog_name(
          canonical_source.payload ->> 'venue_name_ko'
        )
  ),
  projection_rows as (
    select
      catalog.id,
      content_private.exhibition_catalog_v2_payload(catalog) as payload,
      catalog.content_checksum_sha256,
      content_private.exhibition_catalog_v2_checksum(catalog)
        as derived_content_checksum_sha256
    from public.exhibition_catalog_v2 as catalog
  ),
  comparison as (
    select
      coalesce(canonical.id, projection.id) as id,
      canonical.payload as canonical_payload,
      projection.payload as projection_payload,
      canonical.expected_content_checksum_sha256,
      projection.content_checksum_sha256,
      projection.derived_content_checksum_sha256,
      case
        when canonical.id is null then 'only_in_projection'
        when projection.id is null then 'only_in_canonical'
        when canonical.payload is distinct from projection.payload
          then 'field_mismatch'
        when projection.content_checksum_sha256
          is distinct from canonical.expected_content_checksum_sha256
          or projection.content_checksum_sha256
            is distinct from projection.derived_content_checksum_sha256
          then 'checksum_mismatch'
        else 'match'
      end as status
    from canonical_rows as canonical
    full join projection_rows as projection using (id)
  ),
  differences as (
    select
      comparison.id,
      comparison.status,
      comparison.expected_content_checksum_sha256,
      comparison.content_checksum_sha256,
      comparison.derived_content_checksum_sha256,
      case
        when comparison.status <> 'field_mismatch' then '[]'::jsonb
        else coalesce(
          (
            select jsonb_agg(field.key order by field.key)
            from (
              select coalesce(
                canonical_field.key,
                projection_field.key
              ) as key
              from jsonb_each(
                case
                  when jsonb_typeof(comparison.canonical_payload) = 'object'
                    then comparison.canonical_payload
                  else '{}'::jsonb
                end
              )
                as canonical_field(key, value)
              full join jsonb_each(
                case
                  when jsonb_typeof(comparison.projection_payload) = 'object'
                    then comparison.projection_payload
                  else '{}'::jsonb
                end
              )
                as projection_field(key, value) using (key)
              where canonical_field.value
                is distinct from projection_field.value
            ) as field
          ),
          '[]'::jsonb
        )
      end as differing_fields
    from comparison
    where comparison.status <> 'match'
  ),
  reported_differences as (
    select *
    from differences
    order by id
    limit 100
  )
  select jsonb_build_object(
    'schema_version', 1,
    'in_sync', not exists (select 1 from differences),
    'canonical_count', (select count(*) from canonical_rows),
    'projection_count', (select count(*) from projection_rows),
    'matching_count', (
      select count(*) from comparison where status = 'match'
    ),
    'difference_count', (select count(*) from differences),
    'missing_count', (
      select count(*) from differences where status = 'only_in_canonical'
    ),
    'unexpected_count', (
      select count(*) from differences where status = 'only_in_projection'
    ),
    'mismatched_count', (
      select count(*)
      from differences
      where status in ('field_mismatch', 'checksum_mismatch')
    ),
    'reported_difference_count', (
      select count(*) from reported_differences
    ),
    'truncated', (select count(*) from differences) > 100,
    'differences', coalesce(
      (
        select jsonb_agg(to_jsonb(reported_differences) order by id)
        from reported_differences
      ),
      '[]'::jsonb
    )
  );
$function$;

comment on function public.admin_reconcile_exhibition_catalog_v2() is
  'Service-role-only field, stable gallery identity, and checksum reconciliation between canonical published content and exhibition_catalog_v2.';

revoke all on function public.admin_reconcile_exhibition_catalog_v2()
  from public, anon, authenticated, service_role;
grant execute on function public.admin_reconcile_exhibition_catalog_v2()
  to service_role;
