begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(39);

select has_column('content', 'galleries', 'revision',
  'Gallery Info has one aggregate revision');
select col_default_is('content', 'galleries', 'revision', '1',
  'Gallery Info revisions start at one');
select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'owner_get_gallery_info',
        'owner_save_gallery_info',
        'geocode_current_caller',
        'geocode_consume_rate_limit'
      )
      and not procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  4,
  'Gallery Info and geocode wrappers are SECURITY INVOKER with empty search paths'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.owner_get_gallery_info()'),
        ('public.owner_save_gallery_info(integer,jsonb)'),
        ('public.geocode_current_caller()'),
        ('public.geocode_consume_rate_limit()')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  4,
  'authenticated callers receive only the explicit Gallery Info/geocode RPCs'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.owner_get_gallery_info()'),
        ('public.owner_save_gallery_info(integer,jsonb)'),
        ('public.geocode_current_caller()'),
        ('public.geocode_consume_rate_limit()')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
       or has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  0,
  'anonymous and service roles receive no Gallery Info/geocode RPC grant'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('content.galleries'),
        ('content.venues'),
        ('content.gallery_memberships')
    ) as relation(name)
    where has_table_privilege('authenticated', relation.name, 'INSERT')
       or has_table_privilege('authenticated', relation.name, 'UPDATE')
       or has_table_privilege('authenticated', relation.name, 'DELETE')
  ),
  0,
  'Gallery Info adds no direct canonical table write privileges'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000002401', 'gallery-info-active@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002402', 'gallery-info-new@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002403', 'gallery-info-claimant@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002404', 'gallery-info-staff@example.invalid', now(), '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values (
  '00000000-0000-0000-0000-000000002404',
  'contributor'::content.staff_role,
  true
);

insert into content.venues (
  id, slug, name_ko, name_en, city_ko, city_en, region_ko, region_en,
  address_ko, address_en, latitude, longitude, default_hours, default_contact
)
values (
  '24000000-0000-0000-0000-000000000001', 'gallery-info-shared',
  '공유 갤러리', 'Shared Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu',
  '서울특별시 종로구 이전로 1', '1 Previous-ro, Jongno-gu, Seoul',
  37.5701, 126.9801, 'Tue-Sun 10:00-18:00', 'old@example.invalid'
);

insert into content.galleries (
  id, canonical_venue_id, name_ko, name_en, status, created_by, updated_by
)
values
  (
    '24100000-0000-0000-0000-000000000001',
    '24000000-0000-0000-0000-000000000001',
    '활성 갤러리', 'Active Gallery', 'active',
    '00000000-0000-0000-0000-000000002401',
    '00000000-0000-0000-0000-000000002401'
  ),
  (
    '24100000-0000-0000-0000-000000000002',
    null,
    '새 갤러리', 'New Gallery', 'pending',
    '00000000-0000-0000-0000-000000002402',
    '00000000-0000-0000-0000-000000002402'
  ),
  (
    '24100000-0000-0000-0000-000000000003',
    null,
    '기존 갤러리', 'Existing Gallery', 'active', null, null
  ),
  (
    '24100000-0000-0000-0000-000000000004',
    '24000000-0000-0000-0000-000000000001',
    '다른 갤러리', 'Other Gallery', 'active', null, null
  );

insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_website_url, created_by, updated_by
)
values
  (
    '24100000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000002401',
    'active', 'https://active.example.invalid',
    '00000000-0000-0000-0000-000000002401',
    '00000000-0000-0000-0000-000000002401'
  ),
  (
    '24100000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000002402',
    'pending', 'https://new.example.invalid',
    '00000000-0000-0000-0000-000000002402',
    '00000000-0000-0000-0000-000000002402'
  ),
  (
    '24100000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000002403',
    'pending', 'https://existing.example.invalid',
    '00000000-0000-0000-0000-000000002403',
    '00000000-0000-0000-0000-000000002403'
  );

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002401","role":"authenticated"}',
  true
);

select is(public.owner_get_gallery_info() ->> 'gallery_id',
  '24100000-0000-0000-0000-000000000001',
  'an active owner reads only their Gallery Info aggregate');
select is(public.owner_get_gallery_info() ->> 'revision', '1',
  'Gallery Info returns the optimistic revision');
select is(public.owner_get_gallery_info() ->> 'address_ko',
  '서울특별시 종로구 이전로 1',
  'Gallery Info returns canonical venue defaults');
select ok(
  not public.owner_get_gallery_info() ? 'canonical_venue_id'
    and not public.owner_get_gallery_info() ? 'user_id'
    and not public.owner_get_gallery_info() ? 'created_by',
  'Gallery Info omits internal identifiers and actor fields'
);

create temporary table gallery_info_state (
  key text primary key,
  value text not null
) on commit drop;
grant select, insert, update, delete on gallery_info_state to authenticated;

with saved as (
  select public.owner_save_gallery_info(
    1,
    jsonb_build_object(
      'name_ko', '활성 갤러리 새 이름',
      'name_en', 'Active Gallery Revised',
      'venue_name_ko', '활성 전시장',
      'venue_name_en', 'Active Venue',
      'city_ko', '서울',
      'city_en', 'Seoul',
      'region_ko', '용산구',
      'region_en', 'Yongsan-gu',
      'address_ko', '서울특별시 용산구 새로 2',
      'address_en', '2 Sae-ro, Yongsan-gu, Seoul',
      'latitude', 37.5402,
      'longitude', 126.9902,
      'hours', 'Wed-Mon 11:00-19:00',
      'contact', 'new@example.invalid'
    )
  ) as payload
)
insert into gallery_info_state (key, value)
select 'active_revision', payload ->> 'revision' from saved;

select is((select value::integer from gallery_info_state where key = 'active_revision'), 2,
  'a successful Gallery Info save increments the aggregate revision once');
reset role;
insert into gallery_info_state (key, value)
select 'active_venue_id', canonical_venue_id::text
from content.galleries
where id = '24100000-0000-0000-0000-000000000001';
select isnt(
  (select value::uuid from gallery_info_state where key = 'active_venue_id'),
  '24000000-0000-0000-0000-000000000001'::uuid,
  'a shared canonical venue is cloned before owner mutation'
);
select is(
  (
    select canonical_venue_id
    from content.galleries
    where id = '24100000-0000-0000-0000-000000000004'
  ),
  '24000000-0000-0000-0000-000000000001'::uuid,
  'clone-on-write leaves another gallery tenant linked to the original venue'
);
select is(
  (
    select address_ko
    from content.venues
    where id = '24000000-0000-0000-0000-000000000001'
  ),
  '서울특별시 종로구 이전로 1',
  'clone-on-write does not alter the other tenant venue'
);
select is(
  (
    select count(*)::integer
    from content.audit_log
    where actor_user_id = '00000000-0000-0000-0000-000000002401'
      and action = 'gallery.info_saved'
      and entity_id = '24100000-0000-0000-0000-000000000001'
      and metadata ->> 'revision' = '2'
  ),
  1,
  'Gallery Info saves emit one tenant-scoped audit record'
);
select ok(
  (
    select metadata::text not like '%new@example.invalid%'
    from content.audit_log
    where actor_user_id = '00000000-0000-0000-0000-000000002401'
      and action = 'gallery.info_saved'
  ),
  'Gallery Info audit metadata omits contact values'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002401","role":"authenticated"}',
  true
);
select throws_ok(
  $$select public.owner_save_gallery_info(1, '{"name_ko":"stale"}'::jsonb)$$,
  '40001', 'revision_conflict',
  'stale Gallery Info writes fail without overwriting newer data'
);
select throws_ok(
  $$select public.owner_save_gallery_info(2, '{"gallery_id":"cross-tenant"}'::jsonb)$$,
  '22023', 'gallery_info_field_not_allowed',
  'Gallery Info rejects ownership and identifier fields'
);
select throws_ok(
  $$select public.owner_save_gallery_info(2, '{"latitude":37.5}'::jsonb)$$,
  '22023', 'gallery_info_location_invalid',
  'Gallery Info rejects an incomplete coordinate pair'
);

with created as (
  select public.owner_create_exhibition_draft(
    '24200000-0000-0000-0000-000000000001'
  ) as payload
)
insert into gallery_info_state (key, value)
select 'first_exhibition_id', payload ->> 'id' from created
union all
select 'first_version_id', payload ->> 'working_version_id' from created;

reset role;
select is(
  (
    select jsonb_build_array(
      venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
      address_ko, address_en, latitude, longitude, hours, contact
    )
    from content.exhibition_versions
    where id = (select value::uuid from gallery_info_state where key = 'first_version_id')
  ),
  jsonb_build_array(
    '활성 전시장', 'Active Venue', '서울', 'Seoul', '용산구', 'Yongsan-gu',
    '서울특별시 용산구 새로 2', '2 Sae-ro, Yongsan-gu, Seoul',
    37.5402, 126.9902, 'Wed-Mon 11:00-19:00', 'new@example.invalid'
  ),
  'a new exhibition snapshots every matching Gallery Info venue field'
);
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002401","role":"authenticated"}',
  true
);
select is(
  public.owner_save_exhibition_draft(
    (select value from gallery_info_state where key = 'first_exhibition_id'),
    (select value::uuid from gallery_info_state where key = 'first_version_id'),
    1,
    jsonb_build_object('latitude', 37.5503, 'longitude', 127.0103)
  ) ->> 'latitude',
  '37.5503',
  'the copied exhibition coordinates remain independently editable'
);
select is(public.owner_get_gallery_info() ->> 'latitude', '37.5402',
  'editing exhibition coordinates does not alter Gallery Info');

select is(
  public.owner_save_gallery_info(
    2,
    jsonb_build_object(
      'name_ko', '활성 갤러리 새 이름',
      'name_en', 'Active Gallery Revised',
      'venue_name_ko', '두 번째 전시장',
      'venue_name_en', 'Second Venue',
      'city_ko', '서울',
      'city_en', 'Seoul',
      'region_ko', '성동구',
      'region_en', 'Seongdong-gu',
      'address_ko', '서울특별시 성동구 다음로 3',
      'address_en', '3 Daeum-ro, Seongdong-gu, Seoul',
      'latitude', 37.5603,
      'longitude', 127.0403,
      'hours', 'Daily 12:00-20:00',
      'contact', 'second@example.invalid'
    )
  ) ->> 'revision',
  '3',
  'a later Gallery Info save succeeds against the latest revision'
);
reset role;
select is(
  (
    select jsonb_build_array(
      venue_name_ko, address_ko, latitude, longitude, hours, contact
    )
    from content.exhibition_versions
    where id = (select value::uuid from gallery_info_state where key = 'first_version_id')
  ),
  jsonb_build_array(
    '활성 전시장', '서울특별시 용산구 새로 2',
    37.5503, 127.0103, 'Wed-Mon 11:00-19:00', 'new@example.invalid'
  ),
  'later Gallery Info changes never rewrite an existing draft snapshot'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002401","role":"authenticated"}',
  true
);
select is(
  public.owner_create_exhibition_draft(
    '24200000-0000-0000-0000-000000000002'
  ) ->> 'venue_name_ko',
  '두 번째 전시장',
  'the next exhibition receives the latest Gallery Info defaults'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002402","role":"authenticated"}',
  true
);
select is(public.owner_get_gallery_info() ->> 'gallery_id',
  '24100000-0000-0000-0000-000000000002',
  'a pending owner can read a brand-new pending gallery they created');
select is(
  public.owner_save_gallery_info(
    1,
    jsonb_build_object(
      'name_ko', '새 갤러리', 'name_en', 'New Gallery',
      'venue_name_ko', '새 전시장', 'venue_name_en', 'New Venue',
      'city_ko', '부산', 'city_en', 'Busan',
      'region_ko', '해운대구', 'region_en', 'Haeundae-gu',
      'address_ko', '부산광역시 해운대구 새길 4',
      'address_en', '4 Sae-gil, Haeundae-gu, Busan',
      'latitude', 35.1604, 'longitude', 129.1604,
      'hours', 'Tue-Sun 10:00-18:00', 'contact', 'new-gallery@example.invalid'
    )
  ) ->> 'revision',
  '2',
  'a pending creator can save their new pending gallery'
);
reset role;
select ok(
  (
    select canonical_venue_id is not null
    from content.galleries
    where id = '24100000-0000-0000-0000-000000000002'
  ),
  'the first pending-gallery save creates and links a canonical venue'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002403","role":"authenticated"}',
  true
);
select throws_ok(
  $$select public.owner_get_gallery_info()$$,
  '42501', 'gallery_info_access_denied',
  'a pending claimant cannot read an existing active gallery'
);
select throws_ok(
  $$select public.owner_save_gallery_info(1, '{}'::jsonb)$$,
  '42501', 'gallery_info_access_denied',
  'a pending claimant cannot mutate an existing active gallery'
);
select throws_ok(
  $$select public.geocode_current_caller()$$,
  '42501', 'geocode_access_required',
  'a pending existing-gallery claimant cannot use owner geocoding'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002401","role":"authenticated"}',
  true
);
select is(public.geocode_current_caller() ->> 'caller_type', 'owner',
  'an eligible active owner can use the shared geocoder');
reset role;
truncate table content_private.geocode_rate_limit_windows;
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002401","role":"authenticated"}',
  true
);
select is(public.geocode_consume_rate_limit() ->> 'allowed', 'true',
  'an eligible owner can atomically consume geocoding quota');
reset role;
select is(
  (
    select request_count
    from content_private.geocode_rate_limit_windows
    where scope = 'owner'
      and subject_key = '00000000-0000-0000-0000-000000002401'
      and window_started_at = date_trunc('minute', transaction_timestamp())
  ),
  1,
  'owner geocoding uses a distributed per-caller counter'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002404","role":"authenticated"}',
  true
);
select is(public.geocode_current_caller() ->> 'caller_type', 'staff',
  'existing active staff geocoding access continues to work');
select is(public.geocode_consume_rate_limit() ->> 'allowed', 'true',
  'staff and owner geocoding share the generic quota boundary');

reset role;
select is(
  (
    select request_count
    from content_private.geocode_rate_limit_windows
    where scope = 'project'
      and subject_key = 'project'
      and window_started_at = date_trunc('minute', transaction_timestamp())
  ),
  2,
  'staff and owner requests increment the same distributed project counter'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002401","role":"authenticated"}',
  true
);
select is(
  (
    select count(*)::integer
    from generate_series(1, 9)
    where public.geocode_consume_rate_limit() ->> 'allowed' = 'true'
  ),
  9,
  'an owner may consume exactly ten requests in the active minute'
);
select is(public.geocode_consume_rate_limit() ->> 'limited_by', 'owner',
  'the eleventh owner request fails closed at the per-caller ceiling');

select * from finish();
rollback;
