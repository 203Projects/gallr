begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(52);

select is(
  (
    select count(*)::integer
    from pg_type as type
    join pg_namespace as namespace on namespace.oid = type.typnamespace
    where namespace.nspname = 'content'
      and type.typname = 'owner_exhibition_status'
  ),
  1,
  'owner exhibition workflow enum exists'
);
select has_column('content', 'exhibitions', 'owner_status', 'owner workflow status exists');
select has_column('content', 'exhibitions', 'owner_review_notes', 'owner review note exists');
select has_column('content', 'exhibition_submissions', 'source', 'submission source exists');
select has_column('content', 'exhibition_submissions', 'owner_exhibition_id', 'owner submission link exists');
select is(
  (
    select count(*)::integer
    from pg_indexes
    where schemaname = 'content'
      and indexname in (
        'exhibitions_gallery_owner_status_idx',
        'exhibition_submissions_owner_exhibition_idx',
        'exhibition_submissions_one_open_owner_round_idx'
      )
  ),
  3,
  'owner list, foreign key, and open-review queries are indexed'
);
select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname in (
        'gallery owners upload draft media',
        'gallery owners read draft media'
      )
  ),
  2,
  'owner draft storage paths have explicit policies'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.owner_list_exhibitions()'),
        ('public.owner_create_exhibition_draft(uuid)'),
        ('public.owner_save_exhibition_draft(text,uuid,integer,jsonb)'),
        ('public.owner_reserve_cover_upload(text,uuid,integer,text,bigint,text)'),
        ('public.owner_complete_cover_upload(text,uuid,integer,uuid)'),
        ('public.owner_submit_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_list_gallery_claims(text,text)'),
        ('public.admin_approve_gallery_claim(uuid,uuid,uuid)'),
        ('public.admin_reject_gallery_claim(uuid,uuid,text,uuid)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  9,
  'authenticated callers receive only the explicit workflow RPC surface'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('public.owner_list_exhibitions()'),
        ('public.owner_create_exhibition_draft(uuid)'),
        ('public.owner_save_exhibition_draft(text,uuid,integer,jsonb)'),
        ('public.owner_submit_exhibition(text,uuid,integer,uuid)'),
        ('public.admin_list_gallery_claims(text,text)')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
  ),
  0,
  'anonymous callers cannot enter owner or claim-review RPCs'
);
select is(
  (
    select count(*)::integer
    from (
      values
        ('content.exhibitions'),
        ('content.exhibition_versions'),
        ('content.media_assets'),
        ('content.exhibition_submissions')
    ) as relation(name)
    where has_table_privilege('authenticated', relation.name, 'INSERT')
       or has_table_privilege('authenticated', relation.name, 'UPDATE')
       or has_table_privilege('authenticated', relation.name, 'DELETE')
  ),
  0,
  'owner workflow adds no generic canonical table write privileges'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
values
  ('00000000-0000-0000-0000-000000000901', 'pending-owner@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000000902', 'other-owner@example.invalid', now(), '{}'::jsonb),
  ('00000000-0000-0000-0000-000000000903', 'publisher@example.invalid', now(), '{}'::jsonb);

insert into content.staff_members (user_id, role, active)
values (
  '00000000-0000-0000-0000-000000000903',
  'publisher'::content.staff_role,
  true
);

insert into content.venues (
  id, slug, name_ko, name_en, address_ko, address_en,
  city_ko, city_en, region_ko, region_en, latitude, longitude
)
values
  (
    '90000000-0000-0000-0000-000000000001', 'owner-alpha',
    '갤러리 알파', 'Gallery Alpha', '서울특별시 종로구 삼청로 12',
    '12 Samcheong-ro, Jongno-gu, Seoul', '서울', 'Seoul', '종로구',
    'Jongno-gu', 37.582, 126.981
  ),
  (
    '90000000-0000-0000-0000-000000000002', 'owner-beta',
    '갤러리 베타', 'Gallery Beta', '서울특별시 용산구 이태원로 1',
    '1 Itaewon-ro, Yongsan-gu, Seoul', '서울', 'Seoul', '용산구',
    'Yongsan-gu', 37.534, 126.994
  );

insert into content.galleries (
  id, canonical_venue_id, name_ko, name_en, status, created_by, updated_by
)
values
  (
    '91000000-0000-0000-0000-000000000001',
    '90000000-0000-0000-0000-000000000001',
    '갤러리 알파', 'Gallery Alpha', 'pending',
    '00000000-0000-0000-0000-000000000901',
    '00000000-0000-0000-0000-000000000901'
  ),
  (
    '91000000-0000-0000-0000-000000000002',
    '90000000-0000-0000-0000-000000000002',
    '갤러리 베타', 'Gallery Beta', 'active',
    '00000000-0000-0000-0000-000000000902',
    '00000000-0000-0000-0000-000000000902'
  );

insert into content.gallery_memberships (
  gallery_id, user_id, status, claim_website_url, created_by, updated_by
)
values
  (
    '91000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000901',
    'pending', 'https://alpha.example.invalid',
    '00000000-0000-0000-0000-000000000901',
    '00000000-0000-0000-0000-000000000901'
  ),
  (
    '91000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000902',
    'active', 'https://beta.example.invalid',
    '00000000-0000-0000-0000-000000000902',
    '00000000-0000-0000-0000-000000000902'
  );

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000901","role":"authenticated"}',
  true
);

create temp table owner_test_state (
  key text primary key,
  value text not null
);

with created as (
  select public.owner_create_exhibition_draft(
    '92000000-0000-0000-0000-000000000001'
  ) as payload
)
insert into owner_test_state (key, value)
select 'exhibition_id', payload ->> 'id' from created
union all
select 'version_id', payload ->> 'working_version_id' from created;

reset role;
select is(
  (
    select exhibition.gallery_id
    from content.exhibitions as exhibition
    where exhibition.id = (select value from owner_test_state where key = 'exhibition_id')
  ),
  '91000000-0000-0000-0000-000000000001'::uuid,
  'draft creation binds the authenticated owner gallery'
);
select is(
  (
    select exhibition.owner_status::text
    from content.exhibitions as exhibition
    where exhibition.id = (select value from owner_test_state where key = 'exhibition_id')
  ),
  'draft',
  'new owner exhibition starts as draft'
);
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000903","role":"authenticated"}',
  true
);
select is(
  (
    select count(*)::integer
    from public.admin_list_exhibitions('', null) as listed(payload)
    where payload ->> 'id' = (select value from owner_test_state where key = 'exhibition_id')
  ),
  0,
  'staff exhibition list hides owner drafts before submission'
);
select throws_ok(
  format(
    'select public.admin_get_exhibition(%L)',
    (select value from owner_test_state where key = 'exhibition_id')
  ),
  'P0002',
  'exhibition_not_found',
  'staff cannot open an owner draft before submission'
);
reset role;
select is(
  (
    select version.venue_name_ko
    from content.exhibition_versions as version
    where version.id = (
      select value::uuid from owner_test_state where key = 'version_id'
    )
  ),
  '갤러리 알파',
  'new draft inherits canonical venue defaults'
);
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000901","role":"authenticated"}',
  true
);
select is((select count(*)::integer from public.owner_list_exhibitions()), 1,
  'owner list returns only the caller gallery record');

select throws_ok(
  format(
    'select public.owner_submit_exhibition(%L, %L::uuid, 1, %L::uuid)',
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value from owner_test_state where key = 'version_id'),
    '92000000-0000-0000-0000-000000000002'
  ),
  '42501',
  'active_gallery_membership_required',
  'pending gallery owners cannot submit'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000903","role":"authenticated"}',
  true
);

select is(
  (select count(*)::integer from public.admin_list_gallery_claims('', 'pending')),
  1,
  'staff publisher sees the pending gallery claim'
);
select lives_ok(
  $$select public.admin_approve_gallery_claim(
    '91000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000901',
    '92000000-0000-0000-0000-000000000003'
  )$$,
  'staff publisher approves the gallery claim'
);
reset role;
select is(
  (
    select status::text from content.gallery_memberships
    where gallery_id = '91000000-0000-0000-0000-000000000001'
      and user_id = '00000000-0000-0000-0000-000000000901'
  ),
  'active',
  'claim approval activates the membership'
);
select is(
  (
    select status::text from content.galleries
    where id = '91000000-0000-0000-0000-000000000001'
  ),
  'active',
  'claim approval activates a new gallery identity'
);
select is(
  (
    select count(*)::integer from content.audit_log
    where action = 'gallery.claim_approved'
      and entity_id = '91000000-0000-0000-0000-000000000001'
  ),
  1,
  'claim approval is audited'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000901","role":"authenticated"}',
  true
);

with saved as (
  select public.owner_save_exhibition_draft(
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value::uuid from owner_test_state where key = 'version_id'),
    1,
    jsonb_build_object(
      'name_ko', '작은 방의 기록',
      'name_en', 'Notes from a Small Room',
      'opening_date', '2026-09-02',
      'closing_date', '2026-11-08',
      'hours', 'Tue-Sun 11:00-18:00',
      'description_ko', '작은 방에서 시작된 기록입니다.'
    )
  ) as payload
)
insert into owner_test_state (key, value)
select 'revision', payload ->> 'revision' from saved;

select is((select value::integer from owner_test_state where key = 'revision'), 2,
  'owner save increments the optimistic revision');
select throws_ok(
  format(
    'select public.owner_save_exhibition_draft(%L, %L::uuid, 1, %L::jsonb)',
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value from owner_test_state where key = 'version_id'),
    '{"name_ko":"stale"}'
  ),
  '40001',
  'revision_conflict',
  'stale owner writes fail closed'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000902","role":"authenticated"}',
  true
);
select throws_ok(
  format(
    'select public.owner_save_exhibition_draft(%L, %L::uuid, 2, %L::jsonb)',
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value from owner_test_state where key = 'version_id'),
    '{"name_ko":"cross tenant"}'
  ),
  '42501',
  'owner_exhibition_access_denied',
  'another gallery owner cannot save the draft'
);
select is((select count(*)::integer from public.owner_list_exhibitions()), 0,
  'another gallery owner cannot list the draft');

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000901","role":"authenticated"}',
  true
);

with reserved as (
  select public.owner_reserve_cover_upload(
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value::uuid from owner_test_state where key = 'version_id'),
    2,
    'image/jpeg',
    4096,
    'cover.jpg'
  ) as payload
)
insert into owner_test_state (key, value)
select 'asset_id', payload ->> 'asset_id' from reserved
union all
select 'object_path', payload ->> 'object_path' from reserved;

select matches(
  (select value from owner_test_state where key = 'object_path'),
  '^owner-drafts/00000000-0000-0000-0000-000000000901/',
  'owner cover reservation uses the authenticated user path'
);
insert into storage.objects (bucket_id, name, owner_id, metadata)
values (
  'exhibition-media',
  (select value from owner_test_state where key = 'object_path'),
  '00000000-0000-0000-0000-000000000901',
  '{"mimetype":"image/jpeg","size":"4096"}'::jsonb
);

with completed as (
  select public.owner_complete_cover_upload(
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value::uuid from owner_test_state where key = 'version_id'),
    2,
    (select value::uuid from owner_test_state where key = 'asset_id')
  ) as payload
)
insert into owner_test_state (key, value)
select 'revision_after_cover', payload ->> 'revision' from completed;

select is((select value::integer from owner_test_state where key = 'revision_after_cover'), 3,
  'cover completion increments the draft revision');
reset role;
select is(
  (
    select asset.status::text from content.media_assets as asset
    where asset.id = (select value::uuid from owner_test_state where key = 'asset_id')
  ),
  'ready',
  'verified owner cover becomes ready'
);
select is(
  (
    select attachment.role::text
    from content.exhibition_version_media as attachment
    where attachment.version_id = (
      select value::uuid from owner_test_state where key = 'version_id'
    )
  ),
  'cover',
  'verified owner media is attached as the cover'
);

update content.exhibition_versions
set name_en = ''
where id = (select value::uuid from owner_test_state where key = 'version_id');
select throws_ok(
  format(
    'insert into content.exhibition_submissions (id, status, submitter_email, payload, source, owner_exhibition_id, submitted_at) values (%L::uuid, %L, %L, %L::jsonb, %L, %L, now())',
    '92000000-0000-0000-0000-000000000099',
    'submitted',
    'pending-owner@example.invalid',
    jsonb_build_object('version_id', (select value from owner_test_state where key = 'version_id'))::text,
    'owner_workspace',
    (select value from owner_test_state where key = 'exhibition_id')
  ),
  '23514',
  'owner_submission_bilingual_incomplete',
  'owner submission rejects a missing English translation'
);
update content.exhibition_versions
set name_en = 'Notes from a Small Room'
where id = (select value::uuid from owner_test_state where key = 'version_id');

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000901","role":"authenticated"}',
  true
);
select lives_ok(
  format(
    'select public.owner_submit_exhibition(%L, %L::uuid, 3, %L::uuid)',
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value from owner_test_state where key = 'version_id'),
    '92000000-0000-0000-0000-000000000004'
  ),
  'active owner submits a complete draft'
);
reset role;
select is(
  (
    select owner_status::text from content.exhibitions
    where id = (select value from owner_test_state where key = 'exhibition_id')
  ),
  'submitted',
  'submission updates the owner-visible state'
);
select is(
  (
    select count(*)::integer from content.exhibition_submissions
    where owner_exhibition_id = (
      select value from owner_test_state where key = 'exhibition_id'
    ) and source = 'owner_workspace'
  ),
  1,
  'submission creates one owner review round'
);
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000903","role":"authenticated"}',
  true
);
select is(
  (
    select count(*)::integer
    from public.admin_list_exhibitions('', null) as listed(payload)
    where payload ->> 'id' = (select value from owner_test_state where key = 'exhibition_id')
  ),
  0,
  'staff exhibition list keeps submitted owner work out of the canonical queue'
);
select is(
  (
    select count(*)::integer
    from public.admin_list_exhibition_submissions('', 'submitted') as listed(payload)
    where payload ->> 'owner_exhibition_id' = (select value from owner_test_state where key = 'exhibition_id')
  ),
  1,
  'staff sees submitted owner work once in the submissions queue'
);
reset role;
select is(
  (
    select count(*)::integer
    from content.submission_media as snapshot
    join content.exhibition_submissions as submission
      on submission.id = snapshot.submission_id
    where submission.owner_exhibition_id = (
      select value from owner_test_state where key = 'exhibition_id'
    )
      and snapshot.media_id = (
        select value::uuid from owner_test_state where key = 'asset_id'
      )
  ),
  1,
  'owner submission snapshots the attached cover for staff review'
);
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000901","role":"authenticated"}',
  true
);
select lives_ok(
  format(
    'select public.owner_submit_exhibition(%L, %L::uuid, 3, %L::uuid)',
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value from owner_test_state where key = 'version_id'),
    '92000000-0000-0000-0000-000000000004'
  ),
  'submission request replay is idempotent'
);
reset role;
select is(
  (
    select count(*)::integer from content.exhibition_submissions
    where owner_exhibition_id = (
      select value from owner_test_state where key = 'exhibition_id'
    )
  ),
  1,
  'idempotent replay does not duplicate a review round'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000903","role":"authenticated"}',
  true
);

select lives_ok(
  format(
    'select public.admin_reject_exhibition_submission((select id from content.exhibition_submissions where owner_exhibition_id = %L), %L, %L::uuid)',
    (select value from owner_test_state where key = 'exhibition_id'),
    'Add the full street address and confirm opening hours.',
    '92000000-0000-0000-0000-000000000005'
  ),
  'staff can request changes on an owner submission'
);
reset role;
select is(
  (
    select owner_status::text from content.exhibitions
    where id = (select value from owner_test_state where key = 'exhibition_id')
  ),
  'needs_changes',
  'staff rejection moves the owner workflow to needs changes'
);
select is(
  (
    select owner_review_notes from content.exhibitions
    where id = (select value from owner_test_state where key = 'exhibition_id')
  ),
  'Add the full street address and confirm opening hours.',
  'owner record exposes the bounded review note'
);
select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'submission.rejected'
      and aggregate_id = (
        select id::text from content.exhibition_submissions
        where owner_exhibition_id = (select value from owner_test_state where key = 'exhibition_id')
          and status = 'rejected'
      )
      and payload ->> 'source' = 'owner_workspace'
      and payload ->> 'recipient_email' = 'pending-owner@example.invalid'
      and payload ->> 'review_notes' = 'Add the full street address and confirm opening hours.'
  ),
  1,
  'rejection queues a bounded owner email notification'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000901","role":"authenticated"}',
  true
);
select lives_ok(
  format(
    'select public.owner_save_exhibition_draft(%L, %L::uuid, 3, %L::jsonb)',
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value from owner_test_state where key = 'version_id'),
    '{"hours":"Tue-Sun 11:00-19:00"}'
  ),
  'owner can revise a needs-changes draft'
);
select lives_ok(
  format(
    'select public.owner_submit_exhibition(%L, %L::uuid, 4, %L::uuid)',
    (select value from owner_test_state where key = 'exhibition_id'),
    (select value from owner_test_state where key = 'version_id'),
    '92000000-0000-0000-0000-000000000006'
  ),
  'owner can resubmit as a new review round'
);
reset role;
select is(
  (
    select count(*)::integer from content.exhibition_submissions
    where owner_exhibition_id = (
      select value from owner_test_state where key = 'exhibition_id'
    )
  ),
  2,
  'resubmission preserves review history'
);
select is(
  (
    select count(*)::integer
    from content.submission_media as snapshot
    join content.exhibition_submissions as submission
      on submission.id = snapshot.submission_id
    where submission.owner_exhibition_id = (
      select value from owner_test_state where key = 'exhibition_id'
    )
      and snapshot.media_id = (
        select value::uuid from owner_test_state where key = 'asset_id'
      )
  ),
  2,
  'each owner review round keeps its own media snapshot'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000903","role":"authenticated"}',
  true
);
select lives_ok(
  format(
    'select public.admin_accept_exhibition_submission((select id from content.exhibition_submissions where owner_exhibition_id = %L and status = ''submitted''), %L::uuid)',
    (select value from owner_test_state where key = 'exhibition_id'),
    '92000000-0000-0000-0000-000000000007'
  ),
  'staff accepts the existing owner draft into the editor'
);
reset role;
select is(
  (
    select count(*)::integer from content.exhibitions
    where gallery_id = '91000000-0000-0000-0000-000000000001'
  ),
  1,
  'owner submission acceptance does not duplicate the exhibition identity'
);
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000903","role":"authenticated"}',
  true
);
select is(
  (
    select count(*)::integer
    from public.admin_list_exhibitions('', null) as listed(payload)
    where payload ->> 'id' = (select value from owner_test_state where key = 'exhibition_id')
  ),
  1,
  'accepted owner work becomes visible in the canonical exhibition list'
);
reset role;
select is(
  (
    select count(*)::integer from content.outbox_events
    where event_type = 'submission.accepted'
      and aggregate_id = (
        select id::text from content.exhibition_submissions
        where owner_exhibition_id = (select value from owner_test_state where key = 'exhibition_id')
          and status = 'accepted'
      )
      and payload ->> 'source' = 'owner_workspace'
      and payload ->> 'recipient_email' = 'pending-owner@example.invalid'
  ),
  1,
  'acceptance queues an owner email notification'
);
select is(
  (
    select count(*)::integer from content.audit_log
    where action = 'owner_exhibition.submitted'
      and entity_id = (select value from owner_test_state where key = 'exhibition_id')
  ),
  2,
  'each owner submission round is durably audited'
);

select * from finish();
rollback;
