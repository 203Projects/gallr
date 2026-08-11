-- First-class Gallery Info for gallery owners. Gallery identity and canonical
-- venue defaults are one optimistic aggregate; exhibition versions remain
-- independent create-time snapshots.

alter table content.galleries
  add column revision integer not null default 1;

alter table content.galleries
  add constraint galleries_revision_positive check (revision > 0);

comment on column content.galleries.revision is
  'Optimistic aggregate revision for owner-maintained gallery identity and canonical venue defaults.';

-- Keep the historical staff quota scope while adding an equivalent owner
-- caller scope. Both share the existing project row and lock.
alter table content_private.geocode_rate_limit_windows
  drop constraint geocode_rate_limit_windows_scope_check;
alter table content_private.geocode_rate_limit_windows
  drop constraint geocode_rate_limit_windows_check;
alter table content_private.geocode_rate_limit_windows
  add constraint geocode_rate_limit_windows_scope_check
  check (scope in ('project', 'staff', 'owner'));
alter table content_private.geocode_rate_limit_windows
  add constraint geocode_rate_limit_windows_subject_scope_check
  check (
    (scope = 'project' and subject_key = 'project')
    or (scope in ('staff', 'owner') and subject_key <> 'project')
  );

comment on table content_private.geocode_rate_limit_windows is
  'Fixed one-minute NAVER geocoding counters. Private implementations enforce 10 requests per staff/owner caller and 30 per project.';

create or replace function content_private.owner_assert_gallery_info_access()
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_gallery_id uuid;
begin
  select gallery.id
  into v_gallery_id
  from content.gallery_memberships as membership
  join content.galleries as gallery on gallery.id = membership.gallery_id
  where membership.user_id = v_user_id
    and membership.role = 'owner'::content.gallery_member_role
    and (
      (
        membership.status = 'active'::content.gallery_membership_status
        and gallery.status = 'active'::content.gallery_status
      )
      or (
        membership.status = 'pending'::content.gallery_membership_status
        and gallery.status = 'pending'::content.gallery_status
        and gallery.created_by = v_user_id
        and membership.created_by = v_user_id
      )
    )
  order by membership.updated_at desc, gallery.id
  limit 1;

  if v_gallery_id is null then
    raise exception using
      errcode = '42501',
      message = 'gallery_info_access_denied';
  end if;
  return v_gallery_id;
end;
$$;

create or replace function content_private.owner_gallery_info_json(
  p_gallery_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'gallery_id', gallery.id,
    'revision', gallery.revision,
    'name_ko', gallery.name_ko,
    'name_en', gallery.name_en,
    'venue_name_ko', coalesce(venue.name_ko, gallery.name_ko),
    'venue_name_en', coalesce(venue.name_en, gallery.name_en),
    'city_ko', coalesce(venue.city_ko, ''),
    'city_en', coalesce(venue.city_en, ''),
    'region_ko', coalesce(venue.region_ko, ''),
    'region_en', coalesce(venue.region_en, ''),
    'address_ko', coalesce(venue.address_ko, ''),
    'address_en', coalesce(venue.address_en, ''),
    'latitude', venue.latitude,
    'longitude', venue.longitude,
    'hours', coalesce(venue.default_hours, ''),
    'contact', coalesce(venue.default_contact, ''),
    'updated_at', greatest(gallery.updated_at, venue.updated_at)
  )
  from content.galleries as gallery
  left join content.venues as venue on venue.id = gallery.canonical_venue_id
  where gallery.id = p_gallery_id;
$$;

create or replace function content_private.owner_validate_gallery_info_patch(
  p_patch jsonb
)
returns void
language plpgsql
stable
security invoker
set search_path = ''
as $$
declare
  v_key text;
  v_value text;
  v_coordinate double precision;
begin
  if p_patch is null or jsonb_typeof(p_patch) <> 'object' then
    raise exception using
      errcode = '22023', message = 'gallery_info_patch_invalid';
  end if;

  if (p_patch ? 'latitude') <> (p_patch ? 'longitude') then
    raise exception using
      errcode = '22023', message = 'gallery_info_location_invalid';
  end if;

  for v_key in select jsonb_object_keys(p_patch)
  loop
    if v_key not in (
      'name_ko', 'name_en', 'venue_name_ko', 'venue_name_en',
      'city_ko', 'city_en', 'region_ko', 'region_en',
      'address_ko', 'address_en', 'latitude', 'longitude',
      'hours', 'contact'
    ) then
      raise exception using
        errcode = '22023',
        message = 'gallery_info_field_not_allowed',
        detail = v_key;
    end if;

    if v_key in ('latitude', 'longitude') then
      if jsonb_typeof(p_patch -> v_key) not in ('number', 'null') then
        raise exception using
          errcode = '22023', message = 'gallery_info_field_invalid';
      end if;
      if jsonb_typeof(p_patch -> v_key) = 'number' then
        begin
          v_coordinate := (p_patch ->> v_key)::double precision;
        exception when numeric_value_out_of_range or invalid_text_representation then
          raise exception using
            errcode = '22023', message = 'gallery_info_location_invalid';
        end;
        if (v_key = 'latitude' and not v_coordinate between -90 and 90)
           or (v_key = 'longitude' and not v_coordinate between -180 and 180) then
          raise exception using
            errcode = '22023', message = 'gallery_info_location_invalid';
        end if;
      end if;
    else
      if jsonb_typeof(p_patch -> v_key) not in ('string', 'null') then
        raise exception using
          errcode = '22023', message = 'gallery_info_field_invalid';
      end if;
      v_value := coalesce(p_patch ->> v_key, '');
      if v_value ~ '[[:cntrl:]]'
         or length(v_value) > (case
           when v_key in ('address_ko', 'address_en') then 500
           when v_key in ('hours', 'contact') then 1000
           else 300
         end) then
        raise exception using
          errcode = '22023', message = 'gallery_info_field_invalid';
      end if;
    end if;
  end loop;
end;
$$;

create or replace function content_private.owner_get_gallery_info_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_gallery_id uuid := content_private.owner_assert_gallery_info_access();
begin
  return content_private.owner_gallery_info_json(v_gallery_id);
end;
$$;

create or replace function content_private.owner_save_gallery_info_impl(
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
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_gallery_id uuid := content_private.owner_assert_gallery_info_access();
  v_gallery content.galleries%rowtype;
  v_venue content.venues%rowtype;
  v_venue_id uuid;
  v_name_ko text;
  v_name_en text;
  v_venue_name_ko text;
  v_venue_name_en text;
  v_city_ko text;
  v_city_en text;
  v_region_ko text;
  v_region_en text;
  v_address_ko text;
  v_address_en text;
  v_latitude double precision;
  v_longitude double precision;
  v_hours text;
  v_contact text;
  v_changed_fields jsonb;
begin
  perform content_private.owner_validate_gallery_info_patch(p_patch);

  select gallery.*
  into v_gallery
  from content.galleries as gallery
  where gallery.id = v_gallery_id
  for update;

  -- Re-check after the lock so concurrent staff review/revocation cannot leave
  -- a stale authorization decision inside this write transaction.
  if content_private.owner_assert_gallery_info_access() <> v_gallery_id then
    raise exception using
      errcode = '42501', message = 'gallery_info_access_denied';
  end if;

  if p_expected_revision is null or p_expected_revision <> v_gallery.revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_gallery.revision::text;
  end if;

  if v_gallery.canonical_venue_id is not null then
    select venue.*
    into v_venue
    from content.venues as venue
    where venue.id = v_gallery.canonical_venue_id
    for update;
  end if;

  v_name_ko := btrim(case when p_patch ? 'name_ko'
    then coalesce(p_patch ->> 'name_ko', '') else v_gallery.name_ko end);
  v_name_en := btrim(case when p_patch ? 'name_en'
    then coalesce(p_patch ->> 'name_en', '') else v_gallery.name_en end);
  v_venue_name_ko := btrim(case when p_patch ? 'venue_name_ko'
    then coalesce(p_patch ->> 'venue_name_ko', '')
    else coalesce(v_venue.name_ko, v_gallery.name_ko) end);
  v_venue_name_en := btrim(case when p_patch ? 'venue_name_en'
    then coalesce(p_patch ->> 'venue_name_en', '')
    else coalesce(v_venue.name_en, v_gallery.name_en) end);
  v_city_ko := btrim(case when p_patch ? 'city_ko'
    then coalesce(p_patch ->> 'city_ko', '') else coalesce(v_venue.city_ko, '') end);
  v_city_en := btrim(case when p_patch ? 'city_en'
    then coalesce(p_patch ->> 'city_en', '') else coalesce(v_venue.city_en, '') end);
  v_region_ko := btrim(case when p_patch ? 'region_ko'
    then coalesce(p_patch ->> 'region_ko', '') else coalesce(v_venue.region_ko, '') end);
  v_region_en := btrim(case when p_patch ? 'region_en'
    then coalesce(p_patch ->> 'region_en', '') else coalesce(v_venue.region_en, '') end);
  v_address_ko := btrim(case when p_patch ? 'address_ko'
    then coalesce(p_patch ->> 'address_ko', '') else coalesce(v_venue.address_ko, '') end);
  v_address_en := btrim(case when p_patch ? 'address_en'
    then coalesce(p_patch ->> 'address_en', '') else coalesce(v_venue.address_en, '') end);
  v_latitude := case when p_patch ? 'latitude'
    then (p_patch ->> 'latitude')::double precision else v_venue.latitude end;
  v_longitude := case when p_patch ? 'longitude'
    then (p_patch ->> 'longitude')::double precision else v_venue.longitude end;
  v_hours := nullif(btrim(case when p_patch ? 'hours'
    then coalesce(p_patch ->> 'hours', '') else coalesce(v_venue.default_hours, '') end), '');
  v_contact := nullif(btrim(case when p_patch ? 'contact'
    then coalesce(p_patch ->> 'contact', '') else coalesce(v_venue.default_contact, '') end), '');

  if v_name_ko = '' or v_name_en = ''
     or v_venue_name_ko = '' or v_venue_name_en = ''
     or v_city_ko = '' or v_city_en = ''
     or v_region_ko = '' or v_region_en = ''
     or v_address_ko = '' or v_address_en = '' then
    raise exception using
      errcode = '22023', message = 'gallery_info_required';
  end if;
  if v_latitude is null or v_longitude is null
     or not v_latitude between -90 and 90
     or not v_longitude between -180 and 180 then
    raise exception using
      errcode = '22023', message = 'gallery_info_location_invalid';
  end if;

  if v_gallery.canonical_venue_id is null
     or exists (
       select 1
       from content.galleries as other_gallery
       where other_gallery.canonical_venue_id = v_gallery.canonical_venue_id
         and other_gallery.id <> v_gallery.id
     ) then
    insert into content.venues (
      name_ko, name_en, city_ko, city_en, region_ko, region_en,
      address_ko, address_en, latitude, longitude,
      default_hours, default_contact, created_by, updated_by
    ) values (
      v_venue_name_ko, v_venue_name_en, v_city_ko, v_city_en,
      v_region_ko, v_region_en, v_address_ko, v_address_en,
      v_latitude, v_longitude, v_hours, v_contact, v_user_id, v_user_id
    ) returning id into v_venue_id;
  else
    v_venue_id := v_gallery.canonical_venue_id;
    update content.venues
    set name_ko = v_venue_name_ko,
        name_en = v_venue_name_en,
        city_ko = v_city_ko,
        city_en = v_city_en,
        region_ko = v_region_ko,
        region_en = v_region_en,
        address_ko = v_address_ko,
        address_en = v_address_en,
        latitude = v_latitude,
        longitude = v_longitude,
        default_hours = v_hours,
        default_contact = v_contact,
        archived_at = null,
        updated_by = v_user_id
    where id = v_venue_id;
  end if;

  update content.galleries
  set name_ko = v_name_ko,
      name_en = v_name_en,
      canonical_venue_id = v_venue_id,
      revision = revision + 1,
      updated_by = v_user_id
  where id = v_gallery_id
    and revision = p_expected_revision;
  if not found then
    raise exception using errcode = '40001', message = 'revision_conflict';
  end if;

  select coalesce(jsonb_agg(field order by field), '[]'::jsonb)
  into v_changed_fields
  from jsonb_object_keys(p_patch) as field;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  ) values (
    v_user_id,
    'gallery.info_saved',
    'gallery',
    v_gallery_id::text,
    jsonb_build_object(
      'previous_revision', p_expected_revision,
      'revision', p_expected_revision + 1,
      'changed_fields', v_changed_fields
    )
  );

  return content_private.owner_gallery_info_json(v_gallery_id);
end;
$$;

create or replace function public.owner_get_gallery_info()
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select content_private.owner_get_gallery_info_impl();
$$;

create or replace function public.owner_save_gallery_info(
  p_expected_revision integer,
  p_patch jsonb
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.owner_save_gallery_info_impl(
    p_expected_revision,
    p_patch
  );
$$;

-- Shared authorization response for the existing geocode Edge Function. It
-- admits current staff or the exact Gallery Info owner predicate above.
create or replace function content_private.geocode_current_caller_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := (select auth.uid());
  v_staff_role content.staff_role;
  v_gallery_id uuid;
begin
  if v_user_id is null then
    raise exception using
      errcode = '42501', message = 'authentication_required';
  end if;

  select staff.role
  into v_staff_role
  from content.staff_members as staff
  where staff.user_id = v_user_id
    and staff.active;
  if found then
    return jsonb_build_object(
      'caller_type', 'staff',
      'user_id', v_user_id,
      'role', v_staff_role::text
    );
  end if;

  select gallery.id
  into v_gallery_id
  from auth.users as auth_user
  join content.gallery_memberships as membership
    on membership.user_id = auth_user.id
  join content.galleries as gallery on gallery.id = membership.gallery_id
  where auth_user.id = v_user_id
    and auth_user.email_confirmed_at is not null
    and membership.role = 'owner'::content.gallery_member_role
    and (
      (
        membership.status = 'active'::content.gallery_membership_status
        and gallery.status = 'active'::content.gallery_status
      )
      or (
        membership.status = 'pending'::content.gallery_membership_status
        and gallery.status = 'pending'::content.gallery_status
        and gallery.created_by = v_user_id
        and membership.created_by = v_user_id
      )
    )
  order by membership.updated_at desc, gallery.id
  limit 1;
  if v_gallery_id is not null then
    return jsonb_build_object(
      'caller_type', 'owner',
      'user_id', v_user_id,
      'gallery_id', v_gallery_id
    );
  end if;

  raise exception using
    errcode = '42501', message = 'geocode_access_required';
end;
$$;

create or replace function content_private.geocode_consume_rate_limit_impl()
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_caller jsonb := content_private.geocode_current_caller_impl();
  v_user_id uuid := (v_caller ->> 'user_id')::uuid;
  v_now timestamptz := transaction_timestamp();
  v_window_started_at timestamptz := date_trunc('minute', v_now);
  v_retry_after_seconds integer := greatest(
    1,
    least(
      60,
      ceil(extract(epoch from (
        date_trunc('minute', v_now) + interval '1 minute' - v_now
      )))::integer
    )
  );
  v_project_count integer := 0;
  v_owner_count integer := 0;
begin
  if v_caller ->> 'caller_type' = 'staff' then
    return content_private.admin_consume_geocode_rate_limit_impl();
  end if;

  perform pg_catalog.pg_advisory_xact_lock(1744830465, 1);
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtext(v_user_id::text),
    3
  );

  delete from content_private.geocode_rate_limit_windows as rate_window
  where rate_window.window_started_at
    < v_window_started_at - interval '24 hours';

  select rate_window.request_count
  into v_project_count
  from content_private.geocode_rate_limit_windows as rate_window
  where rate_window.scope = 'project'
    and rate_window.subject_key = 'project'
    and rate_window.window_started_at = v_window_started_at;
  v_project_count := coalesce(v_project_count, 0);

  select rate_window.request_count
  into v_owner_count
  from content_private.geocode_rate_limit_windows as rate_window
  where rate_window.scope = 'owner'
    and rate_window.subject_key = v_user_id::text
    and rate_window.window_started_at = v_window_started_at;
  v_owner_count := coalesce(v_owner_count, 0);

  if v_project_count >= 30 then
    return jsonb_build_object(
      'allowed', false,
      'retry_after_seconds', v_retry_after_seconds,
      'limited_by', 'project'
    );
  end if;
  if v_owner_count >= 10 then
    return jsonb_build_object(
      'allowed', false,
      'retry_after_seconds', v_retry_after_seconds,
      'limited_by', 'owner'
    );
  end if;

  insert into content_private.geocode_rate_limit_windows (
    scope, subject_key, window_started_at, request_count, updated_at
  ) values (
    'project', 'project', v_window_started_at, 1, v_now
  )
  on conflict (scope, subject_key, window_started_at)
  do update set
    request_count = geocode_rate_limit_windows.request_count + 1,
    updated_at = excluded.updated_at;

  insert into content_private.geocode_rate_limit_windows (
    scope, subject_key, window_started_at, request_count, updated_at
  ) values (
    'owner', v_user_id::text, v_window_started_at, 1, v_now
  )
  on conflict (scope, subject_key, window_started_at)
  do update set
    request_count = geocode_rate_limit_windows.request_count + 1,
    updated_at = excluded.updated_at;

  return jsonb_build_object(
    'allowed', true,
    'retry_after_seconds', 0,
    'limited_by', null
  );
end;
$$;

create or replace function public.geocode_current_caller()
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select content_private.geocode_current_caller_impl();
$$;

create or replace function public.geocode_consume_rate_limit()
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.geocode_consume_rate_limit_impl();
$$;

-- Add coordinates to the owner draft representation without dropping the
-- public-impact metrics introduced by the later Gallery Public Impact slice.
create or replace function content_private.owner_exhibition_json(
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
    'revision', version.revision,
    'owner_status', exhibition.owner_status::text,
    'review_notes', coalesce(exhibition.owner_review_notes, ''),
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
    'latitude', version.latitude,
    'longitude', version.longitude,
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
    'ticket_url', coalesce(version.ticket_url, ''),
    'updated_at', greatest(exhibition.updated_at, version.updated_at),
    'page_loads_30d', case
      when exhibition.owner_status = 'published'::content.owner_exhibition_status
        and exhibition.archived_at is null then impact.page_loads_30d
      else 0
    end,
    'page_loads_all_time', case
      when exhibition.owner_status = 'published'::content.owner_exhibition_status
        and exhibition.archived_at is null then impact.page_loads_all_time
      else 0
    end,
    'cover', cover.payload
  )
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = p_version_id
  left join lateral (
    select
      coalesce(sum(metric.page_loads) filter (
        where metric.metric_date >= (now() at time zone 'UTC')::date - 29
      ), 0)::bigint as page_loads_30d,
      coalesce(sum(metric.page_loads), 0)::bigint as page_loads_all_time
    from content.exhibition_daily_metrics as metric
    where metric.exhibition_id = exhibition.id
  ) as impact on true
  left join lateral (
    select jsonb_build_object(
      'asset_id', asset.id,
      'status', asset.status::text,
      'bucket_id', asset.bucket_id,
      'object_path', asset.object_path,
      'public_url', asset.public_url,
      'mime_type', coalesce(asset.mime_type, ''),
      'byte_size', coalesce(asset.byte_size, 0),
      'original_filename', coalesce(asset.metadata ->> 'original_filename', '')
    ) as payload
    from content.exhibition_version_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.version_id = version.id
      and attachment.role = 'cover'::content.media_role
    order by attachment.created_at desc, attachment.media_id
    limit 1
  ) as cover on true;
$$;

create or replace function content_private.owner_validate_exhibition_patch(
  p_patch jsonb
)
returns void
language plpgsql
stable
security invoker
set search_path = ''
as $$
declare
  v_key text;
  v_value text;
  v_coordinate double precision;
begin
  if p_patch is null or jsonb_typeof(p_patch) <> 'object' then
    raise exception using errcode = '22023', message = 'patch_must_be_an_object';
  end if;
  if (p_patch ? 'latitude') <> (p_patch ? 'longitude') then
    raise exception using errcode = '22023', message = 'owner_patch_coordinate_pair_invalid';
  end if;
  for v_key in select jsonb_object_keys(p_patch)
  loop
    if v_key not in (
      'name_ko', 'name_en', 'venue_name_ko', 'venue_name_en',
      'city_ko', 'city_en', 'region_ko', 'region_en',
      'address_ko', 'address_en', 'latitude', 'longitude',
      'opening_date', 'closing_date', 'description_ko', 'description_en',
      'hours', 'contact', 'reception_date', 'reception_start_time', 'ticket_url'
    ) then
      raise exception using
        errcode = '22023', message = 'owner_patch_field_not_allowed', detail = v_key;
    end if;
    if v_key in ('latitude', 'longitude') then
      if jsonb_typeof(p_patch -> v_key) not in ('number', 'null') then
        raise exception using errcode = '22023', message = 'owner_patch_field_invalid';
      end if;
      if jsonb_typeof(p_patch -> v_key) = 'number' then
        begin
          v_coordinate := (p_patch ->> v_key)::double precision;
        exception when numeric_value_out_of_range or invalid_text_representation then
          raise exception using errcode = '22023', message = 'owner_patch_coordinate_invalid';
        end;
        if (v_key = 'latitude' and not v_coordinate between -90 and 90)
           or (v_key = 'longitude' and not v_coordinate between -180 and 180) then
          raise exception using errcode = '22023', message = 'owner_patch_coordinate_invalid';
        end if;
      end if;
      continue;
    end if;
    if jsonb_typeof(p_patch -> v_key) not in ('string', 'null') then
      raise exception using errcode = '22023', message = 'owner_patch_field_invalid';
    end if;
    v_value := coalesce(p_patch ->> v_key, '');
    if length(v_value) > (case
      when v_key in ('description_ko', 'description_en') then 20000
      when v_key in ('hours', 'contact') then 1000
      when v_key in ('address_ko', 'address_en') then 500
      else 300
    end) then
      raise exception using errcode = '22023', message = 'owner_patch_field_too_long';
    end if;
    if v_key in ('opening_date', 'closing_date', 'reception_date')
       and v_value <> '' then
      if v_value !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then
        raise exception using errcode = '22023', message = 'owner_patch_date_invalid';
      end if;
      begin
        perform v_value::date;
      exception when datetime_field_overflow or invalid_datetime_format then
        raise exception using errcode = '22023', message = 'owner_patch_date_invalid';
      end;
    end if;
    if v_key = 'reception_start_time' and v_value <> ''
       and v_value !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' then
      raise exception using errcode = '22023', message = 'owner_patch_time_invalid';
    end if;
    if v_key = 'ticket_url' and v_value <> ''
       and v_value !~* '^https?://[^[:space:]]+$' then
      raise exception using errcode = '22023', message = 'owner_patch_ticket_url_invalid';
    end if;
  end loop;
end;
$$;

create or replace function content_private.owner_save_exhibition_draft_impl(
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
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_version content.exhibition_versions%rowtype;
  v_new_revision integer;
begin
  perform content_private.owner_validate_exhibition_patch(p_patch);
  v_version := content_private.owner_assert_exhibition_draft(
    p_exhibition_id, p_expected_version_id, p_expected_revision
  );

  update content.exhibition_versions as version
  set
    name_ko = case when p_patch ? 'name_ko' then coalesce(p_patch ->> 'name_ko', '') else version.name_ko end,
    name_en = case when p_patch ? 'name_en' then coalesce(p_patch ->> 'name_en', '') else version.name_en end,
    venue_name_ko = case when p_patch ? 'venue_name_ko' then coalesce(p_patch ->> 'venue_name_ko', '') else version.venue_name_ko end,
    venue_name_en = case when p_patch ? 'venue_name_en' then coalesce(p_patch ->> 'venue_name_en', '') else version.venue_name_en end,
    city_ko = case when p_patch ? 'city_ko' then coalesce(p_patch ->> 'city_ko', '') else version.city_ko end,
    city_en = case when p_patch ? 'city_en' then coalesce(p_patch ->> 'city_en', '') else version.city_en end,
    region_ko = case when p_patch ? 'region_ko' then coalesce(p_patch ->> 'region_ko', '') else version.region_ko end,
    region_en = case when p_patch ? 'region_en' then coalesce(p_patch ->> 'region_en', '') else version.region_en end,
    address_ko = case when p_patch ? 'address_ko' then coalesce(p_patch ->> 'address_ko', '') else version.address_ko end,
    address_en = case when p_patch ? 'address_en' then coalesce(p_patch ->> 'address_en', '') else version.address_en end,
    latitude = case when p_patch ? 'latitude' then (p_patch ->> 'latitude')::double precision else version.latitude end,
    longitude = case when p_patch ? 'longitude' then (p_patch ->> 'longitude')::double precision else version.longitude end,
    opening_date = case when p_patch ? 'opening_date' then nullif(p_patch ->> 'opening_date', '')::date else version.opening_date end,
    closing_date = case when p_patch ? 'closing_date' then nullif(p_patch ->> 'closing_date', '')::date else version.closing_date end,
    description_ko = case when p_patch ? 'description_ko' then coalesce(p_patch ->> 'description_ko', '') else version.description_ko end,
    description_en = case when p_patch ? 'description_en' then coalesce(p_patch ->> 'description_en', '') else version.description_en end,
    hours = case when p_patch ? 'hours' then nullif(p_patch ->> 'hours', '') else version.hours end,
    contact = case when p_patch ? 'contact' then nullif(p_patch ->> 'contact', '') else version.contact end,
    reception_date = case
      when p_patch ? 'reception_date' then
        case when nullif(p_patch ->> 'reception_date', '') is null then null
          else (p_patch ->> 'reception_date')::date::timestamp at time zone 'Asia/Seoul' end
      else version.reception_date
    end,
    opening_time = case when p_patch ? 'reception_start_time' then nullif(p_patch ->> 'reception_start_time', '') else version.opening_time end,
    ticket_url = case when p_patch ? 'ticket_url' then nullif(p_patch ->> 'ticket_url', '') else version.ticket_url end,
    revision = version.revision + 1,
    updated_by = v_user_id
  where version.id = v_version.id
    and version.revision = p_expected_revision
  returning revision into v_new_revision;
  if not found then
    raise exception using errcode = '40001', message = 'revision_conflict';
  end if;

  update content.exhibitions
  set updated_by = v_user_id
  where id = p_exhibition_id;
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  ) values (
    v_user_id, 'owner_exhibition.draft_saved', 'exhibition', p_exhibition_id,
    jsonb_build_object('version_id', v_version.id, 'revision', v_new_revision)
  );
  return content_private.owner_exhibition_json(p_exhibition_id, v_version.id);
end;
$$;

revoke all on function content_private.owner_assert_gallery_info_access()
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_gallery_info_json(uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_validate_gallery_info_patch(jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_get_gallery_info_impl()
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_save_gallery_info_impl(integer, jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.geocode_current_caller_impl()
  from public, anon, authenticated, service_role;
revoke all on function content_private.geocode_consume_rate_limit_impl()
  from public, anon, authenticated, service_role;

grant execute on function content_private.owner_get_gallery_info_impl(),
  content_private.owner_save_gallery_info_impl(integer, jsonb),
  content_private.geocode_current_caller_impl(),
  content_private.geocode_consume_rate_limit_impl()
to authenticated;

revoke all on function public.owner_get_gallery_info()
  from public, anon, authenticated, service_role;
revoke all on function public.owner_save_gallery_info(integer, jsonb)
  from public, anon, authenticated, service_role;
revoke all on function public.geocode_current_caller()
  from public, anon, authenticated, service_role;
revoke all on function public.geocode_consume_rate_limit()
  from public, anon, authenticated, service_role;

grant execute on function public.owner_get_gallery_info(),
  public.owner_save_gallery_info(integer, jsonb),
  public.geocode_current_caller(),
  public.geocode_consume_rate_limit()
to authenticated;
