-- Spec 089 public routes (US7, US8): listing state on personal routes.
--
--   unlisted ──request──► requested ──approve──► approved      (an active editor's request is approved at once)
--      ▲   ◄──withdraw──┘    └──decline──► declined ──request──► requested
--      ├── withdraw / revoke ◄── approved
--      │   an owner's change to the name or stops of a requested or approved route (non-editor) ──► requested
--   removed ◄── staff unlist (from requested, approved, declined); only staff restore removed ──► unlisted
--
-- Whether a route is shown to readers is derived, never stored (20261010150000). Writes go only through the
-- functions below; app roles have no table write privilege (089).
begin;

alter table public.personal_routes
  add column if not exists listing_state text not null default 'unlisted',
  add column if not exists listing_requested_at timestamptz,
  add column if not exists listing_decided_at timestamptz,
  add column if not exists listing_decided_by uuid references auth.users(id) on delete set null,
  add column if not exists listing_last_approved_at timestamptz,
  add column if not exists listing_decline_reason text,
  add column if not exists listing_decline_note text;

alter table public.personal_routes drop constraint if exists personal_routes_listing_state;
alter table public.personal_routes add constraint personal_routes_listing_state
  check (listing_state in ('unlisted', 'requested', 'approved', 'declined', 'removed'));
alter table public.personal_routes drop constraint if exists personal_routes_listing_decline_reason;
alter table public.personal_routes add constraint personal_routes_listing_decline_reason
  check (
    (listing_state = 'declined') = (listing_decline_reason is not null)
    and (listing_decline_reason is null
         or listing_decline_reason in ('name_or_description', 'promotional', 'composition', 'other'))
  );
alter table public.personal_routes drop constraint if exists personal_routes_listing_decline_note;
alter table public.personal_routes add constraint personal_routes_listing_decline_note
  check (listing_decline_note is null or char_length(listing_decline_note) <= 500);
alter table public.personal_routes drop constraint if exists personal_routes_listing_approved_decided;
alter table public.personal_routes add constraint personal_routes_listing_approved_decided
  check (listing_state <> 'approved' or listing_decided_at is not null);

comment on column public.personal_routes.listing_state is
  'Public list state set by the author and staff; visibility to readers is derived from it and the catalogue.';
comment on column public.personal_routes.listing_decided_at is
  'Time of the last approve or decline; while approved it identifies the approved version that copies count for.';
comment on column public.personal_routes.listing_last_approved_at is
  'Time of the last approval, kept when an edit returns the route to review (Admin shows it as edited).';

create or replace function content_private.is_active_route_editor(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from content.editor_memberships as membership
    where membership.user_id = p_user and membership.active
  );
$$;

-- The single reason a route is not shown, in the order the author sees it (DD13).
create or replace function content_private.route_listing_blocker(p_route_id uuid, p_today date)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  with route as (
    select * from public.personal_routes where id = p_route_id
  ),
  stops as (
    select stop.exhibition_id, catalogue.opening_date, catalogue.closing_date, catalogue.id is not null as listed
    from public.personal_route_stops as stop
    left join public.exhibition_catalog_v2 as catalogue on catalogue.id = stop.exhibition_id
    where stop.route_id = p_route_id
  )
  select case
    when route.revoked_at is not null then 'revoked'
    when route.listing_state = 'removed' then 'removed'
    when route.listing_state = 'declined' then 'declined'
    when route.listing_state not in ('requested', 'approved') then null
    when exists (select 1 from stops where listed and closing_date < p_today) then 'ended_stop'
    when exists (select 1 from stops where not listed) then 'missing_stop'
    when (select greatest(p_today, max(opening_date)) > min(closing_date) from stops) then 'no_shared_day'
    else null
  end
  from route;
$$;

-- An author's route row as 내 동선 shows it.
create or replace function content_private.my_route_row_json(p_route_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', route.id,
    'name', route.name,
    'stop_count', (select count(*) from public.personal_route_stops as stop where stop.route_id = route.id),
    'is_published', route.is_published,
    'revoked_at', route.revoked_at,
    'updated_at', route.updated_at,
    'listing_state', route.listing_state,
    'listing_decline_reason', route.listing_decline_reason,
    'listing_decline_note', route.listing_decline_note,
    'author_is_editor', content_private.is_active_route_editor(route.owner),
    'listing_blocker', content_private.route_listing_blocker(route.id, (now() at time zone 'Asia/Seoul')::date)
  )
  from public.personal_routes as route
  where route.id = p_route_id;
$$;

create or replace function content_private.list_my_personal_routes_impl()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    jsonb_agg(content_private.my_route_row_json(route.id) order by route.updated_at desc),
    '[]'::jsonb
  )
  from public.personal_routes as route
  where route.owner = (select auth.uid());
$$;

create or replace function public.list_my_personal_routes()
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.list_my_personal_routes_impl(); $$;

create or replace function content_private.request_route_listing_impl(p_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype := content_private.owned_route_for_update(p_id);
  v_actor uuid := auth.uid();
  v_now timestamptz := clock_timestamp();
begin
  if v_route.revoked_at is not null then
    raise exception using errcode = '55000', message = 'personal_route_revoked';
  end if;
  if not v_route.is_published then
    raise exception using errcode = '55000', message = 'route_listing_requires_published';
  end if;
  if v_route.listing_state = 'removed' then
    raise exception using errcode = '55000', message = 'route_listing_invalid_transition';
  end if;
  if v_route.listing_state in ('unlisted', 'declined') then
    if content_private.is_active_route_editor(v_actor) then
      update public.personal_routes
      set listing_state = 'approved', listing_requested_at = v_now, listing_decided_at = v_now,
          listing_decided_by = v_actor, listing_last_approved_at = v_now,
          listing_decline_reason = null, listing_decline_note = null
      where id = p_id;
    else
      update public.personal_routes
      set listing_state = 'requested', listing_requested_at = v_now,
          listing_decline_reason = null, listing_decline_note = null
      where id = p_id;
    end if;
  end if;
  return content_private.my_route_row_json(p_id);
end;
$$;

create or replace function content_private.withdraw_route_listing_impl(p_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype := content_private.owned_route_for_update(p_id);
begin
  if v_route.listing_state in ('declined', 'removed') then
    raise exception using errcode = '55000', message = 'route_listing_invalid_transition';
  end if;
  if v_route.listing_state in ('requested', 'approved') then
    update public.personal_routes set listing_state = 'unlisted' where id = p_id;
  end if;
  return content_private.my_route_row_json(p_id);
end;
$$;

create or replace function content_private.staff_route_for_update(p_id uuid)
returns public.personal_routes
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype;
begin
  perform content_private.require_route_staff();
  select * into v_route from public.personal_routes where id = p_id for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'personal_route_not_found';
  end if;
  return v_route;
end;
$$;

create or replace function content_private.unlist_route_impl(p_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype := content_private.staff_route_for_update(p_id);
begin
  if v_route.listing_state = 'unlisted' then
    raise exception using errcode = '55000', message = 'route_listing_invalid_transition';
  end if;
  if v_route.listing_state <> 'removed' then
    update public.personal_routes
    set listing_state = 'removed', listing_decided_by = auth.uid(),
        listing_decline_reason = null, listing_decline_note = null
    where id = p_id;
  end if;
  return content_private.get_route_for_moderation_impl(p_id)
    || jsonb_build_object('listing_state', 'removed');
end;
$$;

create or replace function content_private.restore_route_listing_impl(p_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype := content_private.staff_route_for_update(p_id);
begin
  if v_route.listing_state <> 'removed' then
    raise exception using errcode = '55000', message = 'route_listing_invalid_transition';
  end if;
  update public.personal_routes set listing_state = 'unlisted', listing_decided_by = auth.uid() where id = p_id;
  return content_private.get_route_for_moderation_impl(p_id)
    || jsonb_build_object('listing_state', 'unlisted');
end;
$$;

-- 089 revoke, now also taking the route off the list (unless staff already removed it).
create or replace function content_private.revoke_personal_route_impl(p_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  perform content_private.require_route_staff();
  update public.personal_routes
  set revoked_at = coalesce(revoked_at, clock_timestamp()),
      listing_state = case when listing_state = 'removed' then 'removed' else 'unlisted' end,
      listing_decline_reason = case when listing_state = 'removed' then listing_decline_reason else null end,
      listing_decline_note = case when listing_state = 'removed' then listing_decline_note else null end
  where id = p_id;
  if not found then
    raise exception using errcode = 'P0002', message = 'personal_route_not_found';
  end if;
  return content_private.get_route_for_moderation_impl(p_id);
end;
$$;

-- 089 save, unchanged except the edit reset: a change to the name or the stops of a requested or approved route
-- of a non-editor returns it to review in the same transaction (R10).
create or replace function content_private.save_personal_route_impl(
  p_id uuid,
  p_name text,
  p_exhibition_ids text[]
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_name text := btrim(coalesce(p_name, ''));
  v_ids text[] := coalesce(p_exhibition_ids, array[]::text[]);
  v_route public.personal_routes%rowtype;
  v_previous_ids text[];
  v_changed boolean;
  v_resets boolean;
  v_missing_location text[] := array[]::text[];
  v_unavailable text[] := array[]::text[];
  v_stops jsonb := '[]'::jsonb;
  v_position integer := 0;
  v_id text;
  v_catalog public.exhibition_catalog_v2%rowtype;
  v_previous public.personal_route_stops%rowtype;
begin
  if v_actor is null then
    raise exception using errcode = '42501', message = 'personal_route_unauthenticated';
  end if;
  if p_id is null then
    raise exception using errcode = '22023', message = 'personal_route_id_required';
  end if;
  if char_length(v_name) not between 1 and 60 then
    raise exception using errcode = '22023', message = 'personal_route_invalid_name';
  end if;
  if cardinality(v_ids) not between 2 and 10 then
    raise exception using errcode = '22023', message = 'personal_route_invalid_stop_count';
  end if;
  if (select count(distinct id) from unnest(v_ids) as id) <> cardinality(v_ids) then
    raise exception using errcode = '22023', message = 'personal_route_duplicate_stop';
  end if;

  insert into public.personal_routes (id, owner, name)
  values (p_id, v_actor, v_name)
  on conflict (id) do nothing;

  select * into v_route from public.personal_routes where id = p_id for update;
  if v_route.owner <> v_actor then
    raise exception using errcode = '42501', message = 'personal_route_not_owner';
  end if;
  if v_route.revoked_at is not null then
    raise exception using errcode = '55000', message = 'personal_route_revoked';
  end if;

  v_previous_ids := array(
    select stop.exhibition_id from public.personal_route_stops as stop
    where stop.route_id = p_id order by stop.position
  );

  foreach v_id in array v_ids loop
    select * into v_catalog from public.exhibition_catalog_v2 where id = v_id;
    if found then
      if v_catalog.latitude is null or v_catalog.longitude is null then
        v_missing_location := v_missing_location || v_id;
      else
        v_stops := v_stops || jsonb_build_object(
          'position', v_position,
          'exhibition_id', v_id,
          'name_ko', v_catalog.name_ko,
          'name_en', v_catalog.name_en,
          'venue_name_ko', v_catalog.venue_name_ko,
          'venue_name_en', v_catalog.venue_name_en,
          'latitude', v_catalog.latitude,
          'longitude', v_catalog.longitude,
          'region_ko', v_catalog.region_ko,
          'region_en', v_catalog.region_en,
          'city_ko', v_catalog.city_ko
        );
      end if;
    else
      select * into v_previous
      from public.personal_route_stops
      where route_id = p_id and exhibition_id = v_id
      limit 1;
      if found then
        v_stops := v_stops || jsonb_build_object(
          'position', v_position,
          'exhibition_id', v_id,
          'name_ko', v_previous.name_ko,
          'name_en', v_previous.name_en,
          'venue_name_ko', v_previous.venue_name_ko,
          'venue_name_en', v_previous.venue_name_en,
          'latitude', v_previous.latitude,
          'longitude', v_previous.longitude,
          'region_ko', v_previous.region_ko,
          'region_en', v_previous.region_en,
          'city_ko', v_previous.city_ko
        );
      else
        v_unavailable := v_unavailable || v_id;
      end if;
    end if;
    v_position := v_position + 1;
  end loop;

  if cardinality(v_missing_location) > 0 then
    raise exception using
      errcode = '22023',
      message = 'personal_route_missing_location',
      detail = to_jsonb(v_missing_location)::text;
  end if;
  if cardinality(v_unavailable) > 0 then
    raise exception using
      errcode = '22023',
      message = 'personal_route_unavailable_stops',
      detail = to_jsonb(v_unavailable)::text;
  end if;

  delete from public.personal_route_stops where route_id = p_id;
  insert into public.personal_route_stops (
    route_id, position, exhibition_id, name_ko, name_en, venue_name_ko, venue_name_en,
    latitude, longitude, region_ko, region_en, city_ko
  )
  select
    p_id, stop.position, stop.exhibition_id, stop.name_ko, stop.name_en, stop.venue_name_ko, stop.venue_name_en,
    stop.latitude, stop.longitude, stop.region_ko, stop.region_en, stop.city_ko
  from jsonb_to_recordset(v_stops) as stop(
    position smallint, exhibition_id text, name_ko text, name_en text, venue_name_ko text, venue_name_en text,
    latitude double precision, longitude double precision, region_ko text, region_en text, city_ko text
  );

  v_changed := v_route.name is distinct from v_name or v_previous_ids is distinct from v_ids;
  v_resets := v_changed
    and v_route.listing_state in ('requested', 'approved')
    and not content_private.is_active_route_editor(v_actor);

  update public.personal_routes
  set name = v_name,
      updated_at = clock_timestamp(),
      listing_state = case when v_resets then 'requested' else listing_state end,
      listing_requested_at = case
        when v_resets and listing_state = 'approved' then clock_timestamp()
        else listing_requested_at
      end
  where id = p_id;

  return content_private.personal_route_json(p_id);
end;
$$;

revoke all on function content_private.is_active_route_editor(uuid) from public, anon, authenticated;
revoke all on function content_private.route_listing_blocker(uuid, date) from public, anon, authenticated;
revoke all on function content_private.my_route_row_json(uuid) from public, anon, authenticated;
revoke all on function content_private.list_my_personal_routes_impl() from public, anon, authenticated;
revoke all on function content_private.request_route_listing_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.withdraw_route_listing_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.staff_route_for_update(uuid) from public, anon, authenticated;
revoke all on function content_private.unlist_route_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.restore_route_listing_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.revoke_personal_route_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.save_personal_route_impl(uuid, text, text[]) from public, anon, authenticated;
grant execute on function content_private.list_my_personal_routes_impl() to authenticated;
grant execute on function content_private.request_route_listing_impl(uuid) to authenticated;
grant execute on function content_private.withdraw_route_listing_impl(uuid) to authenticated;
grant execute on function content_private.unlist_route_impl(uuid) to authenticated;
grant execute on function content_private.restore_route_listing_impl(uuid) to authenticated;
grant execute on function content_private.revoke_personal_route_impl(uuid) to authenticated;
grant execute on function content_private.save_personal_route_impl(uuid, text, text[]) to authenticated;

create or replace function public.request_route_listing(p_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.request_route_listing_impl(p_id); $$;

create or replace function public.withdraw_route_listing(p_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.withdraw_route_listing_impl(p_id); $$;

create or replace function public.unlist_route(p_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.unlist_route_impl(p_id); $$;

create or replace function public.restore_route_listing(p_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.restore_route_listing_impl(p_id); $$;

revoke all on function public.request_route_listing(uuid) from public, anon, authenticated;
revoke all on function public.withdraw_route_listing(uuid) from public, anon, authenticated;
revoke all on function public.unlist_route(uuid) from public, anon, authenticated;
revoke all on function public.restore_route_listing(uuid) from public, anon, authenticated;
revoke all on function public.list_my_personal_routes() from public, anon, authenticated;
grant execute on function public.request_route_listing(uuid) to authenticated;
grant execute on function public.withdraw_route_listing(uuid) to authenticated;
grant execute on function public.unlist_route(uuid) to authenticated;
grant execute on function public.restore_route_listing(uuid) to authenticated;
grant execute on function public.list_my_personal_routes() to authenticated;

commit;
