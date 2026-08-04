-- Gallery-owner identity and claim foundation.
--
-- This migration is additive. Public readers continue to resolve only the
-- published exhibition pointer; gallery ownership is a stable identity link
-- and never replaces versioned venue snapshots.

do $$
begin
  create type content.gallery_status as enum (
    'pending',
    'active',
    'merged',
    'disabled'
  );
exception
  when duplicate_object then null;
end
$$;

do $$
begin
  create type content.gallery_member_role as enum ('owner');
exception
  when duplicate_object then null;
end
$$;

do $$
begin
  create type content.gallery_membership_status as enum (
    'pending',
    'active',
    'rejected',
    'suspended',
    'revoked'
  );
exception
  when duplicate_object then null;
end
$$;

create table if not exists content.galleries (
  id uuid primary key default gen_random_uuid(),
  canonical_venue_id uuid references content.venues(id) on delete set null,
  name_ko text not null,
  name_en text not null default '',
  status content.gallery_status not null default 'pending',
  merged_into_gallery_id uuid references content.galleries(id) on delete restrict,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint galleries_name_ko_not_blank check (
    length(btrim(name_ko)) between 1 and 300
  ),
  constraint galleries_name_en_length check (length(name_en) <= 300),
  constraint galleries_merge_target check (
    (
      status = 'merged'::content.gallery_status
      and merged_into_gallery_id is not null
      and merged_into_gallery_id <> id
    )
    or (
      status <> 'merged'::content.gallery_status
      and merged_into_gallery_id is null
    )
  )
);

create table if not exists content.gallery_memberships (
  gallery_id uuid not null
    references content.galleries(id) on delete restrict,
  user_id uuid not null references auth.users(id) on delete cascade,
  role content.gallery_member_role not null default 'owner',
  status content.gallery_membership_status not null default 'pending',
  claim_website_url text,
  claim_social_url text,
  claim_note text,
  reviewed_at timestamptz,
  reviewed_by uuid references auth.users(id) on delete set null,
  review_notes text,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  primary key (gallery_id, user_id),
  constraint gallery_memberships_website_url check (
    claim_website_url is null
    or (
      length(claim_website_url) <= 2048
      and claim_website_url ~* '^https?://[^[:space:]]+$'
    )
  ),
  constraint gallery_memberships_social_url check (
    claim_social_url is null
    or (
      length(claim_social_url) <= 2048
      and claim_social_url ~* '^https?://[^[:space:]]+$'
    )
  ),
  constraint gallery_memberships_claim_note_length check (
    claim_note is null or length(claim_note) <= 2000
  ),
  constraint gallery_memberships_review_notes_length check (
    review_notes is null or length(review_notes) <= 4000
  ),
  constraint gallery_memberships_claim_evidence check (
    nullif(btrim(claim_website_url), '') is not null
    or nullif(btrim(claim_social_url), '') is not null
    or nullif(btrim(claim_note), '') is not null
  )
);

alter table content.exhibitions
  add column if not exists gallery_id uuid;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'exhibitions_gallery_fk'
      and conrelid = 'content.exhibitions'::regclass
  ) then
    alter table content.exhibitions
      add constraint exhibitions_gallery_fk
      foreign key (gallery_id)
      references content.galleries(id)
      on delete restrict;
  end if;
end
$$;

create index if not exists galleries_canonical_venue_idx
  on content.galleries (canonical_venue_id)
  where canonical_venue_id is not null;
create index if not exists galleries_name_ko_lower_idx
  on content.galleries (lower(name_ko) text_pattern_ops)
  where status = 'active'::content.gallery_status;
create index if not exists galleries_name_en_lower_idx
  on content.galleries (lower(name_en) text_pattern_ops)
  where status = 'active'::content.gallery_status
    and name_en <> '';
create index if not exists gallery_memberships_user_idx
  on content.gallery_memberships (user_id, updated_at desc);
create unique index if not exists gallery_memberships_one_active_owner_idx
  on content.gallery_memberships (gallery_id)
  where status = 'active'::content.gallery_membership_status;
create unique index if not exists gallery_memberships_one_workspace_per_user_idx
  on content.gallery_memberships (user_id)
  where status in (
    'pending'::content.gallery_membership_status,
    'active'::content.gallery_membership_status
  );
create index if not exists exhibitions_gallery_idx
  on content.exhibitions (gallery_id)
  where gallery_id is not null;

drop trigger if exists galleries_set_updated_at on content.galleries;
create trigger galleries_set_updated_at
  before update on content.galleries
  for each row execute function content_private.set_updated_at();

drop trigger if exists gallery_memberships_set_updated_at
  on content.gallery_memberships;
create trigger gallery_memberships_set_updated_at
  before update on content.gallery_memberships
  for each row execute function content_private.set_updated_at();

alter table content.galleries enable row level security;
alter table content.gallery_memberships enable row level security;

revoke all on table content.galleries
  from public, anon, authenticated, service_role;
revoke all on table content.gallery_memberships
  from public, anon, authenticated, service_role;

comment on table content.galleries is
  'Durable customer organization. Locations remain content.venues; published versions retain venue snapshots.';
comment on table content.gallery_memberships is
  'Owner authorization and claim evidence. Browser access is command-only.';
comment on column content.exhibitions.gallery_id is
  'Stable owner organization link; nullable for unclaimed legacy/staff records.';

create or replace function content_private.owner_assert_authenticated()
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := (select auth.uid());
begin
  if v_user_id is null then
    raise exception using
      errcode = '42501',
      message = 'authentication_required';
  end if;

  if not exists (
    select 1
    from auth.users as auth_user
    where auth_user.id = v_user_id
      and auth_user.email_confirmed_at is not null
  ) then
    raise exception using
      errcode = '42501',
      message = 'confirmed_email_required';
  end if;

  return v_user_id;
end;
$$;

create or replace function content_private.owner_access_json(
  p_user_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'membership', jsonb_build_object(
      'role', membership.role,
      'status', membership.status,
      'created_at', membership.created_at,
      'updated_at', membership.updated_at
    ),
    'gallery', jsonb_build_object(
      'id', gallery.id,
      'name_ko', gallery.name_ko,
      'name_en', gallery.name_en,
      'status', gallery.status,
      'address_ko', coalesce(venue.address_ko, ''),
      'address_en', coalesce(venue.address_en, '')
    )
  )
  from content.gallery_memberships as membership
  join content.galleries as gallery on gallery.id = membership.gallery_id
  left join content.venues as venue on venue.id = gallery.canonical_venue_id
  where membership.user_id = p_user_id
  order by
    case membership.status
      when 'active'::content.gallery_membership_status then 1
      when 'pending'::content.gallery_membership_status then 2
      when 'suspended'::content.gallery_membership_status then 3
      when 'rejected'::content.gallery_membership_status then 4
      when 'revoked'::content.gallery_membership_status then 5
    end,
    membership.updated_at desc,
    membership.gallery_id
  limit 1;
$$;

create or replace function content_private.owner_normalize_evidence(
  p_website_url text,
  p_social_url text,
  p_claim_note text
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_website_url text := nullif(btrim(p_website_url), '');
  v_social_url text := nullif(btrim(p_social_url), '');
  v_claim_note text := nullif(btrim(p_claim_note), '');
begin
  if v_website_url is null
     and v_social_url is null
     and v_claim_note is null then
    raise exception using
      errcode = '22023',
      message = 'gallery_claim_evidence_required';
  end if;
  if v_website_url is not null
     and (
       length(v_website_url) > 2048
       or v_website_url !~* '^https?://[^[:space:]]+$'
     ) then
    raise exception using
      errcode = '22023',
      message = 'gallery_claim_website_invalid';
  end if;
  if v_social_url is not null
     and (
       length(v_social_url) > 2048
       or v_social_url !~* '^https?://[^[:space:]]+$'
     ) then
    raise exception using
      errcode = '22023',
      message = 'gallery_claim_social_invalid';
  end if;
  if v_claim_note is not null and length(v_claim_note) > 2000 then
    raise exception using
      errcode = '22023',
      message = 'gallery_claim_note_invalid';
  end if;

  return jsonb_build_object(
    'website_url', v_website_url,
    'social_url', v_social_url,
    'claim_note', v_claim_note
  );
end;
$$;

create or replace function content_private.owner_current_access_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.owner_assert_authenticated();
begin
  return content_private.owner_access_json(v_user_id);
end;
$$;

create or replace function content_private.owner_search_galleries_impl(
  p_query text
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_query text := lower(btrim(p_query));
begin
  perform content_private.owner_assert_authenticated();

  if length(v_query) < 2 or length(v_query) > 100 then
    raise exception using
      errcode = '22023',
      message = 'gallery_search_query_invalid';
  end if;

  return query
  select jsonb_build_object(
    'gallery_id', gallery.id,
    'name_ko', gallery.name_ko,
    'name_en', gallery.name_en,
    'address_ko', coalesce(venue.address_ko, ''),
    'address_en', coalesce(venue.address_en, ''),
    'is_claimed', exists (
      select 1
      from content.gallery_memberships as active_membership
      where active_membership.gallery_id = gallery.id
        and active_membership.status =
          'active'::content.gallery_membership_status
    )
  )
  from content.galleries as gallery
  left join content.venues as venue on venue.id = gallery.canonical_venue_id
  where gallery.status = 'active'::content.gallery_status
    and (
      lower(gallery.name_ko) like v_query || '%'
      or lower(gallery.name_en) like v_query || '%'
    )
  order by lower(gallery.name_ko), gallery.id
  limit 20;
end;
$$;

create or replace function content_private.owner_claim_existing_gallery_impl(
  p_gallery_id uuid,
  p_website_url text,
  p_social_url text,
  p_claim_note text,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_evidence jsonb := content_private.owner_normalize_evidence(
    p_website_url,
    p_social_url,
    p_claim_note
  );
  v_fingerprint text;
  v_prior_fingerprint text;
begin
  if p_gallery_id is null then
    raise exception using errcode = '22023', message = 'gallery_id_required';
  end if;
  if p_request_id is null then
    raise exception using errcode = '22023', message = 'request_id_required';
  end if;

  v_fingerprint := md5(
    jsonb_build_object(
      'gallery_id', p_gallery_id,
      'evidence', v_evidence
    )::text
  );

  perform pg_advisory_xact_lock(
    hashtextextended('gallery-owner-claim:' || v_user_id::text, 0)
  );

  select audit.metadata ->> 'request_fingerprint'
  into v_prior_fingerprint
  from content.audit_log as audit
  where audit.actor_user_id = v_user_id
    and audit.action = 'gallery.claim_requested'
    and audit.request_id = p_request_id
  order by audit.occurred_at desc, audit.id
  limit 1;

  if found then
    if v_prior_fingerprint is distinct from v_fingerprint then
      raise exception using errcode = '22023', message = 'idempotency_conflict';
    end if;
    return content_private.owner_access_json(v_user_id);
  end if;

  if exists (
    select 1
    from content.gallery_memberships as membership
    where membership.user_id = v_user_id
      and membership.status in (
        'pending'::content.gallery_membership_status,
        'active'::content.gallery_membership_status,
        'suspended'::content.gallery_membership_status
      )
  ) then
    raise exception using
      errcode = '23505',
      message = 'owner_workspace_already_exists';
  end if;

  perform 1
  from content.galleries as gallery
  where gallery.id = p_gallery_id
    and gallery.status = 'active'::content.gallery_status
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'gallery_not_available';
  end if;

  if exists (
    select 1
    from content.gallery_memberships as membership
    where membership.gallery_id = p_gallery_id
      and membership.status = 'active'::content.gallery_membership_status
  ) then
    raise exception using errcode = '23505', message = 'gallery_already_claimed';
  end if;

  insert into content.gallery_memberships (
    gallery_id,
    user_id,
    role,
    status,
    claim_website_url,
    claim_social_url,
    claim_note,
    created_by,
    updated_by
  )
  values (
    p_gallery_id,
    v_user_id,
    'owner'::content.gallery_member_role,
    'pending'::content.gallery_membership_status,
    v_evidence ->> 'website_url',
    v_evidence ->> 'social_url',
    v_evidence ->> 'claim_note',
    v_user_id,
    v_user_id
  )
  on conflict (gallery_id, user_id) do update
  set
    status = 'pending'::content.gallery_membership_status,
    claim_website_url = excluded.claim_website_url,
    claim_social_url = excluded.claim_social_url,
    claim_note = excluded.claim_note,
    reviewed_at = null,
    reviewed_by = null,
    review_notes = null,
    updated_by = v_user_id
  where content.gallery_memberships.status in (
    'rejected'::content.gallery_membership_status,
    'revoked'::content.gallery_membership_status
  );

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    request_id,
    metadata
  )
  values (
    v_user_id,
    'gallery.claim_requested',
    'gallery',
    p_gallery_id::text,
    p_request_id,
    jsonb_build_object(
      'gallery_id', p_gallery_id,
      'request_fingerprint', v_fingerprint
    )
  );

  insert into content.outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    deduplication_key
  )
  values (
    'gallery',
    p_gallery_id::text,
    'gallery.claim_requested',
    jsonb_build_object(
      'gallery_id', p_gallery_id,
      'user_id', v_user_id,
      'status', 'pending'
    ),
    format(
      'gallery.claim_requested:%s:%s',
      v_user_id,
      p_request_id
    )
  );

  return content_private.owner_access_json(v_user_id);
end;
$$;

create or replace function content_private.owner_create_gallery_claim_impl(
  p_name_ko text,
  p_name_en text,
  p_website_url text,
  p_social_url text,
  p_claim_note text,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_name_ko text := btrim(p_name_ko);
  v_name_en text := btrim(coalesce(p_name_en, ''));
  v_evidence jsonb := content_private.owner_normalize_evidence(
    p_website_url,
    p_social_url,
    p_claim_note
  );
  v_fingerprint text;
  v_prior_fingerprint text;
  v_gallery_id uuid;
begin
  if v_name_ko is null
     or length(v_name_ko) < 1
     or length(v_name_ko) > 300
     or length(v_name_en) > 300 then
    raise exception using errcode = '22023', message = 'gallery_name_invalid';
  end if;
  if p_request_id is null then
    raise exception using errcode = '22023', message = 'request_id_required';
  end if;

  v_fingerprint := md5(
    jsonb_build_object(
      'name_ko', v_name_ko,
      'name_en', v_name_en,
      'evidence', v_evidence
    )::text
  );

  perform pg_advisory_xact_lock(
    hashtextextended('gallery-owner-claim:' || v_user_id::text, 0)
  );

  select
    (audit.metadata ->> 'gallery_id')::uuid,
    audit.metadata ->> 'request_fingerprint'
  into v_gallery_id, v_prior_fingerprint
  from content.audit_log as audit
  where audit.actor_user_id = v_user_id
    and audit.action = 'gallery.created_and_claimed'
    and audit.request_id = p_request_id
  order by audit.occurred_at desc, audit.id
  limit 1;

  if found then
    if v_prior_fingerprint is distinct from v_fingerprint then
      raise exception using errcode = '22023', message = 'idempotency_conflict';
    end if;
    return content_private.owner_access_json(v_user_id);
  end if;

  if exists (
    select 1
    from content.gallery_memberships as membership
    where membership.user_id = v_user_id
      and membership.status in (
        'pending'::content.gallery_membership_status,
        'active'::content.gallery_membership_status,
        'suspended'::content.gallery_membership_status
      )
  ) then
    raise exception using
      errcode = '23505',
      message = 'owner_workspace_already_exists';
  end if;

  insert into content.galleries (
    name_ko,
    name_en,
    status,
    created_by,
    updated_by
  )
  values (
    v_name_ko,
    v_name_en,
    'pending'::content.gallery_status,
    v_user_id,
    v_user_id
  )
  returning id into v_gallery_id;

  insert into content.gallery_memberships (
    gallery_id,
    user_id,
    role,
    status,
    claim_website_url,
    claim_social_url,
    claim_note,
    created_by,
    updated_by
  )
  values (
    v_gallery_id,
    v_user_id,
    'owner'::content.gallery_member_role,
    'pending'::content.gallery_membership_status,
    v_evidence ->> 'website_url',
    v_evidence ->> 'social_url',
    v_evidence ->> 'claim_note',
    v_user_id,
    v_user_id
  );

  insert into content.audit_log (
    actor_user_id,
    action,
    entity_type,
    entity_id,
    request_id,
    metadata
  )
  values (
    v_user_id,
    'gallery.created_and_claimed',
    'gallery',
    v_gallery_id::text,
    p_request_id,
    jsonb_build_object(
      'gallery_id', v_gallery_id,
      'request_fingerprint', v_fingerprint
    )
  );

  insert into content.outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    deduplication_key
  )
  values (
    'gallery',
    v_gallery_id::text,
    'gallery.created_and_claimed',
    jsonb_build_object(
      'gallery_id', v_gallery_id,
      'user_id', v_user_id,
      'status', 'pending'
    ),
    format(
      'gallery.created_and_claimed:%s:%s',
      v_user_id,
      p_request_id
    )
  );

  return content_private.owner_access_json(v_user_id);
end;
$$;

revoke all on function content_private.owner_assert_authenticated()
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_access_json(uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_normalize_evidence(text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_current_access_impl()
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_search_galleries_impl(text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.owner_claim_existing_gallery_impl(
  uuid, text, text, text, uuid
) from public, anon, authenticated, service_role;
revoke all on function content_private.owner_create_gallery_claim_impl(
  text, text, text, text, text, uuid
) from public, anon, authenticated, service_role;

grant execute on function content_private.owner_current_access_impl()
  to authenticated;
grant execute on function content_private.owner_search_galleries_impl(text)
  to authenticated;
grant execute on function content_private.owner_claim_existing_gallery_impl(
  uuid, text, text, text, uuid
) to authenticated;
grant execute on function content_private.owner_create_gallery_claim_impl(
  text, text, text, text, text, uuid
) to authenticated;

create or replace function public.owner_current_access()
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select content_private.owner_current_access_impl();
$$;

create or replace function public.owner_search_galleries(
  p_query text
)
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select * from content_private.owner_search_galleries_impl(p_query);
$$;

create or replace function public.owner_claim_existing_gallery(
  p_gallery_id uuid,
  p_website_url text,
  p_social_url text,
  p_claim_note text,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.owner_claim_existing_gallery_impl(
    p_gallery_id,
    p_website_url,
    p_social_url,
    p_claim_note,
    p_request_id
  );
$$;

create or replace function public.owner_create_gallery_claim(
  p_name_ko text,
  p_name_en text,
  p_website_url text,
  p_social_url text,
  p_claim_note text,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.owner_create_gallery_claim_impl(
    p_name_ko,
    p_name_en,
    p_website_url,
    p_social_url,
    p_claim_note,
    p_request_id
  );
$$;

revoke all on function public.owner_current_access()
  from public, anon, authenticated, service_role;
revoke all on function public.owner_search_galleries(text)
  from public, anon, authenticated, service_role;
revoke all on function public.owner_claim_existing_gallery(
  uuid, text, text, text, uuid
) from public, anon, authenticated, service_role;
revoke all on function public.owner_create_gallery_claim(
  text, text, text, text, text, uuid
) from public, anon, authenticated, service_role;

grant execute on function public.owner_current_access()
  to authenticated;
grant execute on function public.owner_search_galleries(text)
  to authenticated;
grant execute on function public.owner_claim_existing_gallery(
  uuid, text, text, text, uuid
) to authenticated;
grant execute on function public.owner_create_gallery_claim(
  text, text, text, text, text, uuid
) to authenticated;
