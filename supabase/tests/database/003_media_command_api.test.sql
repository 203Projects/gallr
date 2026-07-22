begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(94);

-- -------------------------------------------------------------------------
-- Public surface, canonical write boundary, and Storage policy shape.
-- -------------------------------------------------------------------------

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'admin_list_exhibition_media',
        'admin_request_media_upload',
        'admin_finalize_media_upload',
        'admin_attach_exhibition_media',
        'admin_update_exhibition_media_metadata',
        'admin_reorder_exhibition_media',
        'admin_detach_exhibition_media'
      )
      and not procedure.prosecdef
  ),
  7,
  'all seven public media RPCs are SECURITY INVOKER'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'public'
      and procedure.proname in (
        'admin_list_exhibition_media',
        'admin_request_media_upload',
        'admin_finalize_media_upload',
        'admin_attach_exhibition_media',
        'admin_update_exhibition_media_metadata',
        'admin_reorder_exhibition_media',
        'admin_detach_exhibition_media'
      )
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  7,
  'all public media RPCs pin an empty search_path'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_list_exhibition_media(text,uuid)'),
        ('public.admin_request_media_upload(text,uuid,integer,text,text,bigint)'),
        ('public.admin_finalize_media_upload(text,uuid,integer,uuid,integer,integer,text)'),
        ('public.admin_attach_exhibition_media(text,uuid,integer,uuid,text)'),
        ('public.admin_update_exhibition_media_metadata(text,uuid,integer,uuid,text,text,text,text)'),
        ('public.admin_reorder_exhibition_media(text,uuid,integer,uuid[])'),
        ('public.admin_detach_exhibition_media(text,uuid,integer,uuid)')
    ) as signature(value)
    where has_function_privilege('authenticated', signature.value, 'EXECUTE')
  ),
  7,
  'authenticated can execute every public media RPC'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_list_exhibition_media(text,uuid)'),
        ('public.admin_request_media_upload(text,uuid,integer,text,text,bigint)'),
        ('public.admin_finalize_media_upload(text,uuid,integer,uuid,integer,integer,text)'),
        ('public.admin_attach_exhibition_media(text,uuid,integer,uuid,text)'),
        ('public.admin_update_exhibition_media_metadata(text,uuid,integer,uuid,text,text,text,text)'),
        ('public.admin_reorder_exhibition_media(text,uuid,integer,uuid[])'),
        ('public.admin_detach_exhibition_media(text,uuid,integer,uuid)')
    ) as signature(value)
    where has_function_privilege('anon', signature.value, 'EXECUTE')
  ),
  0,
  'anon can execute no public media RPC'
);

select is(
  (
    select count(*)::integer
    from (
      values
        ('public.admin_list_exhibition_media(text,uuid)'),
        ('public.admin_request_media_upload(text,uuid,integer,text,text,bigint)'),
        ('public.admin_finalize_media_upload(text,uuid,integer,uuid,integer,integer,text)'),
        ('public.admin_attach_exhibition_media(text,uuid,integer,uuid,text)'),
        ('public.admin_update_exhibition_media_metadata(text,uuid,integer,uuid,text,text,text,text)'),
        ('public.admin_reorder_exhibition_media(text,uuid,integer,uuid[])'),
        ('public.admin_detach_exhibition_media(text,uuid,integer,uuid)')
    ) as signature(value)
    where has_function_privilege('service_role', signature.value, 'EXECUTE')
  ),
  0,
  'service_role receives no implicit media command execute grant'
);

select is(
  (
    select count(*)::integer
    from pg_proc as procedure
    join pg_namespace as namespace on namespace.oid = procedure.pronamespace
    where namespace.nspname = 'content_private'
      and procedure.proname in (
        'admin_list_exhibition_media_impl',
        'admin_request_media_upload_impl',
        'admin_finalize_media_upload_impl',
        'admin_attach_exhibition_media_impl',
        'admin_update_exhibition_media_metadata_impl',
        'admin_reorder_exhibition_media_impl',
        'admin_detach_exhibition_media_impl'
      )
      and procedure.prosecdef
      and procedure.proconfig @> array['search_path=""']::text[]
  ),
  7,
  'private media implementations are hardened SECURITY DEFINER functions'
);

select ok(
  (
    select relation.relrowsecurity
    from pg_class as relation
    join pg_namespace as namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = 'storage'
      and relation.relname = 'objects'
  ),
  'storage.objects keeps row-level security enabled'
);

select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname = 'active staff upload registered exhibition media'
      and cmd = 'INSERT'
      and roles = array['authenticated']::name[]
  ),
  1,
  'staged upload access is one authenticated INSERT policy'
);

select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname = 'active staff read registered exhibition media'
      and cmd = 'SELECT'
      and roles = array['authenticated']::name[]
  ),
  1,
  'staged object access is one authenticated SELECT policy'
);

select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname ilike '%exhibition media%'
      and cmd in ('UPDATE', 'DELETE')
  ),
  0,
  'editorial storage has no browser UPDATE or DELETE policy'
);

select ok(
  not has_table_privilege('authenticated', 'content.media_assets', 'UPDATE'),
  'authenticated cannot directly mutate technical media assets'
);

select ok(
  not has_table_privilege(
    'authenticated',
    'content.exhibition_version_media',
    'UPDATE, DELETE'
  ),
  'authenticated cannot directly mutate version attachments'
);

select is(
  (
    select count(*)::integer
    from information_schema.columns
    where table_schema = 'content'
      and table_name = 'exhibition_version_media'
      and column_name in ('alt_ko', 'alt_en', 'credit', 'rights_url')
      and is_nullable = 'NO'
  ),
  4,
  'presentation metadata is version-scoped and non-null'
);

select is(
  (
    select count(*)::integer
    from information_schema.columns
    where table_schema = 'content'
      and table_name = 'media_assets'
      and column_name in (
        'delivery_bucket_id',
        'delivery_object_path',
        'purged_at'
      )
  ),
  3,
  'technical assets track stable delivery and purge state'
);

select ok(
  (
    select bucket.public
      and bucket.file_size_limit = 10485760
      and bucket.allowed_mime_types = array[
        'image/jpeg',
        'image/png',
        'image/webp'
      ]::text[]
    from storage.buckets as bucket
    where bucket.id = 'exhibition-images'
  ),
  'stable exhibition delivery uses a public, image-only ten MiB bucket'
);

select ok(
  exists (
    select 1
    from pg_trigger
    where tgrelid = 'content.exhibition_versions'::regclass
      and tgname = 'exhibition_versions_guard_published_media'
      and not tgisinternal
  ),
  'publication is guarded by attached media readiness'
);

-- -------------------------------------------------------------------------
-- Staff identities and shared state.
-- -------------------------------------------------------------------------

insert into auth.users (id, email, raw_user_meta_data)
values
  (
    '00000000-0000-0000-0000-000000000301'::uuid,
    'media-normal@example.invalid',
    '{}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000302'::uuid,
    'media-contributor@example.invalid',
    '{"full_name":"Media Contributor"}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000303'::uuid,
    'media-publisher@example.invalid',
    '{"full_name":"Media Publisher"}'::jsonb
  ),
  (
    '00000000-0000-0000-0000-000000000304'::uuid,
    'media-inactive@example.invalid',
    '{}'::jsonb
  );

insert into content.staff_members (user_id, role, active)
values
  (
    '00000000-0000-0000-0000-000000000302'::uuid,
    'contributor'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000303'::uuid,
    'publisher'::content.staff_role,
    true
  ),
  (
    '00000000-0000-0000-0000-000000000304'::uuid,
    'contributor'::content.staff_role,
    false
  );

create temporary table media_test_state (
  key text primary key,
  payload jsonb not null default '{}'::jsonb
) on commit drop;
grant select, insert, update, delete on media_test_state
  to authenticated, service_role;

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000301","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    select public.admin_request_media_upload(
      'missing',
      '10000000-0000-0000-0000-000000000001'::uuid,
      1,
      'image.jpg',
      'image/jpeg',
      1000
    )
  $$,
  '42501',
  'active_staff_membership_required',
  'a signed-in non-staff user cannot request an upload'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000304","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    select *
    from public.admin_list_exhibition_media(
      'missing',
      '10000000-0000-0000-0000-000000000001'::uuid
    )
  $$,
  '42501',
  'active_staff_membership_required',
  'inactive staff cannot list media'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

insert into pg_temp.media_test_state (key, payload)
values ('draft', public.admin_create_exhibition_draft());

update pg_temp.media_test_state as state
set payload = public.admin_save_exhibition_draft(
  state.payload ->> 'id',
  (state.payload ->> 'working_version_id')::uuid,
  (state.payload ->> 'revision')::integer,
  jsonb_build_object(
    'name_ko', '미디어 테스트',
    'name_en', 'Media Test',
    'venue_name_ko', '미디어 전시장',
    'venue_name_en', 'Media Gallery',
    'city_ko', '서울',
    'city_en', 'Seoul',
    'region_ko', '용산구',
    'region_en', 'Yongsan-gu',
    'address_ko', '서울 용산구 테스트로 3',
    'latitude', '37.5343',
    'longitude', '126.9946',
    'opening_date', '2026-07-21',
    'closing_date', '2026-09-01'
  )
)
where state.key = 'draft';

select is(
  (
    select (payload ->> 'revision')::integer
    from pg_temp.media_test_state
    where key = 'draft'
  ),
  2,
  'the prepared media draft starts these commands at revision two'
);

-- -------------------------------------------------------------------------
-- Reservation validation, immutable path generation, and Storage RLS.
-- -------------------------------------------------------------------------

select throws_ok(
  $$
    select public.admin_request_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      1,
      'stale.jpg',
      'image/jpeg',
      1000
    )
  $$,
  '40001',
  'revision_conflict',
  'upload reservation requires the exact draft revision'
);

select throws_ok(
  $$
    select public.admin_request_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      'animation.gif',
      'image/gif',
      1000
    )
  $$,
  '22023',
  'unsupported_media_mime_type',
  'upload reservation accepts only JPEG, PNG, and WebP'
);

select throws_ok(
  $$
    select public.admin_request_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      'too-large.jpg',
      'image/jpeg',
      10485761
    )
  $$,
  '22023',
  'invalid_media_byte_size',
  'upload reservation enforces the ten MiB byte limit'
);

select throws_ok(
  $$
    select public.admin_request_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      '',
      'image/jpeg',
      1000
    )
  $$,
  '22023',
  'invalid_original_filename',
  'upload reservation rejects a blank original filename'
);

select is(
  (select count(*) from content.media_assets),
  0::bigint,
  'failed reservations leave no media asset rows'
);

insert into pg_temp.media_test_state (key, payload)
select
  'upload_1',
  public.admin_request_media_upload(
    draft.payload ->> 'id',
    (draft.payload ->> 'working_version_id')::uuid,
    (draft.payload ->> 'revision')::integer,
    '../../Cover FINAL.JPEG',
    'image/jpeg',
    1000
  )
from pg_temp.media_test_state as draft
where draft.key = 'draft';

select matches(
  (
    select payload ->> 'object_path'
    from pg_temp.media_test_state
    where key = 'upload_1'
  ),
  '^drafts/[A-Za-z0-9_-]+/[0-9a-f-]{36}/original\.jpg$',
  'the server generates a safe immutable JPEG path'
);

select is(
  (
    select payload ->> 'original_filename'
    from pg_temp.media_test_state
    where key = 'upload_1'
  ),
  '../../Cover FINAL.JPEG',
  'the client filename is retained only as metadata'
);

select ok(
  (
    select payload ->> 'asset_id' is not null
      and payload ->> 'bucket_id' = 'exhibition-media'
      and payload ->> 'status' = 'pending_upload'
      and payload ->> 'delivery_bucket_id' is null
      and payload ->> 'delivery_object_path' is null
      and payload ->> 'public_url' is null
    from pg_temp.media_test_state
    where key = 'upload_1'
  ),
  'the reservation DTO exposes the staged technical state'
);

select is(
  (
    select (payload ->> 'revision')::integer
    from pg_temp.media_test_state
    where key = 'draft'
  ),
  2,
  'requesting an upload does not increment the content revision'
);

select is(
  (
    select count(*)
    from content.audit_log
    where action = 'media.upload_requested'
      and entity_id = (
        select payload ->> 'asset_id'
        from pg_temp.media_test_state
        where key = 'upload_1'
      )
  ),
  1::bigint,
  'a successful reservation records one actor audit entry'
);

select throws_ok(
  $$
    select public.admin_finalize_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      100,
      200,
      repeat('a', 64)
    )
  $$,
  'P0002',
  'uploaded_storage_object_not_found',
  'finalize requires the exact staged object to exist'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000301","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    insert into storage.objects (
      bucket_id,
      name,
      owner,
      owner_id,
      metadata
    )
    select
      upload.payload ->> 'bucket_id',
      upload.payload ->> 'object_path',
      auth.uid(),
      auth.uid()::text,
      '{"mimetype":"image/jpeg","size":"1000"}'::jsonb
    from pg_temp.media_test_state as upload
    where upload.key = 'upload_1'
  $$,
  '42501',
  null,
  'a non-staff user cannot upload to a registered path'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    insert into storage.objects (
      bucket_id,
      name,
      owner,
      owner_id,
      metadata
    ) values (
      'exhibition-media',
      'drafts/unregistered/object/original.jpg',
      auth.uid(),
      auth.uid()::text,
      '{"mimetype":"image/jpeg","size":"1000"}'::jsonb
    )
  $$,
  '42501',
  null,
  'staff cannot upload to an unregistered object path'
);

select lives_ok(
  $$
    insert into storage.objects (
      bucket_id,
      name,
      owner,
      owner_id,
      metadata
    )
    select
      upload.payload ->> 'bucket_id',
      upload.payload ->> 'object_path',
      auth.uid(),
      auth.uid()::text,
      '{"mimetype":"image/png","size":"1000"}'::jsonb
    from pg_temp.media_test_state as upload
    where upload.key = 'upload_1'
  $$,
  'the reserving contributor can insert the exact staged object'
);

select is(
  (
    select count(*)
    from storage.objects as object
    where object.bucket_id = 'exhibition-media'
      and object.name = (
        select payload ->> 'object_path'
        from pg_temp.media_test_state
        where key = 'upload_1'
      )
  ),
  1::bigint,
  'the contributor SELECT policy exposes the registered staged object'
);

create function pg_temp.try_storage_update(p_object_path text)
returns integer
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_changed integer;
begin
  update storage.objects as object
  set metadata = '{"mimetype":"image/jpeg","size":"999"}'::jsonb
  where object.bucket_id = 'exhibition-media'
    and object.name = p_object_path;
  get diagnostics v_changed = row_count;
  return v_changed;
end;
$$;
grant execute on function pg_temp.try_storage_update(text) to authenticated;

select is(
  pg_temp.try_storage_update(
    (
      select payload ->> 'object_path'
      from pg_temp.media_test_state
      where key = 'upload_1'
    )
  ),
  0,
  'the browser has no Storage UPDATE path even for its own staged object'
);

-- -------------------------------------------------------------------------
-- Finalization validates Storage metadata and is safely replayable.
-- -------------------------------------------------------------------------

select throws_ok(
  $$
    select public.admin_finalize_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      100,
      200,
      repeat('a', 64)
    )
  $$,
  '22023',
  'uploaded_media_mime_mismatch',
  'finalize rejects Storage MIME metadata that differs from the reservation'
);

reset role;
update storage.objects as object
set metadata = '{"mimetype":"image/jpeg","size":"not-a-number"}'::jsonb
where object.bucket_id = 'exhibition-media'
  and object.name = (
    select payload ->> 'object_path'
    from pg_temp.media_test_state
    where key = 'upload_1'
  );
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    select public.admin_finalize_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      )
    )
  $$,
  '22023',
  'uploaded_media_size_metadata_invalid',
  'finalize rejects non-numeric Storage size metadata'
);

reset role;
update storage.objects as object
set metadata = '{"mimetype":"image/jpeg","size":"999"}'::jsonb
where object.bucket_id = 'exhibition-media'
  and object.name = (
    select payload ->> 'object_path'
    from pg_temp.media_test_state
    where key = 'upload_1'
  );
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    select public.admin_finalize_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      )
    )
  $$,
  '22023',
  'uploaded_media_size_mismatch',
  'finalize rejects a Storage size that differs from the reservation'
);

reset role;
update storage.objects as object
set metadata = '{"mimetype":"image/jpeg","size":"1000"}'::jsonb
where object.bucket_id = 'exhibition-media'
  and object.name = (
    select payload ->> 'object_path'
    from pg_temp.media_test_state
    where key = 'upload_1'
  );
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    select public.admin_finalize_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      100,
      null,
      null
    )
  $$,
  '22023',
  'invalid_media_dimensions',
  'optional dimensions must be a positive pair'
);

select throws_ok(
  $$
    select public.admin_finalize_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      100,
      200,
      'not-a-checksum'
    )
  $$,
  '22023',
  'invalid_media_checksum',
  'optional checksums must be lowercase-normalizable SHA-256 hex'
);

update pg_temp.media_test_state as upload
set payload = public.admin_finalize_media_upload(
  draft.payload ->> 'id',
  (draft.payload ->> 'working_version_id')::uuid,
  (draft.payload ->> 'revision')::integer,
  (upload.payload ->> 'asset_id')::uuid,
  100,
  200,
  repeat('A', 64)
)
from pg_temp.media_test_state as draft
where upload.key = 'upload_1'
  and draft.key = 'draft';

select ok(
  (
    select payload ->> 'status' = 'ready'
      and (payload ->> 'width')::integer = 100
      and (payload ->> 'height')::integer = 200
      and payload ->> 'checksum_sha256' = repeat('a', 64)
    from pg_temp.media_test_state
    where key = 'upload_1'
  ),
  'finalize moves pending bytes to ready and normalizes the checksum'
);

select lives_ok(
  $$
    select public.admin_finalize_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      100,
      200,
      repeat('a', 64)
    )
  $$,
  'an identical finalize retry returns the existing result'
);

select is(
  (
    select count(*)
    from content.audit_log
    where action = 'media.upload_finalized'
      and entity_id = (
        select payload ->> 'asset_id'
        from pg_temp.media_test_state
        where key = 'upload_1'
      )
  ),
  1::bigint,
  'an identical finalize retry does not duplicate audit history'
);

select throws_ok(
  $$
    select public.admin_finalize_media_upload(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      101,
      200,
      repeat('a', 64)
    )
  $$,
  '22023',
  'media_finalize_result_mismatch',
  'a finalize retry cannot rewrite authoritative technical metadata'
);

select is(
  (
    select (payload ->> 'revision')::integer
    from pg_temp.media_test_state
    where key = 'draft'
  ),
  2,
  'finalizing bytes does not increment the content revision'
);

create function pg_temp.make_ready_media(
  p_filename text,
  p_mime_type text,
  p_byte_size bigint,
  p_width integer,
  p_height integer,
  p_checksum text
)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_draft jsonb;
  v_upload jsonb;
begin
  select state.payload
  into strict v_draft
  from pg_temp.media_test_state as state
  where state.key = 'draft';

  v_upload := public.admin_request_media_upload(
    v_draft ->> 'id',
    (v_draft ->> 'working_version_id')::uuid,
    (v_draft ->> 'revision')::integer,
    p_filename,
    p_mime_type,
    p_byte_size
  );

  insert into storage.objects (
    bucket_id,
    name,
    owner,
    owner_id,
    metadata
  ) values (
    v_upload ->> 'bucket_id',
    v_upload ->> 'object_path',
    auth.uid(),
    auth.uid()::text,
    jsonb_build_object(
      'mimetype', p_mime_type,
      'size', p_byte_size::text
    )
  );

  return public.admin_finalize_media_upload(
    v_draft ->> 'id',
    (v_draft ->> 'working_version_id')::uuid,
    (v_draft ->> 'revision')::integer,
    (v_upload ->> 'asset_id')::uuid,
    p_width,
    p_height,
    p_checksum
  );
end;
$$;
grant execute on function pg_temp.make_ready_media(
  text,
  text,
  bigint,
  integer,
  integer,
  text
) to authenticated;

insert into pg_temp.media_test_state (key, payload)
values
  (
    'upload_2',
    pg_temp.make_ready_media(
      'first-cover.png',
      'image/png',
      2000,
      200,
      300,
      repeat('b', 64)
    )
  ),
  (
    'upload_3',
    pg_temp.make_ready_media(
      'replacement-cover.webp',
      'image/webp',
      3000,
      300,
      400,
      repeat('c', 64)
    )
  );

select is(
  (
    select count(*)
    from pg_temp.media_test_state
    where key in ('upload_1', 'upload_2', 'upload_3')
      and payload ->> 'status' = 'ready'
  ),
  3::bigint,
  'all three MIME variants can reach ready state'
);

-- -------------------------------------------------------------------------
-- Attachment commands: conflicts, cover replacement, metadata, and order.
-- -------------------------------------------------------------------------

select throws_ok(
  $$
    select public.admin_attach_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      'thumbnail'
    )
  $$,
  '22023',
  'invalid_media_role',
  'attachments accept only cover or gallery roles'
);

select throws_ok(
  $$
    select public.admin_attach_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      1,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      'gallery'
    )
  $$,
  '40001',
  'revision_conflict',
  'attach rejects a stale draft revision'
);

select ok(
  not exists (
    select 1
    from content.exhibition_version_media
    where version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'draft'
    )
  )
  and not exists (
    select 1
    from content.outbox_events
    where event_type = 'media.publish_requested'
  ),
  'failed attach attempts leave attachments and outbox unchanged'
);

insert into pg_temp.media_test_state (key, payload)
select
  'bundle',
  public.admin_attach_exhibition_media(
    draft.payload ->> 'id',
    (draft.payload ->> 'working_version_id')::uuid,
    (draft.payload ->> 'revision')::integer,
    (upload.payload ->> 'asset_id')::uuid,
    'gallery'
  )
from pg_temp.media_test_state as draft
cross join pg_temp.media_test_state as upload
where draft.key = 'draft'
  and upload.key = 'upload_1';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'draft'
  and bundle.key = 'bundle';

select is(
  (
    select (payload ->> 'revision')::integer
    from pg_temp.media_test_state
    where key = 'draft'
  ),
  3,
  'attach increments the draft revision exactly once'
);

select is(
  (
    select jsonb_array_length(payload -> 'media')
    from pg_temp.media_test_state
    where key = 'bundle'
  ),
  1,
  'attach returns the exhibition and its ordered media bundle'
);

reset role;

select ok(
  exists (
    select 1
    from content.outbox_events as event
    join pg_temp.media_test_state as upload on upload.key = 'upload_1'
    where event.event_type = 'media.publish_requested'
      and event.aggregate_id = upload.payload ->> 'asset_id'
      and event.deduplication_key = format(
        'media:%s:publish_requested',
        upload.payload ->> 'asset_id'
      )
      and event.payload ->> 'asset_id' = upload.payload ->> 'asset_id'
      and event.payload ->> 'source_bucket_id' = 'exhibition-media'
      and event.payload ->> 'source_object_path' = upload.payload ->> 'object_path'
      and event.payload ->> 'delivery_bucket_id' = 'exhibition-images'
      and event.payload ->> 'delivery_object_path' = format(
        'cms/%s/original.jpg',
        upload.payload ->> 'asset_id'
      )
  ),
  'ready attachment enqueues the deterministic media publication contract'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    select public.admin_attach_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      'gallery'
    )
  $$,
  '40001',
  'revision_conflict',
  'a retry with its consumed revision conflicts'
);

reset role;

select is(
  (
    select count(*)
    from content.outbox_events
    where event_type = 'media.publish_requested'
      and aggregate_id = (
        select payload ->> 'asset_id'
        from pg_temp.media_test_state
        where key = 'upload_1'
      )
  ),
  1::bigint,
  'a conflicting attach retry cannot duplicate publication intent'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

update pg_temp.media_test_state as bundle
set payload = public.admin_attach_exhibition_media(
  draft.payload ->> 'id',
  (draft.payload ->> 'working_version_id')::uuid,
  (draft.payload ->> 'revision')::integer,
  (upload.payload ->> 'asset_id')::uuid,
  'cover'
)
from pg_temp.media_test_state as draft,
  pg_temp.media_test_state as upload
where bundle.key = 'bundle'
  and draft.key = 'draft'
  and upload.key = 'upload_2';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'draft'
  and bundle.key = 'bundle';

select ok(
  (
    select (payload ->> 'revision')::integer = 4
    from pg_temp.media_test_state
    where key = 'draft'
  )
  and (
    select count(*) = 1
    from content.exhibition_version_media
    where version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'draft'
    )
      and role = 'cover'::content.media_role
      and sort_order = 0
  ),
  'the first cover is attached at canonical order zero'
);

select throws_ok(
  $$
    select public.admin_update_exhibition_media_metadata(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      4,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_2'
      ),
      '첫 번째 표지',
      'First cover',
      'Gallery Credit',
      'javascript:alert(1)'
    )
  $$,
  '22023',
  'invalid_media_rights_url',
  'attachment metadata rejects non-HTTP rights URLs'
);

select is(
  (
    select (payload ->> 'revision')::integer
    from pg_temp.media_test_state
    where key = 'draft'
  ),
  4,
  'invalid metadata leaves the revision unchanged'
);

update pg_temp.media_test_state as bundle
set payload = public.admin_update_exhibition_media_metadata(
  draft.payload ->> 'id',
  (draft.payload ->> 'working_version_id')::uuid,
  (draft.payload ->> 'revision')::integer,
  (upload.payload ->> 'asset_id')::uuid,
  '첫 번째 표지',
  'First cover',
  'Gallery Credit',
  'https://rights.example.invalid/first-cover'
)
from pg_temp.media_test_state as draft,
  pg_temp.media_test_state as upload
where bundle.key = 'bundle'
  and draft.key = 'draft'
  and upload.key = 'upload_2';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'draft'
  and bundle.key = 'bundle';

select ok(
  (
    select (payload ->> 'revision')::integer = 5
    from pg_temp.media_test_state
    where key = 'draft'
  )
  and exists (
    select 1
    from content.exhibition_version_media as attachment
    where attachment.version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'draft'
    )
      and attachment.media_id = (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_2'
      )
      and attachment.alt_ko = '첫 번째 표지'
      and attachment.alt_en = 'First cover'
      and attachment.credit = 'Gallery Credit'
      and attachment.rights_url = 'https://rights.example.invalid/first-cover'
  ),
  'metadata updates the exact version attachment and increments once'
);

select ok(
  exists (
    select 1
    from content.media_assets as asset
    where asset.id = (
      select (payload ->> 'asset_id')::uuid
      from pg_temp.media_test_state
      where key = 'upload_2'
    )
      and asset.alt_ko = ''
      and asset.alt_en = ''
      and coalesce(asset.credit, '') = ''
      and coalesce(asset.rights_url, '') = ''
  ),
  'attachment presentation never mutates shared technical asset metadata'
);

update pg_temp.media_test_state as bundle
set payload = public.admin_attach_exhibition_media(
  draft.payload ->> 'id',
  (draft.payload ->> 'working_version_id')::uuid,
  (draft.payload ->> 'revision')::integer,
  (upload.payload ->> 'asset_id')::uuid,
  'cover'
)
from pg_temp.media_test_state as draft,
  pg_temp.media_test_state as upload
where bundle.key = 'bundle'
  and draft.key = 'draft'
  and upload.key = 'upload_3';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'draft'
  and bundle.key = 'bundle';

select ok(
  (
    select (payload ->> 'revision')::integer = 6
    from pg_temp.media_test_state
    where key = 'draft'
  )
  and (
    select count(*) = 1
    from content.exhibition_version_media
    where version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'draft'
    )
      and role = 'cover'::content.media_role
      and media_id = (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_3'
      )
      and sort_order = 0
  ),
  'cover replacement installs exactly one new cover atomically'
);

select ok(
  exists (
    select 1
    from content.exhibition_version_media as attachment
    where attachment.version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'draft'
    )
      and attachment.media_id = (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_2'
      )
      and attachment.role = 'gallery'::content.media_role
      and attachment.sort_order > 0
      and attachment.alt_en = 'First cover'
      and attachment.credit = 'Gallery Credit'
  ),
  'the replaced cover is demoted to gallery without losing presentation'
);

update pg_temp.media_test_state as bundle
set payload = public.admin_update_exhibition_media_metadata(
  draft.payload ->> 'id',
  (draft.payload ->> 'working_version_id')::uuid,
  (draft.payload ->> 'revision')::integer,
  (upload.payload ->> 'asset_id')::uuid,
  '교체 표지',
  'Replacement cover',
  'Replacement Artist',
  'https://rights.example.invalid/replacement-cover'
)
from pg_temp.media_test_state as draft,
  pg_temp.media_test_state as upload
where bundle.key = 'bundle'
  and draft.key = 'draft'
  and upload.key = 'upload_3';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'draft'
  and bundle.key = 'bundle';

select is(
  (
    select payload ->> 'cover_alt_en'
    from pg_temp.media_test_state
    where key = 'draft'
  ),
  'Replacement cover',
  'the exhibition DTO reads cover presentation from its attachment'
);

select throws_ok(
  $$
    select public.admin_reorder_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      7,
      array[
        (
          select (payload ->> 'asset_id')::uuid
          from pg_temp.media_test_state
          where key = 'upload_1'
        )
      ]
    )
  $$,
  '22023',
  'gallery_order_must_match_exact_set',
  'gallery reorder cannot omit an attached gallery'
);

select throws_ok(
  $$
    select public.admin_reorder_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      7,
      array[
        (
          select (payload ->> 'asset_id')::uuid
          from pg_temp.media_test_state
          where key = 'upload_1'
        ),
        (
          select (payload ->> 'asset_id')::uuid
          from pg_temp.media_test_state
          where key = 'upload_1'
        )
      ]
    )
  $$,
  '22023',
  'gallery_order_must_match_exact_set',
  'gallery reorder rejects duplicate asset IDs'
);

select throws_ok(
  $$
    select public.admin_reorder_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      7,
      array[
        (
          select (payload ->> 'asset_id')::uuid
          from pg_temp.media_test_state
          where key = 'upload_1'
        ),
        (
          select (payload ->> 'asset_id')::uuid
          from pg_temp.media_test_state
          where key = 'upload_3'
        )
      ]
    )
  $$,
  '22023',
  'gallery_order_must_match_exact_set',
  'gallery reorder excludes the fixed cover'
);

select is(
  (
    select (payload ->> 'revision')::integer
    from pg_temp.media_test_state
    where key = 'draft'
  ),
  7,
  'invalid reorder attempts leave the revision unchanged'
);

update pg_temp.media_test_state as bundle
set payload = public.admin_reorder_exhibition_media(
  draft.payload ->> 'id',
  (draft.payload ->> 'working_version_id')::uuid,
  (draft.payload ->> 'revision')::integer,
  array[
    (upload_2.payload ->> 'asset_id')::uuid,
    (upload_1.payload ->> 'asset_id')::uuid
  ]
)
from pg_temp.media_test_state as draft,
  pg_temp.media_test_state as upload_1,
  pg_temp.media_test_state as upload_2
where bundle.key = 'bundle'
  and draft.key = 'draft'
  and upload_1.key = 'upload_1'
  and upload_2.key = 'upload_2';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'draft'
  and bundle.key = 'bundle';

select ok(
  (
    select (payload ->> 'revision')::integer = 8
    from pg_temp.media_test_state
    where key = 'draft'
  )
  and (
    select array_agg(attachment.media_id order by attachment.sort_order)
    from content.exhibition_version_media as attachment
    where attachment.version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'draft'
    )
      and attachment.role = 'gallery'::content.media_role
  ) = array[
    (
      select (payload ->> 'asset_id')::uuid
      from pg_temp.media_test_state
      where key = 'upload_2'
    ),
    (
      select (payload ->> 'asset_id')::uuid
      from pg_temp.media_test_state
      where key = 'upload_1'
    )
  ]
  and (
    select array_agg(attachment.sort_order order by attachment.sort_order)
    from content.exhibition_version_media as attachment
    where attachment.version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'draft'
    )
  ) = array[0, 1, 2],
  'exact gallery reorder writes contiguous canonical order once'
);

select is(
  (
    select count(*)
    from public.admin_list_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      )
    )
  ),
  3::bigint,
  'list returns every attachment for the selected exact version'
);

select ok(
  (
    select listed.value ?& array[
      'asset_id',
      'version_id',
      'role',
      'sort_order',
      'status',
      'bucket_id',
      'object_path',
      'delivery_bucket_id',
      'delivery_object_path',
      'mime_type',
      'byte_size',
      'width',
      'height',
      'checksum_sha256',
      'public_url',
      'alt_ko',
      'alt_en',
      'credit',
      'rights_url',
      'original_filename',
      'created_at',
      'updated_at'
    ]
    from public.admin_list_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      )
    ) as listed(value)
    order by (listed.value ->> 'sort_order')::integer
    limit 1
  ),
  'list emits the complete stable media DTO contract'
);

select is(
  (
    select count(*)
    from content.audit_log
    where action in (
      'exhibition.media_attached',
      'exhibition.media_metadata_updated',
      'exhibition.media_reordered'
    )
      and actor_user_id = '00000000-0000-0000-0000-000000000302'::uuid
  ),
  6::bigint,
  'every successful attachment mutation records the authenticated actor'
);

-- -------------------------------------------------------------------------
-- Ready-to-published lifecycle and immutable published versions.
-- -------------------------------------------------------------------------

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000303","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    select public.admin_publish_exhibition(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'draft'
      ),
      8,
      '90000000-0000-0000-0000-000000000301'::uuid
    )
  $$,
  '23514',
  'attached_media_must_be_published_before_exhibition',
  'an exhibition cannot publish while an attachment is only ready'
);

select ok(
  exists (
    select 1
    from content.exhibition_versions as version
    where version.id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'draft'
    )
      and version.status = 'draft'::content.exhibition_version_status
      and version.revision = 8
  )
  and not exists (
    select 1
    from content.outbox_events
    where event_type = 'exhibition.published'
      and aggregate_id = (
        select payload ->> 'id'
        from pg_temp.media_test_state
        where key = 'draft'
      )
  ),
  'failed publication leaves the version and outbox unchanged'
);

reset role;

select lives_ok(
  $$
    select public.outbox_mark_media_published(
      (select (payload ->> 'asset_id')::uuid from pg_temp.media_test_state where key = 'upload_1'),
      'exhibition-media',
      (select payload ->> 'object_path' from pg_temp.media_test_state where key = 'upload_1'),
      'exhibition-images',
      format(
        'cms/%s/original.jpg',
        (select payload ->> 'asset_id' from pg_temp.media_test_state where key = 'upload_1')
      ),
      'https://cdn.example.invalid/media-1.jpg',
      'image/jpeg',
      1000,
      100,
      200,
      repeat('a', 64),
      repeat('a', 64)
    )
  $$,
  'the worker can confirm the JPEG at its reserved delivery path'
);

select lives_ok(
  $$
    select public.outbox_mark_media_published(
      (select (payload ->> 'asset_id')::uuid from pg_temp.media_test_state where key = 'upload_2'),
      'exhibition-media',
      (select payload ->> 'object_path' from pg_temp.media_test_state where key = 'upload_2'),
      'exhibition-images',
      format(
        'cms/%s/original.png',
        (select payload ->> 'asset_id' from pg_temp.media_test_state where key = 'upload_2')
      ),
      'https://cdn.example.invalid/media-2.png',
      'image/png',
      2000,
      200,
      300,
      repeat('b', 64),
      repeat('b', 64)
    )
  $$,
  'the worker can confirm the PNG at its reserved delivery path'
);

select lives_ok(
  $$
    select public.outbox_mark_media_published(
      (select (payload ->> 'asset_id')::uuid from pg_temp.media_test_state where key = 'upload_3'),
      'exhibition-media',
      (select payload ->> 'object_path' from pg_temp.media_test_state where key = 'upload_3'),
      'exhibition-images',
      format(
        'cms/%s/original.webp',
        (select payload ->> 'asset_id' from pg_temp.media_test_state where key = 'upload_3')
      ),
      'https://cdn.example.invalid/media-3.webp',
      'image/webp',
      3000,
      300,
      400,
      repeat('c', 64),
      repeat('c', 64)
    )
  $$,
  'the worker can confirm the WebP at its reserved delivery path'
);

select is(
  (
    select count(*)
    from content.media_assets
    where id in (
      (select (payload ->> 'asset_id')::uuid from pg_temp.media_test_state where key = 'upload_1'),
      (select (payload ->> 'asset_id')::uuid from pg_temp.media_test_state where key = 'upload_2'),
      (select (payload ->> 'asset_id')::uuid from pg_temp.media_test_state where key = 'upload_3')
    )
      and status = 'published'::content.media_asset_status
      and public_url is not null
      and delivery_bucket_id = 'exhibition-images'
      and delivery_object_path is not null
  ),
  3::bigint,
  'worker confirmation establishes stable publication fields for every asset'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000303","role":"authenticated"}',
  true
);

insert into pg_temp.media_test_state (key, payload)
select
  'published',
  public.admin_publish_exhibition(
    draft.payload ->> 'id',
    (draft.payload ->> 'working_version_id')::uuid,
    (draft.payload ->> 'revision')::integer,
    '90000000-0000-0000-0000-000000000302'::uuid
  )
from pg_temp.media_test_state as draft
where draft.key = 'draft';

select ok(
  (
    select payload ->> 'status' = 'published'
      and (payload ->> 'revision')::integer = 9
      and payload ->> 'cover_image_url' = 'https://cdn.example.invalid/media-3.webp'
      and payload ->> 'cover_alt_en' = 'Replacement cover'
    from pg_temp.media_test_state
    where key = 'published'
  ),
  'publication succeeds only after stable delivery and keeps attachment cover metadata'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

insert into pg_temp.media_test_state (key, payload)
select
  'clone',
  public.admin_save_exhibition_draft(
    published.payload ->> 'id',
    (published.payload ->> 'working_version_id')::uuid,
    (published.payload ->> 'revision')::integer,
    '{"name_en":"Edited after publication"}'::jsonb
  )
from pg_temp.media_test_state as published
where published.key = 'published';

select ok(
  (
    select payload ->> 'status' = 'draft'
      and payload ->> 'working_version_id' <> payload ->> 'published_version_id'
      and (payload ->> 'revision')::integer = 10
    from pg_temp.media_test_state
    where key = 'clone'
  )
  and (
    select count(*) = 3
    from content.exhibition_version_media
    where version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'clone'
    )
  ),
  'editing a published exhibition clones a complete media draft'
);

select ok(
  exists (
    select 1
    from content.exhibition_version_media as draft_attachment
    join content.exhibition_version_media as published_attachment
      on published_attachment.media_id = draft_attachment.media_id
    where draft_attachment.version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'clone'
    )
      and published_attachment.version_id = (
        select (payload ->> 'published_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'clone'
      )
      and draft_attachment.media_id = (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_2'
      )
      and draft_attachment.alt_en = 'First cover'
      and draft_attachment.credit = 'Gallery Credit'
      and draft_attachment.alt_en = published_attachment.alt_en
      and draft_attachment.credit = published_attachment.credit
  ),
  'draft cloning inherits presentation from the exact published attachment'
);

insert into pg_temp.media_test_state (key, payload)
select
  'clone_bundle',
  public.admin_update_exhibition_media_metadata(
    clone.payload ->> 'id',
    (clone.payload ->> 'working_version_id')::uuid,
    (clone.payload ->> 'revision')::integer,
    (upload.payload ->> 'asset_id')::uuid,
    '출판 후 수정',
    'Draft-only override',
    'Draft Credit',
    'https://rights.example.invalid/draft-only'
  )
from pg_temp.media_test_state as clone
cross join pg_temp.media_test_state as upload
where clone.key = 'clone'
  and upload.key = 'upload_2';

update pg_temp.media_test_state as clone
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where clone.key = 'clone'
  and bundle.key = 'clone_bundle';

select ok(
  exists (
    select 1
    from content.exhibition_version_media as attachment
    where attachment.version_id = (
      select (payload ->> 'working_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'clone'
    )
      and attachment.media_id = (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_2'
      )
      and attachment.alt_en = 'Draft-only override'
  )
  and exists (
    select 1
    from content.exhibition_version_media as attachment
    where attachment.version_id = (
      select (payload ->> 'published_version_id')::uuid
      from pg_temp.media_test_state
      where key = 'clone'
    )
      and attachment.media_id = (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_2'
      )
      and attachment.alt_en = 'First cover'
      and attachment.credit = 'Gallery Credit'
  ),
  'editing draft presentation leaves the published attachment immutable'
);

select is(
  (
    select listed.value ->> 'alt_en'
    from public.admin_list_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'clone'),
      (
        select (payload ->> 'published_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'clone'
      )
    ) as listed(value)
    where listed.value ->> 'asset_id' = (
      select payload ->> 'asset_id'
      from pg_temp.media_test_state
      where key = 'upload_2'
    )
  ),
  'First cover',
  'listing the published version still returns its original presentation'
);

select throws_ok(
  $$
    select public.admin_detach_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'published'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'published'
      ),
      9,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      )
    )
  $$,
  'P0002',
  'working_draft_not_found',
  'published versions cannot be detached through draft commands'
);

select throws_ok(
  $$
    select public.admin_attach_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'published'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'published'
      ),
      9,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'upload_1'
      ),
      'gallery'
    )
  $$,
  'P0002',
  'working_draft_not_found',
  'published versions cannot be attached through draft commands'
);

update pg_temp.media_test_state as bundle
set payload = public.admin_detach_exhibition_media(
  clone.payload ->> 'id',
  (clone.payload ->> 'working_version_id')::uuid,
  (clone.payload ->> 'revision')::integer,
  (upload.payload ->> 'asset_id')::uuid
)
from pg_temp.media_test_state as clone,
  pg_temp.media_test_state as upload
where bundle.key = 'clone_bundle'
  and clone.key = 'clone'
  and upload.key = 'upload_1';

update pg_temp.media_test_state as clone
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where clone.key = 'clone'
  and bundle.key = 'clone_bundle';

select ok(
  exists (
    select 1
    from content.media_assets as asset
    where asset.id = (
      select (payload ->> 'asset_id')::uuid
      from pg_temp.media_test_state
      where key = 'upload_1'
    )
      and asset.status = 'published'::content.media_asset_status
  )
  and not exists (
    select 1
    from content.outbox_events
    where event_type = 'media.cleanup_requested'
      and aggregate_id = (
        select payload ->> 'asset_id'
        from pg_temp.media_test_state
        where key = 'upload_1'
      )
  ),
  'detaching a draft reference preserves an asset still used by a published version'
);

-- No-media publication remains valid.
insert into pg_temp.media_test_state (key, payload)
values ('no_media', public.admin_create_exhibition_draft());

update pg_temp.media_test_state as state
set payload = public.admin_save_exhibition_draft(
  state.payload ->> 'id',
  (state.payload ->> 'working_version_id')::uuid,
  (state.payload ->> 'revision')::integer,
  jsonb_build_object(
    'name_ko', '미디어 없는 전시',
    'venue_name_ko', '테스트 공간',
    'city_ko', '서울',
    'region_ko', '중구',
    'address_ko', '서울 중구 테스트로 4',
    'latitude', '37.5641',
    'longitude', '126.9979',
    'opening_date', '2026-07-21',
    'closing_date', '2026-08-21'
  )
)
where state.key = 'no_media';

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000303","role":"authenticated"}',
  true
);

update pg_temp.media_test_state as state
set payload = public.admin_publish_exhibition(
  state.payload ->> 'id',
  (state.payload ->> 'working_version_id')::uuid,
  (state.payload ->> 'revision')::integer,
  '90000000-0000-0000-0000-000000000303'::uuid
)
where state.key = 'no_media';

select is(
  (
    select payload ->> 'status'
    from pg_temp.media_test_state
    where key = 'no_media'
  ),
  'published',
  'publication remains valid when an exhibition intentionally has no media'
);

-- -------------------------------------------------------------------------
-- Final-reference detach, orphan cleanup intent, and rejected DTO state.
-- -------------------------------------------------------------------------

select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

create function pg_temp.make_ready_media_for(
  p_state_key text,
  p_filename text,
  p_mime_type text,
  p_byte_size bigint,
  p_width integer,
  p_height integer,
  p_checksum text
)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_draft jsonb;
  v_upload jsonb;
begin
  select state.payload
  into strict v_draft
  from pg_temp.media_test_state as state
  where state.key = p_state_key;

  v_upload := public.admin_request_media_upload(
    v_draft ->> 'id',
    (v_draft ->> 'working_version_id')::uuid,
    (v_draft ->> 'revision')::integer,
    p_filename,
    p_mime_type,
    p_byte_size
  );

  insert into storage.objects (
    bucket_id,
    name,
    owner,
    owner_id,
    metadata
  ) values (
    v_upload ->> 'bucket_id',
    v_upload ->> 'object_path',
    auth.uid(),
    auth.uid()::text,
    jsonb_build_object(
      'mimetype', p_mime_type,
      'size', p_byte_size::text
    )
  );

  return public.admin_finalize_media_upload(
    v_draft ->> 'id',
    (v_draft ->> 'working_version_id')::uuid,
    (v_draft ->> 'revision')::integer,
    (v_upload ->> 'asset_id')::uuid,
    p_width,
    p_height,
    p_checksum
  );
end;
$$;
grant execute on function pg_temp.make_ready_media_for(
  text,
  text,
  text,
  bigint,
  integer,
  integer,
  text
) to authenticated;

insert into pg_temp.media_test_state (key, payload)
values ('orphan_draft', public.admin_create_exhibition_draft());

insert into pg_temp.media_test_state (key, payload)
values (
  'orphan_upload',
  pg_temp.make_ready_media_for(
    'orphan_draft',
    'eventually-orphaned.jpg',
    'image/jpeg',
    4000,
    400,
    500,
    repeat('d', 64)
  )
);

insert into pg_temp.media_test_state (key, payload)
select
  'orphan_bundle',
  public.admin_attach_exhibition_media(
    draft.payload ->> 'id',
    (draft.payload ->> 'working_version_id')::uuid,
    (draft.payload ->> 'revision')::integer,
    (upload.payload ->> 'asset_id')::uuid,
    'gallery'
  )
from pg_temp.media_test_state as draft
cross join pg_temp.media_test_state as upload
where draft.key = 'orphan_draft'
  and upload.key = 'orphan_upload';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'orphan_draft'
  and bundle.key = 'orphan_bundle';

update pg_temp.media_test_state as bundle
set payload = public.admin_detach_exhibition_media(
  draft.payload ->> 'id',
  (draft.payload ->> 'working_version_id')::uuid,
  (draft.payload ->> 'revision')::integer,
  (upload.payload ->> 'asset_id')::uuid
)
from pg_temp.media_test_state as draft,
  pg_temp.media_test_state as upload
where bundle.key = 'orphan_bundle'
  and draft.key = 'orphan_draft'
  and upload.key = 'orphan_upload';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'orphan_draft'
  and bundle.key = 'orphan_bundle';

select ok(
  (
    select (payload ->> 'revision')::integer = 3
    from pg_temp.media_test_state
    where key = 'orphan_draft'
  )
  and (
    select jsonb_array_length(payload -> 'media') = 0
    from pg_temp.media_test_state
    where key = 'orphan_bundle'
  )
  and exists (
    select 1
    from content.media_assets as asset
    where asset.id = (
      select (payload ->> 'asset_id')::uuid
      from pg_temp.media_test_state
      where key = 'orphan_upload'
    )
      and asset.status = 'orphaned'::content.media_asset_status
      and asset.purged_at is null
  ),
  'detaching a final ready reference increments once and marks it orphaned'
);

reset role;

select ok(
  exists (
    select 1
    from content.outbox_events as event
    join pg_temp.media_test_state as upload on upload.key = 'orphan_upload'
    where event.event_type = 'media.cleanup_requested'
      and event.aggregate_id = upload.payload ->> 'asset_id'
      and event.deduplication_key = format(
        'media:%s:cleanup_requested',
        upload.payload ->> 'asset_id'
      )
      and event.payload ->> 'asset_id' = upload.payload ->> 'asset_id'
      and event.payload ->> 'source_bucket_id' = 'exhibition-media'
      and event.payload ->> 'source_object_path' = upload.payload ->> 'object_path'
      and event.payload ->> 'delivery_bucket_id' = 'exhibition-images'
      and event.payload ->> 'delivery_object_path' = format(
        'cms/%s/original.jpg',
        upload.payload ->> 'asset_id'
      )
  ),
  'final-reference detach enqueues a deduplicated cleanup contract with both paths'
);

select is(
  (
    select count(*)
    from storage.objects as object
    where object.bucket_id = 'exhibition-media'
      and object.name = (
        select payload ->> 'object_path'
        from pg_temp.media_test_state
        where key = 'orphan_upload'
      )
  ),
  1::bigint,
  'detach never deletes Storage bytes inside the database transaction'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

select throws_ok(
  $$
    select public.admin_attach_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'orphan_draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'orphan_draft'
      ),
      3,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'orphan_upload'
      ),
      'gallery'
    )
  $$,
  '22023',
  'media_asset_is_not_attachable',
  'orphaned is a terminal state for admin attachment commands'
);

select ok(
  exists (
    select 1
    from content.audit_log
    where action = 'exhibition.media_detached'
      and actor_user_id = '00000000-0000-0000-0000-000000000302'::uuid
      and entity_id = (
        select payload ->> 'id'
        from pg_temp.media_test_state
        where key = 'orphan_draft'
      )
      and metadata ->> 'orphaned' = 'true'
  ),
  'orphaning detach records actor, version, asset, and orphan outcome'
);

insert into pg_temp.media_test_state (key, payload)
values ('rejected_draft', public.admin_create_exhibition_draft());

insert into pg_temp.media_test_state (key, payload)
values (
  'rejected_upload',
  pg_temp.make_ready_media_for(
    'rejected_draft',
    'rejected.webp',
    'image/webp',
    5000,
    500,
    600,
    repeat('e', 64)
  )
);

insert into pg_temp.media_test_state (key, payload)
select
  'rejected_bundle',
  public.admin_attach_exhibition_media(
    draft.payload ->> 'id',
    (draft.payload ->> 'working_version_id')::uuid,
    (draft.payload ->> 'revision')::integer,
    (upload.payload ->> 'asset_id')::uuid,
    'gallery'
  )
from pg_temp.media_test_state as draft
cross join pg_temp.media_test_state as upload
where draft.key = 'rejected_draft'
  and upload.key = 'rejected_upload';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'rejected_draft'
  and bundle.key = 'rejected_bundle';

reset role;
select public.outbox_reject_media(
  (select (payload ->> 'asset_id')::uuid from pg_temp.media_test_state where key = 'rejected_upload'),
  'exhibition-media',
  (select payload ->> 'object_path' from pg_temp.media_test_state where key = 'rejected_upload'),
  'mime_sniff_failed',
  'test rejection'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"00000000-0000-0000-0000-000000000302","role":"authenticated"}',
  true
);

select is(
  (
    select listed.value ->> 'status'
    from public.admin_list_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'rejected_draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'rejected_draft'
      )
    ) as listed(value)
    where listed.value ->> 'asset_id' = (
      select payload ->> 'asset_id'
      from pg_temp.media_test_state
      where key = 'rejected_upload'
    )
  ),
  'rejected',
  'the admin media DTO surfaces worker rejection explicitly'
);

select throws_ok(
  $$
    select public.admin_attach_exhibition_media(
      (select payload ->> 'id' from pg_temp.media_test_state where key = 'rejected_draft'),
      (
        select (payload ->> 'working_version_id')::uuid
        from pg_temp.media_test_state
        where key = 'rejected_draft'
      ),
      2,
      (
        select (payload ->> 'asset_id')::uuid
        from pg_temp.media_test_state
        where key = 'rejected_upload'
      ),
      'gallery'
    )
  $$,
  '22023',
  'media_asset_is_not_attachable',
  'rejected media cannot be reattached or promoted by admin commands'
);

update pg_temp.media_test_state as bundle
set payload = public.admin_detach_exhibition_media(
  draft.payload ->> 'id',
  (draft.payload ->> 'working_version_id')::uuid,
  (draft.payload ->> 'revision')::integer,
  (upload.payload ->> 'asset_id')::uuid
)
from pg_temp.media_test_state as draft,
  pg_temp.media_test_state as upload
where bundle.key = 'rejected_bundle'
  and draft.key = 'rejected_draft'
  and upload.key = 'rejected_upload';

update pg_temp.media_test_state as draft
set payload = bundle.payload -> 'exhibition'
from pg_temp.media_test_state as bundle
where draft.key = 'rejected_draft'
  and bundle.key = 'rejected_bundle';

select ok(
  (
    select (payload ->> 'revision')::integer = 3
    from pg_temp.media_test_state
    where key = 'rejected_draft'
  )
  and (
    select jsonb_array_length(payload -> 'media') = 0
    from pg_temp.media_test_state
    where key = 'rejected_bundle'
  )
  and exists (
    select 1
    from content.media_assets as asset
    where asset.id = (
      select (payload ->> 'asset_id')::uuid
      from pg_temp.media_test_state
      where key = 'rejected_upload'
    )
      and asset.status = 'orphaned'::content.media_asset_status
      and asset.purged_at is null
  ),
  'detaching the final rejected attachment transitions it to orphaned'
);

reset role;

select ok(
  exists (
    select 1
    from content.outbox_events as event
    join pg_temp.media_test_state as upload on upload.key = 'rejected_upload'
    where event.event_type = 'media.cleanup_requested'
      and event.aggregate_id = upload.payload ->> 'asset_id'
      and event.deduplication_key = format(
        'media:%s:cleanup_requested',
        upload.payload ->> 'asset_id'
      )
      and event.payload ->> 'asset_id' = upload.payload ->> 'asset_id'
      and event.payload ->> 'source_bucket_id' = 'exhibition-media'
      and event.payload ->> 'source_object_path' = upload.payload ->> 'object_path'
      and not event.payload ? 'delivery_bucket_id'
      and not event.payload ? 'delivery_object_path'
  ),
  'detached rejected media enqueues source cleanup without a nonexistent delivery object'
);

select * from finish();
rollback;
