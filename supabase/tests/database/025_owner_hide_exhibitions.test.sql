begin;

create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;
select plan(26);

select has_column('content', 'exhibitions', 'owner_hidden_at',
  'canonical exhibitions record owner-list hiding without deletion');
select has_column('content', 'exhibitions', 'owner_hidden_by',
  'owner-list hiding records the actor');
select has_index('content', 'exhibitions', 'exhibitions_owner_hidden_by_idx',
  'owner actor foreign-key lookups have a supporting index');
select has_function('public', 'owner_hide_exhibition', array['text', 'uuid', 'integer'],
  'owner hide RPC exists');
select is(
  (select prosecdef::text from pg_proc where oid = 'public.owner_hide_exhibition(text,uuid,integer)'::regprocedure),
  'false', 'public owner hide wrapper is SECURITY INVOKER'
);
select ok(
  has_function_privilege('authenticated', 'public.owner_hide_exhibition(text,uuid,integer)', 'EXECUTE')
    and not has_function_privilege('anon', 'public.owner_hide_exhibition(text,uuid,integer)', 'EXECUTE'),
  'only authenticated callers receive wrapper execution'
);
select ok(
  not has_table_privilege('authenticated', 'content.exhibitions', 'UPDATE'),
  'browser callers still cannot update canonical exhibitions directly'
);

insert into auth.users (id, email, email_confirmed_at, created_at, updated_at, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000002501', 'hide-owner@example.invalid', now(), now(), now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002502', 'hide-other@example.invalid', now(), now(), now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000002503', 'hide-claimant@example.invalid', now(), now(), now(), '{}'::jsonb);

insert into content.galleries (id, name_ko, name_en, status, created_by, updated_by)
values
  ('25100000-0000-0000-0000-000000000001', '숨김 갤러리', 'Hide Gallery', 'active',
   '00000000-0000-0000-0000-000000002501', '00000000-0000-0000-0000-000000002501'),
  ('25100000-0000-0000-0000-000000000002', '다른 갤러리', 'Other Gallery', 'active',
   '00000000-0000-0000-0000-000000002502', '00000000-0000-0000-0000-000000002502');

insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_website_url, created_by, updated_by
)
values
  ('25100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000002501', 'active', 'https://hide.example.invalid',
   '00000000-0000-0000-0000-000000002501', '00000000-0000-0000-0000-000000002501'),
  ('25100000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000002502', 'active', 'https://other.example.invalid',
   '00000000-0000-0000-0000-000000002502', '00000000-0000-0000-0000-000000002502'),
  ('25100000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000002503', 'pending', 'https://claimant.example.invalid',
   '00000000-0000-0000-0000-000000002503', '00000000-0000-0000-0000-000000002503');

insert into content.exhibitions (
  id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
)
values
  ('hide-draft', '25100000-0000-0000-0000-000000000001', 'draft', now(),
   '00000000-0000-0000-0000-000000002501', '00000000-0000-0000-0000-000000002501'),
  ('hide-submitted', '25100000-0000-0000-0000-000000000001', 'submitted', now(),
   '00000000-0000-0000-0000-000000002501', '00000000-0000-0000-0000-000000002501'),
  ('hide-published', '25100000-0000-0000-0000-000000000001', 'published', now(),
   '00000000-0000-0000-0000-000000002501', '00000000-0000-0000-0000-000000002501'),
  ('hide-other', '25100000-0000-0000-0000-000000000002', 'draft', now(),
   '00000000-0000-0000-0000-000000002502', '00000000-0000-0000-0000-000000002502');

insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, name_en, venue_name_ko, venue_name_en,
  city_ko, city_en, region_ko, region_en, address_ko, address_en,
  latitude, longitude, opening_date, closing_date, hours,
  published_at, published_by, created_by, updated_by
)
values
  ('25200000-0000-0000-0000-000000000001', 'hide-draft', 1, 3, 'draft',
   '초안', 'Draft', '장소', 'Venue', '서울', 'Seoul', '종로구', 'Jongno-gu', '주소', 'Address',
   37.57, 126.98, '2026-08-01', '2026-08-31', 'Daily', null, null,
   '00000000-0000-0000-0000-000000002501', '00000000-0000-0000-0000-000000002501'),
  ('25200000-0000-0000-0000-000000000002', 'hide-submitted', 1, 4, 'draft',
   '제출', 'Submitted', '장소', 'Venue', '서울', 'Seoul', '종로구', 'Jongno-gu', '주소', 'Address',
   37.57, 126.98, '2026-08-01', '2026-08-31', 'Daily', null, null,
   '00000000-0000-0000-0000-000000002501', '00000000-0000-0000-0000-000000002501'),
  ('25200000-0000-0000-0000-000000000003', 'hide-published', 1, 5, 'published',
   '공개', 'Published', '장소', 'Venue', '서울', 'Seoul', '종로구', 'Jongno-gu', '주소', 'Address',
   37.57, 126.98, '2026-08-01', '2026-08-31', 'Daily', now(),
   '00000000-0000-0000-0000-000000002501',
   '00000000-0000-0000-0000-000000002501', '00000000-0000-0000-0000-000000002501'),
  ('25200000-0000-0000-0000-000000000004', 'hide-other', 1, 2, 'draft',
   '다른', 'Other', '장소', 'Venue', '서울', 'Seoul', '종로구', 'Jongno-gu', '주소', 'Address',
   37.57, 126.98, '2026-08-01', '2026-08-31', 'Daily', null, null,
   '00000000-0000-0000-0000-000000002502', '00000000-0000-0000-0000-000000002502');

update content.exhibitions
set published_version_id = '25200000-0000-0000-0000-000000000003'
where id = 'hide-published';

set local role authenticated;
select set_config('request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002501","role":"authenticated"}', true);

select is((select count(*)::integer from public.owner_list_exhibitions()), 3,
  'owner initially sees every canonical exhibition');
select is(public.owner_hide_exhibition('hide-draft',
  '25200000-0000-0000-0000-000000000001', 3) ->> 'hidden', 'true',
  'owner can hide a draft');
select is((select count(*)::integer from public.owner_list_exhibitions()), 2,
  'hidden draft leaves My exhibitions');

reset role;
select is((select count(*)::integer from content.exhibitions where id = 'hide-draft'), 1,
  'hide preserves the canonical exhibition row');
select is((select count(*)::integer from content.exhibition_versions where exhibition_id = 'hide-draft'), 1,
  'hide preserves every version row');
select is((select count(*)::integer from content.audit_log where action = 'owner_exhibition.hidden'), 1,
  'accepted hide writes one audit event');
select ok(not exists (
  select 1 from content.audit_log
  where action = 'owner_exhibition.hidden'
    and metadata::text ~ '(초안|Draft|주소|Address)'
), 'hide audit metadata does not copy exhibition content');

set local role authenticated;
select set_config('request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002501","role":"authenticated"}', true);
select is(public.owner_hide_exhibition('hide-draft',
  '25200000-0000-0000-0000-000000000001', 3) ->> 'hidden', 'true',
  'repeating an accepted hide is idempotent');
reset role;
select is((select count(*)::integer from content.audit_log where action = 'owner_exhibition.hidden'), 1,
  'idempotent replay does not duplicate audit evidence');

set local role authenticated;
select set_config('request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002501","role":"authenticated"}', true);
select throws_ok(
  $$select public.owner_hide_exhibition('hide-submitted', '25200000-0000-0000-0000-000000000002', 3)$$,
  '40001', 'revision_conflict', 'stale displayed revision fails closed');
select is(public.owner_hide_exhibition('hide-submitted',
  '25200000-0000-0000-0000-000000000002', 4) ->> 'hidden', 'true',
  'submitted exhibition can be hidden from the list');
select is(public.owner_hide_exhibition('hide-published',
  '25200000-0000-0000-0000-000000000003', 5) ->> 'hidden', 'true',
  'published exhibition can be hidden from the list');
select is((select count(*)::integer from public.owner_list_exhibitions()), 0,
  'all accepted hides are filtered from My exhibitions');

reset role;
select is((select owner_status::text from content.exhibitions where id = 'hide-submitted'), 'submitted',
  'submitted canonical workflow state remains intact');
select is((select owner_status::text from content.exhibitions where id = 'hide-published'), 'published',
  'published canonical workflow state remains intact');
select is((select published_version_id::text from content.exhibitions where id = 'hide-published'),
  '25200000-0000-0000-0000-000000000003', 'published snapshot linkage remains intact');
select is((select count(*)::integer from public.exhibition_catalog_v2 where id = 'hide-published'), 1,
  'published exhibition remains in the production catalog after owner-list hiding');

set local role authenticated;
select set_config('request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002501","role":"authenticated"}', true);
select throws_ok(
  $$select public.owner_hide_exhibition('hide-other', '25200000-0000-0000-0000-000000000004', 2)$$,
  '42501', 'owner_exhibition_access_denied', 'cross-gallery hide fails closed');

select set_config('request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000002503","role":"authenticated"}', true);
select throws_ok(
  $$select public.owner_hide_exhibition('hide-draft', '25200000-0000-0000-0000-000000000001', 3)$$,
  '42501', 'gallery_info_access_denied', 'pending claimant for an existing gallery cannot hide');

select * from finish();
rollback;
