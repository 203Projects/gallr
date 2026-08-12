begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(51);

select has_table(
  'content_private',
  'legacy_mobile_catalog_mirror_config',
  'legacy mobile mirror has a private fail-closed configuration'
);
select has_function(
  'public',
  'service_replace_legacy_mobile_catalog',
  array['jsonb', 'text', 'text'],
  'legacy mobile mirror exposes one fixed snapshot RPC'
);
select ok(
  has_function_privilege(
    'service_role',
    'public.service_replace_legacy_mobile_catalog(jsonb,text,text)',
    'EXECUTE'
  ),
  'service role can invoke the guarded snapshot RPC'
);
select ok(
  not has_function_privilege(
    'anon',
    'public.service_replace_legacy_mobile_catalog(jsonb,text,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.service_replace_legacy_mobile_catalog(jsonb,text,text)',
    'EXECUTE'
  ),
  'browser roles cannot invoke the cross-project mirror'
);
select is(
  (
    select column_default = 'false'
    from information_schema.columns
    where table_schema = 'content_private'
      and table_name = 'legacy_mobile_catalog_mirror_config'
      and column_name = 'enabled'
  ),
  true,
  'the mirror is disabled by schema default'
);
select is(
  (
    select column_default = 'false'
    from information_schema.columns
    where table_schema = 'content_private'
      and table_name = 'legacy_mobile_catalog_mirror_config'
      and column_name = 'source_outbox_enabled'
  ),
  true,
  'source catalogue enqueueing is disabled by schema default'
);

-- Linked verification runs with Seoul source enqueueing active. Recreate the
-- disabled migration fixture and legacy write boundary inside this transaction;
-- rollback restores the production configuration and snapshot metadata.
update content_private.exhibition_catalog_runtime
set legacy_mirror_enabled = false,
    legacy_writes_blocked = false,
    legacy_mirror_enabled_at = null,
    baseline_row_count = null,
    baseline_id_checksum_sha256 = null,
    baseline_catalog_checksum_sha256 = null,
    reason = 'pgTAP legacy mobile fixture'
where singleton;

update content_private.legacy_mobile_catalog_mirror_config
set enabled = false,
    source_outbox_enabled = false,
    expected_source_project_ref = null,
    max_delete_fraction = 0.25,
    reason = 'pgTAP legacy mobile fixture',
    last_snapshot_sha256 = null,
    last_applied_at = null
where singleton;
select has_function(
  'content_private',
  'invoke_legacy_catalog_mirror',
  array[]::text[],
  'the source has an inert reconciliation invocation entry point'
);
select has_function(
  'content_private',
  'invoke_legacy_catalog_mirror',
  array['text'],
  'the source has a reason-specific immediate invocation entry point'
);
select ok(
  not has_function_privilege(
    'service_role',
    'content_private.invoke_legacy_catalog_mirror()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'content_private.invoke_legacy_catalog_mirror()',
    'EXECUTE'
  ),
  'reconciliation invocation remains database-owner-only'
);
select ok(
  not has_function_privilege(
    'service_role',
    'content_private.invoke_legacy_catalog_mirror(text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'content_private.invoke_legacy_catalog_mirror(text)',
    'EXECUTE'
  ),
  'immediate invocation remains database-owner-only'
);
select has_function(
  'content_private',
  'invalidate_legacy_mobile_catalog_snapshot',
  array[]::text[],
  'the target has a private snapshot invalidation trigger function'
);
select ok(
  not has_function_privilege(
    'service_role',
    'content_private.invalidate_legacy_mobile_catalog_snapshot()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'content_private.invalidate_legacy_mobile_catalog_snapshot()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'content_private.invalidate_legacy_mobile_catalog_snapshot()',
    'EXECUTE'
  ),
  'snapshot invalidation remains database-owner-only'
);
select has_trigger(
  'public',
  'exhibitions',
  'exhibitions_enqueue_legacy_mobile_catalog_sync',
  'exhibition changes enqueue compatibility work when the source is enabled'
);
select has_trigger(
  'public',
  'events',
  'events_enqueue_legacy_mobile_catalog_sync',
  'event changes enqueue compatibility work when the source is enabled'
);
select has_trigger(
  'public',
  'editors',
  'editors_enqueue_legacy_mobile_catalog_sync',
  'editor changes enqueue compatibility work when the source is enabled'
);
select has_trigger(
  'public',
  'exhibition_catalog_v2',
  'exhibition_catalog_v2_enqueue_legacy_mobile_catalog_sync',
  'canonical-v2 changes enqueue compatibility work when the source is enabled'
);
select has_trigger(
  'public',
  'exhibitions',
  'exhibitions_invalidate_legacy_mobile_catalog_snapshot',
  'target exhibition drift invalidates the recorded source snapshot'
);
select has_trigger(
  'public',
  'events',
  'events_invalidate_legacy_mobile_catalog_snapshot',
  'target event drift invalidates the recorded source snapshot'
);
select has_trigger(
  'public',
  'editors',
  'editors_invalidate_legacy_mobile_catalog_snapshot',
  'target editor drift invalidates the recorded source snapshot'
);
select has_trigger(
  'public',
  'exhibition_catalog_v2',
  'exhibition_catalog_v2_invalidate_legacy_mobile_catalog_snapshot',
  'target canonical-v2 drift invalidates the recorded source snapshot'
);

create temp table legacy_mobile_test_snapshot (
  payload jsonb not null
);
grant select on legacy_mobile_test_snapshot to service_role;

insert into public.events (
  id, name_ko, name_en, location_label_ko, location_label_en,
  start_date, end_date, brand_color
) values (
  'legacy-mobile-event', '레거시 행사', 'Legacy Event', '서울', 'Seoul',
  current_date - 1, current_date + 30, '#000000'
);

insert into public.editors (
  id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
  is_active, active_from
) values (
  'legacy-mobile-editor', '레거시 에디터', 'Legacy Editor', '에디터', 'Editor',
  '소개', 'Biography', true, current_date - 1
);

insert into public.exhibitions (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en,
  country_code, region_ko, region_en, opening_date, closing_date,
  description_ko, description_en, address_ko, address_en, event_id, editor_id
) values
  (
    'legacy-mobile-one', '레거시 하나', 'Legacy One', '갤러리', 'Gallery',
    '서울', 'Seoul', 'JP', '종로구', 'Jongno-gu', current_date - 1,
    current_date + 30, '설명', 'Description', '서울', 'Seoul',
    'legacy-mobile-event', 'legacy-mobile-editor'
  ),
  (
    'legacy-mobile-two', '레거시 둘', 'Legacy Two', '갤러리', 'Gallery',
    '서울', 'Seoul', 'JP', '용산구', 'Yongsan-gu', current_date - 1,
    current_date + 30, '설명', 'Description', '서울', 'Seoul',
    'legacy-mobile-event', 'legacy-mobile-editor'
  );

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en,
  country_code, region_ko, region_en, opening_date, closing_date, is_featured,
  latitude, longitude, description_ko, description_en, address_ko, address_en,
  cover_image_url, hours, contact, reception_date, opening_time, event_id,
  editor_id, is_homepage_featured, ticket_url, updated_at, is_editors_pick,
  guest_editor_id, content_checksum_sha256, credits_ko, credits_en
) values
  (
    'legacy-mobile-one', '레거시 하나', 'Legacy One', '갤러리', 'Gallery',
    '서울', 'Seoul', 'JP', '종로구', 'Jongno-gu', current_date - 1,
    current_date + 30, false, null, null, '설명', 'Description', '서울',
    'Seoul', null, null, null, null, null, 'legacy-mobile-event', null,
    false, null, clock_timestamp(), false, null, repeat('0', 64), '', ''
  ),
  (
    'legacy-mobile-two', '레거시 둘', 'Legacy Two', '갤러리', 'Gallery',
    '서울', 'Seoul', 'JP', '용산구', 'Yongsan-gu', current_date - 1,
    current_date + 30, false, null, null, '설명', 'Description', '서울',
    'Seoul', null, null, null, null, null, 'legacy-mobile-event', null,
    false, null, clock_timestamp(), false, null, repeat('0', 64), '', ''
  );

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'legacy_catalog.sync_requested'
      and deduplication_key = format(
        'legacy_catalog:sync_requested:%s',
        pg_catalog.txid_current()
      )
  ),
  0,
  'catalogue writes do not enqueue mirror work before source activation'
);

update content_private.legacy_mobile_catalog_mirror_config
set source_outbox_enabled = true,
    reason = 'test source activation'
where singleton;

select lives_ok(
  $sql$
    update public.events
    set updated_at = clock_timestamp()
    where id = 'legacy-mobile-event'
  $sql$,
  'an unavailable immediate path does not block the catalogue write'
);

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'legacy_catalog.sync_requested'
      and deduplication_key = format(
        'legacy_catalog:sync_requested:%s',
        pg_catalog.txid_current()
      )
  ),
  1,
  'the failed immediate path retains one durable mirror request'
);
select is(
  (
    select aggregate_id
    from content.outbox_events
    where event_type = 'legacy_catalog.sync_requested'
      and deduplication_key = format(
        'legacy_catalog:sync_requested:%s',
        pg_catalog.txid_current()
      )
  ),
  'public-catalogue',
  'the mirror request identifies only the public catalogue aggregate'
);

delete from content.outbox_events
where event_type = 'legacy_catalog.sync_requested'
  and deduplication_key = format(
    'legacy_catalog:sync_requested:%s',
    pg_catalog.txid_current()
  );

-- Replace only the asynchronous request helper inside this rolled-back test
-- transaction. The catalogue trigger must request one immediate sync for the
-- transaction while still writing the durable outbox fallback.
create or replace function content_private.invoke_legacy_catalog_mirror(
  p_source text
)
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  perform pg_catalog.set_config(
    'gallr.test_legacy_mirror_sources',
    coalesce(
      pg_catalog.current_setting(
        'gallr.test_legacy_mirror_sources',
        true
      ),
      ''
    ) || p_source || ',',
    true
  );
  return 1;
end;
$function$;

update public.exhibitions
set updated_at = clock_timestamp()
where id like 'legacy-mobile-%';

select is(
  (
    select count(*)::integer
    from content.outbox_events
    where event_type = 'legacy_catalog.sync_requested'
      and deduplication_key = format(
        'legacy_catalog:sync_requested:%s',
        pg_catalog.txid_current()
      )
  ),
  1,
  'a multi-row catalogue transaction enqueues one durable mirror request'
);
select is(
  pg_catalog.current_setting('gallr.test_legacy_mirror_sources', true),
  'outbox,',
  'a catalogue transaction also requests one immediate compatibility sync'
);

update content_private.legacy_mobile_catalog_mirror_config
set source_outbox_enabled = false,
    reason = 'test target activation'
where singleton;

insert into legacy_mobile_test_snapshot (payload)
select jsonb_build_object(
  'events', coalesce(
    (select jsonb_agg(to_jsonb(event_row) order by event_row.id) from public.events event_row),
    '[]'::jsonb
  ),
  'editors', coalesce(
    (select jsonb_agg(to_jsonb(editor_row) order by editor_row.id) from public.editors editor_row),
    '[]'::jsonb
  ),
  'exhibitions', coalesce(
    (
      select jsonb_agg(
        to_jsonb(exhibition_row) - 'is_editors_pick' - 'guest_editor_id'
        order by exhibition_row.id
      )
      from public.exhibitions exhibition_row
    ),
    '[]'::jsonb
  ),
  'exhibition_catalog_v2', coalesce(
    (
      select jsonb_agg(to_jsonb(canonical_row) order by canonical_row.id)
      from public.exhibition_catalog_v2 canonical_row
    ),
    '[]'::jsonb
  )
);

-- Simulate target drift after the Seoul snapshot was captured. The complete
-- snapshot apply must repair country identity before validating its checksum.
update public.exhibitions
set country_code = 'KR'
where id like 'legacy-mobile-%';
update public.exhibition_catalog_v2
set country_code = 'KR'
where id like 'legacy-mobile-%';

set local role service_role;
select throws_ok(
  format(
    'select public.service_replace_legacy_mobile_catalog(%L::jsonb, %L, %L)',
    (select payload::text from legacy_mobile_test_snapshot),
    'oqrvbstopuppznxqoonp',
    'test disabled mirror'
  ),
  '42501',
  'legacy_mobile_catalog_mirror_disabled',
  'service role cannot mirror before an owner enables the exact target'
);
reset role;

update content_private.exhibition_catalog_runtime
set legacy_mirror_enabled = false,
    legacy_writes_blocked = true,
    legacy_mirror_enabled_at = null,
    reason = 'legacy mobile mirror test freeze'
where singleton;

update content_private.legacy_mobile_catalog_mirror_config
set enabled = true,
    expected_source_project_ref = 'oqrvbstopuppznxqoonp',
    max_delete_fraction = 0.25,
    reason = 'test activation'
where singleton;

set local role service_role;
select throws_ok(
  format(
    'select public.service_replace_legacy_mobile_catalog(%L::jsonb, %L, %L)',
    (select payload::text from legacy_mobile_test_snapshot),
    'abcdefghijklmnopqrst',
    'test wrong source'
  ),
  '42501',
  'legacy_mobile_catalog_source_mismatch',
  'the configured source project identity is enforced'
);

select is(
  public.service_replace_legacy_mobile_catalog(
    (select payload - 'exhibition_catalog_v2' from legacy_mobile_test_snapshot),
    'oqrvbstopuppznxqoonp',
    'test transition-compatible legacy snapshot'
  ) ->> 'status',
  'applied',
  'the receiver remains compatible with the three-resource coordinator during rollout'
);

select throws_ok(
  format(
    'select public.service_replace_legacy_mobile_catalog(%L::jsonb, %L, %L)',
    (
      select jsonb_set(
        payload,
        '{exhibition_catalog_v2,0,content_checksum_sha256}',
        to_jsonb(repeat('f', 64))
      )::text
      from legacy_mobile_test_snapshot
    ),
    'oqrvbstopuppznxqoonp',
    'test corrupt canonical-v2 checksum'
  ),
  '22023',
  'legacy_mobile_catalog_canonical_v2_checksum_mismatch',
  'a canonical-v2 payload with a forged checksum fails atomically'
);

select is(
  public.service_replace_legacy_mobile_catalog(
    (select payload from legacy_mobile_test_snapshot),
    'oqrvbstopuppznxqoonp',
    'test initial snapshot'
  ) ->> 'status',
  'applied',
  'a complete guarded snapshot is applied'
);
reset role;

select matches(
  (
    select last_snapshot_sha256
    from content_private.legacy_mobile_catalog_mirror_config
    where singleton
  ),
  '^[0-9a-f]{64}$',
  'mirror-owned writes retain the canonical snapshot marker'
);

set local role service_role;
select is(
  public.service_replace_legacy_mobile_catalog(
    (select payload from legacy_mobile_test_snapshot),
    'oqrvbstopuppznxqoonp',
    'test idempotent replay'
  ) ->> 'status',
  'unchanged',
  'replaying an identical canonical snapshot is idempotent'
);
reset role;

select is(
  (select count(*)::integer from public.exhibitions where id like 'legacy-mobile-%'),
  2,
  'the legacy exhibition reader retains the complete snapshot'
);
select is(
  (select count(*)::integer from public.events where id = 'legacy-mobile-event'),
  1,
  'event rows required by installed clients are mirrored'
);
select is(
  (select count(*)::integer from public.editors where id = 'legacy-mobile-editor'),
  1,
  'editor rows required by installed clients are mirrored'
);
select is(
  (
    select count(*)::integer
    from public.exhibition_catalog_v2
    where id like 'legacy-mobile-%'
  ),
  2,
  'canonical-v2 rows required by versions 1.7.4 and 1.7.5 are mirrored'
);
select is(
  (
    select city_ko || '/' || city_en
    from public.exhibition_catalog_v2
    where id = 'legacy-mobile-one'
  ),
  '서울/Seoul',
  'canonical-v2 city labels retain the normalized source pair'
);
select is(
  (select country_code from public.exhibitions where id = 'legacy-mobile-one'),
  'JP',
  'legacy readers receive the authoritative country identity'
);
select is(
  (
    select country_code
    from public.exhibition_catalog_v2
    where id = 'legacy-mobile-one'
  ),
  'JP',
  'canonical-v2 readers receive the authoritative country identity'
);
select is(
  (
    select target.content_checksum_sha256
    from public.exhibition_catalog_v2 target
    where target.id = 'legacy-mobile-one'
  ),
  (
    select item ->> 'content_checksum_sha256'
    from legacy_mobile_test_snapshot,
      lateral jsonb_array_elements(payload -> 'exhibition_catalog_v2') item
    where item ->> 'id' = 'legacy-mobile-one'
  ),
  'canonical-v2 content remains checksum-identical to the source snapshot'
);
select is(
  (select is_editors_pick from public.exhibitions where id = 'legacy-mobile-one'),
  false,
  'generated compatibility columns remain database-owned'
);
select is(
  (
    select count(*)::integer
    from content.audit_log
    where action = 'legacy_mobile_catalog_mirror.applied'
  ),
  2,
  'only a changed snapshot appends an audit record'
);

-- Reconciliation must compare the target rows, not only remember the last
-- source hash. Simulate an out-of-band target drift while the Seoul snapshot
-- stays unchanged, then verify the next replay repairs it. Temporarily open
-- ordinary writes rather than impersonating the mirror-owned write context.
update content_private.exhibition_catalog_runtime
set legacy_writes_blocked = false
where singleton;

update public.events
set cover_image_url = 'https://drift.invalid/event.jpg'
where id = 'legacy-mobile-event';

update content_private.exhibition_catalog_runtime
set legacy_writes_blocked = true
where singleton;

select is(
  (
    select last_snapshot_sha256
    from content_private.legacy_mobile_catalog_mirror_config
    where singleton
  ),
  null,
  'an out-of-band target write invalidates the snapshot marker'
);

set local role service_role;
select is(
  public.service_replace_legacy_mobile_catalog(
    (select payload from legacy_mobile_test_snapshot),
    'oqrvbstopuppznxqoonp',
    'test target drift repair'
  ) ->> 'status',
  'applied',
  'an unchanged source snapshot repairs target catalogue drift'
);
reset role;

select is(
  (select cover_image_url from public.events where id = 'legacy-mobile-event'),
  null,
  'target drift is restored to the authoritative snapshot value'
);
select is(
  (
    select count(*)::integer
    from content.audit_log
    where action = 'legacy_mobile_catalog_mirror.applied'
  ),
  3,
  'a target repair appends one additional audit record'
);

update public.exhibition_catalog_v2
set city_en = ''
where id = 'legacy-mobile-one';

select is(
  (
    select last_snapshot_sha256
    from content_private.legacy_mobile_catalog_mirror_config
    where singleton
  ),
  null,
  'an out-of-band canonical-v2 write invalidates the snapshot marker'
);

set local role service_role;
select is(
  public.service_replace_legacy_mobile_catalog(
    (select payload from legacy_mobile_test_snapshot),
    'oqrvbstopuppznxqoonp',
    'test canonical-v2 drift repair'
  ) ->> 'status',
  'applied',
  'an unchanged source snapshot repairs canonical-v2 target drift'
);
reset role;

select is(
  (select city_en from public.exhibition_catalog_v2 where id = 'legacy-mobile-one'),
  'Seoul',
  'canonical-v2 city drift is restored to the authoritative snapshot value'
);

update legacy_mobile_test_snapshot
set payload = jsonb_set(
  payload,
  '{exhibitions}',
  (
    select jsonb_agg(item)
    from jsonb_array_elements(payload -> 'exhibitions') item
    where item ->> 'id' = 'legacy-mobile-one'
  )
);

set local role service_role;
select throws_ok(
  format(
    'select public.service_replace_legacy_mobile_catalog(%L::jsonb, %L, %L)',
    (select payload::text from legacy_mobile_test_snapshot),
    'oqrvbstopuppznxqoonp',
    'test excessive delete'
  ),
  '22023',
  'legacy_mobile_catalog_delete_limit_exceeded',
  'an unexpectedly destructive snapshot fails closed'
);
reset role;

select * from finish();
rollback;
