-- gallr media command API (additive, local-first Phase 3)
--
-- Bytes remain owned by Supabase Storage. PostgreSQL owns immutable paths,
-- technical metadata, attachment presentation, revisions, audit, and outbox
-- intent. Browser clients receive no canonical table writes and no Storage
-- UPDATE/DELETE policy for this bucket.

alter table content.media_assets
  add column if not exists delivery_bucket_id text,
  add column if not exists delivery_object_path text,
  add column if not exists purged_at timestamptz;

do $migration$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'media_assets_delivery_path_pair'
      and conrelid = 'content.media_assets'::regclass
  ) then
    alter table content.media_assets
      add constraint media_assets_delivery_path_pair check (
        (delivery_bucket_id is null) = (delivery_object_path is null)
      );
  end if;
end
$migration$;

create unique index if not exists media_assets_unique_delivery_object_idx
  on content.media_assets (delivery_bucket_id, delivery_object_path)
  where delivery_bucket_id is not null
    and delivery_object_path is not null;

create index if not exists media_assets_orphan_cleanup_idx
  on content.media_assets (updated_at, id)
  where status = 'orphaned'::content.media_asset_status
    and purged_at is null;

alter table content.exhibition_version_media
  add column if not exists alt_ko text not null default '',
  add column if not exists alt_en text not null default '',
  add column if not exists credit text not null default '',
  add column if not exists rights_url text not null default '';

-- Backfill once from the old asset-scoped presentation fields. From this point
-- onward attachment fields are authoritative and media_assets is technical.
update content.exhibition_version_media as attachment
set
  alt_ko = coalesce(asset.alt_ko, ''),
  alt_en = coalesce(asset.alt_en, ''),
  credit = coalesce(asset.credit, ''),
  rights_url = coalesce(asset.rights_url, '')
from content.media_assets as asset
where asset.id = attachment.media_id;

-- Canonical order is cover=0 and galleries=1..N. Shift first so the immediate
-- UNIQUE(version_id, sort_order) constraint cannot collide during backfill.
update content.exhibition_version_media
set sort_order = sort_order + 1000000;

with normalized as (
  select
    attachment.version_id,
    attachment.media_id,
    case
      when attachment.role = 'cover'::content.media_role then 0
      else row_number() over (
        partition by attachment.version_id, attachment.role
        order by attachment.sort_order, attachment.created_at, attachment.media_id
      )::integer
    end as normalized_order
  from content.exhibition_version_media as attachment
)
update content.exhibition_version_media as attachment
set sort_order = normalized.normalized_order
from normalized
where normalized.version_id = attachment.version_id
  and normalized.media_id = attachment.media_id;

do $migration$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'exhibition_version_media_role_order'
      and conrelid = 'content.exhibition_version_media'::regclass
  ) then
    alter table content.exhibition_version_media
      add constraint exhibition_version_media_role_order check (
        (role = 'cover'::content.media_role and sort_order = 0)
        or (role = 'gallery'::content.media_role and sort_order > 0)
      );
  end if;
end
$migration$;

-- The Phase 2 draft clone inserts technical attachment columns explicitly.
-- This trigger inherits version-scoped presentation from the current published
-- attachment, preserving metadata without mutating the published row.
create or replace function content_private.inherit_attachment_presentation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_source content.exhibition_version_media%rowtype;
begin
  select source_attachment.*
  into v_source
  from content.exhibition_versions as target_version
  join content.exhibitions as exhibition
    on exhibition.id = target_version.exhibition_id
  join content.exhibition_version_media as source_attachment
    on source_attachment.version_id = exhibition.published_version_id
   and source_attachment.media_id = new.media_id
  where target_version.id = new.version_id;

  if found then
    if new.alt_ko = '' then new.alt_ko := v_source.alt_ko; end if;
    if new.alt_en = '' then new.alt_en := v_source.alt_en; end if;
    if new.credit = '' then new.credit := v_source.credit; end if;
    if new.rights_url = '' then new.rights_url := v_source.rights_url; end if;
  else
    select
      coalesce(asset.alt_ko, ''),
      coalesce(asset.alt_en, ''),
      coalesce(asset.credit, ''),
      coalesce(asset.rights_url, '')
    into new.alt_ko, new.alt_en, new.credit, new.rights_url
    from content.media_assets as asset
    where asset.id = new.media_id;
  end if;

  return new;
end;
$$;

revoke all on function content_private.inherit_attachment_presentation()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_media_inherit_presentation
  on content.exhibition_version_media;
create trigger exhibition_media_inherit_presentation
  before insert on content.exhibition_version_media
  for each row
  execute function content_private.inherit_attachment_presentation();

-- Publishing is allowed with no media, but every attached byte must already be
-- copied to its stable public destination and confirmed by the service worker.
create or replace function content_private.guard_published_version_media()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.status = 'published'::content.exhibition_version_status
     and (
       tg_op = 'INSERT'
       or old.status is distinct from new.status
     )
     and exists (
       select 1
       from content.exhibition_version_media as attachment
       join content.media_assets as asset on asset.id = attachment.media_id
       where attachment.version_id = new.id
         and (
           asset.status <> 'published'::content.media_asset_status
           or asset.public_url is null
           or length(btrim(asset.public_url)) = 0
           or asset.delivery_bucket_id is null
           or asset.delivery_object_path is null
           or asset.purged_at is not null
         )
     ) then
    raise exception using
      errcode = '23514',
      message = 'attached_media_must_be_published_before_exhibition';
  end if;

  return new;
end;
$$;

revoke all on function content_private.guard_published_version_media()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_guard_published_media
  on content.exhibition_versions;
create trigger exhibition_versions_guard_published_media
  before insert or update of status on content.exhibition_versions
  for each row
  execute function content_private.guard_published_version_media();

-- Replace the Phase 2 serializer so cover presentation comes from the exact
-- version attachment rather than shared technical asset metadata.
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

create or replace function content_private.admin_media_json(
  p_version_id uuid,
  p_asset_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'asset_id', asset.id,
    'version_id', attachment.version_id,
    'role', attachment.role::text,
    'sort_order', attachment.sort_order,
    'status', asset.status::text,
    'bucket_id', asset.bucket_id,
    'object_path', asset.object_path,
    'delivery_bucket_id', asset.delivery_bucket_id,
    'delivery_object_path', asset.delivery_object_path,
    'mime_type', coalesce(asset.mime_type, ''),
    'byte_size', asset.byte_size,
    'width', asset.width,
    'height', asset.height,
    'checksum_sha256', asset.checksum_sha256,
    'public_url', asset.public_url,
    'alt_ko', attachment.alt_ko,
    'alt_en', attachment.alt_en,
    'credit', attachment.credit,
    'rights_url', attachment.rights_url,
    'original_filename', coalesce(asset.metadata ->> 'original_filename', ''),
    'created_at', attachment.created_at,
    'updated_at', asset.updated_at
  )
  from content.exhibition_version_media as attachment
  join content.media_assets as asset on asset.id = attachment.media_id
  where attachment.version_id = p_version_id
    and attachment.media_id = p_asset_id;
$$;

revoke all on function content_private.admin_media_json(uuid, uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_media_array(p_version_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    jsonb_agg(
      content_private.admin_media_json(
        attachment.version_id,
        attachment.media_id
      )
      order by attachment.sort_order, attachment.media_id
    ),
    '[]'::jsonb
  )
  from content.exhibition_version_media as attachment
  where attachment.version_id = p_version_id;
$$;

revoke all on function content_private.admin_media_array(uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_media_bundle(
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
    'exhibition', content_private.admin_exhibition_json(
      p_exhibition_id,
      p_version_id
    ),
    'media', content_private.admin_media_array(p_version_id)
  );
$$;

revoke all on function content_private.admin_media_bundle(text, uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_upload_asset_json(p_asset_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'asset_id', asset.id,
    'bucket_id', asset.bucket_id,
    'object_path', asset.object_path,
    'delivery_bucket_id', asset.delivery_bucket_id,
    'delivery_object_path', asset.delivery_object_path,
    'mime_type', coalesce(asset.mime_type, ''),
    'byte_size', asset.byte_size,
    'width', asset.width,
    'height', asset.height,
    'checksum_sha256', asset.checksum_sha256,
    'public_url', asset.public_url,
    'original_filename', coalesce(asset.metadata ->> 'original_filename', ''),
    'status', asset.status::text
  )
  from content.media_assets as asset
  where asset.id = p_asset_id;
$$;

revoke all on function content_private.admin_upload_asset_json(uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_assert_media_draft(
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
  v_exhibition content.exhibitions%rowtype;
  v_version content.exhibition_versions%rowtype;
begin
  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'exhibition_not_found';
  end if;
  if v_exhibition.archived_at is not null then
    raise exception using errcode = '22023', message = 'archived_exhibition_is_not_editable';
  end if;

  select version.*
  into v_version
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.id = p_expected_version_id
    and version.status = 'draft'::content.exhibition_version_status
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'working_draft_not_found';
  end if;
  if p_expected_revision is null
     or v_version.revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_version.revision::text;
  end if;

  return v_version;
end;
$$;

revoke all on function content_private.admin_assert_media_draft(text, uuid, integer)
  from public, anon, authenticated, service_role;

-- Storage upload/read access is constrained to pre-registered immutable paths.
drop policy if exists "active staff upload registered exhibition media"
  on storage.objects;
create policy "active staff upload registered exhibition media"
  on storage.objects
  for insert
  to authenticated
  with check (
    bucket_id = 'exhibition-media'
    and (select content_private.has_staff_role('contributor'::content.staff_role))
    and exists (
      select 1
      from content.media_assets as asset
      join content.exhibition_versions as version
        on version.id::text = asset.metadata ->> 'draft_version_id'
      join content.exhibitions as exhibition
        on exhibition.id = version.exhibition_id
      where asset.bucket_id = storage.objects.bucket_id
        and asset.object_path = storage.objects.name
        and asset.status in (
          'pending_upload'::content.media_asset_status,
          'ready'::content.media_asset_status
        )
        and asset.uploaded_by = (select auth.uid())
        and version.status = 'draft'::content.exhibition_version_status
        and exhibition.archived_at is null
    )
  );

drop policy if exists "active staff read registered exhibition media"
  on storage.objects;
create policy "active staff read registered exhibition media"
  on storage.objects
  for select
  to authenticated
  using (
    bucket_id = 'exhibition-media'
    and (select content_private.has_staff_role('contributor'::content.staff_role))
    and exists (
      select 1
      from content.media_assets as asset
      where asset.bucket_id = storage.objects.bucket_id
        and asset.object_path = storage.objects.name
        and asset.status in (
          'pending_upload'::content.media_asset_status,
          'ready'::content.media_asset_status
        )
    )
  );

grant select, insert on storage.objects to authenticated;

create or replace function content_private.admin_list_exhibition_media_impl(
  p_exhibition_id text,
  p_version_id uuid
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  if not exists (
    select 1
    from content.exhibition_versions as version
    where version.exhibition_id = p_exhibition_id
      and version.id = p_version_id
  ) then
    raise exception using errcode = 'P0002', message = 'exhibition_version_not_found';
  end if;

  return query
  select content_private.admin_media_json(
    attachment.version_id,
    attachment.media_id
  )
  from content.exhibition_version_media as attachment
  where attachment.version_id = p_version_id
  order by attachment.sort_order, attachment.media_id;
end;
$$;

create or replace function content_private.admin_request_media_upload_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_filename text,
  p_mime_type text,
  p_byte_size bigint
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_version content.exhibition_versions%rowtype;
  v_asset_id uuid := gen_random_uuid();
  v_extension text;
  v_object_path text;
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  v_version := content_private.admin_assert_media_draft(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );

  if p_exhibition_id !~ '^[A-Za-z0-9_-]+$' then
    raise exception using errcode = '22023', message = 'unsafe_exhibition_media_path';
  end if;
  if p_filename is null
     or length(btrim(p_filename)) = 0
     or length(p_filename) > 255 then
    raise exception using errcode = '22023', message = 'invalid_original_filename';
  end if;

  v_extension := case p_mime_type
    when 'image/jpeg' then 'jpg'
    when 'image/png' then 'png'
    when 'image/webp' then 'webp'
    else null
  end;
  if v_extension is null then
    raise exception using errcode = '22023', message = 'unsupported_media_mime_type';
  end if;
  if p_byte_size is null or p_byte_size < 1 or p_byte_size > 10485760 then
    raise exception using errcode = '22023', message = 'invalid_media_byte_size';
  end if;

  v_object_path := format(
    'drafts/%s/%s/original.%s',
    p_exhibition_id,
    v_asset_id,
    v_extension
  );

  insert into content.media_assets (
    id,
    status,
    bucket_id,
    object_path,
    mime_type,
    byte_size,
    metadata,
    uploaded_by
  )
  values (
    v_asset_id,
    'pending_upload'::content.media_asset_status,
    'exhibition-media',
    v_object_path,
    p_mime_type,
    p_byte_size,
    jsonb_build_object(
      'original_filename', p_filename,
      'exhibition_id', p_exhibition_id,
      'draft_version_id', v_version.id,
      'declared_mime_type', p_mime_type,
      'declared_byte_size', p_byte_size
    ),
    v_user_id
  );

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'media.upload_requested',
    'media_asset',
    v_asset_id::text,
    jsonb_build_object(
      'exhibition_id', p_exhibition_id,
      'version_id', v_version.id,
      'bucket_id', 'exhibition-media',
      'object_path', v_object_path
    )
  );

  return content_private.admin_upload_asset_json(v_asset_id);
end;
$$;

create or replace function content_private.admin_finalize_media_upload_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_asset_id uuid,
  p_width integer default null,
  p_height integer default null,
  p_checksum_sha256 text default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_version content.exhibition_versions%rowtype;
  v_asset content.media_assets%rowtype;
  v_object_metadata jsonb;
  v_object_mime text;
  v_object_size_text text;
  v_object_size bigint;
  v_checksum text := lower(p_checksum_sha256);
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  v_version := content_private.admin_assert_media_draft(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );

  select asset.*
  into v_asset
  from content.media_assets as asset
  where asset.id = p_asset_id
    and asset.bucket_id = 'exhibition-media'
    and asset.metadata ->> 'exhibition_id' = p_exhibition_id
    and asset.metadata ->> 'draft_version_id' = v_version.id::text
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'registered_media_asset_not_found';
  end if;

  select object.metadata
  into v_object_metadata
  from storage.objects as object
  where object.bucket_id = v_asset.bucket_id
    and object.name = v_asset.object_path;

  if not found then
    raise exception using errcode = 'P0002', message = 'uploaded_storage_object_not_found';
  end if;

  v_object_mime := coalesce(
    v_object_metadata ->> 'mimetype',
    v_object_metadata ->> 'contentType',
    v_object_metadata ->> 'content-type'
  );
  v_object_size_text := coalesce(
    v_object_metadata ->> 'size',
    v_object_metadata ->> 'contentLength',
    v_object_metadata ->> 'content-length'
  );

  if v_object_mime is null or v_object_mime <> v_asset.mime_type then
    raise exception using errcode = '22023', message = 'uploaded_media_mime_mismatch';
  end if;
  if v_object_size_text is null or v_object_size_text !~ '^[0-9]+$' then
    raise exception using errcode = '22023', message = 'uploaded_media_size_metadata_invalid';
  end if;
  v_object_size := v_object_size_text::bigint;
  if v_object_size <> v_asset.byte_size then
    raise exception using errcode = '22023', message = 'uploaded_media_size_mismatch';
  end if;

  if (p_width is null) <> (p_height is null)
     or (p_width is not null and (p_width < 1 or p_height < 1)) then
    raise exception using errcode = '22023', message = 'invalid_media_dimensions';
  end if;
  if p_checksum_sha256 is not null
     and p_checksum_sha256 !~ '^[0-9A-Fa-f]{64}$' then
    raise exception using errcode = '22023', message = 'invalid_media_checksum';
  end if;

  if v_asset.status = 'pending_upload'::content.media_asset_status then
    update content.media_assets
    set
      status = 'ready'::content.media_asset_status,
      width = p_width,
      height = p_height,
      checksum_sha256 = v_checksum
    where id = p_asset_id;

    insert into content.audit_log (
      actor_user_id,
      action,
      entity_type,
      entity_id,
      metadata
    )
    values (
      v_user_id,
      'media.upload_finalized',
      'media_asset',
      p_asset_id::text,
      jsonb_build_object(
        'exhibition_id', p_exhibition_id,
        'version_id', v_version.id,
        'width', p_width,
        'height', p_height,
        'checksum_sha256', v_checksum
      )
    );
  elsif v_asset.status in (
    'ready'::content.media_asset_status,
    'published'::content.media_asset_status
  ) then
    if v_asset.width is distinct from p_width
       or v_asset.height is distinct from p_height
       or v_asset.checksum_sha256 is distinct from v_checksum then
      raise exception using errcode = '22023', message = 'media_finalize_result_mismatch';
    end if;
  else
    raise exception using errcode = '22023', message = 'media_asset_cannot_be_finalized';
  end if;

  return content_private.admin_upload_asset_json(p_asset_id);
end;
$$;

create or replace function content_private.admin_normalize_gallery_order(
  p_version_id uuid
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_offset integer;
begin
  select coalesce(max(attachment.sort_order), 0)
    + count(*)::integer
    + 1
  into v_offset
  from content.exhibition_version_media as attachment
  where attachment.version_id = p_version_id
    and attachment.role = 'gallery'::content.media_role;

  if v_offset > 1000000000 then
    raise exception using errcode = '54000', message = 'media_sort_order_capacity_exceeded';
  end if;

  update content.exhibition_version_media as attachment
  set sort_order = attachment.sort_order + v_offset
  where attachment.version_id = p_version_id
    and attachment.role = 'gallery'::content.media_role;

  with ranked as (
    select
      attachment.media_id,
      row_number() over (
        order by attachment.sort_order, attachment.created_at, attachment.media_id
      )::integer as normalized_order
    from content.exhibition_version_media as attachment
    where attachment.version_id = p_version_id
      and attachment.role = 'gallery'::content.media_role
  )
  update content.exhibition_version_media as attachment
  set sort_order = ranked.normalized_order
  from ranked
  where attachment.version_id = p_version_id
    and attachment.media_id = ranked.media_id;
end;
$$;

revoke all on function content_private.admin_normalize_gallery_order(uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_attach_exhibition_media_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_asset_id uuid,
  p_role text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_version content.exhibition_versions%rowtype;
  v_asset content.media_assets%rowtype;
  v_existing content.exhibition_version_media%rowtype;
  v_existing_cover content.exhibition_version_media%rowtype;
  v_temporary_order integer;
  v_extension text;
  v_delivery_path text;
  v_new_revision integer;
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  v_version := content_private.admin_assert_media_draft(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );

  if p_role not in ('cover', 'gallery') then
    raise exception using errcode = '22023', message = 'invalid_media_role';
  end if;

  -- Identity/version locks are held. Lock all current attachments in stable ID
  -- order before the technical asset to keep every media mutation consistent.
  perform 1
  from content.exhibition_version_media as attachment
  where attachment.version_id = v_version.id
  order by attachment.media_id
  for update;

  select asset.*
  into v_asset
  from content.media_assets as asset
  where asset.id = p_asset_id
    and asset.metadata ->> 'exhibition_id' = p_exhibition_id
    and asset.metadata ->> 'draft_version_id' = v_version.id::text
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'registered_media_asset_not_found';
  end if;
  if v_asset.status not in (
    'ready'::content.media_asset_status,
    'published'::content.media_asset_status
  ) then
    raise exception using errcode = '22023', message = 'media_asset_is_not_attachable';
  end if;

  v_extension := case v_asset.mime_type
    when 'image/jpeg' then 'jpg'
    when 'image/png' then 'png'
    when 'image/webp' then 'webp'
    else null
  end;
  if v_extension is null then
    raise exception using errcode = '22023', message = 'unsupported_media_mime_type';
  end if;
  v_delivery_path := format(
    'cms/%s/original.%s',
    p_asset_id,
    v_extension
  );

  if v_asset.delivery_bucket_id is null then
    update content.media_assets
    set
      delivery_bucket_id = 'exhibition-images',
      delivery_object_path = v_delivery_path
    where id = p_asset_id;
  elsif v_asset.delivery_bucket_id <> 'exhibition-images'
        or v_asset.delivery_object_path <> v_delivery_path then
    raise exception using errcode = '23514', message = 'media_delivery_path_is_immutable';
  end if;

  if v_asset.status = 'ready'::content.media_asset_status then
    insert into content.outbox_events (
      aggregate_type,
      aggregate_id,
      event_type,
      payload,
      deduplication_key
    )
    values (
      'media_asset',
      p_asset_id::text,
      'media.publish_requested',
      jsonb_build_object(
        'asset_id', p_asset_id,
        'source_bucket_id', v_asset.bucket_id,
        'source_object_path', v_asset.object_path,
        'delivery_bucket_id', 'exhibition-images',
        'delivery_object_path', v_delivery_path
      ),
      format('media:%s:publish_requested', p_asset_id)
    )
    on conflict (deduplication_key) do nothing;
  end if;

  select attachment.*
  into v_existing
  from content.exhibition_version_media as attachment
  where attachment.version_id = v_version.id
    and attachment.media_id = p_asset_id;

  if p_role = 'cover' then
    select attachment.*
    into v_existing_cover
    from content.exhibition_version_media as attachment
    where attachment.version_id = v_version.id
      and attachment.role = 'cover'::content.media_role
    for update;

    if found and v_existing_cover.media_id <> p_asset_id then
      select coalesce(max(attachment.sort_order), 0) + 1
      into v_temporary_order
      from content.exhibition_version_media as attachment
      where attachment.version_id = v_version.id;

      update content.exhibition_version_media
      set
        role = 'gallery'::content.media_role,
        sort_order = v_temporary_order
      where version_id = v_version.id
        and media_id = v_existing_cover.media_id;
    end if;

    if v_existing.media_id is null then
      insert into content.exhibition_version_media (
        version_id,
        media_id,
        role,
        sort_order,
        created_by
      )
      values (
        v_version.id,
        p_asset_id,
        'cover'::content.media_role,
        0,
        v_user_id
      );
    else
      update content.exhibition_version_media
      set
        role = 'cover'::content.media_role,
        sort_order = 0
      where version_id = v_version.id
        and media_id = p_asset_id;
    end if;
  else
    if v_existing.media_id is null then
      select coalesce(max(attachment.sort_order), 0) + 1
      into v_temporary_order
      from content.exhibition_version_media as attachment
      where attachment.version_id = v_version.id;

      insert into content.exhibition_version_media (
        version_id,
        media_id,
        role,
        sort_order,
        created_by
      )
      values (
        v_version.id,
        p_asset_id,
        'gallery'::content.media_role,
        greatest(v_temporary_order, 1),
        v_user_id
      );
    elsif v_existing.role = 'cover'::content.media_role then
      select coalesce(max(attachment.sort_order), 0) + 1
      into v_temporary_order
      from content.exhibition_version_media as attachment
      where attachment.version_id = v_version.id;

      update content.exhibition_version_media
      set
        role = 'gallery'::content.media_role,
        sort_order = greatest(v_temporary_order, 1)
      where version_id = v_version.id
        and media_id = p_asset_id;
    end if;
  end if;

  perform content_private.admin_normalize_gallery_order(v_version.id);

  update content.exhibition_versions as version
  set
    revision = version.revision + 1,
    updated_by = v_user_id
  where version.id = v_version.id
    and version.exhibition_id = p_exhibition_id
    and version.status = 'draft'::content.exhibition_version_status
    and version.revision = p_expected_revision
  returning version.revision into v_new_revision;

  if not found then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_version.revision::text;
  end if;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.media_attached',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'version_id', v_version.id,
      'asset_id', p_asset_id,
      'role', p_role,
      'revision', v_new_revision
    )
  );

  return content_private.admin_media_bundle(p_exhibition_id, v_version.id);
end;
$$;

create or replace function content_private.admin_update_exhibition_media_metadata_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_asset_id uuid,
  p_alt_ko text,
  p_alt_en text,
  p_credit text,
  p_rights_url text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_version content.exhibition_versions%rowtype;
  v_new_revision integer;
  v_alt_ko text := coalesce(p_alt_ko, '');
  v_alt_en text := coalesce(p_alt_en, '');
  v_credit text := coalesce(p_credit, '');
  v_rights_url text := coalesce(p_rights_url, '');
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  v_version := content_private.admin_assert_media_draft(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );

  if length(v_alt_ko) > 2000
     or length(v_alt_en) > 2000
     or length(v_credit) > 2000
     or length(v_rights_url) > 4096 then
    raise exception using errcode = '22023', message = 'media_presentation_field_too_long';
  end if;
  if v_rights_url <> ''
     and v_rights_url !~ '^https?://[^[:space:]]+$' then
    raise exception using errcode = '22023', message = 'invalid_media_rights_url';
  end if;

  perform 1
  from content.exhibition_version_media as attachment
  where attachment.version_id = v_version.id
  order by attachment.media_id
  for update;

  update content.exhibition_version_media
  set
    alt_ko = v_alt_ko,
    alt_en = v_alt_en,
    credit = v_credit,
    rights_url = v_rights_url
  where version_id = v_version.id
    and media_id = p_asset_id;

  if not found then
    raise exception using errcode = 'P0002', message = 'media_attachment_not_found';
  end if;

  update content.exhibition_versions as version
  set
    revision = version.revision + 1,
    updated_by = v_user_id
  where version.id = v_version.id
    and version.revision = p_expected_revision
    and version.status = 'draft'::content.exhibition_version_status
  returning version.revision into v_new_revision;

  if not found then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_version.revision::text;
  end if;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.media_metadata_updated',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'version_id', v_version.id,
      'asset_id', p_asset_id,
      'revision', v_new_revision
    )
  );

  return content_private.admin_media_bundle(p_exhibition_id, v_version.id);
end;
$$;

create or replace function content_private.admin_reorder_exhibition_media_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_ordered_asset_ids uuid[]
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_version content.exhibition_versions%rowtype;
  v_actual_count integer;
  v_requested_count integer;
  v_distinct_count integer;
  v_offset integer;
  v_new_revision integer;
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  v_version := content_private.admin_assert_media_draft(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );

  if p_ordered_asset_ids is null
     or array_position(p_ordered_asset_ids, null) is not null then
    raise exception using errcode = '22023', message = 'invalid_gallery_order';
  end if;

  perform 1
  from content.exhibition_version_media as attachment
  where attachment.version_id = v_version.id
  order by attachment.media_id
  for update;

  select count(*)::integer
  into v_actual_count
  from content.exhibition_version_media as attachment
  where attachment.version_id = v_version.id
    and attachment.role = 'gallery'::content.media_role;

  v_requested_count := cardinality(p_ordered_asset_ids);
  select count(distinct requested.asset_id)::integer
  into v_distinct_count
  from unnest(p_ordered_asset_ids) as requested(asset_id);

  if v_requested_count <> v_actual_count
     or v_distinct_count <> v_requested_count
     or exists (
       select 1
       from unnest(p_ordered_asset_ids) as requested(asset_id)
       left join content.exhibition_version_media as attachment
         on attachment.version_id = v_version.id
        and attachment.media_id = requested.asset_id
        and attachment.role = 'gallery'::content.media_role
       where attachment.media_id is null
     ) then
    raise exception using errcode = '22023', message = 'gallery_order_must_match_exact_set';
  end if;

  select coalesce(max(attachment.sort_order), 0)
    + v_actual_count
    + 1
  into v_offset
  from content.exhibition_version_media as attachment
  where attachment.version_id = v_version.id
    and attachment.role = 'gallery'::content.media_role;

  if v_offset > 1000000000 then
    raise exception using errcode = '54000', message = 'media_sort_order_capacity_exceeded';
  end if;

  update content.exhibition_version_media as attachment
  set sort_order = attachment.sort_order + v_offset
  where attachment.version_id = v_version.id
    and attachment.role = 'gallery'::content.media_role;

  update content.exhibition_version_media as attachment
  set sort_order = requested.ordinality::integer
  from unnest(p_ordered_asset_ids) with ordinality
    as requested(asset_id, ordinality)
  where attachment.version_id = v_version.id
    and attachment.media_id = requested.asset_id
    and attachment.role = 'gallery'::content.media_role;

  update content.exhibition_versions as version
  set
    revision = version.revision + 1,
    updated_by = v_user_id
  where version.id = v_version.id
    and version.revision = p_expected_revision
    and version.status = 'draft'::content.exhibition_version_status
  returning version.revision into v_new_revision;

  if not found then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_version.revision::text;
  end if;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.media_reordered',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'version_id', v_version.id,
      'ordered_gallery_asset_ids', to_jsonb(p_ordered_asset_ids),
      'revision', v_new_revision
    )
  );

  return content_private.admin_media_bundle(p_exhibition_id, v_version.id);
end;
$$;

create or replace function content_private.admin_detach_exhibition_media_impl(
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
  v_user_id uuid;
  v_version content.exhibition_versions%rowtype;
  v_asset content.media_assets%rowtype;
  v_attachment content.exhibition_version_media%rowtype;
  v_new_revision integer;
  v_became_orphaned boolean := false;
begin
  v_user_id := content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  v_version := content_private.admin_assert_media_draft(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision
  );

  perform 1
  from content.exhibition_version_media as attachment
  where attachment.version_id = v_version.id
  order by attachment.media_id
  for update;

  select attachment.*
  into v_attachment
  from content.exhibition_version_media as attachment
  where attachment.version_id = v_version.id
    and attachment.media_id = p_asset_id;

  if not found then
    raise exception using errcode = 'P0002', message = 'media_attachment_not_found';
  end if;

  select asset.*
  into strict v_asset
  from content.media_assets as asset
  where asset.id = p_asset_id
  for update;

  delete from content.exhibition_version_media
  where version_id = v_version.id
    and media_id = p_asset_id;

  perform content_private.admin_normalize_gallery_order(v_version.id);

  if v_asset.status in (
       'ready'::content.media_asset_status,
       'published'::content.media_asset_status,
       'rejected'::content.media_asset_status
     )
     and not exists (
       select 1
       from content.exhibition_version_media as attachment
       where attachment.media_id = p_asset_id
     )
     and not exists (
       select 1
       from content.submission_media as submission_attachment
       where submission_attachment.media_id = p_asset_id
     ) then
    update content.media_assets
    set status = 'orphaned'::content.media_asset_status
    where id = p_asset_id;
    v_became_orphaned := true;

    insert into content.outbox_events (
      aggregate_type,
      aggregate_id,
      event_type,
      payload,
      deduplication_key
    )
    values (
      'media_asset',
      p_asset_id::text,
      'media.cleanup_requested',
      jsonb_strip_nulls(jsonb_build_object(
        'asset_id', p_asset_id,
        'source_bucket_id', v_asset.bucket_id,
        'source_object_path', v_asset.object_path,
        'delivery_bucket_id', v_asset.delivery_bucket_id,
        'delivery_object_path', v_asset.delivery_object_path
      )),
      format('media:%s:cleanup_requested', p_asset_id)
    )
    on conflict (deduplication_key) do nothing;
  end if;

  update content.exhibition_versions as version
  set
    revision = version.revision + 1,
    updated_by = v_user_id
  where version.id = v_version.id
    and version.revision = p_expected_revision
    and version.status = 'draft'::content.exhibition_version_status
  returning version.revision into v_new_revision;

  if not found then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_version.revision::text;
  end if;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'exhibition.media_detached',
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'version_id', v_version.id,
      'asset_id', p_asset_id,
      'previous_role', v_attachment.role::text,
      'orphaned', v_became_orphaned,
      'revision', v_new_revision
    )
  );

  return content_private.admin_media_bundle(p_exhibition_id, v_version.id);
end;
$$;

-- Only these implementations are callable by authenticated so their invoker
-- wrappers can enter the private boundary. Each implementation re-checks the
-- server JWT subject and active contributor role before any work.
revoke all on function content_private.admin_list_exhibition_media_impl(text, uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_request_media_upload_impl(text, uuid, integer, text, text, bigint)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_finalize_media_upload_impl(text, uuid, integer, uuid, integer, integer, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_attach_exhibition_media_impl(text, uuid, integer, uuid, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_update_exhibition_media_metadata_impl(text, uuid, integer, uuid, text, text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_reorder_exhibition_media_impl(text, uuid, integer, uuid[])
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_detach_exhibition_media_impl(text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;

grant execute on function content_private.admin_list_exhibition_media_impl(text, uuid)
  to authenticated;
grant execute on function content_private.admin_request_media_upload_impl(text, uuid, integer, text, text, bigint)
  to authenticated;
grant execute on function content_private.admin_finalize_media_upload_impl(text, uuid, integer, uuid, integer, integer, text)
  to authenticated;
grant execute on function content_private.admin_attach_exhibition_media_impl(text, uuid, integer, uuid, text)
  to authenticated;
grant execute on function content_private.admin_update_exhibition_media_metadata_impl(text, uuid, integer, uuid, text, text, text, text)
  to authenticated;
grant execute on function content_private.admin_reorder_exhibition_media_impl(text, uuid, integer, uuid[])
  to authenticated;
grant execute on function content_private.admin_detach_exhibition_media_impl(text, uuid, integer, uuid)
  to authenticated;

create or replace function public.admin_list_exhibition_media(
  p_exhibition_id text,
  p_version_id uuid
)
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select *
  from content_private.admin_list_exhibition_media_impl(
    p_exhibition_id,
    p_version_id
  );
$$;

create or replace function public.admin_request_media_upload(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_filename text,
  p_mime_type text,
  p_byte_size bigint
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_request_media_upload_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_filename,
    p_mime_type,
    p_byte_size
  );
$$;

create or replace function public.admin_finalize_media_upload(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_asset_id uuid,
  p_width integer default null,
  p_height integer default null,
  p_checksum_sha256 text default null
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_finalize_media_upload_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_asset_id,
    p_width,
    p_height,
    p_checksum_sha256
  );
$$;

create or replace function public.admin_attach_exhibition_media(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_asset_id uuid,
  p_role text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_attach_exhibition_media_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_asset_id,
    p_role
  );
$$;

create or replace function public.admin_update_exhibition_media_metadata(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_asset_id uuid,
  p_alt_ko text,
  p_alt_en text,
  p_credit text,
  p_rights_url text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_update_exhibition_media_metadata_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_asset_id,
    p_alt_ko,
    p_alt_en,
    p_credit,
    p_rights_url
  );
$$;

create or replace function public.admin_reorder_exhibition_media(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_ordered_asset_ids uuid[]
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_reorder_exhibition_media_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_ordered_asset_ids
  );
$$;

create or replace function public.admin_detach_exhibition_media(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_asset_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_detach_exhibition_media_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_asset_id
  );
$$;

revoke all on function public.admin_list_exhibition_media(text, uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_request_media_upload(text, uuid, integer, text, text, bigint)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_finalize_media_upload(text, uuid, integer, uuid, integer, integer, text)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_attach_exhibition_media(text, uuid, integer, uuid, text)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_update_exhibition_media_metadata(text, uuid, integer, uuid, text, text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_reorder_exhibition_media(text, uuid, integer, uuid[])
  from public, anon, authenticated, service_role;
revoke all on function public.admin_detach_exhibition_media(text, uuid, integer, uuid)
  from public, anon, authenticated, service_role;

grant execute on function public.admin_list_exhibition_media(text, uuid)
  to authenticated;
grant execute on function public.admin_request_media_upload(text, uuid, integer, text, text, bigint)
  to authenticated;
grant execute on function public.admin_finalize_media_upload(text, uuid, integer, uuid, integer, integer, text)
  to authenticated;
grant execute on function public.admin_attach_exhibition_media(text, uuid, integer, uuid, text)
  to authenticated;
grant execute on function public.admin_update_exhibition_media_metadata(text, uuid, integer, uuid, text, text, text, text)
  to authenticated;
grant execute on function public.admin_reorder_exhibition_media(text, uuid, integer, uuid[])
  to authenticated;
grant execute on function public.admin_detach_exhibition_media(text, uuid, integer, uuid)
  to authenticated;
