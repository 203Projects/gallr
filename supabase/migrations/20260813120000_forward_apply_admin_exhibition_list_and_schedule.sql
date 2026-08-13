-- Forward-apply the Admin workflow contract that production skipped when the
-- later 20260812130428 migration shipped from a parallel release branch first.
-- Keep the original migration immutable and fail closed if the database has a
-- partially installed version of this contract.

do $assert_admin_workflow_forward_state$
declare
  v_has_reception_end_column boolean;
  v_has_current_validator boolean;
  v_has_base_validator boolean;
  v_has_reception_trigger_function boolean;
  v_has_reception_trigger boolean;
  v_has_private_extended_list boolean;
  v_has_public_extended_list boolean;
  v_fully_missing boolean;
  v_fully_applied boolean;
begin
  select exists (
    select 1
    from information_schema.columns
    where table_schema = 'content'
      and table_name = 'exhibition_versions'
      and column_name = 'reception_end_time'
  ) into v_has_reception_end_column;

  v_has_current_validator := to_regprocedure(
    'content_private.admin_validate_patch(jsonb)'
  ) is not null;
  v_has_base_validator := to_regprocedure(
    'content_private.admin_validate_patch_without_reception_end(jsonb)'
  ) is not null;
  v_has_reception_trigger_function := to_regprocedure(
    'content_private.admin_apply_reception_end_time()'
  ) is not null;
  v_has_private_extended_list := to_regprocedure(
    'content_private.admin_list_exhibitions_impl(text,text,text,boolean,text)'
  ) is not null;
  v_has_public_extended_list := to_regprocedure(
    'public.admin_list_exhibitions(text,text,text,boolean,text)'
  ) is not null;

  select exists (
    select 1
    from pg_catalog.pg_trigger as trigger
    join pg_catalog.pg_class as relation on relation.oid = trigger.tgrelid
    join pg_catalog.pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname = 'exhibition_versions'
      and trigger.tgname = 'exhibition_versions_apply_admin_reception_end'
      and not trigger.tgisinternal
  ) into v_has_reception_trigger;

  if not v_has_current_validator then
    raise exception using
      errcode = '55000',
      message = 'admin_workflow_forward_missing_base_contract';
  end if;

  v_fully_missing :=
    not v_has_reception_end_column
    and not v_has_base_validator
    and not v_has_reception_trigger_function
    and not v_has_reception_trigger
    and not v_has_private_extended_list
    and not v_has_public_extended_list;
  v_fully_applied :=
    v_has_reception_end_column
    and v_has_base_validator
    and v_has_reception_trigger_function
    and v_has_reception_trigger
    and v_has_private_extended_list
    and v_has_public_extended_list;

  if not v_fully_missing and not v_fully_applied then
    raise exception using
      errcode = '55000',
      message = 'admin_workflow_forward_partial_state';
  end if;
end;
$assert_admin_workflow_forward_state$;

-- BEGIN EXACT 20260811120000 MIGRATION BODY
-- Complete the August admin workflow requests without weakening the existing
-- revision, publication, or staff-authorization boundaries.

alter table content.exhibition_versions
  alter column is_homepage_featured set default true,
  add column if not exists reception_end_time text;

-- Treat a Korean address's road/parcel portion as the geocoded identity. Floor
-- and unit suffixes may change without moving the pin; a different searchable
-- address still invalidates both coordinates.
create or replace function content_private.searchable_korean_address(
  p_address text
)
returns text
language plpgsql
immutable
set search_path = ''
as $function$
declare
  v_address text := regexp_replace(btrim(coalesce(p_address, '')), '\s+', ' ', 'g');
  v_match text[];
begin
  v_match := regexp_match(
    v_address,
    '^(.+(로|길)[[:space:]]+[0-9]+(-[0-9]+)?)([[:space:]].*)?$'
  );
  if v_match is not null then
    return v_match[1];
  end if;

  v_match := regexp_match(
    v_address,
    '^(.+(동|가)[[:space:]]+[0-9]+(-[0-9]+)?)([[:space:]].*)?$'
  );
  return case when v_match is null then null else v_match[1] end;
end;
$function$;

revoke all on function content_private.searchable_korean_address(text)
  from public, anon, authenticated, service_role;

create or replace function content_private.invalidate_stale_map_coordinates()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $function$
declare
  v_old_searchable text;
  v_new_searchable text;
begin
  if old.address_ko is not distinct from new.address_ko then
    return new;
  end if;

  v_old_searchable := content_private.searchable_korean_address(old.address_ko);
  v_new_searchable := content_private.searchable_korean_address(new.address_ko);

  if v_old_searchable is not null
     and v_old_searchable is not distinct from v_new_searchable then
    return new;
  end if;

  if new.latitude is not distinct from old.latitude
     and new.longitude is not distinct from old.longitude then
    new.latitude := null;
    new.longitude := null;
  end if;
  return new;
end;
$function$;

revoke all on function content_private.invalidate_stale_map_coordinates()
  from public, anon, authenticated, service_role;

-- Extend the established validator while retaining all prior URL, coordinate,
-- association, credits, size, and type checks.
do $rename_admin_patch_validator$
begin
  if to_regprocedure(
    'content_private.admin_validate_patch_without_reception_end(jsonb)'
  ) is null then
    alter function content_private.admin_validate_patch(jsonb)
      rename to admin_validate_patch_without_reception_end;
  end if;
end;
$rename_admin_patch_validator$;

create or replace function content_private.admin_validate_patch(p_patch jsonb)
returns void
language plpgsql
stable
set search_path = ''
as $function$
declare
  v_value text;
begin
  if p_patch ? 'reception_end_time' then
    if jsonb_typeof(p_patch -> 'reception_end_time') not in ('string', 'null') then
      raise exception using
        errcode = '22023',
        message = 'patch_field_has_invalid_type',
        detail = 'reception_end_time must be a string or null';
    end if;
    v_value := coalesce(p_patch ->> 'reception_end_time', '');
    if v_value <> ''
       and v_value !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' then
      raise exception using
        errcode = '22023',
        message = 'patch_time_has_invalid_format',
        detail = 'reception_end_time must use HH:MM';
    end if;
  end if;

  perform content_private.admin_validate_patch_without_reception_end(
    p_patch - 'reception_end_time'
  );
end;
$function$;

revoke all on function content_private.admin_validate_patch(jsonb)
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_validate_patch(jsonb)
  to authenticated;

-- The existing save implementation has an explicit legacy column list. A
-- trigger keeps reception end time versioned across clone and patch paths,
-- following the established credits extension pattern.
create or replace function content_private.admin_apply_reception_end_time()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_active boolean :=
    coalesce(current_setting('content.admin_reception_end_active', true), 'false') = 'true';
  v_has_value boolean :=
    coalesce(current_setting('content.admin_reception_end_has_value', true), 'false') = 'true';
begin
  if tg_op = 'INSERT' and new.version_number > 1 then
    select source.reception_end_time
    into new.reception_end_time
    from content.exhibition_versions as source
    where source.exhibition_id = new.exhibition_id
      and source.version_number < new.version_number
    order by source.version_number desc
    limit 1;
  end if;

  if v_active and v_has_value then
    new.reception_end_time := nullif(
      coalesce(current_setting('content.admin_reception_end_value', true), ''),
      ''
    );
  end if;
  return new;
end;
$function$;

revoke all on function content_private.admin_apply_reception_end_time()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_apply_admin_reception_end
  on content.exhibition_versions;
create trigger exhibition_versions_apply_admin_reception_end
  before insert or update on content.exhibition_versions
  for each row
  execute function content_private.admin_apply_reception_end_time();

-- Preserve both the credits settings introduced in 20260730155757 and the new
-- reception-end setting around the original revision-checked implementation.
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
  perform set_config('content.admin_credits_ko', coalesce(p_patch ->> 'credits_ko', ''), true);
  perform set_config('content.admin_credits_en', coalesce(p_patch ->> 'credits_en', ''), true);
  perform set_config('content.admin_reception_end_active', 'true', true);
  perform set_config(
    'content.admin_reception_end_has_value',
    (coalesce(p_patch, '{}'::jsonb) ? 'reception_end_time')::text,
    true
  );
  perform set_config(
    'content.admin_reception_end_value',
    coalesce(p_patch ->> 'reception_end_time', ''),
    true
  );

  v_result := content_private.admin_save_exhibition_draft_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_patch
  );

  perform set_config('content.admin_credits_active', 'false', true);
  perform set_config('content.admin_reception_end_active', 'false', true);
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
    'has_unpublished_changes', version.status = 'draft'::content.exhibition_version_status,
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
    'reception_end_time', coalesce(version.reception_end_time, ''),
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
    'created_at', exhibition.created_at,
    'published_at', published.published_at,
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
  left join content.exhibition_versions as published
    on published.id = exhibition.published_version_id
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

-- Keep the two-argument list RPC for deployed clients and add an exact
-- five-argument overload for combined publish/date/placement filters and sort.
create or replace function content_private.admin_list_exhibitions_impl(
  p_search text,
  p_status text,
  p_temporal_status text,
  p_featured_only boolean,
  p_sort text
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_search text := lower(btrim(coalesce(p_search, '')));
  v_today date := (current_timestamp at time zone 'Asia/Seoul')::date;
begin
  perform content_private.admin_assert_staff('contributor'::content.staff_role);

  if p_status is not null and p_status not in ('draft', 'published', 'archived') then
    raise exception using errcode = '22023', message = 'invalid_exhibition_status_filter';
  end if;
  if p_temporal_status is not null
     and p_temporal_status not in ('running', 'upcoming', 'ended') then
    raise exception using errcode = '22023', message = 'invalid_exhibition_temporal_filter';
  end if;
  if p_sort not in (
    'updated_desc', 'published_desc', 'opening_asc', 'closing_asc', 'created_desc'
  ) then
    raise exception using errcode = '22023', message = 'invalid_exhibition_sort';
  end if;

  return query
  select content_private.admin_exhibition_json(exhibition.id, chosen.id)
  from content.exhibitions as exhibition
  join lateral (
    select
      version.id,
      version.status,
      version.name_ko,
      version.name_en,
      version.venue_name_ko,
      version.venue_name_en,
      version.opening_date,
      version.closing_date,
      version.is_homepage_featured,
      version.updated_at
    from content.exhibition_versions as version
    where version.exhibition_id = exhibition.id
      and (
        version.status = 'draft'::content.exhibition_version_status
        or version.id = exhibition.published_version_id
      )
    order by
      (version.status = 'draft'::content.exhibition_version_status) desc,
      version.version_number desc
    limit 1
  ) as chosen on true
  left join content.exhibition_versions as published
    on published.id = exhibition.published_version_id
  cross join lateral (
    select case
      when exhibition.archived_at is not null then 'archived'
      when chosen.status = 'draft'::content.exhibition_version_status then 'draft'
      else 'published'
    end as value
  ) as resolved_status
  where (
    v_search = ''
    or position(
      v_search in lower(concat_ws(
        ' ', exhibition.id, chosen.name_ko, chosen.name_en,
        chosen.venue_name_ko, chosen.venue_name_en
      ))
    ) > 0
  )
    and (p_status is null or resolved_status.value = p_status)
    and (not coalesce(p_featured_only, false) or chosen.is_homepage_featured)
    and (
      p_temporal_status is null
      or (p_temporal_status = 'running'
        and chosen.opening_date <= v_today
        and chosen.closing_date >= v_today)
      or (p_temporal_status = 'upcoming'
        and chosen.opening_date <= chosen.closing_date
        and chosen.opening_date > v_today)
      or (p_temporal_status = 'ended'
        and (
          chosen.opening_date > chosen.closing_date
          or chosen.closing_date < v_today
        ))
    )
  order by
    case when p_sort = 'opening_asc' then chosen.opening_date end asc nulls last,
    case when p_sort = 'closing_asc' then chosen.closing_date end asc nulls last,
    case when p_sort = 'published_desc' then published.published_at end desc nulls last,
    case when p_sort = 'created_desc' then exhibition.created_at end desc nulls last,
    case when p_sort = 'updated_desc'
      then greatest(exhibition.updated_at, chosen.updated_at) end desc nulls last,
    exhibition.id;
end;
$function$;

revoke all on function content_private.admin_list_exhibitions_impl(
  text, text, text, boolean, text
) from public, anon, authenticated, service_role;
grant execute on function content_private.admin_list_exhibitions_impl(
  text, text, text, boolean, text
) to authenticated;

create or replace function public.admin_list_exhibitions(
  p_search text,
  p_status text,
  p_temporal_status text,
  p_featured_only boolean,
  p_sort text
)
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $function$
  select *
  from content_private.admin_list_exhibitions_impl(
    p_search,
    p_status,
    p_temporal_status,
    p_featured_only,
    p_sort
  );
$function$;

revoke all on function public.admin_list_exhibitions(
  text, text, text, boolean, text
) from public, anon, authenticated, service_role;
grant execute on function public.admin_list_exhibitions(
  text, text, text, boolean, text
) to authenticated;
