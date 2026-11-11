-- Security audit 2026-10-22 findings #11 and #12: gallery-owner uploads must
-- match a live reservation for an editable draft, and avatars accept only the
-- canonical <uid>.jpg JPEG. The active-owner and pending-claimant upload flows
-- are also exercised end to end by suites 016 and 036; individual submissions
-- by 049.
begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(30);

create function pg_temp.act_as(p_user uuid) returns void language sql as $$
  select set_config(
    'request.jwt.claims',
    json_build_object('sub', p_user, 'role', 'authenticated')::text,
    true
  );
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated;

-- Contract shape.
select has_function(
  'content_private', 'owner_reserved_media_upload_allowed', array['text', 'text'],
  'owner reservation upload check exists'
);
select ok(
  not has_function_privilege(
    'anon', 'content_private.owner_reserved_media_upload_allowed(text,text)', 'EXECUTE'
  ),
  'anonymous callers cannot execute the upload check'
);
select ok(
  (
    select coalesce(with_check, '') like '%owner_reserved_media_upload_allowed(bucket_id, name)%'
      and roles = array['authenticated']::name[]
    from pg_policies
    where schemaname = 'storage' and tablename = 'objects'
      and policyname = 'gallery owners upload draft media' and cmd = 'INSERT'
  ),
  'owner upload policy is bound to the reservation check'
);
select is(
  (select file_size_limit from storage.buckets where id = 'avatars'),
  5242880::bigint,
  'avatar bytes capped at 5 MiB'
);
select is(
  (select allowed_mime_types from storage.buckets where id = 'avatars'),
  array['image/jpeg'],
  'avatar MIME restricted to the app format'
);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('00000000-0000-4000-8000-00000000a901', 'storage-owner@example.invalid', now(), '{}'),
  ('00000000-0000-4000-8000-00000000a902', 'storage-other@example.invalid', now(), '{}'),
  ('00000000-0000-4000-8000-00000000a903', 'storage-unconfirmed@example.invalid', null, '{}');
insert into content.galleries (id, name_ko, status)
values ('69000000-0000-4000-8000-000000000001', '테스트', 'pending');
insert into content.gallery_memberships (gallery_id, user_id, status, claim_website_url) values
  ('69000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-00000000a901',
   'pending', 'https://gallery.example.invalid');

create temp table fixture (draft jsonb, reservation jsonb);
grant all on fixture to authenticated;

set local role authenticated;
select pg_temp.act_as('00000000-0000-4000-8000-00000000a901');
insert into fixture (draft)
select public.owner_create_exhibition_draft('69000000-0000-4000-8000-000000000002');
update fixture set reservation = public.owner_reserve_cover_upload(
  draft ->> 'id', (draft ->> 'working_version_id')::uuid, 1, 'image/jpeg', 100, 'cover.jpg'
);

-- #11: reservation, owner, membership and editable draft.
select throws_ok(
  $$insert into storage.objects (bucket_id, name)
    values ('exhibition-media', 'owner-drafts/00000000-0000-4000-8000-00000000a901/unreserved/original.jpg')$$,
  '42501', null,
  'owner cannot bypass the reservation inside their own prefix'
);
select ok(
  content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', (select reservation ->> 'object_path' from fixture)
  ),
  'valid reserved path accepted'
);
select ok(
  not content_private.owner_reserved_media_upload_allowed(
    'avatars', (select reservation ->> 'object_path' from fixture)
  ),
  'reservation does not open another bucket'
);
select lives_ok(
  $$insert into storage.objects (bucket_id, name)
    select 'exhibition-media', reservation ->> 'object_path' from fixture$$,
  'pending claimant may upload their reserved draft cover'
);

select pg_temp.act_as('00000000-0000-4000-8000-00000000a902');
select ok(
  not content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', (select reservation ->> 'object_path' from fixture)
  ),
  'another account cannot reuse the reservation'
);
select throws_ok(
  $$insert into storage.objects (bucket_id, name)
    values ('exhibition-media', 'owner-drafts/00000000-0000-4000-8000-00000000a902/unreserved/original.jpg')$$,
  '42501', null,
  'non-member cannot write to their own owner-drafts prefix'
);
select lives_ok(
  $$select content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', 'owner-drafts/00000000-0000-4000-8000-00000000a902/x/original.jpg')$$,
  'non-member check returns instead of raising'
);
select ok(
  not content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', 'submissions/00000000-0000-4000-8000-00000000a999/x/original.jpg'
  ),
  'paths outside owner-drafts are left to their own policies'
);

select pg_temp.act_as('00000000-0000-4000-8000-00000000a903');
select lives_ok(
  $$select content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', 'owner-drafts/00000000-0000-4000-8000-00000000a903/x/original.jpg')$$,
  'unconfirmed account check returns instead of raising'
);

-- A fresh reservation for the lifecycle checks below.
select pg_temp.act_as('00000000-0000-4000-8000-00000000a901');
update fixture set reservation = public.owner_reserve_cover_upload(
  draft ->> 'id', (draft ->> 'working_version_id')::uuid, 1, 'image/png', 200, 'second.png'
);
reset role;

update content.media_assets set created_at = now() - interval '2 hours'
where id = (select (reservation ->> 'asset_id')::uuid from fixture);
set local role authenticated;
select ok(
  not content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', (select reservation ->> 'object_path' from fixture)
  ),
  'reservation older than one hour denied'
);
reset role;
update content.media_assets set created_at = now()
where id = (select (reservation ->> 'asset_id')::uuid from fixture);

update content.exhibitions set owner_hidden_at = now()
where id = (select draft ->> 'id' from fixture);
set local role authenticated;
select ok(
  not content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', (select reservation ->> 'object_path' from fixture)
  ),
  'hidden draft denied'
);
reset role;
update content.exhibitions set owner_hidden_at = null
where id = (select draft ->> 'id' from fixture);

update content.exhibitions set owner_status = 'submitted'
where id = (select draft ->> 'id' from fixture);
set local role authenticated;
select ok(
  not content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', (select reservation ->> 'object_path' from fixture)
  ),
  'submitted exhibition is not editable'
);
reset role;

update content.exhibitions set owner_status = 'needs_changes'
where id = (select draft ->> 'id' from fixture);
set local role authenticated;
select ok(
  content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', (select reservation ->> 'object_path' from fixture)
  ),
  'exhibition returned with changes accepts a re-upload'
);
reset role;
update content.exhibitions set owner_status = 'draft'
where id = (select draft ->> 'id' from fixture);

update content.media_assets set status = 'ready'
where id = (select (reservation ->> 'asset_id')::uuid from fixture);
set local role authenticated;
select ok(
  not content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', (select reservation ->> 'object_path' from fixture)
  ),
  'completed reservation cannot be uploaded again'
);
reset role;
update content.media_assets set status = 'pending_upload'
where id = (select (reservation ->> 'asset_id')::uuid from fixture);

update content.gallery_memberships set status = 'rejected'
where user_id = '00000000-0000-4000-8000-00000000a901';
set local role authenticated;
select ok(
  not content_private.owner_reserved_media_upload_allowed(
    'exhibition-media', (select reservation ->> 'object_path' from fixture)
  ),
  'revoked membership denied'
);
select throws_ok(
  $$insert into storage.objects (bucket_id, name)
    select 'exhibition-media', reservation ->> 'object_path' from fixture$$,
  '42501', null,
  'revoked membership cannot upload a reserved path'
);

-- #12: canonical avatar only.
select lives_ok(
  $$insert into storage.objects (bucket_id, name)
    values ('avatars', '00000000-0000-4000-8000-00000000a901.jpg')$$,
  'canonical avatar accepted'
);
select is(
  (
    select count(*)::integer from storage.objects
    where bucket_id = 'avatars' and name = '00000000-0000-4000-8000-00000000a901.jpg'
  ),
  1,
  'owner can read their canonical avatar row (upsert precondition)'
);
with replaced as (
  update storage.objects set metadata = '{"version":2}'
  where bucket_id = 'avatars' and name = '00000000-0000-4000-8000-00000000a901.jpg'
  returning 1
)
select is((select count(*)::integer from replaced), 1, 'canonical avatar can be replaced');
select throws_ok(
  $$insert into storage.objects (bucket_id, name)
    values ('avatars', '00000000-0000-4000-8000-00000000a901.extra.jpg')$$,
  '42501', null, 'arbitrary suffix denied'
);
select throws_ok(
  $$insert into storage.objects (bucket_id, name)
    values ('avatars', '00000000-0000-4000-8000-00000000a901.html')$$,
  '42501', null, 'active-content name denied'
);
select throws_ok(
  $$insert into storage.objects (bucket_id, name)
    values ('avatars', '00000000-0000-4000-8000-00000000a901.png')$$,
  '42501', null, 'non-canonical extension denied'
);
select throws_ok(
  $$update storage.objects set name = '00000000-0000-4000-8000-00000000a901.extra.jpg'
    where bucket_id = 'avatars'$$,
  '42501', null, 'rename cannot evade the canonical path'
);

select pg_temp.act_as('00000000-0000-4000-8000-00000000a902');
select throws_ok(
  $$insert into storage.objects (bucket_id, name)
    values ('avatars', '00000000-0000-4000-8000-00000000a901.jpg')$$,
  '42501', null, 'another account cannot write the avatar'
);
select is(
  (select count(*)::integer from storage.objects where bucket_id = 'avatars'),
  0,
  'another account cannot read the avatar row'
);
reset role;

select * from finish();
rollback;
