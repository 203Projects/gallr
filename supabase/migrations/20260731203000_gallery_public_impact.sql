begin;

create table content.exhibition_daily_metrics (
  exhibition_id text not null
    references content.exhibitions(id) on delete cascade,
  metric_date date not null,
  page_loads bigint not null default 0,
  updated_at timestamptz not null default now(),
  primary key (exhibition_id, metric_date),
  constraint exhibition_daily_metrics_page_loads_nonnegative
    check (page_loads >= 0)
);

comment on table content.exhibition_daily_metrics is
  'Aggregate public exhibition detail-page loads by UTC day; contains no visitor identifiers.';
comment on column content.exhibition_daily_metrics.page_loads is
  'Directional page loads, not unique visitors or billing-grade impressions.';

alter table content.exhibition_daily_metrics enable row level security;

revoke all on table content.exhibition_daily_metrics from public, anon, authenticated;

create or replace function content_private.record_exhibition_page_load_impl(
  p_exhibition_id text
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_rows bigint := 0;
begin
  if p_exhibition_id is null
     or p_exhibition_id <> btrim(p_exhibition_id)
     or length(p_exhibition_id) < 1
     or length(p_exhibition_id) > 128 then
    return false;
  end if;

  insert into content.exhibition_daily_metrics (
    exhibition_id,
    metric_date,
    page_loads,
    updated_at
  )
  select
    exhibition.id,
    (now() at time zone 'UTC')::date,
    1,
    now()
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  where exhibition.id = p_exhibition_id
    and exhibition.published_version_id is not null
    and exhibition.archived_at is null
    and version.status = 'published'::content.exhibition_version_status
  on conflict (exhibition_id, metric_date) do update
  set page_loads = content.exhibition_daily_metrics.page_loads + 1,
      updated_at = now();

  get diagnostics v_rows = row_count;
  return v_rows = 1;
end;
$$;

create or replace function public.record_exhibition_page_load(
  p_exhibition_id text
)
returns boolean
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.record_exhibition_page_load_impl(p_exhibition_id);
$$;

revoke all on function content_private.record_exhibition_page_load_impl(text)
  from public, anon, authenticated;
revoke all on function public.record_exhibition_page_load(text)
  from public, anon, authenticated;
grant execute on function content_private.record_exhibition_page_load_impl(text)
  to service_role;
grant execute on function public.record_exhibition_page_load(text)
  to service_role;

create or replace function content_private.owner_exhibition_json(
  p_exhibition_id text,
  p_version_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', exhibition.id,
    'working_version_id', version.id,
    'version_number', version.version_number,
    'revision', version.revision,
    'owner_status', exhibition.owner_status::text,
    'review_notes', coalesce(exhibition.owner_review_notes, ''),
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'city_ko', version.city_ko,
    'city_en', version.city_en,
    'region_ko', version.region_ko,
    'region_en', version.region_en,
    'address_ko', version.address_ko,
    'address_en', version.address_en,
    'opening_date', coalesce(to_char(version.opening_date, 'YYYY-MM-DD'), ''),
    'closing_date', coalesce(to_char(version.closing_date, 'YYYY-MM-DD'), ''),
    'description_ko', version.description_ko,
    'description_en', version.description_en,
    'hours', coalesce(version.hours, ''),
    'contact', coalesce(version.contact, ''),
    'reception_date', coalesce(
      to_char(version.reception_date at time zone 'Asia/Seoul', 'YYYY-MM-DD'),
      ''
    ),
    'reception_start_time', coalesce(version.opening_time, ''),
    'ticket_url', coalesce(version.ticket_url, ''),
    'updated_at', greatest(exhibition.updated_at, version.updated_at),
    'page_loads_30d', case
      when exhibition.owner_status = 'published'::content.owner_exhibition_status
        and exhibition.archived_at is null then impact.page_loads_30d
      else 0
    end,
    'page_loads_all_time', case
      when exhibition.owner_status = 'published'::content.owner_exhibition_status
        and exhibition.archived_at is null then impact.page_loads_all_time
      else 0
    end,
    'cover', cover.payload
  )
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = p_version_id
  left join lateral (
    select
      coalesce(sum(metric.page_loads) filter (
        where metric.metric_date >= (now() at time zone 'UTC')::date - 29
      ), 0)::bigint as page_loads_30d,
      coalesce(sum(metric.page_loads), 0)::bigint as page_loads_all_time
    from content.exhibition_daily_metrics as metric
    where metric.exhibition_id = exhibition.id
  ) as impact on true
  left join lateral (
    select jsonb_build_object(
      'asset_id', asset.id,
      'status', asset.status::text,
      'bucket_id', asset.bucket_id,
      'object_path', asset.object_path,
      'public_url', asset.public_url,
      'mime_type', coalesce(asset.mime_type, ''),
      'byte_size', coalesce(asset.byte_size, 0),
      'original_filename', coalesce(asset.metadata ->> 'original_filename', '')
    ) as payload
    from content.exhibition_version_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.version_id = version.id
      and attachment.role = 'cover'::content.media_role
    order by attachment.created_at desc, attachment.media_id
    limit 1
  ) as cover on true;
$$;

revoke all on function content_private.owner_exhibition_json(text, uuid)
  from public, anon, authenticated, service_role;

commit;
