-- Spec 089 read path (090 eng review D4): a shared route is readable only by its id.
--
-- 20261010014900 let anon and every account select published routes and their stops straight from the tables,
-- so anyone with the publishable key could list every shared route and its owner. Readers now fetch one route
-- through get_published_route; the tables are readable only by the route's owner.
begin;

drop policy if exists "anyone reads published routes" on public.personal_routes;

drop policy if exists "stops follow their route" on public.personal_route_stops;
drop policy if exists "owners read their route stops" on public.personal_route_stops;
create policy "owners read their route stops"
  on public.personal_route_stops
  for select
  to authenticated
  using (
    exists (
      select 1
      from public.personal_routes as route
      where route.id = personal_route_stops.route_id
        and route.owner = (select auth.uid())
    )
  );

revoke select on public.personal_routes from anon;
revoke select on public.personal_route_stops from anon;

-- The anonymous role has no access to content_private, so like record_route_page_event this read is definer-rights
-- in public. It returns one published, unrevoked route with its stops in order, or null, so a missing,
-- unpublished and revoked route look the same to the reader.
create or replace function public.get_published_route(p_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', route.id,
    'name', route.name,
    'owner', route.owner,
    'updated_at', route.updated_at,
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
  where route.id = p_id
    and route.is_published
    and route.revoked_at is null;
$$;

comment on function public.get_published_route(uuid) is
  'Spec 089 shared route read: one published, unrevoked route with its stop snapshots, or null.';

revoke all on function public.get_published_route(uuid) from public;
grant execute on function public.get_published_route(uuid) to anon, authenticated;

commit;
