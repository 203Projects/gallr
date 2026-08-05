begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(15);

-- Production has canonical ownership enabled. Open the historical legacy
-- reader fixture only for this rolled-back transaction and suppress bridge
-- enqueueing so the reader assertions remain isolated from live state.
update content_private.exhibition_catalog_runtime
set legacy_mirror_enabled = false,
    legacy_writes_blocked = false,
    legacy_mirror_enabled_at = null,
    baseline_row_count = null,
    baseline_id_checksum_sha256 = null,
    baseline_catalog_checksum_sha256 = null,
    reason = 'pgTAP reader integrity fixture'
where singleton;

update content_private.legacy_mobile_catalog_mirror_config
set source_outbox_enabled = false,
    reason = 'pgTAP reader integrity fixture'
where singleton;

select has_column(
  'public',
  'exhibitions',
  'ticket_url',
  'fresh schemas include the legacy ticket URL selected by public readers'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'exhibitions'
      and indexname = 'exhibitions_pkey'
      and indexdef ilike '% (id)'
  ),
  'the unfiltered exhibition cursor is backed by the primary-key index'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'exhibitions'
      and indexname = 'exhibitions_event_id_id_idx'
      and indexdef ilike '% (event_id, id) WHERE (event_id IS NOT NULL)'
  ),
  'event-scoped keyset reads have an equality-plus-cursor partial index'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'exhibitions'
      and indexname = 'exhibitions_featured_id_idx'
      and indexdef ilike '% (id) WHERE (is_featured = true)'
  ),
  'featured keyset reads have a matching partial id index'
);

select ok(
  to_regprocedure('public.exhibition_reader_integrity(text,boolean)') is not null,
  'the public reader integrity function exists with typed filters'
);

select ok(
  has_function_privilege(
    'anon',
    'public.exhibition_reader_integrity(text,boolean)',
    'EXECUTE'
  ),
  'anonymous readers can execute the integrity function'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.exhibition_reader_integrity(text,boolean)',
    'EXECUTE'
  ),
  'authenticated readers can execute the integrity function'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.exhibition_reader_integrity(text,boolean)',
    'EXECUTE'
  ),
  'service-role verification can execute the integrity function'
);

select ok(
  not (
    select function_row.prosecdef
    from pg_catalog.pg_proc as function_row
    where function_row.oid =
      to_regprocedure('public.exhibition_reader_integrity(text,boolean)')
  ),
  'the integrity function is security invoker'
);

select is(
  (
    select function_row.provolatile
    from pg_catalog.pg_proc as function_row
    where function_row.oid =
      to_regprocedure('public.exhibition_reader_integrity(text,boolean)')
  ),
  's'::"char",
  'the integrity function is stable for one statement snapshot'
);

select is(
  (
    select function_row.proconfig
    from pg_catalog.pg_proc as function_row
    where function_row.oid =
      to_regprocedure('public.exhibition_reader_integrity(text,boolean)')
  ),
  array['search_path=""']::text[],
  'the integrity function pins an empty search_path'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as function_row
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        function_row.proacl,
        pg_catalog.acldefault('f', function_row.proowner)
      )
    ) as privilege
    where function_row.oid =
      to_regprocedure('public.exhibition_reader_integrity(text,boolean)')
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC has no implicit execute grant on the integrity function'
);

insert into public.events (
  id,
  name_ko,
  name_en,
  location_label_ko,
  location_label_en,
  start_date,
  end_date,
  brand_color
) values (
  'reader-integrity-event',
  '리더 무결성 이벤트',
  'Reader integrity event',
  '서울',
  'Seoul',
  '2026-01-01',
  '2026-12-31',
  '#112233'
);

insert into public.exhibitions (
  id,
  name_ko,
  venue_name_ko,
  city_ko,
  region_ko,
  opening_date,
  closing_date,
  is_featured,
  event_id
) values
  (
    'integrity-a', '무결성 A', '테스트 전시장', '서울', '서울',
    '2026-01-01', '2026-12-31', true, 'reader-integrity-event'
  ),
  (
    'integrity-b', '무결성 B', '테스트 전시장', '서울', '서울',
    '2026-01-01', '2026-12-31', false, 'reader-integrity-event'
  ),
  (
    'integrity-c', '무결성 C', '테스트 전시장', '서울', '서울',
    '2026-01-01', '2026-12-31', true, 'reader-integrity-event'
  );

set local role anon;

select is(
  (
    select integrity.row_count
    from public.exhibition_reader_integrity('reader-integrity-event', false)
      as integrity
  ),
  3::bigint,
  'event-scoped integrity returns the complete row count under anon RLS'
);

select is(
  (
    select integrity.id_checksum_sha256
    from public.exhibition_reader_integrity('reader-integrity-event', false)
      as integrity
  ),
  '53043595cba7eb4efae06a02df2562f8579967e5de1678defe18a81a4ef17e49',
  'event-scoped integrity hashes the database-ordered length-prefixed ids'
);

select results_eq(
  $$
    select integrity.row_count, integrity.id_checksum_sha256
    from public.exhibition_reader_integrity('reader-integrity-event', true)
      as integrity
  $$,
  $$
    values (
      2::bigint,
      '4df1ebc1f7c914fd15a16f6fd284f09d2866cf25503750217a95667b61059fab'::text
    )
  $$,
  'event and featured filters share the same typed single-snapshot contract'
);

reset role;

select * from finish();
rollback;
