-- Let staff reuse location snapshots already entered for earlier exhibitions.
-- Canonical content.venues rows win when available; otherwise the most recent
-- exhibition version for the same normalized name/location supplies defaults.
-- Selecting one still copies a snapshot into the working exhibition version,
-- so later venue edits cannot rewrite published history.

create or replace function content_private.admin_get_exhibition_lookups_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_venues jsonb;
  v_events jsonb;
  v_editors jsonb;
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );

  with venue_candidates as (
    select
      'venue:' || venue.id::text as id,
      venue.name_ko,
      venue.name_en,
      venue.city_ko,
      venue.city_en,
      venue.region_ko,
      venue.region_en,
      venue.address_ko,
      venue.address_en,
      coalesce(venue.latitude::text, '') as latitude,
      coalesce(venue.longitude::text, '') as longitude,
      0 as source_rank,
      venue.updated_at as source_updated_at
    from content.venues as venue
    where venue.archived_at is null

    union all

    select
      'history:' || version.id::text as id,
      version.venue_name_ko as name_ko,
      version.venue_name_en as name_en,
      version.city_ko,
      version.city_en,
      version.region_ko,
      version.region_en,
      version.address_ko,
      version.address_en,
      coalesce(version.latitude::text, '') as latitude,
      coalesce(version.longitude::text, '') as longitude,
      case
        when version.status = 'published'::content.exhibition_version_status
          then 1
        else 2
      end as source_rank,
      version.updated_at as source_updated_at
    from content.exhibition_versions as version
    where nullif(btrim(version.venue_name_ko), '') is not null
  ),
  ranked_venues as (
    select
      candidate.*,
      row_number() over (
        partition by
          lower(regexp_replace(btrim(candidate.name_ko), '[[:space:]]+', ' ', 'g')),
          lower(
            regexp_replace(
              btrim(
                coalesce(
                  nullif(candidate.address_ko, ''),
                  candidate.city_ko || ' ' || candidate.region_ko
                )
              ),
              '[[:space:]]+',
              ' ',
              'g'
            )
          )
        order by
          candidate.source_rank,
          candidate.source_updated_at desc,
          candidate.id
      ) as location_rank
    from venue_candidates as candidate
  )
  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', venue.id,
        'name_ko', venue.name_ko,
        'name_en', venue.name_en,
        'city_ko', venue.city_ko,
        'city_en', venue.city_en,
        'region_ko', venue.region_ko,
        'region_en', venue.region_en,
        'address_ko', venue.address_ko,
        'address_en', venue.address_en,
        'latitude', venue.latitude,
        'longitude', venue.longitude
      )
      order by lower(venue.name_ko), lower(venue.address_ko), venue.id
    ),
    '[]'::jsonb
  )
  into v_venues
  from ranked_venues as venue
  where venue.location_rank = 1;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', event.id,
        'name_ko', event.name_ko,
        'name_en', event.name_en,
        'location_label_ko', event.location_label_ko,
        'location_label_en', event.location_label_en,
        'start_date', to_char(event.start_date, 'YYYY-MM-DD'),
        'end_date', to_char(event.end_date, 'YYYY-MM-DD'),
        'short_label', event.short_label,
        'is_active', event.is_active
      )
      order by event.is_active desc, event.start_date desc, event.id
    ),
    '[]'::jsonb
  )
  into v_events
  from public.events as event;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', editor.id,
        'name_ko', editor.name_ko,
        'name_en', editor.name_en,
        'title_ko', editor.title_ko,
        'title_en', editor.title_en,
        'is_active', editor.is_active,
        'active_from', to_char(editor.active_from, 'YYYY-MM-DD'),
        'active_to', case
          when editor.active_to is null then null
          else to_char(editor.active_to, 'YYYY-MM-DD')
        end
      )
      order by
        (editor.id = 'gallr-editors') desc,
        editor.is_active desc,
        editor.active_from desc,
        editor.id
    ),
    '[]'::jsonb
  )
  into v_editors
  from public.editors as editor;

  return jsonb_build_object(
    'venues', v_venues,
    'events', v_events,
    'editors', v_editors
  );
end;
$$;

revoke all on function content_private.admin_get_exhibition_lookups_impl()
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_get_exhibition_lookups_impl()
  to authenticated;
