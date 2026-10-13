-- Spec 091 FR-019: 지금 가장 많이 본 on the Home tab. The aggregate mobile analytics already count exhibition opens
-- per day with no visitor identity; this read exposes only an ordering of published exhibition ids over a week or
-- more, never the counts.
--
-- The aggregate cannot tell one visitor from many, so the ranking limits what a single burst of opens can do: a
-- day counts for at most twenty opens per exhibition, an exhibition must have been opened on two different days,
-- and the window is never shorter than seven days, which also keeps one day's activity from being read out.
begin;

create or replace function public.list_most_viewed_exhibition_ids(
  p_days integer default 7,
  p_limit integer default 12
)
returns setof text
language sql
stable
security definer
set search_path = ''
as $$
  with opens_by_day as (
    select daily.exhibition_id, daily.occurred_on, sum(daily.event_count) as opens
    from content.mobile_analytics_daily as daily
    where daily.event_name = 'exhibition_opened'
      and daily.exhibition_id <> ''
      and daily.occurred_on > (now() at time zone 'Asia/Seoul')::date - least(greatest(coalesce(p_days, 7), 7), 30)
      and daily.occurred_on <= (now() at time zone 'Asia/Seoul')::date
    group by daily.exhibition_id, daily.occurred_on
  )
  select opens_by_day.exhibition_id
  from opens_by_day
  where exists (
    select 1
    from public.exhibition_catalog_v2 as published
    where published.id = opens_by_day.exhibition_id
  )
  group by opens_by_day.exhibition_id
  having sum(least(opens_by_day.opens, 20)) >= 3
    and count(*) >= 2
  order by sum(least(opens_by_day.opens, 20)) desc, opens_by_day.exhibition_id
  limit least(greatest(coalesce(p_limit, 12), 1), 50);
$$;

revoke all on function public.list_most_viewed_exhibition_ids(integer, integer) from public;
grant execute on function public.list_most_viewed_exhibition_ids(integer, integer) to anon, authenticated;

comment on function public.list_most_viewed_exhibition_ids(integer, integer) is
  'Spec 091 most viewed: published exhibition ids ordered by opens over the last p_days Seoul days (7 to 30), ids only. A day counts for at most twenty opens; an exhibition needs three counted opens on two or more days. At most p_limit rows (1 to 50).';

commit;
