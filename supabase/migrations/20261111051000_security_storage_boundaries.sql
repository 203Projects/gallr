-- Security audit 2026-10-22, findings #11 and #12 (split from PR #372).
--
-- #11: the gallery-owner INSERT policy on exhibition-media checked only the
-- owner-drafts/<uid>/ prefix, so any authenticated account could write any
-- number of objects there without a reservation, a membership or an editable
-- draft. An upload must now match a pending reservation made by
-- owner_reserve_cover_upload for a draft the caller may still edit.
--
-- #12: the avatars bucket accepted any <uid>.* name, any MIME type and any
-- size. Both apps upload JPEG to exactly <uid>.jpg (cropped to at most
-- 512 x 512 at quality 80), so the bucket now takes only that object, as
-- image/jpeg, up to 5 MiB. Existing objects stay publicly delivered.
begin;

-- Mirrors owner_assert_exhibition_draft at upload time: the caller's current
-- owner membership, the exhibition in that gallery, not hidden, editable
-- (draft or needs_changes), a draft version, and a pending claimant only for
-- drafts they created. Reservations carry no expiry field, so their age comes
-- from the server-owned created_at; the gallery workspace uploads straight
-- after reserving. Paths outside owner-drafts/<uid>/ return false before the
-- membership lookup, so this policy never raises into another INSERT policy
-- (staff media, individual submissions, avatars).
create or replace function content_private.owner_reserved_media_upload_allowed(
  p_bucket text,
  p_path text
)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := (select auth.uid());
  v_membership content.gallery_memberships%rowtype;
begin
  if v_user_id is null
     or p_bucket is distinct from 'exhibition-media'
     or split_part(p_path, '/', 1) <> 'owner-drafts'
     or split_part(p_path, '/', 2) <> v_user_id::text then
    return false;
  end if;

  begin
    v_membership := content_private.owner_assert_gallery_membership_record(false);
  exception when insufficient_privilege then
    return false;
  end;

  return exists (
    select 1
    from content.media_assets as asset
    join content.exhibition_versions as version
      on version.id::text = asset.metadata ->> 'draft_version_id'
    join content.exhibitions as exhibition
      on exhibition.id = version.exhibition_id
    where asset.bucket_id = p_bucket
      and asset.object_path = p_path
      and asset.status = 'pending_upload'::content.media_asset_status
      and asset.uploaded_by = v_user_id
      and asset.metadata ->> 'owner_user_id' = v_user_id::text
      and asset.metadata ->> 'exhibition_id' = exhibition.id
      and asset.created_at > now() - interval '1 hour'
      and version.status = 'draft'::content.exhibition_version_status
      and exhibition.gallery_id = v_membership.gallery_id
      and exhibition.owner_hidden_at is null
      and exhibition.owner_status in (
        'draft'::content.owner_exhibition_status,
        'needs_changes'::content.owner_exhibition_status
      )
      and (
        v_membership.status = 'active'::content.gallery_membership_status
        or exhibition.created_by = v_membership.user_id
      )
  );
end;
$$;

revoke all on function content_private.owner_reserved_media_upload_allowed(text, text)
  from public, anon, authenticated, service_role;
grant execute on function content_private.owner_reserved_media_upload_allowed(text, text)
  to authenticated;

drop policy if exists "gallery owners upload draft media" on storage.objects;
create policy "gallery owners upload draft media"
on storage.objects for insert to authenticated
with check (
  content_private.owner_reserved_media_upload_allowed(bucket_id, name)
);

update storage.buckets
set
  file_size_limit = 5242880,
  allowed_mime_types = array['image/jpeg']
where id = 'avatars';

-- The apps upsert, which needs INSERT, SELECT and UPDATE on the same name.
drop policy if exists "Owner upload avatars" on storage.objects;
create policy "Owner upload avatars"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'avatars'
  and name = (select auth.uid())::text || '.jpg'
);

drop policy if exists "Owner read avatars" on storage.objects;
create policy "Owner read avatars"
on storage.objects for select to authenticated
using (
  bucket_id = 'avatars'
  and name = (select auth.uid())::text || '.jpg'
);

-- WITH CHECK stops an UPDATE from renaming the object to another name.
drop policy if exists "Owner update avatars" on storage.objects;
create policy "Owner update avatars"
on storage.objects for update to authenticated
using (
  bucket_id = 'avatars'
  and name = (select auth.uid())::text || '.jpg'
)
with check (
  bucket_id = 'avatars'
  and name = (select auth.uid())::text || '.jpg'
);

commit;
