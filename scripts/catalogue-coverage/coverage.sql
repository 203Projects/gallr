-- Release coverage for spec 101 (FR-024). Read-only: one SELECT, no writes, no function with side effects.
-- Returns one row with one jsonb column `coverage`; pipe the JSON output into coverage.mjs.
--
--   supabase db query --linked --project-ref <ref> --output-format json \
--     -f scripts/catalogue-coverage/coverage.sql < /dev/null \
--     | node scripts/catalogue-coverage/coverage.mjs --release <version>
--
-- Rows are what readers see (public.exhibition_catalog_v2); term, credit and freshness facts come from each
-- row's published version. "Running and upcoming" means the closing date is today or later in Seoul.
-- Needs the provenance, confirmed-hours and artist-merge migrations (contracts/catalogue-reliability.md).
with params as (
  select
    (now() at time zone 'Asia/Seoul')::date as today,
    now() - interval '7 days' as week_cutoff
),
listed as (
  select
    catalog.id,
    catalog.closing_date,
    catalog.latitude,
    catalog.longitude,
    catalog.description_en,
    catalog.credits_ko,
    catalog.credits_en,
    catalog.hours_weekly,
    version.id as version_id,
    (
      select min(history.published_at)
      from content.exhibition_versions history
      where history.exhibition_id = catalog.id
    ) as first_published_at
  from public.exhibition_catalog_v2 catalog
  left join content.exhibitions exhibition on exhibition.id = catalog.id
  left join content.exhibition_versions version on version.id = exhibition.published_version_id
),
facts as (
  select
    listed.*,
    listed.closing_date >= params.today as running_upcoming,
    coalesce(listed.first_published_at < params.week_cutoff, false) as published_week_ago,
    (
      select count(*)
      from content.exhibition_version_terms version_term
      join content.art_taxonomy_terms term on term.id = version_term.term_id
      where version_term.version_id = listed.version_id
        and term.category::text <> 'visit'
    ) as art_term_count,
    exists (
      select 1 from content.exhibition_version_artists credit
      where credit.version_id = listed.version_id
        and credit.artist_id is not null
    ) as has_linked_artist
  from listed
  cross join params
),
judged as (
  select
    facts.*,
    coalesce(content_private.exhibition_metadata_is_stale(facts.version_id), false) as metadata_stale,
    coalesce(content_private.exhibition_hours_is_stale(facts.version_id), false) as hours_stale
  from facts
),
-- Stale terms do not count as reviewed: the exhibition needs review again (spec 101 US5 scenario 3).
scored as (
  select judged.*, judged.art_term_count >= 3 and not judged.metadata_stale as terms_3plus
  from judged
),
artist_names as (
  select 'ko' as language, content_private.normalize_artist_name(artist.name_ko) as name_key, artist.id
  from content.artists artist
  where artist.archived_at is null
  union all
  select 'en', content_private.normalize_artist_name(artist.name_en), artist.id
  from content.artists artist
  where artist.archived_at is null
),
duplicate_groups as (
  select distinct array_agg(artist_names.id order by artist_names.id) as artist_ids
  from artist_names
  where nullif(artist_names.name_key, '') is not null
  group by artist_names.language, artist_names.name_key
  having count(*) > 1
)
select jsonb_build_object(
  'format_version', 1,
  'today', (select params.today::text from params),
  'running_upcoming', count(*) filter (where running_upcoming),
  'terms_3plus', count(*) filter (where running_upcoming and terms_3plus),
  'published_week_ago', count(*) filter (where running_upcoming and published_week_ago),
  'published_week_ago_terms_3plus',
    count(*) filter (where running_upcoming and published_week_ago and terms_3plus),
  'credits_named',
    count(*) filter (where running_upcoming and btrim(credits_ko || credits_en) <> ''),
  'credits_named_linked',
    count(*) filter (where running_upcoming and btrim(credits_ko || credits_en) <> '' and has_linked_artist),
  'hours_confirmed', count(*) filter (where running_upcoming and hours_weekly is not null),
  'coordinates',
    count(*) filter (where running_upcoming and latitude is not null and longitude is not null),
  'english_description', count(*) filter (where running_upcoming and btrim(description_en) <> ''),
  'metadata_stale', count(*) filter (where running_upcoming and metadata_stale),
  'hours_stale', count(*) filter (where running_upcoming and hours_stale),
  'listed_total', count(*),
  'listed_current_dates', count(*) filter (where running_upcoming),
  'duplicate_artist_groups', (select count(*) from duplicate_groups)
) as coverage
from scored;
