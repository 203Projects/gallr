begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(93);

-- -------------------------------------------------------------------------
-- Public schema, indexes, RLS, grants, private helpers, and RPC hardening.
-- -------------------------------------------------------------------------

select has_table(
  'public',
  'exhibition_catalog_v2',
  'the canonical public exhibition catalog exists'
);

select is(
  (
    select count(*)::integer
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'exhibition_catalog_v2'
  ),
  37,
  'the public catalog exposes thirty-six reader fields plus one row checksum'
);

select is(
  (
    select count(*)::integer
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'exhibition_catalog_v2'
      and column_name in (
        'opening_date',
        'closing_date',
        'content_checksum_sha256'
      )
      and is_nullable = 'NO'
  ),
  3,
  'dates and the derived content checksum are required'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_constraint as constraint_row
    where constraint_row.conrelid =
      'public.exhibition_catalog_v2'::regclass
      and constraint_row.contype = 'c'
      and constraint_row.conname in (
        'exhibition_catalog_v2_id_not_blank',
        'exhibition_catalog_v2_required_text_not_blank',
        'exhibition_catalog_v2_date_order',
        'exhibition_catalog_v2_latitude_range',
        'exhibition_catalog_v2_longitude_range',
        'exhibition_catalog_v2_coordinate_pair',
        'exhibition_catalog_v2_editor_pick_alias',
        'exhibition_catalog_v2_guest_editor_alias',
        'exhibition_catalog_v2_content_checksum_format'
      )
  ),
  9,
  'the projection owns its complete public-shape constraint set'
);

select has_pk(
  'public',
  'exhibition_catalog_v2',
  'the public catalog has an ID primary key for keyset pagination'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'exhibition_catalog_v2'
      and indexname = 'exhibition_catalog_v2_event_id_id_idx'
      and indexdef ilike '%(event_id, id)%WHERE (event_id IS NOT NULL)%'
  ),
  'event-scoped keyset reads have an event-plus-ID partial index'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'exhibition_catalog_v2'
      and indexname = 'exhibition_catalog_v2_featured_id_idx'
      and indexdef ilike '%(id)%WHERE is_featured%'
  ),
  'featured keyset reads have a partial ID index'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_indexes
    where schemaname = 'public'
      and tablename = 'exhibition_catalog_v2'
      and indexname = 'exhibition_catalog_v2_homepage_closing_id_idx'
      and indexdef ilike '%(closing_date, id)%WHERE is_homepage_featured%'
  ),
  'homepage reads have a closing-date-plus-ID partial index'
);

select ok(
  (
    select relation.relrowsecurity
    from pg_catalog.pg_class as relation
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = relation.relnamespace
    where namespace.nspname = 'public'
      and relation.relname = 'exhibition_catalog_v2'
  ),
  'RLS is enabled on the public catalog'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_policies
    where schemaname = 'public'
      and tablename = 'exhibition_catalog_v2'
      and policyname = 'public readers can read exhibition catalog v2'
      and cmd = 'SELECT'
      and roles @> array['anon', 'authenticated', 'service_role']::name[]
  ),
  'one SELECT policy names the three intended reader roles'
);

select ok(
  has_table_privilege('anon', 'public.exhibition_catalog_v2', 'SELECT')
  and not has_table_privilege(
    'anon',
    'public.exhibition_catalog_v2',
    'INSERT, UPDATE, DELETE'
  ),
  'anon can read but cannot mutate the projection'
);

select ok(
  has_table_privilege(
    'authenticated',
    'public.exhibition_catalog_v2',
    'SELECT'
  )
  and not has_table_privilege(
    'authenticated',
    'public.exhibition_catalog_v2',
    'INSERT, UPDATE, DELETE'
  ),
  'authenticated readers can read but cannot mutate the projection'
);

select ok(
  has_table_privilege(
    'service_role',
    'public.exhibition_catalog_v2',
    'SELECT'
  )
  and not has_table_privilege(
    'service_role',
    'public.exhibition_catalog_v2',
    'INSERT, UPDATE, DELETE'
  ),
  'service role can verify but cannot bypass projection maintenance with DML'
);

select ok(
  has_table_privilege('anon', 'public.exhibitions', 'SELECT')
  and has_table_privilege('authenticated', 'public.exhibitions', 'SELECT')
  and not has_table_privilege(
    'anon', 'public.exhibitions', 'INSERT, UPDATE, DELETE, TRUNCATE'
  )
  and not has_table_privilege(
    'authenticated',
    'public.exhibitions',
    'INSERT, UPDATE, DELETE, TRUNCATE'
  )
  and not has_any_column_privilege(
    'anon', 'public.exhibitions', 'INSERT, UPDATE, REFERENCES'
  )
  and not has_any_column_privilege(
    'authenticated',
    'public.exhibitions',
    'INSERT, UPDATE, REFERENCES'
  ),
  'legacy public readers retain SELECT but no table or column write grant'
);

select ok(
  has_table_privilege('service_role', 'public.exhibitions', 'SELECT')
  and has_table_privilege('service_role', 'public.exhibitions', 'INSERT')
  and has_table_privilege('service_role', 'public.exhibitions', 'UPDATE')
  and has_table_privilege('service_role', 'public.exhibitions', 'DELETE')
  and has_table_privilege('service_role', 'public.exhibitions', 'TRUNCATE'),
  'Sheet-owned mode explicitly retains the legacy service-role writer'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_class as relation
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        relation.relacl,
        pg_catalog.acldefault('r', relation.relowner)
      )
    ) as privilege
    where relation.oid = 'public.exhibition_catalog_v2'::regclass
      and privilege.grantee = 0
      and privilege.privilege_type in ('SELECT', 'INSERT', 'UPDATE', 'DELETE')
  ),
  'PUBLIC has no implicit table privilege on the projection'
);

select ok(
  not has_schema_privilege('anon', 'content', 'USAGE')
  and not has_schema_privilege('anon', 'content_private', 'USAGE'),
  'anon receives no access to either private canonical schema'
);

select has_table(
  'content_private',
  'exhibition_catalog_runtime',
  'a private singleton controls the final-cutover legacy mirror'
);

select ok(
  coalesce(
    (
      select column_default = 'false'
      from information_schema.columns
      where table_schema = 'content_private'
        and table_name = 'exhibition_catalog_runtime'
        and column_name = 'legacy_mirror_enabled'
    ),
    false
  )
  and coalesce(
    (
      select column_default = 'false'
      from information_schema.columns
      where table_schema = 'content_private'
        and table_name = 'exhibition_catalog_runtime'
        and column_name = 'legacy_writes_blocked'
    ),
    false
  ),
  'Sheet-owned mode keeps mirror and ownership-guard schema defaults disabled'
);

select ok(
  to_regclass(
    'content_private.exhibition_catalog_legacy_write_context'
  ) is not null
  and not has_table_privilege(
    'anon',
    'content_private.exhibition_catalog_runtime',
    'SELECT, INSERT, UPDATE, DELETE'
  )
  and not has_table_privilege(
    'anon',
    'content_private.exhibition_catalog_legacy_write_context',
    'SELECT, INSERT, UPDATE, DELETE'
  )
  and not has_table_privilege(
    'authenticated',
    'content_private.exhibition_catalog_runtime',
    'SELECT, INSERT, UPDATE, DELETE'
  )
  and not has_table_privilege(
    'authenticated',
    'content_private.exhibition_catalog_legacy_write_context',
    'SELECT, INSERT, UPDATE, DELETE'
  )
  and not has_table_privilege(
    'service_role',
    'content_private.exhibition_catalog_runtime',
    'SELECT, INSERT, UPDATE, DELETE'
  )
  and not has_table_privilege(
    'service_role',
    'content_private.exhibition_catalog_legacy_write_context',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'no API role can inspect or mutate private mirror state or write context'
);

select ok(
  has_table_privilege('service_role', 'content.audit_log', 'SELECT')
  and not has_table_privilege('service_role', 'content.audit_log', 'INSERT')
  and not has_table_privilege('service_role', 'content.audit_log', 'UPDATE')
  and not has_table_privilege('service_role', 'content.audit_log', 'DELETE')
  and not has_table_privilege('service_role', 'content.audit_log', 'TRUNCATE')
  and not has_table_privilege('service_role', 'content.audit_log', 'REFERENCES')
  and not has_table_privilege('service_role', 'content.audit_log', 'TRIGGER'),
  'service role has read-only audit access; only owner-run commands append evidence'
);

set local role service_role;
select throws_ok(
  $$delete from content.audit_log where false$$,
  '42501',
  'permission denied for table audit_log',
  'an API credential cannot bypass audit-log mutation revocation'
);
reset role;

-- Private-state damage must fail closed. A statement-level trigger runs even
-- when its DML predicate matches no legacy rows, so this also covers an empty
-- catalog during installation or recovery.
--
-- The linked rehearsal runs against a production-derived clone whose legacy
-- reader already contains source rows. Isolate this transaction's four
-- deterministic fixtures from both that pre-existing snapshot and an applied
-- representative canonical import; the outer rollback restores every row
-- after pgTAP finishes.
update content_private.exhibition_catalog_runtime
set legacy_mirror_enabled = false,
    legacy_writes_blocked = false,
    legacy_mirror_enabled_at = null,
    baseline_row_count = null,
    baseline_id_checksum_sha256 = null,
    baseline_catalog_checksum_sha256 = null,
    reason = 'pgTAP catalog V2 fixture'
where singleton;

update content_private.legacy_mobile_catalog_mirror_config
set source_outbox_enabled = false,
    reason = 'pgTAP catalog V2 fixture'
where singleton;

truncate table
  content.legacy_import_rows,
  content.legacy_import_links,
  content.legacy_import_batches,
  content.exhibition_version_media,
  content.curation_placements,
  content.exhibition_versions,
  content.exhibitions,
  content.audit_log,
  content.outbox_events,
  public.exhibition_catalog_v2
restart identity cascade;

delete from public.exhibitions;

delete from content_private.exhibition_catalog_runtime where singleton;

select throws_ok(
  $$update public.exhibitions set name_ko = name_ko where false$$,
  '55000',
  'exhibition_catalog_runtime_invalid',
  'the legacy ownership guard fails closed when runtime state is missing'
);

set local role service_role;

select throws_ok(
  $$select public.admin_disable_legacy_exhibition_mirror('missing runtime test')$$,
  '55000',
  'exhibition_catalog_runtime_invalid',
  'freeze refuses to report success without exactly one runtime row'
);

select throws_ok(
  format(
    'select public.admin_enable_legacy_exhibition_mirror(%s, %L, %L, %L)',
    integrity.row_count,
    integrity.id_checksum_sha256,
    integrity.catalog_checksum_sha256,
    'missing runtime test'
  ),
  '55000',
  'exhibition_catalog_runtime_invalid',
  'activation refuses to report success without exactly one runtime row'
)
from public.exhibition_catalog_v2_integrity(null, false) as integrity;

reset role;

insert into content_private.exhibition_catalog_runtime (
  singleton,
  legacy_mirror_enabled,
  legacy_writes_blocked,
  legacy_mirror_enabled_at,
  reason
) values (
  true,
  false,
  false,
  null,
  'installed disabled'
);

select ok(
  exists (
    select 1
    from pg_catalog.pg_trigger as trigger_row
    where trigger_row.tgrelid = 'public.exhibitions'::regclass
      and trigger_row.tgname = 'guard_legacy_exhibitions_owner'
      and not trigger_row.tgisinternal
  ),
  'a statement-level ownership guard protects the legacy reader table'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_proc as procedure
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'guard_legacy_exhibitions_owner',
        'exhibition_catalog_v2_payload',
        'sha256_canonical_jsonb',
        'exhibition_catalog_v2_checksum',
        'legacy_exhibition_catalog_v2_payload',
        'legacy_exhibition_catalog_v2_checksum',
        'set_exhibition_catalog_v2_checksum',
        'mirror_exhibition_catalog_v2_to_legacy',
        'exhibition_catalog_v2_source',
        'refresh_exhibition_catalog_v2',
        'sync_catalog_v2_from_exhibition',
        'sync_catalog_v2_from_version',
        'sync_catalog_v2_from_curation',
        'sync_catalog_v2_from_attachment',
        'sync_catalog_v2_from_media_asset'
      )
  ),
  15,
  'all private catalog checksum, source, refresh, and trigger helpers exist'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_proc as procedure
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'guard_legacy_exhibitions_owner',
        'exhibition_catalog_v2_payload',
        'sha256_canonical_jsonb',
        'exhibition_catalog_v2_checksum',
        'legacy_exhibition_catalog_v2_payload',
        'legacy_exhibition_catalog_v2_checksum',
        'set_exhibition_catalog_v2_checksum',
        'mirror_exhibition_catalog_v2_to_legacy',
        'exhibition_catalog_v2_source',
        'refresh_exhibition_catalog_v2',
        'sync_catalog_v2_from_exhibition',
        'sync_catalog_v2_from_version',
        'sync_catalog_v2_from_curation',
        'sync_catalog_v2_from_attachment',
        'sync_catalog_v2_from_media_asset'
      )
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  15,
  'every private catalog helper pins an empty search_path'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('content_private.guard_legacy_exhibitions_owner()'),
        ('content_private.exhibition_catalog_v2_payload(public.exhibition_catalog_v2)'),
        ('content_private.sha256_canonical_jsonb(jsonb)'),
        ('content_private.exhibition_catalog_v2_checksum(public.exhibition_catalog_v2)'),
        ('content_private.legacy_exhibition_catalog_v2_payload(public.exhibitions)'),
        ('content_private.legacy_exhibition_catalog_v2_checksum(public.exhibitions)'),
        ('content_private.set_exhibition_catalog_v2_checksum()'),
        ('content_private.mirror_exhibition_catalog_v2_to_legacy(text)'),
        ('content_private.exhibition_catalog_v2_source(text)'),
        ('content_private.refresh_exhibition_catalog_v2(text)'),
        ('content_private.sync_catalog_v2_from_exhibition()'),
        ('content_private.sync_catalog_v2_from_version()'),
        ('content_private.sync_catalog_v2_from_curation()'),
        ('content_private.sync_catalog_v2_from_attachment()'),
        ('content_private.sync_catalog_v2_from_media_asset()')
    ) as signature(value)
    cross join (
      values ('anon'), ('authenticated'), ('service_role')
    ) as checked_role(name)
    where has_function_privilege(
      checked_role.name,
      signature.value,
      'EXECUTE'
    )
  ),
  0,
  'no API role can execute private projection helpers directly'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        procedure.proacl,
        pg_catalog.acldefault('f', procedure.proowner)
      )
    ) as privilege
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'guard_legacy_exhibitions_owner',
        'exhibition_catalog_v2_payload',
        'sha256_canonical_jsonb',
        'exhibition_catalog_v2_checksum',
        'legacy_exhibition_catalog_v2_payload',
        'legacy_exhibition_catalog_v2_checksum',
        'set_exhibition_catalog_v2_checksum',
        'mirror_exhibition_catalog_v2_to_legacy',
        'exhibition_catalog_v2_source',
        'refresh_exhibition_catalog_v2',
        'sync_catalog_v2_from_exhibition',
        'sync_catalog_v2_from_version',
        'sync_catalog_v2_from_curation',
        'sync_catalog_v2_from_attachment',
        'sync_catalog_v2_from_media_asset'
      )
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC has no implicit execute grant on any private projection helper'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_trigger
    where tgrelid in (
      'public.exhibition_catalog_v2'::regclass,
      'content.exhibitions'::regclass,
      'content.exhibition_versions'::regclass,
      'content.curation_placements'::regclass,
      'content.exhibition_version_media'::regclass,
      'content.media_assets'::regclass
    )
      and not tgisinternal
      and tgname like 'exhibition_catalog_v2_%'
  ),
  15,
  'gallery identity, checksum, credits, directory, country, source, and compatibility triggers are installed'
);

select ok(
  to_regprocedure(
    'public.exhibition_catalog_v2_integrity(text,boolean)'
  ) is not null,
  'the typed V2 integrity RPC exists'
);

select ok(
  (
    select not procedure.prosecdef
      and procedure.provolatile = 's'::"char"
      and procedure.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as procedure
    where procedure.oid = to_regprocedure(
      'public.exhibition_catalog_v2_integrity(text,boolean)'
    )
  ),
  'the V2 integrity RPC is stable, SECURITY INVOKER, and search-path hardened'
);

select is(
  (
    select count(*)::integer
    from (values ('anon'), ('authenticated'), ('service_role'))
      as checked_role(name)
    where has_function_privilege(
      checked_role.name,
      'public.exhibition_catalog_v2_integrity(text,boolean)',
      'EXECUTE'
    )
  ),
  3,
  'all three reader roles can execute V2 integrity'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        procedure.proacl,
        pg_catalog.acldefault('f', procedure.proowner)
      )
    ) as privilege
    where procedure.oid = to_regprocedure(
      'public.exhibition_catalog_v2_integrity(text,boolean)'
    )
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC has no implicit execute grant on V2 integrity'
);

select ok(
  to_regprocedure(
    'public.admin_reconcile_exhibition_catalog_v2()'
  ) is not null,
  'the service reconciliation RPC exists'
);

select ok(
  (
    select procedure.prosecdef
      and procedure.provolatile = 's'::"char"
      and procedure.proconfig @> array['search_path=""']::text[]
      and exists (
        select 1
        from unnest(procedure.proconfig) as setting(value)
        where lower(setting.value) = 'timezone=utc'
      )
    from pg_catalog.pg_proc as procedure
    where procedure.oid = to_regprocedure(
      'public.admin_reconcile_exhibition_catalog_v2()'
    )
  ),
  'reconciliation is stable, hardened SECURITY DEFINER code with UTC payloads'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.admin_reconcile_exhibition_catalog_v2()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.admin_reconcile_exhibition_catalog_v2()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.admin_reconcile_exhibition_catalog_v2()',
    'EXECUTE'
  ),
  'only service role can execute public reconciliation'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        procedure.proacl,
        pg_catalog.acldefault('f', procedure.proowner)
      )
    ) as privilege
    where procedure.oid = to_regprocedure(
      'public.admin_reconcile_exhibition_catalog_v2()'
    )
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC has no implicit execute grant on reconciliation'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_proc as procedure
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'admin_enable_legacy_exhibition_mirror',
        'admin_disable_legacy_exhibition_mirror'
      )
  ),
  2,
  'the explicit service-only legacy mirror enable and disable commands exist'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_proc as procedure
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'admin_enable_legacy_exhibition_mirror',
        'admin_disable_legacy_exhibition_mirror'
      )
      and procedure.prosecdef
      and procedure.provolatile = 'v'::"char"
      and procedure.proconfig = array['search_path=""']::text[]
  ),
  2,
  'both mirror control commands are volatile hardened SECURITY DEFINER functions'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.admin_enable_legacy_exhibition_mirror(bigint,text,text,text)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.admin_disable_legacy_exhibition_mirror(text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.admin_enable_legacy_exhibition_mirror(bigint,text,text,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.admin_enable_legacy_exhibition_mirror(bigint,text,text,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.admin_disable_legacy_exhibition_mirror(text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.admin_disable_legacy_exhibition_mirror(text)',
    'EXECUTE'
  ),
  'only service role can control the legacy compatibility mirror'
);

select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as procedure
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    cross join lateral pg_catalog.aclexplode(
      coalesce(
        procedure.proacl,
        pg_catalog.acldefault('f', procedure.proowner)
      )
    ) as privilege
    where namespace.nspname = 'public'
      and procedure.proname in (
        'admin_enable_legacy_exhibition_mirror',
        'admin_disable_legacy_exhibition_mirror'
      )
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'PUBLIC has no implicit execute grant on either mirror control command'
);

-- -------------------------------------------------------------------------
-- Deterministic canonical fixtures and transactional projection behavior.
-- -------------------------------------------------------------------------

create temporary table catalog_v2_test_state (
  key text primary key,
  text_value text,
  payload jsonb
) on commit drop;
grant select, insert, update, delete on catalog_v2_test_state
  to anon, service_role;

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
  'catalog-v2-test-event',
  '카탈로그 V2 테스트 행사',
  'Catalog V2 test event',
  '서울',
  'Seoul',
  '2026-01-01',
  '2026-12-31',
  '#123456'
);

insert into content.exhibitions (id, created_at, updated_at)
values
  ('catalog-v2-test-draft', '2026-01-01 00:00:00+00', '2026-01-01 00:00:00+00'),
  ('catalog-v2-test-한글-a', '2026-01-02 00:00:00+00', '2026-01-02 00:00:00+00'),
  ('catalog-v2-test-b', '2026-01-03 00:00:00+00', '2026-01-03 00:00:00+00'),
  ('catalog-v2-test-c', '2026-01-04 00:00:00+00', '2026-01-04 00:00:00+00'),
  ('catalog-v2-test-atomic', '2026-01-05 00:00:00+00', '2026-01-05 00:00:00+00');

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
  ticket_url,
  legacy_cover_image_url,
  is_featured,
  is_homepage_featured,
  published_at,
  created_at,
  updated_at
) values
  (
    '00000000-0000-0000-0000-000000000801'::uuid,
    'catalog-v2-test-draft', 1, 1, 'draft', null, null,
    '비공개 초안', 'Private draft', '초안 전시장', 'Draft venue',
    '서울', 'Seoul', '서울', 'Seoul', '초안 주소', 'Draft address',
    '2026-02-01', '2026-02-28', null, null,
    '비공개 설명', 'Private description', null, null, null, null,
    false, false, null,
    '2026-01-01 00:00:00+00', '2026-01-01 00:00:00+00'
  ),
  (
    '00000000-0000-0000-0000-000000000802'::uuid,
    'catalog-v2-test-한글-a', 1, 1, 'published',
    'catalog-v2-test-event', 'gallr-editors',
    '공개 전시 A', 'Published A', 'A 전시장', 'Venue A',
    '서울', 'Seoul', '서울', 'Seoul', 'A 주소', 'Address A',
    '2026-03-01', '2026-10-31', 37.5, 127.0,
    'A 설명', 'Description A', '10:00-18:00', 'a@example.invalid',
    'https://tickets.example.invalid/a',
    'https://legacy.example.invalid/a.jpg',
    true, false, '2026-01-02 00:00:00+00',
    '2026-01-02 00:00:00+00', '2026-01-02 00:00:00+00'
  ),
  (
    '00000000-0000-0000-0000-000000000803'::uuid,
    'catalog-v2-test-b', 1, 1, 'published',
    'catalog-v2-test-event', null,
    '공개 전시 B', 'Published B', 'B 전시장', 'Venue B',
    '서울', 'Seoul', '서울', 'Seoul', 'B 주소', 'Address B',
    '2026-04-01', '2026-11-30', null, null,
    'B 설명', 'Description B', null, null, null, null,
    false, true, '2026-01-03 00:00:00+00',
    '2026-01-03 00:00:00+00', '2026-01-03 00:00:00+00'
  ),
  (
    '00000000-0000-0000-0000-000000000804'::uuid,
    'catalog-v2-test-c', 1, 1, 'published', null, null,
    '공개 전시 C', 'Published C', 'C 전시장', 'Venue C',
    '부산', 'Busan', '부산', 'Busan', 'C 주소', 'Address C',
    '2026-05-01', '2026-09-30', null, null,
    'C 설명', 'Description C', null, null, null, null,
    false, false, '2026-01-04 00:00:00+00',
    '2026-01-04 00:00:00+00', '2026-01-04 00:00:00+00'
  ),
  (
    '00000000-0000-0000-0000-000000000805'::uuid,
    'catalog-v2-test-atomic', 1, 1, 'published', null, null,
    '원자성 전시', 'Atomic exhibition', '원자성 전시장', 'Atomic venue',
    '대구', 'Daegu', '대구', 'Daegu', '원자성 주소', 'Atomic address',
    '2026-06-01', '2026-12-31', null, null,
    '원자성 설명', 'Atomic description', null, null, null, null,
    false, false, '2026-01-05 00:00:00+00',
    '2026-01-05 00:00:00+00', '2026-01-05 00:00:00+00'
  );

update content.exhibitions as exhibition
set published_version_id = pointer.version_id
from (
  values
    ('catalog-v2-test-한글-a', '00000000-0000-0000-0000-000000000802'::uuid),
    ('catalog-v2-test-b', '00000000-0000-0000-0000-000000000803'::uuid),
    ('catalog-v2-test-c', '00000000-0000-0000-0000-000000000804'::uuid),
    ('catalog-v2-test-atomic', '00000000-0000-0000-0000-000000000805'::uuid)
) as pointer(exhibition_id, version_id)
where exhibition.id = pointer.exhibition_id;

select is(
  (
    select count(*)::integer
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-draft'
  ),
  0,
  'a draft-only canonical identity is absent from the public projection'
);

select is(
  (
    select count(*)::integer
    from public.exhibition_catalog_v2
    where id like 'catalog-v2-test-%'
  ),
  4,
  'only the four published fixture identities are projected'
);

select ok(
  exists (
    select 1
    from public.exhibition_catalog_v2 as catalog
    where catalog.id = 'catalog-v2-test-한글-a'
      and catalog.name_ko = '공개 전시 A'
      and catalog.is_editors_pick
      and catalog.guest_editor_id is null
      and catalog.updated_at = (
        select version.updated_at
        from content.exhibition_versions as version
        where version.id =
          '00000000-0000-0000-0000-000000000802'::uuid
      )
  ),
  'advancing the pointer publishes fields, editor aliases, and version updated_at'
);

select is(
  (
    select count(*)::integer
    from public.exhibition_catalog_v2
    where id like 'catalog-v2-test-%'
      and content_checksum_sha256 ~ '^[0-9a-f]{64}$'
  ),
  4,
  'every projected fixture receives a lowercase SHA-256 row checksum'
);

-- The bridge cannot be enabled unless legacy and V2 already have exact field
-- parity, not merely identical ID membership. Start from an exact legacy copy,
-- then inject and repair one field mismatch around the failed activation.
insert into public.exhibitions (
  id,
  name_ko,
  venue_name_ko,
  city_ko,
  region_ko,
  opening_date,
  closing_date,
  is_featured,
  latitude,
  longitude,
  description_ko,
  cover_image_url,
  updated_at,
  name_en,
  venue_name_en,
  city_en,
  region_en,
  description_en,
  address_ko,
  address_en,
  hours,
  contact,
  reception_date,
  opening_time,
  event_id,
  is_homepage_featured,
  editor_id,
  ticket_url
)
select
  catalog.id,
  catalog.name_ko,
  catalog.venue_name_ko,
  catalog.city_ko,
  catalog.region_ko,
  catalog.opening_date,
  catalog.closing_date,
  catalog.is_featured,
  catalog.latitude,
  catalog.longitude,
  catalog.description_ko,
  catalog.cover_image_url,
  catalog.updated_at,
  catalog.name_en,
  catalog.venue_name_en,
  catalog.city_en,
  catalog.region_en,
  catalog.description_en,
  catalog.address_ko,
  catalog.address_en,
  catalog.hours,
  catalog.contact,
  catalog.reception_date,
  catalog.opening_time,
  catalog.event_id,
  catalog.is_homepage_featured,
  catalog.editor_id,
  catalog.ticket_url
from public.exhibition_catalog_v2 as catalog
where catalog.id like 'catalog-v2-test-%';

insert into catalog_v2_test_state (key, payload)
select 'legacy_mirror_activation', to_jsonb(integrity)
from public.exhibition_catalog_v2_integrity(null, false) as integrity;

update public.exhibitions
set name_ko = 'tampered legacy copy'
where id = 'catalog-v2-test-b';

set local role service_role;

select throws_ok(
  format(
    'select public.admin_enable_legacy_exhibition_mirror(%s, %L, %L, %L)',
    (payload ->> 'row_count')::bigint,
    payload ->> 'id_checksum_sha256',
    payload ->> 'catalog_checksum_sha256',
    'cutover parity test'
  ),
  '40001',
  'legacy_mirror_precondition_failed',
  'mirror activation rejects same-ID field drift without changing ownership'
)
from pg_temp.catalog_v2_test_state
where key = 'legacy_mirror_activation';

reset role;

update public.exhibitions as legacy
set name_ko = catalog.name_ko
from public.exhibition_catalog_v2 as catalog
where legacy.id = 'catalog-v2-test-b'
  and catalog.id = legacy.id;

set local role service_role;

select lives_ok(
  $$select public.admin_disable_legacy_exhibition_mirror('preactivation freeze test')$$,
  'the freeze command is safe before mirror activation'
);

reset role;

select ok(
  exists (
    select 1
    from content_private.exhibition_catalog_runtime as runtime
    where runtime.singleton
      and not runtime.legacy_mirror_enabled
      and runtime.legacy_writes_blocked
      and runtime.legacy_mirror_enabled_at is null
      and runtime.reason = 'preactivation freeze test'
      and not has_table_privilege(
        'service_role',
        'public.exhibitions',
        'INSERT, UPDATE, DELETE, TRUNCATE'
      )
      and exists (
        select 1
        from content.audit_log as audit
        where audit.action = 'legacy_exhibition_mirror.disabled'
          and audit.entity_id = 'legacy_exhibition_mirror'
          and audit.metadata ->> 'reason' = 'preactivation freeze test'
          and (audit.metadata ->> 'legacy_writes_blocked')::boolean
      )
  ),
  'preactivation freeze enters guarded frozen mode and revokes legacy DML'
);

select throws_ok(
  $$update public.exhibitions set name_ko = name_ko where id = 'catalog-v2-test-b'$$,
  '55000',
  'legacy_exhibitions_managed_by_canonical',
  'frozen mode keeps the ownership guard active even when mirroring is off'
);

set local role service_role;

select lives_ok(
  format(
    'select public.admin_enable_legacy_exhibition_mirror(%s, %L, %L, %L)',
    (payload ->> 'row_count')::bigint,
    payload ->> 'id_checksum_sha256',
    payload ->> 'catalog_checksum_sha256',
    'cutover parity test'
  ),
  'service role can enable the mirror from one exact reconciled snapshot'
)
from pg_temp.catalog_v2_test_state
where key = 'legacy_mirror_activation';

reset role;

select ok(
  exists (
    select 1
    from content_private.exhibition_catalog_runtime as runtime
    cross join catalog_v2_test_state as state
    where runtime.singleton
      and runtime.legacy_mirror_enabled
      and runtime.legacy_writes_blocked
      and runtime.legacy_mirror_enabled_at is not null
      and runtime.baseline_row_count =
        (state.payload ->> 'row_count')::bigint
      and runtime.baseline_id_checksum_sha256 =
        state.payload ->> 'id_checksum_sha256'
      and runtime.baseline_catalog_checksum_sha256 =
        state.payload ->> 'catalog_checksum_sha256'
      and runtime.reason = 'cutover parity test'
      and state.key = 'legacy_mirror_activation'
      and exists (
        select 1
        from content.audit_log as audit
        where audit.action = 'legacy_exhibition_mirror.enabled'
          and audit.entity_id = 'legacy_exhibition_mirror'
          and audit.metadata ->> 'reason' = 'cutover parity test'
      )
  ),
  'activation records its exact baseline, reason, and audit event'
);

select is(
  (
    select count(*)::integer
    from public.exhibition_catalog_v2 as catalog
    join public.exhibitions as legacy using (id)
    where catalog.id like 'catalog-v2-test-%'
      and to_jsonb(legacy) - array[
        'is_editors_pick',
        'guest_editor_id'
      ] = to_jsonb(catalog)
        - array[
          'is_editors_pick',
          'guest_editor_id',
          'gallery_id',
          'artists',
          'art_terms',
          'content_checksum_sha256'
        ]
  ),
  4,
  'activation preserves exact field parity without changing membership'
);

select ok(
  not has_table_privilege(
    'service_role',
    'public.exhibitions',
    'INSERT, UPDATE, DELETE, TRUNCATE'
  ),
  'activation revokes every direct legacy mutation privilege from service role'
);

select throws_ok(
  $$
    update public.exhibitions
    set name_ko = name_ko
    where id = 'catalog-v2-test-b'
  $$,
  '55000',
  'legacy_exhibitions_managed_by_canonical',
  'the ownership guard rejects a direct legacy write after activation'
);

insert into catalog_v2_test_state (key, text_value)
select 'a_before_published_update', content_checksum_sha256
from public.exhibition_catalog_v2
where id = 'catalog-v2-test-한글-a';

insert into content.curation_placements (
  surface,
  exhibition_id,
  position,
  enabled
) values
  ('app_featured', 'catalog-v2-test-한글-a', 0, false),
  ('homepage', 'catalog-v2-test-한글-a', 0, true);

select ok(
  exists (
    select 1
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
      and not is_featured
      and is_homepage_featured
  ),
  'curation inserts override both version fallback flags transactionally'
);

update content.curation_placements
set enabled = true
where exhibition_id = 'catalog-v2-test-한글-a'
  and surface = 'app_featured';
delete from content.curation_placements
where exhibition_id = 'catalog-v2-test-한글-a'
  and surface = 'homepage';

select ok(
  exists (
    select 1
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
      and is_featured
      and not is_homepage_featured
  ),
  'curation updates refresh and deletion restores the published-version fallback'
);

update content.exhibition_versions
set name_ko = '공개 전시 A 수정'
where id = '00000000-0000-0000-0000-000000000802'::uuid;

select is(
  (
    select name_ko
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  '공개 전시 A 수정',
  'a direct published-version maintenance update refreshes the projection'
);

select is(
  (
    select name_ko
    from public.exhibitions
    where id = 'catalog-v2-test-한글-a'
  ),
  '공개 전시 A 수정',
  'after activation the same canonical transaction refreshes installed legacy readers'
);

select isnt(
  (
    select content_checksum_sha256
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  (
    select text_value
    from catalog_v2_test_state
    where key = 'a_before_published_update'
  ),
  'changing a published business field changes its row checksum'
);

insert into catalog_v2_test_state (key, text_value)
select 'a_after_published_update', content_checksum_sha256
from public.exhibition_catalog_v2
where id = 'catalog-v2-test-한글-a';

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
  closing_date
) values (
  '00000000-0000-0000-0000-000000000806'::uuid,
  'catalog-v2-test-한글-a',
  2,
  1,
  'draft',
  '비공개 A 초안',
  'A 전시장',
  '서울',
  '서울',
  '2026-03-01',
  '2026-10-31'
);
update content.exhibition_versions
set name_ko = '비공개 A 초안 수정'
where id = '00000000-0000-0000-0000-000000000806'::uuid;

select is(
  (
    select name_ko
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  '공개 전시 A 수정',
  'draft inserts and edits do not replace published copy'
);

select is(
  (
    select content_checksum_sha256
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  (
    select text_value
    from catalog_v2_test_state
    where key = 'a_after_published_update'
  ),
  'private draft activity does not churn the public checksum'
);

update content.exhibitions
set archived_at = '2026-07-01 00:00:00+00'
where id = 'catalog-v2-test-c';

select is(
  (
    (select count(*) from public.exhibition_catalog_v2
      where id = 'catalog-v2-test-c')
    + (select count(*) from public.exhibitions
      where id = 'catalog-v2-test-c')
  ),
  0::bigint,
  'archiving removes the identity from both reader contracts in the same transaction'
);

update content.exhibitions
set archived_at = null
where id = 'catalog-v2-test-c';

select is(
  (
    (select count(*) from public.exhibition_catalog_v2
      where id = 'catalog-v2-test-c')
    + (select count(*) from public.exhibitions
      where id = 'catalog-v2-test-c')
  ),
  2::bigint,
  'restoring reprojects the retained published version to both reader contracts'
);

insert into content.media_assets (
  id,
  status,
  bucket_id,
  object_path,
  delivery_bucket_id,
  delivery_object_path,
  public_url,
  mime_type,
  byte_size,
  width,
  height,
  checksum_sha256,
  published_at
) values (
  '00000000-0000-0000-0000-000000000890'::uuid,
  'published',
  'exhibition-media',
  'drafts/catalog-v2-test-a/00000000-0000-0000-0000-000000000890/original.jpg',
  'exhibition-images',
  'cms/00000000-0000-0000-0000-000000000890/original.jpg',
  'https://cdn.example.invalid/catalog-v2-a.jpg',
  'image/jpeg',
  128,
  16,
  8,
  repeat('a', 64),
  '2026-07-01 00:00:00+00'
);
insert into content.exhibition_version_media (
  version_id,
  media_id,
  role,
  sort_order
) values (
  '00000000-0000-0000-0000-000000000802'::uuid,
  '00000000-0000-0000-0000-000000000890'::uuid,
  'cover',
  0
);

select is(
  (
    select cover_image_url
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  'https://cdn.example.invalid/catalog-v2-a.jpg',
  'attaching a published cover to the published version refreshes its URL'
);

update content.media_assets
set public_url = 'https://cdn.example.invalid/catalog-v2-a-v2.jpg'
where id = '00000000-0000-0000-0000-000000000890'::uuid;

select is(
  (
    select cover_image_url
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  'https://cdn.example.invalid/catalog-v2-a-v2.jpg',
  'published-cover URL maintenance refreshes the denormalized projection'
);

update content.media_assets
set purged_at = '2026-07-02 00:00:00+00'
where id = '00000000-0000-0000-0000-000000000890'::uuid;

select is(
  (
    select cover_image_url
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  'https://legacy.example.invalid/a.jpg',
  'purged published media falls back to the legacy cover URL'
);

update content.media_assets
set purged_at = null
where id = '00000000-0000-0000-0000-000000000890'::uuid;

select is(
  (
    select cover_image_url
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  'https://cdn.example.invalid/catalog-v2-a-v2.jpg',
  'restoring valid published media restores the canonical cover URL'
);

delete from content.exhibition_version_media
where version_id = '00000000-0000-0000-0000-000000000802'::uuid
  and media_id = '00000000-0000-0000-0000-000000000890'::uuid;

select is(
  (
    select cover_image_url
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  'https://legacy.example.invalid/a.jpg',
  'detaching the published cover refreshes the legacy fallback'
);

insert into catalog_v2_test_state (key, text_value)
select 'a_before_checksum_forge', content_checksum_sha256
from public.exhibition_catalog_v2
where id = 'catalog-v2-test-한글-a';
update public.exhibition_catalog_v2
set content_checksum_sha256 = repeat('0', 64)
where id = 'catalog-v2-test-한글-a';

select is(
  (
    select content_checksum_sha256
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-한글-a'
  ),
  (
    select text_value
    from catalog_v2_test_state
    where key = 'a_before_checksum_forge'
  ),
  'the BEFORE trigger rejects a forged checksum by re-deriving the unchanged row'
);

-- -------------------------------------------------------------------------
-- Anonymous event/featured/empty integrity and independent checksum framing.
-- -------------------------------------------------------------------------

insert into catalog_v2_test_state (key, payload)
with scoped as (
  select id, content_checksum_sha256
  from public.exhibition_catalog_v2
  where event_id = 'catalog-v2-test-event'
), expected as (
  select
    count(*)::bigint as row_count,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(id, 'UTF8'))::text || ':' || id,
              '' order by id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ) as id_checksum_sha256,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(id, 'UTF8'))::text || ':' || id
                || octet_length(
                  convert_to(content_checksum_sha256, 'UTF8')
                )::text
                || ':' || content_checksum_sha256,
              '' order by id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ) as catalog_checksum_sha256
  from scoped
)
select 'event_integrity', to_jsonb(expected)
from expected;

insert into catalog_v2_test_state (key, payload)
with scoped as (
  select id, content_checksum_sha256
  from public.exhibition_catalog_v2
  where event_id = 'catalog-v2-test-event'
    and is_featured
), expected as (
  select
    count(*)::bigint as row_count,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(id, 'UTF8'))::text || ':' || id,
              '' order by id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ) as id_checksum_sha256,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(convert_to(id, 'UTF8'))::text || ':' || id
                || octet_length(
                  convert_to(content_checksum_sha256, 'UTF8')
                )::text
                || ':' || content_checksum_sha256,
              '' order by id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ) as catalog_checksum_sha256
  from scoped
)
select 'featured_integrity', to_jsonb(expected)
from expected;

set local role anon;

select is(
  (
    select integrity.row_count
    from public.exhibition_catalog_v2_integrity(
      'catalog-v2-test-event',
      false
    ) as integrity
  ),
  2::bigint,
  'anonymous event integrity counts the complete event collection'
);

select is(
  (
    select integrity.id_checksum_sha256
    from public.exhibition_catalog_v2_integrity(
      'catalog-v2-test-event',
      false
    ) as integrity
  ),
  (
    select payload ->> 'id_checksum_sha256'
    from pg_temp.catalog_v2_test_state
    where key = 'event_integrity'
  ),
  'event integrity uses UTF-8 byte-length-prefixed IDs in database order'
);

select is(
  (
    select integrity.catalog_checksum_sha256
    from public.exhibition_catalog_v2_integrity(
      'catalog-v2-test-event',
      false
    ) as integrity
  ),
  (
    select payload ->> 'catalog_checksum_sha256'
    from pg_temp.catalog_v2_test_state
    where key = 'event_integrity'
  ),
  'event integrity frames each ID and row-content checksum deterministically'
);

select results_eq(
  $$
    select
      integrity.row_count,
      integrity.id_checksum_sha256,
      integrity.catalog_checksum_sha256
    from public.exhibition_catalog_v2_integrity(
      'catalog-v2-test-event',
      true
    ) as integrity
  $$,
  $$
    select
      (payload ->> 'row_count')::bigint,
      payload ->> 'id_checksum_sha256',
      payload ->> 'catalog_checksum_sha256'
    from pg_temp.catalog_v2_test_state
    where key = 'featured_integrity'
  $$,
  'event and featured filters preserve all three integrity values'
);

select results_eq(
  $$
    select
      integrity.row_count,
      integrity.id_checksum_sha256,
      integrity.catalog_checksum_sha256
    from public.exhibition_catalog_v2_integrity(
      'catalog-v2-test-empty-event',
      false
    ) as integrity
  $$,
  $$
    values (
      0::bigint,
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'::text,
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'::text
    )
  $$,
  'an empty scope returns zero plus the two SHA-256 empty-stream digests'
);

select is(
  (
    select count(*)::integer
    from public.exhibition_catalog_v2
    where id like 'catalog-v2-test-%'
  ),
  4,
  'anonymous readers see published fixtures and never the draft-only identity'
);

reset role;

select is(
  (
    select count(*)::integer
    from public.exhibition_catalog_v2 as catalog
    where catalog.id like 'catalog-v2-test-%'
      and catalog.content_checksum_sha256 =
        content_private.exhibition_catalog_v2_checksum(catalog)
  ),
  4,
  'every stored fixture checksum equals an independent re-derivation'
);

-- -------------------------------------------------------------------------
-- Service reconciliation and deliberate missing/unexpected/mismatched drift.
-- -------------------------------------------------------------------------

set local role service_role;
insert into pg_temp.catalog_v2_test_state (key, payload)
values ('clean_reconciliation', public.admin_reconcile_exhibition_catalog_v2());
reset role;

select ok(
  (
    select (payload ->> 'in_sync')::boolean
      and (payload ->> 'missing_count')::integer = 0
      and (payload ->> 'unexpected_count')::integer = 0
      and (payload ->> 'mismatched_count')::integer = 0
    from catalog_v2_test_state
    where key = 'clean_reconciliation'
  ),
  'service reconciliation reports a clean trigger-maintained projection'
);

delete from public.exhibition_catalog_v2
where id = 'catalog-v2-test-b';

select ok(
  (
    select (report ->> 'missing_count')::integer = 1
      and exists (
        select 1
        from jsonb_array_elements(report -> 'differences') as difference(value)
        where difference.value ->> 'id' = 'catalog-v2-test-b'
          and difference.value ->> 'status' = 'only_in_canonical'
      )
    from (
      select public.admin_reconcile_exhibition_catalog_v2() as report
    ) as reconciliation
  ),
  'reconciliation identifies an injected missing projection row'
);

select content_private.refresh_exhibition_catalog_v2('catalog-v2-test-b');

insert into public.exhibition_catalog_v2
select (
  jsonb_populate_record(
    null::public.exhibition_catalog_v2,
    (to_jsonb(catalog) - 'content_checksum_sha256')
      || jsonb_build_object('id', 'catalog-v2-test-unexpected')
  )
).*
from public.exhibition_catalog_v2 as catalog
where catalog.id = 'catalog-v2-test-b';

select ok(
  (
    select (report ->> 'unexpected_count')::integer = 1
      and exists (
        select 1
        from jsonb_array_elements(report -> 'differences') as difference(value)
        where difference.value ->> 'id' = 'catalog-v2-test-unexpected'
          and difference.value ->> 'status' = 'only_in_projection'
      )
    from (
      select public.admin_reconcile_exhibition_catalog_v2() as report
    ) as reconciliation
  ),
  'reconciliation identifies an injected unexpected projection row'
);

delete from public.exhibition_catalog_v2
where id = 'catalog-v2-test-unexpected';

insert into catalog_v2_test_state (key, text_value)
select 'b_before_mismatch', content_checksum_sha256
from public.exhibition_catalog_v2
where id = 'catalog-v2-test-b';
update public.exhibition_catalog_v2
set
  name_ko = '조작된 공개 전시 B',
  content_checksum_sha256 = repeat('0', 64)
where id = 'catalog-v2-test-b';

select ok(
  (
    select content_checksum_sha256 <> repeat('0', 64)
      and content_checksum_sha256 <> (
        select text_value
        from catalog_v2_test_state
        where key = 'b_before_mismatch'
      )
      and content_checksum_sha256 =
        content_private.exhibition_catalog_v2_checksum(catalog)
    from public.exhibition_catalog_v2 as catalog
    where id = 'catalog-v2-test-b'
  ),
  'business-field tampering cannot forge its supplied checksum value'
);

select ok(
  (
    select (report ->> 'mismatched_count')::integer = 1
      and exists (
        select 1
        from jsonb_array_elements(report -> 'differences') as difference(value)
        where difference.value ->> 'id' = 'catalog-v2-test-b'
          and difference.value ->> 'status' = 'field_mismatch'
          and difference.value -> 'differing_fields' ? 'name_ko'
      )
    from (
      select public.admin_reconcile_exhibition_catalog_v2() as report
    ) as reconciliation
  ),
  'reconciliation identifies field-level drift even with a valid derived hash'
);

select content_private.refresh_exhibition_catalog_v2('catalog-v2-test-b');

select ok(
  (public.admin_reconcile_exhibition_catalog_v2() ->> 'in_sync')::boolean,
  'refreshing injected drift restores a clean reconciliation result'
);

-- -------------------------------------------------------------------------
-- A projection write failure must abort its originating canonical statement.
-- -------------------------------------------------------------------------

create or replace function pg_temp.fail_catalog_v2_projection_write()
returns trigger
language plpgsql
as $function$
declare
  v_id text;
begin
  if tg_op = 'DELETE' then
    v_id := old.id;
  else
    v_id := new.id;
  end if;

  if v_id = 'catalog-v2-test-atomic' then
    raise exception using
      errcode = 'P0001',
      message = 'forced_catalog_v2_projection_failure';
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$function$;

create trigger test_fail_catalog_v2_projection_write
  before update or delete on public.exhibition_catalog_v2
  for each row
  execute function pg_temp.fail_catalog_v2_projection_write();

select throws_ok(
  $$
    update content.exhibitions
    set archived_at = '2026-07-03 00:00:00+00'
    where id = 'catalog-v2-test-atomic'
  $$,
  'P0001',
  'forced_catalog_v2_projection_failure',
  'a projection DELETE failure aborts canonical archive'
);

select is(
  (
    select archived_at
    from content.exhibitions
    where id = 'catalog-v2-test-atomic'
  ),
  null::timestamptz,
  'failed projection deletion rolls back the canonical archive timestamp'
);

select is(
  (
    select count(*)::integer
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-atomic'
  ),
  1,
  'failed archive leaves the prior public row intact'
);

select throws_ok(
  $$
    update content.exhibition_versions
    set name_ko = '실패해야 하는 원자성 수정'
    where id = '00000000-0000-0000-0000-000000000805'::uuid
  $$,
  'P0001',
  'forced_catalog_v2_projection_failure',
  'a projection UPDATE failure aborts canonical published-version maintenance'
);

select is(
  (
    select name_ko
    from content.exhibition_versions
    where id = '00000000-0000-0000-0000-000000000805'::uuid
  ),
  '원자성 전시',
  'failed projection refresh rolls back the canonical version field'
);

select is(
  (
    select name_ko
    from public.exhibition_catalog_v2
    where id = 'catalog-v2-test-atomic'
  ),
  '원자성 전시',
  'failed projection refresh leaves the prior public field intact'
);

drop trigger test_fail_catalog_v2_projection_write
  on public.exhibition_catalog_v2;

-- Simulate privilege drift before an incident freeze. The command must
-- idempotently remove these grants again while keeping the guard active.
grant insert, update, delete, truncate on public.exhibitions to service_role;

set local role service_role;
select lives_ok(
  $$select public.admin_disable_legacy_exhibition_mirror('incident freeze test')$$,
  'service role can freeze dual projection before an explicitly reconciled rollback'
);
reset role;

select ok(
  exists (
    select 1
    from content_private.exhibition_catalog_runtime as runtime
    where runtime.singleton
      and not runtime.legacy_mirror_enabled
      and runtime.legacy_writes_blocked
      and runtime.legacy_mirror_enabled_at is null
      and runtime.reason = 'incident freeze test'
      and not has_table_privilege(
        'service_role',
        'public.exhibitions',
        'INSERT, UPDATE, DELETE, TRUNCATE'
      )
      and exists (
        select 1
        from content.audit_log as audit
        where audit.action = 'legacy_exhibition_mirror.disabled'
          and audit.entity_id = 'legacy_exhibition_mirror'
          and audit.metadata ->> 'reason' = 'incident freeze test'
          and (audit.metadata ->> 'legacy_writes_blocked')::boolean
          and (audit.metadata ->> 'legacy_dml_remains_revoked')::boolean
      )
  ),
  'disabling repairs privilege drift, freezes with an audit trail, and does not resume Sheet ownership'
);

update content.exhibition_versions
set name_ko = '미러 비활성 검증'
where id = '00000000-0000-0000-0000-000000000803'::uuid;

select ok(
  exists (
    select 1
    from public.exhibition_catalog_v2 as catalog
    join public.exhibitions as legacy using (id)
    where catalog.id = 'catalog-v2-test-b'
      and catalog.name_ko = '미러 비활성 검증'
      and legacy.name_ko = '공개 전시 B'
  ),
  'after disable V2 remains transactional while legacy stops changing'
);

select * from finish();
rollback;
