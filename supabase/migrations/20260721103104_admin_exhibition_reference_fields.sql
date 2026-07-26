-- Complete the versioned exhibition editor contract for fields that already
-- exist in content.exhibition_versions. This migration is additive: the
-- canonical tables, published projections, and legacy readers are unchanged.

-- Preserve the Phase 3 attachment-scoped cover metadata while extending the
-- form DTO. Optional scalar fields use empty strings because AdminExhibition
-- is a controlled form model; the save boundary converts blanks back to NULL.
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
    'latitude', coalesce(version.latitude::text, ''),
    'longitude', coalesce(version.longitude::text, ''),
    'event_id', coalesce(version.event_id, ''),
    'editor_id', coalesce(version.editor_id, ''),
    'opening_date', coalesce(to_char(version.opening_date, 'YYYY-MM-DD'), ''),
    'closing_date', coalesce(to_char(version.closing_date, 'YYYY-MM-DD'), ''),
    'description_ko', version.description_ko,
    'description_en', version.description_en,
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
$$;

revoke all on function content_private.admin_exhibition_json(text, uuid)
  from public, anon, authenticated, service_role;

-- Validate the complete form patch before taking exhibition locks. Coordinates
-- remain strings in the form DTO so intermediate input is lossless; only this
-- boundary converts them to the database's double-precision representation.
create or replace function content_private.admin_validate_patch(p_patch jsonb)
returns void
language plpgsql
stable
set search_path = ''
as $$
declare
  v_key text;
  v_text_value text;
  v_trimmed_value text;
  v_max_length integer;
  v_coordinate double precision;
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
    'latitude',
    'longitude',
    'event_id',
    'editor_id',
    'opening_date',
    'closing_date',
    'description_ko',
    'description_en',
    'hours',
    'contact',
    'ticket_url',
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

  if (p_patch ? 'latitude') <> (p_patch ? 'longitude') then
    raise exception using
      errcode = '22023',
      message = 'coordinate_pair_must_be_patched_together';
  end if;

  if p_patch ? 'latitude'
     and ((nullif(btrim(p_patch ->> 'latitude'), '') is null)
       <> (nullif(btrim(p_patch ->> 'longitude'), '') is null)) then
    raise exception using
      errcode = '22023',
      message = 'coordinate_pair_must_both_be_blank_or_complete';
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
      v_trimmed_value := btrim(v_text_value);
      v_max_length := case
        when v_key in ('description_ko', 'description_en') then 50000
        when v_key in ('address_ko', 'address_en', 'ticket_url') then 2000
        when v_key in ('event_id', 'editor_id') then 128
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

      if v_key in ('latitude', 'longitude') and v_trimmed_value <> '' then
        begin
          v_coordinate := v_trimmed_value::double precision;
        exception
          when invalid_text_representation or numeric_value_out_of_range then
            raise exception using
              errcode = '22023',
              message = 'patch_coordinate_is_invalid',
              detail = v_key;
        end;

        if v_key = 'latitude'
           and (
             v_coordinate::text in ('NaN', 'Infinity', '-Infinity')
             or v_coordinate < -90
             or v_coordinate > 90
           ) then
          raise exception using
            errcode = '22023',
            message = 'latitude_out_of_range';
        end if;
        if v_key = 'longitude'
           and (
             v_coordinate::text in ('NaN', 'Infinity', '-Infinity')
             or v_coordinate < -180
             or v_coordinate > 180
           ) then
          raise exception using
            errcode = '22023',
            message = 'longitude_out_of_range';
        end if;
      end if;

      if v_key in ('event_id', 'editor_id')
         and v_trimmed_value <> ''
         and v_text_value <> v_trimmed_value then
        raise exception using
          errcode = '22023',
          message = 'association_id_is_not_trimmed',
          detail = v_key;
      end if;

      if v_key = 'ticket_url'
         and v_trimmed_value <> ''
         and v_trimmed_value !~* '^https?://[^[:space:]]+$' then
        raise exception using
          errcode = '22023',
          message = 'ticket_url_is_invalid';
      end if;
    end if;
  end loop;

  if nullif(p_patch ->> 'event_id', '') is not null
     and not exists (
       select 1 from public.events where id = p_patch ->> 'event_id'
     ) then
    raise exception using
      errcode = '23503',
      message = 'unknown_event_id';
  end if;

  if nullif(p_patch ->> 'editor_id', '') is not null
     and not exists (
       select 1 from public.editors where id = p_patch ->> 'editor_id'
     ) then
    raise exception using
      errcode = '23503',
      message = 'unknown_editor_id';
  end if;
end;
$$;

revoke all on function content_private.admin_validate_patch(jsonb)
  from public, anon, authenticated, service_role;

-- Keep every edit in the existing optimistic-concurrency transaction. The
-- association, coordinate, and ticket fields therefore share one revision and
-- one audit event with the rest of the draft patch.
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
    latitude = case when p_patch ? 'latitude'
      then nullif(btrim(p_patch ->> 'latitude'), '')::double precision
      else version.latitude end,
    longitude = case when p_patch ? 'longitude'
      then nullif(btrim(p_patch ->> 'longitude'), '')::double precision
      else version.longitude end,
    event_id = case when p_patch ? 'event_id'
      then nullif(p_patch ->> 'event_id', '') else version.event_id end,
    editor_id = case when p_patch ? 'editor_id'
      then nullif(p_patch ->> 'editor_id', '') else version.editor_id end,
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
    ticket_url = case when p_patch ? 'ticket_url'
      then nullif(btrim(p_patch ->> 'ticket_url'), '') else version.ticket_url end,
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

revoke all on function content_private.admin_save_exhibition_draft_impl(text, uuid, integer, jsonb)
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_save_exhibition_draft_impl(text, uuid, integer, jsonb)
  to authenticated;

-- Return both association catalogs in one staff-checked round trip. Inactive
-- references are deliberately included so historical assignments remain
-- visible and can be retained while editing another field.
create or replace function content_private.admin_get_exhibition_lookups_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_events jsonb;
  v_editors jsonb;
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', event.id,
        'name_ko', event.name_ko,
        'name_en', event.name_en,
        'location_label_ko', event.location_label_ko,
        'location_label_en', event.location_label_en,
        'start_date', to_char(event.start_date, 'YYYY-MM-DD'),
        'end_date', to_char(event.end_date, 'YYYY-MM-DD'),
        'short_label', event.short_label,
        'is_active', event.is_active
      )
      order by event.is_active desc, event.start_date desc, event.id
    ),
    '[]'::jsonb
  )
  into v_events
  from public.events as event;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', editor.id,
        'name_ko', editor.name_ko,
        'name_en', editor.name_en,
        'title_ko', editor.title_ko,
        'title_en', editor.title_en,
        'is_active', editor.is_active,
        'active_from', to_char(editor.active_from, 'YYYY-MM-DD'),
        'active_to', case
          when editor.active_to is null then null
          else to_char(editor.active_to, 'YYYY-MM-DD')
        end
      )
      order by
        (editor.id = 'gallr-editors') desc,
        editor.is_active desc,
        editor.active_from desc,
        editor.id
    ),
    '[]'::jsonb
  )
  into v_editors
  from public.editors as editor;

  return jsonb_build_object(
    'events', v_events,
    'editors', v_editors
  );
end;
$$;

revoke all on function content_private.admin_get_exhibition_lookups_impl()
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_get_exhibition_lookups_impl()
  to authenticated;

create or replace function public.admin_get_exhibition_lookups()
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select content_private.admin_get_exhibition_lookups_impl();
$$;

revoke all on function public.admin_get_exhibition_lookups()
  from public, anon, authenticated, service_role;
grant execute on function public.admin_get_exhibition_lookups()
  to authenticated;
