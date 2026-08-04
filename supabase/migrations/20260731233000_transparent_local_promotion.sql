begin;

do $$
begin
  create type content.local_promotion_status as enum (
    'submitted', 'approved', 'active', 'rejected', 'ended'
  );
exception when duplicate_object then null;
end
$$;

create table content.local_promotions (
  id uuid primary key default gen_random_uuid(),
  launch_kit_id uuid not null unique
    references content.launch_kits(id) on delete restrict,
  exhibition_id text not null
    references content.exhibitions(id) on delete restrict,
  gallery_id uuid not null
    references content.galleries(id) on delete restrict,
  status content.local_promotion_status not null default 'submitted',
  city_ko text not null,
  city_en text not null default '',
  region_ko text not null,
  region_en text not null default '',
  starts_at timestamptz,
  ends_at timestamptz,
  review_notes text,
  revision integer not null default 1,
  requested_at timestamptz not null default now(),
  reviewed_at timestamptz,
  reviewed_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint local_promotions_locality_present check (
    nullif(btrim(city_ko), '') is not null
    or nullif(btrim(region_ko), '') is not null
  ),
  constraint local_promotions_revision_positive check (revision > 0),
  constraint local_promotions_schedule_order check (
    starts_at is null and ends_at is null
    or starts_at is not null and ends_at is not null and starts_at < ends_at
  ),
  constraint local_promotions_schedule_state check (
    (status in (
      'approved'::content.local_promotion_status,
      'active'::content.local_promotion_status,
      'ended'::content.local_promotion_status
    )) = (starts_at is not null and ends_at is not null)
  ),
  constraint local_promotions_review_notes_length check (
    review_notes is null or length(review_notes) <= 2000
  )
);

create index local_promotions_gallery_status_idx
  on content.local_promotions (gallery_id, status, created_at desc, id);
create index local_promotions_exhibition_idx
  on content.local_promotions (exhibition_id);
create index local_promotions_reviewed_by_idx
  on content.local_promotions (reviewed_by) where reviewed_by is not null;
create index local_promotions_created_by_idx
  on content.local_promotions (created_by) where created_by is not null;
create index local_promotions_updated_by_idx
  on content.local_promotions (updated_by) where updated_by is not null;
create index local_promotions_eligible_schedule_idx
  on content.local_promotions (city_ko, region_ko, starts_at, ends_at, id)
  where status in (
    'approved'::content.local_promotion_status,
    'active'::content.local_promotion_status
  );

create table content.local_promotion_impressions (
  id bigint generated always as identity primary key,
  promotion_id uuid not null
    references content.local_promotions(id) on delete restrict,
  viewer_digest text not null,
  displayed_on date not null,
  city_ko text not null default '',
  region_ko text not null default '',
  displayed_at timestamptz not null default now(),
  constraint local_promotion_impressions_digest_format check (
    viewer_digest ~ '^[0-9a-f]{64}$'
  ),
  constraint local_promotion_impressions_viewer_day_unique
    unique (viewer_digest, displayed_on)
);

create index local_promotion_impressions_promotion_idx
  on content.local_promotion_impressions (promotion_id, displayed_at desc, id);

alter table content.local_promotions enable row level security;
alter table content.local_promotion_impressions enable row level security;

revoke all on table content.local_promotions,
  content.local_promotion_impressions
from public, anon, authenticated;
revoke all on sequence content.local_promotion_impressions_id_seq
from public, anon, authenticated;

create or replace function content_private.local_promotion_json(
  p_promotion_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', promotion.id,
    'launch_kit_id', promotion.launch_kit_id,
    'exhibition_id', promotion.exhibition_id,
    'gallery_id', promotion.gallery_id,
    'status', promotion.status::text,
    'revision', promotion.revision,
    'city_ko', promotion.city_ko,
    'city_en', promotion.city_en,
    'region_ko', promotion.region_ko,
    'region_en', promotion.region_en,
    'starts_at', promotion.starts_at,
    'ends_at', promotion.ends_at,
    'review_notes', coalesce(promotion.review_notes, ''),
    'requested_at', promotion.requested_at,
    'reviewed_at', promotion.reviewed_at,
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'closing_date', to_char(version.closing_date, 'YYYY-MM-DD'),
    'gallery_name_ko', gallery.name_ko,
    'gallery_name_en', gallery.name_en
  )
  from content.local_promotions promotion
  join content.exhibitions exhibition on exhibition.id = promotion.exhibition_id
  join content.exhibition_versions version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  join content.galleries gallery on gallery.id = promotion.gallery_id
  where promotion.id = p_promotion_id;
$$;

create or replace function content_private.owner_request_local_promotion_impl(
  p_launch_kit_id uuid,
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
  v_kit content.launch_kits := content_private.owner_assert_active_launch_kit(p_launch_kit_id);
  v_version content.exhibition_versions%rowtype;
  v_promotion content.local_promotions%rowtype;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_response jsonb;
begin
  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('launch_kit_id', p_launch_kit_id)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_request_local_promotion', v_fingerprint
  ) request;
  if v_is_replay then return v_stored; end if;

  select version.* into v_version
  from content.exhibitions exhibition
  join content.exhibition_versions version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  where exhibition.id = v_kit.exhibition_id
    and exhibition.gallery_id = v_kit.gallery_id
    and exhibition.owner_status = 'published'::content.owner_exhibition_status
    and exhibition.archived_at is null
    and version.status = 'published'::content.exhibition_version_status
    and version.closing_date >= (now() at time zone 'Asia/Seoul')::date;
  if not found then
    raise exception using errcode = '42501', message = 'published_owner_exhibition_required';
  end if;

  insert into content.local_promotions (
    launch_kit_id, exhibition_id, gallery_id, status,
    city_ko, city_en, region_ko, region_en,
    requested_at, created_by, updated_by
  ) values (
    v_kit.id, v_kit.exhibition_id, v_kit.gallery_id, 'submitted',
    v_version.city_ko, coalesce(v_version.city_en, ''),
    v_version.region_ko, coalesce(v_version.region_en, ''),
    now(), v_user_id, v_user_id
  )
  on conflict (launch_kit_id) do update
  set
    status = 'submitted',
    city_ko = excluded.city_ko,
    city_en = excluded.city_en,
    region_ko = excluded.region_ko,
    region_en = excluded.region_en,
    starts_at = null,
    ends_at = null,
    review_notes = null,
    reviewed_at = null,
    reviewed_by = null,
    requested_at = now(),
    revision = content.local_promotions.revision + 1,
    updated_at = now(),
    updated_by = v_user_id
  where content.local_promotions.status in (
    'rejected'::content.local_promotion_status,
    'ended'::content.local_promotion_status
  )
  returning * into v_promotion;

  if not found then
    raise exception using errcode = '55000', message = 'promotion_request_already_open';
  end if;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id, metadata
  ) values (
    v_user_id, 'local_promotion.requested', 'local_promotion',
    v_promotion.id::text, p_request_id,
    jsonb_build_object(
      'launch_kit_id', v_kit.id,
      'exhibition_id', v_kit.exhibition_id,
      'gallery_id', v_kit.gallery_id
    )
  );
  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key
  ) values (
    'local_promotion', v_promotion.id::text, 'local_promotion.requested',
    jsonb_build_object(
      'promotion_id', v_promotion.id,
      'exhibition_id', v_kit.exhibition_id,
      'gallery_id', v_kit.gallery_id
    ),
    format('local_promotion:%s:requested:%s', v_promotion.id, p_request_id)
  );

  v_response := content_private.local_promotion_json(v_promotion.id);
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_request_local_promotion',
    v_fingerprint, v_response
  );
end;
$$;

create or replace function content_private.owner_list_local_promotions_impl()
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
  select content_private.local_promotion_json(promotion.id)
  from content.local_promotions promotion
  where promotion.gallery_id = v_gallery_id
  order by promotion.created_at desc, promotion.id;
end;
$$;

create or replace function content_private.admin_list_local_promotions_impl(
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
    'submitted', 'approved', 'active', 'rejected', 'ended'
  ) then
    raise exception using errcode = '22023', message = 'promotion_status_invalid';
  end if;
  return query
  select content_private.local_promotion_json(promotion.id)
  from content.local_promotions promotion
  join content.exhibitions exhibition on exhibition.id = promotion.exhibition_id
  join content.exhibition_versions version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  join content.galleries gallery on gallery.id = promotion.gallery_id
  where (p_status is null or promotion.status::text = p_status)
    and (
      v_search = ''
      or lower(version.name_ko) like '%' || v_search || '%'
      or lower(version.name_en) like '%' || v_search || '%'
      or lower(gallery.name_ko) like '%' || v_search || '%'
      or lower(gallery.name_en) like '%' || v_search || '%'
    )
  order by promotion.requested_at desc, promotion.id;
end;
$$;

create or replace function content_private.admin_approve_local_promotion_impl(
  p_promotion_id uuid,
  p_starts_at timestamptz,
  p_ends_at timestamptz,
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
  v_closing_date date;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_response jsonb;
begin
  if p_starts_at is null or p_ends_at is null or p_starts_at >= p_ends_at
     or p_ends_at <= now() then
    raise exception using errcode = '22023', message = 'promotion_schedule_invalid';
  end if;
  v_fingerprint := content_private.command_request_fingerprint(jsonb_build_object(
    'promotion_id', p_promotion_id,
    'starts_at', p_starts_at,
    'ends_at', p_ends_at
  ));
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_actor_id, p_request_id, 'admin_approve_local_promotion', v_fingerprint
  ) request;
  if v_is_replay then return v_stored; end if;

  select version.closing_date
  into v_closing_date
  from content.local_promotions promotion
  join content.exhibitions exhibition on exhibition.id = promotion.exhibition_id
  join content.exhibition_versions version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  where promotion.id = p_promotion_id
    and promotion.status = 'submitted'::content.local_promotion_status
    and exhibition.owner_status = 'published'::content.owner_exhibition_status
    and exhibition.archived_at is null
    and version.status = 'published'::content.exhibition_version_status
  for update of promotion;
  if not found then
    raise exception using errcode = '22023', message = 'promotion_not_approvable';
  end if;
  if (p_ends_at at time zone 'Asia/Seoul')::date > v_closing_date then
    raise exception using errcode = '22023', message = 'promotion_schedule_outlives_exhibition';
  end if;

  update content.local_promotions set
    status = case
      when p_starts_at <= now() then 'active'::content.local_promotion_status
      else 'approved'::content.local_promotion_status
    end,
    starts_at = p_starts_at,
    ends_at = p_ends_at,
    review_notes = null,
    reviewed_at = now(),
    reviewed_by = v_actor_id,
    revision = revision + 1,
    updated_at = now(),
    updated_by = v_actor_id
  where id = p_promotion_id;
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id, metadata
  ) values (
    v_actor_id, 'local_promotion.approved', 'local_promotion',
    p_promotion_id::text, p_request_id,
    jsonb_build_object('starts_at', p_starts_at, 'ends_at', p_ends_at)
  );
  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key
  ) values (
    'local_promotion', p_promotion_id::text, 'local_promotion.approved',
    jsonb_build_object(
      'promotion_id', p_promotion_id,
      'starts_at', p_starts_at,
      'ends_at', p_ends_at
    ),
    format('local_promotion:%s:approved:%s', p_promotion_id, p_request_id)
  );
  v_response := content_private.local_promotion_json(p_promotion_id);
  return content_private.complete_command_request(
    v_actor_id, p_request_id, 'admin_approve_local_promotion',
    v_fingerprint, v_response
  );
end;
$$;

create or replace function content_private.admin_reject_local_promotion_impl(
  p_promotion_id uuid,
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
  v_notes text := btrim(coalesce(p_review_notes, ''));
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_response jsonb;
begin
  if length(v_notes) < 1 or length(v_notes) > 2000 then
    raise exception using errcode = '22023', message = 'review_notes_required';
  end if;
  v_fingerprint := content_private.command_request_fingerprint(jsonb_build_object(
    'promotion_id', p_promotion_id, 'review_notes', v_notes
  ));
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_actor_id, p_request_id, 'admin_reject_local_promotion', v_fingerprint
  ) request;
  if v_is_replay then return v_stored; end if;

  update content.local_promotions set
    status = 'rejected',
    starts_at = null,
    ends_at = null,
    review_notes = v_notes,
    reviewed_at = now(),
    reviewed_by = v_actor_id,
    revision = revision + 1,
    updated_at = now(),
    updated_by = v_actor_id
  where id = p_promotion_id
    and status = 'submitted'::content.local_promotion_status;
  if not found then
    raise exception using errcode = '22023', message = 'promotion_not_rejectable';
  end if;
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id, metadata
  ) values (
    v_actor_id, 'local_promotion.rejected', 'local_promotion',
    p_promotion_id::text, p_request_id, jsonb_build_object('review_notes', v_notes)
  );
  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key
  ) values (
    'local_promotion', p_promotion_id::text, 'local_promotion.rejected',
    jsonb_build_object('promotion_id', p_promotion_id),
    format('local_promotion:%s:rejected:%s', p_promotion_id, p_request_id)
  );
  v_response := content_private.local_promotion_json(p_promotion_id);
  return content_private.complete_command_request(
    v_actor_id, p_request_id, 'admin_reject_local_promotion',
    v_fingerprint, v_response
  );
end;
$$;

create or replace function content_private.service_select_local_promotion_impl(
  p_viewer_digest text,
  p_city_ko text,
  p_region_ko text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_city text := btrim(coalesce(p_city_ko, ''));
  v_region text := btrim(coalesce(p_region_ko, ''));
  v_displayed_on date := (now() at time zone 'Asia/Seoul')::date;
  v_promotion_id uuid;
  v_response jsonb;
begin
  if p_viewer_digest !~ '^[0-9a-f]{64}$'
     or (v_city = '' and v_region = '')
     or length(v_city) > 100 or length(v_region) > 100 then
    raise exception using errcode = '22023', message = 'promotion_request_invalid';
  end if;
  perform pg_advisory_xact_lock(
    hashtextextended('local-promotion:' || p_viewer_digest || ':' || v_displayed_on::text, 0)
  );
  if exists (
    select 1 from content.local_promotion_impressions impression
    where impression.viewer_digest = p_viewer_digest
      and impression.displayed_on = v_displayed_on
  ) then
    return null;
  end if;

  update content.local_promotions set status = 'ended', updated_at = now()
  where status in ('approved', 'active') and ends_at <= now();
  update content.local_promotions set status = 'active', updated_at = now()
  where status = 'approved' and starts_at <= now() and ends_at > now();

  select promotion.id into v_promotion_id
  from content.local_promotions promotion
  join content.launch_kits kit
    on kit.id = promotion.launch_kit_id
   and kit.status = 'active'::content.launch_kit_status
  join content.exhibitions exhibition
    on exhibition.id = promotion.exhibition_id
   and exhibition.gallery_id = promotion.gallery_id
   and exhibition.owner_status = 'published'::content.owner_exhibition_status
   and exhibition.archived_at is null
  join content.exhibition_versions version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
   and version.status = 'published'::content.exhibition_version_status
  where promotion.status = 'active'::content.local_promotion_status
    and promotion.starts_at <= now()
    and promotion.ends_at > now()
    and (v_city = '' or promotion.city_ko = v_city)
    and (v_region = '' or promotion.region_ko = v_region)
    and version.closing_date >= v_displayed_on
  order by md5(p_viewer_digest || ':' || v_displayed_on::text || ':' || promotion.id::text)
  limit 1;
  if not found then return null; end if;

  insert into content.local_promotion_impressions (
    promotion_id, viewer_digest, displayed_on, city_ko, region_ko
  ) values (v_promotion_id, p_viewer_digest, v_displayed_on, v_city, v_region);

  select jsonb_build_object(
    'promotion_id', promotion.id,
    'exhibition_id', promotion.exhibition_id,
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'city_ko', version.city_ko,
    'city_en', version.city_en,
    'region_ko', version.region_ko,
    'region_en', version.region_en,
    'opening_date', to_char(version.opening_date, 'YYYY-MM-DD'),
    'closing_date', to_char(version.closing_date, 'YYYY-MM-DD'),
    'cover_image_url', cover.public_url,
    'disclosure', 'paid_placement'
  ) into v_response
  from content.local_promotions promotion
  join content.exhibitions exhibition on exhibition.id = promotion.exhibition_id
  join content.exhibition_versions version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  left join lateral (
    select asset.public_url
    from content.exhibition_version_media attachment
    join content.media_assets asset on asset.id = attachment.media_id
    where attachment.version_id = version.id
      and attachment.role = 'cover'::content.media_role
      and asset.status = 'published'::content.media_asset_status
    order by attachment.sort_order, asset.id
    limit 1
  ) cover on true
  where promotion.id = v_promotion_id;
  return v_response;
end;
$$;

create or replace function public.owner_request_local_promotion(
  p_launch_kit_id uuid, p_request_id uuid
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.owner_request_local_promotion_impl(p_launch_kit_id, p_request_id); $$;
create or replace function public.owner_list_local_promotions()
returns setof jsonb language sql stable security invoker set search_path = ''
as $$ select * from content_private.owner_list_local_promotions_impl(); $$;
create or replace function public.admin_list_local_promotions(
  p_search text default '', p_status text default null
) returns setof jsonb language sql stable security invoker set search_path = ''
as $$ select * from content_private.admin_list_local_promotions_impl(p_search, p_status); $$;
create or replace function public.admin_approve_local_promotion(
  p_promotion_id uuid, p_starts_at timestamptz, p_ends_at timestamptz, p_request_id uuid
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.admin_approve_local_promotion_impl(
  p_promotion_id, p_starts_at, p_ends_at, p_request_id
); $$;
create or replace function public.admin_reject_local_promotion(
  p_promotion_id uuid, p_review_notes text, p_request_id uuid
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.admin_reject_local_promotion_impl(
  p_promotion_id, p_review_notes, p_request_id
); $$;
create or replace function public.service_select_local_promotion(
  p_viewer_digest text, p_city_ko text, p_region_ko text
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.service_select_local_promotion_impl(
  p_viewer_digest, p_city_ko, p_region_ko
); $$;

revoke all on function content_private.local_promotion_json(uuid),
  content_private.owner_request_local_promotion_impl(uuid, uuid),
  content_private.owner_list_local_promotions_impl(),
  content_private.admin_list_local_promotions_impl(text, text),
  content_private.admin_approve_local_promotion_impl(uuid, timestamptz, timestamptz, uuid),
  content_private.admin_reject_local_promotion_impl(uuid, text, uuid)
from public, anon, authenticated, service_role;
grant execute on function content_private.owner_request_local_promotion_impl(uuid, uuid),
  content_private.owner_list_local_promotions_impl(),
  content_private.admin_list_local_promotions_impl(text, text),
  content_private.admin_approve_local_promotion_impl(uuid, timestamptz, timestamptz, uuid),
  content_private.admin_reject_local_promotion_impl(uuid, text, uuid)
to authenticated;

revoke all on function content_private.service_select_local_promotion_impl(text, text, text)
from public, anon, authenticated;
grant execute on function content_private.service_select_local_promotion_impl(text, text, text)
to service_role;

revoke all on function public.owner_request_local_promotion(uuid, uuid),
  public.owner_list_local_promotions(),
  public.admin_list_local_promotions(text, text),
  public.admin_approve_local_promotion(uuid, timestamptz, timestamptz, uuid),
  public.admin_reject_local_promotion(uuid, text, uuid)
from public, anon, service_role;
grant execute on function public.owner_request_local_promotion(uuid, uuid),
  public.owner_list_local_promotions(),
  public.admin_list_local_promotions(text, text),
  public.admin_approve_local_promotion(uuid, timestamptz, timestamptz, uuid),
  public.admin_reject_local_promotion(uuid, text, uuid)
to authenticated;

revoke all on function public.service_select_local_promotion(text, text, text)
from public, anon, authenticated;
grant execute on function public.service_select_local_promotion(text, text, text)
to service_role;

comment on table content.local_promotions is
  'Paid Launch Kit promotion requests; separate from editorial curation and organic ranking.';
comment on table content.local_promotion_impressions is
  'Pseudonymous coarse-locality delivery records used only for the once-per-Seoul-day cap.';
comment on column content.local_promotion_impressions.viewer_digest is
  'SHA-256 digest produced at the Edge boundary; raw installation keys are never stored.';

commit;
