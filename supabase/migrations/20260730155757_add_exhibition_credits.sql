-- Add bilingual exhibition credits as versioned editorial content.
-- Credits remain independent from media-asset credit metadata and render as
-- a continuation of the public exhibition description.

alter table content.exhibition_versions
  add column if not exists credits_ko text not null default '',
  add column if not exists credits_en text not null default '';

alter table public.exhibition_catalog_v2
  add column if not exists credits_ko text not null default '',
  add column if not exists credits_en text not null default '';

-- Keep the still-supported legacy rollback reader structurally compatible
-- while canonical remains the writer of record.
alter table public.exhibitions
  add column if not exists credits_ko text not null default '',
  add column if not exists credits_en text not null default '';

-- Preserve the existing validator and extend it without copying its security-
-- sensitive field validation logic.
do $rename_original_admin_patch_validator$
begin
  if to_regprocedure(
    'content_private.admin_validate_patch_without_credits(jsonb)'
  ) is null then
    alter function content_private.admin_validate_patch(jsonb)
      rename to admin_validate_patch_without_credits;
  end if;
end;
$rename_original_admin_patch_validator$;

create or replace function content_private.admin_validate_patch(p_patch jsonb)
returns void
language plpgsql
stable
set search_path = ''
as $function$
declare
  v_key text;
begin
  if p_patch is null or jsonb_typeof(p_patch) <> 'object' then
    raise exception using
      errcode = '22023',
      message = 'patch_must_be_an_object';
  end if;

  foreach v_key in array array['credits_ko', 'credits_en']
  loop
    if p_patch ? v_key then
      if jsonb_typeof(p_patch -> v_key) not in ('string', 'null') then
        raise exception using
          errcode = '22023',
          message = 'patch_field_has_invalid_type',
          detail = format('%s must be a string or null', v_key);
      end if;
      if jsonb_typeof(p_patch -> v_key) = 'string'
         and length(p_patch ->> v_key) > 50000 then
        raise exception using
          errcode = '22023',
          message = 'patch_field_is_too_long',
          detail = format('%s exceeds 50000 characters', v_key);
      end if;
    end if;
  end loop;

  perform content_private.admin_validate_patch_without_credits(
    p_patch - 'credits_ko' - 'credits_en'
  );
end;
$function$;

revoke all on function content_private.admin_validate_patch(jsonb)
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_validate_patch(jsonb)
  to authenticated;

-- A published version is cloned by the existing draft-save implementation.
-- Inherit credits before that insert completes, then apply any explicitly
-- supplied credit patch during the same optimistic-concurrency transaction.
create or replace function content_private.admin_apply_exhibition_credits()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_active boolean :=
    coalesce(current_setting('content.admin_credits_active', true), 'false') = 'true';
  v_has_ko boolean :=
    coalesce(current_setting('content.admin_credits_has_ko', true), 'false') = 'true';
  v_has_en boolean :=
    coalesce(current_setting('content.admin_credits_has_en', true), 'false') = 'true';
begin
  if tg_op = 'INSERT' and new.version_number > 1 then
    select source.credits_ko, source.credits_en
    into new.credits_ko, new.credits_en
    from content.exhibition_versions as source
    where source.exhibition_id = new.exhibition_id
      and source.version_number < new.version_number
    order by source.version_number desc
    limit 1;
  end if;

  if v_active and v_has_ko then
    new.credits_ko :=
      coalesce(current_setting('content.admin_credits_ko', true), '');
  end if;
  if v_active and v_has_en then
    new.credits_en :=
      coalesce(current_setting('content.admin_credits_en', true), '');
  end if;
  return new;
end;
$function$;

drop trigger if exists exhibition_versions_apply_admin_credits
  on content.exhibition_versions;
create trigger exhibition_versions_apply_admin_credits
  before insert or update on content.exhibition_versions
  for each row
  execute function content_private.admin_apply_exhibition_credits();

create or replace function public.admin_save_exhibition_draft(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_patch jsonb
)
returns jsonb
language plpgsql
volatile
security invoker
set search_path = ''
as $function$
declare
  v_result jsonb;
begin
  perform set_config('content.admin_credits_active', 'true', true);
  perform set_config(
    'content.admin_credits_has_ko',
    (coalesce(p_patch, '{}'::jsonb) ? 'credits_ko')::text,
    true
  );
  perform set_config(
    'content.admin_credits_has_en',
    (coalesce(p_patch, '{}'::jsonb) ? 'credits_en')::text,
    true
  );
  perform set_config(
    'content.admin_credits_ko',
    coalesce(p_patch ->> 'credits_ko', ''),
    true
  );
  perform set_config(
    'content.admin_credits_en',
    coalesce(p_patch ->> 'credits_en', ''),
    true
  );

  v_result := content_private.admin_save_exhibition_draft_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_patch
  );

  perform set_config('content.admin_credits_active', 'false', true);
  return v_result;
end;
$function$;

revoke all on function public.admin_save_exhibition_draft(
  text, uuid, integer, jsonb
) from public, anon, authenticated, service_role;
grant execute on function public.admin_save_exhibition_draft(
  text, uuid, integer, jsonb
) to authenticated;

create or replace function content_private.admin_exhibition_json(
  p_exhibition_id text,
  p_version_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select jsonb_build_object(
    'id', exhibition.id,
    'working_version_id', version.id,
    'version_number', version.version_number,
    'published_version_id', exhibition.published_version_id,
    'has_unpublished_changes',
      version.status = 'draft'::content.exhibition_version_status,
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'city_ko', version.city_ko,
    'city_en', version.city_en,
    'region_ko', version.region_ko,
    'region_en', version.region_en,
    'address_ko', version.address_ko,
    'address_en', version.address_en,
    'latitude', coalesce(version.latitude::text, ''),
    'longitude', coalesce(version.longitude::text, ''),
    'event_id', coalesce(version.event_id, ''),
    'editor_id', coalesce(version.editor_id, ''),
    'opening_date', coalesce(to_char(version.opening_date, 'YYYY-MM-DD'), ''),
    'closing_date', coalesce(to_char(version.closing_date, 'YYYY-MM-DD'), ''),
    'description_ko', version.description_ko,
    'description_en', version.description_en,
    'credits_ko', version.credits_ko,
    'credits_en', version.credits_en,
    'hours', coalesce(version.hours, ''),
    'contact', coalesce(version.contact, ''),
    'ticket_url', coalesce(version.ticket_url, ''),
    'reception_date', coalesce(
      to_char(version.reception_date at time zone 'Asia/Seoul', 'YYYY-MM-DD'),
      ''
    ),
    'reception_start_time', coalesce(version.opening_time, ''),
    'cover_image_url', coalesce(cover.public_url, version.legacy_cover_image_url),
    'cover_alt_ko', coalesce(cover.alt_ko, ''),
    'cover_alt_en', coalesce(cover.alt_en, ''),
    'image_credit', coalesce(cover.credit, ''),
    'is_featured', version.is_featured,
    'is_homepage_featured', version.is_homepage_featured,
    'status', case
      when exhibition.archived_at is not null then 'archived'
      when version.status = 'draft'::content.exhibition_version_status then 'draft'
      else 'published'
    end,
    'revision', version.revision,
    'updated_at', greatest(exhibition.updated_at, version.updated_at),
    'updated_by', coalesce(
      nullif(updater_profile.display_name, ''),
      nullif(updater.email, ''),
      'Unknown staff member'
    )
  )
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = p_version_id
  left join lateral (
    select
      asset.public_url,
      attachment.alt_ko,
      attachment.alt_en,
      attachment.credit
    from content.exhibition_version_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.version_id = version.id
      and attachment.role = 'cover'::content.media_role
    order by attachment.sort_order, attachment.created_at
    limit 1
  ) as cover on true
  left join auth.users as updater
    on updater.id = case
      when exhibition.updated_at > version.updated_at then exhibition.updated_by
      else version.updated_by
    end
  left join public.profiles as updater_profile on updater_profile.id = updater.id
  where exhibition.id = p_exhibition_id;
$function$;

revoke all on function content_private.admin_exhibition_json(text, uuid)
  from public, anon, authenticated, service_role;

-- Extend the established flattening payload without changing the return type
-- of exhibition_catalog_v2_source(text), which is already depended on by the
-- transactional projector. Reconciliation uses this payload helper so the
-- integrity contract covers credits as first-class canonical content.
create or replace function content_private.exhibition_catalog_v2_source_payload(
  p_exhibition_id text
)
returns table (
  id text,
  payload jsonb
)
language sql
stable
security definer
set search_path = ''
set timezone = 'UTC'
as $function$
  select
    source.id,
    to_jsonb(source) || jsonb_build_object(
      'credits_ko', version.credits_ko,
      'credits_en', version.credits_en
    ) as payload
  from content_private.exhibition_catalog_v2_source(p_exhibition_id) as source
  join content.exhibitions as exhibition on exhibition.id = source.id
  join content.exhibition_versions as version
    on version.id = exhibition.published_version_id
  order by source.id;
$function$;

revoke all on function
  content_private.exhibition_catalog_v2_source_payload(text)
from public, anon, authenticated, service_role;

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
      source.id,
      source.payload,
      content_private.sha256_canonical_jsonb(source.payload)
        as expected_content_checksum_sha256
    from content_private.exhibition_catalog_v2_source_payload(null) as source
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
              from jsonb_each(comparison.canonical_payload)
                as canonical_field(key, value)
              full join jsonb_each(comparison.projection_payload)
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

revoke all on function public.admin_reconcile_exhibition_catalog_v2()
  from public, anon, authenticated, service_role;
grant execute on function public.admin_reconcile_exhibition_catalog_v2()
  to service_role;

-- The existing projector remains responsible for all established fields. This
-- later alphabetic trigger copies credits after it runs, so media/curation
-- refreshes cannot erase them.
create or replace function content_private.sync_catalog_v2_credits_from_version()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  if tg_op <> 'DELETE' then
    if new.status = 'published'::content.exhibition_version_status
       and exists (
         select 1
         from content.exhibitions as exhibition
         where exhibition.id = new.exhibition_id
           and exhibition.published_version_id = new.id
           and exhibition.archived_at is null
       ) then
      update public.exhibition_catalog_v2 as catalog
      set
        credits_ko = new.credits_ko,
        credits_en = new.credits_en
      where catalog.id = new.exhibition_id
        and (
          catalog.credits_ko is distinct from new.credits_ko
          or catalog.credits_en is distinct from new.credits_en
        );
    end if;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$function$;

drop trigger if exists zz_exhibition_catalog_v2_version_credits
  on content.exhibition_versions;
create trigger zz_exhibition_catalog_v2_version_credits
  after insert or update or delete on content.exhibition_versions
  for each row
  execute function content_private.sync_catalog_v2_credits_from_version();

-- When the compatibility mirror is enabled, keep its two new columns aligned
-- with the canonical catalog without reopening any legacy write path.
create or replace function content_private.sync_legacy_exhibition_credits()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  if not coalesce(
    (
      select runtime.legacy_mirror_enabled
      from content_private.exhibition_catalog_runtime as runtime
      where runtime.singleton
    ),
    false
  ) then
    return new;
  end if;

  insert into content_private.exhibition_catalog_legacy_write_context (
    backend_pid
  ) values (
    pg_catalog.pg_backend_pid()
  ) on conflict (backend_pid) do nothing;

  update public.exhibitions as legacy
  set
    credits_ko = new.credits_ko,
    credits_en = new.credits_en
  where legacy.id = new.id;

  delete from content_private.exhibition_catalog_legacy_write_context
  where backend_pid = pg_catalog.pg_backend_pid();
  return new;
exception
  when others then
    delete from content_private.exhibition_catalog_legacy_write_context
    where backend_pid = pg_catalog.pg_backend_pid();
    raise;
end;
$function$;

drop trigger if exists exhibition_catalog_v2_mirror_credits
  on public.exhibition_catalog_v2;
create trigger exhibition_catalog_v2_mirror_credits
  after insert or update of credits_ko, credits_en
  on public.exhibition_catalog_v2
  for each row
  execute function content_private.sync_legacy_exhibition_credits();

-- Recalculate stored canonical checksums now that their payload contains two
-- additional fields. Both compatibility tables receive the same blank default.
update public.exhibition_catalog_v2
set credits_ko = credits_ko;
