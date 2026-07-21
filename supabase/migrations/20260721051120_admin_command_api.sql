-- gallr admin command/query API (additive, local-first Phase 2)
--
-- The canonical content schema remains private. Only the SECURITY INVOKER
-- functions in public are intended for PostgREST/supabase-js. Privileged work
-- is delegated to narrowly granted SECURITY DEFINER implementations in
-- content_private; every implementation resolves auth.uid() and verifies an
-- active staff role before touching canonical data.
--
-- This migration deliberately does not read, write, backfill, rename, or swap
-- public.exhibitions.

-- Drafts carry curation intent until publish makes it live. Cover/media fields
-- are read-only in this API; a later command API will own version-scoped media
-- registration, metadata, ordering, and publication.
alter table content.exhibition_versions
  add column if not exists is_featured boolean not null default false,
  add column if not exists is_homepage_featured boolean not null default false;

create unique index if not exists exhibition_versions_one_draft_idx
  on content.exhibition_versions (exhibition_id)
  where status = 'draft'::content.exhibition_version_status;

create unique index if not exists exhibition_versions_one_published_idx
  on content.exhibition_versions (exhibition_id)
  where status = 'published'::content.exhibition_version_status;

-- Phase 1 allowed narrowly RLS-guarded direct draft inserts while the command
-- layer did not exist. From this migration onward, browser staff mutate the
-- canonical model only through RPCs. SELECT grants remain for defense-in-depth
-- diagnostics and existing foundation tests; the private schema is still not
-- an exposed Data API schema.
revoke insert, update, delete on content.venues from authenticated;
revoke insert, update, delete on content.exhibitions from authenticated;
revoke insert, update, delete on content.exhibition_versions from authenticated;
revoke insert, update, delete on content.media_assets from authenticated;
revoke insert, update, delete on content.exhibition_version_media from authenticated;
revoke update (role, sort_order, focal_x, focal_y)
  on content.exhibition_version_media from authenticated;
revoke insert, update, delete on content.curation_placements from authenticated;
revoke insert, update, delete on content.audit_log from authenticated;
revoke insert, update, delete on content.outbox_events from authenticated;

-- The foundation's composite FK proves that a publication pointer belongs to
-- the same exhibition. These deferred constraint triggers additionally prove
-- that the pointer's target is still the sole published version at commit.
create or replace function content_private.enforce_published_pointer_status()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_exhibition_id text;
begin
  if tg_table_name = 'exhibitions' then
    v_exhibition_id := case when tg_op = 'DELETE' then old.id else new.id end;
  else
    v_exhibition_id := case
      when tg_op = 'DELETE' then old.exhibition_id
      else new.exhibition_id
    end;
  end if;

  if exists (
    select 1
    from content.exhibitions as exhibition
    left join content.exhibition_versions as version
      on version.exhibition_id = exhibition.id
     and version.id = exhibition.published_version_id
    where exhibition.id = v_exhibition_id
      and exhibition.published_version_id is not null
      and (
        version.id is null
        or version.status <> 'published'::content.exhibition_version_status
      )
  ) then
    raise exception using
      errcode = '23514',
      message = 'published_version_pointer_must_target_published_version';
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

revoke all on function content_private.enforce_published_pointer_status()
  from public, anon, authenticated, service_role;

create constraint trigger exhibitions_enforce_published_pointer_status
  after insert or update on content.exhibitions
  deferrable initially deferred
  for each row
  execute function content_private.enforce_published_pointer_status();

create constraint trigger versions_enforce_published_pointer_status
  after insert or update or delete on content.exhibition_versions
  deferrable initially deferred
  for each row
  execute function content_private.enforce_published_pointer_status();

-- Resolve and authorize the caller from the server-managed JWT subject. This
-- function intentionally ignores user_metadata and public.profiles.is_admin.
create or replace function content_private.admin_assert_staff(
  p_required_role content.staff_role
)
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := (select auth.uid());
  v_role content.staff_role;
  v_allowed boolean := false;
begin
  if v_user_id is null then
    raise exception using
      errcode = '42501',
      message = 'authentication_required';
  end if;

  select staff.role
  into v_role
  from content.staff_members as staff
  where staff.user_id = v_user_id
    and staff.active;

  if not found then
    raise exception using
      errcode = '42501',
      message = 'active_staff_membership_required';
  end if;

  v_allowed := case p_required_role
    when 'contributor'::content.staff_role then
      v_role in (
        'contributor'::content.staff_role,
        'publisher'::content.staff_role,
        'admin'::content.staff_role
      )
    when 'publisher'::content.staff_role then
      v_role in (
        'publisher'::content.staff_role,
        'admin'::content.staff_role
      )
    when 'admin'::content.staff_role then
      v_role = 'admin'::content.staff_role
    else false
  end;

  if not v_allowed then
    raise exception using
      errcode = '42501',
      message = 'insufficient_staff_role',
      detail = format('Required role: %s', p_required_role::text);
  end if;

  return v_user_id;
end;
$$;

revoke all on function content_private.admin_assert_staff(content.staff_role)
  from public, anon, authenticated, service_role;

-- Produce one snake_case DTO matching admin/src/domain.ts. The helper is not
-- executable by API roles and is only called after an implementation has
-- authorized the caller.
create or replace function content_private.admin_exhibition_json(
  p_exhibition_id text,
  p_version_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
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
    'opening_date', coalesce(to_char(version.opening_date, 'YYYY-MM-DD'), ''),
    'closing_date', coalesce(to_char(version.closing_date, 'YYYY-MM-DD'), ''),
    'description_ko', version.description_ko,
    'description_en', version.description_en,
    'hours', coalesce(version.hours, ''),
    'contact', coalesce(version.contact, ''),
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
    select asset.public_url, asset.alt_ko, asset.alt_en, asset.credit
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
$$;

revoke all on function content_private.admin_exhibition_json(text, uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_validate_patch(p_patch jsonb)
returns void
language plpgsql
stable
set search_path = ''
as $$
declare
  v_key text;
  v_text_value text;
  v_max_length integer;
  v_allowed_keys constant text[] := array[
    'name_ko',
    'name_en',
    'venue_name_ko',
    'venue_name_en',
    'city_ko',
    'city_en',
    'region_ko',
    'region_en',
    'address_ko',
    'address_en',
    'opening_date',
    'closing_date',
    'description_ko',
    'description_en',
    'hours',
    'contact',
    'reception_date',
    'reception_start_time',
    'is_featured',
    'is_homepage_featured'
  ];
begin
  if p_patch is null or jsonb_typeof(p_patch) <> 'object' then
    raise exception using
      errcode = '22023',
      message = 'patch_must_be_a_json_object';
  end if;

  if pg_column_size(p_patch) > 131072 then
    raise exception using
      errcode = '22023',
      message = 'patch_is_too_large';
  end if;

  for v_key in select jsonb_object_keys(p_patch)
  loop
    if not (v_key = any(v_allowed_keys)) then
      raise exception using
        errcode = '22023',
        message = 'patch_contains_forbidden_field',
        detail = v_key;
    end if;

    if v_key in ('is_featured', 'is_homepage_featured') then
      if jsonb_typeof(p_patch -> v_key) <> 'boolean' then
        raise exception using
          errcode = '22023',
          message = 'patch_field_has_invalid_type',
          detail = format('%s must be boolean', v_key);
      end if;
    elsif jsonb_typeof(p_patch -> v_key) not in ('string', 'null') then
      raise exception using
        errcode = '22023',
        message = 'patch_field_has_invalid_type',
        detail = format('%s must be a string or null', v_key);
    end if;

    if jsonb_typeof(p_patch -> v_key) = 'string' then
      v_text_value := p_patch ->> v_key;
      v_max_length := case
        when v_key in ('description_ko', 'description_en') then 50000
        when v_key in ('address_ko', 'address_en') then 2000
        when v_key in ('name_ko', 'name_en', 'venue_name_ko', 'venue_name_en') then 500
        else 2000
      end;

      if length(v_text_value) > v_max_length then
        raise exception using
          errcode = '22023',
          message = 'patch_field_is_too_long',
          detail = format('%s exceeds %s characters', v_key, v_max_length);
      end if;

      if v_key in ('opening_date', 'closing_date', 'reception_date')
         and v_text_value <> '' then
        if v_text_value !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then
          raise exception using
            errcode = '22023',
            message = 'patch_date_has_invalid_format',
            detail = format('%s must use YYYY-MM-DD', v_key);
        end if;

        begin
          perform v_text_value::date;
        exception
          when datetime_field_overflow or invalid_datetime_format then
            raise exception using
              errcode = '22023',
              message = 'patch_date_is_invalid',
              detail = v_key;
        end;
      end if;

      if v_key = 'reception_start_time'
         and v_text_value <> ''
         and v_text_value !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' then
        raise exception using
          errcode = '22023',
          message = 'patch_time_has_invalid_format',
          detail = 'reception_start_time must use HH:MM';
      end if;
    end if;
  end loop;
end;
$$;

revoke all on function content_private.admin_validate_patch(jsonb)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_current_staff_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_result jsonb;
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  select jsonb_build_object(
    'user_id', staff.user_id,
    'role', staff.role::text,
    'active', staff.active,
    'display_name', coalesce(profile.display_name, ''),
    'avatar_url', profile.avatar_url
  )
  into v_result
  from content.staff_members as staff
  left join public.profiles as profile on profile.id = staff.user_id
  where staff.user_id = v_user_id
    and staff.active;

  return v_result;
end;
$$;

create or replace function content_private.admin_list_exhibitions_impl(
  p_search text default '',
  p_status text default null
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_search text := lower(btrim(coalesce(p_search, '')));
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  if p_status is not null
     and p_status not in ('draft', 'published', 'archived') then
    raise exception using
      errcode = '22023',
      message = 'invalid_exhibition_status_filter';
  end if;

  return query
  select content_private.admin_exhibition_json(exhibition.id, chosen.id)
  from content.exhibitions as exhibition
  join lateral (
    select version.id, version.status, version.name_ko, version.name_en,
      version.venue_name_ko, version.venue_name_en, version.updated_at
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
        ' ',
        exhibition.id,
        chosen.name_ko,
        chosen.name_en,
        chosen.venue_name_ko,
        chosen.venue_name_en
      ))
    ) > 0
  )
    and (p_status is null or resolved_status.value = p_status)
  order by greatest(exhibition.updated_at, chosen.updated_at) desc,
    exhibition.id;
end;
$$;

create or replace function content_private.admin_get_exhibition_impl(
  p_exhibition_id text
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_version_id uuid;
  v_result jsonb;
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  select version.id
  into v_version_id
  from content.exhibitions as exhibition
  join lateral (
    select candidate.id, candidate.status, candidate.version_number
    from content.exhibition_versions as candidate
    where candidate.exhibition_id = exhibition.id
      and (
        candidate.status = 'draft'::content.exhibition_version_status
        or candidate.id = exhibition.published_version_id
      )
    order by
      (candidate.status = 'draft'::content.exhibition_version_status) desc,
      candidate.version_number desc
    limit 1
  ) as version on true
  where exhibition.id = p_exhibition_id;

  if v_version_id is null then
    raise exception using
      errcode = 'P0002',
      message = 'exhibition_not_found';
  end if;

  v_result := content_private.admin_exhibition_json(
    p_exhibition_id,
    v_version_id
  );
  return v_result;
end;
$$;

create or replace function content_private.admin_create_exhibition_draft_impl()
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_exhibition_id text := gen_random_uuid()::text;
  v_version_id uuid;
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  insert into content.exhibitions (
    id,
    created_by,
    updated_by
  )
  values (
    v_exhibition_id,
    v_user_id,
    v_user_id
  );

  insert into content.exhibition_versions (
    exhibition_id,
    version_number,
    revision,
    status,
    created_by,
    updated_by
  )
  values (
    v_exhibition_id,
    1,
    1,
    'draft'::content.exhibition_version_status,
    v_user_id,
    v_user_id
  )
  returning id into v_version_id;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.draft_created',
    'exhibition',
    v_exhibition_id,
    jsonb_build_object(
      'version_id', v_version_id,
      'version_number', 1,
      'revision', 1
    )
  );

  return content_private.admin_exhibition_json(
    v_exhibition_id,
    v_version_id
  );
end;
$$;

create or replace function content_private.admin_save_exhibition_draft_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_patch jsonb
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_exhibition content.exhibitions%rowtype;
  v_source content.exhibition_versions%rowtype;
  v_draft content.exhibition_versions%rowtype;
  v_existing_draft content.exhibition_versions%rowtype;
  v_version_id uuid;
  v_next_version_number integer;
  v_current_revision integer;
  v_was_cloned boolean := false;
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  perform content_private.admin_validate_patch(p_patch);

  if p_expected_revision is null or p_expected_revision < 1 then
    raise exception using
      errcode = '22023',
      message = 'expected_revision_must_be_positive';
  end if;

  if p_expected_version_id is null then
    raise exception using
      errcode = '22023',
      message = 'expected_version_id_is_required';
  end if;

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found then
    raise exception using
      errcode = 'P0002',
      message = 'exhibition_not_found';
  end if;

  if v_exhibition.archived_at is not null then
    raise exception using
      errcode = '22023',
      message = 'restore_exhibition_before_editing';
  end if;

  select version.*
  into v_source
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.id = p_expected_version_id
  for update;

  if not found then
    raise exception using
      errcode = 'P0002',
      message = 'working_version_not_found';
  end if;

  if v_source.revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_source.revision::text;
  end if;

  if v_source.status = 'draft'::content.exhibition_version_status then
    v_draft := v_source;
  elsif v_source.status = 'published'::content.exhibition_version_status
        and v_source.id = v_exhibition.published_version_id then
    select version.*
    into v_existing_draft
    from content.exhibition_versions as version
    where version.exhibition_id = p_exhibition_id
      and version.status = 'draft'::content.exhibition_version_status
    for update;

    if found then
      raise exception using
        errcode = '40001',
        message = 'revision_conflict',
        detail = v_existing_draft.revision::text;
    end if;

    select coalesce(max(version.version_number), 0) + 1
    into v_next_version_number
    from content.exhibition_versions as version
    where version.exhibition_id = p_exhibition_id;

    insert into content.exhibition_versions (
      exhibition_id,
      version_number,
      revision,
      status,
      venue_id,
      event_id,
      editor_id,
      name_ko,
      name_en,
      venue_name_ko,
      venue_name_en,
      city_ko,
      city_en,
      region_ko,
      region_en,
      address_ko,
      address_en,
      opening_date,
      closing_date,
      latitude,
      longitude,
      description_ko,
      description_en,
      hours,
      contact,
      reception_date,
      opening_time,
      ticket_url,
      legacy_cover_image_url,
      is_featured,
      is_homepage_featured,
      created_by,
      updated_by
    )
    values (
      p_exhibition_id,
      v_next_version_number,
      v_source.revision,
      'draft'::content.exhibition_version_status,
      v_source.venue_id,
      v_source.event_id,
      v_source.editor_id,
      v_source.name_ko,
      v_source.name_en,
      v_source.venue_name_ko,
      v_source.venue_name_en,
      v_source.city_ko,
      v_source.city_en,
      v_source.region_ko,
      v_source.region_en,
      v_source.address_ko,
      v_source.address_en,
      v_source.opening_date,
      v_source.closing_date,
      v_source.latitude,
      v_source.longitude,
      v_source.description_ko,
      v_source.description_en,
      v_source.hours,
      v_source.contact,
      v_source.reception_date,
      v_source.opening_time,
      v_source.ticket_url,
      v_source.legacy_cover_image_url,
      v_source.is_featured,
      v_source.is_homepage_featured,
      v_user_id,
      v_user_id
    )
    returning * into v_draft;

    insert into content.exhibition_version_media (
      version_id,
      media_id,
      role,
      sort_order,
      focal_x,
      focal_y,
      created_by
    )
    select
      v_draft.id,
      source_attachment.media_id,
      source_attachment.role,
      source_attachment.sort_order,
      source_attachment.focal_x,
      source_attachment.focal_y,
      v_user_id
    from content.exhibition_version_media as source_attachment
    where source_attachment.version_id = v_source.id;

    v_was_cloned := true;
  else
    raise exception using
      errcode = '22023',
      message = 'working_version_is_not_editable';
  end if;

  update content.exhibition_versions as version
  set
    name_ko = case when p_patch ? 'name_ko'
      then coalesce(p_patch ->> 'name_ko', '') else version.name_ko end,
    name_en = case when p_patch ? 'name_en'
      then coalesce(p_patch ->> 'name_en', '') else version.name_en end,
    venue_name_ko = case when p_patch ? 'venue_name_ko'
      then coalesce(p_patch ->> 'venue_name_ko', '') else version.venue_name_ko end,
    venue_name_en = case when p_patch ? 'venue_name_en'
      then coalesce(p_patch ->> 'venue_name_en', '') else version.venue_name_en end,
    city_ko = case when p_patch ? 'city_ko'
      then coalesce(p_patch ->> 'city_ko', '') else version.city_ko end,
    city_en = case when p_patch ? 'city_en'
      then coalesce(p_patch ->> 'city_en', '') else version.city_en end,
    region_ko = case when p_patch ? 'region_ko'
      then coalesce(p_patch ->> 'region_ko', '') else version.region_ko end,
    region_en = case when p_patch ? 'region_en'
      then coalesce(p_patch ->> 'region_en', '') else version.region_en end,
    address_ko = case when p_patch ? 'address_ko'
      then coalesce(p_patch ->> 'address_ko', '') else version.address_ko end,
    address_en = case when p_patch ? 'address_en'
      then coalesce(p_patch ->> 'address_en', '') else version.address_en end,
    opening_date = case when p_patch ? 'opening_date' then
      nullif(p_patch ->> 'opening_date', '')::date else version.opening_date end,
    closing_date = case when p_patch ? 'closing_date' then
      nullif(p_patch ->> 'closing_date', '')::date else version.closing_date end,
    description_ko = case when p_patch ? 'description_ko'
      then coalesce(p_patch ->> 'description_ko', '') else version.description_ko end,
    description_en = case when p_patch ? 'description_en'
      then coalesce(p_patch ->> 'description_en', '') else version.description_en end,
    hours = case when p_patch ? 'hours'
      then p_patch ->> 'hours' else version.hours end,
    contact = case when p_patch ? 'contact'
      then p_patch ->> 'contact' else version.contact end,
    reception_date = case when p_patch ? 'reception_date' then
      (
        nullif(p_patch ->> 'reception_date', '')::date::timestamp
        at time zone 'Asia/Seoul'
      )
      else version.reception_date end,
    opening_time = case when p_patch ? 'reception_start_time'
      then p_patch ->> 'reception_start_time' else version.opening_time end,
    is_featured = case when p_patch ? 'is_featured'
      then (p_patch ->> 'is_featured')::boolean else version.is_featured end,
    is_homepage_featured = case when p_patch ? 'is_homepage_featured'
      then (p_patch ->> 'is_homepage_featured')::boolean
      else version.is_homepage_featured end,
    revision = version.revision + 1,
    updated_by = v_user_id
  where version.id = v_draft.id
    and version.exhibition_id = p_exhibition_id
    and version.status = 'draft'::content.exhibition_version_status
    and version.revision = p_expected_revision
  returning version.id, version.revision
  into v_version_id, v_current_revision;

  if not found then
    select version.revision
    into v_current_revision
    from content.exhibition_versions as version
    where version.id = v_draft.id;

    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_current_revision::text;
  end if;

  update content.exhibitions
  set updated_by = v_user_id
  where id = p_exhibition_id;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.draft_saved',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'version_id', v_version_id,
      'revision', v_current_revision,
      'cloned_from_published', v_was_cloned,
      'changed_fields', coalesce(
        (
          select jsonb_agg(fields.key order by fields.key)
          from jsonb_object_keys(p_patch) as fields(key)
        ),
        '[]'::jsonb
      )
    )
  );

  return content_private.admin_exhibition_json(
    p_exhibition_id,
    v_version_id
  );
end;
$$;

create or replace function content_private.admin_publish_exhibition_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_exhibition content.exhibitions%rowtype;
  v_draft content.exhibition_versions%rowtype;
  v_published_version_id uuid;
  v_published_revision integer;
  v_current_revision integer;
begin
  v_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );

  if p_expected_revision is null or p_expected_revision < 1 then
    raise exception using
      errcode = '22023',
      message = 'expected_revision_must_be_positive';
  end if;

  if p_expected_version_id is null then
    raise exception using
      errcode = '22023',
      message = 'expected_version_id_is_required';
  end if;

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found then
    raise exception using
      errcode = 'P0002',
      message = 'exhibition_not_found';
  end if;

  if v_exhibition.archived_at is not null then
    raise exception using
      errcode = '22023',
      message = 'restore_exhibition_before_publishing';
  end if;

  select version.*
  into v_draft
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.id = p_expected_version_id
    and version.status = 'draft'::content.exhibition_version_status
  for update;

  if not found then
    raise exception using
      errcode = 'P0002',
      message = 'draft_not_found';
  end if;

  if v_draft.revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_draft.revision::text;
  end if;

  if length(btrim(v_draft.name_ko)) = 0 then
    raise exception using errcode = '23514', message = 'name_ko_is_required';
  end if;
  if length(btrim(v_draft.venue_name_ko)) = 0
     or length(btrim(v_draft.city_ko)) = 0
     or length(btrim(v_draft.region_ko)) = 0 then
    raise exception using errcode = '23514', message = 'venue_identity_is_required';
  end if;
  if v_draft.opening_date is null or v_draft.closing_date is null then
    raise exception using errcode = '23514', message = 'exhibition_dates_are_required';
  end if;
  if v_draft.closing_date < v_draft.opening_date then
    raise exception using errcode = '23514', message = 'exhibition_dates_are_invalid';
  end if;

  update content.exhibition_versions as version
  set status = 'superseded'::content.exhibition_version_status
  where version.exhibition_id = p_exhibition_id
    and version.status = 'published'::content.exhibition_version_status;

  update content.exhibition_versions as version
  set
    status = 'published'::content.exhibition_version_status,
    revision = version.revision + 1,
    published_at = now(),
    published_by = v_user_id,
    updated_by = v_user_id
  where version.id = v_draft.id
    and version.exhibition_id = p_exhibition_id
    and version.status = 'draft'::content.exhibition_version_status
    and version.revision = p_expected_revision
  returning version.id, version.revision
  into v_published_version_id, v_published_revision;

  if not found then
    select version.revision
    into v_current_revision
    from content.exhibition_versions as version
    where version.id = v_draft.id;

    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_current_revision::text;
  end if;

  update content.exhibitions
  set
    published_version_id = v_published_version_id,
    updated_by = v_user_id
  where id = p_exhibition_id;

  insert into content.curation_placements (
    surface,
    exhibition_id,
    position,
    enabled,
    created_by,
    updated_by
  )
  values
    (
      'app_featured'::content.curation_surface,
      p_exhibition_id,
      0,
      v_draft.is_featured,
      v_user_id,
      v_user_id
    ),
    (
      'homepage'::content.curation_surface,
      p_exhibition_id,
      0,
      v_draft.is_homepage_featured,
      v_user_id,
      v_user_id
    )
  on conflict (surface, exhibition_id) do update
  set
    enabled = excluded.enabled,
    updated_by = excluded.updated_by;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.published',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'version_id', v_published_version_id,
      'version_number', v_draft.version_number,
      'revision', v_published_revision,
      'superseded_version_id', v_exhibition.published_version_id
    )
  );

  insert into content.outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    deduplication_key
  )
  values (
    'exhibition',
    p_exhibition_id,
    'exhibition.published',
    jsonb_build_object(
      'exhibition_id', p_exhibition_id,
      'version_id', v_published_version_id,
      'revision', v_published_revision
    ),
    format(
      'exhibition:%s:published:%s',
      p_exhibition_id,
      v_published_version_id
    )
  )
  on conflict (deduplication_key) do nothing;

  return content_private.admin_exhibition_json(
    p_exhibition_id,
    v_published_version_id
  );
end;
$$;

create or replace function content_private.admin_archive_exhibition_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_exhibition content.exhibitions%rowtype;
  v_version_id uuid;
  v_current_revision integer;
begin
  v_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'exhibition_not_found';
  end if;
  if v_exhibition.archived_at is not null then
    raise exception using errcode = '22023', message = 'exhibition_already_archived';
  end if;

  select version.id, version.revision
  into v_version_id, v_current_revision
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.id = p_expected_version_id
    and (
      version.status = 'draft'::content.exhibition_version_status
      or (
        version.id = v_exhibition.published_version_id
        and not exists (
          select 1
          from content.exhibition_versions as draft
          where draft.exhibition_id = p_exhibition_id
            and draft.status = 'draft'::content.exhibition_version_status
        )
      )
    )
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'working_version_not_found';
  end if;
  if p_expected_revision is null or v_current_revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_current_revision::text;
  end if;

  update content.exhibitions
  set
    archived_at = now(),
    archived_by = v_user_id,
    updated_by = v_user_id
  where id = p_exhibition_id;

  update content.curation_placements
  set
    enabled = false,
    updated_by = v_user_id
  where exhibition_id = p_exhibition_id;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.archived',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object('published_version_id', v_exhibition.published_version_id)
  );

  insert into content.outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    deduplication_key
  )
  values (
    'exhibition',
    p_exhibition_id,
    'exhibition.archived',
    jsonb_build_object(
      'exhibition_id', p_exhibition_id,
      'working_version_id', v_version_id,
      'revision', v_current_revision
    ),
    format(
      'exhibition:%s:archived:%s',
      p_exhibition_id,
      (
        select count(*)
        from content.audit_log as audit
        where audit.entity_type = 'exhibition'
          and audit.entity_id = p_exhibition_id
          and audit.action = 'exhibition.archived'
      )
    )
  )
  on conflict (deduplication_key) do nothing;

  select version.id
  into v_version_id
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and (
      version.status = 'draft'::content.exhibition_version_status
      or (
        version.id = v_exhibition.published_version_id
        and not exists (
          select 1
          from content.exhibition_versions as draft
          where draft.exhibition_id = p_exhibition_id
            and draft.status = 'draft'::content.exhibition_version_status
        )
      )
    )
  order by
    (version.status = 'draft'::content.exhibition_version_status) desc,
    version.version_number desc
  limit 1;

  return content_private.admin_exhibition_json(p_exhibition_id, v_version_id);
end;
$$;

create or replace function content_private.admin_restore_exhibition_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_exhibition content.exhibitions%rowtype;
  v_version_id uuid;
  v_current_revision integer;
begin
  v_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'exhibition_not_found';
  end if;
  if v_exhibition.archived_at is null then
    raise exception using errcode = '22023', message = 'exhibition_is_not_archived';
  end if;

  select version.id, version.revision
  into v_version_id, v_current_revision
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.id = p_expected_version_id
    and (
      version.status = 'draft'::content.exhibition_version_status
      or (
        version.id = v_exhibition.published_version_id
        and not exists (
          select 1
          from content.exhibition_versions as draft
          where draft.exhibition_id = p_exhibition_id
            and draft.status = 'draft'::content.exhibition_version_status
        )
      )
    )
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'working_version_not_found';
  end if;
  if p_expected_revision is null or v_current_revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_current_revision::text;
  end if;

  update content.exhibitions
  set
    archived_at = null,
    archived_by = null,
    updated_by = v_user_id
  where id = p_exhibition_id;

  -- Restore only makes the identity editable/visible again. Curated placement
  -- remains disabled until a publisher explicitly republishes current intent.
  update content.curation_placements
  set
    enabled = false,
    updated_by = v_user_id
  where exhibition_id = p_exhibition_id;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.restored',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object('published_version_id', v_exhibition.published_version_id)
  );

  insert into content.outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    deduplication_key
  )
  values (
    'exhibition',
    p_exhibition_id,
    'exhibition.restored',
    jsonb_build_object(
      'exhibition_id', p_exhibition_id,
      'working_version_id', v_version_id,
      'revision', v_current_revision
    ),
    format(
      'exhibition:%s:restored:%s',
      p_exhibition_id,
      (
        select count(*)
        from content.audit_log as audit
        where audit.entity_type = 'exhibition'
          and audit.entity_id = p_exhibition_id
          and audit.action = 'exhibition.restored'
      )
    )
  )
  on conflict (deduplication_key) do nothing;

  select version.id
  into v_version_id
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and (
      version.status = 'draft'::content.exhibition_version_status
      or version.id = v_exhibition.published_version_id
    )
  order by
    (version.status = 'draft'::content.exhibition_version_status) desc,
    version.version_number desc
  limit 1;

  return content_private.admin_exhibition_json(p_exhibition_id, v_version_id);
end;
$$;

-- Internal implementations remain outside the Data API. Authenticated callers
-- need EXECUTE only so the invoker wrappers can enter them; each implementation
-- independently checks auth.uid() and active staff membership before work.
revoke all on function content_private.admin_current_staff_impl()
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_list_exhibitions_impl(text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_get_exhibition_impl(text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_create_exhibition_draft_impl()
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_save_exhibition_draft_impl(text, uuid, integer, jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_publish_exhibition_impl(text, uuid, integer)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_archive_exhibition_impl(text, uuid, integer)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_restore_exhibition_impl(text, uuid, integer)
  from public, anon, authenticated, service_role;

grant execute on function content_private.admin_current_staff_impl()
  to authenticated;
grant execute on function content_private.admin_list_exhibitions_impl(text, text)
  to authenticated;
grant execute on function content_private.admin_get_exhibition_impl(text)
  to authenticated;
grant execute on function content_private.admin_create_exhibition_draft_impl()
  to authenticated;
grant execute on function content_private.admin_save_exhibition_draft_impl(text, uuid, integer, jsonb)
  to authenticated;
grant execute on function content_private.admin_publish_exhibition_impl(text, uuid, integer)
  to authenticated;
grant execute on function content_private.admin_archive_exhibition_impl(text, uuid, integer)
  to authenticated;
grant execute on function content_private.admin_restore_exhibition_impl(text, uuid, integer)
  to authenticated;

-- Public RPC wrappers: intentionally SECURITY INVOKER. They contain no
-- authorization logic and cannot bypass table RLS or grants themselves.
create or replace function public.admin_current_staff()
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select content_private.admin_current_staff_impl();
$$;

create or replace function public.admin_list_exhibitions(
  p_search text default '',
  p_status text default null
)
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select *
  from content_private.admin_list_exhibitions_impl(p_search, p_status);
$$;

create or replace function public.admin_get_exhibition(
  p_exhibition_id text
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select content_private.admin_get_exhibition_impl(p_exhibition_id);
$$;

create or replace function public.admin_create_exhibition_draft()
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_create_exhibition_draft_impl();
$$;

create or replace function public.admin_save_exhibition_draft(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_patch jsonb
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_save_exhibition_draft_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_patch
  );
$$;

create or replace function public.admin_publish_exhibition(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_publish_exhibition_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );
$$;

create or replace function public.admin_archive_exhibition(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_archive_exhibition_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );
$$;

create or replace function public.admin_restore_exhibition(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_restore_exhibition_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );
$$;

revoke all on function public.admin_current_staff()
  from public, anon, authenticated, service_role;
revoke all on function public.admin_list_exhibitions(text, text)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_get_exhibition(text)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_create_exhibition_draft()
  from public, anon, authenticated, service_role;
revoke all on function public.admin_save_exhibition_draft(text, uuid, integer, jsonb)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_publish_exhibition(text, uuid, integer)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_archive_exhibition(text, uuid, integer)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_restore_exhibition(text, uuid, integer)
  from public, anon, authenticated, service_role;

grant execute on function public.admin_current_staff()
  to authenticated;
grant execute on function public.admin_list_exhibitions(text, text)
  to authenticated;
grant execute on function public.admin_get_exhibition(text)
  to authenticated;
grant execute on function public.admin_create_exhibition_draft()
  to authenticated;
grant execute on function public.admin_save_exhibition_draft(text, uuid, integer, jsonb)
  to authenticated;
grant execute on function public.admin_publish_exhibition(text, uuid, integer)
  to authenticated;
grant execute on function public.admin_archive_exhibition(text, uuid, integer)
  to authenticated;
grant execute on function public.admin_restore_exhibition(text, uuid, integer)
  to authenticated;
