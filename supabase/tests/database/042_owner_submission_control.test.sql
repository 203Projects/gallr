begin;
create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;
select no_plan();
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


insert into content.media_assets(id,status,bucket_id,object_path,public_url,mime_type,byte_size,uploaded_by,published_at)
values ('25500000-0000-0000-0000-000000000001','published','exhibition-media','owner-qa/cover.jpg','https://images.example.invalid/cover.jpg','image/jpeg',2048,'00000000-0000-0000-0000-000000002501',now());
update content.media_assets set delivery_bucket_id='exhibition-images',delivery_object_path='owner-qa/cover.jpg' where id='25500000-0000-0000-0000-000000000001';
insert into content.exhibition_version_media(version_id,media_id,role)
values ('25200000-0000-0000-0000-000000000001','25500000-0000-0000-0000-000000000001','cover'),
('25200000-0000-0000-0000-000000000002','25500000-0000-0000-0000-000000000001','cover');
insert into content.exhibition_submissions(id,status,submitter_email,payload,source,owner_exhibition_id,submitted_at)
values ('25300000-0000-0000-0000-000000000001','in_review','hide-owner@example.invalid',
'{"version_id":"25200000-0000-0000-0000-000000000002","revision":4,"name_ko":"제출","name_en":"Submitted","venue_name_ko":"장소","venue_name_en":"Venue","opening_date":"2026-08-01","closing_date":"2026-08-31","address_ko":"주소","hours":"Daily"}',
'owner_workspace','hide-submitted',now());
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000002501","role":"authenticated"}', true);
select throws_ok($$select public.owner_withdraw_exhibition('hide-submitted','25200000-0000-0000-0000-000000000002',3,'25400000-0000-0000-0000-000000000001')$$,'40001','revision_conflict','stale withdrawal fails');
select throws_ok($$select public.owner_withdraw_exhibition('hide-other','25200000-0000-0000-0000-000000000004',2,'25400000-0000-0000-0000-000000000002')$$,'42501','owner_exhibition_access_denied','foreign gallery cannot withdraw');
select is(public.owner_withdraw_exhibition('hide-submitted','25200000-0000-0000-0000-000000000002',4,'25400000-0000-0000-0000-000000000003')->>'owner_status','draft','withdraw enables editing');
select is(public.owner_withdraw_exhibition('hide-submitted','25200000-0000-0000-0000-000000000002',4,'25400000-0000-0000-0000-000000000003')->>'revision','5','retry returns same advanced revision');
reset role;
select is((select status::text from content.exhibition_submissions where id='25300000-0000-0000-0000-000000000001'),'withdrawn','open review withdrawn');
select is((select count(*)::integer from content.audit_log where action='owner_exhibition.withdrawn'),1,'withdrawal audited once');
set local role authenticated;
select is(public.owner_discard_exhibition('hide-submitted','25200000-0000-0000-0000-000000000002',5,'25400000-0000-0000-0000-000000000004')->>'discarded','true','withdrawn draft can be discarded');
select is(public.owner_discard_exhibition('hide-submitted','25200000-0000-0000-0000-000000000002',5,'25400000-0000-0000-0000-000000000004')->>'discarded','true','discard retry is idempotent');
select throws_ok($$select public.owner_discard_exhibition('hide-published','25200000-0000-0000-0000-000000000003',5,'25400000-0000-0000-0000-000000000005')$$,'22023','owner_submission_already_decided','published work cannot be discarded');
reset role;
select ok((select owner_hidden_at is not null from content.exhibitions where id='hide-submitted'),'discard hides retained draft');
select is((select count(*)::integer from content.exhibition_versions where exhibition_id='hide-submitted'),1,'discard preserves version history');
-- Accepted is a separate stage: it blocks further owner withdrawal/discard.
update content.exhibitions set owner_status='submitted' where id='hide-draft';
insert into content.exhibition_submissions(id,status,submitter_email,payload,source,owner_exhibition_id,submitted_at)
values ('25300000-0000-0000-0000-000000000002','submitted','hide-owner@example.invalid',
'{"version_id":"25200000-0000-0000-0000-000000000001","revision":3,"name_ko":"초안","name_en":"Draft","venue_name_ko":"장소","venue_name_en":"Venue","opening_date":"2026-08-01","closing_date":"2026-08-31","address_ko":"주소","hours":"Daily"}',
'owner_workspace','hide-draft',now());
update content.exhibition_submissions set status='accepted',accepted_exhibition_id='hide-draft',reviewed_at=now(),reviewed_by='00000000-0000-0000-0000-000000002501' where id='25300000-0000-0000-0000-000000000002';
set local role authenticated;
select throws_ok($$select public.owner_withdraw_exhibition('hide-draft','25200000-0000-0000-0000-000000000001',3,'25400000-0000-0000-0000-000000000006')$$,'22023','owner_submission_already_decided','acceptance wins over later withdrawal');
select throws_ok($$select public.owner_discard_exhibition('hide-draft','25200000-0000-0000-0000-000000000001',3,'25400000-0000-0000-0000-000000000007')$$,'22023','owner_submission_already_decided','acceptance wins over later discard');
reset role;
select ok(not has_function_privilege('anon','public.owner_withdraw_exhibition(text,uuid,integer,uuid)','execute'),'anon cannot withdraw');
select ok(not has_function_privilege('service_role','public.owner_discard_exhibition(text,uuid,integer,uuid)','execute'),'service role cannot use owner discard');

-- A fresh submitted round is discarded atomically, with its snapshot retained.
update content.exhibitions set owner_hidden_at=null,owner_hidden_by=null,owner_status='draft' where id='hide-submitted';
set local role authenticated;
select is(public.owner_save_exhibition_draft('hide-submitted','25200000-0000-0000-0000-000000000002',6,'{"name_en":"Revised title"}')->>'name_en','Revised title','retained draft can be edited');
select is(public.owner_submit_exhibition('hide-submitted','25200000-0000-0000-0000-000000000002',7,'25400000-0000-0000-0000-000000000008')->>'owner_status','submitted','editing can be resubmitted');
-- The open round is authoritative even if historical import timestamps sort later.
reset role;
update content.exhibition_submissions set created_at=now()+interval '1 second' where id='25300000-0000-0000-0000-000000000001';
set local role authenticated;
select is(public.owner_discard_exhibition('hide-submitted','25200000-0000-0000-0000-000000000002',7,'25400000-0000-0000-0000-000000000009')->>'discarded','true','submitted draft is discarded');
reset role;
select is((select count(*)::integer from content.exhibition_submissions where owner_exhibition_id='hide-submitted' and status='withdrawn'),2,'both review rounds remain withdrawn');
select ok(not exists(select 1 from content.exhibition_submissions where owner_exhibition_id='hide-submitted' and status in ('submitted','in_review')),'discard leaves no approvable round');
-- Pending claims may discard their own private draft, never an active owner record.
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000002503","role":"authenticated"}', true);
select throws_ok($$select public.owner_discard_exhibition('hide-draft','25200000-0000-0000-0000-000000000001',3,'25400000-0000-0000-0000-000000000010')$$,'42501','owner_exhibition_access_denied','pending claimant cannot discard another owner draft');
reset role;
select * from finish();
rollback;
