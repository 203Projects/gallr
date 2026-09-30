-- Authenticated exhibition intake is independent of gallery management access.
-- Public-form review history and all existing owner/editor contracts are retained.
alter table content.exhibition_submissions
  add column if not exists submitter_user_id uuid references auth.users(id) on delete set null;
create index if not exists exhibition_submissions_submitter_idx
  on content.exhibition_submissions(submitter_user_id, submitted_at desc)
  where submitter_user_id is not null;

create table if not exists content.artist_registrations (
  request_id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  submission_id uuid not null unique default gen_random_uuid(),
  asset_id uuid not null unique default gen_random_uuid(),
  object_path text not null unique,
  mime_type text not null check (mime_type in ('image/jpeg','image/png')),
  byte_size bigint not null check (byte_size between 1 and 10485760),
  filename text not null check (length(filename) between 1 and 255),
  fingerprint jsonb,
  response jsonb,
  created_at timestamptz not null default now(),
  finalized_at timestamptz,
  constraint artist_registration_completion check ((finalized_at is null) = (response is null))
);
alter table content.artist_registrations enable row level security;
create index if not exists artist_registrations_user_created_idx
  on content.artist_registrations(user_id,created_at desc);
revoke all on content.artist_registrations from public, anon, authenticated, service_role;

create or replace function content_private.artist_assert_verified()
returns uuid language plpgsql stable security definer set search_path = '' as $$
declare v_user uuid := auth.uid();
begin
  if v_user is null or not exists (
    select 1 from auth.users u where u.id=v_user and u.email_confirmed_at is not null
      and not coalesce(u.is_anonymous,false)
  ) then
    raise exception using errcode='42501',message='artist_verified_account_required';
  end if;
  return v_user;
end;
$$;
revoke all on function content_private.artist_assert_verified() from public, anon, authenticated, service_role;

create or replace function content_private.artist_reserve_registration_impl(
  p_request_id uuid, p_mime_type text, p_byte_size bigint, p_filename text
)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare
  v_user uuid := content_private.artist_assert_verified();
  v_slot content.artist_registrations%rowtype;
  v_submission uuid := gen_random_uuid();
  v_asset uuid := gen_random_uuid();
begin
  if p_request_id is null or p_mime_type is null or p_mime_type not in ('image/jpeg','image/png')
    or p_byte_size is null or p_byte_size not between 1 and 10485760
    or p_filename is null or length(btrim(p_filename)) not between 1 and 255 then
    raise exception using errcode='22023',message='artist_poster_invalid';
  end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('artist_registration:'||v_user::text,0));
  select * into v_slot from content.artist_registrations where request_id=p_request_id for update;
  if found then
    if v_slot.user_id<>v_user then
      raise exception using errcode='42501',message='artist_registration_access_denied';
    end if;
    if v_slot.mime_type<>p_mime_type or v_slot.byte_size<>p_byte_size or v_slot.filename<>btrim(p_filename) then
      raise exception using errcode='22023',message='artist_request_conflict';
    end if;
    if v_slot.finalized_at is null and v_slot.created_at<=now()-interval '1 day' then
      raise exception using errcode='22023',message='artist_registration_expired';
    end if;
  else
    if (select count(*) from content.artist_registrations where user_id=v_user and created_at>now()-interval '1 hour')>=3
      or (select count(*) from content.artist_registrations where user_id=v_user and created_at>now()-interval '1 day')>=10 then
      raise exception using errcode='P0001',message='artist_registration_rate_limited';
    end if;
    insert into content.artist_registrations(request_id,user_id,submission_id,asset_id,object_path,mime_type,byte_size,filename)
    values(p_request_id,v_user,v_submission,v_asset,
      format('submissions/%s/%s/original.%s',v_submission,v_asset,case p_mime_type when 'image/png' then 'png' else 'jpg' end),
      p_mime_type,p_byte_size,btrim(p_filename)) returning * into v_slot;
  end if;
  return jsonb_build_object('request_id',v_slot.request_id,'object_path',v_slot.object_path,'bucket_id','exhibition-media',
    'finalized',v_slot.finalized_at is not null);
end;
$$;

-- Storage policies call this boolean helper rather than granting access to the
-- reservation table. The exact reserved path is the only writable object.
create or replace function content_private.artist_can_upload(p_path text)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists(select 1 from content.artist_registrations r join auth.users u on u.id=r.user_id
    where r.user_id=auth.uid() and r.object_path=p_path and r.finalized_at is null
      and r.created_at>now()-interval '1 day' and u.email_confirmed_at is not null and not coalesce(u.is_anonymous,false));
$$;
revoke all on function content_private.artist_can_upload(text) from public,anon,authenticated,service_role;
grant execute on function content_private.artist_can_upload(text) to authenticated;
drop policy if exists "artists upload reserved cover" on storage.objects;
create policy "artists upload reserved cover" on storage.objects for insert to authenticated
with check (bucket_id='exhibition-media' and content_private.artist_can_upload(name));

create or replace function content_private.artist_submit_registration_impl(
  p_request_id uuid,p_payload jsonb,p_name text,p_relationship text,p_email text
)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare
  v_user uuid := content_private.artist_assert_verified();
  v_slot content.artist_registrations%rowtype;
  v_fingerprint jsonb;
  v_email text := lower(btrim(p_email));
  v_payload jsonb;
  v_result jsonb;
  v_key text;
begin
  select * into v_slot from content.artist_registrations where request_id=p_request_id for update;
  if not found or v_slot.user_id<>v_user then
    raise exception using errcode='42501',message='artist_registration_access_denied';
  end if;
  if p_payload is null or jsonb_typeof(p_payload)<>'object' or pg_column_size(p_payload)>30000
    or p_name is null or length(btrim(p_name)) not between 1 and 200
    or p_relationship is null or p_relationship not in ('artist','curator','organizer','gallery')
    or v_email is null or length(v_email)>254 or v_email !~ '^[^[:space:]@<>]+@[^[:space:]@<>]+\.[^[:space:]@<>]+$' then
    raise exception using errcode='22023',message='artist_details_invalid';
  end if;
  foreach v_key in array array['name_ko','artists','venue_name_ko','address_ko','hours','description_ko','opening_date','closing_date'] loop
    if jsonb_typeof(p_payload->v_key) is distinct from 'string' or nullif(btrim(p_payload->>v_key),'') is null then
      raise exception using errcode='22023',message='artist_details_invalid';
    end if;
  end loop;
  if length(p_payload->>'artists')>1000 or length(p_payload->>'description_ko')>18000
    or (p_payload->>'opening_date') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
    or (p_payload->>'closing_date') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then
    raise exception using errcode='22023',message='artist_details_invalid';
  end if;
  begin
    if (p_payload->>'closing_date')::date < (p_payload->>'opening_date')::date then
      raise exception using errcode='22023',message='artist_details_invalid';
    end if;
  exception when datetime_field_overflow or invalid_datetime_format then
    raise exception using errcode='22023',message='artist_details_invalid';
  end;
  -- Unknown fields cannot grant roles or alter publication/venue authority.
  v_payload := jsonb_build_object('name_ko',btrim(p_payload->>'name_ko'),'artists',btrim(p_payload->>'artists'),
    'venue_name_ko',btrim(p_payload->>'venue_name_ko'),'address_ko',btrim(p_payload->>'address_ko'),
    'hours',btrim(p_payload->>'hours'),'description_ko',btrim(p_payload->>'description_ko'),
    'opening_date',p_payload->>'opening_date','closing_date',p_payload->>'closing_date');
  v_fingerprint := jsonb_build_object('payload',v_payload,'name',btrim(p_name),'relationship',p_relationship,'email',v_email);
  if v_slot.finalized_at is not null then
    if v_slot.fingerprint<>v_fingerprint then
      raise exception using errcode='22023',message='artist_request_conflict';
    end if;
    return v_slot.response;
  end if;
  if v_slot.created_at<=now()-interval '1 day' then
    raise exception using errcode='22023',message='artist_registration_expired';
  end if;
  if not exists(select 1 from storage.objects o where o.bucket_id='exhibition-media'
    and o.name=v_slot.object_path and o.owner_id=v_user::text) then
    raise exception using errcode='22023',message='artist_poster_missing';
  end if;
  -- Account reservation limits prevent varying contact addresses from bypassing
  -- intake limits. The legacy helper additionally meters an opaque account hash.
  v_result := content_private.create_exhibition_submission_impl(v_slot.submission_id,v_email,
    v_payload || jsonb_build_object('description_ko','참여 작가: '||(v_payload->>'artists')||E'\n\n'||(v_payload->>'description_ko')),
    encode(extensions.digest(v_user::text,'sha256'),'hex'),null,
    jsonb_build_array(jsonb_build_object('asset_id',v_slot.asset_id,'object_path',v_slot.object_path,
      'mime_type',v_slot.mime_type,'byte_size',v_slot.byte_size,'original_filename',v_slot.filename)));
  update content.exhibition_submissions set submitter_user_id=v_user,submitter_name=btrim(p_name),submitter_email=v_email,
    payload=payload||jsonb_build_object('artists',v_payload->>'artists','submitter_relationship',p_relationship)
    where id=v_slot.submission_id;
  update content.artist_registrations set fingerprint=v_fingerprint,response=v_result,finalized_at=now() where request_id=p_request_id;
  insert into content.audit_log(actor_user_id,action,entity_type,entity_id,metadata)
    values(v_user,'artist_exhibition.submitted','exhibition_submission',v_slot.submission_id::text,jsonb_build_object('request_id',p_request_id));
  return v_result;
end;
$$;

create or replace function content_private.artist_list_registrations_impl()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_user uuid := content_private.artist_assert_verified();
begin
  return coalesce((select jsonb_agg(record.item order by record.submitted_at desc,record.id) from (
    select s.id,s.submitted_at,jsonb_build_object('id',s.id,'status',s.status::text,'payload',s.payload,
      'review_notes',coalesce(s.review_notes,''),'submitted_at',s.submitted_at,
      'published',coalesce(e.published_version_id is not null and e.archived_at is null,false),
      'exhibition_id',s.accepted_exhibition_id) as item
    from content.exhibition_submissions s left join content.exhibitions e on e.id=s.accepted_exhibition_id
    where s.submitter_user_id=v_user order by s.submitted_at desc,s.id limit 20
  ) record),'[]'::jsonb);
end;
$$;

create or replace function public.artist_reserve_registration(p_request_id uuid,p_mime_type text,p_byte_size bigint,p_filename text)
returns jsonb language sql volatile security invoker set search_path = '' as $$
  select content_private.artist_reserve_registration_impl(p_request_id,p_mime_type,p_byte_size,p_filename);
$$;
create or replace function public.artist_submit_registration(p_request_id uuid,p_payload jsonb,p_name text,p_relationship text,p_email text)
returns jsonb language sql volatile security invoker set search_path = '' as $$
  select content_private.artist_submit_registration_impl(p_request_id,p_payload,p_name,p_relationship,p_email);
$$;
create or replace function public.artist_list_registrations()
returns jsonb language sql stable security invoker set search_path = '' as $$
  select content_private.artist_list_registrations_impl();
$$;
revoke all on function content_private.artist_reserve_registration_impl(uuid,text,bigint,text),
  content_private.artist_submit_registration_impl(uuid,jsonb,text,text,text),content_private.artist_list_registrations_impl(),
  public.artist_reserve_registration(uuid,text,bigint,text),public.artist_submit_registration(uuid,jsonb,text,text,text),public.artist_list_registrations()
  from public,anon,authenticated,service_role;
grant execute on function content_private.artist_reserve_registration_impl(uuid,text,bigint,text),
  content_private.artist_submit_registration_impl(uuid,jsonb,text,text,text),content_private.artist_list_registrations_impl(),
  public.artist_reserve_registration(uuid,text,bigint,text),public.artist_submit_registration(uuid,jsonb,text,text,text),public.artist_list_registrations()
  to authenticated;
