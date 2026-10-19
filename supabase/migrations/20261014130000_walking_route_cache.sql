-- Spec 093 walking routes: a shared venue-to-venue leg cache and the quotas of the walking-route Edge Function
-- (contracts/walking-route.md, "Database objects"; research R8).
--
-- A position key is round(lat, 5) || ',' || round(lng, 5), always with five decimals. Rows are undirected: the
-- smaller key (bytewise, collation "C", as the function compares) comes first and the caller reverses the path for
-- the other direction. A pair is written only when both ends name an exhibition whose current catalogue
-- coordinates give exactly those keys, so the table can never hold a position that is not a venue.
--
-- Quotas are fixed windows: 30 provider calls a minute and 1,500 a Seoul day across the project, and 120 legs an
-- hour per source (an HMAC of the ingress address computed by the function). Expired windows are pruned on every
-- call. Everything here is reached only through the three service_role functions.
begin;

create table if not exists content_private.walking_legs (
  from_key text collate "C" not null,
  to_key text collate "C" not null,
  distance_m integer not null,
  polyline text not null,
  provider text not null,
  routed_at timestamptz not null default now(),
  primary key (from_key, to_key),
  constraint walking_legs_key_order check (from_key < to_key),
  constraint walking_legs_key_format check (
    from_key ~ '^[0-9]{2}\.[0-9]{5},[0-9]{3}\.[0-9]{5}$'
    and to_key ~ '^[0-9]{2}\.[0-9]{5},[0-9]{3}\.[0-9]{5}$'
  ),
  constraint walking_legs_distance check (distance_m between 0 and 1000000),
  constraint walking_legs_polyline check (char_length(polyline) between 1 and 12000),
  constraint walking_legs_provider check (provider in ('ors', 'osrm'))
);

comment on table content_private.walking_legs is
  'Spec 093 walking path between two catalogue venues, keyed by five-decimal positions, smaller key first. Written only by service_walking_legs_store after both ends match exhibition_catalog_v2.';
comment on column content_private.walking_legs.polyline is
  'Encoded polyline (precision 5, latitude first) from from_key to to_key, at most 500 points.';
comment on column content_private.walking_legs.routed_at is
  'When the provider produced the path; rows older than 365 days are routed again when the provider budget allows.';

create table if not exists content_private.walking_route_quota (
  bucket text collate "C" not null,
  window_start timestamptz not null,
  count integer not null,
  primary key (bucket, window_start),
  constraint walking_route_quota_bucket check (
    bucket in ('provider:minute', 'provider:day') or bucket ~ '^source:[0-9a-f]{64}$'
  ),
  constraint walking_route_quota_count check (count between 0 and 1500)
);

comment on table content_private.walking_route_quota is
  'Spec 093 fixed-window counters: provider:minute (30), provider:day (1,500, Seoul day) and source:<hmac> (120 legs an hour).';

create index if not exists walking_route_quota_window_start_idx
  on content_private.walking_route_quota (window_start);

alter table content_private.walking_legs enable row level security;
alter table content_private.walking_route_quota enable row level security;
revoke all on table content_private.walking_legs from public, anon, authenticated, service_role;
revoke all on table content_private.walking_route_quota from public, anon, authenticated, service_role;

create or replace function content_private.walking_position_key(
  p_latitude double precision,
  p_longitude double precision
)
returns text
language sql
immutable
set search_path = ''
as $$
  select pg_catalog.round(p_latitude::numeric, 5)::text || ',' || pg_catalog.round(p_longitude::numeric, 5)::text;
$$;

comment on function content_private.walking_position_key(double precision, double precision) is
  'Spec 093 position key. A double is read with 15 significant digits, then rounded half away from zero to five decimals; the Edge Function mirrors this.';

-- p_pairs: [{"from_key": text, "to_key": text}, ...] (at most 10), keys already ordered by the caller.
-- Returns [{"from_key", "to_key", "distance_m", "polyline", "provider", "routed_at"}, ...] for exact matches only.
create or replace function public.service_walking_legs_lookup(p_pairs jsonb)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if p_pairs is null or jsonb_typeof(p_pairs) <> 'array' or jsonb_array_length(p_pairs) > 10 then
    raise exception using errcode = '22023', message = 'walking_legs_lookup_invalid';
  end if;

  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'from_key', leg.from_key,
      'to_key', leg.to_key,
      'distance_m', leg.distance_m,
      'polyline', leg.polyline,
      'provider', leg.provider,
      'routed_at', leg.routed_at
    ) order by leg.from_key, leg.to_key)
    from content_private.walking_legs as leg
    where exists (
      select 1
      from jsonb_array_elements(p_pairs) as pair(value)
      where jsonb_typeof(pair.value) = 'object'
        and leg.from_key = (pair.value ->> 'from_key')
        and leg.to_key = (pair.value ->> 'to_key')
    )
  ), '[]'::jsonb);
end;
$$;

-- p_legs: [{"from_key", "from_exhibition_id", "to_key", "to_exhibition_id", "distance_m", "polyline",
-- "provider"}, ...] (at most 10), from_key < to_key and the polyline drawn from from_key to to_key.
-- Inserts or refreshes each verified pair and returns how many were written; any other pair is skipped silently.
create or replace function public.service_walking_legs_store(p_legs jsonb)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_stored integer := 0;
begin
  if p_legs is null or jsonb_typeof(p_legs) <> 'array' or jsonb_array_length(p_legs) > 10 then
    raise exception using errcode = '22023', message = 'walking_legs_store_invalid';
  end if;

  with candidate as (
    select
      case when jsonb_typeof(element.value -> 'from_key') = 'string'
        then element.value ->> 'from_key' end collate "C" as from_key,
      case when jsonb_typeof(element.value -> 'to_key') = 'string'
        then element.value ->> 'to_key' end collate "C" as to_key,
      case when jsonb_typeof(element.value -> 'from_exhibition_id') = 'string'
        then element.value ->> 'from_exhibition_id' end as from_exhibition_id,
      case when jsonb_typeof(element.value -> 'to_exhibition_id') = 'string'
        then element.value ->> 'to_exhibition_id' end as to_exhibition_id,
      case when jsonb_typeof(element.value -> 'distance_m') = 'number'
        then (element.value ->> 'distance_m')::numeric end as distance_m,
      case when jsonb_typeof(element.value -> 'polyline') = 'string'
        then element.value ->> 'polyline' end as polyline,
      case when jsonb_typeof(element.value -> 'provider') = 'string'
        then element.value ->> 'provider' end as provider
    from jsonb_array_elements(p_legs) as element(value)
    where jsonb_typeof(element.value) = 'object'
  ),
  verified as (
    select distinct on (candidate.from_key, candidate.to_key)
      candidate.from_key,
      candidate.to_key,
      candidate.distance_m::integer as distance_m,
      candidate.polyline,
      candidate.provider
    from candidate
    join public.exhibition_catalog_v2 as from_venue
      on from_venue.id = candidate.from_exhibition_id
      and from_venue.latitude is not null
      and from_venue.longitude is not null
      and content_private.walking_position_key(from_venue.latitude, from_venue.longitude) = candidate.from_key
    join public.exhibition_catalog_v2 as to_venue
      on to_venue.id = candidate.to_exhibition_id
      and to_venue.latitude is not null
      and to_venue.longitude is not null
      and content_private.walking_position_key(to_venue.latitude, to_venue.longitude) = candidate.to_key
    where candidate.from_key < candidate.to_key
      and candidate.from_key ~ '^[0-9]{2}\.[0-9]{5},[0-9]{3}\.[0-9]{5}$'
      and candidate.to_key ~ '^[0-9]{2}\.[0-9]{5},[0-9]{3}\.[0-9]{5}$'
      and candidate.distance_m between 0 and 1000000
      and candidate.distance_m = trunc(candidate.distance_m)
      and char_length(candidate.polyline) between 1 and 12000
      and candidate.provider in ('ors', 'osrm')
    order by candidate.from_key, candidate.to_key
  )
  insert into content_private.walking_legs as leg (from_key, to_key, distance_m, polyline, provider, routed_at)
  select verified.from_key, verified.to_key, verified.distance_m, verified.polyline, verified.provider, now()
  from verified
  on conflict (from_key, to_key) do update set
    distance_m = excluded.distance_m,
    polyline = excluded.polyline,
    provider = excluded.provider,
    routed_at = excluded.routed_at;

  get diagnostics v_stored = row_count;
  return v_stored;
end;
$$;

-- p_source: 64 lowercase hex characters (HMAC of the ingress address), required when p_legs > 0.
-- p_legs: legs in this request (0-5), counted against the source's hourly quota of 120, all or nothing.
-- p_provider_calls: provider calls wanted (0-5); granted up to what is left of the minute and Seoul-day budgets.
-- Returns {"allowed": bool, "retry_after_seconds": int, "provider_calls_granted": int}. A refused source
-- (allowed false, retry_after_seconds 1-3600) is granted no provider call and nothing is counted.
create or replace function public.service_walking_route_consume(
  p_source text,
  p_legs integer,
  p_provider_calls integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := now();
  v_minute timestamptz := date_trunc('minute', v_now);
  v_hour timestamptz := date_trunc('hour', v_now);
  v_day timestamptz := date_trunc('day', v_now at time zone 'Asia/Seoul') at time zone 'Asia/Seoul';
  v_source_count integer;
  v_minute_count integer := 0;
  v_day_count integer := 0;
  v_granted integer := 0;
begin
  if p_legs is null or p_legs < 0 or p_legs > 5
     or p_provider_calls is null or p_provider_calls < 0 or p_provider_calls > 5
     or (p_legs > 0 and (p_source is null or p_source !~ '^[0-9a-f]{64}$')) then
    raise exception using errcode = '22023', message = 'walking_route_quota_invalid';
  end if;

  delete from content_private.walking_route_quota as quota
  where (quota.bucket = 'provider:minute' and quota.window_start < v_minute)
     or (quota.bucket = 'provider:day' and quota.window_start < v_day)
     or (quota.bucket like 'source:%' and quota.window_start < v_hour);

  if p_legs > 0 then
    insert into content_private.walking_route_quota as quota (bucket, window_start, count)
    values ('source:' || p_source, v_hour, p_legs)
    on conflict (bucket, window_start) do update set count = quota.count + excluded.count
    where quota.count + excluded.count <= 120
    returning quota.count into v_source_count;

    if v_source_count is null then
      return jsonb_build_object(
        'allowed', false,
        'retry_after_seconds', greatest(1, least(3600,
          ceil(extract(epoch from (v_hour + interval '1 hour' - v_now)))::integer)),
        'provider_calls_granted', 0
      );
    end if;
  end if;

  if p_provider_calls > 0 then
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('walking-route:provider-budget', 0));

    select
      coalesce(sum(quota.count) filter (where quota.bucket = 'provider:minute' and quota.window_start = v_minute), 0),
      coalesce(sum(quota.count) filter (where quota.bucket = 'provider:day' and quota.window_start = v_day), 0)
    into v_minute_count, v_day_count
    from content_private.walking_route_quota as quota
    where quota.bucket in ('provider:minute', 'provider:day');

    v_granted := greatest(0, least(p_provider_calls, 30 - v_minute_count, 1500 - v_day_count));

    if v_granted > 0 then
      insert into content_private.walking_route_quota as quota (bucket, window_start, count)
      values ('provider:minute', v_minute, v_granted), ('provider:day', v_day, v_granted)
      on conflict (bucket, window_start) do update set count = quota.count + excluded.count;
    end if;
  end if;

  return jsonb_build_object('allowed', true, 'retry_after_seconds', 0, 'provider_calls_granted', v_granted);
end;
$$;

comment on function public.service_walking_legs_lookup(jsonb) is
  'Spec 093: cached walking legs for exact ordered position-key pairs (at most 10). Service role only.';
comment on function public.service_walking_legs_store(jsonb) is
  'Spec 093: insert or refresh walking legs whose both ends match current catalogue coordinates; others are skipped. Returns rows written. Service role only.';
comment on function public.service_walking_route_consume(text, integer, integer) is
  'Spec 093: atomic quota check. Source: 120 legs an hour, all or nothing. Provider: up to 30 calls a minute and 1,500 a Seoul day, granted partially. Service role only.';

revoke all on function content_private.walking_position_key(double precision, double precision)
  from public, anon, authenticated;
revoke all on function public.service_walking_legs_lookup(jsonb) from public, anon, authenticated;
revoke all on function public.service_walking_legs_store(jsonb) from public, anon, authenticated;
revoke all on function public.service_walking_route_consume(text, integer, integer) from public, anon, authenticated;
grant execute on function public.service_walking_legs_lookup(jsonb) to service_role;
grant execute on function public.service_walking_legs_store(jsonb) to service_role;
grant execute on function public.service_walking_route_consume(text, integer, integer) to service_role;

commit;
