begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(76);

-- -------------------------------------------------------------------------
-- Schema, RLS, grants, and the service-only compatibility surface.
-- -------------------------------------------------------------------------

select has_table(
  'content',
  'legacy_import_batches',
  'legacy import batches table exists'
);
select has_table(
  'content',
  'legacy_import_rows',
  'legacy import rows table exists'
);
select has_table(
  'content',
  'legacy_import_links',
  'legacy import provenance links table exists'
);
select has_column(
  'content',
  'exhibition_versions',
  'legacy_source_updated_at',
  'published imports retain their source updated_at timestamp'
);

select is(
  (
    select count(*)::integer
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname in (
        'legacy_import_batches',
        'legacy_import_rows',
        'legacy_import_links'
      )
      and relation.relrowsecurity
  ),
  3,
  'RLS is enabled on every legacy import table'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('content.legacy_import_batches'),
        ('content.legacy_import_rows'),
        ('content.legacy_import_links')
    ) as relation(name)
    where not has_table_privilege('anon', relation.name, 'SELECT')
      and not has_table_privilege('anon', relation.name, 'INSERT')
      and not has_table_privilege('anon', relation.name, 'UPDATE')
      and not has_table_privilege('anon', relation.name, 'DELETE')
  ),
  3,
  'anon has no DML privilege on legacy import state'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('content.legacy_import_batches'),
        ('content.legacy_import_rows'),
        ('content.legacy_import_links')
    ) as relation(name)
    where not has_table_privilege('authenticated', relation.name, 'SELECT')
      and not has_table_privilege('authenticated', relation.name, 'INSERT')
      and not has_table_privilege('authenticated', relation.name, 'UPDATE')
      and not has_table_privilege('authenticated', relation.name, 'DELETE')
  ),
  3,
  'authenticated clients have no DML privilege on legacy import state'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('content.legacy_import_batches'),
        ('content.legacy_import_rows'),
        ('content.legacy_import_links')
    ) as relation(name)
    where has_table_privilege(
      'service_role',
      relation.name,
      'SELECT, INSERT, UPDATE, DELETE'
    )
  ),
  3,
  'service role owns the import-state DML surface'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'migration_stage_legacy_exhibitions',
        'migration_apply_legacy_exhibitions',
        'migration_reconcile_legacy_exhibitions'
      )
      and not procedure.prosecdef
  ),
  3,
  'all public migration RPCs are SECURITY INVOKER'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'migration_stage_legacy_exhibitions',
        'migration_apply_legacy_exhibitions',
        'migration_reconcile_legacy_exhibitions'
      )
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  3,
  'all public migration RPCs pin an empty search_path'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.migration_stage_legacy_exhibitions(jsonb)'),
        ('public.migration_apply_legacy_exhibitions(uuid)'),
        ('public.migration_reconcile_legacy_exhibitions(uuid)')
    ) as signature(value)
    where has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  3,
  'service role can execute all migration RPCs'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.migration_stage_legacy_exhibitions(jsonb)'),
        ('public.migration_apply_legacy_exhibitions(uuid)'),
        ('public.migration_reconcile_legacy_exhibitions(uuid)')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
  ),
  0,
  'anon can execute no migration RPC'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.migration_stage_legacy_exhibitions(jsonb)'),
        ('public.migration_apply_legacy_exhibitions(uuid)'),
        ('public.migration_reconcile_legacy_exhibitions(uuid)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  0,
  'authenticated clients can execute no migration RPC'
);

select is(
  (
    select count(*)::integer
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'public'
      and relation.relname in (
        'exhibitions_v2_preview',
        'guest_editors_v2_preview'
      )
      and relation.relkind = 'v'
      and relation.reloptions @> array['security_invoker=true']::text[]
  ),
  2,
  'both compatibility previews are SECURITY INVOKER views'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.exhibitions_v2_preview'),
        ('public.guest_editors_v2_preview')
    ) as relation(name)
    where has_table_privilege('service_role', relation.name, 'SELECT')
  ),
  2,
  'service role can read both compatibility previews'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.exhibitions_v2_preview'),
        ('public.guest_editors_v2_preview')
    ) as relation(name)
    where has_table_privilege('anon', relation.name, 'SELECT')
  ),
  0,
  'anon cannot read compatibility previews before cutover'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.exhibitions_v2_preview'),
        ('public.guest_editors_v2_preview')
    ) as relation(name)
    where has_table_privilege('authenticated', relation.name, 'SELECT')
  ),
  0,
  'authenticated clients cannot read compatibility previews before cutover'
);

select is(
  (
    select relation.relkind::text
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'public'
      and relation.relname = 'exhibitions'
  ),
  'r',
  'legacy public.exhibitions remains a table'
);

set local role service_role;
select lives_ok(
  $$ select count(*) from public.exhibitions_v2_preview $$,
  'service role can query the exhibition compatibility preview'
);
reset role;

-- -------------------------------------------------------------------------
-- Fixtures and helpers.
-- -------------------------------------------------------------------------

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
  'legacy-test-event',
  '레거시 테스트 이벤트',
  'Legacy test event',
  '서울',
  'Seoul',
  '2026-01-01',
  '2026-12-31',
  '#112233'
);

insert into public.editors (
  id,
  name_ko,
  name_en,
  title_ko,
  title_en,
  bio_ko,
  bio_en,
  is_active,
  active_from
) values (
  'legacy-test-editor',
  '레거시 테스트 에디터',
  'Legacy Test Editor',
  '게스트 에디터',
  'Guest editor',
  '테스트 소개',
  'Test biography',
  true,
  '2026-01-01'
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
  description_ko,
  updated_at
) values (
  'legacy-table-sentinel',
  '레거시 테이블 보존 행',
  '보존 미술관',
  '서울',
  '서울',
  '2026-01-01',
  '2026-12-31',
  false,
  '이 행은 마이그레이션 작업으로 변경되면 안 됩니다.',
  '2026-01-02T03:04:05+09:00'
);

create temporary table legacy_import_test_state (
  key text primary key,
  payload jsonb not null
) on commit drop;

grant select, insert, update, delete
  on legacy_import_test_state to service_role;

insert into legacy_import_test_state (key, payload)
select
  'legacy_table_before',
  jsonb_build_object(
    'row_count', (select count(*) from public.exhibitions),
    'sentinel', (
      select to_jsonb(exhibition)
      from public.exhibitions as exhibition
      where exhibition.id = 'legacy-table-sentinel'
    )
  );

create function pg_temp.legacy_test_row(p_id text)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select jsonb_build_object(
    'id', p_id,
    'name_ko', '기본 전시',
    'name_en', '',
    'venue_name_ko', '기본 미술관',
    'venue_name_en', '',
    'city_ko', '서울',
    'city_en', '',
    'region_ko', '서울',
    'region_en', '',
    'opening_date', '2026-01-01',
    'closing_date', '2026-12-31',
    'is_featured', false,
    'latitude', null,
    'longitude', null,
    'description_ko', '',
    'description_en', '',
    'address_ko', '',
    'address_en', '',
    'cover_image_url', format('https://legacy.example.invalid/%s.jpg', p_id),
    'hours', null,
    'contact', null,
    'reception_date', null,
    'opening_time', null,
    'event_id', null,
    'editor_id', null,
    'is_homepage_featured', false,
    'ticket_url', null,
    'updated_at', '2026-01-02T03:04:05+09:00'
  );
$$;

create function pg_temp.legacy_test_bundle(
  p_source_file_name text,
  p_source_sha256 text,
  p_rows jsonb,
  p_source_snapshot_at timestamptz default '2026-07-21T10:00:00+09:00'
)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select jsonb_build_object(
    'schema_version', 1,
    'source_system', 'legacy_public_exhibitions',
    'source_file_name', p_source_file_name,
    'source_snapshot_at', p_source_snapshot_at,
    'source_sha256', p_source_sha256,
    'row_count', jsonb_array_length(p_rows),
    'rows', p_rows
  );
$$;

create function pg_temp.legacy_issue_count(p_batch_id uuid, p_code text)
returns bigint
language sql
stable
set search_path = ''
as $$
  select count(*)
  from content.legacy_import_rows as staged
  cross join lateral jsonb_array_elements(staged.issues) as issue(value)
  where staged.batch_id = p_batch_id
    and issue.value ->> 'code' = p_code;
$$;

grant execute on function pg_temp.legacy_test_row(text) to service_role;
grant execute on function pg_temp.legacy_test_bundle(
  text,
  text,
  jsonb,
  timestamptz
)
  to service_role;

-- -------------------------------------------------------------------------
-- Malformed and semantically invalid bundles remain reviewable and blocked.
-- -------------------------------------------------------------------------

set local role service_role;

select throws_ok(
  $$ select public.migration_stage_legacy_exhibitions('[]'::jsonb) $$,
  '22023',
  'legacy_bundle_must_be_an_object',
  'a structurally invalid bundle is rejected before staging'
);

select throws_ok(
  $$ select public.migration_stage_legacy_exhibitions('{}'::jsonb) $$,
  '22023',
  'unsupported_legacy_bundle_schema',
  'a bundle missing schema_version is rejected before staging'
);

insert into legacy_import_test_state (key, payload)
values (
  'invalid_stage',
  public.migration_stage_legacy_exhibitions(
    pg_temp.legacy_test_bundle(
      'invalid-legacy-export.json',
      repeat('1', 64),
      jsonb_build_array(
        pg_temp.legacy_test_row('duplicate-legacy-id'),
        pg_temp.legacy_test_row('duplicate-legacy-id'),
        pg_temp.legacy_test_row('invalid-real-date')
          || jsonb_build_object('opening_date', '2026-02-29'),
        pg_temp.legacy_test_row('invalid-date-shape')
          || jsonb_build_object('opening_date', '2026-2-28'),
        pg_temp.legacy_test_row('reversed-date-range')
          || jsonb_build_object(
            'opening_date', '2026-08-10',
            'closing_date', '2026-08-09'
          ),
        pg_temp.legacy_test_row(' outer-space-id ')
          || jsonb_build_object(
            'cover_image_url', 'https://legacy.example.invalid/outer-space-id.jpg'
          ),
        pg_temp.legacy_test_row('partial-coordinates')
          || jsonb_build_object('latitude', '37.5665'),
        pg_temp.legacy_test_row('bad-cover-url')
          || jsonb_build_object('cover_image_url', 'ftp://example.invalid/cover.jpg'),
        pg_temp.legacy_test_row('orphan-associations')
          || jsonb_build_object(
            'event_id', 'missing-event',
            'editor_id', 'missing-editor'
          )
      )
    )
  )
);

reset role;

select is(
  (select payload ->> 'status' from legacy_import_test_state where key = 'invalid_stage'),
  'validated',
  'semantic problems produce a validated dry-run report'
);
select is(
  (
    select (payload ->> 'blocked_rows')::integer
    from legacy_import_test_state
    where key = 'invalid_stage'
  ),
  9,
  'every invalid source row is retained and blocked'
);
select is(
  (
    select (payload ->> 'error_count')::integer
    from legacy_import_test_state
    where key = 'invalid_stage'
  ),
  10,
  'the dry-run report counts every blocking issue'
);
select is(
  pg_temp.legacy_issue_count(
    (
      select (payload ->> 'batch_id')::uuid
      from legacy_import_test_state
      where key = 'invalid_stage'
    ),
    'duplicate_id'
  ),
  2::bigint,
  'both duplicate source rows are identified'
);
select is(
  pg_temp.legacy_issue_count(
    (select (payload ->> 'batch_id')::uuid from legacy_import_test_state where key = 'invalid_stage'),
    'invalid_opening_date'
  ),
  2::bigint,
  'a non-existent Gregorian date and a non-canonical date shape are rejected'
);
select is(
  pg_temp.legacy_issue_count(
    (select (payload ->> 'batch_id')::uuid from legacy_import_test_state where key = 'invalid_stage'),
    'id_is_not_trimmed'
  ),
  1::bigint,
  'outer whitespace on an authoritative source ID blocks normalization'
);
select is(
  pg_temp.legacy_issue_count(
    (select (payload ->> 'batch_id')::uuid from legacy_import_test_state where key = 'invalid_stage'),
    'reversed_date_range'
  ),
  1::bigint,
  'a reversed date range is rejected'
);
select is(
  pg_temp.legacy_issue_count(
    (select (payload ->> 'batch_id')::uuid from legacy_import_test_state where key = 'invalid_stage'),
    'incomplete_coordinate_pair'
  ),
  1::bigint,
  'partial coordinates are rejected'
);
select is(
  pg_temp.legacy_issue_count(
    (select (payload ->> 'batch_id')::uuid from legacy_import_test_state where key = 'invalid_stage'),
    'invalid_url'
  ),
  1::bigint,
  'a non-HTTP cover URL is rejected'
);
select is(
  pg_temp.legacy_issue_count(
    (select (payload ->> 'batch_id')::uuid from legacy_import_test_state where key = 'invalid_stage'),
    'orphan_event_id'
  ),
  1::bigint,
  'an unknown event reference is rejected'
);
select is(
  pg_temp.legacy_issue_count(
    (select (payload ->> 'batch_id')::uuid from legacy_import_test_state where key = 'invalid_stage'),
    'orphan_editor_id'
  ),
  1::bigint,
  'an unknown editor reference is rejected'
);

set local role service_role;
select throws_ok(
  format(
    'select public.migration_apply_legacy_exhibitions(%L::uuid)',
    (
      select payload ->> 'batch_id'
      from legacy_import_test_state
      where key = 'invalid_stage'
    )
  ),
  '23514',
  'legacy_import_batch_has_errors',
  'a batch with validation errors cannot mutate canonical content'
);
reset role;

select is(
  (
    select count(*)
    from content.exhibitions
    where id in (
      'duplicate-legacy-id',
      'invalid-real-date',
      'invalid-date-shape',
      'reversed-date-range',
      'outer-space-id',
      'partial-coordinates',
      'bad-cover-url',
      'orphan-associations'
    )
  ),
  0::bigint,
  'blocked rows create no canonical identities'
);

-- -------------------------------------------------------------------------
-- Valid stage/apply preserves the exact stable identity and public contract.
-- -------------------------------------------------------------------------

set local role service_role;

insert into legacy_import_test_state (key, payload)
values (
  'valid_bundle',
  pg_temp.legacy_test_bundle(
    'valid-legacy-export.json',
    repeat('2', 64),
    jsonb_build_array(
      pg_temp.legacy_test_row('stable-legacy-id')
        || jsonb_build_object(
          'source_row_number', 42,
          'name_ko', '보존할 레거시 전시',
          'name_en', '',
          'venue_name_ko', '레거시 미술관',
          'venue_name_en', '',
          'city_ko', '서울',
          'city_en', '',
          'region_ko', '서울',
          'region_en', '',
          'opening_date', '2026.02.28',
          'closing_date', '2026-03-10',
          'is_featured', true,
          'latitude', '37.5665',
          'longitude', '126.9780',
          'description_ko', '한국어 설명',
          'description_en', '',
          'address_ko', '서울특별시 중구 1',
          'address_en', '',
          'cover_image_url', 'https://legacy.example.invalid/covers/stable-legacy-id.jpg',
          'hours', '',
          'contact', null,
          'reception_date', '2026-02-28T18:30:00+09:00',
          'opening_time', '',
          'event_id', 'legacy-test-event',
          'editor_id', 'legacy-test-editor',
          'is_homepage_featured', false,
          'ticket_url', 'https://tickets.example.invalid/stable-legacy-id',
          'updated_at', '2026-06-01T02:03:04+09:00'
        )
    )
  )
);

insert into legacy_import_test_state (key, payload)
select
  'valid_stage',
  public.migration_stage_legacy_exhibitions(payload)
from legacy_import_test_state
where key = 'valid_bundle';

reset role;

select ok(
  (
    select payload ->> 'status' = 'validated'
      and (payload ->> 'error_count')::integer = 0
      and (payload #>> '{planned_actions,insert}')::integer = 1
    from legacy_import_test_state
    where key = 'valid_stage'
  ),
  'a valid snapshot is ready for one deterministic insert'
);

select is(
  (
    select action
    from content.legacy_import_rows
    where batch_id = (
      select (payload ->> 'batch_id')::uuid
      from legacy_import_test_state
      where key = 'valid_stage'
    )
  ),
  'insert',
  'the valid staged row records its planned insert action'
);

set local role service_role;
insert into legacy_import_test_state (key, payload)
select
  'valid_apply',
  public.migration_apply_legacy_exhibitions((payload ->> 'batch_id')::uuid)
from legacy_import_test_state
where key = 'valid_stage';
reset role;

select ok(
  (
    select payload ->> 'status' = 'applied'
      and not (payload ->> 'idempotent_replay')::boolean
      and (payload #>> '{applied_actions,insert}')::integer = 1
    from legacy_import_test_state
    where key = 'valid_apply'
  ),
  'the first apply reports one non-replayed insert'
);

select ok(
  (
    select exhibition.id = 'stable-legacy-id'
      and exhibition.published_version_id = link.last_imported_version_id
      and link.source_id = exhibition.id
      and link.first_batch_id = (
        select (payload ->> 'batch_id')::uuid
        from legacy_import_test_state
        where key = 'valid_stage'
      )
    from content.exhibitions as exhibition
    join content.legacy_import_links as link
      on link.exhibition_id = exhibition.id
    where exhibition.id = 'stable-legacy-id'
      and link.source_system = 'legacy_public_exhibitions'
  ),
  'apply preserves the exact text ID and records its published provenance pointer'
);

select ok(
  (
    select version.version_number = 1
      and version.status = 'published'::content.exhibition_version_status
      and version.opening_date = '2026-02-28'::date
      and version.closing_date = '2026-03-10'::date
      and version.name_en = ''
      and version.description_en = ''
      and version.address_en = ''
      and version.hours is null
      and version.contact is null
      and version.opening_time is null
    from content.exhibition_versions as version
    join content.exhibitions as exhibition
      on exhibition.published_version_id = version.id
    where exhibition.id = 'stable-legacy-id'
  ),
  'typed canonical content preserves required empty strings and nullable fields'
);

select ok(
  (
    select version.event_id = 'legacy-test-event'
      and version.editor_id = 'legacy-test-editor'
      and version.latitude = 37.5665::double precision
      and version.longitude = 126.9780::double precision
      and version.legacy_cover_image_url =
        'https://legacy.example.invalid/covers/stable-legacy-id.jpg'
      and version.ticket_url = 'https://tickets.example.invalid/stable-legacy-id'
      and version.reception_date = '2026-02-28T18:30:00+09:00'::timestamptz
      and version.legacy_source_updated_at =
        '2026-06-01T02:03:04+09:00'::timestamptz
    from content.exhibition_versions as version
    join content.exhibitions as exhibition
      on exhibition.published_version_id = version.id
    where exhibition.id = 'stable-legacy-id'
  ),
  'associations, coordinates, legacy image, ticket, reception, and source timestamp survive import'
);

select ok(
  (
    select count(*) = 2
      and bool_and(
        case placement.surface
          when 'app_featured'::content.curation_surface then placement.enabled
          when 'homepage'::content.curation_surface then not placement.enabled
        end
      )
    from content.curation_placements as placement
    where placement.exhibition_id = 'stable-legacy-id'
  ),
  'apply imports both curation flags exactly'
);

select ok(
  (
    select preview.id = 'stable-legacy-id'
      and preview.name_ko = '보존할 레거시 전시'
      and preview.name_en = ''
      and preview.hours is null
      and preview.contact is null
      and preview.opening_time is null
      and preview.cover_image_url =
        'https://legacy.example.invalid/covers/stable-legacy-id.jpg'
      and preview.event_id = 'legacy-test-event'
      and preview.editor_id = 'legacy-test-editor'
      and preview.ticket_url = 'https://tickets.example.invalid/stable-legacy-id'
      and preview.is_featured
      and not preview.is_homepage_featured
    from public.exhibitions_v2_preview as preview
    where preview.id = 'stable-legacy-id'
  ),
  'compatibility preview preserves exact values and null-versus-empty behavior'
);

select ok(
  (
    select not preview.is_editors_pick
      and preview.guest_editor_id = 'legacy-test-editor'
    from public.exhibitions_v2_preview as preview
    where preview.id = 'stable-legacy-id'
  ),
  'compatibility preview derives the historical editor fields'
);

select is(
  (
    select updated_at
    from public.exhibitions_v2_preview
    where id = 'stable-legacy-id'
  ),
  '2026-06-01T02:03:04+09:00'::timestamptz,
  'compatibility updated_at preserves the source timestamp despite pointer updates'
);

set local role service_role;
insert into legacy_import_test_state (key, payload)
select
  'valid_reconcile',
  public.migration_reconcile_legacy_exhibitions((payload ->> 'batch_id')::uuid)
from legacy_import_test_state
where key = 'valid_stage';
reset role;

select ok(
  (
    select (payload ->> 'source_count')::integer = 1
      and (payload ->> 'preview_count')::integer = 1
      and (payload ->> 'matching_count')::integer = 1
      and (payload ->> 'difference_count')::integer = 0
      and payload -> 'differences' = '[]'::jsonb
    from legacy_import_test_state
    where key = 'valid_reconcile'
  ),
  'reconciliation is clean immediately after a valid apply'
);

-- Draft-only and archived identities never leak into the preview.
insert into content.exhibitions (id)
values ('preview-hidden-draft');
insert into content.exhibition_versions (
  exhibition_id,
  version_number,
  status,
  name_ko,
  venue_name_ko,
  city_ko,
  region_ko,
  opening_date,
  closing_date
) values (
  'preview-hidden-draft',
  1,
  'draft'::content.exhibition_version_status,
  '숨은 초안',
  '초안 미술관',
  '서울',
  '서울',
  '2026-01-01',
  '2026-12-31'
);

insert into content.exhibitions (id, archived_at)
values ('preview-hidden-archived', now());
insert into content.exhibition_versions (
  id,
  exhibition_id,
  version_number,
  status,
  name_ko,
  venue_name_ko,
  city_ko,
  region_ko,
  opening_date,
  closing_date,
  published_at
) values (
  '55000000-0000-0000-0000-000000000001'::uuid,
  'preview-hidden-archived',
  1,
  'published'::content.exhibition_version_status,
  '숨은 보관 전시',
  '보관 미술관',
  '서울',
  '서울',
  '2026-01-01',
  '2026-12-31',
  now()
);
update content.exhibitions
set published_version_id = '55000000-0000-0000-0000-000000000001'::uuid
where id = 'preview-hidden-archived';

select is(
  (
    select count(*)
    from public.exhibitions_v2_preview
    where id = 'preview-hidden-draft'
  ),
  0::bigint,
  'draft-only identities are absent from the compatibility preview'
);
select is(
  (
    select count(*)
    from public.exhibitions_v2_preview
    where id = 'preview-hidden-archived'
  ),
  0::bigint,
  'archived identities are absent from the compatibility preview'
);

-- -------------------------------------------------------------------------
-- Exact replay is side-effect free.
-- -------------------------------------------------------------------------

set local role service_role;
insert into legacy_import_test_state (key, payload)
select
  'replayed_stage',
  public.migration_stage_legacy_exhibitions(payload)
from legacy_import_test_state
where key = 'valid_bundle';
reset role;

select ok(
  (
    select replay.payload ->> 'batch_id' = original.payload ->> 'batch_id'
      and (replay.payload ->> 'idempotent_replay')::boolean
      and replay.payload ->> 'status' = 'applied'
    from legacy_import_test_state as replay
    cross join legacy_import_test_state as original
    where replay.key = 'replayed_stage'
      and original.key = 'valid_stage'
  ),
  'staging the identical source snapshot returns the original applied batch'
);

set local role service_role;
select throws_ok(
  $$
    select public.migration_stage_legacy_exhibitions(
      pg_temp.legacy_test_bundle(
        'tampered-valid-legacy-export.json',
        repeat('2', 64),
        jsonb_build_array(
          pg_temp.legacy_test_row('stable-legacy-id')
            || jsonb_build_object('name_ko', '같은 SHA의 다른 내용')
        )
      )
    )
  $$,
  '23514',
  'legacy_source_sha256_payload_mismatch',
  'a reused source SHA with different normalized content fails closed'
);
reset role;

select is(
  (
    select count(*)
    from content.legacy_import_batches
    where source_system = 'legacy_public_exhibitions'
      and source_sha256 = repeat('2', 64)
  ),
  1::bigint,
  'identical source SHA replay creates no second batch'
);

set local role service_role;
insert into legacy_import_test_state (key, payload)
select
  'replayed_apply',
  public.migration_apply_legacy_exhibitions((payload ->> 'batch_id')::uuid)
from legacy_import_test_state
where key = 'replayed_stage';
reset role;

select ok(
  (
    select payload ->> 'status' = 'applied'
      and (payload ->> 'idempotent_replay')::boolean
    from legacy_import_test_state
    where key = 'replayed_apply'
  ),
  'applying an already-applied batch returns an idempotent replay'
);

select ok(
  (
    select
      (select count(*) from content.exhibition_versions where exhibition_id = 'stable-legacy-id') = 1
      and (
        select count(*)
        from content.audit_log
        where entity_id = 'stable-legacy-id'
          and action like 'legacy_import.%'
      ) = 1
  ),
  'exact replay creates no duplicate version or audit record'
);

-- Stage a valid intermediate snapshot now, then deliberately leave it pending
-- while a newer full snapshot is applied below.
set local role service_role;
insert into legacy_import_test_state (key, payload)
values (
  'stale_bundle',
  pg_temp.legacy_test_bundle(
    'eventually-stale-legacy-export.json',
    repeat('7', 64),
    jsonb_build_array(
      pg_temp.legacy_test_row('stable-legacy-id')
        || jsonb_build_object(
          'name_ko', '뒤늦게 적용하면 안 되는 중간 스냅샷',
          'venue_name_ko', '레거시 미술관',
          'city_ko', '서울',
          'region_ko', '서울',
          'opening_date', '2026-02-28',
          'closing_date', '2026-03-10',
          'updated_at', '2026-06-01T12:00:00+09:00'
        )
    ),
    '2026-07-21T10:30:00+09:00'::timestamptz
  )
);

insert into legacy_import_test_state (key, payload)
select
  'stale_stage',
  public.migration_stage_legacy_exhibitions(payload)
from legacy_import_test_state
where key = 'stale_bundle';
reset role;

select ok(
  (
    select (payload ->> 'error_count')::integer = 0
      and (payload #>> '{planned_actions,revise}')::integer = 1
    from legacy_import_test_state
    where key = 'stale_stage'
  ),
  'an intermediate snapshot can be reviewed before a newer snapshot is applied'
);

set local role service_role;
insert into legacy_import_test_state (key, payload)
values (
  'future_bundle',
  pg_temp.legacy_test_bundle(
    'future-before-baseline-change-legacy-export.json',
    repeat('9', 64),
    jsonb_build_array(
      pg_temp.legacy_test_row('stable-legacy-id')
        || jsonb_build_object(
          'name_ko', '기준선 변경 전에 검토한 미래 스냅샷',
          'venue_name_ko', '레거시 미술관',
          'city_ko', '서울',
          'region_ko', '서울',
          'opening_date', '2026-02-28',
          'closing_date', '2026-03-10',
          'updated_at', '2026-06-04T05:06:07+09:00'
        )
    ),
    '2026-07-21T11:30:00+09:00'::timestamptz
  )
);

insert into legacy_import_test_state (key, payload)
select
  'future_stage',
  public.migration_stage_legacy_exhibitions(payload)
from legacy_import_test_state
where key = 'future_bundle';
reset role;

select ok(
  (
    select (future.payload ->> 'error_count')::integer = 0
      and (future.payload #>> '{planned_actions,revise}')::integer = 1
      and future.payload ->> 'baseline_batch_id' = valid.payload ->> 'batch_id'
    from legacy_import_test_state as future
    cross join legacy_import_test_state as valid
    where future.key = 'future_stage'
      and valid.key = 'valid_stage'
  ),
  'a staged batch records the exact applied baseline used for operator review'
);

-- -------------------------------------------------------------------------
-- A changed full snapshot creates immutable superseded history.
-- -------------------------------------------------------------------------

set local role service_role;
insert into legacy_import_test_state (key, payload)
values (
  'changed_bundle',
  pg_temp.legacy_test_bundle(
    'changed-legacy-export.json',
    repeat('3', 64),
    jsonb_build_array(
      pg_temp.legacy_test_row('stable-legacy-id')
        || jsonb_build_object(
          'name_ko', '보존할 레거시 전시 — 개정',
          'venue_name_ko', '레거시 미술관',
          'city_ko', '서울',
          'region_ko', '서울',
          'opening_date', '2026-02-28',
          'closing_date', '2026-03-10',
          'is_featured', false,
          'latitude', '37.5665',
          'longitude', '126.9780',
          'description_ko', '개정된 한국어 설명',
          'address_ko', '서울특별시 중구 1',
          'cover_image_url', 'https://legacy.example.invalid/covers/stable-legacy-id.jpg',
          'reception_date', '2026-02-28T18:30:00+09:00',
          'event_id', 'legacy-test-event',
          'editor_id', 'legacy-test-editor',
          'is_homepage_featured', true,
          'ticket_url', 'https://tickets.example.invalid/stable-legacy-id',
          'updated_at', '2026-06-02T03:04:05+09:00'
        )
    ),
    '2026-07-21T11:00:00+09:00'::timestamptz
  )
);

insert into legacy_import_test_state (key, payload)
select
  'changed_stage',
  public.migration_stage_legacy_exhibitions(payload)
from legacy_import_test_state
where key = 'changed_bundle';
reset role;

select ok(
  (
    select (payload ->> 'error_count')::integer = 0
      and (payload #>> '{planned_actions,revise}')::integer = 1
    from legacy_import_test_state
    where key = 'changed_stage'
  ),
  'a changed snapshot plans one revision without validation errors'
);

set local role service_role;
insert into legacy_import_test_state (key, payload)
select
  'changed_apply',
  public.migration_apply_legacy_exhibitions((payload ->> 'batch_id')::uuid)
from legacy_import_test_state
where key = 'changed_stage';
reset role;

select is(
  (
    select (payload #>> '{applied_actions,revise}')::integer
    from legacy_import_test_state
    where key = 'changed_apply'
  ),
  1,
  'applying changed source data reports one revision'
);

select ok(
  (
    select count(*) = 2
      and count(*) filter (
        where status = 'superseded'::content.exhibition_version_status
      ) = 1
      and count(*) filter (
        where status = 'published'::content.exhibition_version_status
      ) = 1
      and min(version_number) = 1
      and max(version_number) = 2
    from content.exhibition_versions
    where exhibition_id = 'stable-legacy-id'
  ),
  'changed import retains one superseded and one published version in order'
);

select is(
  (
    select name_ko
    from content.exhibition_versions
    where exhibition_id = 'stable-legacy-id'
      and version_number = 1
  ),
  '보존할 레거시 전시',
  'the first imported version remains immutable'
);

select ok(
  (
    select preview.name_ko = '보존할 레거시 전시 — 개정'
      and not preview.is_featured
      and preview.is_homepage_featured
      and preview.updated_at = '2026-06-02T03:04:05+09:00'::timestamptz
    from public.exhibitions_v2_preview as preview
    where preview.id = 'stable-legacy-id'
  ),
  'preview advances to the revised copy, curation, and source timestamp'
);

select ok(
  (
    select exhibition.published_version_id = link.last_imported_version_id
      and link.last_batch_id = (
        select (payload ->> 'batch_id')::uuid
        from legacy_import_test_state
        where key = 'changed_stage'
      )
      and version.version_number = 2
    from content.exhibitions as exhibition
    join content.legacy_import_links as link
      on link.exhibition_id = exhibition.id
    join content.exhibition_versions as version
      on version.id = link.last_imported_version_id
    where exhibition.id = 'stable-legacy-id'
      and link.source_system = 'legacy_public_exhibitions'
  ),
  'the identity and provenance link advance together to imported version two'
);

set local role service_role;
insert into legacy_import_test_state (key, payload)
select
  'changed_reconcile',
  public.migration_reconcile_legacy_exhibitions((payload ->> 'batch_id')::uuid)
from legacy_import_test_state
where key = 'changed_stage';
reset role;

select is(
  (
    select (payload ->> 'difference_count')::integer
    from legacy_import_test_state
    where key = 'changed_reconcile'
  ),
  0,
  'reconciliation returns to zero after the changed snapshot is applied'
);

set local role service_role;
select throws_ok(
  format(
    'select public.migration_apply_legacy_exhibitions(%L::uuid)',
    (
      select payload ->> 'batch_id'
      from legacy_import_test_state
      where key = 'future_stage'
    )
  ),
  '40001',
  'legacy_import_baseline_changed_since_stage',
  'apply rejects a newer snapshot when its reviewed applied baseline has changed'
);
reset role;

set local role service_role;
select throws_ok(
  format(
    'select public.migration_apply_legacy_exhibitions(%L::uuid)',
    (
      select payload ->> 'batch_id'
      from legacy_import_test_state
      where key = 'stale_stage'
    )
  ),
  '40001',
  'legacy_import_source_snapshot_not_newer',
  'a pre-staged older snapshot cannot roll back a newer applied snapshot'
);
reset role;

select ok(
  (
    select preview.name_ko = '보존할 레거시 전시 — 개정'
      and version.version_number = 2
      and link.last_batch_id = (
        select (payload ->> 'batch_id')::uuid
        from legacy_import_test_state
        where key = 'changed_stage'
      )
      and (
        select status
        from content.legacy_import_batches
        where id = (
          select (payload ->> 'batch_id')::uuid
          from legacy_import_test_state
          where key = 'stale_stage'
        )
      ) = 'validated'
    from public.exhibitions_v2_preview as preview
    join content.exhibitions as exhibition on exhibition.id = preview.id
    join content.legacy_import_links as link
      on link.exhibition_id = exhibition.id
     and link.source_system = 'legacy_public_exhibitions'
    join content.exhibition_versions as version
      on version.id = link.last_imported_version_id
    where preview.id = 'stable-legacy-id'
  ),
  'stale apply rejection preserves the newer projection, version, link, and batch state'
);

set local role service_role;
insert into legacy_import_test_state (key, payload)
values (
  'stale_restage',
  public.migration_stage_legacy_exhibitions(
    pg_temp.legacy_test_bundle(
      'stale-after-newer-legacy-export.json',
      repeat('8', 64),
      (
        select payload -> 'rows'
        from legacy_import_test_state
        where key = 'stale_bundle'
      ),
      '2026-07-21T10:45:00+09:00'::timestamptz
    )
  )
);
reset role;

select ok(
  (
    select (state.payload ->> 'error_count')::integer = 1
      and (state.payload ->> 'blocked_rows')::integer = 1
      and staged.action = 'blocked'
      and pg_temp.legacy_issue_count(
        (state.payload ->> 'batch_id')::uuid,
        'source_snapshot_not_newer'
      ) = 1
    from legacy_import_test_state as state
    join content.legacy_import_rows as staged
      on staged.batch_id = (state.payload ->> 'batch_id')::uuid
    where state.key = 'stale_restage'
  ),
  'staging an older full snapshot after a newer apply reports and blocks it'
);

-- A canonical archive that lands after validation must be rechecked at apply.
set local role service_role;
insert into legacy_import_test_state (key, payload)
values (
  'archive_race_bundle',
  pg_temp.legacy_test_bundle(
    'archive-race-legacy-export.json',
    repeat('5', 64),
    jsonb_build_array(
      pg_temp.legacy_test_row('stable-legacy-id')
        || jsonb_build_object(
          'name_ko', '보관 이후 덮어쓰면 안 되는 전시',
          'venue_name_ko', '레거시 미술관',
          'city_ko', '서울',
          'region_ko', '서울',
          'opening_date', '2026-02-28',
          'closing_date', '2026-03-10',
          'event_id', 'legacy-test-event',
          'editor_id', 'legacy-test-editor',
          'updated_at', '2026-06-03T04:05:06+09:00'
        )
    ),
    '2026-07-21T12:00:00+09:00'::timestamptz
  )
);

insert into legacy_import_test_state (key, payload)
select
  'archive_race_stage',
  public.migration_stage_legacy_exhibitions(payload)
from legacy_import_test_state
where key = 'archive_race_bundle';
reset role;

select ok(
  (
    select (payload ->> 'error_count')::integer = 0
      and (payload #>> '{planned_actions,revise}')::integer = 1
    from legacy_import_test_state
    where key = 'archive_race_stage'
  ),
  'a still-active canonical identity validates as a revision before the archive race'
);

update content.exhibitions
set archived_at = now()
where id = 'stable-legacy-id';

set local role service_role;
select throws_ok(
  format(
    'select public.migration_apply_legacy_exhibitions(%L::uuid)',
    (
      select payload ->> 'batch_id'
      from legacy_import_test_state
      where key = 'archive_race_stage'
    )
  ),
  '23514',
  'legacy_import_batch_has_errors',
  'apply rechecks and rejects an identity archived after staging'
);
reset role;

select ok(
  (
    select exhibition.archived_at is not null
      and exhibition.published_version_id = link.last_imported_version_id
      and batch.status = 'validated'
      and batch.applied_at is null
      and (
        select count(*)
        from content.exhibition_versions
        where exhibition_id = exhibition.id
      ) = 2
    from content.exhibitions as exhibition
    join content.legacy_import_links as link
      on link.exhibition_id = exhibition.id
     and link.source_system = 'legacy_public_exhibitions'
    join content.legacy_import_batches as batch
      on batch.id = (
        select (payload ->> 'batch_id')::uuid
        from legacy_import_test_state
        where key = 'archive_race_stage'
      )
    where exhibition.id = 'stable-legacy-id'
  ),
  'archive-race rejection leaves canonical history, pointer, and provenance unchanged'
);

set local role service_role;
insert into legacy_import_test_state (key, payload)
values (
  'archived_restage',
  public.migration_stage_legacy_exhibitions(
    pg_temp.legacy_test_bundle(
      'archived-restage-legacy-export.json',
      repeat('6', 64),
      (
        select payload -> 'rows'
        from legacy_import_test_state
        where key = 'archive_race_bundle'
      ),
      '2026-07-21T13:00:00+09:00'::timestamptz
    )
  )
);
reset role;

select ok(
  (
    select (state.payload ->> 'error_count')::integer = 1
      and (state.payload ->> 'blocked_rows')::integer = 1
      and staged.action = 'blocked'
      and pg_temp.legacy_issue_count(
        (state.payload ->> 'batch_id')::uuid,
        'canonical_archived_since_import'
      ) = 1
    from legacy_import_test_state as state
    join content.legacy_import_rows as staged
      on staged.batch_id = (state.payload ->> 'batch_id')::uuid
    where state.key = 'archived_restage'
  ),
  'restaging while archived reports canonical_archived_since_import and blocks the row'
);

update content.exhibitions
set archived_at = null
where id = 'stable-legacy-id';

-- -------------------------------------------------------------------------
-- A later canonical/admin publish makes a Sheet delta fail closed.
-- -------------------------------------------------------------------------

update content.exhibition_versions
set status = 'superseded'::content.exhibition_version_status
where exhibition_id = 'stable-legacy-id'
  and status = 'published'::content.exhibition_version_status;

insert into content.exhibition_versions (
  id,
  exhibition_id,
  version_number,
  revision,
  status,
  name_ko,
  venue_name_ko,
  city_ko,
  region_ko,
  opening_date,
  closing_date,
  published_at
) values (
  '55000000-0000-0000-0000-000000000002'::uuid,
  'stable-legacy-id',
  3,
  1,
  'published'::content.exhibition_version_status,
  '관리자에서 발행한 전시',
  '관리자 미술관',
  '서울',
  '서울',
  '2026-02-28',
  '2026-03-10',
  now()
);

update content.exhibitions
set published_version_id = '55000000-0000-0000-0000-000000000002'::uuid
where id = 'stable-legacy-id';

set local role service_role;
insert into legacy_import_test_state (key, payload)
values (
  'conflict_stage',
  public.migration_stage_legacy_exhibitions(
    pg_temp.legacy_test_bundle(
      'conflicting-legacy-export.json',
      repeat('4', 64),
      jsonb_build_array(
        pg_temp.legacy_test_row('stable-legacy-id')
          || jsonb_build_object(
            'name_ko', '시트에서 뒤늦게 변경한 전시',
            'venue_name_ko', '레거시 미술관',
            'city_ko', '서울',
            'region_ko', '서울',
            'opening_date', '2026-02-28',
            'closing_date', '2026-03-10',
            'event_id', 'legacy-test-event',
            'editor_id', 'legacy-test-editor',
            'updated_at', '2026-06-03T04:05:06+09:00'
          )
      ),
      '2026-07-21T14:00:00+09:00'::timestamptz
    )
  )
);
reset role;

select is(
  pg_temp.legacy_issue_count(
    (
      select (payload ->> 'batch_id')::uuid
      from legacy_import_test_state
      where key = 'conflict_stage'
    ),
    'canonical_changed_since_import'
  ),
  1::bigint,
  'a canonical pointer changed by an admin is reported as a blocking conflict'
);

select is(
  (
    select action
    from content.legacy_import_rows
    where batch_id = (
      select (payload ->> 'batch_id')::uuid
      from legacy_import_test_state
      where key = 'conflict_stage'
    )
  ),
  'blocked',
  'a canonical pointer conflict marks the staged action blocked'
);

set local role service_role;
select throws_ok(
  format(
    'select public.migration_apply_legacy_exhibitions(%L::uuid)',
    (
      select payload ->> 'batch_id'
      from legacy_import_test_state
      where key = 'conflict_stage'
    )
  ),
  '23514',
  'legacy_import_batch_has_errors',
  'apply refuses to overwrite a later canonical/admin publication'
);
reset role;

select ok(
  (
    select exhibition.published_version_id =
        '55000000-0000-0000-0000-000000000002'::uuid
      and link.last_imported_version_id <> exhibition.published_version_id
      and (
        select count(*)
        from content.exhibition_versions
        where exhibition_id = exhibition.id
      ) = 3
    from content.exhibitions as exhibition
    join content.legacy_import_links as link
      on link.exhibition_id = exhibition.id
    where exhibition.id = 'stable-legacy-id'
      and link.source_system = 'legacy_public_exhibitions'
  ),
  'failed conflict apply leaves both the admin pointer and importer provenance unchanged'
);

-- -------------------------------------------------------------------------
-- The entire workflow is additive to the shipped legacy table.
-- -------------------------------------------------------------------------

select is(
  (
    select relation.relkind::text
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'public'
      and relation.relname = 'exhibitions'
  ),
  'r',
  'public.exhibitions is still a table after every migration operation'
);

select is(
  (select count(*) from public.exhibitions),
  (
    select (payload ->> 'row_count')::bigint
    from legacy_import_test_state
    where key = 'legacy_table_before'
  ),
  'legacy public exhibition row count is unchanged'
);

select is(
  (
    select to_jsonb(exhibition)
    from public.exhibitions as exhibition
    where exhibition.id = 'legacy-table-sentinel'
  ),
  (
    select payload -> 'sentinel'
    from legacy_import_test_state
    where key = 'legacy_table_before'
  ),
  'legacy public exhibition content is byte-for-byte unchanged as JSON'
);

select * from finish();
rollback;
