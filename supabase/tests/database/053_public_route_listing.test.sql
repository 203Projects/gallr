-- Public routes listing state (spec 089 US7, contracts/public-routes-functions.md): author requests and withdrawals,
-- editor auto-approval, the edit reset, staff unlist/restore, revoke, and the author rows' listing fields.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(47);

-- Schema and privileges
select has_column('public', 'personal_routes', 'listing_state', 'routes carry a listing state');
select has_column('public', 'personal_routes', 'listing_last_approved_at', 'routes remember their last approval');
select ok(not has_function_privilege('anon', 'public.request_route_listing(uuid)', 'EXECUTE'), 'anon cannot request listing');
select ok(has_function_privilege('authenticated', 'public.request_route_listing(uuid)', 'EXECUTE'), 'signed-in users can request listing');
select ok(not has_function_privilege('anon', 'public.withdraw_route_listing(uuid)', 'EXECUTE'), 'anon cannot withdraw');
select ok(not has_function_privilege('anon', 'public.unlist_route(uuid)', 'EXECUTE'), 'anon cannot unlist');
select ok(not has_function_privilege('anon', 'public.restore_route_listing(uuid)', 'EXECUTE'), 'anon cannot restore');

insert into auth.users (id, email, email_confirmed_at, is_anonymous, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000a701', 'list-author@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000a702', 'list-editor@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000a703', 'list-former-editor@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000a704', 'list-stranger@example.invalid', now(), false, '{}'),
  ('00000000-0000-4000-8000-00000000a705', 'list-staff@example.invalid', now(), false, '{}');
insert into public.editors (id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en, is_active, active_from) values
  ('listing-editor', '에디터', 'Editor', '에디터', 'Editor', '소개', 'Bio', true, current_date),
  ('listing-former-editor', '전 에디터', 'Former editor', '에디터', 'Editor', '소개', 'Bio', false, current_date);
insert into content.editor_memberships (user_id, editor_id, active) values
  ('00000000-0000-4000-8000-00000000a702', 'listing-editor', true),
  ('00000000-0000-4000-8000-00000000a703', 'listing-former-editor', false);
insert into content.staff_members (user_id, role, active) values ('00000000-0000-4000-8000-00000000a705', 'admin', true);

insert into public.exhibition_catalog_v2 (
  id, name_ko, name_en, venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
  opening_date, closing_date, is_featured, latitude, longitude, description_ko, description_en,
  address_ko, address_en, is_homepage_featured, updated_at, is_editors_pick, content_checksum_sha256
)
select
  stop.id, stop.id, stop.id, '갤러리 ' || stop.id, 'Gallery ' || stop.id, '서울', 'Seoul', '종로구', 'Jongno-gu',
  seoul.today + stop.opens, seoul.today + stop.closes, false, 37.57, 126.98, '', '', '', '', false, now(), false,
  repeat('0', 64)
from (select (now() at time zone 'Asia/Seoul')::date as today) as seoul,
  (values ('lr-1', -5, 30), ('lr-2', -5, 30), ('lr-3', -5, 30), ('lr-ended', -20, -1), ('lr-future', 40, 60),
          ('lr-gone', -5, 30)) as stop(id, opens, closes);

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a701', true);
select public.save_personal_route('30000000-0000-4000-8000-000000000001', '공개 후보', array['lr-1', 'lr-2']);
select public.publish_personal_route('30000000-0000-4000-8000-000000000001');
select public.save_personal_route('30000000-0000-4000-8000-000000000002', '비공개', array['lr-1', 'lr-2']);
select public.save_personal_route('30000000-0000-4000-8000-000000000003', '종료 포함', array['lr-1', 'lr-ended']);
select public.publish_personal_route('30000000-0000-4000-8000-000000000003');
select public.save_personal_route('30000000-0000-4000-8000-000000000004', '겹치지 않음', array['lr-1', 'lr-future']);
select public.publish_personal_route('30000000-0000-4000-8000-000000000004');
select public.save_personal_route('30000000-0000-4000-8000-000000000005', '사라질 전시', array['lr-1', 'lr-gone']);
select public.publish_personal_route('30000000-0000-4000-8000-000000000005');

-- Author requests and withdrawals
select is(
  (select r->>'listing_state' from jsonb_array_elements(public.list_my_personal_routes()) r
   where r->>'id' = '30000000-0000-4000-8000-000000000001'),
  'unlisted', 'a saved route starts unlisted'
);
select is(
  (select r->>'listing_blocker' from jsonb_array_elements(public.list_my_personal_routes()) r
   where r->>'id' = '30000000-0000-4000-8000-000000000002'),
  null, 'an unlisted route has no listing reason'
);
select throws_ok(
  $$select public.request_route_listing('30000000-0000-4000-8000-000000000002')$$,
  '55000', 'route_listing_requires_published', 'an unpublished route cannot be listed'
);
select is(
  public.request_route_listing('30000000-0000-4000-8000-000000000001')->>'listing_state',
  'requested', 'a non-editor request waits for review'
);
select is(
  public.request_route_listing('30000000-0000-4000-8000-000000000001')->>'listing_state',
  'requested', 'requesting again is harmless'
);
select ok(
  (select listing_requested_at is not null from public.personal_routes where id = '30000000-0000-4000-8000-000000000001'),
  'the request time is recorded'
);
select is(
  (select r->>'author_is_editor' from jsonb_array_elements(public.list_my_personal_routes()) r
   where r->>'id' = '30000000-0000-4000-8000-000000000001'),
  'false', 'the author row says the author is not an editor'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a704', true);
select throws_ok(
  $$select public.request_route_listing('30000000-0000-4000-8000-000000000001')$$,
  '42501', 'personal_route_not_owner', 'another account cannot request listing'
);
select throws_ok(
  $$select public.withdraw_route_listing('30000000-0000-4000-8000-000000000001')$$,
  '42501', 'personal_route_not_owner', 'another account cannot withdraw'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a701', true);
select is(
  public.withdraw_route_listing('30000000-0000-4000-8000-000000000001')->>'listing_state',
  'unlisted', 'the author withdraws a request'
);
select is(
  public.request_route_listing('30000000-0000-4000-8000-000000000001')->>'listing_state',
  'requested', 'the author requests again'
);
select public.save_personal_route('30000000-0000-4000-8000-000000000001', '공개 후보', array['lr-1', 'lr-2']);
select is(
  (select listing_state from public.personal_routes where id = '30000000-0000-4000-8000-000000000001'),
  'requested', 'an unchanged save keeps the request'
);

-- The edit reset (R10)
reset role;
update public.personal_routes
set listing_state = 'approved', listing_decided_at = now(), listing_last_approved_at = now()
where id = '30000000-0000-4000-8000-000000000001';
set local role authenticated;
select public.save_personal_route('30000000-0000-4000-8000-000000000001', '공개 후보 (수정)', array['lr-1', 'lr-2']);
select is(
  (select listing_state from public.personal_routes where id = '30000000-0000-4000-8000-000000000001'),
  'requested', 'renaming an approved route sends it back to review'
);
select ok(
  (select listing_last_approved_at is not null from public.personal_routes
   where id = '30000000-0000-4000-8000-000000000001'),
  'a route sent back to review remembers it was approved'
);
reset role;
update public.personal_routes set listing_state = 'approved', listing_decided_at = now()
where id = '30000000-0000-4000-8000-000000000001';
set local role authenticated;
select public.save_personal_route('30000000-0000-4000-8000-000000000001', '공개 후보 (수정)', array['lr-2', 'lr-1']);
select is(
  (select listing_state from public.personal_routes where id = '30000000-0000-4000-8000-000000000001'),
  'requested', 'reordering an approved route sends it back to review'
);
reset role;
update public.personal_routes set listing_state = 'approved', listing_decided_at = now()
where id = '30000000-0000-4000-8000-000000000001';
set local role authenticated;
select public.save_personal_route('30000000-0000-4000-8000-000000000001', '공개 후보 (수정)', array['lr-2', 'lr-1']);
select is(
  (select listing_state from public.personal_routes where id = '30000000-0000-4000-8000-000000000001'),
  'approved', 'an unchanged save keeps an approval'
);

-- Editors
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a702', true);
select public.save_personal_route('30000000-0000-4000-8000-000000000006', '에디터 동선', array['lr-1', 'lr-2']);
select public.publish_personal_route('30000000-0000-4000-8000-000000000006');
select is(
  public.request_route_listing('30000000-0000-4000-8000-000000000006')->>'listing_state',
  'approved', 'an active editor is approved at once'
);
select is(
  (select listing_decided_by from public.personal_routes where id = '30000000-0000-4000-8000-000000000006'),
  '00000000-0000-4000-8000-00000000a702'::uuid, 'the editor is recorded as the decider'
);
select is(
  (select r->>'author_is_editor' from jsonb_array_elements(public.list_my_personal_routes()) r
   where r->>'id' = '30000000-0000-4000-8000-000000000006'),
  'true', 'the author row says the author is an editor'
);
select public.save_personal_route('30000000-0000-4000-8000-000000000006', '에디터 동선', array['lr-1', 'lr-3']);
select is(
  (select listing_state from public.personal_routes where id = '30000000-0000-4000-8000-000000000006'),
  'approved', 'an editor edit stays approved'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a703', true);
select public.save_personal_route('30000000-0000-4000-8000-000000000007', '전 에디터 동선', array['lr-1', 'lr-2']);
select public.publish_personal_route('30000000-0000-4000-8000-000000000007');
select is(
  public.request_route_listing('30000000-0000-4000-8000-000000000007')->>'listing_state',
  'requested', 'an inactive editor membership waits for review'
);

-- Declined
reset role;
update public.personal_routes
set listing_state = 'declined', listing_decided_at = now(), listing_decline_reason = 'promotional',
    listing_decline_note = '전시 홍보 문구를 빼 주세요'
where id = '30000000-0000-4000-8000-000000000001';
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a701', true);
select is(
  (select r->>'listing_decline_reason' || ' | ' || (r->>'listing_decline_note') || ' | ' || (r->>'listing_blocker')
   from jsonb_array_elements(public.list_my_personal_routes()) r
   where r->>'id' = '30000000-0000-4000-8000-000000000001'),
  'promotional | 전시 홍보 문구를 빼 주세요 | declined', 'the author sees the decline reason, note and blocker'
);
select throws_ok(
  $$select public.withdraw_route_listing('30000000-0000-4000-8000-000000000001')$$,
  '55000', 'route_listing_invalid_transition', 'a declined route is not withdrawn'
);
select is(
  public.request_route_listing('30000000-0000-4000-8000-000000000001')->>'listing_state',
  'requested', 'a declined route can be requested again'
);
select ok(
  (select listing_decline_reason is null and listing_decline_note is null from public.personal_routes
   where id = '30000000-0000-4000-8000-000000000001'),
  'a new request clears the decline'
);

-- Staff unlist and restore
select throws_ok(
  $$select public.unlist_route('30000000-0000-4000-8000-000000000001')$$,
  '42501', 'personal_route_not_staff', 'an author cannot unlist'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a705', true);
select is(
  public.unlist_route('30000000-0000-4000-8000-000000000001')->>'listing_state',
  'removed', 'staff unlist a route'
);
select throws_ok(
  $$select public.restore_route_listing('30000000-0000-4000-8000-000000000002')$$,
  '55000', 'route_listing_invalid_transition', 'only an unlisted-by-staff route can be restored'
);
select throws_ok(
  $$select public.unlist_route('30000000-0000-4000-8000-000000000002')$$,
  '55000', 'route_listing_invalid_transition', 'a route that is not listed cannot be unlisted'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a701', true);
select is(
  (select r->>'listing_blocker' from jsonb_array_elements(public.list_my_personal_routes()) r
   where r->>'id' = '30000000-0000-4000-8000-000000000001'),
  'removed', 'the author sees the route was taken off the list'
);
select throws_ok(
  $$select public.request_route_listing('30000000-0000-4000-8000-000000000001')$$,
  '55000', 'route_listing_invalid_transition', 'the author cannot leave the unlisted-by-staff state'
);
select throws_ok(
  $$select public.withdraw_route_listing('30000000-0000-4000-8000-000000000001')$$,
  '55000', 'route_listing_invalid_transition', 'the author cannot withdraw a removed route'
);
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a705', true);
select is(
  public.restore_route_listing('30000000-0000-4000-8000-000000000001')->>'listing_state',
  'unlisted', 'staff restore a removed route'
);

-- Revoke takes a route off the list
reset role;
update public.personal_routes set listing_state = 'approved', listing_decided_at = now()
where id = '30000000-0000-4000-8000-000000000001';
set local role authenticated;
select public.revoke_personal_route('30000000-0000-4000-8000-000000000001');
reset role;
select is(
  (select listing_state from public.personal_routes where id = '30000000-0000-4000-8000-000000000001'),
  'unlisted', 'revoking a route takes it off the list'
);
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-00000000a701', true);
select is(
  (select r->>'listing_blocker' from jsonb_array_elements(public.list_my_personal_routes()) r
   where r->>'id' = '30000000-0000-4000-8000-000000000001'),
  'revoked', 'a revoked route reports revocation first'
);

-- Why an approved route is not shown (DD13 precedence)
reset role;
delete from public.exhibition_catalog_v2 where id = 'lr-gone';
update public.personal_routes set listing_state = 'approved', listing_decided_at = now()
where id in ('30000000-0000-4000-8000-000000000003', '30000000-0000-4000-8000-000000000004',
             '30000000-0000-4000-8000-000000000005');
set local role authenticated;
select is(
  (select string_agg(r->>'listing_blocker', ',' order by r->>'id') from jsonb_array_elements(public.list_my_personal_routes()) r
   where r->>'id' in ('30000000-0000-4000-8000-000000000003', '30000000-0000-4000-8000-000000000004',
                      '30000000-0000-4000-8000-000000000005')),
  'ended_stop,no_shared_day,missing_stop', 'an approved route names why it is not shown'
);
reset role;
update public.personal_routes set listing_state = 'declined', listing_decline_reason = 'composition'
where id = '30000000-0000-4000-8000-000000000003';
set local role authenticated;
select is(
  (select r->>'listing_blocker' from jsonb_array_elements(public.list_my_personal_routes()) r
   where r->>'id' = '30000000-0000-4000-8000-000000000003'),
  'declined', 'a decline outranks an ended stop'
);

-- Integrity
reset role;
select throws_ok(
  $$update public.personal_routes set listing_state = 'bogus' where id = '30000000-0000-4000-8000-000000000002'$$,
  '23514', null, 'an unknown listing state is refused'
);
select throws_ok(
  $$update public.personal_routes set listing_state = 'declined' where id = '30000000-0000-4000-8000-000000000002'$$,
  '23514', null, 'a decline needs a reason'
);
set local role authenticated;
select throws_ok(
  $$update public.personal_routes set listing_state = 'approved' where id = '30000000-0000-4000-8000-000000000002'$$,
  '42501', null, 'an author cannot approve by writing the table'
);
reset role;

select * from finish();
rollback;
