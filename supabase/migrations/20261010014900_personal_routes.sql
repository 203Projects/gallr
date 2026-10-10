-- Spec 089 personal routes: authored, ordered exhibition routes that can be shared by link.
--
-- Writes go only through the functions below: owners save, publish and delete; staff revoke. Stop details are
-- copied from the published catalogue at save time so a public route can never show invented exhibitions
-- (design E-D7). Readers see a route only while it is published and not revoked.

create table if not exists public.personal_routes (
  id uuid primary key,
  owner uuid not null references auth.users(id) on delete cascade,
  name text not null,
  is_published boolean not null default false,
  published_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint personal_routes_name_length check (char_length(btrim(name)) between 1 and 60),
  constraint personal_routes_name_trimmed check (name = btrim(name)),
  constraint personal_routes_published_at_when_published check (not is_published or published_at is not null)
);

comment on table public.personal_routes is
  'Spec 089 authored routes. updated_at is the route revision used in share links. Written only through route functions.';

create index if not exists personal_routes_owner_updated_idx
  on public.personal_routes (owner, updated_at desc);

create table if not exists public.personal_route_stops (
  route_id uuid not null references public.personal_routes(id) on delete cascade,
  position smallint not null,
  exhibition_id text not null,
  name_ko text not null,
  name_en text not null,
  venue_name_ko text not null,
  venue_name_en text not null,
  latitude double precision not null,
  longitude double precision not null,
  region_ko text not null,
  region_en text not null,
  city_ko text not null,
  primary key (route_id, position),
  constraint personal_route_stops_position_range check (position between 0 and 9),
  constraint personal_route_stops_latitude_range check (latitude between -90 and 90),
  constraint personal_route_stops_longitude_range check (longitude between -180 and 180)
);

comment on table public.personal_route_stops is
  'Spec 089 stop snapshots copied from exhibition_catalog_v2 at save time; exhibition_id has no foreign key so a stop survives catalogue removal.';

create table if not exists public.route_page_daily (
  route_id uuid not null references public.personal_routes(id) on delete cascade,
  day date not null,
  event text not null,
  shared boolean not null,
  count integer not null default 0,
  primary key (route_id, day, event, shared),
  constraint route_page_daily_event check (event in ('route_page_opened', 'route_page_started')),
  constraint route_page_daily_count check (count >= 0)
);

comment on table public.route_page_daily is
  'Spec 089 aggregate route page counts per Asia/Seoul day. Unauthenticated traffic: no reader identity is stored.';

alter table public.personal_routes enable row level security;
alter table public.personal_route_stops enable row level security;
alter table public.route_page_daily enable row level security;

drop policy if exists "owners read their routes" on public.personal_routes;
create policy "owners read their routes"
  on public.personal_routes
  for select
  to authenticated
  using (owner = (select auth.uid()));

drop policy if exists "anyone reads published routes" on public.personal_routes;
create policy "anyone reads published routes"
  on public.personal_routes
  for select
  to anon, authenticated
  using (is_published and revoked_at is null);

drop policy if exists "stops follow their route" on public.personal_route_stops;
create policy "stops follow their route"
  on public.personal_route_stops
  for select
  to anon, authenticated
  using (
    exists (
      select 1
      from public.personal_routes as route
      where route.id = personal_route_stops.route_id
        and (
          route.owner = (select auth.uid())
          or (route.is_published and route.revoked_at is null)
        )
    )
  );

revoke all on public.personal_routes from public, anon, authenticated;
revoke all on public.personal_route_stops from public, anon, authenticated;
revoke all on public.route_page_daily from public, anon, authenticated;
grant select on public.personal_routes to anon, authenticated;
grant select on public.personal_route_stops to anon, authenticated;

-- Route JSON returned by every owner function and read by the app.
create or replace function content_private.personal_route_json(p_route_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', route.id,
    'revision', route.updated_at,
    'name', route.name,
    'is_published', route.is_published,
    'published_at', route.published_at,
    'revoked_at', route.revoked_at,
    'stops', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'position', stop.position,
            'exhibition_id', stop.exhibition_id,
            'name_ko', stop.name_ko,
            'name_en', stop.name_en,
            'venue_name_ko', stop.venue_name_ko,
            'venue_name_en', stop.venue_name_en,
            'latitude', stop.latitude,
            'longitude', stop.longitude,
            'region_ko', stop.region_ko,
            'region_en', stop.region_en,
            'city_ko', stop.city_ko
          )
          order by stop.position
        )
        from public.personal_route_stops as stop
        where stop.route_id = route.id
      ),
      '[]'::jsonb
    )
  )
  from public.personal_routes as route
  where route.id = p_route_id;
$$;

revoke all on function content_private.personal_route_json(uuid) from public, anon, authenticated;

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

  -- Create under the caller when the id is new, then lock the row so saves of one route run one at a time (E-D12).
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
      -- No longer listed: keep it only if this route already held it, with its stored snapshot (E-D18).
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

  update public.personal_routes
  set name = v_name, updated_at = clock_timestamp()
  where id = p_id;

  return content_private.personal_route_json(p_id);
end;
$$;

create or replace function content_private.owned_route_for_update(p_id uuid)
returns public.personal_routes
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_route public.personal_routes%rowtype;
begin
  if v_actor is null then
    raise exception using errcode = '42501', message = 'personal_route_unauthenticated';
  end if;
  select * into v_route from public.personal_routes where id = p_id for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'personal_route_not_found';
  end if;
  if v_route.owner <> v_actor then
    raise exception using errcode = '42501', message = 'personal_route_not_owner';
  end if;
  return v_route;
end;
$$;

create or replace function content_private.publish_personal_route_impl(p_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype := content_private.owned_route_for_update(p_id);
begin
  if v_route.revoked_at is not null then
    raise exception using errcode = '55000', message = 'personal_route_revoked';
  end if;
  update public.personal_routes
  set is_published = true, published_at = coalesce(published_at, clock_timestamp())
  where id = p_id;
  return content_private.personal_route_json(p_id);
end;
$$;

create or replace function content_private.delete_personal_route_impl(p_id uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  perform content_private.owned_route_for_update(p_id);
  delete from public.personal_routes where id = p_id;
end;
$$;

create or replace function content_private.require_route_staff()
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not exists (
    select 1
    from content.staff_members as staff
    where staff.user_id = (select auth.uid())
      and staff.active
  ) then
    raise exception using errcode = '42501', message = 'personal_route_not_staff';
  end if;
end;
$$;

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
  set revoked_at = coalesce(revoked_at, clock_timestamp())
  where id = p_id;
  if not found then
    raise exception using errcode = 'P0002', message = 'personal_route_not_found';
  end if;
  return content_private.get_route_for_moderation_impl(p_id);
end;
$$;

create or replace function content_private.get_route_for_moderation_impl(p_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_route jsonb;
begin
  perform content_private.require_route_staff();
  v_route := content_private.personal_route_json(p_id);
  if v_route is null then
    return null;
  end if;
  return v_route || jsonb_build_object(
    'author_display_name',
    coalesce(
      (
        select nullif(profile.display_name, '')
        from public.personal_routes as route
        join public.profiles as profile on profile.id = route.owner
        where route.id = p_id
      ),
      ''
    )
  );
end;
$$;

revoke all on function content_private.save_personal_route_impl(uuid, text, text[]) from public, anon, authenticated;
revoke all on function content_private.owned_route_for_update(uuid) from public, anon, authenticated;
revoke all on function content_private.publish_personal_route_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.delete_personal_route_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.require_route_staff() from public, anon, authenticated;
revoke all on function content_private.revoke_personal_route_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.get_route_for_moderation_impl(uuid) from public, anon, authenticated;
grant execute on function content_private.save_personal_route_impl(uuid, text, text[]) to authenticated;
grant execute on function content_private.publish_personal_route_impl(uuid) to authenticated;
grant execute on function content_private.delete_personal_route_impl(uuid) to authenticated;
grant execute on function content_private.revoke_personal_route_impl(uuid) to authenticated;
grant execute on function content_private.get_route_for_moderation_impl(uuid) to authenticated;

create or replace function public.save_personal_route(p_id uuid, p_name text, p_exhibition_ids text[])
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.save_personal_route_impl(p_id, p_name, p_exhibition_ids); $$;

create or replace function public.publish_personal_route(p_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.publish_personal_route_impl(p_id); $$;

create or replace function public.delete_personal_route(p_id uuid)
returns void language sql volatile security invoker set search_path = ''
as $$ select content_private.delete_personal_route_impl(p_id); $$;

create or replace function public.revoke_personal_route(p_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.revoke_personal_route_impl(p_id); $$;

create or replace function public.get_route_for_moderation(p_id uuid)
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.get_route_for_moderation_impl(p_id); $$;

-- The owner's routes, newest first; row-level security limits this to the caller's own rows.
create or replace function public.list_my_personal_routes()
returns jsonb language sql stable security invoker set search_path = ''
as $$
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', route.id,
        'name', route.name,
        'stop_count', (select count(*) from public.personal_route_stops as stop where stop.route_id = route.id),
        'is_published', route.is_published,
        'revoked_at', route.revoked_at,
        'updated_at', route.updated_at
      )
      order by route.updated_at desc
    ),
    '[]'::jsonb
  )
  from public.personal_routes as route
  where route.owner = (select auth.uid());
$$;

revoke all on function public.save_personal_route(uuid, text, text[]) from public, anon, authenticated;
revoke all on function public.publish_personal_route(uuid) from public, anon, authenticated;
revoke all on function public.delete_personal_route(uuid) from public, anon, authenticated;
revoke all on function public.revoke_personal_route(uuid) from public, anon, authenticated;
revoke all on function public.get_route_for_moderation(uuid) from public, anon, authenticated;
revoke all on function public.list_my_personal_routes() from public, anon, authenticated;
grant execute on function public.save_personal_route(uuid, text, text[]) to authenticated;
grant execute on function public.publish_personal_route(uuid) to authenticated;
grant execute on function public.delete_personal_route(uuid) to authenticated;
grant execute on function public.revoke_personal_route(uuid) to authenticated;
grant execute on function public.get_route_for_moderation(uuid) to authenticated;
grant execute on function public.list_my_personal_routes() to authenticated;

-- Page counts are written by the public route page with the publishable key. The anonymous role has no access
-- to content_private, so this one function is definer-rights in public; it accepts only two event names and
-- only published, unrevoked routes (design E-D5).
create or replace function public.record_route_page_event(p_route_id uuid, p_event text, p_shared boolean)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_event is null or p_event not in ('route_page_opened', 'route_page_started') then
    return;
  end if;
  if not exists (
    select 1
    from public.personal_routes as route
    where route.id = p_route_id
      and route.is_published
      and route.revoked_at is null
  ) then
    return;
  end if;
  insert into public.route_page_daily (route_id, day, event, shared, count)
  values (p_route_id, (now() at time zone 'Asia/Seoul')::date, p_event, coalesce(p_shared, false), 1)
  on conflict (route_id, day, event, shared) do update
    set count = public.route_page_daily.count + 1;
end;
$$;

revoke all on function public.record_route_page_event(uuid, text, boolean) from public;
grant execute on function public.record_route_page_event(uuid, text, boolean) to anon, authenticated;

-- Recipient loop (design E-D14): of routes first published in a week, how many were opened through a shared
-- link within seven days, and how many opens and direction starts they received.
create or replace view public.personal_route_recipient_loop
with (security_invoker = true)
as
select
  date_trunc('week', route.published_at at time zone 'Asia/Seoul')::date as published_week,
  count(*) as routes_published,
  count(*) filter (
    where exists (
      select 1
      from public.route_page_daily as page
      where page.route_id = route.id
        and page.event = 'route_page_opened'
        and page.shared
        and page.day between (route.published_at at time zone 'Asia/Seoul')::date
          and (route.published_at at time zone 'Asia/Seoul')::date + 6
    )
  ) as routes_opened_within_7_days,
  coalesce(sum(
    (select sum(page.count) from public.route_page_daily as page
     where page.route_id = route.id and page.event = 'route_page_opened' and page.shared)
  ), 0) as shared_opens,
  coalesce(sum(
    (select sum(page.count) from public.route_page_daily as page
     where page.route_id = route.id and page.event = 'route_page_started')
  ), 0) as direction_starts
from public.personal_routes as route
where route.published_at is not null
group by 1;

comment on view public.personal_route_recipient_loop is
  'Spec 089 success metric. Counts are unauthenticated page traffic; staff and operators read it with server credentials.';

revoke all on public.personal_route_recipient_loop from public, anon, authenticated;
grant select on public.personal_route_recipient_loop to service_role;
grant select on public.route_page_daily to service_role;
grant select on public.personal_routes to service_role;
grant select on public.personal_route_stops to service_role;
