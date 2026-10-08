begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(21);

select has_table('content','gallery_profile_images','gallery profile image table exists');
select ok((select relrowsecurity from pg_class where oid='content.gallery_profile_images'::regclass),'profile image table has RLS');
select ok(not has_table_privilege('anon','content.gallery_profile_images','SELECT'),'anonymous cannot read profile image table');
select ok(not has_table_privilege('authenticated','content.gallery_profile_images','INSERT'),'authenticated cannot write profile image table');
select has_function('public','list_gallery_profile_images',array[]::text[],'public profile image RPC exists');
select ok(has_function_privilege('anon','public.list_gallery_profile_images()','EXECUTE'),'anonymous can list profile images');
select ok(has_function_privilege('authenticated','public.list_gallery_profile_images()','EXECUTE'),'authenticated can list profile images');
select ok(not exists(select 1 from aclexplode((select proacl from pg_proc where oid='public.list_gallery_profile_images()'::regprocedure)) acl where acl.grantee=0),'PUBLIC pseudo-role has no implicit execute');
select is((select public from storage.buckets where id='gallery-profile-images'),true,'profile image bucket is public');
select is((select allowed_mime_types from storage.buckets where id='gallery-profile-images'),array['image/jpeg']::text[],'profile image bucket accepts JPEG only');
select is((select file_size_limit from storage.buckets where id='gallery-profile-images'),262144::bigint,'profile image bucket limits object size');

insert into content.galleries(id,name_ko,name_en,status,merged_into_gallery_id) values
 ('00000000-0000-4000-8000-000000009001','활성 갤러리','Active Gallery','active',null),
 ('00000000-0000-4000-8000-000000009002','대기 갤러리','Pending Gallery','pending',null),
 ('00000000-0000-4000-8000-000000009003','병합 갤러리','Merged Gallery','merged','00000000-0000-4000-8000-000000009001');

select lives_ok($$insert into content.gallery_profile_images(gallery_id,kind,storage_path,source_page_url,license,credit,requires_attribution,content_sha256) values
 ('00000000-0000-4000-8000-000000009001','photo','00000000-0000-4000-8000-000000009001/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg','https://commons.wikimedia.org/wiki/File:Active.jpg','CC BY-SA 4.0 (Wikimedia Commons)','Jane Doe, CC BY-SA 4.0',true,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
 ('00000000-0000-4000-8000-000000009002','logo','00000000-0000-4000-8000-000000009002/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.jpg','https://pending.example/','official site',null,false,'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
 ('00000000-0000-4000-8000-000000009003','logo','00000000-0000-4000-8000-000000009003/cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc.jpg','https://merged.example/','official site',null,false,'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc')$$,'operator can record curated images');

select throws_ok($$insert into content.gallery_profile_images(gallery_id,kind,storage_path,source_page_url,license,credit,requires_attribution,content_sha256) values ('00000000-0000-4000-8000-000000009002','banner','00000000-0000-4000-8000-000000009002/dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd.jpg','https://x.example/','official site',null,false,'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd')$$,'23514',null,'unknown kind rejected');
select throws_ok($$update content.gallery_profile_images set storage_path='other/../x.jpg' where gallery_id='00000000-0000-4000-8000-000000009002'$$,'23514',null,'storage path must be gallery-scoped and content-addressed');
select throws_ok($$update content.gallery_profile_images set requires_attribution=true where gallery_id='00000000-0000-4000-8000-000000009002'$$,'23514',null,'attribution requires a credit');
select throws_ok($$update content.gallery_profile_images set credit='Someone' where gallery_id='00000000-0000-4000-8000-000000009002'$$,'23514',null,'credit only when attribution is required');
select throws_ok($$update content.gallery_profile_images set source_page_url='javascript:alert(1)' where gallery_id='00000000-0000-4000-8000-000000009002'$$,'23514',null,'source page must be http(s)');

set local role anon;
select is((select count(*)::integer from public.list_gallery_profile_images()),1,'only active unmerged galleries are listed');
select is(
  (select row(gallery_id,name_ko,name_en,kind,storage_path,credit)::text from public.list_gallery_profile_images()),
  row('00000000-0000-4000-8000-000000009001'::uuid,'활성 갤러리','Active Gallery','photo','00000000-0000-4000-8000-000000009001/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg','Jane Doe, CC BY-SA 4.0')::text,
  'listing exposes names, kind, path and credit');
select throws_ok($$select * from content.gallery_profile_images$$,'42501',null,'anonymous direct table read denied');
select throws_ok($$insert into storage.objects(bucket_id,name,metadata) values ('gallery-profile-images','00000000-0000-4000-8000-000000009001/x.jpg','{}')$$,'42501',null,'anonymous upload denied');
reset role;

select * from finish();
rollback;
