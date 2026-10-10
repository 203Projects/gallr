-- Public routes staff review (spec 089 US8; contracts/public-routes-functions.md): the queue, revision-checked
-- decisions, reported routes, report resolution and the extended moderation read.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(38);

select ok(not has_function_privilege('anon', 'public.list_route_listing_queue()', 'EXECUTE'), 'anon cannot read the queue');
select ok(
  not has_function_privilege('anon', 'public.decide_route_listing(uuid,text,text,text,timestamptz)', 'EXECUTE'),
  'anon cannot decide'
);
select ok(not has_function_privilege('anon', 'public.list_reported_routes()', 'EXECUTE'), 'anon cannot read reports');
select ok(not has_function_privilege('anon', 'public.resolve_route_reports(uuid,text)', 'EXECUTE'), 'anon cannot resolve reports');

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000c901', 'review-author@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000c902', 'review-staff@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000c903', 'review-reader-1@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000c904', 'review-reader-2@example.invalid', now(), false, '{}');
update public.profiles set display_name = '검토 작가' where id = '00000000-0000-4000-8000-00000000c901';
insert into content.staff_members (user_id, role, active) values ('00000000-0000-4000-8000-00000000c902', 'admin', true);

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  stop.id, stop.id, stop.id, '갤러리', 'Gallery', '서울', 'Seoul', '종로구', 'Jongno-gu',
  seoul.today - 5, seoul.today + 30, false, 37.57, 126.98, '', '', '', '', false, now(), false, repeat('0', 64)
from (select (now() at time zone 'Asia/Seoul')::date as today) as seoul,
  (values ('rv-1'), ('rv-2'), ('rv-3')) as stop(id);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000c901', true);
select public.save_personal_route(route.id, route.name, array['rv-1', 'rv-2'])
from (values
  ('50000000-0000-4000-8000-000000000001'::uuid, '먼저 요청'),
  ('50000000-0000-4000-8000-000000000002'::uuid, '다시 요청'),
  ('50000000-0000-4000-8000-000000000003'::uuid, '신고 많은 동선'),
  ('50000000-0000-4000-8000-000000000004'::uuid, '신고 하나'),
  ('50000000-0000-4000-8000-000000000005'::uuid, '오류 확인')
) as route(id, name);
select public.publish_personal_route(id) from unnest(array[
  '50000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000002',
  '50000000-0000-4000-8000-000000000003', '50000000-0000-4000-8000-000000000004',
  '50000000-0000-4000-8000-000000000005'
]::uuid[]) as id;
reset role;
update public.personal_routes as route
set listing_state = 'requested', listing_requested_at = now() - waited.age,
    listing_last_approved_at = waited.last_approved
from (values
  ('50000000-0000-4000-8000-000000000001'::uuid, interval '3 hours', null::timestamptz),
  ('50000000-0000-4000-8000-000000000002'::uuid, interval '1 hour', now() - interval '2 days'),
  ('50000000-0000-4000-8000-000000000005'::uuid, interval '30 minutes', null::timestamptz)
) as waited(id, age, last_approved)
where route.id = waited.id;
update public.personal_routes
set listing_state = 'approved', listing_decided_at = now() - interval '1 day', listing_last_approved_at = now() - interval '1 day'
where id in ('50000000-0000-4000-8000-000000000003', '50000000-0000-4000-8000-000000000004');

-- Non-staff are refused
set local role authenticated;
select throws_ok($$select public.list_route_listing_queue()$$, '42501', 'personal_route_not_staff', 'an author cannot read the queue');
select throws_ok(
  $$select public.decide_route_listing('50000000-0000-4000-8000-000000000001', 'approve', null, null, now())$$,
  '42501', 'personal_route_not_staff', 'an author cannot decide'
);
select throws_ok($$select public.list_reported_routes()$$, '42501', 'personal_route_not_staff', 'an author cannot read reports');
select throws_ok(
  $$select public.resolve_route_reports('50000000-0000-4000-8000-000000000003', 'dismissed')$$,
  '42501', 'personal_route_not_staff', 'an author cannot resolve reports'
);

-- Readers report and copy before staff look
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000c903', true);
select public.report_route('50000000-0000-4000-8000-000000000003', 'promotional');
select public.report_route('50000000-0000-4000-8000-000000000004', 'other');
select public.save_public_route('50000000-0000-4000-8000-000000000004');
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000c904', true);
select public.report_route('50000000-0000-4000-8000-000000000003', 'other');

-- The queue
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000c902', true);
select is(
  (select array_agg(q->>'id' order by ordinality) from jsonb_array_elements(public.list_route_listing_queue()) with ordinality as queue(q, ordinality)),
  array['50000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000002', '50000000-0000-4000-8000-000000000005'],
  'the queue lists waiting routes, oldest first'
);
select is(
  (select q->>'was_approved_before' from jsonb_array_elements(public.list_route_listing_queue()) q
   where q->>'id' = '50000000-0000-4000-8000-000000000002'),
  'true', 'a re-requested route is marked as edited'
);
select is(
  (select q->>'was_approved_before' from jsonb_array_elements(public.list_route_listing_queue()) q
   where q->>'id' = '50000000-0000-4000-8000-000000000001'),
  'false', 'a first request is not marked as edited'
);
select is(
  (select q->>'author_display_name' || ' ' || (q->>'stop_count') from jsonb_array_elements(public.list_route_listing_queue()) q
   where q->>'id' = '50000000-0000-4000-8000-000000000001'),
  '검토 작가 2', 'a queue row carries the author and stop count'
);
select ok(
  (select q->>'revision' is not null and q->>'requested_at' is not null
   from jsonb_array_elements(public.list_route_listing_queue()) q
   where q->>'id' = '50000000-0000-4000-8000-000000000001'),
  'a queue row carries the revision and request time'
);

-- Decisions bound to the reviewed revision
reset role;
create temporary table reviewed as
  select id, updated_at from public.personal_routes
  where id in ('50000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000002',
               '50000000-0000-4000-8000-000000000005');
grant select on reviewed to authenticated;
set local role authenticated;
select is(
  public.decide_route_listing(
    '50000000-0000-4000-8000-000000000001', 'approve', null, null,
    (select updated_at from reviewed where id = '50000000-0000-4000-8000-000000000001')
  )->>'listing_state',
  'approved', 'staff approve the version they reviewed'
);
reset role;
select ok(
  (select listing_decided_by = '00000000-0000-4000-8000-00000000c902' and listing_last_approved_at is not null
   from public.personal_routes where id = '50000000-0000-4000-8000-000000000001'),
  'the approval records the staff member and time'
);
set local role authenticated;
select throws_ok(
  $$select public.decide_route_listing('50000000-0000-4000-8000-000000000001', 'approve', null, null,
      (select updated_at from reviewed where id = '50000000-0000-4000-8000-000000000001'))$$,
  '55000', 'route_listing_invalid_transition', 'an approved route cannot be decided again'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000c901', true);
select public.save_personal_route('50000000-0000-4000-8000-000000000002', '다시 요청 (수정)', array['rv-1', 'rv-2']);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000c902', true);
select throws_ok(
  $$select public.decide_route_listing('50000000-0000-4000-8000-000000000002', 'approve', null, null,
      (select updated_at from reviewed where id = '50000000-0000-4000-8000-000000000002'))$$,
  '55000', 'route_listing_stale', 'a decision on a version the author changed is refused'
);
reset role;
select is(
  (select listing_state from public.personal_routes where id = '50000000-0000-4000-8000-000000000002'),
  'requested', 'a refused decision changes nothing'
);
set local role authenticated;
select is(
  public.decide_route_listing(
    '50000000-0000-4000-8000-000000000002', 'decline', 'composition', '전시를 줄여 주세요',
    (select (q->>'revision')::timestamptz from jsonb_array_elements(public.list_route_listing_queue()) q
     where q->>'id' = '50000000-0000-4000-8000-000000000002')
  )->>'listing_state',
  'declined', 'staff decline the current version with a reason'
);
reset role;
select is(
  (select listing_decline_reason || ' | ' || listing_decline_note from public.personal_routes
   where id = '50000000-0000-4000-8000-000000000002'),
  'composition | 전시를 줄여 주세요', 'the decline keeps the reason and note'
);
set local role authenticated;
select throws_ok(
  $$select public.decide_route_listing('50000000-0000-4000-8000-000000000005', 'decline', null, null,
      (select updated_at from reviewed where id = '50000000-0000-4000-8000-000000000005'))$$,
  '22023', 'route_listing_invalid_reason', 'a decline needs a reason'
);
select throws_ok(
  $$select public.decide_route_listing('50000000-0000-4000-8000-000000000005', 'decline', 'spam', null,
      (select updated_at from reviewed where id = '50000000-0000-4000-8000-000000000005'))$$,
  '22023', 'route_listing_invalid_reason', 'an unknown decline reason is refused'
);
select throws_ok(
  $$select public.decide_route_listing('50000000-0000-4000-8000-000000000005', 'decline', 'other', repeat('가', 501),
      (select updated_at from reviewed where id = '50000000-0000-4000-8000-000000000005'))$$,
  '22023', 'route_listing_invalid_note', 'a note longer than 500 characters is refused'
);
select throws_ok(
  $$select public.decide_route_listing('50000000-0000-4000-8000-000000000005', 'maybe', null, null,
      (select updated_at from reviewed where id = '50000000-0000-4000-8000-000000000005'))$$,
  '22023', 'route_listing_invalid_decision', 'an unknown decision is refused'
);
select throws_ok(
  $$select public.decide_route_listing('50000000-0000-4000-8000-0000000000ff', 'approve', null, null, now())$$,
  'P0002', 'personal_route_not_found', 'a missing route cannot be decided'
);

-- Reported routes
select is(
  (select array_agg(r->>'id' order by ordinality) from jsonb_array_elements(public.list_reported_routes()) with ordinality as reported(r, ordinality)),
  array['50000000-0000-4000-8000-000000000003', '50000000-0000-4000-8000-000000000004'],
  'reported routes are listed, most reported first'
);
select is(
  (select (r->>'open_count')::integer from jsonb_array_elements(public.list_reported_routes()) r
   where r->>'id' = '50000000-0000-4000-8000-000000000003'),
  2, 'a reported route carries its open report count'
);
select is(
  (select r->'reasons' from jsonb_array_elements(public.list_reported_routes()) r
   where r->>'id' = '50000000-0000-4000-8000-000000000003'),
  '{"other": 1, "promotional": 1}'::jsonb, 'a reported route carries its reason tallies'
);
select is(
  (public.get_route_for_moderation('50000000-0000-4000-8000-000000000003')->>'open_report_count')::integer,
  2, 'the moderation read shows open reports'
);
select is(
  (public.get_route_for_moderation('50000000-0000-4000-8000-000000000004')->>'copy_count')::integer,
  1, 'the moderation read shows all-time copies'
);
select is(
  public.get_route_for_moderation('50000000-0000-4000-8000-000000000003')->>'listing_state',
  'approved', 'the moderation read shows the listing state'
);
select throws_ok(
  $$select public.resolve_route_reports('50000000-0000-4000-8000-000000000004', 'ignored')$$,
  '22023', 'route_report_invalid_resolution', 'an unknown resolution is refused'
);
select is(
  public.resolve_route_reports('50000000-0000-4000-8000-000000000004', 'dismissed')->>'listing_state',
  'approved', 'dismissing reports keeps the route listed'
);
select is(
  public.resolve_route_reports('50000000-0000-4000-8000-000000000003', 'upheld')->>'listing_state',
  'removed', 'upholding reports takes the route off the list'
);
reset role;
select is(
  (select count(*)::integer from public.route_reports where resolved_at is null),
  0, 'resolving closes every open report on the route'
);
select is(
  (select string_agg(distinct resolution, ',') from public.route_reports where route_id = '50000000-0000-4000-8000-000000000003'),
  'upheld', 'upheld reports are recorded as upheld'
);
set local role authenticated;
select is(
  jsonb_array_length(public.list_reported_routes()),
  0, 'resolved routes leave the reports view'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000c903', true);
select lives_ok(
  $$select public.report_route('50000000-0000-4000-8000-000000000004', 'inappropriate')$$,
  'a reader may report again after the earlier report was resolved'
);
reset role;

select * from finish();
rollback;
