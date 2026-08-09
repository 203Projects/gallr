begin;

-- R3 now ships as a free owner capability. Keep the historical payment columns
-- nullable so migration lineage remains reversible, but remove every callable
-- payment surface and the constraint that made payment proof an entitlement.
alter table content.launch_kits
  drop constraint if exists launch_kits_active_payment;

drop function if exists public.owner_prepare_launch_kit_checkout(text, uuid);
drop function if exists public.service_attach_launch_kit_checkout(uuid, text, text, integer);
drop function if exists public.service_activate_launch_kit(text, text, text, bigint, text);
drop function if exists content_private.owner_prepare_launch_kit_checkout_impl(text, uuid);
drop function if exists content_private.service_attach_launch_kit_checkout_impl(uuid, text, text, integer);
drop function if exists content_private.service_activate_launch_kit_impl(text, text, text, bigint, text);

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
  v_kit_id uuid;
  v_status content.launch_kit_status;
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
    raise exception using errcode = '42501', message = 'published_owner_exhibition_required';
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
    exhibition_id, gallery_id, status, activated_at, created_by, updated_by
  ) values (
    p_exhibition_id, v_gallery_id, 'active'::content.launch_kit_status,
    now(), v_user_id, v_user_id
  )
  on conflict (exhibition_id) do nothing
  returning id, status into v_kit_id, v_status;

  if v_kit_id is not null then
    v_activated := true;
  else
    select kit.id, kit.status
    into v_kit_id, v_status
    from content.launch_kits as kit
    where kit.exhibition_id = p_exhibition_id
      and kit.gallery_id = v_gallery_id
    for update;

    if v_kit_id is null then
      raise exception using errcode = '42501', message = 'published_owner_exhibition_required';
    elsif v_status = 'pending'::content.launch_kit_status then
      update content.launch_kits
      set status = 'active'::content.launch_kit_status,
          activated_at = coalesce(activated_at, now()),
          revision = revision + 1,
          updated_at = now(),
          updated_by = v_user_id
      where id = v_kit_id;
      v_activated := true;
    elsif v_status <> 'active'::content.launch_kit_status then
      raise exception using errcode = '55000', message = 'launch_kit_not_activatable';
    end if;
  end if;

  if v_activated then
    insert into content.audit_log (
      actor_user_id, action, entity_type, entity_id, request_id, metadata
    ) values (
      v_user_id, 'launch_kit.activated', 'launch_kit', v_kit_id::text,
      p_request_id, jsonb_build_object(
        'exhibition_id', p_exhibition_id,
        'activation', 'free'
      )
    );
  end if;

  v_response := content_private.owner_launch_kit_json(v_kit_id);
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'owner_activate_launch_kit',
    v_fingerprint, v_response
  );
end;
$$;

create or replace function public.owner_activate_launch_kit(
  p_exhibition_id text, p_request_id uuid
) returns jsonb language sql volatile security invoker set search_path = ''
as $$ select content_private.owner_activate_launch_kit_impl(p_exhibition_id, p_request_id); $$;

revoke all on function content_private.owner_activate_launch_kit_impl(text, uuid)
from public, anon, authenticated, service_role;
grant execute on function content_private.owner_activate_launch_kit_impl(text, uuid)
to authenticated;

revoke all on function public.owner_activate_launch_kit(text, uuid)
from public, anon, service_role;
grant execute on function public.owner_activate_launch_kit(text, uuid)
to authenticated;

comment on function public.owner_activate_launch_kit(text, uuid) is
  'Activates the free Launch Kit for an owner''s currently published exhibition.';
comment on column content.launch_kits.stripe_price_id is
  'Deprecated lineage field. Current Launch Kits are free and no payment integration is active.';

-- Preserve the R4 delivery contract while removing the inaccurate paid claim.
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
    hashtextextended('local-promotion:' || p_viewer_digest || ':' || v_displayed_on::text, 0)
  );
  if exists (
    select 1 from content.local_promotion_impressions impression
    where impression.viewer_digest = p_viewer_digest
      and impression.displayed_on = v_displayed_on
  ) then
    return null;
  end if;

  update content.local_promotions set status = 'ended', updated_at = now()
  where status in ('approved', 'active') and ends_at <= now();
  update content.local_promotions set status = 'active', updated_at = now()
  where status = 'approved' and starts_at <= now() and ends_at > now();

  select promotion.id into v_promotion_id
  from content.local_promotions promotion
  join content.launch_kits kit
    on kit.id = promotion.launch_kit_id
   and kit.status = 'active'::content.launch_kit_status
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
  order by md5(p_viewer_digest || ':' || v_displayed_on::text || ':' || promotion.id::text)
  limit 1;
  if not found then return null; end if;

  insert into content.local_promotion_impressions (
    promotion_id, viewer_digest, displayed_on, city_ko, region_ko
  ) values (v_promotion_id, p_viewer_digest, v_displayed_on, v_city, v_region);

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
    'disclosure', 'promoted_placement'
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

comment on function content_private.service_select_local_promotion_impl(text, text, text) is
  'Selects one staff-approved free local promotion with a transparent promoted-placement disclosure.';

commit;
