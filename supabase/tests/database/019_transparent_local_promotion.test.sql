begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(29);

select has_table('content', 'local_promotions', 'promotion requests are isolated from curation');
select has_table('content', 'local_promotion_impressions', 'daily delivery cap is durable');
select is(
  (
    select count(*)::integer
    from pg_class relation
    join pg_namespace namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'content'
      and relation.relname in ('local_promotions', 'local_promotion_impressions')
      and relation.relrowsecurity
  ),
  2,
  'RLS is enabled on promotion tables'
);
select is(
  (
    select count(*)::integer
    from (values ('content.local_promotions'), ('content.local_promotion_impressions')) relation(name)
    where has_table_privilege('anon', relation.name, 'SELECT')
       or has_table_privilege('authenticated', relation.name, 'SELECT')
       or has_table_privilege('authenticated', relation.name, 'INSERT')
       or has_table_privilege('authenticated', relation.name, 'UPDATE')
  ),
  0,
  'browser roles have no generic promotion table privileges'
);
select is(
  (
    select count(*)::integer
    from (values
      ('public.owner_request_local_promotion(uuid,uuid)'),
      ('public.owner_list_local_promotions()'),
      ('public.admin_list_local_promotions(text,text)'),
      ('public.admin_approve_local_promotion(uuid,timestamp with time zone,timestamp with time zone,uuid)'),
      ('public.admin_reject_local_promotion(uuid,text,uuid)')
    ) signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  5,
  'owners and staff receive only explicit promotion RPCs'
);
select ok(
  has_function_privilege(
    'service_role',
    'public.service_select_local_promotion(text,text,text)',
    'EXECUTE'
  ),
  'service role alone can select a visitor placement'
);
select ok(
  not has_function_privilege(
    'anon',
    'public.service_select_local_promotion(text,text,text)',
    'EXECUTE'
  ),
  'anonymous callers cannot bypass the Edge boundary'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'content_private.local_promotion_json(uuid)',
    'EXECUTE'
  ),
  'authenticated users cannot bypass tenant-scoped list RPCs through the serializer'
);
select is(
  (
    select count(*)::integer from pg_indexes
    where schemaname = 'content'
      and indexname in (
        'local_promotions_gallery_status_idx',
        'local_promotions_eligible_schedule_idx',
        'local_promotion_impressions_promotion_idx'
      )
  ),
  3,
  'tenant, eligibility, and impression foreign-key reads are indexed'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000001201', 'promotion-owner@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000001202', 'promotion-other@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000001203', 'promotion-publisher@example.invalid', now(), '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values ('00000000-0000-0000-0000-000000001203', 'publisher', true);

insert into content.galleries (id, name_ko, name_en, status)
values
  ('c1000000-0000-0000-0000-000000000001', '프로모션 갤러리', 'Promotion Gallery', 'active'),
  ('c1000000-0000-0000-0000-000000000002', '다른 갤러리', 'Other Gallery', 'active');

insert into content.gallery_memberships (gallery_id, user_id, status, claim_note)
values
  ('c1000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000001201', 'active', 'test'),
  ('c1000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000001202', 'active', 'test');

insert into content.exhibitions (id, gallery_id, owner_status)
values
  ('promotion-published', 'c1000000-0000-0000-0000-000000000001', 'published'),
  ('promotion-other', 'c1000000-0000-0000-0000-000000000002', 'published'),
  ('promotion-free-beta', 'c1000000-0000-0000-0000-000000000001', 'published');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, status, name_ko, name_en,
  venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  address_ko, opening_date, closing_date, hours, published_at
)
values
  (
    'c2000000-0000-0000-0000-000000000001', 'promotion-published', 1,
    'published', '계절 사이', 'Between Seasons', '아틀리에 한남', 'Atelier Hannam',
    '서울', 'Seoul', '용산구', 'Yongsan-gu', '서울 용산구',
    current_date - 1, current_date + 30, '11:00-18:00', now()
  ),
  (
    'c2000000-0000-0000-0000-000000000002', 'promotion-other', 1,
    'published', '다른 전시', 'Other Exhibition', '다른 갤러리', 'Other Gallery',
    '서울', 'Seoul', '종로구', 'Jongno-gu', '서울 종로구',
    current_date - 1, current_date + 30, '11:00-18:00', now()
  ),
  (
    'c2000000-0000-0000-0000-000000000003', 'promotion-free-beta', 1,
    'published', '무료 베타 전시', 'Free Beta Exhibition', '프로모션 갤러리', 'Promotion Gallery',
    '부산', 'Busan', '해운대구', 'Haeundae-gu', '부산 해운대구',
    current_date - 1, current_date + 30, '11:00-18:00', now()
  );

update content.exhibitions set published_version_id = case id
  when 'promotion-published' then 'c2000000-0000-0000-0000-000000000001'::uuid
  when 'promotion-other' then 'c2000000-0000-0000-0000-000000000002'::uuid
  when 'promotion-free-beta' then 'c2000000-0000-0000-0000-000000000003'::uuid
end where id in ('promotion-published', 'promotion-other', 'promotion-free-beta');

insert into content.launch_kits (
  id, exhibition_id, gallery_id, status, entitlement_source, stripe_price_id,
  stripe_checkout_session_id, stripe_payment_intent_id, stripe_event_id,
  amount_total, currency, activated_at
)
values
  (
    'c3000000-0000-0000-0000-000000000001', 'promotion-published',
    'c1000000-0000-0000-0000-000000000001', 'active', 'paid', 'price_test',
    'cs_promotion', 'pi_promotion', 'evt_promotion', 9900, 'krw', now()
  ),
  (
    'c3000000-0000-0000-0000-000000000002', 'promotion-other',
    'c1000000-0000-0000-0000-000000000002', 'active', 'paid', 'price_test',
    'cs_promotion_other', 'pi_promotion_other', 'evt_promotion_other', 9900, 'krw', now()
  ),
  (
    'c3000000-0000-0000-0000-000000000003', 'promotion-free-beta',
    'c1000000-0000-0000-0000-000000000001', 'active', 'free_beta', null,
    null, null, null, null, null, now()
  );

create temp table promotion_test_state (key text primary key, value text not null);
grant select, insert, update on promotion_test_state to authenticated;
grant select on promotion_test_state to service_role;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001201","role":"authenticated"}',
  true
);
with requested as (
  select public.owner_request_local_promotion(
    'c3000000-0000-0000-0000-000000000001',
    'c4000000-0000-0000-0000-000000000001'
  ) payload
)
insert into promotion_test_state (key, value)
select 'promotion_id', payload ->> 'id' from requested;
select is(
  public.owner_request_local_promotion(
    'c3000000-0000-0000-0000-000000000001',
    'c4000000-0000-0000-0000-000000000001'
  ) ->> 'status',
  'submitted',
  'owner request replay is idempotent'
);
select is((select count(*)::integer from public.owner_list_local_promotions()), 1,
  'owner sees only their promotion request');
select throws_ok(
  $$select public.owner_request_local_promotion(
    'c3000000-0000-0000-0000-000000000003',
    'c4000000-0000-0000-0000-000000000005'
  )$$,
  '42501', 'paid_launch_kit_required',
  'a free-beta Kit cannot request paid local promotion'
);
reset role;

select is(
  (
    select count(*)::integer
    from content.local_promotions
    where launch_kit_id = 'c3000000-0000-0000-0000-000000000003'
  ),
  0,
  'paid-entitlement denial creates no promotion request'
);
delete from content.local_promotions
where launch_kit_id = 'c3000000-0000-0000-0000-000000000003';

insert into content.local_promotions (
  id, launch_kit_id, exhibition_id, gallery_id, status,
  city_ko, city_en, region_ko, region_en,
  starts_at, ends_at, reviewed_at, reviewed_by
)
values (
  'c5000000-0000-0000-0000-000000000003',
  'c3000000-0000-0000-0000-000000000003',
  'promotion-free-beta', 'c1000000-0000-0000-0000-000000000001',
  'active', '부산', 'Busan', '해운대구', 'Haeundae-gu',
  now() - interval '1 minute', now() + interval '7 days', now(),
  '00000000-0000-0000-0000-000000001203'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001202","role":"authenticated"}',
  true
);
select throws_ok(
  $$select public.owner_request_local_promotion(
    'c3000000-0000-0000-0000-000000000001',
    'c4000000-0000-0000-0000-000000000002'
  )$$,
  '42501', 'active_launch_kit_required',
  'another gallery cannot request promotion for the Kit'
);
select is((select count(*)::integer from public.owner_list_local_promotions()), 0,
  'another gallery cannot list the request');
reset role;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000001203","role":"authenticated"}',
  true
);
select is((select count(*)::integer from public.admin_list_local_promotions('', 'submitted')), 1,
  'staff sees submitted promotion independently from curation');
select throws_ok(
  format(
    'select public.admin_approve_local_promotion(%L::uuid, now(), now() + interval ''31 days'', %L::uuid)',
    (select value from promotion_test_state where key = 'promotion_id'),
    'c4000000-0000-0000-0000-000000000003'
  ),
  '22023', 'promotion_schedule_outlives_exhibition',
  'approval cannot outlive the published exhibition'
);
select is(
  public.admin_approve_local_promotion(
    (select value::uuid from promotion_test_state where key = 'promotion_id'),
    now() - interval '1 minute', now() + interval '7 days',
    'c4000000-0000-0000-0000-000000000004'
  ) ->> 'status',
  'active',
  'staff can activate a valid current schedule'
);
select is(
  public.admin_approve_local_promotion(
    (select value::uuid from promotion_test_state where key = 'promotion_id'),
    now() - interval '1 minute', now() + interval '7 days',
    'c4000000-0000-0000-0000-000000000004'
  ) ->> 'status',
  'active',
  'staff decision replay is idempotent'
);
reset role;

set local role service_role;
select is(
  public.service_select_local_promotion(repeat('c', 64), '부산', '')::text,
  null::text,
  'visitor delivery ignores a scheduled promotion backed by a free-beta Kit'
);
select is(
  public.service_select_local_promotion(repeat('a', 64), '부산', '')::text,
  null::text,
  'non-matching locality returns no placement'
);
select is(
  public.service_select_local_promotion(repeat('a', 64), '서울', '종로구')::text,
  null::text,
  'region mismatch returns no placement'
);
select is(
  public.service_select_local_promotion(repeat('a', 64), '서울', '용산구') ->> 'name_en',
  'Between Seasons',
  'matching coarse locality returns the published exhibition'
);
select is(
  public.service_select_local_promotion(repeat('a', 64), '서울', '용산구')::text,
  null::text,
  'same viewer receives at most one promotion per Seoul day'
);
select is(
  public.service_select_local_promotion(repeat('b', 64), '서울', '') ->> 'disclosure',
  'paid_placement',
  'city match can return a transparently disclosed placement'
);
reset role;

select is(
  (select count(*)::integer from content.local_promotion_impressions),
  2,
  'only successful deliveries create impressions'
);
select is(
  (select min(length(viewer_digest))::integer from content.local_promotion_impressions),
  64,
  'only fixed SHA-256 viewer digests are stored'
);
select is(
  (select count(*)::integer from content.audit_log
   where entity_id = (select value from promotion_test_state where key = 'promotion_id')
     and action in ('local_promotion.requested', 'local_promotion.approved')),
  2,
  'request and approval are each audited once'
);
select is(
  (select count(*)::integer from content.curation_placements
   where exhibition_id = 'promotion-published'),
  0,
  'paid promotion never creates editorial curation placement'
);

select * from finish();
rollback;
