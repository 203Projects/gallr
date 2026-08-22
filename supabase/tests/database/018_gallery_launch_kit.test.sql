begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(60);

select has_table('content', 'launch_kits', 'launch kit entitlements exist');
select has_table('content', 'launch_guests', 'private launch guest list exists');
select has_table('content', 'launch_rsvp_rate_limits', 'public RSVP rate limits exist');
select has_table('content', 'stripe_webhook_events', 'Stripe webhook idempotency exists');
select is(
  (
    select array_agg(enum_value.enumlabel::text order by enum_value.enumsortorder)
    from pg_catalog.pg_type as enum_type
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = enum_type.typnamespace
    join pg_catalog.pg_enum as enum_value
      on enum_value.enumtypid = enum_type.oid
    where namespace.nspname = 'content'
      and enum_type.typname = 'launch_kit_entitlement_source'
  ),
  array['free_beta', 'paid']::text[],
  'Launch Kit entitlement source is provider-independent and closed'
);
select has_column(
  'content', 'launch_kits', 'entitlement_source',
  'Launch Kits record their explicit entitlement source'
);
select is(
  (
    select count(*)::integer
    from information_schema.columns
    where table_schema = 'content'
      and table_name = 'launch_kits'
      and column_name in (
        'stripe_price_id', 'stripe_checkout_session_id',
        'stripe_payment_intent_id', 'stripe_event_id',
        'amount_total', 'currency', 'checkout_attempt'
      )
  ),
  7,
  'historical payment evidence columns remain available for paid backfill and audit'
);
select is(
  (
    select array_agg(constraint_record.conname::text order by constraint_record.conname)
    from pg_catalog.pg_constraint as constraint_record
    where constraint_record.conrelid = 'content.launch_kits'::regclass
      and constraint_record.conname in (
        'launch_kits_active_entitlement',
        'launch_kits_free_beta_without_payment',
        'launch_kits_paid_entitlement_evidence'
      )
  ),
  array[
    'launch_kits_active_entitlement',
    'launch_kits_free_beta_without_payment',
    'launch_kits_paid_entitlement_evidence'
  ]::text[],
  'active, free-beta, and paid evidence constraints are installed'
);
select ok(
  not exists (
    select 1
    from pg_catalog.pg_constraint as constraint_record
    where constraint_record.conrelid = 'content.launch_kits'::regclass
      and constraint_record.conname = 'launch_kits_active_payment'
  ),
  'the historical all-active-Kits-require-payment constraint is absent'
);
select is(
  (
    select count(*)::integer
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname in (
        'launch_kits', 'launch_guests',
        'launch_rsvp_rate_limits', 'stripe_webhook_events'
      )
      and relation.relrowsecurity
  ),
  4,
  'RLS is enabled on all Launch Kit tables'
);
select is(
  (
    select count(*)::integer
    from (values
      ('content.launch_kits'),
      ('content.launch_guests'),
      ('content.launch_rsvp_rate_limits'),
      ('content.stripe_webhook_events')
    ) as relation(name)
    where has_table_privilege('anon', relation.name, 'SELECT')
       or has_table_privilege('anon', relation.name, 'INSERT')
       or has_table_privilege('authenticated', relation.name, 'SELECT')
       or has_table_privilege('authenticated', relation.name, 'INSERT')
       or has_table_privilege('authenticated', relation.name, 'UPDATE')
  ),
  0,
  'browser roles receive no generic Launch Kit table privileges'
);
select is(
  (
    select count(*)::integer
    from (values
      ('public.owner_activate_launch_kit(text,uuid)'),
      ('public.owner_list_launch_kits()'),
      ('public.owner_list_launch_guests(uuid,text,text,timestamp with time zone,uuid,integer)'),
      ('public.owner_add_launch_guest(uuid,text,text,integer,uuid)'),
      ('public.owner_check_in_launch_guest(uuid,uuid,uuid)'),
      ('public.owner_rotate_launch_rsvp_token(uuid,uuid)')
    ) as signature(value)
    where coalesce(
      has_function_privilege(
        'authenticated', to_regprocedure(signature.value), 'EXECUTE'
      ),
      false
    )
  ),
  6,
  'owners receive only the explicit Launch Kit command surface'
);
select ok(
  coalesce(
    has_function_privilege(
      'authenticated',
      to_regprocedure('public.owner_activate_launch_kit(text,uuid)'),
      'EXECUTE'
    ),
    false
  )
  and not coalesce(
    has_function_privilege(
      'anon',
      to_regprocedure('public.owner_activate_launch_kit(text,uuid)'),
      'EXECUTE'
    ),
    false
  )
  and not coalesce(
    has_function_privilege(
      'service_role',
      to_regprocedure('public.owner_activate_launch_kit(text,uuid)'),
      'EXECUTE'
    ),
    false
  ),
  'the public activation command is executable only by authenticated owners'
);
select is(
  (
    select count(*)::integer
    from (values
      ('public.service_public_launch_kit(uuid)'),
      ('public.service_submit_launch_rsvp(uuid,text,text,integer,boolean,text)')
    ) as signature(value)
    where has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  2,
  'service role receives only the narrow public RSVP surface'
);
select is(
  (
    select count(*)::integer
    from (values
      ('public.owner_activate_launch_kit(text,uuid)'),
      ('public.owner_list_launch_kits()'),
      ('public.service_submit_launch_rsvp(uuid,text,text,integer,boolean,text)')
    ) as signature(value)
    where coalesce(
      has_function_privilege('anon', to_regprocedure(signature.value), 'EXECUTE'),
      false
    )
  ),
  0,
  'anonymous callers cannot bypass Edge authorization'
);
select is(
  (
    select count(*)::integer
    from (values
      ('public.owner_prepare_launch_kit_checkout(text,uuid)'),
      ('public.service_attach_launch_kit_checkout(uuid,text,text,integer)'),
      ('public.service_activate_launch_kit(text,text,text,bigint,text)'),
      ('content_private.owner_prepare_launch_kit_checkout_impl(text,uuid)'),
      ('content_private.service_attach_launch_kit_checkout_impl(uuid,text,text,integer)'),
      ('content_private.service_activate_launch_kit_impl(text,text,text,bigint,text)')
    ) as signature(value)
    where to_regprocedure(signature.value) is not null
  ),
  0,
  'obsolete public and private payment RPCs are absent'
);
select ok(
  coalesce(
    has_function_privilege(
      'authenticated',
      to_regprocedure('content_private.owner_activate_launch_kit_impl(text,uuid)'),
      'EXECUTE'
    ),
    false
  )
  and not coalesce(
    has_function_privilege(
      'anon',
      to_regprocedure('content_private.owner_activate_launch_kit_impl(text,uuid)'),
      'EXECUTE'
    ),
    false
  )
  and not coalesce(
    has_function_privilege(
      'service_role',
      to_regprocedure('content_private.owner_activate_launch_kit_impl(text,uuid)'),
      'EXECUTE'
    ),
    false
  ),
  'the private activation implementation is executable only by authenticated owners'
);
select is(
  (
    select count(*)::integer
    from (values
      ('content_private.owner_launch_kit_json(uuid)'),
      ('content_private.owner_assert_active_launch_kit(uuid)'),
      ('content_private.launch_guest_json(content.launch_guests)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  0,
  'owners cannot bypass tenant RPCs through internal Launch Kit helpers'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000001101', 'launch-owner@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000001102', 'launch-other@example.invalid', now(), '{}'::jsonb);

insert into content.galleries (id, name_ko, status)
values
  ('b1000000-0000-0000-0000-000000000001', '런치 갤러리', 'active'),
  ('b1000000-0000-0000-0000-000000000002', '다른 런치 갤러리', 'active');

insert into content.gallery_memberships (gallery_id, user_id, status, claim_note)
values
  ('b1000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000001101', 'active', 'test'),
  ('b1000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000001102', 'active', 'test');

insert into content.exhibitions (id, gallery_id, owner_status)
values
  ('launch-published', 'b1000000-0000-0000-0000-000000000001', 'published'),
  ('launch-draft', 'b1000000-0000-0000-0000-000000000001', 'draft'),
  ('launch-other', 'b1000000-0000-0000-0000-000000000002', 'published'),
  ('launch-pending-clean', 'b1000000-0000-0000-0000-000000000001', 'published'),
  ('launch-pending-payment', 'b1000000-0000-0000-0000-000000000001', 'published'),
  ('launch-cancelled', 'b1000000-0000-0000-0000-000000000001', 'published'),
  ('launch-refunded', 'b1000000-0000-0000-0000-000000000001', 'published'),
  ('launch-paid-existing', 'b1000000-0000-0000-0000-000000000001', 'published');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, status, name_ko, name_en,
  venue_name_ko, city_ko, region_ko, address_ko,
  opening_date, closing_date, hours, reception_date, opening_time, published_at
)
values
  (
    'b2000000-0000-0000-0000-000000000001', 'launch-published', 1,
    'published', '작은 방의 기록', 'Notes from a Small Room', '런치 갤러리',
    '서울', '종로구', '서울 종로구', current_date, current_date + 30,
    '11:00-18:00', now() + interval '7 days', '19:00', now()
  ),
  (
    'b2000000-0000-0000-0000-000000000002', 'launch-draft', 1,
    'draft', '초안', '', '런치 갤러리', '서울', '종로구', '서울 종로구',
    current_date, current_date + 30, '11:00-18:00', null, null, null
  ),
  (
    'b2000000-0000-0000-0000-000000000003', 'launch-other', 1,
    'published', '다른 전시', 'Other Exhibition', '다른 갤러리',
    '서울', '용산구', '서울 용산구', current_date, current_date + 30,
    '11:00-18:00', now() + interval '7 days', '18:00', now()
  ),
  (
    'b2000000-0000-0000-0000-000000000004', 'launch-pending-clean', 1,
    'published', '결제 없는 대기 전시', 'Clean Pending Exhibition', '런치 갤러리',
    '서울', '종로구', '서울 종로구', current_date, current_date + 30,
    '11:00-18:00', now() + interval '8 days', '18:00', now()
  ),
  (
    'b2000000-0000-0000-0000-000000000005', 'launch-pending-payment', 1,
    'published', '결제 흔적 대기 전시', 'Payment Pending Exhibition', '런치 갤러리',
    '서울', '종로구', '서울 종로구', current_date, current_date + 30,
    '11:00-18:00', now() + interval '9 days', '18:00', now()
  ),
  (
    'b2000000-0000-0000-0000-000000000006', 'launch-cancelled', 1,
    'published', '취소된 런치 전시', 'Cancelled Launch Exhibition', '런치 갤러리',
    '서울', '종로구', '서울 종로구', current_date, current_date + 30,
    '11:00-18:00', now() + interval '10 days', '18:00', now()
  ),
  (
    'b2000000-0000-0000-0000-000000000007', 'launch-refunded', 1,
    'published', '환불된 런치 전시', 'Refunded Launch Exhibition', '런치 갤러리',
    '서울', '종로구', '서울 종로구', current_date, current_date + 30,
    '11:00-18:00', now() + interval '11 days', '18:00', now()
  ),
  (
    'b2000000-0000-0000-0000-000000000008', 'launch-paid-existing', 1,
    'published', '기존 유료 런치 전시', 'Existing Paid Launch Exhibition', '런치 갤러리',
    '서울', '종로구', '서울 종로구', current_date, current_date + 30,
    '11:00-18:00', now() + interval '12 days', '18:00', now()
  );

update content.exhibitions
set published_version_id = case id
  when 'launch-published' then 'b2000000-0000-0000-0000-000000000001'::uuid
  when 'launch-other' then 'b2000000-0000-0000-0000-000000000003'::uuid
  when 'launch-pending-clean' then 'b2000000-0000-0000-0000-000000000004'::uuid
  when 'launch-pending-payment' then 'b2000000-0000-0000-0000-000000000005'::uuid
  when 'launch-cancelled' then 'b2000000-0000-0000-0000-000000000006'::uuid
  when 'launch-refunded' then 'b2000000-0000-0000-0000-000000000007'::uuid
  when 'launch-paid-existing' then 'b2000000-0000-0000-0000-000000000008'::uuid
end
where id in (
  'launch-published', 'launch-other', 'launch-pending-clean',
  'launch-pending-payment', 'launch-cancelled', 'launch-refunded',
  'launch-paid-existing'
);

create temp table launch_test_state (key text primary key, value text not null);
grant select, insert, update on launch_test_state to authenticated;
grant select on launch_test_state to service_role;
insert into launch_test_state (key, value)
select 'stripe_webhook_events_before', count(*)::text
from content.stripe_webhook_events;

update content.exhibition_versions
set description_ko = '작은 방에서 시작된 기록입니다.',
    description_en = 'A record that began in a small room.',
    address_en = '12 Samcheong-ro, Jongno-gu, Seoul',
    contact = 'hello@launch-gallery.example'
where id = 'b2000000-0000-0000-0000-000000000001'::uuid;

insert into content.media_assets (
  id, status, bucket_id, object_path,
  delivery_bucket_id, delivery_object_path, public_url,
  mime_type, byte_size, width, height, checksum_sha256, published_at
) values (
  'b5000000-0000-0000-0000-000000000001'::uuid,
  'published'::content.media_asset_status,
  'exhibition-media',
  'tests/launch-rsvp-cover.jpg',
  'exhibition-images',
  'cms/b5000000-0000-0000-0000-000000000001/original.jpg',
  'https://cdn.example.invalid/launch-rsvp-cover.jpg',
  'image/jpeg', 1024, 1200, 800, repeat('c', 64), now()
);
insert into content.exhibition_version_media (
  version_id, media_id, role, sort_order
) values (
  'b2000000-0000-0000-0000-000000000001'::uuid,
  'b5000000-0000-0000-0000-000000000001'::uuid,
  'cover'::content.media_role,
  0
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001101","role":"authenticated"}',
  true
);
with activated as (
  select public.owner_activate_launch_kit(
    'launch-published', 'b3000000-0000-0000-0000-000000000001'
  ) as payload
)
insert into launch_test_state (key, value)
select 'kit_id', payload ->> 'id' from activated;
select is(
  (
    select payload ->> 'status'
    from public.owner_activate_launch_kit(
      'launch-published', 'b3000000-0000-0000-0000-000000000001'
    ) as payload
  ),
  'active',
  'free activation is replay-safe'
);
select is(
  (
    select payload ->> 'entitlement_source'
    from public.owner_activate_launch_kit(
      'launch-published', 'b3000000-0000-0000-0000-000000000001'
    ) as payload
  ),
  'free_beta',
  'owner activation returns the explicit free-beta entitlement source'
);
select ok(
  (
    select length(payload ->> 'public_token') > 0
    from public.owner_activate_launch_kit(
      'launch-published', 'b3000000-0000-0000-0000-000000000001'
    ) as payload
  ),
  'free activation returns the public RSVP token'
);
select is(
  (
    select payload ->> 'id'
    from public.owner_activate_launch_kit(
      'launch-published', 'b3000000-0000-0000-0000-000000000007'
    ) as payload
  ),
  (select value from launch_test_state where key = 'kit_id'),
  'a distinct activation request still returns the one Kit for the exhibition'
);
select throws_ok(
  $$select public.owner_activate_launch_kit(
    'launch-draft', 'b3000000-0000-0000-0000-000000000002'
  )$$,
  '42501',
  'published_owner_exhibition_required',
  'publication is required before free activation'
);
select throws_ok(
  $$select public.owner_activate_launch_kit(
    'launch-other', 'b3000000-0000-0000-0000-000000000008'
  )$$,
  '42501',
  'published_owner_exhibition_required',
  'an owner cannot activate another gallery''s exhibition'
);
reset role;

select is(
  (
    select count(*)::integer from content.launch_kits
    where exhibition_id = 'launch-published'
      and status = 'active'
      and entitlement_source = 'free_beta'
  ),
  1,
  'one active free-beta entitlement is created per exhibition'
);
select ok(
  (
    select activated_at is not null
      and stripe_price_id is null
      and stripe_checkout_session_id is null
      and stripe_payment_intent_id is null
      and stripe_event_id is null
      and amount_total is null
      and currency is null
      and checkout_attempt = 0
    from content.launch_kits
    where exhibition_id = 'launch-published'
  ),
  'free activation records time without manufacturing payment evidence'
);
select is(
  (
    select count(*)::integer
    from content.stripe_webhook_events
  ),
  (
    select value::integer
    from launch_test_state
    where key = 'stripe_webhook_events_before'
  ),
  'free activation preserves the historical Stripe webhook event count'
);
insert into launch_test_state (key, value)
select 'public_token', public_token::text
from content.launch_kits where exhibition_id = 'launch-published';

set local role service_role;
select is(
  (
    select public.service_public_launch_kit(
      (select value::uuid from launch_test_state where key = 'public_token')
    ) ->> 'name_en'
  ),
  'Notes from a Small Room',
  'public RSVP lookup exposes published presentation data'
);
select is(
  (
    select jsonb_build_object(
      'cover_image_url', payload ->> 'cover_image_url',
      'description_ko', payload ->> 'description_ko',
      'description_en', payload ->> 'description_en',
      'opening_date', payload ->> 'opening_date',
      'closing_date', payload ->> 'closing_date',
      'hours', payload ->> 'hours',
      'contact', payload ->> 'contact'
    )
    from (
      select public.service_public_launch_kit(
        (select value::uuid from launch_test_state where key = 'public_token')
      ) as payload
    ) as public_kit
  ),
  jsonb_build_object(
    'cover_image_url', 'https://cdn.example.invalid/launch-rsvp-cover.jpg',
    'description_ko', '작은 방에서 시작된 기록입니다.',
    'description_en', 'A record that began in a small room.',
    'opening_date', to_char(current_date, 'YYYY-MM-DD'),
    'closing_date', to_char(current_date + 30, 'YYYY-MM-DD'),
    'hours', '11:00-18:00',
    'contact', 'hello@launch-gallery.example'
  ),
  'public RSVP exposes only the essential published exhibition presentation'
);
select ok(
  (
    select public.service_submit_launch_rsvp(
      (select value::uuid from launch_test_state where key = 'public_token'),
      'Maya Chen', 'MAYA@EXAMPLE.COM', 2, true,
      repeat('a', 64)
    )
  ),
  'public RSVP is accepted through the private service command'
);
select ok(
  (
    select public.service_submit_launch_rsvp(
      (select value::uuid from launch_test_state where key = 'public_token'),
      'Maya Chen', 'maya@example.com', 3, true,
      repeat('a', 64)
    )
  ),
  'duplicate normalized email updates without disclosing prior registration'
);
select throws_ok(
  format(
    'select public.service_submit_launch_rsvp(%L::uuid, %L, %L, 1, false, %L)',
    (select value from launch_test_state where key = 'public_token'),
    'No Consent', 'no-consent@example.com', repeat('b', 64)
  ),
  '22023', 'launch_rsvp_invalid',
  'public RSVP requires privacy acknowledgement'
);
reset role;

select is(
  (
    select count(*)::integer from content.launch_guests
    where email_normalized = 'maya@example.com'
  ),
  1,
  'duplicate public RSVP remains one guest record'
);
select is(
  (
    select party_size::integer from content.launch_guests
    where email_normalized = 'maya@example.com'
  ),
  3,
  'duplicate public RSVP refreshes party size'
);
select ok(
  (
    select privacy_acknowledged_at is not null from content.launch_guests
    where email_normalized = 'maya@example.com'
  ),
  'public guest retains privacy evidence'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001101","role":"authenticated"}',
  true
);
select is(
  (select count(*)::integer from public.owner_list_launch_kits()),
  1,
  'owner sees only their Launch Kit'
);
select is(
  (
    select (payload ->> 'guest_count')::integer
    from public.owner_list_launch_kits() as payload
  ),
  3,
  'owner summary uses real party size'
);
select is(
  format(
    '%s:%s',
    (select payload ->> 'name' from public.owner_list_launch_guests(
      (select value::uuid from launch_test_state where key = 'kit_id')
    ) as payload),
    (select payload ->> 'status' from public.owner_list_launch_guests(
      (select value::uuid from launch_test_state where key = 'kit_id')
    ) as payload)
  ),
  'Maya Chen:going',
  'owner can list the active kit guest'
);
with added as (
  select public.owner_add_launch_guest(
    (select value::uuid from launch_test_state where key = 'kit_id'),
    'Jordan Lee', 'jordan@example.com', 1,
    'b3000000-0000-0000-0000-000000000003'
  ) as payload
)
insert into launch_test_state (key, value)
select 'guest_id', payload ->> 'id' from added;
with checked_in as (
  select public.owner_check_in_launch_guest(
    (select value::uuid from launch_test_state where key = 'kit_id'),
    (select value::uuid from launch_test_state where key = 'guest_id'),
    'b3000000-0000-0000-0000-000000000004'
  ) as payload
)
insert into launch_test_state (key, value)
select 'checked_status', payload ->> 'status' from checked_in
union all
select 'checked_at', payload ->> 'checked_in_at' from checked_in;
select is(
  (select value from launch_test_state where key = 'checked_status'),
  'checked_in',
  'owner checks a guest in'
);
select is(
  (
    select public.owner_check_in_launch_guest(
      (select value::uuid from launch_test_state where key = 'kit_id'),
      (select value::uuid from launch_test_state where key = 'guest_id'),
      'b3000000-0000-0000-0000-000000000004'
    ) ->> 'checked_in_at'
  ),
  (select value from launch_test_state where key = 'checked_at'),
  'check-in replay preserves first arrival time'
);
select is(
  (
    select count(*)::integer from public.owner_list_launch_guests(
      (select value::uuid from launch_test_state where key = 'kit_id'),
      '', 'checked_in', null, null, 50
    )
  ),
  1,
  'owner status filter returns checked-in guests'
);
with rotated as (
  select public.owner_rotate_launch_rsvp_token(
    (select value::uuid from launch_test_state where key = 'kit_id'),
    'b3000000-0000-0000-0000-000000000006'
  ) as payload
)
insert into launch_test_state (key, value)
select 'rotated_token', payload ->> 'public_token' from rotated;
select isnt(
  (select value from launch_test_state where key = 'rotated_token'),
  (select value from launch_test_state where key = 'public_token'),
  'owner can revoke the old RSVP URL by rotating its token'
);
reset role;
set local role service_role;
select is(
  public.service_public_launch_kit(
    (select value::uuid from launch_test_state where key = 'public_token')
  )::text,
  null::text,
  'the rotated RSVP token no longer resolves'
);
reset role;
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001101","role":"authenticated"}',
  true
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001102","role":"authenticated"}',
  true
);
select throws_ok(
  format(
    'select * from public.owner_list_launch_guests(%L::uuid)',
    (select value from launch_test_state where key = 'kit_id')
  ),
  '42501', 'active_launch_kit_required',
  'another gallery cannot read the guest list'
);
select throws_ok(
  format(
    'select public.owner_check_in_launch_guest(%L::uuid, %L::uuid, %L::uuid)',
    (select value from launch_test_state where key = 'kit_id'),
    (select value from launch_test_state where key = 'guest_id'),
    'b3000000-0000-0000-0000-000000000005'
  ),
  '42501', 'active_launch_kit_required',
  'another gallery cannot check in a guest'
);
reset role;

insert into content.launch_kits (
  id, exhibition_id, gallery_id, status, entitlement_source,
  stripe_price_id, stripe_checkout_session_id, stripe_payment_intent_id,
  stripe_event_id, amount_total, currency, checkout_attempt, activated_at
)
values
  (
    'b4000000-0000-0000-0000-000000000001', 'launch-pending-clean',
    'b1000000-0000-0000-0000-000000000001', 'pending', null,
    null, null, null, null, null, null, 0, null
  ),
  (
    'b4000000-0000-0000-0000-000000000002', 'launch-pending-payment',
    'b1000000-0000-0000-0000-000000000001', 'pending', null,
    'price_pending_test', 'cs_pending_test', null, null, null, null, 1, null
  ),
  (
    'b4000000-0000-0000-0000-000000000003', 'launch-cancelled',
    'b1000000-0000-0000-0000-000000000001', 'cancelled', null,
    null, null, null, null, null, null, 0, null
  ),
  (
    'b4000000-0000-0000-0000-000000000004', 'launch-refunded',
    'b1000000-0000-0000-0000-000000000001', 'refunded', null,
    null, null, null, null, null, null, 0, null
  ),
  (
    'b4000000-0000-0000-0000-000000000005', 'launch-paid-existing',
    'b1000000-0000-0000-0000-000000000001', 'active', 'paid',
    'price_existing_paid', 'cs_existing_paid', 'pi_existing_paid',
    'evt_existing_paid', 9900, 'krw', 1, now()
  );

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001101","role":"authenticated"}',
  true
);
select is(
  public.owner_activate_launch_kit(
    'launch-pending-clean', 'b3000000-0000-0000-0000-000000000010'
  ) ->> 'entitlement_source',
  'free_beta',
  'a payment-free pending Kit converts to a free-beta entitlement'
);
select throws_ok(
  $$select public.owner_activate_launch_kit(
    'launch-pending-payment', 'b3000000-0000-0000-0000-000000000011'
  )$$,
  '55000', 'launch_kit_payment_state_present',
  'a pending Kit with payment state fails closed instead of becoming free'
);
select throws_ok(
  $$select public.owner_activate_launch_kit(
    'launch-cancelled', 'b3000000-0000-0000-0000-000000000012'
  )$$,
  '55000', 'launch_kit_not_activatable',
  'a cancelled Kit does not silently reactivate'
);
select throws_ok(
  $$select public.owner_activate_launch_kit(
    'launch-refunded', 'b3000000-0000-0000-0000-000000000013'
  )$$,
  '55000', 'launch_kit_not_activatable',
  'a refunded Kit does not silently reactivate'
);
select is(
  public.owner_activate_launch_kit(
    'launch-paid-existing', 'b3000000-0000-0000-0000-000000000014'
  ) ->> 'entitlement_source',
  'paid',
  'free-beta activation never downgrades an existing paid entitlement'
);
select is(
  (
    select payload ->> 'entitlement_source'
    from public.owner_list_launch_kits() as payload
    where payload ->> 'exhibition_id' = 'launch-paid-existing'
  ),
  'paid',
  'owner serialization preserves the paid source assigned to existing active Kits'
);
reset role;

select ok(
  (
    select status = 'active'::content.launch_kit_status
      and entitlement_source = 'free_beta'::content.launch_kit_entitlement_source
      and activated_at is not null
      and stripe_price_id is null
      and stripe_checkout_session_id is null
      and stripe_payment_intent_id is null
      and stripe_event_id is null
      and amount_total is null
      and currency is null
      and checkout_attempt = 0
    from content.launch_kits
    where exhibition_id = 'launch-pending-clean'
  ),
  'pending conversion records a clean free-beta entitlement'
);
select ok(
  (
    select status = 'pending'::content.launch_kit_status
      and entitlement_source is null
      and stripe_price_id = 'price_pending_test'
      and stripe_checkout_session_id = 'cs_pending_test'
      and checkout_attempt = 1
    from content.launch_kits
    where exhibition_id = 'launch-pending-payment'
  ),
  'failed beta activation preserves ambiguous payment state for operator review'
);
select is(
  (
    select count(*)::integer
    from content.launch_kits
    where status = 'active'::content.launch_kit_status
      and entitlement_source is null
  ),
  0,
  'every active Kit has an entitlement source after paid backfill and beta activation'
);
select throws_ok(
  $$update content.launch_kits
    set activated_at = null
    where exhibition_id = 'launch-published'$$,
  '23514', null,
  'an active Kit cannot lose its activation timestamp'
);
select throws_ok(
  $$update content.launch_kits
    set entitlement_source = null
    where exhibition_id = 'launch-published'$$,
  '23514', null,
  'an active Kit cannot lose its entitlement source'
);
select throws_ok(
  $$update content.launch_kits
    set stripe_price_id = 'price_free_beta_conflict'
    where exhibition_id = 'launch-published'$$,
  '23514', null,
  'a free-beta Kit cannot acquire partial payment evidence'
);
select throws_ok(
  $$update content.launch_kits
    set checkout_attempt = 1
    where exhibition_id = 'launch-published'$$,
  '23514', null,
  'a free-beta Kit cannot retain a checkout attempt'
);
select throws_ok(
  $$update content.launch_kits
    set stripe_payment_intent_id = null
    where exhibition_id = 'launch-paid-existing'$$,
  '23514', null,
  'a paid entitlement cannot lose required payment evidence'
);

select is(
  (
    select count(*)::integer from content.audit_log
    where action = 'launch_kit.activated'
      and entity_id = (select value from launch_test_state where key = 'kit_id')
  ),
  1,
  'free activation is audited once without duplication across request IDs'
);

select * from finish();
rollback;
