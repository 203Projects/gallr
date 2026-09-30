-- Verified individuals upload one private image to an exact reserved path.
-- Source bytes remain private; staff acceptance attaches the existing asset to a draft.
create index if not exists media_assets_individual_reservation_quota_idx
  on content.media_assets ((metadata->>'individual_user_id'),created_at desc)
  where metadata ? 'individual_user_id';

create or replace function content_private.individual_verified_actor()
returns uuid language plpgsql stable security definer set search_path = ''
as $$
declare v_actor uuid := auth.uid();
begin
  if v_actor is null or not exists (select 1 from auth.users where id=v_actor
    and email_confirmed_at is not null and email is not null and not coalesce(is_anonymous,false)) then
    raise exception using errcode='42501',message='verified_email_required';
  end if;
  return v_actor;
end;
$$;
revoke all on function content_private.individual_verified_actor() from public,anon,authenticated,service_role;

create or replace function content_private.reserve_individual_exhibition_image_impl(
  p_request_id uuid,p_mime_type text,p_byte_size bigint,p_original_filename text
) returns jsonb language plpgsql volatile security definer set search_path = ''
as $$
declare
  v_actor uuid := content_private.individual_verified_actor();
  v_asset uuid := extensions.gen_random_uuid();
  v_submission uuid := extensions.gen_random_uuid();
  v_extension text;
  v_path text;
  v_fingerprint text;
  v_replay record;
  v_response jsonb;
  v_expires timestamptz := clock_timestamp()+interval '1 hour';
begin
  v_extension := case p_mime_type when 'image/jpeg' then 'jpg' when 'image/png' then 'png' end;
  if v_extension is null or p_byte_size is null or p_byte_size not between 1 and 5242880
    or nullif(btrim(p_original_filename),'') is null or length(p_original_filename)>255 then
    raise exception using errcode='22023',message='individual_image_invalid';
  end if;
  v_fingerprint := content_private.command_request_fingerprint(jsonb_build_object(
    'mime_type',p_mime_type,'byte_size',p_byte_size,'original_filename',p_original_filename));
  select * into v_replay from content_private.begin_command_request(
    v_actor,p_request_id,'individual.reserve_image',v_fingerprint);
  if v_replay.is_replay then return v_replay.stored_response; end if;
  -- Serialize the reservation quota for one identity, including distinct request UUIDs.
  perform pg_advisory_xact_lock(hashtextextended('individual-image:'||v_actor::text,0));
  if (select count(*) from content.media_assets where metadata->>'individual_user_id'=v_actor::text and created_at>now()-interval '1 hour')>=10 then
    raise exception using errcode='P0001',message='individual_image_rate_limited';
  end if;
  v_path := format('submissions/%s/%s/original.%s',v_submission,v_asset,v_extension);
  insert into content.media_assets(id,status,bucket_id,object_path,mime_type,byte_size,uploaded_by,metadata)
  values(v_asset,'pending_upload','exhibition-media',v_path,p_mime_type,p_byte_size,v_actor,
    jsonb_build_object('original_filename',btrim(p_original_filename),'submission_id',v_submission,
      'individual_user_id',v_actor,'expires_at',v_expires));
  v_response := jsonb_build_object('asset_id',v_asset,'bucket_id','exhibition-media',
    'object_path',v_path,'mime_type',p_mime_type,'byte_size',p_byte_size,'expires_at',v_expires);
  return content_private.complete_command_request(v_actor,p_request_id,'individual.reserve_image',v_fingerprint,v_response);
end;
$$;
revoke all on function content_private.reserve_individual_exhibition_image_impl(uuid,text,bigint,text) from public,anon,authenticated,service_role;
grant execute on function content_private.reserve_individual_exhibition_image_impl(uuid,text,bigint,text) to authenticated;
create or replace function public.reserve_individual_exhibition_image(p_request_id uuid,p_mime_type text,p_byte_size bigint,p_original_filename text)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.reserve_individual_exhibition_image_impl(p_request_id,p_mime_type,p_byte_size,p_original_filename); $$;
revoke all on function public.reserve_individual_exhibition_image(uuid,text,bigint,text) from public,anon,authenticated,service_role;
grant execute on function public.reserve_individual_exhibition_image(uuid,text,bigint,text) to authenticated;

create or replace function content_private.individual_image_upload_allowed(p_bucket text,p_path text)
returns boolean language sql stable security definer set search_path = ''
as $$
  select p_bucket='exhibition-media' and exists (
    select 1 from content.media_assets as asset join auth.users as actor on actor.id=asset.uploaded_by
    where asset.bucket_id=p_bucket and asset.object_path=p_path and asset.uploaded_by=auth.uid()
      and asset.metadata->>'individual_user_id'=auth.uid()::text and asset.status='pending_upload'
      and (asset.metadata->>'expires_at')::timestamptz>now()
      and actor.email_confirmed_at is not null and not coalesce(actor.is_anonymous,false)
  );
$$;
revoke all on function content_private.individual_image_upload_allowed(text,text) from public,anon,authenticated,service_role;
grant execute on function content_private.individual_image_upload_allowed(text,text) to authenticated;
drop policy if exists "verified individuals upload reserved image" on storage.objects;
create policy "verified individuals upload reserved image" on storage.objects for insert to authenticated
with check(content_private.individual_image_upload_allowed(bucket_id,name));
-- No individual SELECT, UPDATE, DELETE, upsert, or public-read policy.

create or replace function content_private.submit_individual_exhibition_impl(
  p_payload jsonb, p_request_id uuid
) returns jsonb
language plpgsql volatile security definer set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_email text;
  v_fingerprint text;
  v_replay record;
  v_response jsonb;
  v_submission_id uuid;
  v_asset content.media_assets%rowtype;
  v_media jsonb;
begin
  select email into v_email from auth.users
  where id = v_actor and email_confirmed_at is not null
    and not coalesce(is_anonymous, false);
  if v_actor is null or v_email is null then
    raise exception using errcode = '42501', message = 'verified_email_required';
  end if;
  if p_payload is null or jsonb_typeof(p_payload) <> 'object'
     or octet_length(p_payload::text) > 65536 then
    raise exception using errcode = '22023', message = 'individual_submission_payload_invalid';
  end if;
  if exists (
    select 1 from jsonb_each(p_payload) as field
    where field.key not in ('name_ko','venue_name_ko','address_ko','hours',
                           'opening_date','closing_date','description_ko','image_asset_id')
       or jsonb_typeof(field.value) <> 'string'
  ) or length(coalesce(p_payload->>'description_ko','')) > 5000
     or coalesce(p_payload->>'opening_date','') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
     or coalesce(p_payload->>'closing_date','') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then
    raise exception using errcode = '22023', message = 'individual_submission_payload_invalid';
  end if;
  v_fingerprint := content_private.command_request_fingerprint(p_payload);
  select * into v_replay from content_private.begin_command_request(
    v_actor, p_request_id, 'individual.submit_exhibition', v_fingerprint
  );
  if v_replay.is_replay then return v_replay.stored_response; end if;
  if coalesce(p_payload->>'image_asset_id','') !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' then
    raise exception using errcode='22023',message='individual_image_required';
  end if;
  select * into v_asset from content.media_assets where id=(p_payload->>'image_asset_id')::uuid
    and uploaded_by=v_actor and metadata->>'individual_user_id'=v_actor::text
    and status='pending_upload' for update;
  if not found then raise exception using errcode='42501',message='individual_image_reservation_invalid'; end if;
  if (v_asset.metadata->>'expires_at')::timestamptz<=clock_timestamp() then
    raise exception using errcode='22023',message='individual_image_reservation_expired';
  end if;
  v_submission_id := (v_asset.metadata->>'submission_id')::uuid;
  v_media := jsonb_build_array(jsonb_build_object('asset_id',v_asset.id,'object_path',v_asset.object_path,
    'mime_type',v_asset.mime_type,'byte_size',v_asset.byte_size,'original_filename',v_asset.metadata->>'original_filename'));
  -- Intake owns ready-asset creation. The reservation is replaced atomically; a failure rolls back the deletion.
  delete from content.media_assets where id=v_asset.id;
  v_response := content_private.create_exhibition_submission_impl(
    v_submission_id, v_email, p_payload - 'image_asset_id',
    encode(extensions.digest(convert_to('individual:' || v_actor::text, 'UTF8'), 'sha256'), 'hex'),
    'verified-individual-form', v_media
  );
  update content.media_assets set uploaded_by=v_actor, metadata=metadata || jsonb_build_object('individual_user_id',v_actor) where id=v_asset.id;
  update content.exhibition_submissions
    set submitter_user_id = v_actor where id = v_submission_id;
  insert into content.audit_log(actor_user_id,action,entity_type,entity_id,metadata)
    values (v_actor,'individual.exhibition_submitted','exhibition_submission',v_submission_id::text,'{}');
  return content_private.complete_command_request(
    v_actor,p_request_id,'individual.submit_exhibition',v_fingerprint,v_response
  );
end;
$$;
revoke all on function content_private.submit_individual_exhibition_impl(jsonb,uuid)
  from public,anon,authenticated,service_role;
grant execute on function content_private.submit_individual_exhibition_impl(jsonb,uuid) to authenticated;

create or replace function public.submit_individual_exhibition(p_payload jsonb,p_request_id uuid)
returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.submit_individual_exhibition_impl(p_payload,p_request_id); $$;
revoke all on function public.submit_individual_exhibition(jsonb,uuid)
  from public,anon,authenticated,service_role;
grant execute on function public.submit_individual_exhibition(jsonb,uuid) to authenticated;
