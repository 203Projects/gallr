begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(36);

select has_table('content', 'launch_kits', 'launch kit entitlements exist');
select has_table('content', 'launch_guests', 'private launch guest list exists');
select has_table('content', 'launch_rsvp_rate_limits', 'public RSVP rate limits exist');
select has_table('content', 'stripe_webhook_events', 'Stripe webhook idempotency exists');
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
      ('public.owner_prepare_launch_kit_checkout(text,uuid)'),
      ('public.owner_list_launch_kits()'),
      ('public.owner_list_launch_guests(uuid,text,text,timestamp with time zone,uuid,integer)'),
      ('public.owner_add_launch_guest(uuid,text,text,integer,uuid)'),
      ('public.owner_check_in_launch_guest(uuid,uuid,uuid)'),
      ('public.owner_rotate_launch_rsvp_token(uuid,uuid)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  6,
  'owners receive only the explicit Launch Kit command surface'
);
select is(
  (
    select count(*)::integer
    from (values
      ('public.service_attach_launch_kit_checkout(uuid,text,text,integer)'),
      ('public.service_activate_launch_kit(text,text,text,bigint,text)'),
      ('public.service_public_launch_kit(uuid)'),
      ('public.service_submit_launch_rsvp(uuid,text,text,integer,boolean,text)')
    ) as signature(value)
    where has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  4,
  'service role receives the narrow payment and RSVP surface'
);
select is(
  (
    select count(*)::integer
    from (values
      ('public.owner_prepare_launch_kit_checkout(text,uuid)'),
      ('public.owner_list_launch_kits()'),
      ('public.service_activate_launch_kit(text,text,text,bigint,text)'),
      ('public.service_submit_launch_rsvp(uuid,text,text,integer,boolean,text)')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
  ),
  0,
  'anonymous callers cannot bypass Edge authorization'
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
  ('launch-other', 'b1000000-0000-0000-0000-000000000002', 'published');

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
  );

update content.exhibitions
set published_version_id = case id
  when 'launch-published' then 'b2000000-0000-0000-0000-000000000001'::uuid
  when 'launch-other' then 'b2000000-0000-0000-0000-000000000003'::uuid
end
where id in ('launch-published', 'launch-other');

create temp table launch_test_state (key text primary key, value text not null);
grant select, insert, update on launch_test_state to authenticated;
grant select on launch_test_state to service_role;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001101","role":"authenticated"}',
  true
);
with prepared as (
  select public.owner_prepare_launch_kit_checkout(
    'launch-published', 'b3000000-0000-0000-0000-000000000001'
  ) as payload
)
insert into launch_test_state (key, value)
select 'kit_id', payload ->> 'launch_kit_id' from prepared;
select is(
  (
    select payload ->> 'status'
    from public.owner_prepare_launch_kit_checkout(
      'launch-published', 'b3000000-0000-0000-0000-000000000001'
    ) as payload
  ),
  'pending',
  'checkout preparation is replay-safe'
);
select is(
  (
    select (payload ->> 'checkout_attempt')::integer
    from public.owner_prepare_launch_kit_checkout(
      'launch-published', 'b3000000-0000-0000-0000-000000000001'
    ) as payload
  ),
  0,
  'checkout context starts at attempt zero for safe session restart'
);
select throws_ok(
  $$select public.owner_prepare_launch_kit_checkout(
    'launch-draft', 'b3000000-0000-0000-0000-000000000002'
  )$$,
  '42501',
  'published_owner_exhibition_required',
  'free publication is required before purchase'
);
reset role;

select is(
  (
    select count(*)::integer from content.launch_kits
    where exhibition_id = 'launch-published' and status = 'pending'
  ),
  1,
  'one pending entitlement is created per exhibition'
);

set local role service_role;
select lives_ok(
  format(
    'select public.service_attach_launch_kit_checkout(%L::uuid, %L, %L, 1)',
    (select value from launch_test_state where key = 'kit_id'),
    'price_launch_test', 'cs_test_launch'
  ),
  'server attaches the configured price and Checkout Session'
);
select is(
  (
    select public.service_activate_launch_kit(
      'cs_test_launch', 'evt_launch_paid', 'pi_launch_paid', 9900, 'krw'
    ) ->> 'status'
  ),
  'active',
  'signed paid webhook activation returns active entitlement'
);
select is(
  (
    select public.service_activate_launch_kit(
      'cs_test_launch', 'evt_launch_paid', 'pi_launch_paid', 9900, 'krw'
    ) ->> 'status'
  ),
  'active',
  'webhook replay is idempotent'
);
reset role;
select is(
  (
    select count(*)::integer from content.stripe_webhook_events
    where event_id = 'evt_launch_paid'
  ),
  1,
  'Stripe event is recorded once'
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

select is(
  (
    select count(*)::integer from content.audit_log
    where action = 'launch_kit.activated'
      and entity_id = (select value from launch_test_state where key = 'kit_id')
  ),
  1,
  'paid activation is audited once'
);

select * from finish();
rollback;
