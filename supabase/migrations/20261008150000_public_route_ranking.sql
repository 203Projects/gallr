-- Spec 089 public routes (US9, US10): copies, reports, and the ranked public list.
--
-- A route is shown when it is approved, published and unrevoked, every stop's exhibition is still in the catalogue,
-- and there is a day on which every stop is running: max(today, latest opening) <= earliest closing, in Seoul dates.
-- Ranking counts copies made in the last 30 days on the approved version (approved_at = listing_decided_at), newer
-- approval first on ties. Reports never hide a route; staff decide (20261008160000).
begin;

create table if not exists public.route_saves (
  route_id uuid not null references public.personal_routes(id) on delete cascade,
  account_id uuid not null references auth.users(id) on delete cascade,
  approved_at timestamptz not null,
  created_at timestamptz not null default now(),
  primary key (route_id, account_id, approved_at)
);

comment on table public.route_saves is
  'Spec 089 copies of listed routes, one per account per approved version; the ranking signal.';

create index if not exists route_saves_ranking_idx on public.route_saves (route_id, approved_at, created_at);

create table if not exists public.route_reports (
  id uuid primary key default gen_random_uuid(),
  route_id uuid not null references public.personal_routes(id) on delete cascade,
  account_id uuid not null references auth.users(id) on delete cascade,
  reason text not null,
  created_at timestamptz not null default now(),
  resolved_at timestamptz,
  resolution text,
  constraint route_reports_reason check (reason in ('inappropriate', 'promotional', 'wrong_information', 'other')),
  constraint route_reports_resolution check (
    (resolved_at is null) = (resolution is null)
    and (resolution is null or resolution in ('dismissed', 'upheld'))
  )
);

comment on table public.route_reports is
  'Spec 089 reader reports on listed routes; staff dismiss or uphold them, they never hide a route by themselves.';

create unique index if not exists route_reports_one_open_per_account
  on public.route_reports (route_id, account_id) where resolved_at is null;

alter table public.route_saves enable row level security;
alter table public.route_reports enable row level security;
revoke all on public.route_saves from public, anon, authenticated;
revoke all on public.route_reports from public, anon, authenticated;
grant select on public.route_saves to service_role;
grant select on public.route_reports to service_role;

-- The first day every stop is running, or null when the route is not shown on or after p_today.
create or replace function content_private.route_first_shared_day(p_route_id uuid, p_today date)
returns date
language sql
stable
security definer
set search_path = ''
as $$
  select case
    when bool_and(catalogue.id is not null)
      and greatest(p_today, max(catalogue.opening_date)) <= min(catalogue.closing_date)
    then greatest(p_today, max(catalogue.opening_date))
  end
  from public.personal_routes as route
  join public.personal_route_stops as stop on stop.route_id = route.id
  left join public.exhibition_catalog_v2 as catalogue on catalogue.id = stop.exhibition_id
  where route.id = p_route_id
    and route.listing_state = 'approved'
    and route.is_published
    and route.revoked_at is null
  group by route.id;
$$;

create or replace function content_private.list_public_routes_impl(p_limit integer, p_today date)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  with shown as (
    select route.*, content_private.route_first_shared_day(route.id, p_today) as first_shared_day
    from public.personal_routes as route
    where route.listing_state = 'approved' and route.is_published and route.revoked_at is null
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
  )
  select coalesce(jsonb_agg(route_json order by ordinal), '[]'::jsonb)
  from (
    select
      row_number() over (order by ranked.copy_count_30d desc, ranked.listing_decided_at desc, ranked.id) as ordinal,
      jsonb_build_object(
        'id', ranked.id,
        'name', ranked.name,
        'stop_count', (select count(*) from public.personal_route_stops as stop where stop.route_id = ranked.id),
        'first_district_ko', first_stop.region_ko,
        'first_district_en', first_stop.region_en,
        'last_district_ko', last_stop.region_ko,
        'last_district_en', last_stop.region_en,
        'author_display_name', coalesce(nullif(btrim(profile.display_name), ''), ''),
        'is_editor', content_private.is_active_route_editor(ranked.owner),
        'copy_count_30d', ranked.copy_count_30d,
        'first_shared_day', ranked.first_shared_day,
        'listing_decided_at', ranked.listing_decided_at
      ) as route_json
    from ranked
    left join public.profiles as profile on profile.id = ranked.owner
    left join lateral (
      select stop.region_ko, stop.region_en from public.personal_route_stops as stop
      where stop.route_id = ranked.id order by stop.position asc limit 1
    ) as first_stop on true
    left join lateral (
      select stop.region_ko, stop.region_en from public.personal_route_stops as stop
      where stop.route_id = ranked.id order by stop.position desc limit 1
    ) as last_stop on true
    order by ordinal
    limit greatest(1, least(coalesce(p_limit, 10), 10))
  ) as limited;
$$;

-- Readers without an account use this; anon has no access to content_private, so it is definer-rights here.
create or replace function public.list_public_routes(p_limit integer default 10)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$ select content_private.list_public_routes_impl(p_limit, (now() at time zone 'Asia/Seoul')::date); $$;

create or replace function content_private.save_public_route_impl(p_id uuid)
returns jsonb
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
  select * into v_route from public.personal_routes where id = p_id;
  if not found or content_private.route_first_shared_day(p_id, (now() at time zone 'Asia/Seoul')::date) is null then
    raise exception using errcode = '55000', message = 'route_not_listed';
  end if;
  if v_route.owner <> v_actor then
    insert into public.route_saves (route_id, account_id, approved_at)
    values (p_id, v_actor, v_route.listing_decided_at)
    on conflict do nothing;
  end if;
  return public.get_published_route(p_id);
end;
$$;

create or replace function content_private.report_route_impl(p_id uuid, p_reason text)
returns void
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
  if p_reason is null or p_reason not in ('inappropriate', 'promotional', 'wrong_information', 'other') then
    raise exception using errcode = '22023', message = 'route_listing_invalid_reason';
  end if;
  select * into v_route from public.personal_routes where id = p_id;
  if not found or content_private.route_first_shared_day(p_id, (now() at time zone 'Asia/Seoul')::date) is null then
    raise exception using errcode = '55000', message = 'route_not_listed';
  end if;
  if v_route.owner = v_actor then
    raise exception using errcode = '42501', message = 'route_report_own_route';
  end if;
  insert into public.route_reports (route_id, account_id, reason) values (p_id, v_actor, p_reason);
exception
  when unique_violation then
    raise exception using errcode = '23505', message = 'route_report_exists';
end;
$$;

revoke all on function content_private.route_first_shared_day(uuid, date) from public, anon, authenticated;
revoke all on function content_private.list_public_routes_impl(integer, date) from public, anon, authenticated;
revoke all on function content_private.save_public_route_impl(uuid) from public, anon, authenticated;
revoke all on function content_private.report_route_impl(uuid, text) from public, anon, authenticated;
grant execute on function content_private.save_public_route_impl(uuid) to authenticated;
grant execute on function content_private.report_route_impl(uuid, text) to authenticated;

create or replace function public.save_public_route(p_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.save_public_route_impl(p_id); $$;

create or replace function public.report_route(p_id uuid, p_reason text)
returns void language sql volatile security invoker set search_path = ''
as $$ select content_private.report_route_impl(p_id, p_reason); $$;

revoke all on function public.list_public_routes(integer) from public;
revoke all on function public.save_public_route(uuid) from public, anon, authenticated;
revoke all on function public.report_route(uuid, text) from public, anon, authenticated;
grant execute on function public.list_public_routes(integer) to anon, authenticated;
grant execute on function public.save_public_route(uuid) to authenticated;
grant execute on function public.report_route(uuid, text) to authenticated;

commit;
