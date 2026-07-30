-- Canonical gallery-submission intake and staff review commands.
--
-- The public browser talks only to the submit-exhibition Edge Function. The
-- Edge Function uploads immutable private objects, then calls the service-role
-- intake RPC. Staff review uses authenticated SECURITY INVOKER wrappers around
-- role-checked private implementations. Acceptance creates a draft and never
-- publishes or changes a reader.

create or replace function content_private.assert_exhibition_submission_rate_limit(
  p_submitter_email text,
  p_source_ip_hash text
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_email text := lower(btrim(p_submitter_email));
begin
  if v_email is null
     or length(v_email) > 320
     or v_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then
    raise exception using errcode = '22023', message = 'submitter_email_invalid';
  end if;
  if p_source_ip_hash is null
     or p_source_ip_hash !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'source_ip_hash_invalid';
  end if;

  -- One shared transaction lock makes the global/email/IP counts exact even
  -- when several Edge invocations arrive concurrently.
  perform pg_advisory_xact_lock(
    hashtextextended('exhibition-submission-rate-limit', 0)
  );
  if (
    select count(*) >= 40
    from content.exhibition_submissions
    where created_at >= now() - interval '1 hour'
  ) then
    raise exception using errcode = 'P0001', message = 'submission_rate_limited';
  end if;
  if (
    select count(*) >= 3
    from content.exhibition_submissions
    where lower(submitter_email) = v_email
      and created_at >= now() - interval '1 hour'
  ) then
    raise exception using errcode = 'P0001', message = 'submission_rate_limited';
  end if;
  if (
    select count(*) >= 10
    from content.exhibition_submissions
    where source_ip_hash = p_source_ip_hash
      and created_at >= now() - interval '1 hour'
  ) then
    raise exception using errcode = 'P0001', message = 'submission_rate_limited';
  end if;
end;
$$;

revoke all on function content_private.assert_exhibition_submission_rate_limit(
  text, text
) from public, anon, authenticated, service_role;
grant execute on function content_private.assert_exhibition_submission_rate_limit(
  text, text
) to service_role;

create or replace function public.check_exhibition_submission_rate_limit(
  p_submitter_email text,
  p_source_ip_hash text
)
returns void
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.assert_exhibition_submission_rate_limit(
    p_submitter_email,
    p_source_ip_hash
  );
$$;

revoke all on function public.check_exhibition_submission_rate_limit(text, text)
  from public, anon, authenticated, service_role;
grant execute on function public.check_exhibition_submission_rate_limit(text, text)
  to service_role;

create or replace function content_private.create_exhibition_submission_impl(
  p_submission_id uuid,
  p_submitter_email text,
  p_payload jsonb,
  p_source_ip_hash text,
  p_user_agent text,
  p_media jsonb default '[]'::jsonb
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_email text := lower(btrim(p_submitter_email));
  v_payload jsonb;
  v_media_item jsonb;
  v_asset_id uuid;
  v_object_path text;
  v_mime_type text;
  v_byte_size bigint;
  v_original_filename text;
  v_extension text;
  v_expected_path text;
  v_sort_order integer := 0;
  v_object_metadata jsonb;
begin
  if p_submission_id is null then
    raise exception using errcode = '22023', message = 'submission_id_required';
  end if;
  if v_email is null
     or length(v_email) > 320
     or v_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then
    raise exception using errcode = '22023', message = 'submitter_email_invalid';
  end if;
  if p_source_ip_hash is null
     or p_source_ip_hash !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'source_ip_hash_invalid';
  end if;
  if p_user_agent is not null and length(p_user_agent) > 512 then
    raise exception using errcode = '22023', message = 'user_agent_too_long';
  end if;
  if p_payload is null or jsonb_typeof(p_payload) <> 'object' then
    raise exception using errcode = '22023', message = 'submission_payload_invalid';
  end if;
  if p_media is null
     or jsonb_typeof(p_media) <> 'array'
     or jsonb_array_length(p_media) > 5 then
    raise exception using errcode = '22023', message = 'submission_media_invalid';
  end if;

  if nullif(btrim(p_payload ->> 'name_ko'), '') is null
     or length(p_payload ->> 'name_ko') > 300
     or nullif(btrim(p_payload ->> 'venue_name_ko'), '') is null
     or length(p_payload ->> 'venue_name_ko') > 300
     or nullif(btrim(p_payload ->> 'opening_date'), '') is null
     or nullif(btrim(p_payload ->> 'closing_date'), '') is null
     or nullif(btrim(p_payload ->> 'address_ko'), '') is null
     or length(p_payload ->> 'address_ko') > 500
     or nullif(btrim(p_payload ->> 'hours'), '') is null
     or length(p_payload ->> 'hours') > 1000 then
    raise exception using errcode = '22023', message = 'submission_required_field_invalid';
  end if;
  if (p_payload ->> 'opening_date')::date
     > (p_payload ->> 'closing_date')::date then
    raise exception using errcode = '22023', message = 'submission_date_order_invalid';
  end if;

  -- Normalize and whitelist the durable payload. Submitter contact remains in
  -- the dedicated private column and is never copied into public exhibition
  -- contact fields.
  v_payload := jsonb_build_object(
    'name_ko', btrim(p_payload ->> 'name_ko'),
    'name_en', btrim(coalesce(p_payload ->> 'name_en', '')),
    'venue_name_ko', btrim(p_payload ->> 'venue_name_ko'),
    'venue_name_en', btrim(coalesce(p_payload ->> 'venue_name_en', '')),
    'opening_date', p_payload ->> 'opening_date',
    'closing_date', p_payload ->> 'closing_date',
    'address_ko', btrim(p_payload ->> 'address_ko'),
    'address_en', btrim(coalesce(p_payload ->> 'address_en', '')),
    'hours', btrim(p_payload ->> 'hours'),
    'description_ko', btrim(coalesce(p_payload ->> 'description_ko', '')),
    'description_en', btrim(coalesce(p_payload ->> 'description_en', '')),
    'reception_date', btrim(coalesce(p_payload ->> 'reception_date', '')),
    'reception_end', btrim(coalesce(p_payload ->> 'reception_end', ''))
  );

  if length(v_payload ->> 'name_en') > 300
     or length(v_payload ->> 'venue_name_en') > 300
     or length(v_payload ->> 'address_en') > 500
     or length(v_payload ->> 'description_ko') > 20000
     or length(v_payload ->> 'description_en') > 20000
     or length(v_payload ->> 'reception_date') > 32
     or length(v_payload ->> 'reception_end') > 32 then
    raise exception using errcode = '22023', message = 'submission_optional_field_invalid';
  end if;
  if v_payload ->> 'reception_date' <> '' then
    if (v_payload ->> 'reception_date')
         !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9]$' then
      raise exception using errcode = '22023', message = 'submission_reception_invalid';
    end if;
    begin
      perform (v_payload ->> 'reception_date')::timestamp;
    exception when datetime_field_overflow or invalid_datetime_format then
      raise exception using errcode = '22023', message = 'submission_reception_invalid';
    end;
  end if;
  if v_payload ->> 'reception_end' <> '' then
    if v_payload ->> 'reception_date' = ''
       or (v_payload ->> 'reception_end')
            !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9]$' then
      raise exception using errcode = '22023', message = 'submission_reception_invalid';
    end if;
    begin
      if (v_payload ->> 'reception_end')::timestamp
         < (v_payload ->> 'reception_date')::timestamp then
        raise exception using errcode = '22023', message = 'submission_reception_invalid';
      end if;
    exception when datetime_field_overflow or invalid_datetime_format then
      raise exception using errcode = '22023', message = 'submission_reception_invalid';
    end;
  end if;

  perform content_private.assert_exhibition_submission_rate_limit(
    v_email,
    p_source_ip_hash
  );

  insert into content.exhibition_submissions (
    id,
    status,
    submitter_email,
    payload,
    source_ip_hash,
    user_agent,
    submitted_at
  )
  values (
    p_submission_id,
    'submitted'::content.submission_status,
    v_email,
    v_payload,
    p_source_ip_hash,
    nullif(left(p_user_agent, 512), ''),
    now()
  );

  for v_media_item in
    select value from jsonb_array_elements(p_media)
  loop
    begin
      v_asset_id := (v_media_item ->> 'asset_id')::uuid;
      v_byte_size := (v_media_item ->> 'byte_size')::bigint;
    exception when others then
      raise exception using errcode = '22023', message = 'submission_media_invalid';
    end;
    v_object_path := v_media_item ->> 'object_path';
    v_mime_type := v_media_item ->> 'mime_type';
    v_original_filename := btrim(v_media_item ->> 'original_filename');
    v_extension := case v_mime_type
      when 'image/jpeg' then 'jpg'
      when 'image/png' then 'png'
      else null
    end;
    if v_extension is null
       or v_byte_size < 1
       or v_byte_size > 10485760
       or v_original_filename is null
       or length(v_original_filename) < 1
       or length(v_original_filename) > 255 then
      raise exception using errcode = '22023', message = 'submission_media_invalid';
    end if;
    v_expected_path := format(
      'submissions/%s/%s/original.%s',
      p_submission_id,
      v_asset_id,
      v_extension
    );
    if v_object_path is distinct from v_expected_path then
      raise exception using errcode = '22023', message = 'submission_media_path_invalid';
    end if;

    select object.metadata
    into v_object_metadata
    from storage.objects as object
    where object.bucket_id = 'exhibition-media'
      and object.name = v_object_path;
    if not found then
      raise exception using errcode = 'P0002', message = 'submission_media_object_not_found';
    end if;
    if coalesce(
         v_object_metadata ->> 'mimetype',
         v_object_metadata ->> 'contentType',
         v_object_metadata ->> 'content-type'
       ) is distinct from v_mime_type then
      raise exception using errcode = '22023', message = 'submission_media_mime_mismatch';
    end if;
    if coalesce(
         v_object_metadata ->> 'size',
         v_object_metadata ->> 'contentLength',
         v_object_metadata ->> 'content-length'
       ) is distinct from v_byte_size::text then
      raise exception using errcode = '22023', message = 'submission_media_size_mismatch';
    end if;

    insert into content.media_assets (
      id,
      status,
      bucket_id,
      object_path,
      mime_type,
      byte_size,
      metadata
    )
    values (
      v_asset_id,
      'ready'::content.media_asset_status,
      'exhibition-media',
      v_object_path,
      v_mime_type,
      v_byte_size,
      jsonb_build_object(
        'original_filename', v_original_filename,
        'submission_id', p_submission_id
      )
    );
    insert into content.submission_media (
      submission_id,
      media_id,
      sort_order
    )
    values (p_submission_id, v_asset_id, v_sort_order);
    v_sort_order := v_sort_order + 1;
  end loop;

  return jsonb_build_object(
    'submission_id', p_submission_id,
    'status', 'submitted',
    'submitted_at', (
      select submitted_at
      from content.exhibition_submissions
      where id = p_submission_id
    )
  );
end;
$$;

revoke all on function content_private.create_exhibition_submission_impl(
  uuid, text, jsonb, text, text, jsonb
) from public, anon, authenticated, service_role;
grant execute on function content_private.create_exhibition_submission_impl(
  uuid, text, jsonb, text, text, jsonb
) to service_role;

create or replace function public.create_exhibition_submission(
  p_submission_id uuid,
  p_submitter_email text,
  p_payload jsonb,
  p_source_ip_hash text,
  p_user_agent text,
  p_media jsonb default '[]'::jsonb
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.create_exhibition_submission_impl(
    p_submission_id,
    p_submitter_email,
    p_payload,
    p_source_ip_hash,
    p_user_agent,
    p_media
  );
$$;

revoke all on function public.create_exhibition_submission(
  uuid, text, jsonb, text, text, jsonb
) from public, anon, authenticated, service_role;
grant execute on function public.create_exhibition_submission(
  uuid, text, jsonb, text, text, jsonb
) to service_role;

create or replace function content_private.admin_submission_json(
  p_submission_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', submission.id,
    'status', submission.status::text,
    'submitter_email', coalesce(submission.submitter_email, ''),
    'payload', submission.payload,
    'accepted_exhibition_id', submission.accepted_exhibition_id,
    'review_notes', coalesce(submission.review_notes, ''),
    'submitted_at', submission.submitted_at,
    'reviewed_at', submission.reviewed_at,
    'created_at', submission.created_at,
    'media', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'asset_id', asset.id,
            'bucket_id', asset.bucket_id,
            'object_path', asset.object_path,
            'mime_type', coalesce(asset.mime_type, ''),
            'byte_size', asset.byte_size,
            'original_filename', coalesce(
              asset.metadata ->> 'original_filename',
              ''
            )
          )
          order by attachment.sort_order, asset.id
        )
        from content.submission_media as attachment
        join content.media_assets as asset on asset.id = attachment.media_id
        where attachment.submission_id = submission.id
      ),
      '[]'::jsonb
    )
  )
  from content.exhibition_submissions as submission
  where submission.id = p_submission_id;
$$;

revoke all on function content_private.admin_submission_json(uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_list_exhibition_submissions_impl(
  p_search text default '',
  p_status text default null
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_search text := lower(btrim(coalesce(p_search, '')));
begin
  perform content_private.admin_assert_staff(
    'contributor'::content.staff_role
  );
  if p_status is not null
     and p_status not in (
       'pending_upload',
       'submitted',
       'in_review',
       'accepted',
       'rejected',
       'withdrawn'
     ) then
    raise exception using errcode = '22023', message = 'submission_status_invalid';
  end if;

  return query
  select content_private.admin_submission_json(submission.id)
  from content.exhibition_submissions as submission
  where (p_status is null or submission.status::text = p_status)
    and (
      v_search = ''
      or lower(coalesce(submission.submitter_email, '')) like '%' || v_search || '%'
      or lower(coalesce(submission.payload ->> 'name_ko', '')) like '%' || v_search || '%'
      or lower(coalesce(submission.payload ->> 'name_en', '')) like '%' || v_search || '%'
      or lower(coalesce(submission.payload ->> 'venue_name_ko', '')) like '%' || v_search || '%'
      or lower(coalesce(submission.payload ->> 'venue_name_en', '')) like '%' || v_search || '%'
    )
  order by submission.submitted_at desc nulls last, submission.created_at desc;
end;
$$;

revoke all on function content_private.admin_list_exhibition_submissions_impl(
  text, text
) from public, anon, authenticated, service_role;
grant execute on function content_private.admin_list_exhibition_submissions_impl(
  text, text
) to authenticated;

create or replace function content_private.admin_start_submission_review_impl(
  p_submission_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_submission content.exhibition_submissions%rowtype;
begin
  v_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );
  select submission.*
  into v_submission
  from content.exhibition_submissions as submission
  where submission.id = p_submission_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'submission_not_found';
  end if;
  if v_submission.status = 'submitted'::content.submission_status then
    update content.exhibition_submissions
    set
      status = 'in_review'::content.submission_status,
      reviewed_by = v_user_id
    where id = p_submission_id;
  elsif v_submission.status <> 'in_review'::content.submission_status then
    raise exception using errcode = '22023', message = 'submission_not_reviewable';
  end if;
  return content_private.admin_submission_json(p_submission_id);
end;
$$;

revoke all on function content_private.admin_start_submission_review_impl(uuid)
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_start_submission_review_impl(uuid)
  to authenticated;

create or replace function content_private.admin_accept_submission_impl(
  p_submission_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_submission content.exhibition_submissions%rowtype;
  v_exhibition_id text := gen_random_uuid()::text;
  v_version_id uuid;
  v_attachment record;
  v_extension text;
  v_delivery_path text;
begin
  v_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );
  select submission.*
  into v_submission
  from content.exhibition_submissions as submission
  where submission.id = p_submission_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'submission_not_found';
  end if;
  if v_submission.status not in (
    'submitted'::content.submission_status,
    'in_review'::content.submission_status
  ) then
    raise exception using errcode = '22023', message = 'submission_not_acceptable';
  end if;

  insert into content.exhibitions (id, created_by, updated_by)
  values (v_exhibition_id, v_user_id, v_user_id);

  insert into content.exhibition_versions (
    exhibition_id,
    version_number,
    revision,
    status,
    name_ko,
    name_en,
    venue_name_ko,
    venue_name_en,
    address_ko,
    address_en,
    opening_date,
    closing_date,
    description_ko,
    description_en,
    hours,
    reception_date,
    opening_time,
    created_by,
    updated_by
  )
  values (
    v_exhibition_id,
    1,
    1,
    'draft'::content.exhibition_version_status,
    v_submission.payload ->> 'name_ko',
    v_submission.payload ->> 'name_en',
    v_submission.payload ->> 'venue_name_ko',
    v_submission.payload ->> 'venue_name_en',
    v_submission.payload ->> 'address_ko',
    v_submission.payload ->> 'address_en',
    (v_submission.payload ->> 'opening_date')::date,
    (v_submission.payload ->> 'closing_date')::date,
    v_submission.payload ->> 'description_ko',
    v_submission.payload ->> 'description_en',
    nullif(v_submission.payload ->> 'hours', ''),
    case
      when nullif(v_submission.payload ->> 'reception_date', '') is null then null
      else (
        (v_submission.payload ->> 'reception_date')::timestamp
        at time zone 'Asia/Seoul'
      )
    end,
    case
      when nullif(v_submission.payload ->> 'reception_date', '') is null then null
      else to_char(
        (v_submission.payload ->> 'reception_date')::timestamp,
        'HH24:MI'
      )
    end,
    v_user_id,
    v_user_id
  )
  returning id into v_version_id;

  for v_attachment in
    select
      attachment.sort_order,
      asset.*
    from content.submission_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.submission_id = p_submission_id
    order by attachment.sort_order, asset.id
    for update of asset
  loop
    v_extension := case v_attachment.mime_type
      when 'image/jpeg' then 'jpg'
      when 'image/png' then 'png'
      else null
    end;
    if v_extension is null
       or v_attachment.status <> 'ready'::content.media_asset_status then
      raise exception using errcode = '22023', message = 'submission_media_not_attachable';
    end if;
    v_delivery_path := format(
      'cms/%s/original.%s',
      v_attachment.id,
      v_extension
    );
    update content.media_assets
    set
      uploaded_by = v_user_id,
      delivery_bucket_id = 'exhibition-images',
      delivery_object_path = v_delivery_path,
      metadata = metadata || jsonb_build_object(
        'exhibition_id', v_exhibition_id,
        'draft_version_id', v_version_id
      )
    where id = v_attachment.id;

    insert into content.exhibition_version_media (
      version_id,
      media_id,
      role,
      sort_order,
      created_by
    )
    values (
      v_version_id,
      v_attachment.id,
      case
        when v_attachment.sort_order = 0
          then 'cover'::content.media_role
        else 'gallery'::content.media_role
      end,
      case
        when v_attachment.sort_order = 0 then 0
        else v_attachment.sort_order
      end,
      v_user_id
    );

    insert into content.outbox_events (
      aggregate_type,
      aggregate_id,
      event_type,
      payload,
      deduplication_key
    )
    values (
      'media_asset',
      v_attachment.id::text,
      'media.publish_requested',
      jsonb_build_object(
        'asset_id', v_attachment.id,
        'source_bucket_id', v_attachment.bucket_id,
        'source_object_path', v_attachment.object_path,
        'delivery_bucket_id', 'exhibition-images',
        'delivery_object_path', v_delivery_path
      ),
      format('media:%s:publish_requested', v_attachment.id)
    )
    on conflict (deduplication_key) do nothing;
  end loop;

  update content.exhibition_submissions
  set
    status = 'accepted'::content.submission_status,
    accepted_exhibition_id = v_exhibition_id,
    reviewed_by = v_user_id,
    reviewed_at = now()
  where id = p_submission_id;

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'submission.accepted',
    'exhibition_submission',
    p_submission_id::text,
    jsonb_build_object(
      'exhibition_id', v_exhibition_id,
      'version_id', v_version_id
    )
  );

  return jsonb_build_object(
    'submission', content_private.admin_submission_json(p_submission_id),
    'exhibition', content_private.admin_exhibition_json(
      v_exhibition_id,
      v_version_id
    )
  );
end;
$$;

revoke all on function content_private.admin_accept_submission_impl(uuid)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_accept_submission_idempotent_impl(
  p_submission_id uuid,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored_response jsonb;
  v_response jsonb;
begin
  v_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );
  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('submission_id', p_submission_id)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored_response
  from content_private.begin_command_request(
    v_user_id,
    p_request_id,
    'admin_accept_exhibition_submission',
    v_fingerprint
  ) as request;
  if v_is_replay then
    return v_stored_response;
  end if;
  perform set_config('app.command_request_id', p_request_id::text, true);
  perform set_config('app.command_actor_id', v_user_id::text, true);
  v_response := content_private.admin_accept_submission_impl(p_submission_id);
  perform set_config('app.command_request_id', '', true);
  perform set_config('app.command_actor_id', '', true);
  return content_private.complete_command_request(
    v_user_id,
    p_request_id,
    'admin_accept_exhibition_submission',
    v_fingerprint,
    v_response
  );
end;
$$;

revoke all on function content_private.admin_accept_submission_idempotent_impl(
  uuid, uuid
) from public, anon, authenticated, service_role;
grant execute on function content_private.admin_accept_submission_idempotent_impl(
  uuid, uuid
) to authenticated;

create or replace function content_private.admin_reject_submission_impl(
  p_submission_id uuid,
  p_review_notes text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_status content.submission_status;
begin
  v_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );
  if p_review_notes is null
     or length(btrim(p_review_notes)) < 1
     or length(p_review_notes) > 2000 then
    raise exception using errcode = '22023', message = 'review_notes_required';
  end if;
  select status
  into v_status
  from content.exhibition_submissions
  where id = p_submission_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'submission_not_found';
  end if;
  if v_status not in (
    'submitted'::content.submission_status,
    'in_review'::content.submission_status
  ) then
    raise exception using errcode = '22023', message = 'submission_not_rejectable';
  end if;
  update content.exhibition_submissions
  set
    status = 'rejected'::content.submission_status,
    reviewed_by = v_user_id,
    review_notes = btrim(p_review_notes),
    reviewed_at = now()
  where id = p_submission_id;
  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    metadata
  )
  values (
    v_user_id,
    'submission.rejected',
    'exhibition_submission',
    p_submission_id::text,
    jsonb_build_object('review_notes', btrim(p_review_notes))
  );
  return content_private.admin_submission_json(p_submission_id);
end;
$$;

revoke all on function content_private.admin_reject_submission_impl(uuid, text)
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_reject_submission_idempotent_impl(
  p_submission_id uuid,
  p_review_notes text,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored_response jsonb;
  v_response jsonb;
begin
  v_user_id := content_private.admin_assert_staff(
    'publisher'::content.staff_role
  );
  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object(
      'submission_id', p_submission_id,
      'review_notes', btrim(p_review_notes)
    )
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored_response
  from content_private.begin_command_request(
    v_user_id,
    p_request_id,
    'admin_reject_exhibition_submission',
    v_fingerprint
  ) as request;
  if v_is_replay then
    return v_stored_response;
  end if;
  perform set_config('app.command_request_id', p_request_id::text, true);
  perform set_config('app.command_actor_id', v_user_id::text, true);
  v_response := content_private.admin_reject_submission_impl(
    p_submission_id,
    p_review_notes
  );
  perform set_config('app.command_request_id', '', true);
  perform set_config('app.command_actor_id', '', true);
  return content_private.complete_command_request(
    v_user_id,
    p_request_id,
    'admin_reject_exhibition_submission',
    v_fingerprint,
    v_response
  );
end;
$$;

revoke all on function content_private.admin_reject_submission_idempotent_impl(
  uuid, text, uuid
) from public, anon, authenticated, service_role;
grant execute on function content_private.admin_reject_submission_idempotent_impl(
  uuid, text, uuid
) to authenticated;

create or replace function public.admin_list_exhibition_submissions(
  p_search text default '',
  p_status text default null
)
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select *
  from content_private.admin_list_exhibition_submissions_impl(
    p_search,
    p_status
  );
$$;

create or replace function public.admin_start_exhibition_submission_review(
  p_submission_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_start_submission_review_impl(p_submission_id);
$$;

create or replace function public.admin_accept_exhibition_submission(
  p_submission_id uuid,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_accept_submission_idempotent_impl(
    p_submission_id,
    p_request_id
  );
$$;

create or replace function public.admin_reject_exhibition_submission(
  p_submission_id uuid,
  p_review_notes text,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_reject_submission_idempotent_impl(
    p_submission_id,
    p_review_notes,
    p_request_id
  );
$$;

revoke all on function public.admin_list_exhibition_submissions(text, text)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_start_exhibition_submission_review(uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_accept_exhibition_submission(uuid, uuid)
  from public, anon, authenticated, service_role;
revoke all on function public.admin_reject_exhibition_submission(uuid, text, uuid)
  from public, anon, authenticated, service_role;

grant execute on function public.admin_list_exhibition_submissions(text, text)
  to authenticated;
grant execute on function public.admin_start_exhibition_submission_review(uuid)
  to authenticated;
grant execute on function public.admin_accept_exhibition_submission(uuid, uuid)
  to authenticated;
grant execute on function public.admin_reject_exhibition_submission(uuid, text, uuid)
  to authenticated;
