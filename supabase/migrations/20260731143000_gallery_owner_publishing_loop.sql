-- Account-backed gallery exhibition drafts, review rounds, and staff handoff.
-- Owner records reuse the canonical versioned CMS and public publication
-- pointer. Legacy staff exhibitions and anonymous submissions remain intact.

do $$
begin
  create type content.owner_exhibition_status as enum (
    'draft',
    'submitted',
    'needs_changes',
    'published',
    'archived'
  );
exception when duplicate_object then null;
end
$$;

alter table content.exhibitions
  add column if not exists owner_status content.owner_exhibition_status,
  add column if not exists owner_review_notes text,
  add column if not exists owner_status_changed_at timestamptz;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'exhibitions_owner_review_notes_length'
      and conrelid = 'content.exhibitions'::regclass
  ) then
    alter table content.exhibitions
      add constraint exhibitions_owner_review_notes_length
      check (owner_review_notes is null or length(owner_review_notes) <= 2000);
  end if;
end
$$;

alter table content.exhibition_submissions
  add column if not exists source text not null default 'public_form',
  add column if not exists owner_exhibition_id text;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'exhibition_submissions_source_check'
      and conrelid = 'content.exhibition_submissions'::regclass
  ) then
    alter table content.exhibition_submissions
      add constraint exhibition_submissions_source_check
      check (source in ('public_form', 'owner_workspace'));
  end if;
  if not exists (
    select 1 from pg_constraint
    where conname = 'exhibition_submissions_owner_exhibition_fk'
      and conrelid = 'content.exhibition_submissions'::regclass
  ) then
    alter table content.exhibition_submissions
      add constraint exhibition_submissions_owner_exhibition_fk
      foreign key (owner_exhibition_id)
      references content.exhibitions(id)
      on delete restrict;
  end if;
  if not exists (
    select 1 from pg_constraint
    where conname = 'exhibition_submissions_owner_source_pair'
      and conrelid = 'content.exhibition_submissions'::regclass
  ) then
    alter table content.exhibition_submissions
      add constraint exhibition_submissions_owner_source_pair
      check (
        (source = 'owner_workspace' and owner_exhibition_id is not null)
        or (source = 'public_form' and owner_exhibition_id is null)
      );
  end if;
end
$$;

create index if not exists exhibitions_gallery_owner_status_idx
  on content.exhibitions (gallery_id, owner_status, updated_at desc)
  where gallery_id is not null and owner_status is not null;
create index if not exists exhibition_submissions_owner_exhibition_idx
  on content.exhibition_submissions (owner_exhibition_id, created_at desc)
  where owner_exhibition_id is not null;
create unique index if not exists exhibition_submissions_one_open_owner_round_idx
  on content.exhibition_submissions (owner_exhibition_id)
  where source = 'owner_workspace'
    and status in (
      'submitted'::content.submission_status,
      'in_review'::content.submission_status
    );

comment on column content.exhibitions.owner_status is
  'Customer-visible lifecycle for account-backed gallery records; null for legacy/staff-only identities.';
comment on column content.exhibition_submissions.owner_exhibition_id is
  'Existing canonical draft reviewed by an account-backed owner submission round.';

drop policy if exists "gallery owners upload draft media" on storage.objects;
create policy "gallery owners upload draft media"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'exhibition-media'
  and (storage.foldername(name))[1] = 'owner-drafts'
  and (storage.foldername(name))[2] = (select auth.uid())::text
);

drop policy if exists "gallery owners read draft media" on storage.objects;
create policy "gallery owners read draft media"
on storage.objects for select to authenticated
using (
  bucket_id = 'exhibition-media'
  and (storage.foldername(name))[1] = 'owner-drafts'
  and (storage.foldername(name))[2] = (select auth.uid())::text
);

create or replace function content_private.owner_assert_gallery_membership(
  p_require_active boolean default false
)
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
  select membership.gallery_id
  into v_gallery_id
  from content.gallery_memberships as membership
  where membership.user_id = v_user_id
    and (
      membership.status = 'active'::content.gallery_membership_status
      or (
        not p_require_active
        and membership.status = 'pending'::content.gallery_membership_status
      )
    )
  order by membership.updated_at desc, membership.gallery_id
  limit 1;

  if v_gallery_id is null then
    raise exception using
      errcode = '42501',
      message = case
        when p_require_active then 'active_gallery_membership_required'
        else 'gallery_membership_required'
      end;
  end if;
  return v_gallery_id;
end;
$$;

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
    'public_path', case
      when exhibition.published_version_id is not null
        then '/exhibitions/' || exhibition.id || '/'
      else ''
    end,
    'cover', cover.payload
  )
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = p_version_id
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

create or replace function content_private.owner_assert_exhibition_draft(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns content.exhibition_versions
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(false);
  v_status content.owner_exhibition_status;
  v_version content.exhibition_versions%rowtype;
begin
  if p_expected_version_id is null or p_expected_revision is null
     or p_expected_revision < 1 then
    raise exception using errcode = '22023', message = 'owner_revision_required';
  end if;

  select exhibition.owner_status into v_status
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
    and exhibition.gallery_id = v_gallery_id
  for update;
  if not found then
    raise exception using errcode = '42501', message = 'owner_exhibition_access_denied';
  end if;
  if v_status not in (
    'draft'::content.owner_exhibition_status,
    'needs_changes'::content.owner_exhibition_status
  ) then
    raise exception using errcode = '22023', message = 'owner_exhibition_not_editable';
  end if;

  select version.* into v_version
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.id = p_expected_version_id
    and version.status = 'draft'::content.exhibition_version_status
  for update;
  if not found then
    raise exception using errcode = '42501', message = 'owner_exhibition_access_denied';
  end if;
  if v_version.revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_version.revision::text;
  end if;
  return v_version;
end;
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
begin
  if p_patch is null or jsonb_typeof(p_patch) <> 'object' then
    raise exception using errcode = '22023', message = 'patch_must_be_an_object';
  end if;
  for v_key in select jsonb_object_keys(p_patch)
  loop
    if v_key not in (
      'name_ko', 'name_en', 'venue_name_ko', 'venue_name_en',
      'city_ko', 'city_en', 'region_ko', 'region_en',
      'address_ko', 'address_en', 'opening_date', 'closing_date',
      'description_ko', 'description_en', 'hours', 'contact',
      'reception_date', 'reception_start_time', 'ticket_url'
    ) then
      raise exception using
        errcode = '22023', message = 'owner_patch_field_not_allowed', detail = v_key;
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

create or replace function content_private.owner_list_exhibitions_impl()
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(false);
begin
  return query
  select content_private.owner_exhibition_json(exhibition.id, chosen.id)
  from content.exhibitions as exhibition
  join lateral (
    select version.id
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
  where exhibition.gallery_id = v_gallery_id
    and exhibition.owner_status is not null
  order by exhibition.updated_at desc, exhibition.id;
end;
$$;

create or replace function content_private.owner_create_exhibition_draft_impl(
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(false);
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_exhibition_id text := gen_random_uuid()::text;
  v_version_id uuid;
  v_response jsonb;
begin
  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('gallery_id', v_gallery_id)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_create_exhibition_draft', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  insert into content.exhibitions (
    id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
  ) values (
    v_exhibition_id, v_gallery_id, 'draft', now(), v_user_id, v_user_id
  );

  insert into content.exhibition_versions (
    exhibition_id, version_number, revision, status, venue_id,
    venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
    address_ko, address_en, latitude, longitude, hours, contact,
    created_by, updated_by
  )
  select
    v_exhibition_id, 1, 1, 'draft', venue.id,
    coalesce(venue.name_ko, gallery.name_ko),
    coalesce(venue.name_en, gallery.name_en),
    coalesce(venue.city_ko, ''), coalesce(venue.city_en, ''),
    coalesce(venue.region_ko, ''), coalesce(venue.region_en, ''),
    coalesce(venue.address_ko, ''), coalesce(venue.address_en, ''),
    venue.latitude, venue.longitude, venue.default_hours, venue.default_contact,
    v_user_id, v_user_id
  from content.galleries as gallery
  left join content.venues as venue on venue.id = gallery.canonical_venue_id
  where gallery.id = v_gallery_id
  returning id into v_version_id;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id, metadata
  ) values (
    v_user_id, 'owner_exhibition.draft_created', 'exhibition',
    v_exhibition_id, p_request_id,
    jsonb_build_object('gallery_id', v_gallery_id, 'version_id', v_version_id)
  );
  v_response := content_private.owner_exhibition_json(v_exhibition_id, v_version_id);
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_create_exhibition_draft',
    v_fingerprint, v_response
  );
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

create or replace function content_private.owner_reserve_cover_upload_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_mime_type text,
  p_byte_size bigint,
  p_original_filename text
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
  v_asset_id uuid := gen_random_uuid();
  v_extension text;
  v_path text;
begin
  v_version := content_private.owner_assert_exhibition_draft(
    p_exhibition_id, p_expected_version_id, p_expected_revision
  );
  v_extension := case p_mime_type
    when 'image/jpeg' then 'jpg'
    when 'image/png' then 'png'
    when 'image/webp' then 'webp'
    else null
  end;
  if v_extension is null then
    raise exception using errcode = '22023', message = 'owner_cover_mime_invalid';
  end if;
  if p_byte_size is null or p_byte_size < 1 or p_byte_size > 10485760 then
    raise exception using errcode = '22023', message = 'owner_cover_size_invalid';
  end if;
  if nullif(btrim(p_original_filename), '') is null
     or length(p_original_filename) > 255 then
    raise exception using errcode = '22023', message = 'owner_cover_filename_invalid';
  end if;
  v_path := format(
    'owner-drafts/%s/%s/original.%s', v_user_id, v_asset_id, v_extension
  );
  insert into content.media_assets (
    id, status, bucket_id, object_path, mime_type, byte_size, uploaded_by, metadata
  ) values (
    v_asset_id, 'pending_upload', 'exhibition-media', v_path,
    p_mime_type, p_byte_size, v_user_id,
    jsonb_build_object(
      'original_filename', btrim(p_original_filename),
      'exhibition_id', p_exhibition_id,
      'draft_version_id', v_version.id,
      'owner_user_id', v_user_id
    )
  );
  return jsonb_build_object(
    'asset_id', v_asset_id,
    'bucket_id', 'exhibition-media',
    'object_path', v_path,
    'mime_type', p_mime_type,
    'byte_size', p_byte_size
  );
end;
$$;

create or replace function content_private.owner_complete_cover_upload_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_asset_id uuid
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
  v_asset content.media_assets%rowtype;
  v_object_metadata jsonb;
  v_extension text;
  v_delivery_path text;
begin
  v_version := content_private.owner_assert_exhibition_draft(
    p_exhibition_id, p_expected_version_id, p_expected_revision
  );
  select asset.* into v_asset
  from content.media_assets as asset
  where asset.id = p_asset_id
    and asset.status = 'pending_upload'::content.media_asset_status
    and asset.metadata ->> 'owner_user_id' = v_user_id::text
    and asset.metadata ->> 'exhibition_id' = p_exhibition_id
    and asset.metadata ->> 'draft_version_id' = v_version.id::text
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'owner_cover_reservation_not_found';
  end if;

  select object.metadata into v_object_metadata
  from storage.objects as object
  where object.bucket_id = v_asset.bucket_id
    and object.name = v_asset.object_path;
  if not found then
    raise exception using errcode = 'P0002', message = 'owner_cover_object_not_found';
  end if;
  if coalesce(
       v_object_metadata ->> 'mimetype',
       v_object_metadata ->> 'contentType',
       v_object_metadata ->> 'content-type'
     ) is distinct from v_asset.mime_type then
    raise exception using errcode = '22023', message = 'owner_cover_mime_mismatch';
  end if;
  if coalesce(
       v_object_metadata ->> 'size',
       v_object_metadata ->> 'contentLength',
       v_object_metadata ->> 'content-length'
     ) is distinct from v_asset.byte_size::text then
    raise exception using errcode = '22023', message = 'owner_cover_size_mismatch';
  end if;

  v_extension := case v_asset.mime_type
    when 'image/jpeg' then 'jpg'
    when 'image/png' then 'png'
    when 'image/webp' then 'webp'
  end;
  v_delivery_path := format('cms/%s/original.%s', p_asset_id, v_extension);

  update content.media_assets
  set
    status = 'ready'::content.media_asset_status,
    delivery_bucket_id = 'exhibition-images',
    delivery_object_path = v_delivery_path
  where id = p_asset_id;

  update content.media_assets as asset
  set status = 'orphaned'::content.media_asset_status
  where asset.id in (
    select attachment.media_id
    from content.exhibition_version_media as attachment
    where attachment.version_id = v_version.id
      and attachment.role = 'cover'::content.media_role
      and attachment.media_id <> p_asset_id
  ) and asset.status <> 'published'::content.media_asset_status;
  delete from content.exhibition_version_media
  where version_id = v_version.id
    and role = 'cover'::content.media_role;
  insert into content.exhibition_version_media (
    version_id, media_id, role, sort_order, created_by
  ) values (v_version.id, p_asset_id, 'cover', 0, v_user_id);

  update content.exhibition_versions
  set revision = revision + 1, updated_by = v_user_id
  where id = v_version.id and revision = p_expected_revision;
  if not found then
    raise exception using errcode = '40001', message = 'revision_conflict';
  end if;
  update content.exhibitions set updated_by = v_user_id where id = p_exhibition_id;

  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key
  ) values (
    'media_asset', p_asset_id::text, 'media.publish_requested',
    jsonb_build_object(
      'asset_id', p_asset_id,
      'source_bucket_id', v_asset.bucket_id,
      'source_object_path', v_asset.object_path,
      'delivery_bucket_id', 'exhibition-images',
      'delivery_object_path', v_delivery_path
    ),
    format('media:%s:publish_requested', p_asset_id)
  ) on conflict (deduplication_key) do nothing;
  return content_private.owner_exhibition_json(p_exhibition_id, v_version.id);
end;
$$;

create or replace function content_private.owner_submit_exhibition_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(true);
  v_version content.exhibition_versions%rowtype;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_submission_id uuid := gen_random_uuid();
  v_email text;
  v_response jsonb;
begin
  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object(
      'exhibition_id', p_exhibition_id,
      'version_id', p_expected_version_id,
      'revision', p_expected_revision
    )
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_submit_exhibition', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  v_version := content_private.owner_assert_exhibition_draft(
    p_exhibition_id, p_expected_version_id, p_expected_revision
  );
  if not exists (
    select 1 from content.exhibitions
    where id = p_exhibition_id and gallery_id = v_gallery_id
  ) then
    raise exception using errcode = '42501', message = 'owner_exhibition_access_denied';
  end if;
  if nullif(btrim(v_version.name_ko), '') is null
     or nullif(btrim(v_version.venue_name_ko), '') is null
     or nullif(btrim(v_version.city_ko), '') is null
     or nullif(btrim(v_version.region_ko), '') is null
     or nullif(btrim(v_version.address_ko), '') is null
     or v_version.opening_date is null
     or v_version.closing_date is null
     or v_version.closing_date < v_version.opening_date
     or nullif(btrim(v_version.hours), '') is null then
    raise exception using errcode = '23514', message = 'owner_submission_incomplete';
  end if;
  if not exists (
    select 1
    from content.exhibition_version_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.version_id = v_version.id
      and attachment.role = 'cover'::content.media_role
      and asset.status in (
        'ready'::content.media_asset_status,
        'published'::content.media_asset_status
      )
  ) then
    raise exception using errcode = '23514', message = 'owner_submission_cover_required';
  end if;
  select email into v_email from auth.users where id = v_user_id;

  insert into content.exhibition_submissions (
    id, status, submitter_email, payload, source, owner_exhibition_id, submitted_at
  ) values (
    v_submission_id, 'submitted', lower(v_email),
    jsonb_build_object(
      'name_ko', v_version.name_ko,
      'name_en', v_version.name_en,
      'venue_name_ko', v_version.venue_name_ko,
      'venue_name_en', v_version.venue_name_en,
      'opening_date', to_char(v_version.opening_date, 'YYYY-MM-DD'),
      'closing_date', to_char(v_version.closing_date, 'YYYY-MM-DD'),
      'address_ko', v_version.address_ko,
      'address_en', v_version.address_en,
      'hours', coalesce(v_version.hours, ''),
      'description_ko', v_version.description_ko,
      'description_en', v_version.description_en,
      'reception_date', '',
      'reception_end', '',
      'version_id', v_version.id,
      'revision', v_version.revision
    ),
    'owner_workspace', p_exhibition_id, now()
  );
  update content.exhibitions
  set
    owner_status = 'submitted',
    owner_review_notes = null,
    owner_status_changed_at = now(),
    updated_by = v_user_id
  where id = p_exhibition_id;
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id, metadata
  ) values (
    v_user_id, 'owner_exhibition.submitted', 'exhibition', p_exhibition_id,
    p_request_id,
    jsonb_build_object(
      'gallery_id', v_gallery_id,
      'version_id', v_version.id,
      'revision', v_version.revision,
      'submission_id', v_submission_id
    )
  );
  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key
  ) values (
    'exhibition', p_exhibition_id, 'owner_exhibition.submitted',
    jsonb_build_object(
      'exhibition_id', p_exhibition_id,
      'gallery_id', v_gallery_id,
      'submission_id', v_submission_id
    ),
    format('owner_exhibition:%s:submitted:%s', p_exhibition_id, p_request_id)
  );
  v_response := content_private.owner_exhibition_json(
    p_exhibition_id, v_version.id
  );
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_submit_exhibition', v_fingerprint, v_response
  );
end;
$$;

-- Gallery-claim staff queue and idempotent decisions.
create or replace function content_private.admin_list_gallery_claims_impl(
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
  perform content_private.admin_assert_staff('publisher'::content.staff_role);
  if p_status is not null and p_status not in (
    'pending', 'active', 'rejected', 'suspended', 'revoked'
  ) then
    raise exception using errcode = '22023', message = 'gallery_claim_status_invalid';
  end if;
  return query
  select jsonb_build_object(
    'gallery_id', gallery.id,
    'gallery_name_ko', gallery.name_ko,
    'gallery_name_en', gallery.name_en,
    'gallery_status', gallery.status::text,
    'user_id', membership.user_id,
    'owner_email', coalesce(owner.email, ''),
    'membership_status', membership.status::text,
    'website_url', coalesce(membership.claim_website_url, ''),
    'social_url', coalesce(membership.claim_social_url, ''),
    'claim_note', coalesce(membership.claim_note, ''),
    'review_notes', coalesce(membership.review_notes, ''),
    'created_at', membership.created_at,
    'reviewed_at', membership.reviewed_at
  )
  from content.gallery_memberships as membership
  join content.galleries as gallery on gallery.id = membership.gallery_id
  join auth.users as owner on owner.id = membership.user_id
  where (p_status is null or membership.status::text = p_status)
    and (
      v_search = ''
      or lower(gallery.name_ko) like '%' || v_search || '%'
      or lower(gallery.name_en) like '%' || v_search || '%'
      or lower(owner.email) like '%' || v_search || '%'
    )
  order by membership.created_at desc, gallery.id, membership.user_id;
end;
$$;

create or replace function content_private.admin_decide_gallery_claim_impl(
  p_gallery_id uuid,
  p_user_id uuid,
  p_approve boolean,
  p_review_notes text,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor_id uuid := content_private.admin_assert_staff('publisher'::content.staff_role);
  v_membership content.gallery_memberships%rowtype;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_response jsonb;
  v_action text := case when p_approve then 'admin_approve_gallery_claim' else 'admin_reject_gallery_claim' end;
begin
  if not p_approve and (
    nullif(btrim(p_review_notes), '') is null or length(p_review_notes) > 2000
  ) then
    raise exception using errcode = '22023', message = 'review_notes_required';
  end if;
  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object(
      'gallery_id', p_gallery_id,
      'user_id', p_user_id,
      'approve', p_approve,
      'review_notes', case when p_approve then '' else btrim(p_review_notes) end
    )
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_actor_id, p_request_id, v_action, v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  select membership.* into v_membership
  from content.gallery_memberships as membership
  where membership.gallery_id = p_gallery_id
    and membership.user_id = p_user_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'gallery_claim_not_found';
  end if;
  if v_membership.status <> 'pending'::content.gallery_membership_status then
    raise exception using errcode = '22023', message = 'gallery_claim_not_pending';
  end if;

  update content.gallery_memberships
  set
    status = case
      when p_approve then 'active'::content.gallery_membership_status
      else 'rejected'::content.gallery_membership_status
    end,
    reviewed_at = now(),
    reviewed_by = v_actor_id,
    review_notes = case when p_approve then null else btrim(p_review_notes) end,
    updated_by = v_actor_id
  where gallery_id = p_gallery_id and user_id = p_user_id;
  if p_approve then
    update content.galleries
    set status = 'active', updated_by = v_actor_id
    where id = p_gallery_id and status = 'pending';
  else
    update content.galleries
    set status = 'disabled', updated_by = v_actor_id
    where id = p_gallery_id and status = 'pending';
  end if;
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id, metadata
  ) values (
    v_actor_id,
    case when p_approve then 'gallery.claim_approved' else 'gallery.claim_rejected' end,
    'gallery', p_gallery_id::text, p_request_id,
    jsonb_build_object('user_id', p_user_id)
  );
  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key
  ) values (
    'gallery', p_gallery_id::text,
    case when p_approve then 'gallery.claim_approved' else 'gallery.claim_rejected' end,
    jsonb_build_object('gallery_id', p_gallery_id, 'user_id', p_user_id),
    format('gallery:%s:claim:%s:%s', p_gallery_id, case when p_approve then 'approved' else 'rejected' end, p_request_id)
  );
  select value into v_response
  from content_private.admin_list_gallery_claims_impl(
    '', case when p_approve then 'active' else 'rejected' end
  ) as value
  where value ->> 'gallery_id' = p_gallery_id::text
    and value ->> 'user_id' = p_user_id::text;
  return content_private.complete_command_request(
    v_actor_id, p_request_id, v_action, v_fingerprint, v_response
  );
end;
$$;

-- Add owner-source context to the existing staff submissions DTO.
create or replace function content_private.admin_submission_json(
  p_submission_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', submission.id,
    'status', submission.status::text,
    'source', submission.source,
    'owner_exhibition_id', submission.owner_exhibition_id,
    'gallery_name_ko', coalesce(gallery.name_ko, ''),
    'gallery_name_en', coalesce(gallery.name_en, ''),
    'submitter_email', coalesce(submission.submitter_email, ''),
    'payload', submission.payload,
    'accepted_exhibition_id', submission.accepted_exhibition_id,
    'review_notes', coalesce(submission.review_notes, ''),
    'submitted_at', submission.submitted_at,
    'reviewed_at', submission.reviewed_at,
    'created_at', submission.created_at,
    'media', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'asset_id', asset.id,
            'bucket_id', asset.bucket_id,
            'object_path', asset.object_path,
            'mime_type', coalesce(asset.mime_type, ''),
            'byte_size', asset.byte_size,
            'original_filename', coalesce(asset.metadata ->> 'original_filename', '')
          ) order by attachment.sort_order, asset.id
        )
        from content.submission_media as attachment
        join content.media_assets as asset on asset.id = attachment.media_id
        where attachment.submission_id = submission.id
      ),
      '[]'::jsonb
    )
  )
  from content.exhibition_submissions as submission
  left join content.exhibitions as exhibition
    on exhibition.id = submission.owner_exhibition_id
  left join content.galleries as gallery on gallery.id = exhibition.gallery_id
  where submission.id = p_submission_id;
$$;

do $$
begin
  if to_regprocedure('content_private.admin_accept_public_submission_impl(uuid)') is null then
    alter function content_private.admin_accept_submission_impl(uuid)
      rename to admin_accept_public_submission_impl;
  end if;
  if to_regprocedure('content_private.admin_reject_public_submission_impl(uuid,text)') is null then
    alter function content_private.admin_reject_submission_impl(uuid, text)
      rename to admin_reject_public_submission_impl;
  end if;
end
$$;

create or replace function content_private.admin_accept_submission_impl(
  p_submission_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.admin_assert_staff('publisher'::content.staff_role);
  v_submission content.exhibition_submissions%rowtype;
  v_version_id uuid;
begin
  select submission.* into v_submission
  from content.exhibition_submissions as submission
  where submission.id = p_submission_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'submission_not_found';
  end if;
  if v_submission.source <> 'owner_workspace' then
    return content_private.admin_accept_public_submission_impl(p_submission_id);
  end if;
  if v_submission.status not in (
    'submitted'::content.submission_status,
    'in_review'::content.submission_status
  ) then
    raise exception using errcode = '22023', message = 'submission_not_acceptable';
  end if;
  select version.id into v_version_id
  from content.exhibition_versions as version
  where version.exhibition_id = v_submission.owner_exhibition_id
    and version.status = 'draft'::content.exhibition_version_status
  order by version.version_number desc
  limit 1;
  if v_version_id is null then
    raise exception using errcode = 'P0002', message = 'owner_draft_not_found';
  end if;
  update content.exhibition_submissions
  set
    status = 'accepted',
    accepted_exhibition_id = owner_exhibition_id,
    reviewed_by = v_user_id,
    reviewed_at = now()
  where id = p_submission_id;
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  ) values (
    v_user_id, 'submission.accepted', 'exhibition_submission',
    p_submission_id::text,
    jsonb_build_object(
      'exhibition_id', v_submission.owner_exhibition_id,
      'version_id', v_version_id,
      'source', 'owner_workspace'
    )
  );
  return jsonb_build_object(
    'submission', content_private.admin_submission_json(p_submission_id),
    'exhibition', content_private.admin_exhibition_json(
      v_submission.owner_exhibition_id, v_version_id
    )
  );
end;
$$;

create or replace function content_private.admin_reject_submission_impl(
  p_submission_id uuid,
  p_review_notes text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.admin_assert_staff('publisher'::content.staff_role);
  v_submission content.exhibition_submissions%rowtype;
begin
  select submission.* into v_submission
  from content.exhibition_submissions as submission
  where submission.id = p_submission_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'submission_not_found';
  end if;
  if v_submission.source <> 'owner_workspace' then
    return content_private.admin_reject_public_submission_impl(
      p_submission_id, p_review_notes
    );
  end if;
  if nullif(btrim(p_review_notes), '') is null or length(p_review_notes) > 2000 then
    raise exception using errcode = '22023', message = 'review_notes_required';
  end if;
  if v_submission.status not in (
    'submitted'::content.submission_status,
    'in_review'::content.submission_status
  ) then
    raise exception using errcode = '22023', message = 'submission_not_rejectable';
  end if;
  update content.exhibition_submissions
  set
    status = 'rejected',
    reviewed_by = v_user_id,
    review_notes = btrim(p_review_notes),
    reviewed_at = now()
  where id = p_submission_id;
  update content.exhibitions
  set
    owner_status = 'needs_changes',
    owner_review_notes = btrim(p_review_notes),
    owner_status_changed_at = now(),
    updated_by = v_user_id
  where id = v_submission.owner_exhibition_id
    and owner_status = 'submitted';
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  ) values (
    v_user_id, 'submission.rejected', 'exhibition_submission',
    p_submission_id::text,
    jsonb_build_object(
      'exhibition_id', v_submission.owner_exhibition_id,
      'source', 'owner_workspace'
    )
  );
  return content_private.admin_submission_json(p_submission_id);
end;
$$;

create or replace function content_private.sync_owner_exhibition_publication()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if new.status = 'published'::content.exhibition_version_status
     and old.status is distinct from new.status then
    update content.exhibitions
    set
      owner_status = 'published',
      owner_review_notes = null,
      owner_status_changed_at = now()
    where id = new.exhibition_id and owner_status is not null;
  end if;
  return new;
end;
$$;

drop trigger if exists exhibition_versions_sync_owner_publication
  on content.exhibition_versions;
create trigger exhibition_versions_sync_owner_publication
  after update of status on content.exhibition_versions
  for each row execute function content_private.sync_owner_exhibition_publication();

create or replace function content_private.sync_owner_exhibition_archive()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if new.owner_status is null or new.archived_at is not distinct from old.archived_at then
    return new;
  end if;
  update content.exhibitions
  set
    owner_status = case
      when new.archived_at is not null then 'archived'::content.owner_exhibition_status
      when new.published_version_id is not null then 'published'::content.owner_exhibition_status
      else 'draft'::content.owner_exhibition_status
    end,
    owner_status_changed_at = now()
  where id = new.id;
  return new;
end;
$$;

drop trigger if exists exhibitions_sync_owner_archive on content.exhibitions;
create trigger exhibitions_sync_owner_archive
  after update of archived_at on content.exhibitions
  for each row execute function content_private.sync_owner_exhibition_archive();

-- Narrow grants. Canonical tables remain command-only for browser roles.
revoke all on function content_private.owner_assert_gallery_membership(boolean)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_exhibition_json(text, uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_assert_exhibition_draft(text, uuid, integer)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_validate_exhibition_patch(jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_list_exhibitions_impl()
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_create_exhibition_draft_impl(uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_save_exhibition_draft_impl(text, uuid, integer, jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_reserve_cover_upload_impl(text, uuid, integer, text, bigint, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_complete_cover_upload_impl(text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_submit_exhibition_impl(text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_list_gallery_claims_impl(text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_decide_gallery_claim_impl(uuid, uuid, boolean, text, uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_accept_submission_impl(uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_reject_submission_impl(uuid, text)
  from public, anon, authenticated, service_role;

grant execute on function content_private.owner_list_exhibitions_impl(),
  content_private.owner_create_exhibition_draft_impl(uuid),
  content_private.owner_save_exhibition_draft_impl(text, uuid, integer, jsonb),
  content_private.owner_reserve_cover_upload_impl(text, uuid, integer, text, bigint, text),
  content_private.owner_complete_cover_upload_impl(text, uuid, integer, uuid),
  content_private.owner_submit_exhibition_impl(text, uuid, integer, uuid),
  content_private.admin_list_gallery_claims_impl(text, text),
  content_private.admin_decide_gallery_claim_impl(uuid, uuid, boolean, text, uuid)
to authenticated;

create or replace function public.owner_list_exhibitions()
returns setof jsonb language sql stable security invoker set search_path = ''
as $$ select * from content_private.owner_list_exhibitions_impl(); $$;

create or replace function public.owner_create_exhibition_draft(p_request_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.owner_create_exhibition_draft_impl(p_request_id); $$;

create or replace function public.owner_save_exhibition_draft(
  p_exhibition_id text, p_expected_version_id uuid,
  p_expected_revision integer, p_patch jsonb
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$
  select content_private.owner_save_exhibition_draft_impl(
    p_exhibition_id, p_expected_version_id, p_expected_revision, p_patch
  );
$$;

create or replace function public.owner_reserve_cover_upload(
  p_exhibition_id text, p_expected_version_id uuid,
  p_expected_revision integer, p_mime_type text,
  p_byte_size bigint, p_original_filename text
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$
  select content_private.owner_reserve_cover_upload_impl(
    p_exhibition_id, p_expected_version_id, p_expected_revision,
    p_mime_type, p_byte_size, p_original_filename
  );
$$;

create or replace function public.owner_complete_cover_upload(
  p_exhibition_id text, p_expected_version_id uuid,
  p_expected_revision integer, p_asset_id uuid
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$
  select content_private.owner_complete_cover_upload_impl(
    p_exhibition_id, p_expected_version_id, p_expected_revision, p_asset_id
  );
$$;

create or replace function public.owner_submit_exhibition(
  p_exhibition_id text, p_expected_version_id uuid,
  p_expected_revision integer, p_request_id uuid
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$
  select content_private.owner_submit_exhibition_impl(
    p_exhibition_id, p_expected_version_id, p_expected_revision, p_request_id
  );
$$;

create or replace function public.admin_list_gallery_claims(
  p_search text default '', p_status text default null
)
returns setof jsonb language sql stable security invoker set search_path = ''
as $$
  select * from content_private.admin_list_gallery_claims_impl(p_search, p_status);
$$;

create or replace function public.admin_approve_gallery_claim(
  p_gallery_id uuid, p_user_id uuid, p_request_id uuid
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$
  select content_private.admin_decide_gallery_claim_impl(
    p_gallery_id, p_user_id, true, null, p_request_id
  );
$$;

create or replace function public.admin_reject_gallery_claim(
  p_gallery_id uuid, p_user_id uuid, p_review_notes text, p_request_id uuid
)
returns jsonb language sql volatile security invoker set search_path = ''
as $$
  select content_private.admin_decide_gallery_claim_impl(
    p_gallery_id, p_user_id, false, p_review_notes, p_request_id
  );
$$;

revoke all on function public.owner_list_exhibitions(),
  public.owner_create_exhibition_draft(uuid),
  public.owner_save_exhibition_draft(text, uuid, integer, jsonb),
  public.owner_reserve_cover_upload(text, uuid, integer, text, bigint, text),
  public.owner_complete_cover_upload(text, uuid, integer, uuid),
  public.owner_submit_exhibition(text, uuid, integer, uuid),
  public.admin_list_gallery_claims(text, text),
  public.admin_approve_gallery_claim(uuid, uuid, uuid),
  public.admin_reject_gallery_claim(uuid, uuid, text, uuid)
from public, anon, authenticated, service_role;

grant execute on function public.owner_list_exhibitions(),
  public.owner_create_exhibition_draft(uuid),
  public.owner_save_exhibition_draft(text, uuid, integer, jsonb),
  public.owner_reserve_cover_upload(text, uuid, integer, text, bigint, text),
  public.owner_complete_cover_upload(text, uuid, integer, uuid),
  public.owner_submit_exhibition(text, uuid, integer, uuid),
  public.admin_list_gallery_claims(text, text),
  public.admin_approve_gallery_claim(uuid, uuid, uuid),
  public.admin_reject_gallery_claim(uuid, uuid, text, uuid)
to authenticated;
