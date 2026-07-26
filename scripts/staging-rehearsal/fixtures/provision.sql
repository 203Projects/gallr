\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on

begin;
set local lock_timeout = '10s';
set local statement_timeout = '10min';
set local idle_in_transaction_session_timeout = '2min';
set local timezone = 'UTC';

create temp table fixture_config (
  staging_ref_sha256 text not null,
  production_ref_sha256 text not null,
  confirmation_ref_sha256 text not null,
  run_id text not null,
  fixture_prefix text not null,
  load_event_id text not null,
  empty_event_id text not null,
  editor_id text not null,
  boundary_id text not null,
  mutation_id text not null,
  media_object_path text not null,
  baseline_canonical_count bigint not null,
  baseline_canonical_id_hash text not null,
  baseline_canonical_catalog_hash text not null,
  baseline_version_count bigint not null,
  baseline_version_catalog_hash text not null,
  baseline_v2_count bigint not null,
  baseline_v2_id_hash text not null,
  baseline_v2_catalog_hash text not null,
  baseline_legacy_count bigint not null,
  baseline_legacy_id_hash text not null,
  baseline_legacy_catalog_hash text not null,
  baseline_event_count bigint not null,
  baseline_event_catalog_hash text not null,
  baseline_editor_count bigint not null,
  baseline_editor_catalog_hash text not null,
  baseline_media_count bigint not null,
  baseline_media_catalog_hash text not null,
  baseline_attachment_count bigint not null,
  baseline_attachment_catalog_hash text not null,
  baseline_curation_count bigint not null,
  baseline_curation_catalog_hash text not null,
  baseline_submission_count bigint not null,
  baseline_submission_catalog_hash text not null,
  baseline_submission_media_count bigint not null,
  baseline_submission_media_catalog_hash text not null,
  baseline_import_row_count bigint not null,
  baseline_import_row_catalog_hash text not null,
  baseline_import_link_count bigint not null,
  baseline_import_link_catalog_hash text not null,
  baseline_audit_count bigint not null
) on commit drop;

insert into fixture_config values (
  :'staging_ref_sha256',
  :'production_ref_sha256',
  :'confirmation_ref_sha256',
  :'run_id',
  :'fixture_prefix',
  :'load_event_id',
  :'empty_event_id',
  :'editor_id',
  :'boundary_id',
  :'mutation_id',
  :'media_object_path',
  :'baseline_canonical_count'::bigint,
  :'baseline_canonical_id_hash',
  :'baseline_canonical_catalog_hash',
  :'baseline_version_count'::bigint,
  :'baseline_version_catalog_hash',
  :'baseline_v2_count'::bigint,
  :'baseline_v2_id_hash',
  :'baseline_v2_catalog_hash',
  :'baseline_legacy_count'::bigint,
  :'baseline_legacy_id_hash',
  :'baseline_legacy_catalog_hash',
  :'baseline_event_count'::bigint,
  :'baseline_event_catalog_hash',
  :'baseline_editor_count'::bigint,
  :'baseline_editor_catalog_hash',
  :'baseline_media_count'::bigint,
  :'baseline_media_catalog_hash',
  :'baseline_attachment_count'::bigint,
  :'baseline_attachment_catalog_hash',
  :'baseline_curation_count'::bigint,
  :'baseline_curation_catalog_hash',
  :'baseline_submission_count'::bigint,
  :'baseline_submission_catalog_hash',
  :'baseline_submission_media_count'::bigint,
  :'baseline_submission_media_catalog_hash',
  :'baseline_import_row_count'::bigint,
  :'baseline_import_row_catalog_hash',
  :'baseline_import_link_count'::bigint,
  :'baseline_import_link_catalog_hash',
  :'baseline_audit_count'::bigint
);

-- Serialize every rehearsal fixture lifecycle, even when run prefixes differ,
-- so the captured global baseline remains meaningful.
do $lock$
begin
  perform pg_catalog.pg_advisory_xact_lock(73243, 1205);
end
$lock$;

do $guard$
declare
  config fixture_config%rowtype;
begin
  select * into strict config from fixture_config;

  if config.staging_ref_sha256 !~ '^[0-9a-f]{64}$'
     or config.production_ref_sha256 !~ '^[0-9a-f]{64}$'
     or config.staging_ref_sha256 = config.production_ref_sha256 then
    raise exception using errcode = '22023', message = 'distinct_staging_ref_required';
  end if;
  if config.confirmation_ref_sha256 <> config.staging_ref_sha256 then
    raise exception using errcode = '42501', message = 'fixture_confirmation_invalid';
  end if;
  if config.run_id !~ '^[a-z0-9][a-z0-9-]{7,31}$'
     or config.fixture_prefix <> 'gallr-rehearsal-' || config.run_id || '-' then
    raise exception using errcode = '22023', message = 'fixture_identity_invalid';
  end if;
  if length(config.boundary_id) > 128 or length(config.mutation_id) > 128 then
    raise exception using errcode = '22023', message = 'fixture_id_too_long';
  end if;

  if to_regclass('content.exhibitions') is null
     or to_regclass('content.exhibition_versions') is null
     or to_regclass('content.media_assets') is null
     or to_regclass('content.exhibition_version_media') is null
     or to_regclass('content.curation_placements') is null
     or to_regclass('content.exhibition_submissions') is null
     or to_regclass('content.submission_media') is null
     or to_regclass('content.legacy_import_rows') is null
     or to_regclass('content.legacy_import_links') is null
     or to_regclass('content.audit_log') is null
     or to_regclass('content_private.exhibition_catalog_runtime') is null
     or to_regclass('public.exhibition_catalog_v2') is null
     or to_regclass('public.exhibitions') is null
     or to_regclass('public.events') is null
     or to_regclass('public.editors') is null
     or to_regprocedure('public.exhibition_catalog_v2_integrity(text,boolean)') is null
     or to_regprocedure('public.exhibition_reader_integrity(text,boolean)') is null
     or to_regprocedure('public.admin_reconcile_exhibition_catalog_v2()') is null then
    raise exception using errcode = '55000', message = 'required_catalog_schema_not_deployed';
  end if;

  if not pg_catalog.has_table_privilege(current_user, 'content.exhibitions', 'INSERT')
     or not pg_catalog.has_table_privilege(current_user, 'content.exhibitions', 'UPDATE')
     or not pg_catalog.has_table_privilege(current_user, 'content.exhibitions', 'DELETE') then
    raise exception using errcode = '42501', message = 'fixture_operator_lacks_canonical_privileges';
  end if;

  if not exists (
    select 1 from pg_catalog.pg_trigger
    where tgrelid = 'content.exhibitions'::regclass
      and tgname = 'exhibition_catalog_v2_exhibition_visibility_update'
      and not tgisinternal
  ) or not exists (
    select 1 from pg_catalog.pg_trigger
    where tgrelid = 'content.exhibition_versions'::regclass
      and tgname = 'exhibition_catalog_v2_version_change'
      and not tgisinternal
  ) then
    raise exception using errcode = '55000', message = 'catalog_projection_triggers_missing';
  end if;

  if not exists (
    select 1 from public.editors where id = 'gallr-editors'
  ) then
    raise exception using errcode = '23503', message = 'house_editor_seed_missing';
  end if;
  if (select count(*) from content_private.exhibition_catalog_runtime
      where singleton) <> 1 then
    raise exception using errcode = '55000', message = 'catalog_runtime_singleton_invalid';
  end if;
end
$guard$;

-- Freeze every tracked/reference table for the short isolated staging load so
-- the baseline comparison and evidence cannot race an unrelated writer.
lock table
  content_private.exhibition_catalog_runtime,
  content.exhibitions,
  content.exhibition_versions,
  content.media_assets,
  content.exhibition_version_media,
  content.curation_placements,
  content.exhibition_submissions,
  content.submission_media,
  content.legacy_import_rows,
  content.legacy_import_links,
  content.audit_log,
  public.events,
  public.editors,
  public.exhibition_catalog_v2,
  public.exhibitions
in share mode;

\ir tracked-state.sql

-- Prove that nothing changed after the shell captured baseline.tsv.
do $baseline_guard$
declare
  config fixture_config%rowtype;
  canonical_count bigint;
  canonical_hash text;
  v2 record;
  legacy record;
  legacy_catalog_hash text;
begin
  select * into strict config from fixture_config;
  select
    count(*)::bigint,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(exhibition.id)::text || ':' || exhibition.id,
              '' order by exhibition.id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    )
  into canonical_count, canonical_hash
  from content.exhibitions as exhibition;
  select * into strict v2 from public.exhibition_catalog_v2_integrity(null, false);
  select * into strict legacy from public.exhibition_reader_integrity(null, false);
  select encode(
    extensions.digest(
      convert_to(
        coalesce(
          string_agg(
            octet_length(convert_to(exhibition.id, 'UTF8'))::text
              || ':' || exhibition.id
              || octet_length(
                convert_to(
                  content_private.legacy_exhibition_catalog_v2_checksum(exhibition),
                  'UTF8'
                )
              )::text
              || ':'
              || content_private.legacy_exhibition_catalog_v2_checksum(exhibition),
            '' order by exhibition.id
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ) into legacy_catalog_hash
  from public.exhibitions as exhibition;

  if canonical_count <> config.baseline_canonical_count
     or canonical_hash <> config.baseline_canonical_id_hash
     or (select row_count from fixture_tracked_state where resource = 'canonical')
          <> config.baseline_canonical_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'canonical')
          <> config.baseline_canonical_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'versions')
          <> config.baseline_version_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'versions')
          <> config.baseline_version_catalog_hash
     or v2.row_count <> config.baseline_v2_count
     or v2.id_checksum_sha256 <> config.baseline_v2_id_hash
     or v2.catalog_checksum_sha256 <> config.baseline_v2_catalog_hash
     or legacy.row_count <> config.baseline_legacy_count
     or legacy.id_checksum_sha256 <> config.baseline_legacy_id_hash
     or legacy_catalog_hash <> config.baseline_legacy_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'events')
          <> config.baseline_event_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'events')
          <> config.baseline_event_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'editors')
          <> config.baseline_editor_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'editors')
          <> config.baseline_editor_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'media')
          <> config.baseline_media_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'media')
          <> config.baseline_media_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'attachments')
          <> config.baseline_attachment_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'attachments')
          <> config.baseline_attachment_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'curations')
          <> config.baseline_curation_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'curations')
          <> config.baseline_curation_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'submissions')
          <> config.baseline_submission_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'submissions')
          <> config.baseline_submission_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'submission_media')
          <> config.baseline_submission_media_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'submission_media')
          <> config.baseline_submission_media_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'import_rows')
          <> config.baseline_import_row_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'import_rows')
          <> config.baseline_import_row_catalog_hash
     or (select row_count from fixture_tracked_state where resource = 'import_links')
          <> config.baseline_import_link_count
     or (select catalog_checksum_sha256 from fixture_tracked_state where resource = 'import_links')
          <> config.baseline_import_link_catalog_hash
     or (select count(*) from content.audit_log) <> config.baseline_audit_count then
    raise exception using errcode = '40001', message = 'staging_baseline_changed_before_provision';
  end if;
end
$baseline_guard$;

create temp table fixture_rows (
  ordinal integer primary key,
  exhibition_id text not null unique,
  version_id uuid not null unique
) on commit drop;

insert into fixture_rows (ordinal, exhibition_id, version_id)
select
  series.ordinal,
  case series.ordinal
    when 500 then config.boundary_id
    when 750 then config.mutation_id
    else config.fixture_prefix || 'catalog-' || lpad(series.ordinal::text, 4, '0')
  end,
  gen_random_uuid()
from fixture_config as config
cross join generate_series(1, 1205) as series(ordinal);

do $collision_guard$
declare
  config fixture_config%rowtype;
begin
  select * into strict config from fixture_config;

  if exists (
    select 1 from content.exhibitions
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from public.exhibition_catalog_v2
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from public.exhibitions
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from public.events
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from public.editors
    where left(id, length(config.fixture_prefix)) = config.fixture_prefix
  ) or exists (
    select 1 from content.media_assets
    where object_path = config.media_object_path
  ) then
    raise exception using errcode = '23505', message = 'fixture_prefix_collision';
  end if;

  if (select count(*) from fixture_rows) <> 1205
     or (select count(distinct exhibition_id) from fixture_rows) <> 1205 then
    raise exception using errcode = '23514', message = 'fixture_id_generation_invalid';
  end if;
end
$collision_guard$;

insert into public.events (
  id,
  name_ko,
  name_en,
  description_ko,
  description_en,
  location_label_ko,
  location_label_en,
  start_date,
  end_date,
  brand_color,
  accent_color,
  ticket_url,
  is_active,
  updated_at,
  short_label
)
select
  config.load_event_id,
  '스테이징 페이지네이션 전용 이벤트',
  'Staging pagination fixture event',
  '1,205개 전시의 PostgREST 전송 검증 전용',
  'Dedicated to the 1,205-row PostgREST transport rehearsal',
  '서울 · 스테이징',
  'Seoul · staging',
  date '2026-01-01',
  date '2027-12-31',
  '#D72670',
  '#102A43',
  'https://fixtures.invalid/tickets?source=staging&kind=load',
  true,
  clock_timestamp(),
  'LOAD,(V2)'
from fixture_config as config
union all
select
  config.empty_event_id,
  '스테이징 빈 결과 전용 이벤트',
  'Staging empty-result fixture event',
  '0건 필터 결과 검증 전용',
  'Dedicated to the zero-row filter rehearsal',
  '서울 · 스테이징',
  'Seoul · staging',
  date '2026-01-01',
  date '2027-12-31',
  '#52606D',
  null,
  null,
  true,
  clock_timestamp(),
  'EMPTY,(V2)'
from fixture_config as config;

insert into public.editors (
  id,
  name_ko,
  name_en,
  title_ko,
  title_en,
  bio_ko,
  bio_en,
  is_active,
  active_from,
  active_to,
  created_at,
  updated_at
)
select
  config.editor_id,
  '스테이징 객원 에디터',
  'Staging Guest Editor',
  '전송 검증 전용',
  'Transport rehearsal only',
  '실서비스 콘텐츠가 아닌 격리된 스테이징 fixture입니다.',
  'An isolated staging fixture, not production editorial content.',
  true,
  date '2026-01-01',
  date '2027-12-31',
  clock_timestamp(),
  clock_timestamp()
from fixture_config as config;

insert into content.exhibitions (
  id,
  created_at,
  updated_at
)
select
  row.exhibition_id,
  timestamptz '2026-01-01 00:00:00+00' + row.ordinal * interval '1 second',
  timestamptz '2026-01-01 00:00:00+00' + row.ordinal * interval '1 second'
from fixture_rows as row
order by row.ordinal;

insert into content.exhibition_versions (
  id,
  exhibition_id,
  version_number,
  revision,
  status,
  event_id,
  editor_id,
  name_ko,
  name_en,
  venue_name_ko,
  venue_name_en,
  city_ko,
  city_en,
  region_ko,
  region_en,
  address_ko,
  address_en,
  opening_date,
  closing_date,
  latitude,
  longitude,
  description_ko,
  description_en,
  hours,
  contact,
  reception_date,
  opening_time,
  ticket_url,
  legacy_cover_image_url,
  is_featured,
  is_homepage_featured,
  published_at,
  created_at,
  updated_at
)
select
  row.version_id,
  row.exhibition_id,
  1,
  1,
  'published'::content.exhibition_version_status,
  config.load_event_id,
  case row.ordinal
    when 3 then 'gallr-editors'
    when 4 then config.editor_id
    else null
  end,
  '스테이징 전시 ' || lpad(row.ordinal::text, 4, '0') || ' · 예약문자 ,():',
  'Staging exhibition ' || lpad(row.ordinal::text, 4, '0') || ' · reserved ,():',
  '검증 갤러리 ' || ((row.ordinal - 1) % 23 + 1)::text,
  'Fixture Gallery ' || ((row.ordinal - 1) % 23 + 1)::text,
  case when row.ordinal % 11 = 0 then '부산' else '서울' end,
  case when row.ordinal % 11 = 0 then 'Busan' else 'Seoul' end,
  case when row.ordinal % 11 = 0 then '부산' else '서울' end,
  case when row.ordinal % 11 = 0 then 'Busan' else 'Seoul' end,
  '대한민국 테스트로 ' || row.ordinal::text,
  row.ordinal::text || ' Fixture-ro, Republic of Korea',
  date '2026-01-01' + ((row.ordinal - 1) % 365),
  date '2026-01-31' + ((row.ordinal - 1) % 365),
  case when row.ordinal % 7 = 0
    then null
    else (37.50 + ((row.ordinal % 100)::double precision / 10000.0))
  end,
  case when row.ordinal % 7 = 0
    then null
    else (127.00 + ((row.ordinal % 100)::double precision / 10000.0))
  end,
  '한글·Unicode와 따옴표 '' 및 쉼표, 괄호()를 포함한 스테이징 설명 ' || row.ordinal::text,
  'Staging description with quote '', comma, parentheses (), and Unicode ' || row.ordinal::text,
  case when row.ordinal % 3 = 0 then '화–일 10:00–18:00' else null end,
  case when row.ordinal % 5 = 0 then 'fixture@example.invalid' else null end,
  case when row.ordinal = 8 then timestamptz '2026-05-01 09:00:00+00' else null end,
  case when row.ordinal = 8 then '18:00' else null end,
  'https://fixtures.invalid/tickets/' || row.ordinal::text || '?ref=staging&lang=ko',
  'https://fixtures.invalid/' || config.fixture_prefix || 'legacy-' ||
    lpad(row.ordinal::text, 4, '0') || '.jpg',
  row.ordinal in (1, 500, 1000, 1205),
  row.ordinal in (2, 501, 1001),
  timestamptz '2026-01-01 00:00:00+00' + row.ordinal * interval '1 second',
  timestamptz '2026-01-01 00:00:00+00' + row.ordinal * interval '1 second',
  timestamptz '2026-01-01 00:00:00+00' + row.ordinal * interval '1 second'
from fixture_rows as row
cross join fixture_config as config
order by row.ordinal;

with asset as (
  insert into content.media_assets (
    id,
    status,
    bucket_id,
    object_path,
    public_url,
    mime_type,
    byte_size,
    width,
    height,
    checksum_sha256,
    metadata,
    created_at,
    updated_at,
    published_at
  )
  select
    gen_random_uuid(),
    'published'::content.media_asset_status,
    'exhibition-media',
    config.media_object_path,
    'https://fixtures.invalid/' || config.fixture_prefix || 'published-cover-0005.webp',
    'image/webp',
    4096,
    1600,
    1200,
    encode(extensions.digest(convert_to(config.media_object_path, 'UTF8'), 'sha256'), 'hex'),
    jsonb_build_object('fixture_run_id', config.run_id, 'bytes_uploaded', false),
    clock_timestamp(),
    clock_timestamp(),
    clock_timestamp()
  from fixture_config as config
  returning id
)
insert into content.exhibition_version_media (
  version_id,
  media_id,
  role,
  sort_order,
  alt_ko,
  alt_en,
  credit,
  rights_url
)
select
  row.version_id,
  asset.id,
  'cover'::content.media_role,
  0,
  '스테이징 공개 미디어 커버',
  'Staging published media cover',
  'gallr staging fixture',
  'https://fixtures.invalid/rights/staging'
from fixture_rows as row
cross join asset
where row.ordinal = 5;

insert into content.curation_placements (
  id,
  surface,
  exhibition_id,
  position,
  enabled,
  created_at,
  updated_at
)
select
  gen_random_uuid(),
  'app_featured'::content.curation_surface,
  row.exhibition_id,
  0,
  true,
  clock_timestamp(),
  clock_timestamp()
from fixture_rows as row
where row.ordinal = 6
union all
select
  gen_random_uuid(),
  'homepage'::content.curation_surface,
  row.exhibition_id,
  0,
  true,
  clock_timestamp(),
  clock_timestamp()
from fixture_rows as row
where row.ordinal = 7;

update content.exhibitions as exhibition
set
  published_version_id = row.version_id,
  updated_at = clock_timestamp()
from fixture_rows as row
where exhibition.id = row.exhibition_id;

do $validation$
declare
  config fixture_config%rowtype;
  load_integrity record;
  empty_integrity record;
  featured_integrity record;
  boundary_position bigint;
  mutation_position bigint;
  reconciliation jsonb;
begin
  select * into strict config from fixture_config;
  select * into strict load_integrity
  from public.exhibition_catalog_v2_integrity(config.load_event_id, false);
  select * into strict empty_integrity
  from public.exhibition_catalog_v2_integrity(config.empty_event_id, false);
  select * into strict featured_integrity
  from public.exhibition_catalog_v2_integrity(config.load_event_id, true);
  select public.admin_reconcile_exhibition_catalog_v2() into strict reconciliation;

  select ordered.position into boundary_position
  from (
    select catalog.id, row_number() over (order by catalog.id) as position
    from public.exhibition_catalog_v2 as catalog
    where catalog.event_id = config.load_event_id
  ) as ordered
  where ordered.id = config.boundary_id;

  select ordered.position into mutation_position
  from (
    select catalog.id, row_number() over (order by catalog.id) as position
    from public.exhibition_catalog_v2 as catalog
    where catalog.event_id = config.load_event_id
  ) as ordered
  where ordered.id = config.mutation_id;

  if (select count(*) from content.exhibitions as exhibition
      join fixture_rows as row on row.exhibition_id = exhibition.id) <> 1205
     or (select count(*) from content.exhibition_versions as version
         join fixture_rows as row on row.version_id = version.id
         where version.status = 'published'::content.exhibition_version_status) <> 1205
     or (select count(*) from content.exhibitions as exhibition
         join fixture_rows as row on row.exhibition_id = exhibition.id
         where exhibition.published_version_id = row.version_id) <> 1205
     or load_integrity.row_count <> 1205
     or empty_integrity.row_count <> 0
     or featured_integrity.row_count <> 5
     or not coalesce((reconciliation ->> 'in_sync')::boolean, false)
     or boundary_position is distinct from 500
     or mutation_position is distinct from 750
     or (select count(*)
         from public.exhibitions as legacy
         where left(legacy.id, length(config.fixture_prefix)) = config.fixture_prefix)
          <> case when (
               select runtime.legacy_mirror_enabled
               from content_private.exhibition_catalog_runtime as runtime
               where runtime.singleton
             ) then 1205 else 0 end
     or (select count(*) from public.exhibition_catalog_v2
         where event_id = config.load_event_id and is_homepage_featured) <> 4
     or (select count(*) from public.exhibition_catalog_v2
         where event_id = config.load_event_id
           and latitude is null and longitude is null) = 0
     or (select count(*) from public.exhibition_catalog_v2
         where event_id = config.load_event_id and is_editors_pick) <> 1
     or (select count(*) from public.exhibition_catalog_v2
         where event_id = config.load_event_id
           and guest_editor_id = config.editor_id) <> 1
     or (select cover_image_url from public.exhibition_catalog_v2
         where id = config.fixture_prefix || 'catalog-0001')
          is distinct from
            ('https://fixtures.invalid/' || config.fixture_prefix || 'legacy-0001.jpg')
     or (select cover_image_url from public.exhibition_catalog_v2
         where id = config.fixture_prefix || 'catalog-0005')
          is distinct from
            ('https://fixtures.invalid/' || config.fixture_prefix || 'published-cover-0005.webp') then
    raise exception using errcode = '23514', message = 'fixture_projection_validation_failed';
  end if;
end
$validation$;

select jsonb_build_object(
  'state', 'provisioned',
  'captured_at', to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  'fixture_count', 1205,
  'featured_count', 5,
  'homepage_count', 4,
  'load_event_id', config.load_event_id,
  'empty_event_id', config.empty_event_id,
  'boundary_cursor_id', config.boundary_id,
  'mutation_target_id', config.mutation_id,
  'editor_id', config.editor_id,
  'media_object_path', config.media_object_path,
  'storage_bytes_created', false,
  'runtime', (
    select to_jsonb(runtime)
    from content_private.exhibition_catalog_runtime as runtime
    where runtime.singleton
  ),
  'event_integrity', (
    select to_jsonb(integrity)
    from public.exhibition_catalog_v2_integrity(config.load_event_id, false) as integrity
  ),
  'featured_integrity', (
    select to_jsonb(integrity)
    from public.exhibition_catalog_v2_integrity(config.load_event_id, true) as integrity
  ),
  'empty_integrity', (
    select to_jsonb(integrity)
    from public.exhibition_catalog_v2_integrity(config.empty_event_id, false) as integrity
  ),
  'reconciliation', public.admin_reconcile_exhibition_catalog_v2(),
  'fixture_version_id_checksum_sha256', (
    select encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(row.version_id::text, 'UTF8'))::text
                || ':' || row.version_id::text,
              '' order by row.version_id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    )
    from fixture_rows as row
  ),
  'fixture_media_id_checksum_sha256', (
    select encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(asset.id::text, 'UTF8'))::text
                || ':' || asset.id::text,
              '' order by asset.id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    )
    from content.media_assets as asset
    where asset.object_path = config.media_object_path
  ),
  'fixture_attachment_id_checksum_sha256', (
    select encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(
                convert_to(
                  jsonb_build_array(attachment.version_id, attachment.media_id)::text,
                  'UTF8'
                )
              )::text
                || ':'
                || jsonb_build_array(attachment.version_id, attachment.media_id)::text,
              '' order by attachment.version_id, attachment.media_id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    )
    from content.exhibition_version_media as attachment
    join fixture_rows as row on row.version_id = attachment.version_id
  ),
  'fixture_curation_id_checksum_sha256', (
    select encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(placement.id::text, 'UTF8'))::text
                || ':' || placement.id::text,
              '' order by placement.id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    )
    from content.curation_placements as placement
    join fixture_rows as row on row.exhibition_id = placement.exhibition_id
  ),
  'fixture_exhibition_ids', (
    select jsonb_agg(row.exhibition_id order by row.exhibition_id)
    from fixture_rows as row
  ),
  'fixture_version_ids', (
    select jsonb_agg(row.version_id order by row.exhibition_id)
    from fixture_rows as row
  ),
  'fixture_media_asset_ids', (
    select jsonb_agg(asset.id order by asset.id)
    from content.media_assets as asset
    where asset.object_path = config.media_object_path
  ),
  'fixture_curation_ids', (
    select jsonb_agg(placement.id order by placement.id)
    from content.curation_placements as placement
    join fixture_rows as row on row.exhibition_id = placement.exhibition_id
  )
)
from fixture_config as config;

commit;
