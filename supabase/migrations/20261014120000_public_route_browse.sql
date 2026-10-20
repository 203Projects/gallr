-- Spec 092: the 동선 둘러보기 browse screen reads every listed route, sorted, filtered by district and paged.
--
-- A route is shown here exactly when it is shown in 추천 동선: content_private.route_first_shared_day is not null
-- (approved, published, unrevoked, every stop in the catalogue, a day on which every stop is running). Rows carry
-- the list_public_routes row unchanged plus up to three stop covers, so the app needs no read per route.
-- 'popular' is the 추천 동선 ranking (30-day copies of the approved version, newer approval, id); 'newest' is the
-- approval time, newest first, then id. A blank district means every district. Paging is by offset; the app drops a route it has already loaded.
-- list_public_routes and its consumers are unchanged.
begin;

create or replace function content_private.browse_public_routes_impl(
  p_sort text,
  p_district text,
  p_limit integer,
  p_offset integer,
  p_today date
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_sort text := coalesce(p_sort, 'popular');
  v_limit integer := greatest(1, least(coalesce(p_limit, 20), 50));
  v_offset integer := greatest(0, coalesce(p_offset, 0));
  v_district text := nullif(btrim(p_district), '');
  v_rows jsonb;
begin
  if v_sort not in ('popular', 'newest') then
    raise exception using errcode = '22023', message = 'route_browse_invalid_sort';
  end if;

  with shown as (
    select route.*, content_private.route_first_shared_day(route.id, p_today) as first_shared_day
    from public.personal_routes as route
    where route.listing_state = 'approved' and route.is_published and route.revoked_at is null
      and (
        v_district is null
        or exists (
          select 1 from public.personal_route_stops as stop
          where stop.route_id = route.id and stop.region_ko = v_district
        )
      )
  ),
  ranked as (
    select
      shown.*,
      (
        select count(*) from public.route_saves as copy
        where copy.route_id = shown.id
          and copy.approved_at = shown.listing_decided_at
          and copy.created_at >= ((p_today - 30)::timestamp at time zone 'Asia/Seoul')
      ) as copy_count_30d
    from shown
    where shown.first_shared_day is not null
  ),
  ordered as (
    select
      ranked.*,
      case
        when v_sort = 'popular' then
          row_number() over (order by ranked.copy_count_30d desc, ranked.listing_decided_at desc, ranked.id)
        else
          row_number() over (order by ranked.listing_decided_at desc, ranked.id)
      end as ordinal
    from ranked
  ),
  page as (
    select * from ordered order by ordinal offset v_offset limit v_limit + 1
  )
  select coalesce(jsonb_agg(route_json order by ordinal), '[]'::jsonb)
  into v_rows
  from (
    select
      page.ordinal,
      jsonb_build_object(
        'id', page.id,
        'name', page.name,
        'stop_count', (select count(*) from public.personal_route_stops as stop where stop.route_id = page.id),
        'first_district_ko', first_stop.region_ko,
        'first_district_en', first_stop.region_en,
        'last_district_ko', last_stop.region_ko,
        'last_district_en', last_stop.region_en,
        'author_display_name', coalesce(page.listing_author_name, nullif(btrim(profile.display_name), ''), ''),
        'is_editor', content_private.is_active_route_editor(page.owner),
        'copy_count_30d', page.copy_count_30d,
        'first_shared_day', page.first_shared_day,
        'listing_decided_at', page.listing_decided_at,
        'cover_urls', (
          select coalesce(jsonb_agg(cover.url order by cover.first_position), '[]'::jsonb)
          from (
            select covered.url, min(covered.position) as first_position
            from (
              select stop.position, btrim(catalogue.cover_image_url) as url
              from public.personal_route_stops as stop
              join public.exhibition_catalog_v2 as catalogue on catalogue.id = stop.exhibition_id
              where stop.route_id = page.id and nullif(btrim(catalogue.cover_image_url), '') is not null
            ) as covered
            group by covered.url
            order by first_position
            limit 3
          ) as cover
        )
      ) as route_json
    from page
    left join public.profiles as profile on profile.id = page.owner
    left join lateral (
      select stop.region_ko, stop.region_en from public.personal_route_stops as stop
      where stop.route_id = page.id order by stop.position asc limit 1
    ) as first_stop on true
    left join lateral (
      select stop.region_ko, stop.region_en from public.personal_route_stops as stop
      where stop.route_id = page.id order by stop.position desc limit 1
    ) as last_stop on true
  ) as rows;

  -- One extra row was read to learn whether another page follows; it is not returned.
  return jsonb_build_object(
    'routes',
    case
      when jsonb_array_length(v_rows) > v_limit then v_rows - v_limit
      else v_rows
    end,
    'has_more',
    jsonb_array_length(v_rows) > v_limit
  );
end;
$$;

comment on function content_private.browse_public_routes_impl(text, text, integer, integer, date) is
  'Spec 092 browse page of listed routes for a Seoul date: 추천 동선 eligibility, popular or newest order, optional district (region_ko), offset paging with has_more, and up to three stop covers.';

-- Districts that at least one shown route passes through, each route counted once per district.
create or replace function content_private.list_public_route_districts_impl(p_today date)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    jsonb_agg(
      jsonb_build_object('district_ko', district.region_ko, 'district_en', district.region_en, 'route_count', district.route_count)
      order by district.route_count desc, district.region_ko
    ),
    '[]'::jsonb
  )
  from (
    select stop.region_ko, min(stop.region_en) as region_en, count(distinct stop.route_id) as route_count
    from public.personal_routes as route
    join public.personal_route_stops as stop on stop.route_id = route.id
    where route.listing_state = 'approved' and route.is_published and route.revoked_at is null
      and btrim(stop.region_ko) <> ''
      and content_private.route_first_shared_day(route.id, p_today) is not null
    group by stop.region_ko
  ) as district;
$$;

comment on function content_private.list_public_route_districts_impl(date) is
  'Spec 092 district chips: each district (region_ko) some listed route passes through on a Seoul date, with its English name and route count.';

-- Readers without an account use these; anon has no access to content_private, so they are definer-rights here.
create or replace function public.browse_public_routes(
  p_sort text default 'popular',
  p_district text default null,
  p_limit integer default 20,
  p_offset integer default 0
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select content_private.browse_public_routes_impl(
    p_sort, p_district, p_limit, p_offset, (now() at time zone 'Asia/Seoul')::date
  );
$$;

comment on function public.browse_public_routes(text, text, integer, integer) is
  'Spec 092 browse page of listed routes, today in Seoul; see content_private.browse_public_routes_impl.';

create or replace function public.list_public_route_districts()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$ select content_private.list_public_route_districts_impl((now() at time zone 'Asia/Seoul')::date); $$;

comment on function public.list_public_route_districts() is
  'Spec 092 district chips for the browse screen, today in Seoul.';

revoke all on function content_private.browse_public_routes_impl(text, text, integer, integer, date)
  from public, anon, authenticated;
revoke all on function content_private.list_public_route_districts_impl(date) from public, anon, authenticated;
revoke all on function public.browse_public_routes(text, text, integer, integer) from public;
revoke all on function public.list_public_route_districts() from public;
grant execute on function public.browse_public_routes(text, text, integer, integer) to anon, authenticated;
grant execute on function public.list_public_route_districts() to anon, authenticated;

commit;
