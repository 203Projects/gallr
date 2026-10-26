-- Audit #11/#12: bind uploads to reservations and the app's canonical avatar.
begin;

create or replace function content_private.owner_reserved_media_upload_allowed(p_bucket text, p_path text)
returns boolean
language plpgsql stable security definer set search_path = ''
as $$
declare
  v_member content.gallery_memberships%rowtype;
begin
  if p_bucket <> 'exhibition-media' or auth.uid() is null then return false; end if;
  begin
    v_member := content_private.owner_assert_gallery_membership_record(false);
  exception when insufficient_privilege then return false;
  end;
  return exists (
    select 1 from content.media_assets as asset
    join content.exhibition_versions as version
      on version.id::text = asset.metadata->>'draft_version_id'
    join content.exhibitions as exhibition
      on exhibition.id = version.exhibition_id
    where asset.bucket_id = p_bucket and asset.object_path = p_path
      and asset.uploaded_by = auth.uid()
      and asset.metadata->>'owner_user_id' = auth.uid()::text
      and asset.metadata->>'exhibition_id' = exhibition.id
      and asset.status = 'pending_upload'
      and asset.created_at > now() - interval '1 hour'
      and version.status = 'draft'
      and exhibition.gallery_id = v_member.gallery_id
      and exhibition.owner_hidden_at is null
      and exhibition.owner_status in ('draft', 'needs_changes')
      and (v_member.status = 'active' or exhibition.created_by = v_member.user_id)
  );
end;
$$;
revoke all on function content_private.owner_reserved_media_upload_allowed(text,text)
  from public, anon, authenticated, service_role;
grant execute on function content_private.owner_reserved_media_upload_allowed(text,text) to authenticated;
drop policy if exists "gallery owners upload draft media" on storage.objects;
create policy "gallery owners upload draft media" on storage.objects for insert to authenticated
with check (content_private.owner_reserved_media_upload_allowed(bucket_id, name));

-- Both platforms upload JPEG to exactly <user-id>.jpg. Existing public URLs
-- remain readable, but alternate suffixes cannot mint unlimited new objects.
update storage.buckets set file_size_limit = 5242880,
  allowed_mime_types = array['image/jpeg'] where id = 'avatars';
drop policy if exists "Owner upload avatars" on storage.objects;
drop policy if exists "Owner update avatars" on storage.objects;
drop policy if exists "Owner read avatars" on storage.objects;
create policy "Owner upload avatars" on storage.objects for insert to authenticated
with check (bucket_id = 'avatars' and name = (select auth.uid())::text || '.jpg');
create policy "Owner read avatars" on storage.objects for select to authenticated
using (bucket_id = 'avatars' and name = (select auth.uid())::text || '.jpg');
create policy "Owner update avatars" on storage.objects for update to authenticated
using (bucket_id = 'avatars' and name = (select auth.uid())::text || '.jpg')
with check (bucket_id = 'avatars' and name = (select auth.uid())::text || '.jpg');

commit;
