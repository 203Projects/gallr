begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select no_plan();
insert into auth.users (id,email,email_confirmed_at,raw_user_meta_data) values
 ('00000000-0000-4000-8000-00000000a901','storage-owner@example.invalid',now(),'{}'),
 ('00000000-0000-4000-8000-00000000a902','storage-other@example.invalid',now(),'{}');
insert into content.galleries(id,name_ko,status) values ('69000000-0000-4000-8000-000000000001','테스트','pending');
insert into content.gallery_memberships(gallery_id,user_id,status,claim_website_url) values
 ('69000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-00000000a901','pending','https://gallery.example.invalid');
create temp table fixture (draft jsonb, reservation jsonb);
grant all on fixture to authenticated;
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-4000-8000-00000000a901","role":"authenticated"}',true);
insert into fixture(draft) select public.owner_create_exhibition_draft('69000000-0000-4000-8000-000000000002');
update fixture set reservation = public.owner_reserve_cover_upload(draft->>'id',(draft->>'working_version_id')::uuid,1,'image/jpeg',100,'cover.jpg');
select throws_ok($$insert into storage.objects(bucket_id,name) values ('exhibition-media','owner-drafts/00000000-0000-4000-8000-00000000a901/unreserved/original.jpg')$$,
 '42501',null,'owner cannot bypass reservation');
select lives_ok($$insert into storage.objects(bucket_id,name) select 'exhibition-media',reservation->>'object_path' from fixture$$,'pending claimant may upload their reserved draft');
select ok(content_private.owner_reserved_media_upload_allowed('exhibition-media',(select reservation->>'object_path' from fixture)),'valid reserved path accepted');
select lives_ok($$insert into storage.objects(bucket_id,name) values ('avatars','00000000-0000-4000-8000-00000000a901.jpg')$$,'canonical avatar accepted');
select lives_ok($$update storage.objects set metadata='{"version":2}' where bucket_id='avatars' and name='00000000-0000-4000-8000-00000000a901.jpg'$$,'canonical avatar can be replaced');
select throws_ok($$insert into storage.objects(bucket_id,name) values ('avatars','00000000-0000-4000-8000-00000000a901.extra.jpg')$$,'42501',null,'arbitrary suffix denied');
select throws_ok($$insert into storage.objects(bucket_id,name) values ('avatars','00000000-0000-4000-8000-00000000a901.html')$$,'42501',null,'active content path denied');
select throws_ok($$update storage.objects set name='00000000-0000-4000-8000-00000000a901.extra.jpg' where bucket_id='avatars'$$,'42501',null,'rename cannot evade canonical path');
select set_config('request.jwt.claims','{"sub":"00000000-0000-4000-8000-00000000a902","role":"authenticated"}',true);
select ok(not content_private.owner_reserved_media_upload_allowed('exhibition-media',(select reservation->>'object_path' from fixture)),'another account cannot reuse reservation');
select throws_ok($$insert into storage.objects(bucket_id,name) values ('exhibition-media','owner-drafts/00000000-0000-4000-8000-00000000a902/unreserved/original.jpg')$$,'42501',null,'non-member own prefix denied');
select throws_ok($$insert into storage.objects(bucket_id,name) values ('avatars','00000000-0000-4000-8000-00000000a901.jpg')$$,'42501',null,'other account avatar denied');
reset role;
select is((select file_size_limit from storage.buckets where id='avatars'),5242880::bigint,'avatar bytes capped');
select is((select allowed_mime_types from storage.buckets where id='avatars'),array['image/jpeg'],'avatar MIME restricted to app format');
update content.media_assets set created_at=now()-interval '2 hours' where id=(select (reservation->>'asset_id')::uuid from fixture);
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"00000000-0000-4000-8000-00000000a901","role":"authenticated"}',true);
select ok(not content_private.owner_reserved_media_upload_allowed('exhibition-media',(select reservation->>'object_path' from fixture)),'expired reservation denied');
reset role;
update content.media_assets set created_at=now() where id=(select (reservation->>'asset_id')::uuid from fixture);
update content.exhibitions set owner_hidden_at=now() where id=(select draft->>'id' from fixture);
set local role authenticated;
select ok(not content_private.owner_reserved_media_upload_allowed('exhibition-media',(select reservation->>'object_path' from fixture)),'hidden draft denied');
reset role;
update content.exhibitions set owner_hidden_at=null where id=(select draft->>'id' from fixture);
update content.gallery_memberships set status='rejected' where user_id='00000000-0000-4000-8000-00000000a901';
set local role authenticated;
select ok(not content_private.owner_reserved_media_upload_allowed('exhibition-media',(select reservation->>'object_path' from fixture)),'revoked membership denied');
select * from finish();
rollback;
