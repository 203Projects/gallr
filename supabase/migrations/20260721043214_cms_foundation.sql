-- gallr CMS foundation (additive only)
--
-- This migration deliberately does not modify, rename, or backfill
-- public.exhibitions. It introduces the private canonical content model and
-- closes the legacy profiles.is_admin privilege-escalation path while keeping
-- that column available as a read-compatible mirror for shipped clients.

create schema if not exists content;
create schema if not exists content_private;

comment on schema content is
  'Canonical gallr editorial data. Keep this schema out of the Data API exposed-schema list.';
comment on schema content_private is
  'Non-exposed authorization and trigger helpers. Never add this schema to the Data API.';

revoke all on schema content from public, anon, authenticated;
revoke all on schema content_private from public, anon, authenticated;

-- PostgreSQL grants EXECUTE on new functions to PUBLIC through a global default
-- ACL. A per-schema REVOKE cannot override that global default, so revoke it for
-- every future function created by the migration owner and grant deliberately.
alter default privileges revoke execute on functions from public;

create type content.staff_role as enum (
  'contributor',
  'publisher',
  'admin'
);

create type content.exhibition_version_status as enum (
  'draft',
  'published',
  'superseded'
);

create type content.media_asset_status as enum (
  'pending_upload',
  'ready',
  'published',
  'orphaned',
  'rejected'
);

create type content.media_role as enum (
  'cover',
  'gallery'
);

create type content.curation_surface as enum (
  'app_featured',
  'homepage'
);

create type content.outbox_status as enum (
  'pending',
  'processing',
  'delivered',
  'failed'
);

create type content.submission_status as enum (
  'pending_upload',
  'submitted',
  'in_review',
  'accepted',
  'rejected',
  'withdrawn'
);

-- New editorial uploads use a private staging/published bucket. Browser uploads
-- will receive short-lived, object-scoped signed targets from a later command
-- API; authenticated users receive no blanket storage.objects write policy.
insert into storage.buckets (
  id,
  name,
  public,
  file_size_limit,
  allowed_mime_types
)
values (
  'exhibition-media',
  'exhibition-media',
  false,
  10485760,
  array['image/jpeg', 'image/png', 'image/webp']::text[]
)
on conflict (id) do update
set
  name = excluded.name,
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

-- Authorization source for editorial and moderation access. This table is
-- intentionally separate from public profiles and cannot be written by an
-- authenticated client.
create table content.staff_members (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role content.staff_role not null default 'contributor',
  active boolean not null default true,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null
);

comment on table content.staff_members is
  'Authoritative staff authorization. public.profiles.is_admin is only a derived compatibility mirror.';

alter table content.staff_members enable row level security;

create or replace function content_private.has_staff_role(
  required_role content.staff_role
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    exists (
      select 1
      from content.staff_members as staff
      where staff.user_id = (select auth.uid())
        and staff.active
        and case required_role
          when 'contributor'::content.staff_role then
            staff.role in (
              'contributor'::content.staff_role,
              'publisher'::content.staff_role,
              'admin'::content.staff_role
            )
          when 'publisher'::content.staff_role then
            staff.role in (
              'publisher'::content.staff_role,
              'admin'::content.staff_role
            )
          when 'admin'::content.staff_role then
            staff.role = 'admin'::content.staff_role
        end
    ),
    false
  );
$$;

revoke all on function content_private.has_staff_role(content.staff_role)
  from public, anon, authenticated;

-- Do not bootstrap staff from profiles.is_admin: that field was historically
-- owner-writable and cannot be trusted as an authorization source. Production
-- staff must be seeded from an independently verified UUID allowlist.

-- Keep profiles.is_admin for existing DTOs, but always derive its value from
-- staff_members. A profile owner can no longer promote themselves even if an
-- older client includes is_admin in an upsert payload.
create or replace function content_private.enforce_profile_admin_mirror()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.is_admin := exists (
    select 1
    from content.staff_members as staff
    where staff.user_id = new.id
      and staff.active
      and staff.role = 'admin'::content.staff_role
  );

  return new;
end;
$$;

revoke all on function content_private.enforce_profile_admin_mirror()
  from public, anon, authenticated;

drop trigger if exists profiles_enforce_admin_mirror on public.profiles;
create trigger profiles_enforce_admin_mirror
  before insert or update on public.profiles
  for each row
  execute function content_private.enforce_profile_admin_mirror();

create or replace function content_private.sync_profile_admin_mirror()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  affected_user_id uuid;
begin
  affected_user_id := case
    when tg_op = 'DELETE' then old.user_id
    else new.user_id
  end;

  update public.profiles
  set
    is_admin = exists (
      select 1
      from content.staff_members as staff
      where staff.user_id = affected_user_id
        and staff.active
        and staff.role = 'admin'::content.staff_role
    ),
    updated_at = now()
  where id = affected_user_id;

  if tg_op = 'DELETE' then
    return old;
  end if;

  return new;
end;
$$;

revoke all on function content_private.sync_profile_admin_mirror()
  from public, anon, authenticated;

drop trigger if exists staff_members_sync_profile_admin on content.staff_members;
create trigger staff_members_sync_profile_admin
  after insert or update or delete on content.staff_members
  for each row
  execute function content_private.sync_profile_admin_mirror();

update public.profiles as profiles
set is_admin = exists (
  select 1
  from content.staff_members as staff
  where staff.user_id = profiles.id
    and staff.active
    and staff.role = 'admin'::content.staff_role
);

alter table public.profiles
  alter column is_admin set default false;
update public.profiles set is_admin = false where is_admin is null;
alter table public.profiles
  alter column is_admin set not null;

comment on column public.profiles.is_admin is
  'Read-compatible mirror of active content.staff_members admin membership. Never use as an authorization source.';

drop policy if exists "Owner insert" on public.profiles;
create policy "Owner insert"
  on public.profiles
  for insert
  to authenticated
  with check ((select auth.uid()) = id);

drop policy if exists "Owner write" on public.profiles;
create policy "Owner write"
  on public.profiles
  for update
  to authenticated
  using ((select auth.uid()) = id)
  with check ((select auth.uid()) = id);

-- Harden the existing signup trigger function as part of the same boundary.
-- User metadata remains acceptable for display fields, but never for roles.
create or replace function content_private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, display_name, avatar_url)
  values (
    new.id,
    coalesce(new.raw_user_meta_data ->> 'full_name', ''),
    coalesce(new.raw_user_meta_data ->> 'avatar_url', '')
  )
  on conflict (id) do nothing;

  return new;
end;
$$;

revoke all on function content_private.handle_new_user()
  from public, anon, authenticated;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row
  execute function content_private.handle_new_user();

drop function if exists public.handle_new_user();

-- Canonicalize the legacy bookmark policies with explicit roles, init-plan
-- friendly auth lookups, and a WITH CHECK guard for updates.
drop policy if exists "Owner read" on public.bookmarks;
create policy "Owner read"
  on public.bookmarks
  for select
  to authenticated
  using ((select auth.uid()) = user_id);

drop policy if exists "Owner write" on public.bookmarks;
create policy "Owner write"
  on public.bookmarks
  for insert
  to authenticated
  with check ((select auth.uid()) = user_id);

drop policy if exists "Owner update" on public.bookmarks;
create policy "Owner update"
  on public.bookmarks
  for update
  to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "Owner delete" on public.bookmarks;
create policy "Owner delete"
  on public.bookmarks
  for delete
  to authenticated
  using ((select auth.uid()) = user_id);

-- Prevent thought authors from self-approving or changing ownership while
-- retaining the existing client flow that edits only the content column.
create or replace function content_private.enforce_thought_moderation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := (select auth.uid());
  caller_is_admin boolean := false;
begin
  if caller_id is not null then
    select exists (
      select 1
      from content.staff_members as staff
      where staff.user_id = caller_id
        and staff.active
        and staff.role = 'admin'::content.staff_role
    ) into caller_is_admin;
  end if;

  if tg_op = 'INSERT' and caller_id is not null and not caller_is_admin then
    new.is_approved := false;
  elsif tg_op = 'UPDATE' and caller_id is not null and not caller_is_admin then
    new.user_id := old.user_id;
    new.exhibition_id := old.exhibition_id;
    new.is_approved := old.is_approved;
  end if;

  return new;
end;
$$;

revoke all on function content_private.enforce_thought_moderation()
  from public, anon, authenticated;

drop trigger if exists thoughts_enforce_moderation on public.thoughts;
create trigger thoughts_enforce_moderation
  before insert or update on public.thoughts
  for each row
  execute function content_private.enforce_thought_moderation();

drop policy if exists "Approved public read" on public.thoughts;
drop policy if exists "Admin read all" on public.thoughts;
create policy "approved thoughts are publicly readable"
  on public.thoughts
  for select
  to anon
  using (is_approved = true);

create policy "authenticated users can read permitted thoughts"
  on public.thoughts
  for select
  to authenticated
  using (
    is_approved = true
    or (select auth.uid()) = user_id
    or (select content_private.has_staff_role('admin'::content.staff_role))
  );

drop policy if exists "Owner write" on public.thoughts;
create policy "owners can create thoughts"
  on public.thoughts
  for insert
  to authenticated
  with check ((select auth.uid()) = user_id);

drop policy if exists "Owner update" on public.thoughts;
drop policy if exists "Admin update" on public.thoughts;
create policy "owners or admins can update thoughts"
  on public.thoughts
  for update
  to authenticated
  using (
    (select auth.uid()) = user_id
    or (select content_private.has_staff_role('admin'::content.staff_role))
  )
  with check (
    (select auth.uid()) = user_id
    or (select content_private.has_staff_role('admin'::content.staff_role))
  );

drop policy if exists "Owner delete" on public.thoughts;
drop policy if exists "Admin delete" on public.thoughts;
create policy "owners or admins can delete thoughts"
  on public.thoughts
  for delete
  to authenticated
  using (
    (select auth.uid()) = user_id
    or (select content_private.has_staff_role('admin'::content.staff_role))
  );

-- Remove policy names that may exist from historical dashboard edits.
drop policy if exists "Admin read all thoughts" on public.thoughts;
drop policy if exists "Admin moderate thoughts" on public.thoughts;
drop policy if exists "Admin delete thoughts" on public.thoughts;

-- Reusable venue defaults. Published exhibition versions store snapshots so
-- later venue edits do not rewrite historical public content.
create table content.venues (
  id uuid primary key default gen_random_uuid(),
  slug text unique,
  name_ko text not null,
  name_en text not null default '',
  address_ko text not null default '',
  address_en text not null default '',
  city_ko text not null default '',
  city_en text not null default '',
  region_ko text not null default '',
  region_en text not null default '',
  latitude double precision,
  longitude double precision,
  default_hours text,
  default_contact text,
  archived_at timestamptz,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint venues_slug_format check (
    slug is null
    or slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
  ),
  constraint venues_name_ko_not_blank check (length(btrim(name_ko)) > 0),
  constraint venues_latitude_range check (
    latitude is null or latitude between -90 and 90
  ),
  constraint venues_longitude_range check (
    longitude is null or longitude between -180 and 180
  ),
  constraint venues_coordinate_pair check (
    (latitude is null) = (longitude is null)
  )
);

create table content.exhibitions (
  id text primary key,
  published_version_id uuid,
  archived_at timestamptz,
  archived_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint exhibitions_id_format check (
    id = btrim(id)
    and length(id) between 1 and 128
  )
);

create table content.exhibition_versions (
  id uuid primary key default gen_random_uuid(),
  exhibition_id text not null
    references content.exhibitions(id) on delete restrict,
  version_number integer not null,
  revision integer not null default 1,
  status content.exhibition_version_status not null default 'draft',
  venue_id uuid references content.venues(id) on delete set null,
  event_id text references public.events(id) on delete set null,
  editor_id text references public.editors(id) on delete set null,
  name_ko text not null default '',
  name_en text not null default '',
  venue_name_ko text not null default '',
  venue_name_en text not null default '',
  city_ko text not null default '',
  city_en text not null default '',
  region_ko text not null default '',
  region_en text not null default '',
  address_ko text not null default '',
  address_en text not null default '',
  opening_date date,
  closing_date date,
  latitude double precision,
  longitude double precision,
  description_ko text not null default '',
  description_en text not null default '',
  hours text,
  contact text,
  reception_date timestamptz,
  opening_time text,
  ticket_url text,
  legacy_cover_image_url text,
  published_at timestamptz,
  published_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint exhibition_versions_number_positive check (version_number > 0),
  constraint exhibition_versions_revision_positive check (revision > 0),
  constraint exhibition_versions_date_order check (
    opening_date is null
    or closing_date is null
    or closing_date >= opening_date
  ),
  constraint exhibition_versions_latitude_range check (
    latitude is null or latitude between -90 and 90
  ),
  constraint exhibition_versions_longitude_range check (
    longitude is null or longitude between -180 and 180
  ),
  constraint exhibition_versions_coordinate_pair check (
    (latitude is null) = (longitude is null)
  ),
  constraint exhibition_versions_published_metadata check (
    status = 'draft'::content.exhibition_version_status
    or published_at is not null
  ),
  unique (exhibition_id, version_number),
  unique (exhibition_id, id)
);

alter table content.exhibitions
  add constraint exhibitions_published_version_fk
  foreign key (id, published_version_id)
  references content.exhibition_versions(exhibition_id, id)
  deferrable initially deferred;

create table content.media_assets (
  id uuid primary key default gen_random_uuid(),
  status content.media_asset_status not null default 'pending_upload',
  bucket_id text not null,
  object_path text not null,
  public_url text,
  mime_type text,
  byte_size bigint,
  width integer,
  height integer,
  checksum_sha256 text,
  alt_ko text not null default '',
  alt_en text not null default '',
  credit text,
  rights_url text,
  metadata jsonb not null default '{}'::jsonb,
  uploaded_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  published_at timestamptz,
  constraint media_assets_bucket_not_blank check (length(btrim(bucket_id)) > 0),
  constraint media_assets_path_not_blank check (length(btrim(object_path)) > 0),
  constraint media_assets_unique_object unique (bucket_id, object_path),
  constraint media_assets_byte_size_positive check (
    byte_size is null or byte_size > 0
  ),
  constraint media_assets_dimensions_positive check (
    (width is null and height is null)
    or (
      width is not null
      and height is not null
      and width > 0
      and height > 0
    )
  ),
  constraint media_assets_checksum_format check (
    checksum_sha256 is null or checksum_sha256 ~ '^[0-9a-f]{64}$'
  ),
  constraint media_assets_metadata_object check (
    jsonb_typeof(metadata) = 'object'
  ),
  constraint media_assets_published_fields check (
    status <> 'published'::content.media_asset_status
    or (public_url is not null and published_at is not null)
  )
);

create table content.exhibition_version_media (
  version_id uuid not null
    references content.exhibition_versions(id) on delete cascade,
  media_id uuid not null
    references content.media_assets(id) on delete restrict,
  role content.media_role not null default 'gallery',
  sort_order integer not null default 0,
  focal_x numeric(5,4),
  focal_y numeric(5,4),
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  primary key (version_id, media_id),
  constraint exhibition_version_media_sort_nonnegative check (sort_order >= 0),
  constraint exhibition_version_media_focal_x check (
    focal_x is null or focal_x between 0 and 1
  ),
  constraint exhibition_version_media_focal_y check (
    focal_y is null or focal_y between 0 and 1
  ),
  unique (version_id, sort_order)
);

create unique index exhibition_version_media_one_cover_idx
  on content.exhibition_version_media (version_id)
  where role = 'cover'::content.media_role;

create table content.curation_placements (
  id uuid primary key default gen_random_uuid(),
  surface content.curation_surface not null,
  exhibition_id text not null
    references content.exhibitions(id) on delete restrict,
  position integer not null default 0,
  enabled boolean not null default true,
  starts_at timestamptz,
  ends_at timestamptz,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint curation_placements_position_nonnegative check (position >= 0),
  constraint curation_placements_window_order check (
    starts_at is null or ends_at is null or ends_at > starts_at
  ),
  unique (surface, exhibition_id)
);

create table content.audit_log (
  id uuid primary key default gen_random_uuid(),
  actor_user_id uuid references auth.users(id) on delete set null,
  action text not null,
  entity_type text not null,
  entity_id text not null,
  request_id uuid,
  metadata jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null default now(),
  constraint audit_log_action_not_blank check (length(btrim(action)) > 0),
  constraint audit_log_entity_type_not_blank check (length(btrim(entity_type)) > 0),
  constraint audit_log_entity_id_not_blank check (length(btrim(entity_id)) > 0),
  constraint audit_log_metadata_object check (jsonb_typeof(metadata) = 'object')
);

create table content.outbox_events (
  id uuid primary key default gen_random_uuid(),
  aggregate_type text not null,
  aggregate_id text not null,
  event_type text not null,
  payload jsonb not null default '{}'::jsonb,
  status content.outbox_status not null default 'pending',
  deduplication_key text unique,
  attempts integer not null default 0,
  available_at timestamptz not null default now(),
  locked_at timestamptz,
  delivered_at timestamptz,
  last_error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint outbox_events_aggregate_type_not_blank check (
    length(btrim(aggregate_type)) > 0
  ),
  constraint outbox_events_aggregate_id_not_blank check (
    length(btrim(aggregate_id)) > 0
  ),
  constraint outbox_events_event_type_not_blank check (
    length(btrim(event_type)) > 0
  ),
  constraint outbox_events_attempts_nonnegative check (attempts >= 0),
  constraint outbox_events_payload_object check (jsonb_typeof(payload) = 'object'),
  constraint outbox_events_delivery_timestamp check (
    status <> 'delivered'::content.outbox_status or delivered_at is not null
  )
);

create table content.exhibition_submissions (
  id uuid primary key default gen_random_uuid(),
  status content.submission_status not null default 'pending_upload',
  submitter_name text,
  submitter_email text,
  submitter_phone text,
  payload jsonb not null,
  submission_token_hash text,
  source_ip_hash text,
  user_agent text,
  accepted_exhibition_id text
    references content.exhibitions(id) on delete set null,
  reviewed_by uuid references auth.users(id) on delete set null,
  review_notes text,
  submitted_at timestamptz,
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint exhibition_submissions_payload_object check (
    jsonb_typeof(payload) = 'object'
  ),
  constraint exhibition_submissions_submitted_at check (
    status = 'pending_upload'::content.submission_status
    or submitted_at is not null
  ),
  constraint exhibition_submissions_review_metadata check (
    status not in (
      'accepted'::content.submission_status,
      'rejected'::content.submission_status
    )
    or reviewed_at is not null
  )
);

create table content.submission_media (
  submission_id uuid not null
    references content.exhibition_submissions(id) on delete cascade,
  media_id uuid not null
    references content.media_assets(id) on delete restrict,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  primary key (submission_id, media_id),
  constraint submission_media_sort_nonnegative check (sort_order >= 0),
  unique (submission_id, sort_order)
);

-- Query and relationship indexes. Primary keys and UNIQUE constraints already
-- create their own indexes and are not duplicated here.
create index staff_members_active_role_idx
  on content.staff_members (active, role)
  where active;

create index venues_active_name_idx
  on content.venues (name_ko)
  where archived_at is null;

create index exhibitions_published_version_idx
  on content.exhibitions (published_version_id)
  where published_version_id is not null;

create index exhibitions_active_updated_idx
  on content.exhibitions (updated_at desc)
  where archived_at is null;

create index exhibition_versions_exhibition_status_idx
  on content.exhibition_versions (exhibition_id, status, version_number desc);

create index exhibition_versions_venue_idx
  on content.exhibition_versions (venue_id)
  where venue_id is not null;

create index exhibition_versions_event_idx
  on content.exhibition_versions (event_id)
  where event_id is not null;

create index exhibition_versions_editor_idx
  on content.exhibition_versions (editor_id)
  where editor_id is not null;

create index media_assets_status_created_idx
  on content.media_assets (status, created_at desc);

create index exhibition_version_media_media_idx
  on content.exhibition_version_media (media_id);

create index curation_placements_surface_order_idx
  on content.curation_placements (surface, enabled, position, starts_at, ends_at);

create index audit_log_entity_idx
  on content.audit_log (entity_type, entity_id, occurred_at desc);

create index audit_log_actor_idx
  on content.audit_log (actor_user_id, occurred_at desc)
  where actor_user_id is not null;

create index outbox_events_pending_idx
  on content.outbox_events (available_at, created_at)
  where status in (
    'pending'::content.outbox_status,
    'failed'::content.outbox_status
  );

create index exhibition_submissions_review_queue_idx
  on content.exhibition_submissions (status, submitted_at, created_at);

create index exhibition_submissions_accepted_exhibition_idx
  on content.exhibition_submissions (accepted_exhibition_id)
  where accepted_exhibition_id is not null;

create index submission_media_media_idx
  on content.submission_media (media_id);

-- Keep mutable-row timestamps consistent without trusting clients to send
-- updated_at. This trigger function is security-invoker and writes only NEW.
create or replace function content_private.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

revoke all on function content_private.set_updated_at()
  from public, anon, authenticated;

create trigger staff_members_set_updated_at
  before update on content.staff_members
  for each row execute function content_private.set_updated_at();
create trigger venues_set_updated_at
  before update on content.venues
  for each row execute function content_private.set_updated_at();
create trigger exhibitions_set_updated_at
  before update on content.exhibitions
  for each row execute function content_private.set_updated_at();
create trigger exhibition_versions_set_updated_at
  before update on content.exhibition_versions
  for each row execute function content_private.set_updated_at();
create trigger media_assets_set_updated_at
  before update on content.media_assets
  for each row execute function content_private.set_updated_at();
create trigger curation_placements_set_updated_at
  before update on content.curation_placements
  for each row execute function content_private.set_updated_at();
create trigger outbox_events_set_updated_at
  before update on content.outbox_events
  for each row execute function content_private.set_updated_at();
create trigger exhibition_submissions_set_updated_at
  before update on content.exhibition_submissions
  for each row execute function content_private.set_updated_at();

alter table content.venues enable row level security;
alter table content.exhibitions enable row level security;
alter table content.exhibition_versions enable row level security;
alter table content.media_assets enable row level security;
alter table content.exhibition_version_media enable row level security;
alter table content.curation_placements enable row level security;
alter table content.audit_log enable row level security;
alter table content.outbox_events enable row level security;
alter table content.exhibition_submissions enable row level security;
alter table content.submission_media enable row level security;

-- Staff can see their own membership; only an admin can enumerate the team.
create policy "staff can read permitted memberships"
  on content.staff_members
  for select
  to authenticated
  using (
    (select auth.uid()) = user_id
    or (select content_private.has_staff_role('admin'::content.staff_role))
  );

create policy "staff can read venues"
  on content.venues
  for select
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
  );

create policy "staff can create venues"
  on content.venues
  for insert
  to authenticated
  with check (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and created_by = (select auth.uid())
  );

create policy "staff can update venues"
  on content.venues
  for update
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
  )
  with check (
    (select content_private.has_staff_role('contributor'::content.staff_role))
  );

create policy "staff can read exhibition identities"
  on content.exhibitions
  for select
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
  );

create policy "staff can create unpublished exhibition identities"
  on content.exhibitions
  for insert
  to authenticated
  with check (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and created_by = (select auth.uid())
    and published_version_id is null
    and archived_at is null
  );

create policy "staff can read exhibition versions"
  on content.exhibition_versions
  for select
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
  );

create policy "staff can create draft versions"
  on content.exhibition_versions
  for insert
  to authenticated
  with check (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and status = 'draft'::content.exhibition_version_status
    and created_by = (select auth.uid())
  );

create policy "staff can update draft versions"
  on content.exhibition_versions
  for update
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and status = 'draft'::content.exhibition_version_status
  )
  with check (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and status = 'draft'::content.exhibition_version_status
  );

create policy "staff can read media assets"
  on content.media_assets
  for select
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
  );

create policy "staff can register pending uploads"
  on content.media_assets
  for insert
  to authenticated
  with check (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and status = 'pending_upload'::content.media_asset_status
    and uploaded_by = (select auth.uid())
    and public_url is null
  );

create policy "staff can read version media"
  on content.exhibition_version_media
  for select
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
  );

create policy "staff can attach media to drafts"
  on content.exhibition_version_media
  for insert
  to authenticated
  with check (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and created_by = (select auth.uid())
    and exists (
      select 1
      from content.exhibition_versions as version
      where version.id = version_id
        and version.status = 'draft'::content.exhibition_version_status
    )
    and exists (
      select 1
      from content.media_assets as asset
      where asset.id = media_id
        and asset.status in (
          'ready'::content.media_asset_status,
          'published'::content.media_asset_status
        )
    )
  );

create policy "staff can reorder draft media"
  on content.exhibition_version_media
  for update
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and exists (
      select 1
      from content.exhibition_versions as version
      where version.id = version_id
        and version.status = 'draft'::content.exhibition_version_status
    )
  )
  with check (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and exists (
      select 1
      from content.exhibition_versions as version
      where version.id = version_id
        and version.status = 'draft'::content.exhibition_version_status
    )
  );

create policy "staff can detach media from drafts"
  on content.exhibition_version_media
  for delete
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
    and exists (
      select 1
      from content.exhibition_versions as version
      where version.id = version_id
        and version.status = 'draft'::content.exhibition_version_status
    )
  );

create policy "staff can read curation"
  on content.curation_placements
  for select
  to authenticated
  using (
    (select content_private.has_staff_role('contributor'::content.staff_role))
  );

create policy "publishers can create curation"
  on content.curation_placements
  for insert
  to authenticated
  with check (
    (select content_private.has_staff_role('publisher'::content.staff_role))
    and created_by = (select auth.uid())
    and exists (
      select 1
      from content.exhibitions as exhibition
      where exhibition.id = exhibition_id
        and exhibition.published_version_id is not null
        and exhibition.archived_at is null
    )
  );

create policy "publishers can update curation"
  on content.curation_placements
  for update
  to authenticated
  using (
    (select content_private.has_staff_role('publisher'::content.staff_role))
  )
  with check (
    (select content_private.has_staff_role('publisher'::content.staff_role))
    and exists (
      select 1
      from content.exhibitions as exhibition
      where exhibition.id = exhibition_id
        and exhibition.published_version_id is not null
        and exhibition.archived_at is null
    )
  );

create policy "staff can read permitted audit log"
  on content.audit_log
  for select
  to authenticated
  using (
    actor_user_id = (select auth.uid())
    or (select content_private.has_staff_role('publisher'::content.staff_role))
  );

create policy "publishers can read outbox"
  on content.outbox_events
  for select
  to authenticated
  using (
    (select content_private.has_staff_role('publisher'::content.staff_role))
  );

create policy "publishers can read submissions"
  on content.exhibition_submissions
  for select
  to authenticated
  using (
    (select content_private.has_staff_role('publisher'::content.staff_role))
  );

create policy "publishers can read submission media"
  on content.submission_media
  for select
  to authenticated
  using (
    (select content_private.has_staff_role('publisher'::content.staff_role))
  );

-- Explicit grants are required on newer Supabase projects. The content schema
-- remains private because it must not be added to the Data API's exposed
-- schemas; these grants support RLS-aware direct access and future
-- security-invoker admin APIs.
revoke all on all tables in schema content from public, anon, authenticated;
revoke all on all functions in schema content_private from public, anon, authenticated;

grant usage on schema content to authenticated, service_role;
grant usage on schema content_private to authenticated, service_role;

grant usage on type content.staff_role to authenticated, service_role;
grant usage on type content.exhibition_version_status to authenticated, service_role;
grant usage on type content.media_asset_status to authenticated, service_role;
grant usage on type content.media_role to authenticated, service_role;
grant usage on type content.curation_surface to authenticated, service_role;
grant usage on type content.outbox_status to authenticated, service_role;
grant usage on type content.submission_status to authenticated, service_role;

grant execute on function content_private.has_staff_role(content.staff_role)
  to authenticated, service_role;

grant select on content.staff_members to authenticated;
grant select, insert on content.venues to authenticated;
grant select, insert on content.exhibitions to authenticated;
grant select, insert on content.exhibition_versions to authenticated;
grant select, insert on content.media_assets to authenticated;
grant select, insert, delete on content.exhibition_version_media to authenticated;
grant update (role, sort_order, focal_x, focal_y)
  on content.exhibition_version_media to authenticated;
grant select, insert on content.curation_placements to authenticated;
grant select on content.audit_log to authenticated;
grant select on content.outbox_events to authenticated;
grant select on content.exhibition_submissions to authenticated;
grant select on content.submission_media to authenticated;

grant all privileges on all tables in schema content to service_role;

-- Retain the existing profile and thought API contracts with explicit grants.
grant select on public.profiles to anon, authenticated;
grant insert, update on public.profiles to authenticated;
grant select on public.thoughts to anon, authenticated;
grant insert, update, delete on public.thoughts to authenticated;
