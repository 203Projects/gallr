-- Spec 091 FR-019: 지금 가장 많이 본 on the Home tab. The aggregate mobile analytics already count exhibition opens
-- per day with no visitor identity; this read exposes only an ordering of exhibition ids over a short window, never
-- the counts. An exhibition needs a few opens before it is ranked, so the list is not a single visit echoed back.
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
  select daily.exhibition_id
  from content.mobile_analytics_daily as daily
  where daily.event_name = 'exhibition_opened'
    and daily.exhibition_id <> ''
    and daily.occurred_on > (now() at time zone 'Asia/Seoul')::date - least(greatest(coalesce(p_days, 7), 1), 30)
    and daily.occurred_on <= (now() at time zone 'Asia/Seoul')::date
  group by daily.exhibition_id
  having sum(daily.event_count) >= 3
  order by sum(daily.event_count) desc, daily.exhibition_id
  limit least(greatest(coalesce(p_limit, 12), 1), 50);
$$;

revoke all on function public.list_most_viewed_exhibition_ids(integer, integer) from public;
grant execute on function public.list_most_viewed_exhibition_ids(integer, integer) to anon, authenticated;

comment on function public.list_most_viewed_exhibition_ids(integer, integer) is
  'Spec 091 most viewed: exhibition ids ordered by opens over the last p_days Seoul days (1 to 30), ids only, at least three opens each, at most p_limit rows (1 to 50).';

commit;
