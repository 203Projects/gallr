begin;

do $$
begin
  create type content.launch_kit_entitlement_source as enum (
    'free_beta', 'paid'
  );
exception when duplicate_object then null;
end
$$;

alter table content.launch_kits
  add column if not exists entitlement_source
    content.launch_kit_entitlement_source;

-- Before this migration, every active row was protected by
-- launch_kits_active_payment. Refuse to guess if an environment does not match
-- that lineage; active payment-backed rows are the only rows safe to backfill.
do $$
begin
  if exists (
    select 1
    from content.launch_kits as kit
    where kit.status = 'active'::content.launch_kit_status
      and kit.entitlement_source is null
      and (
        kit.activated_at is null
        or kit.stripe_price_id is null
        or kit.stripe_checkout_session_id is null
        or kit.stripe_payment_intent_id is null
        or kit.stripe_event_id is null
        or kit.amount_total is null
        or kit.currency is null
      )
  ) then
    raise exception using
      errcode = '23514',
      message = 'launch_kit_entitlement_backfill_inconsistent';
  end if;
end
$$;

update content.launch_kits
set entitlement_source = 'paid'::content.launch_kit_entitlement_source
where status = 'active'::content.launch_kit_status
  and entitlement_source is null;

-- Once the payment RPCs are removed there is no supported way to complete or
-- cancel an in-flight checkout. Stop the migration so an operator can resolve
-- that evidence under the old contract instead of stranding a payment attempt.
do $$
begin
  if exists (
    select 1
    from content.launch_kits as kit
    where kit.status = 'pending'::content.launch_kit_status
      and (
        kit.entitlement_source is not null
        or kit.activated_at is not null
        or kit.stripe_price_id is not null
        or kit.stripe_checkout_session_id is not null
        or kit.stripe_payment_intent_id is not null
        or kit.stripe_event_id is not null
        or kit.amount_total is not null
        or kit.currency is not null
        or kit.checkout_attempt <> 0
      )
  ) then
    raise exception using
      errcode = '55000',
      message = 'launch_kit_pending_payment_state_requires_resolution';
  end if;
end
$$;

do $$
begin
  if exists (
    select 1
    from content.launch_kits as kit
    where kit.status = 'active'::content.launch_kit_status
      and (
        kit.activated_at is null
        or kit.entitlement_source is null
      )
  ) or exists (
    select 1
    from content.launch_kits as kit
    where kit.entitlement_source = 'paid'::content.launch_kit_entitlement_source
      and (
        kit.activated_at is null
        or kit.stripe_price_id is null
        or kit.stripe_checkout_session_id is null
        or kit.stripe_payment_intent_id is null
        or kit.stripe_event_id is null
        or kit.amount_total is null
        or kit.currency is null
      )
  ) or exists (
    select 1
    from content.launch_kits as kit
    where kit.entitlement_source = 'free_beta'::content.launch_kit_entitlement_source
      and (
        kit.activated_at is null
        or kit.stripe_price_id is not null
        or kit.stripe_checkout_session_id is not null
        or kit.stripe_payment_intent_id is not null
        or kit.stripe_event_id is not null
        or kit.amount_total is not null
        or kit.currency is not null
        or kit.checkout_attempt <> 0
      )
  ) then
    raise exception using
      errcode = '23514',
      message = 'launch_kit_entitlement_state_inconsistent';
  end if;
end
$$;

alter table content.launch_kits
  drop constraint if exists launch_kits_active_payment,
  drop constraint if exists launch_kits_active_entitlement,
  drop constraint if exists launch_kits_free_beta_without_payment,
  drop constraint if exists launch_kits_paid_entitlement_evidence;

alter table content.launch_kits
  add constraint launch_kits_active_entitlement check (
    status <> 'active'::content.launch_kit_status
    or (
      activated_at is not null
      and entitlement_source is not null
    )
  ),
  add constraint launch_kits_free_beta_without_payment check (
    entitlement_source is distinct from
      'free_beta'::content.launch_kit_entitlement_source
    or (
      stripe_price_id is null
      and stripe_checkout_session_id is null
      and stripe_payment_intent_id is null
      and stripe_event_id is null
      and amount_total is null
      and currency is null
      and checkout_attempt = 0
    )
  ),
  add constraint launch_kits_paid_entitlement_evidence check (
    entitlement_source is distinct from
      'paid'::content.launch_kit_entitlement_source
    or (
      activated_at is not null
      and stripe_price_id is not null
      and stripe_checkout_session_id is not null
      and stripe_payment_intent_id is not null
      and stripe_event_id is not null
      and amount_total is not null
      and currency is not null
    )
  );

drop function if exists public.owner_prepare_launch_kit_checkout(text, uuid);
drop function if exists public.service_attach_launch_kit_checkout(uuid, text, text, integer);
drop function if exists public.service_activate_launch_kit(text, text, text, bigint, text);
drop function if exists content_private.owner_prepare_launch_kit_checkout_impl(text, uuid);
drop function if exists content_private.service_attach_launch_kit_checkout_impl(uuid, text, text, integer);
drop function if exists content_private.service_activate_launch_kit_impl(text, text, text, bigint, text);

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
    'entitlement_source', kit.entitlement_source::text,
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

-- The public RSVP page receives the same bounded presentation fields already
-- published in the canonical catalogue. Payment, membership, review, audit,
-- internal media, and guest data remain outside this service-only projection.
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
    'name_en', coalesce(version.name_en, ''),
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', coalesce(version.venue_name_en, ''),
    'address_ko', version.address_ko,
    'address_en', coalesce(version.address_en, ''),
    'cover_image_url', coalesce(
      cover.public_url,
      version.legacy_cover_image_url,
      ''
    ),
    'description_ko', coalesce(version.description_ko, ''),
    'description_en', coalesce(version.description_en, ''),
    'opening_date', to_char(version.opening_date, 'YYYY-MM-DD'),
    'closing_date', to_char(version.closing_date, 'YYYY-MM-DD'),
    'hours', coalesce(version.hours, ''),
    'contact', coalesce(version.contact, ''),
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
  left join lateral (
    select asset.public_url
    from content.exhibition_version_media as attachment
    join content.media_assets as asset on asset.id = attachment.media_id
    where attachment.version_id = version.id
      and attachment.role = 'cover'::content.media_role
      and asset.status = 'published'::content.media_asset_status
      and asset.purged_at is null
    order by attachment.sort_order, attachment.created_at, attachment.media_id
    limit 1
  ) as cover on true
  where kit.public_token = p_public_token
    and kit.status = 'active'::content.launch_kit_status
    and exhibition.owner_status = 'published'::content.owner_exhibition_status
    and exhibition.archived_at is null
    and version.status = 'published'::content.exhibition_version_status;
$$;

comment on function content_private.service_public_launch_kit_impl(uuid) is
  'Returns only published exhibition presentation required by the public RSVP page.';

create or replace function content_private.owner_activate_launch_kit_impl(
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
  v_kit content.launch_kits;
  v_activated boolean := false;
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
    raise exception using
      errcode = '42501',
      message = 'published_owner_exhibition_required';
  end if;

  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('exhibition_id', p_exhibition_id)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_activate_launch_kit', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  insert into content.launch_kits (
    exhibition_id, gallery_id, status, entitlement_source,
    activated_at, created_by, updated_by
  ) values (
    p_exhibition_id, v_gallery_id, 'active'::content.launch_kit_status,
    'free_beta'::content.launch_kit_entitlement_source,
    now(), v_user_id, v_user_id
  )
  on conflict (exhibition_id) do nothing
  returning * into v_kit;

  if v_kit.id is not null then
    v_activated := true;
  else
    select kit.*
    into v_kit
    from content.launch_kits as kit
    where kit.exhibition_id = p_exhibition_id
      and kit.gallery_id = v_gallery_id
    for update;

    if v_kit.id is null then
      raise exception using
        errcode = '42501',
        message = 'published_owner_exhibition_required';
    elsif v_kit.status = 'active'::content.launch_kit_status then
      null;
    elsif v_kit.status = 'pending'::content.launch_kit_status then
      if v_kit.entitlement_source is not null
         or v_kit.activated_at is not null
         or v_kit.stripe_price_id is not null
         or v_kit.stripe_checkout_session_id is not null
         or v_kit.stripe_payment_intent_id is not null
         or v_kit.stripe_event_id is not null
         or v_kit.amount_total is not null
         or v_kit.currency is not null
         or v_kit.checkout_attempt <> 0 then
        raise exception using
          errcode = '55000',
          message = 'launch_kit_payment_state_present';
      end if;

      update content.launch_kits
      set status = 'active'::content.launch_kit_status,
          entitlement_source = 'free_beta'::content.launch_kit_entitlement_source,
          activated_at = now(),
          revision = revision + 1,
          updated_at = now(),
          updated_by = v_user_id
      where id = v_kit.id
      returning * into v_kit;
      v_activated := true;
    else
      raise exception using
        errcode = '55000',
        message = 'launch_kit_not_activatable';
    end if;
  end if;

  if v_activated then
    insert into content.audit_log (
      actor_user_id, action, entity_type, entity_id, request_id, metadata
    ) values (
      v_user_id, 'launch_kit.activated', 'launch_kit', v_kit.id::text,
      p_request_id, jsonb_build_object(
        'exhibition_id', p_exhibition_id,
        'entitlement_source', 'free_beta'
      )
    );
  end if;

  v_response := content_private.owner_launch_kit_json(v_kit.id);
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_activate_launch_kit',
    v_fingerprint, v_response
  );
end;
$$;

create or replace function public.owner_activate_launch_kit(
  p_exhibition_id text,
  p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.owner_activate_launch_kit_impl(
    p_exhibition_id, p_request_id
  );
$$;

-- R4 remains paid-only even while R3 is free. The owner boundary rejects a
-- free-beta Kit before command-request or promotion state can be written.
create or replace function content_private.owner_request_local_promotion_impl(
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
  v_version content.exhibition_versions%rowtype;
  v_promotion content.local_promotions%rowtype;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_response jsonb;
begin
  if v_kit.entitlement_source is distinct from
      'paid'::content.launch_kit_entitlement_source then
    raise exception using
      errcode = '42501',
      message = 'paid_launch_kit_required';
  end if;

  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('launch_kit_id', p_launch_kit_id)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_request_local_promotion', v_fingerprint
  ) request;
  if v_is_replay then return v_stored; end if;

  select version.* into v_version
  from content.exhibitions exhibition
  join content.exhibition_versions version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  where exhibition.id = v_kit.exhibition_id
    and exhibition.gallery_id = v_kit.gallery_id
    and exhibition.owner_status = 'published'::content.owner_exhibition_status
    and exhibition.archived_at is null
    and version.status = 'published'::content.exhibition_version_status
    and version.closing_date >= (now() at time zone 'Asia/Seoul')::date;
  if not found then
    raise exception using
      errcode = '42501',
      message = 'published_owner_exhibition_required';
  end if;

  insert into content.local_promotions (
    launch_kit_id, exhibition_id, gallery_id, status,
    city_ko, city_en, region_ko, region_en,
    requested_at, created_by, updated_by
  ) values (
    v_kit.id, v_kit.exhibition_id, v_kit.gallery_id, 'submitted',
    v_version.city_ko, coalesce(v_version.city_en, ''),
    v_version.region_ko, coalesce(v_version.region_en, ''),
    now(), v_user_id, v_user_id
  )
  on conflict (launch_kit_id) do update
  set
    status = 'submitted',
    city_ko = excluded.city_ko,
    city_en = excluded.city_en,
    region_ko = excluded.region_ko,
    region_en = excluded.region_en,
    starts_at = null,
    ends_at = null,
    review_notes = null,
    reviewed_at = null,
    reviewed_by = null,
    requested_at = now(),
    revision = content.local_promotions.revision + 1,
    updated_at = now(),
    updated_by = v_user_id
  where content.local_promotions.status in (
    'rejected'::content.local_promotion_status,
    'ended'::content.local_promotion_status
  )
  returning * into v_promotion;

  if not found then
    raise exception using
      errcode = '55000',
      message = 'promotion_request_already_open';
  end if;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id, metadata
  ) values (
    v_user_id, 'local_promotion.requested', 'local_promotion',
    v_promotion.id::text, p_request_id,
    jsonb_build_object(
      'launch_kit_id', v_kit.id,
      'exhibition_id', v_kit.exhibition_id,
      'gallery_id', v_kit.gallery_id
    )
  );
  insert into content.outbox_events (
    aggregate_type, aggregate_id, event_type, payload, deduplication_key
  ) values (
    'local_promotion', v_promotion.id::text, 'local_promotion.requested',
    jsonb_build_object(
      'promotion_id', v_promotion.id,
      'exhibition_id', v_kit.exhibition_id,
      'gallery_id', v_kit.gallery_id
    ),
    format('local_promotion:%s:requested:%s', v_promotion.id, p_request_id)
  );

  v_response := content_private.local_promotion_json(v_promotion.id);
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_request_local_promotion',
    v_fingerprint, v_response
  );
end;
$$;

-- Delivery independently filters on the paid entitlement. This prevents a
-- stale or privileged-inserted promotion row from bypassing the owner gate.
create or replace function content_private.service_select_local_promotion_impl(
  p_viewer_digest text,
  p_city_ko text,
  p_region_ko text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_city text := btrim(coalesce(p_city_ko, ''));
  v_region text := btrim(coalesce(p_region_ko, ''));
  v_displayed_on date := (now() at time zone 'Asia/Seoul')::date;
  v_promotion_id uuid;
  v_response jsonb;
begin
  if p_viewer_digest !~ '^[0-9a-f]{64}$'
     or (v_city = '' and v_region = '')
     or length(v_city) > 100 or length(v_region) > 100 then
    raise exception using errcode = '22023', message = 'promotion_request_invalid';
  end if;
  perform pg_advisory_xact_lock(
    hashtextextended(
      'local-promotion:' || p_viewer_digest || ':' || v_displayed_on::text,
      0
    )
  );
  if exists (
    select 1
    from content.local_promotion_impressions impression
    where impression.viewer_digest = p_viewer_digest
      and impression.displayed_on = v_displayed_on
  ) then
    return null;
  end if;

  update content.local_promotions
  set status = 'ended', updated_at = now()
  where status in ('approved', 'active') and ends_at <= now();
  update content.local_promotions
  set status = 'active', updated_at = now()
  where status = 'approved' and starts_at <= now() and ends_at > now();

  select promotion.id into v_promotion_id
  from content.local_promotions promotion
  join content.launch_kits kit
    on kit.id = promotion.launch_kit_id
   and kit.status = 'active'::content.launch_kit_status
   and kit.entitlement_source = 'paid'::content.launch_kit_entitlement_source
  join content.exhibitions exhibition
    on exhibition.id = promotion.exhibition_id
   and exhibition.gallery_id = promotion.gallery_id
   and exhibition.owner_status = 'published'::content.owner_exhibition_status
   and exhibition.archived_at is null
  join content.exhibition_versions version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
   and version.status = 'published'::content.exhibition_version_status
  where promotion.status = 'active'::content.local_promotion_status
    and promotion.starts_at <= now()
    and promotion.ends_at > now()
    and (v_city = '' or promotion.city_ko = v_city)
    and (v_region = '' or promotion.region_ko = v_region)
    and version.closing_date >= v_displayed_on
  order by md5(
    p_viewer_digest || ':' || v_displayed_on::text || ':' || promotion.id::text
  )
  limit 1;
  if not found then return null; end if;

  insert into content.local_promotion_impressions (
    promotion_id, viewer_digest, displayed_on, city_ko, region_ko
  ) values (
    v_promotion_id, p_viewer_digest, v_displayed_on, v_city, v_region
  );

  select jsonb_build_object(
    'promotion_id', promotion.id,
    'exhibition_id', promotion.exhibition_id,
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'city_ko', version.city_ko,
    'city_en', version.city_en,
    'region_ko', version.region_ko,
    'region_en', version.region_en,
    'opening_date', to_char(version.opening_date, 'YYYY-MM-DD'),
    'closing_date', to_char(version.closing_date, 'YYYY-MM-DD'),
    'cover_image_url', cover.public_url,
    'disclosure', 'paid_placement'
  ) into v_response
  from content.local_promotions promotion
  join content.exhibitions exhibition on exhibition.id = promotion.exhibition_id
  join content.exhibition_versions version
    on version.id = exhibition.published_version_id
   and version.exhibition_id = exhibition.id
  left join lateral (
    select asset.public_url
    from content.exhibition_version_media attachment
    join content.media_assets asset on asset.id = attachment.media_id
    where attachment.version_id = version.id
      and attachment.role = 'cover'::content.media_role
      and asset.status = 'published'::content.media_asset_status
    order by attachment.sort_order, asset.id
    limit 1
  ) cover on true
  where promotion.id = v_promotion_id;
  return v_response;
end;
$$;

revoke all on function content_private.owner_launch_kit_json(uuid)
from public, anon, authenticated, service_role;

revoke all on function content_private.owner_activate_launch_kit_impl(text, uuid)
from public, anon, authenticated, service_role;
grant execute on function content_private.owner_activate_launch_kit_impl(text, uuid)
to authenticated;

revoke all on function public.owner_activate_launch_kit(text, uuid)
from public, anon, authenticated, service_role;
grant execute on function public.owner_activate_launch_kit(text, uuid)
to authenticated;

revoke all on function content_private.owner_request_local_promotion_impl(uuid, uuid)
from public, anon, authenticated, service_role;
grant execute on function content_private.owner_request_local_promotion_impl(uuid, uuid)
to authenticated;

revoke all on function content_private.service_select_local_promotion_impl(text, text, text)
from public, anon, authenticated, service_role;
grant execute on function content_private.service_select_local_promotion_impl(text, text, text)
to service_role;

comment on type content.launch_kit_entitlement_source is
  'Provider-independent Launch Kit authorization source. R4 requires paid.';
comment on column content.launch_kits.entitlement_source is
  'Explicit authorization source: free beta tooling or payment-backed access.';
comment on function public.owner_activate_launch_kit(text, uuid) is
  'Idempotently activates a free-beta Launch Kit for one currently published owner exhibition.';

commit;
