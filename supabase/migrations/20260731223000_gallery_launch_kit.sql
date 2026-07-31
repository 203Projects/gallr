begin;

do $$
begin
  create type content.launch_kit_status as enum (
    'pending', 'active', 'cancelled', 'refunded'
  );
exception when duplicate_object then null;
end
$$;

do $$
begin
  create type content.launch_guest_status as enum (
    'going', 'checked_in', 'cancelled'
  );
exception when duplicate_object then null;
end
$$;

do $$
begin
  create type content.launch_guest_source as enum ('public_rsvp', 'owner');
exception when duplicate_object then null;
end
$$;

create table content.launch_kits (
  id uuid primary key default gen_random_uuid(),
  exhibition_id text not null unique
    references content.exhibitions(id) on delete restrict,
  gallery_id uuid not null
    references content.galleries(id) on delete restrict,
  status content.launch_kit_status not null default 'pending',
  public_token uuid not null unique default gen_random_uuid(),
  stripe_price_id text,
  stripe_checkout_session_id text unique,
  stripe_payment_intent_id text unique,
  stripe_event_id text unique,
  amount_total bigint,
  currency text,
  checkout_attempt integer not null default 0,
  revision integer not null default 1,
  activated_at timestamptz,
  cancelled_at timestamptz,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint launch_kits_checkout_attempt_nonnegative check (checkout_attempt >= 0),
  constraint launch_kits_revision_positive check (revision > 0),
  constraint launch_kits_amount_nonnegative check (
    amount_total is null or amount_total >= 0
  ),
  constraint launch_kits_currency_format check (
    currency is null or currency ~ '^[a-z]{3}$'
  ),
  constraint launch_kits_active_payment check (
    status <> 'active'::content.launch_kit_status
    or (
      activated_at is not null
      and stripe_checkout_session_id is not null
      and stripe_payment_intent_id is not null
      and stripe_event_id is not null
      and stripe_price_id is not null
      and amount_total is not null
      and currency is not null
    )
  )
);

create index launch_kits_gallery_status_idx
  on content.launch_kits (gallery_id, status, updated_at desc, id);

create table content.launch_guests (
  id uuid primary key default gen_random_uuid(),
  launch_kit_id uuid not null
    references content.launch_kits(id) on delete cascade,
  name text not null,
  email text not null,
  email_normalized text not null,
  party_size smallint not null default 1,
  status content.launch_guest_status not null default 'going',
  source content.launch_guest_source not null,
  privacy_acknowledged_at timestamptz,
  checked_in_at timestamptz,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint launch_guests_name_length check (
    length(btrim(name)) between 1 and 200
  ),
  constraint launch_guests_email_length check (
    length(email) between 3 and 320
  ),
  constraint launch_guests_email_normalized check (
    email_normalized = lower(btrim(email))
    and email_normalized ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
  ),
  constraint launch_guests_party_size check (party_size between 1 and 6),
  constraint launch_guests_check_in_state check (
    (status = 'checked_in'::content.launch_guest_status) =
      (checked_in_at is not null)
  ),
  constraint launch_guests_public_privacy check (
    source <> 'public_rsvp'::content.launch_guest_source
    or privacy_acknowledged_at is not null
  )
);

create unique index launch_guests_active_email_idx
  on content.launch_guests (launch_kit_id, email_normalized)
  where status <> 'cancelled'::content.launch_guest_status;
create index launch_guests_kit_status_cursor_idx
  on content.launch_guests (launch_kit_id, status, created_at, id);

create table content.launch_rsvp_rate_limits (
  source_digest text not null,
  window_start timestamptz not null,
  hits integer not null default 1,
  updated_at timestamptz not null default now(),
  primary key (source_digest, window_start),
  constraint launch_rsvp_rate_digest_format check (
    source_digest ~ '^[0-9a-f]{64}$'
  ),
  constraint launch_rsvp_rate_hits_positive check (hits > 0)
);

create table content.stripe_webhook_events (
  event_id text primary key,
  event_type text not null,
  processed_at timestamptz not null default now(),
  constraint stripe_webhook_event_id_length check (
    length(event_id) between 1 and 255
  ),
  constraint stripe_webhook_event_type_length check (
    length(event_type) between 1 and 255
  )
);

alter table content.launch_kits enable row level security;
alter table content.launch_guests enable row level security;
alter table content.launch_rsvp_rate_limits enable row level security;
alter table content.stripe_webhook_events enable row level security;

revoke all on table content.launch_kits,
  content.launch_guests,
  content.launch_rsvp_rate_limits,
  content.stripe_webhook_events
from public, anon, authenticated;

create or replace function content_private.owner_launch_kit_json(
  p_launch_kit_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', kit.id,
    'exhibition_id', kit.exhibition_id,
    'status', kit.status::text,
    'revision', kit.revision,
    'public_token', case
      when kit.status = 'active'::content.launch_kit_status
        then kit.public_token::text
      else ''
    end,
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'reception_date', coalesce(
      to_char(version.reception_date at time zone 'Asia/Seoul', 'YYYY-MM-DD'),
      ''
    ),
    'reception_start_time', coalesce(version.opening_time, ''),
    'rsvp_count', coalesce(summary.rsvp_count, 0),
    'guest_count', coalesce(summary.guest_count, 0),
    'checked_in_count', coalesce(summary.checked_in_count, 0),
    'updated_at', kit.updated_at
  )
  from content.launch_kits as kit
  join content.exhibitions as exhibition on exhibition.id = kit.exhibition_id
  join content.exhibition_versions as version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  left join lateral (
    select
      count(*) filter (
        where guest.status <> 'cancelled'::content.launch_guest_status
      )::bigint as rsvp_count,
      coalesce(sum(guest.party_size) filter (
        where guest.status <> 'cancelled'::content.launch_guest_status
      ), 0)::bigint as guest_count,
      coalesce(sum(guest.party_size) filter (
        where guest.status = 'checked_in'::content.launch_guest_status
      ), 0)::bigint as checked_in_count
    from content.launch_guests as guest
    where guest.launch_kit_id = kit.id
  ) as summary on true
  where kit.id = p_launch_kit_id;
$$;

create or replace function content_private.owner_prepare_launch_kit_checkout_impl(
  p_exhibition_id text,
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
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(true);
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_kit_id uuid;
  v_status content.launch_kit_status;
  v_response jsonb;
begin
  if not exists (
    select 1
    from content.exhibitions as exhibition
    join content.exhibition_versions as version
      on version.id = exhibition.published_version_id
     and version.exhibition_id = exhibition.id
    where exhibition.id = p_exhibition_id
      and exhibition.gallery_id = v_gallery_id
      and exhibition.owner_status = 'published'::content.owner_exhibition_status
      and exhibition.archived_at is null
      and version.status = 'published'::content.exhibition_version_status
  ) then
    raise exception using errcode = '42501', message = 'published_owner_exhibition_required';
  end if;

  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('exhibition_id', p_exhibition_id)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_prepare_launch_kit_checkout', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  insert into content.launch_kits (
    exhibition_id, gallery_id, created_by, updated_by
  ) values (
    p_exhibition_id, v_gallery_id, v_user_id, v_user_id
  )
  on conflict (exhibition_id) do update
  set updated_at = content.launch_kits.updated_at
  returning id, status into v_kit_id, v_status;

  if v_status not in (
    'pending'::content.launch_kit_status,
    'active'::content.launch_kit_status
  ) then
    raise exception using errcode = '55000', message = 'launch_kit_not_purchasable';
  end if;

  v_response := jsonb_build_object(
    'launch_kit_id', v_kit_id,
    'exhibition_id', p_exhibition_id,
    'gallery_id', v_gallery_id,
    'status', v_status::text,
    'checkout_attempt', (
      select checkout_attempt from content.launch_kits where id = v_kit_id
    ),
    'checkout_session_id', (
      select stripe_checkout_session_id from content.launch_kits where id = v_kit_id
    )
  );
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_prepare_launch_kit_checkout',
    v_fingerprint, v_response
  );
end;
$$;

create or replace function content_private.service_attach_launch_kit_checkout_impl(
  p_launch_kit_id uuid,
  p_stripe_price_id text,
  p_checkout_session_id text,
  p_checkout_attempt integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_kit content.launch_kits;
begin
  if length(p_stripe_price_id) not between 1 and 255
     or length(p_checkout_session_id) not between 1 and 255
     or p_checkout_attempt < 1 then
    raise exception using errcode = '22023', message = 'launch_checkout_invalid';
  end if;
  update content.launch_kits
  set stripe_price_id = p_stripe_price_id,
      stripe_checkout_session_id = p_checkout_session_id,
      checkout_attempt = p_checkout_attempt,
      revision = revision + 1,
      updated_at = now()
  where id = p_launch_kit_id
    and status = 'pending'::content.launch_kit_status
    and (
      stripe_checkout_session_id is null
      or stripe_checkout_session_id = p_checkout_session_id
      or p_checkout_attempt > checkout_attempt
    )
  returning * into v_kit;
  if v_kit.id is null then
    raise exception using errcode = '55000', message = 'launch_checkout_attach_conflict';
  end if;
  return jsonb_build_object(
    'launch_kit_id', v_kit.id,
    'checkout_attempt', v_kit.checkout_attempt
  );
end;
$$;

create or replace function content_private.service_activate_launch_kit_impl(
  p_checkout_session_id text,
  p_stripe_event_id text,
  p_payment_intent_id text,
  p_amount_total bigint,
  p_currency text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_kit content.launch_kits;
  v_inserted integer;
begin
  if length(p_checkout_session_id) not between 1 and 255
     or length(p_stripe_event_id) not between 1 and 255
     or length(p_payment_intent_id) not between 1 and 255
     or p_amount_total < 0
     or p_currency !~ '^[a-z]{3}$' then
    raise exception using errcode = '22023', message = 'launch_payment_invalid';
  end if;

  insert into content.stripe_webhook_events (event_id, event_type)
  values (p_stripe_event_id, 'checkout.session.completed')
  on conflict (event_id) do nothing;
  get diagnostics v_inserted = row_count;

  select * into v_kit
  from content.launch_kits
  where stripe_checkout_session_id = p_checkout_session_id
  for update;
  if v_kit.id is null then
    if v_inserted = 1 then
      delete from content.stripe_webhook_events where event_id = p_stripe_event_id;
    end if;
    raise exception using errcode = 'P0002', message = 'launch_checkout_not_found';
  end if;

  if v_kit.status = 'active'::content.launch_kit_status then
    if v_kit.stripe_payment_intent_id <> p_payment_intent_id
       or v_kit.amount_total <> p_amount_total
       or v_kit.currency <> p_currency then
      raise exception using errcode = '23505', message = 'launch_payment_replay_conflict';
    end if;
    return content_private.owner_launch_kit_json(v_kit.id);
  end if;

  if v_kit.status <> 'pending'::content.launch_kit_status then
    raise exception using errcode = '55000', message = 'launch_kit_not_activatable';
  end if;

  update content.launch_kits
  set status = 'active',
      stripe_payment_intent_id = p_payment_intent_id,
      stripe_event_id = p_stripe_event_id,
      amount_total = p_amount_total,
      currency = p_currency,
      activated_at = now(),
      revision = revision + 1,
      updated_at = now()
  where id = v_kit.id
  returning * into v_kit;

  insert into content.audit_log (
    action, entity_type, entity_id, metadata
  ) values (
    'launch_kit.activated', 'launch_kit', v_kit.id::text,
    jsonb_build_object(
      'exhibition_id', v_kit.exhibition_id,
      'stripe_event_id', p_stripe_event_id,
      'amount_total', p_amount_total,
      'currency', p_currency
    )
  );
  return content_private.owner_launch_kit_json(v_kit.id);
end;
$$;

create or replace function content_private.owner_list_launch_kits_impl()
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(false);
begin
  return query
  select content_private.owner_launch_kit_json(kit.id)
  from content.launch_kits as kit
  where kit.gallery_id = v_gallery_id
  order by kit.updated_at desc, kit.id;
end;
$$;

create or replace function content_private.owner_assert_active_launch_kit(
  p_launch_kit_id uuid
)
returns content.launch_kits
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(true);
  v_kit content.launch_kits;
begin
  select * into v_kit
  from content.launch_kits
  where id = p_launch_kit_id
    and gallery_id = v_gallery_id
    and status = 'active'::content.launch_kit_status;
  if v_kit.id is null then
    raise exception using errcode = '42501', message = 'active_launch_kit_required';
  end if;
  return v_kit;
end;
$$;

create or replace function content_private.launch_guest_json(
  p_guest content.launch_guests
)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select jsonb_build_object(
    'id', p_guest.id,
    'launch_kit_id', p_guest.launch_kit_id,
    'name', p_guest.name,
    'email', p_guest.email,
    'party_size', p_guest.party_size,
    'status', p_guest.status::text,
    'checked_in_at', p_guest.checked_in_at,
    'created_at', p_guest.created_at
  );
$$;

create or replace function content_private.owner_list_launch_guests_impl(
  p_launch_kit_id uuid,
  p_query text default '',
  p_status text default 'all',
  p_after_created_at timestamptz default null,
  p_after_id uuid default null,
  p_limit integer default 50
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_kit content.launch_kits := content_private.owner_assert_active_launch_kit(p_launch_kit_id);
  v_query text := btrim(coalesce(p_query, ''));
begin
  if length(v_query) > 200
     or p_status not in ('all', 'going', 'checked_in')
     or p_limit not between 1 and 100
     or ((p_after_created_at is null) <> (p_after_id is null)) then
    raise exception using errcode = '22023', message = 'launch_guest_list_invalid';
  end if;
  return query
  select content_private.launch_guest_json(guest)
  from content.launch_guests as guest
  where guest.launch_kit_id = v_kit.id
    and guest.status <> 'cancelled'::content.launch_guest_status
    and (
      p_status = 'all'
      or guest.status::text = p_status
    )
    and (
      v_query = ''
      or guest.name ilike '%' || v_query || '%'
      or guest.email_normalized ilike '%' || lower(v_query) || '%'
    )
    and (
      p_after_created_at is null
      or (guest.created_at, guest.id) > (p_after_created_at, p_after_id)
    )
  order by guest.created_at, guest.id
  limit p_limit;
end;
$$;

create or replace function content_private.owner_add_launch_guest_impl(
  p_launch_kit_id uuid,
  p_name text,
  p_email text,
  p_party_size integer,
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
  v_kit content.launch_kits := content_private.owner_assert_active_launch_kit(p_launch_kit_id);
  v_name text := btrim(coalesce(p_name, ''));
  v_email text := lower(btrim(coalesce(p_email, '')));
  v_guest content.launch_guests;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_response jsonb;
begin
  if length(v_name) not between 1 and 200
     or length(v_email) not between 3 and 320
     or v_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
     or p_party_size not between 1 and 6 then
    raise exception using errcode = '22023', message = 'launch_guest_invalid';
  end if;
  v_fingerprint := content_private.command_request_fingerprint(jsonb_build_object(
    'launch_kit_id', v_kit.id, 'name', v_name,
    'email', v_email, 'party_size', p_party_size
  ));
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_add_launch_guest', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  insert into content.launch_guests (
    launch_kit_id, name, email, email_normalized, party_size,
    source, created_by, updated_by
  ) values (
    v_kit.id, v_name, v_email, v_email, p_party_size,
    'owner', v_user_id, v_user_id
  )
  on conflict (launch_kit_id, email_normalized)
    where status <> 'cancelled'::content.launch_guest_status
  do update set
    name = excluded.name,
    email = excluded.email,
    party_size = excluded.party_size,
    updated_at = now(),
    updated_by = v_user_id
  returning * into v_guest;
  v_response := content_private.launch_guest_json(v_guest);
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_add_launch_guest', v_fingerprint, v_response
  );
end;
$$;

create or replace function content_private.owner_check_in_launch_guest_impl(
  p_launch_kit_id uuid,
  p_guest_id uuid,
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
  v_kit content.launch_kits := content_private.owner_assert_active_launch_kit(p_launch_kit_id);
  v_guest content.launch_guests;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_response jsonb;
begin
  v_fingerprint := content_private.command_request_fingerprint(jsonb_build_object(
    'launch_kit_id', v_kit.id, 'guest_id', p_guest_id
  ));
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_check_in_launch_guest', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  select * into v_guest
  from content.launch_guests
  where id = p_guest_id and launch_kit_id = v_kit.id
  for update;
  if v_guest.id is null or v_guest.status = 'cancelled'::content.launch_guest_status then
    raise exception using errcode = 'P0002', message = 'launch_guest_not_found';
  end if;
  if v_guest.status = 'going'::content.launch_guest_status then
    update content.launch_guests
    set status = 'checked_in', checked_in_at = now(),
        updated_at = now(), updated_by = v_user_id
    where id = v_guest.id
    returning * into v_guest;
  end if;
  v_response := content_private.launch_guest_json(v_guest);
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_check_in_launch_guest', v_fingerprint,
    v_response
  );
end;
$$;

create or replace function content_private.owner_rotate_launch_rsvp_token_impl(
  p_launch_kit_id uuid,
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
  v_kit content.launch_kits := content_private.owner_assert_active_launch_kit(p_launch_kit_id);
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_response jsonb;
begin
  v_fingerprint := content_private.command_request_fingerprint(jsonb_build_object(
    'launch_kit_id', v_kit.id
  ));
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_rotate_launch_rsvp_token', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  update content.launch_kits
  set public_token = gen_random_uuid(), revision = revision + 1, updated_at = now()
  where id = v_kit.id;
  v_response := content_private.owner_launch_kit_json(v_kit.id);
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id
  ) values (
    v_user_id, 'launch_kit.rsvp_token_rotated', 'launch_kit',
    v_kit.id::text, p_request_id
  );
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_rotate_launch_rsvp_token', v_fingerprint,
    v_response
  );
end;
$$;

create or replace function content_private.service_public_launch_kit_impl(
  p_public_token uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'exhibition_id', exhibition.id,
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'address_ko', version.address_ko,
    'address_en', version.address_en,
    'reception_date', coalesce(
      to_char(version.reception_date at time zone 'Asia/Seoul', 'YYYY-MM-DD'),
      ''
    ),
    'reception_start_time', coalesce(version.opening_time, '')
  )
  from content.launch_kits as kit
  join content.exhibitions as exhibition on exhibition.id = kit.exhibition_id
  join content.exhibition_versions as version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  where kit.public_token = p_public_token
    and kit.status = 'active'::content.launch_kit_status
    and exhibition.owner_status = 'published'::content.owner_exhibition_status
    and exhibition.archived_at is null
    and version.status = 'published'::content.exhibition_version_status;
$$;

create or replace function content_private.service_submit_launch_rsvp_impl(
  p_public_token uuid,
  p_name text,
  p_email text,
  p_party_size integer,
  p_privacy_acknowledged boolean,
  p_source_digest text
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_kit_id uuid;
  v_name text := btrim(coalesce(p_name, ''));
  v_email text := lower(btrim(coalesce(p_email, '')));
  v_hits integer;
  v_window timestamptz := date_trunc('hour', now());
begin
  if not p_privacy_acknowledged
     or length(v_name) not between 1 and 200
     or length(v_email) not between 3 and 320
     or v_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
     or p_party_size not between 1 and 6
     or p_source_digest !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'launch_rsvp_invalid';
  end if;
  select kit.id into v_kit_id
  from content.launch_kits as kit
  join content.exhibitions as exhibition on exhibition.id = kit.exhibition_id
  where kit.public_token = p_public_token
    and kit.status = 'active'::content.launch_kit_status
    and exhibition.owner_status = 'published'::content.owner_exhibition_status
    and exhibition.archived_at is null;
  if v_kit_id is null then return false; end if;

  insert into content.launch_rsvp_rate_limits (
    source_digest, window_start, hits
  ) values (p_source_digest, v_window, 1)
  on conflict (source_digest, window_start) do update
  set hits = content.launch_rsvp_rate_limits.hits + 1,
      updated_at = now()
  returning hits into v_hits;
  if v_hits > 10 then
    raise exception using errcode = 'P0001', message = 'launch_rsvp_rate_limited';
  end if;

  insert into content.launch_guests (
    launch_kit_id, name, email, email_normalized, party_size,
    source, privacy_acknowledged_at
  ) values (
    v_kit_id, v_name, v_email, v_email, p_party_size,
    'public_rsvp', now()
  )
  on conflict (launch_kit_id, email_normalized)
    where status <> 'cancelled'::content.launch_guest_status
  do update set
    name = excluded.name,
    email = excluded.email,
    party_size = excluded.party_size,
    privacy_acknowledged_at = excluded.privacy_acknowledged_at,
    updated_at = now();
  return true;
end;
$$;

create or replace function public.owner_prepare_launch_kit_checkout(
  p_exhibition_id text, p_request_id uuid
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.owner_prepare_launch_kit_checkout_impl(p_exhibition_id, p_request_id); $$;
create or replace function public.owner_list_launch_kits()
returns setof jsonb language sql stable security invoker set search_path = ''
as $$ select * from content_private.owner_list_launch_kits_impl(); $$;
create or replace function public.owner_list_launch_guests(
  p_launch_kit_id uuid, p_query text default '', p_status text default 'all',
  p_after_created_at timestamptz default null, p_after_id uuid default null,
  p_limit integer default 50
) returns setof jsonb language sql stable security invoker set search_path = ''
as $$ select * from content_private.owner_list_launch_guests_impl(
  p_launch_kit_id, p_query, p_status, p_after_created_at, p_after_id, p_limit
); $$;
create or replace function public.owner_add_launch_guest(
  p_launch_kit_id uuid, p_name text, p_email text,
  p_party_size integer, p_request_id uuid
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.owner_add_launch_guest_impl(
  p_launch_kit_id, p_name, p_email, p_party_size, p_request_id
); $$;
create or replace function public.owner_check_in_launch_guest(
  p_launch_kit_id uuid, p_guest_id uuid, p_request_id uuid
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.owner_check_in_launch_guest_impl(
  p_launch_kit_id, p_guest_id, p_request_id
); $$;
create or replace function public.owner_rotate_launch_rsvp_token(
  p_launch_kit_id uuid, p_request_id uuid
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.owner_rotate_launch_rsvp_token_impl(
  p_launch_kit_id, p_request_id
); $$;
create or replace function public.service_attach_launch_kit_checkout(
  p_launch_kit_id uuid, p_stripe_price_id text,
  p_checkout_session_id text, p_checkout_attempt integer
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.service_attach_launch_kit_checkout_impl(
  p_launch_kit_id, p_stripe_price_id, p_checkout_session_id, p_checkout_attempt
); $$;
create or replace function public.service_activate_launch_kit(
  p_checkout_session_id text, p_stripe_event_id text,
  p_payment_intent_id text, p_amount_total bigint, p_currency text
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.service_activate_launch_kit_impl(
  p_checkout_session_id, p_stripe_event_id, p_payment_intent_id,
  p_amount_total, p_currency
); $$;
create or replace function public.service_public_launch_kit(p_public_token uuid)
returns jsonb language sql stable security invoker set search_path = ''
as $$ select content_private.service_public_launch_kit_impl(p_public_token); $$;
create or replace function public.service_submit_launch_rsvp(
  p_public_token uuid, p_name text, p_email text, p_party_size integer,
  p_privacy_acknowledged boolean, p_source_digest text
) returns boolean language sql volatile security invoker set search_path = ''
as $$ select content_private.service_submit_launch_rsvp_impl(
  p_public_token, p_name, p_email, p_party_size,
  p_privacy_acknowledged, p_source_digest
); $$;

revoke all on function content_private.owner_launch_kit_json(uuid),
  content_private.owner_prepare_launch_kit_checkout_impl(text, uuid),
  content_private.owner_list_launch_kits_impl(),
  content_private.owner_assert_active_launch_kit(uuid),
  content_private.launch_guest_json(content.launch_guests),
  content_private.owner_list_launch_guests_impl(uuid, text, text, timestamptz, uuid, integer),
  content_private.owner_add_launch_guest_impl(uuid, text, text, integer, uuid),
  content_private.owner_check_in_launch_guest_impl(uuid, uuid, uuid),
  content_private.owner_rotate_launch_rsvp_token_impl(uuid, uuid)
from public, anon, authenticated, service_role;
grant execute on function content_private.owner_prepare_launch_kit_checkout_impl(text, uuid),
  content_private.owner_list_launch_kits_impl(),
  content_private.owner_list_launch_guests_impl(uuid, text, text, timestamptz, uuid, integer),
  content_private.owner_add_launch_guest_impl(uuid, text, text, integer, uuid),
  content_private.owner_check_in_launch_guest_impl(uuid, uuid, uuid),
  content_private.owner_rotate_launch_rsvp_token_impl(uuid, uuid)
to authenticated;

revoke all on function content_private.service_attach_launch_kit_checkout_impl(uuid, text, text, integer),
  content_private.service_activate_launch_kit_impl(text, text, text, bigint, text),
  content_private.service_public_launch_kit_impl(uuid),
  content_private.service_submit_launch_rsvp_impl(uuid, text, text, integer, boolean, text)
from public, anon, authenticated;
grant execute on function content_private.service_attach_launch_kit_checkout_impl(uuid, text, text, integer),
  content_private.service_activate_launch_kit_impl(text, text, text, bigint, text),
  content_private.service_public_launch_kit_impl(uuid),
  content_private.service_submit_launch_rsvp_impl(uuid, text, text, integer, boolean, text)
to service_role;

revoke all on function public.owner_prepare_launch_kit_checkout(text, uuid),
  public.owner_list_launch_kits(),
  public.owner_list_launch_guests(uuid, text, text, timestamptz, uuid, integer),
  public.owner_add_launch_guest(uuid, text, text, integer, uuid),
  public.owner_check_in_launch_guest(uuid, uuid, uuid),
  public.owner_rotate_launch_rsvp_token(uuid, uuid)
from public, anon, service_role;
grant execute on function public.owner_prepare_launch_kit_checkout(text, uuid),
  public.owner_list_launch_kits(),
  public.owner_list_launch_guests(uuid, text, text, timestamptz, uuid, integer),
  public.owner_add_launch_guest(uuid, text, text, integer, uuid),
  public.owner_check_in_launch_guest(uuid, uuid, uuid),
  public.owner_rotate_launch_rsvp_token(uuid, uuid)
to authenticated;

revoke all on function public.service_attach_launch_kit_checkout(uuid, text, text, integer),
  public.service_activate_launch_kit(text, text, text, bigint, text),
  public.service_public_launch_kit(uuid),
  public.service_submit_launch_rsvp(uuid, text, text, integer, boolean, text)
from public, anon, authenticated;
grant execute on function public.service_attach_launch_kit_checkout(uuid, text, text, integer),
  public.service_activate_launch_kit(text, text, text, bigint, text),
  public.service_public_launch_kit(uuid),
  public.service_submit_launch_rsvp(uuid, text, text, integer, boolean, text)
to service_role;

comment on table content.launch_guests is
  'Launch Kit RSVP personal data; private to the owning gallery and service workflows.';
comment on column content.launch_guests.checked_in_at is
  'Immutable first arrival time after the idempotent owner check-in command.';

commit;
