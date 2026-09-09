begin;

create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;
select plan(54);

select has_function(
  'content_private',
  'owner_assert_gallery_membership_record',
  array['boolean'],
  'claimant-aware owner membership helper exists'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'content_private.owner_assert_gallery_membership_record(boolean)',
    'EXECUTE'
  ),
  'browser callers cannot invoke the membership-record helper directly'
);
select ok(
  not has_function_privilege(
    'authenticated',
    'content_private.admin_decide_gallery_claim_impl(uuid,uuid,boolean,text,uuid)',
    'EXECUTE'
  ),
  'the un-serialized claim decision helper is no longer client executable'
);
select ok(
  has_function_privilege(
    'authenticated',
    'content_private.admin_decide_gallery_claim_isolated_impl(uuid,uuid,boolean,text,uuid)',
    'EXECUTE'
  ),
  'authenticated wrappers can enter only the serialized claim decision boundary'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000003501', 'claimant-a@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003502', 'claimant-b@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003503', 'new-gallery@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003504', 'claim-publisher@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003505', 'explicit-winner@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000003506', 'explicit-loser@example.invalid', now(), '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values (
  '00000000-0000-0000-0000-000000003504',
  'publisher'::content.staff_role,
  true
);

insert into content.venues (
  id, slug, name_ko, name_en, address_ko, address_en,
  city_ko, city_en, region_ko, region_en, latitude, longitude,
  default_hours, default_contact
)
values (
  '35000000-0000-0000-0000-000000000001',
  'claim-isolation-private-defaults',
  '비공개 기본 장소',
  'Private Default Venue',
  '승인 전 비공개 주소',
  'Private address before approval',
  '서울',
  'Seoul',
  '종로구',
  'Jongno-gu',
  37.123,
  126.456,
  'Private default hours',
  'private-contact@example.invalid'
);

insert into content.galleries (
  id, canonical_venue_id, name_ko, name_en, status, created_by, updated_by
)
values
  (
    '35100000-0000-0000-0000-000000000001',
    '35000000-0000-0000-0000-000000000001',
    '기존 갤러리',
    'Existing Gallery',
    'active',
    '00000000-0000-0000-0000-000000003504',
    '00000000-0000-0000-0000-000000003504'
  ),
  (
    '35100000-0000-0000-0000-000000000002',
    null,
    '신규 갤러리',
    'New Gallery',
    'pending',
    '00000000-0000-0000-0000-000000003503',
    '00000000-0000-0000-0000-000000003503'
  ),
  (
    '35100000-0000-0000-0000-000000000003',
    null,
    '명시적 거절 갤러리',
    'Explicit Rejection Gallery',
    'active',
    '00000000-0000-0000-0000-000000003504',
    '00000000-0000-0000-0000-000000003504'
  );

insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_note, created_by, updated_by
)
values
  (
    '35100000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000003501',
    'pending',
    'claimant A evidence',
    '00000000-0000-0000-0000-000000003501',
    '00000000-0000-0000-0000-000000003501'
  ),
  (
    '35100000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000003502',
    'pending',
    'claimant B evidence',
    '00000000-0000-0000-0000-000000003502',
    '00000000-0000-0000-0000-000000003502'
  ),
  (
    '35100000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000003503',
    'pending',
    'new gallery evidence',
    '00000000-0000-0000-0000-000000003503',
    '00000000-0000-0000-0000-000000003503'
  ),
  (
    '35100000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000003505',
    'pending',
    'explicit winner evidence',
    '00000000-0000-0000-0000-000000003505',
    '00000000-0000-0000-0000-000000003505'
  ),
  (
    '35100000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000003506',
    'pending',
    'explicit loser evidence',
    '00000000-0000-0000-0000-000000003506',
    '00000000-0000-0000-0000-000000003506'
  );

create temp table claim_isolation_state (
  key text primary key,
  value text not null
);
grant select, insert, update on claim_isolation_state to authenticated;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003501","role":"authenticated"}',
  true
);
with created as (
  select public.owner_create_exhibition_draft(
    '35200000-0000-0000-0000-000000000001'
  ) as payload
)
insert into claim_isolation_state (key, value)
select 'a_exhibition', payload ->> 'id' from created
union all
select 'a_version', payload ->> 'working_version_id' from created;
select ok(
  (select value <> '' from claim_isolation_state where key = 'a_exhibition'),
  'first pending claimant can prepare a draft'
);
select is(
  (
    select payload ->> 'address_ko'
    from public.owner_list_exhibitions() as payload
    where payload ->> 'id' = (
      select value from claim_isolation_state where key = 'a_exhibition'
    )
  ),
  ''::text,
  'existing-gallery claimant cannot copy restricted canonical address defaults'
);
select is(
  (
    select payload ->> 'latitude'
    from public.owner_list_exhibitions() as payload
    where payload ->> 'id' = (
      select value from claim_isolation_state where key = 'a_exhibition'
    )
  ),
  null::text,
  'existing-gallery claimant cannot copy restricted canonical coordinates'
);
select is(
  (select count(*)::integer from public.owner_list_exhibitions()),
  1,
  'first pending claimant lists only their draft'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003502","role":"authenticated"}',
  true
);
with created as (
  select public.owner_create_exhibition_draft(
    '35200000-0000-0000-0000-000000000002'
  ) as payload
)
insert into claim_isolation_state (key, value)
select 'b_exhibition', payload ->> 'id' from created
union all
select 'b_version', payload ->> 'working_version_id' from created;
select ok(
  (select value <> '' from claim_isolation_state where key = 'b_exhibition'),
  'second pending claimant can prepare a separate draft'
);
select is(
  (select count(*)::integer from public.owner_list_exhibitions()),
  1,
  'second pending claimant cannot list the first claimant draft'
);
select is(
  (select payload ->> 'id' from public.owner_list_exhibitions() as payload),
  (select value from claim_isolation_state where key = 'b_exhibition'),
  'pending list is scoped to the exact claimant creator'
);
select throws_ok(
  format(
    'select public.owner_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select value from claim_isolation_state where key = 'a_exhibition'),
    (select value from claim_isolation_state where key = 'a_version'),
    '{"name_ko":"cross claimant"}'
  ),
  '42501',
  'owner_exhibition_access_denied',
  'pending claimant cannot save another claimant draft'
);
select throws_ok(
  format(
    'select public.owner_reserve_cover_upload(%L, %L::uuid, 1, %L, 1024, %L)',
    (select value from claim_isolation_state where key = 'a_exhibition'),
    (select value from claim_isolation_state where key = 'a_version'),
    'image/jpeg',
    'cover.jpg'
  ),
  '42501',
  'owner_exhibition_access_denied',
  'pending claimant cannot reserve media for another claimant draft'
);
select throws_ok(
  format(
    'select public.owner_complete_cover_upload(%L, %L::uuid, 1, %L::uuid)',
    (select value from claim_isolation_state where key = 'a_exhibition'),
    (select value from claim_isolation_state where key = 'a_version'),
    '35200000-0000-0000-0000-000000000009'
  ),
  '42501',
  'owner_exhibition_access_denied',
  'pending claimant cannot complete media for another claimant draft'
);
select throws_ok(
  $$ select * from public.owner_list_launch_kits() $$,
  '42501',
  'active_gallery_membership_required',
  'pending claimant cannot list approved-owner Launch Kit capabilities'
);
select throws_ok(
  $$ select * from public.owner_list_local_promotions() $$,
  '42501',
  'active_gallery_membership_required',
  'pending claimant cannot list approved-owner promotion state'
);
with saved as (
  select public.owner_save_exhibition_draft(
    (select value from claim_isolation_state where key = 'b_exhibition'),
    (select value::uuid from claim_isolation_state where key = 'b_version'),
    1,
    '{"name_ko":"두 번째 청구인의 초안"}'::jsonb
  ) as payload
)
select is(
  (select (payload ->> 'revision')::integer from saved),
  2,
  'pending claimant can still save their own draft'
);
with reserved as (
  select public.owner_reserve_cover_upload(
    (select value from claim_isolation_state where key = 'b_exhibition'),
    (select value::uuid from claim_isolation_state where key = 'b_version'),
    2,
    'image/jpeg',
    1024,
    'pending-cover.jpg'
  ) as payload
)
insert into claim_isolation_state (key, value)
select 'b_asset', payload ->> 'asset_id' from reserved
union all
select 'b_object_path', payload ->> 'object_path' from reserved;
select matches(
  (select value from claim_isolation_state where key = 'b_object_path'),
  '^owner-drafts/00000000-0000-0000-0000-000000003502/',
  'pending claimant reserves media only in their own source path'
);
insert into storage.objects (bucket_id, name, owner_id, metadata)
values (
  'exhibition-media',
  (select value from claim_isolation_state where key = 'b_object_path'),
  '00000000-0000-0000-0000-000000003502',
  '{"mimetype":"image/jpeg","size":"1024"}'::jsonb
);
select is(
  (
    select (payload ->> 'revision')::integer
    from public.owner_complete_cover_upload(
      (select value from claim_isolation_state where key = 'b_exhibition'),
      (select value::uuid from claim_isolation_state where key = 'b_version'),
      2,
      (select value::uuid from claim_isolation_state where key = 'b_asset')
    ) as payload
  ),
  3,
  'pending claimant can complete media for their own draft'
);
reset role;

-- Model a previously active claimant who re-entered pending review with an
-- editable needs-changes draft. Approval must quarantine every editable state,
-- not only newly created draft rows.
insert into content.exhibitions (
  id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
)
values (
  'claimant-b-needs-changes',
  '35100000-0000-0000-0000-000000000001',
  'needs_changes',
  now(),
  '00000000-0000-0000-0000-000000003502',
  '00000000-0000-0000-0000-000000003502'
);
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, venue_name_ko, city_ko, region_ko, address_ko,
  opening_date, closing_date, hours, created_by, updated_by
)
values (
  '35200000-0000-0000-0000-000000000010',
  'claimant-b-needs-changes',
  1,
  1,
  'draft',
  '수정 필요 초안',
  '장소',
  '서울',
  '종로구',
  '주소',
  current_date,
  current_date + 7,
  'Daily',
  '00000000-0000-0000-0000-000000003502',
  '00000000-0000-0000-0000-000000003502'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003503","role":"authenticated"}',
  true
);
with created as (
  select public.owner_create_exhibition_draft(
    '35200000-0000-0000-0000-000000000003'
  ) as payload
)
insert into claim_isolation_state (key, value)
select 'new_exhibition', payload ->> 'id' from created
union all
select 'new_version', payload ->> 'working_version_id' from created;
select ok(
  (select value <> '' from claim_isolation_state where key = 'new_exhibition'),
  'caller-created pending gallery can prepare a draft'
);
select is(
  (select count(*)::integer from public.owner_list_exhibitions()),
  1,
  'caller-created pending gallery lists its draft'
);
with saved as (
  select public.owner_save_exhibition_draft(
    (select value from claim_isolation_state where key = 'new_exhibition'),
    (select value::uuid from claim_isolation_state where key = 'new_version'),
    1,
    '{"name_ko":"신규 갤러리 초안"}'::jsonb
  ) as payload
)
select is(
  (select (payload ->> 'revision')::integer from saved),
  2,
  'caller-created pending gallery can save its draft'
);
select throws_ok(
  format(
    'select public.owner_submit_exhibition(%L, %L::uuid, 2, %L::uuid)',
    (select value from claim_isolation_state where key = 'new_exhibition'),
    (select value from claim_isolation_state where key = 'new_version'),
    '35200000-0000-0000-0000-000000000004'
  ),
  '42501',
  'active_gallery_membership_required',
  'pending gallery creator still cannot submit before approval'
);
reset role;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003504","role":"authenticated"}',
  true
);
select lives_ok(
  $$ select public.admin_approve_gallery_claim(
    '35100000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000003501',
    '35200000-0000-0000-0000-000000000005'
  ) $$,
  'publisher approves one competing claim through the serialized boundary'
);
reset role;

select is(
  (
    select status::text from content.gallery_memberships
    where gallery_id = '35100000-0000-0000-0000-000000000001'
      and user_id = '00000000-0000-0000-0000-000000003501'
  ),
  'active',
  'approved claimant becomes active'
);
select is(
  (
    select status::text from content.gallery_memberships
    where gallery_id = '35100000-0000-0000-0000-000000000001'
      and user_id = '00000000-0000-0000-0000-000000003502'
  ),
  'rejected',
  'competing claimant is rejected atomically'
);
select is(
  (
    select count(*)::integer from content.gallery_memberships
    where gallery_id = '35100000-0000-0000-0000-000000000001'
      and status = 'pending'::content.gallery_membership_status
  ),
  0,
  'approval leaves no competing pending claims'
);
select is(
  (
    select reviewed_by::text from content.gallery_memberships
    where gallery_id = '35100000-0000-0000-0000-000000000001'
      and user_id = '00000000-0000-0000-0000-000000003502'
  ),
  '00000000-0000-0000-0000-000000003504',
  'competing rejection records the publisher'
);
select is(
  (
    select review_notes from content.gallery_memberships
    where gallery_id = '35100000-0000-0000-0000-000000000001'
      and user_id = '00000000-0000-0000-0000-000000003502'
  ),
  'Another claim for this gallery was approved.',
  'competing rejection records a deterministic reason'
);
select ok(
  (
    select owner_hidden_at is not null from content.exhibitions
    where id = (select value from claim_isolation_state where key = 'b_exhibition')
  ),
  'losing claimant draft is quarantined from the approved owner'
);
select ok(
  (
    select owner_hidden_at is not null from content.exhibitions
    where id = 'claimant-b-needs-changes'
  ),
  'losing claimant needs-changes draft is also quarantined'
);
select ok(
  (
    select owner_hidden_at is null from content.exhibitions
    where id = (select value from claim_isolation_state where key = 'a_exhibition')
  ),
  'approved claimant draft remains visible'
);
select is(
  (
    select count(*)::integer from content.audit_log
    where action = 'gallery.claim_rejected'
      and entity_id = '35100000-0000-0000-0000-000000000001'
      and metadata ->> 'user_id' = '00000000-0000-0000-0000-000000003502'
      and metadata ->> 'reason' = 'competing_claim_approved'
  ),
  1,
  'competing rejection writes one metadata-only audit record'
);
select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'gallery.claim_rejected'
      and payload ->> 'gallery_id' = '35100000-0000-0000-0000-000000000001'
      and payload ->> 'user_id' = '00000000-0000-0000-0000-000000003502'
      and payload ->> 'reason' = 'competing_claim_approved'
  ),
  1,
  'competing rejection writes one sanitized outbox event'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003504","role":"authenticated"}',
  true
);
select lives_ok(
  $$ select public.admin_approve_gallery_claim(
    '35100000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000003501',
    '35200000-0000-0000-0000-000000000005'
  ) $$,
  'approval replay remains idempotent'
);
reset role;
select is(
  (
    select count(*)::integer from content.audit_log
    where action = 'gallery.claim_rejected'
      and entity_id = '35100000-0000-0000-0000-000000000001'
      and metadata ->> 'user_id' = '00000000-0000-0000-0000-000000003502'
      and metadata ->> 'reason' = 'competing_claim_approved'
  ),
  1,
  'approval replay does not duplicate competing-rejection audit evidence'
);
select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'gallery.claim_rejected'
      and payload ->> 'gallery_id' = '35100000-0000-0000-0000-000000000001'
      and payload ->> 'user_id' = '00000000-0000-0000-0000-000000003502'
      and payload ->> 'reason' = 'competing_claim_approved'
  ),
  1,
  'approval replay does not duplicate competing-rejection outbox evidence'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003502","role":"authenticated"}',
  true
);
select is(
  public.owner_current_access() #>> '{membership,status}',
  'rejected',
  'losing claimant retains safe claim-status visibility'
);
select throws_ok(
  $$ select * from public.owner_list_exhibitions() $$,
  '42501',
  'gallery_membership_required',
  'losing claimant cannot re-enter the draft workspace'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003501","role":"authenticated"}',
  true
);
select is(
  (select count(*)::integer from public.owner_list_exhibitions()),
  1,
  'approved owner sees only the approved claimant draft'
);
with saved as (
  select public.owner_save_exhibition_draft(
    (select value from claim_isolation_state where key = 'a_exhibition'),
    (select value::uuid from claim_isolation_state where key = 'a_version'),
    1,
    '{"name_ko":"승인된 소유자 초안"}'::jsonb
  ) as payload
)
select is(
  (select (payload ->> 'revision')::integer from saved),
  2,
  'approved owner retains ordinary draft mutation behavior'
);
select throws_ok(
  format(
    'select public.owner_save_exhibition_draft(%L, %L::uuid, 2, %L::jsonb)',
    (select value from claim_isolation_state where key = 'b_exhibition'),
    (select value from claim_isolation_state where key = 'b_version'),
    '{"name_ko":"quarantined"}'
  ),
  '42501',
  'owner_exhibition_access_denied',
  'approved owner cannot mutate the quarantined losing-claim draft'
);

with created as (
  select public.owner_create_exhibition_draft(
    '35200000-0000-0000-0000-000000000006'
  ) as payload
)
insert into claim_isolation_state (key, value)
select 'active_exhibition', payload ->> 'id' from created
union all
select 'active_version', payload ->> 'working_version_id' from created;
select is(
  (
    select payload ->> 'address_ko'
    from public.owner_list_exhibitions() as payload
    where payload ->> 'id' = (
      select value from claim_isolation_state where key = 'active_exhibition'
    )
  ),
  '승인 전 비공개 주소',
  'approved owner still receives canonical address defaults'
);
select is(
  (
    select payload ->> 'latitude'
    from public.owner_list_exhibitions() as payload
    where payload ->> 'id' = (
      select value from claim_isolation_state where key = 'active_exhibition'
    )
  ),
  '37.123',
  'approved owner still receives canonical coordinates'
);

-- Explicit rejection must quarantine the claimant before any later approval;
-- the later approved owner must not inherit or mutate those editable records.
reset role;
insert into content.exhibitions (
  id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
)
values
  (
    'explicit-loser-draft',
    '35100000-0000-0000-0000-000000000003',
    'draft',
    now(),
    '00000000-0000-0000-0000-000000003506',
    '00000000-0000-0000-0000-000000003506'
  ),
  (
    'explicit-loser-needs',
    '35100000-0000-0000-0000-000000000003',
    'needs_changes',
    now(),
    '00000000-0000-0000-0000-000000003506',
    '00000000-0000-0000-0000-000000003506'
  );
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, venue_name_ko, city_ko, region_ko, address_ko,
  opening_date, closing_date, hours, created_by, updated_by
)
values
  ('35200000-0000-0000-0000-000000000011', 'explicit-loser-draft', 1, 1, 'draft', '거절 초안', '장소', '서울', '종로구', '주소', current_date, current_date + 7, 'Daily', '00000000-0000-0000-0000-000000003506', '00000000-0000-0000-0000-000000003506'),
  ('35200000-0000-0000-0000-000000000012', 'explicit-loser-needs', 1, 1, 'draft', '거절 수정', '장소', '서울', '종로구', '주소', current_date, current_date + 7, 'Daily', '00000000-0000-0000-0000-000000003506', '00000000-0000-0000-0000-000000003506');

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003504","role":"authenticated"}',
  true
);
select lives_ok(
  $$ select public.admin_reject_gallery_claim(
    '35100000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000003506',
    'Claim evidence was not verified.',
    '35200000-0000-0000-0000-000000000013'
  ) $$,
  'publisher can explicitly reject a competing claim'
);
reset role;
select is(
  (
    select status::text from content.gallery_memberships
    where gallery_id = '35100000-0000-0000-0000-000000000003'
      and user_id = '00000000-0000-0000-0000-000000003506'
  ),
  'rejected',
  'explicit loser membership is rejected'
);
select ok(
  (
    select bool_and(owner_hidden_at is not null)
    from content.exhibitions
    where id in ('explicit-loser-draft', 'explicit-loser-needs')
  ),
  'explicit rejection quarantines every editable loser record'
);
select is(
  (
    select count(*)::integer from content.audit_log
    where action = 'owner_exhibition.quarantined'
      and entity_id in ('explicit-loser-draft', 'explicit-loser-needs')
      and metadata ->> 'reason' = 'claim_rejected'
  ),
  2,
  'explicit rejection writes one quarantine audit record per exhibition'
);

-- Model an already-rejected claimant deleting their account before another
-- claim is approved. The membership cascades away and created_by becomes NULL;
-- approval must still quarantine this unattributed editable record.
insert into content.exhibitions (
  id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
)
values (
  'explicit-orphan-draft',
  '35100000-0000-0000-0000-000000000003',
  'draft',
  now(),
  '00000000-0000-0000-0000-000000003506',
  '00000000-0000-0000-0000-000000003506'
);
insert into content.exhibition_versions (
  id, exhibition_id, version_number, revision, status,
  name_ko, venue_name_ko, city_ko, region_ko, address_ko,
  opening_date, closing_date, hours, created_by, updated_by
)
values (
  '35200000-0000-0000-0000-000000000015',
  'explicit-orphan-draft',
  1,
  1,
  'draft',
  '고아 초안',
  '장소',
  '서울',
  '종로구',
  '주소',
  current_date,
  current_date + 7,
  'Daily',
  '00000000-0000-0000-0000-000000003506',
  '00000000-0000-0000-0000-000000003506'
);
delete from auth.users
where id = '00000000-0000-0000-0000-000000003506';

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003504","role":"authenticated"}',
  true
);
select lives_ok(
  $$ select public.admin_approve_gallery_claim(
    '35100000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000003505',
    '35200000-0000-0000-0000-000000000014'
  ) $$,
  'publisher can approve the remaining claim after explicit rejection'
);
reset role;
select ok(
  (
    select count(*) = 1
      and bool_and(status = 'active'::content.gallery_membership_status)
    from content.gallery_memberships
    where gallery_id = '35100000-0000-0000-0000-000000000003'
      and user_id = '00000000-0000-0000-0000-000000003505'
  )
  and (
    select count(*) = 0
    from content.gallery_memberships
    where gallery_id = '35100000-0000-0000-0000-000000000003'
      and user_id = '00000000-0000-0000-0000-000000003506'
  ),
  'approval preserves one active owner after the rejected account is deleted'
);
select ok(
  (
    select owner_hidden_at is not null
    from content.exhibitions
    where id = 'explicit-orphan-draft'
  ),
  'approval quarantines an editable draft whose rejected creator was deleted'
);
select is(
  (
    select count(*)::integer
    from content.audit_log
    where action = 'owner_exhibition.quarantined'
      and entity_id = 'explicit-orphan-draft'
      and metadata ->> 'reason' = 'non_approved_or_orphaned_creator'
  ),
  1,
  'orphan quarantine records exact metadata-only audit evidence'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000003505","role":"authenticated"}',
  true
);
select is(
  (select count(*)::integer from public.owner_list_exhibitions()),
  0,
  'approved owner cannot list the explicitly rejected claimant records'
);
select throws_ok(
  $$ select public.owner_save_exhibition_draft(
    'explicit-loser-draft',
    '35200000-0000-0000-0000-000000000011',
    1,
    '{"name_ko":"must stay quarantined"}'::jsonb
  ) $$,
  '42501',
  'owner_exhibition_access_denied',
  'approved owner cannot mutate an explicitly rejected claimant draft'
);
reset role;

select * from finish();
rollback;
