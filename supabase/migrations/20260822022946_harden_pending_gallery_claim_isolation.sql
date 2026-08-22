begin;

-- Keep pending claimants able to prepare drafts, but retain the membership
-- identity so downstream commands can distinguish an active owner from one
-- specific claimant. Active memberships are valid only for active galleries;
-- pending memberships may target either a new pending gallery or an existing
-- active gallery awaiting staff review.
create or replace function content_private.owner_assert_gallery_membership_record(
  p_require_active boolean default false
)
returns content.gallery_memberships
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := content_private.owner_assert_authenticated();
  v_membership content.gallery_memberships%rowtype;
begin
  select membership.*
  into v_membership
  from content.gallery_memberships as membership
  join content.galleries as gallery on gallery.id = membership.gallery_id
  where membership.user_id = v_user_id
    and membership.role = 'owner'::content.gallery_member_role
    and (
      (
        membership.status = 'active'::content.gallery_membership_status
        and gallery.status = 'active'::content.gallery_status
      )
      or (
        not p_require_active
        and membership.status = 'pending'::content.gallery_membership_status
        and gallery.status in (
          'pending'::content.gallery_status,
          'active'::content.gallery_status
        )
      )
    )
  order by membership.updated_at desc, membership.gallery_id
  limit 1;

  if v_membership.gallery_id is null then
    raise exception using
      errcode = '42501',
      message = case
        when p_require_active then 'active_gallery_membership_required'
        else 'gallery_membership_required'
      end;
  end if;

  return v_membership;
end;
$$;

create or replace function content_private.owner_assert_gallery_membership(
  p_require_active boolean default false
)
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_membership content.gallery_memberships%rowtype :=
    content_private.owner_assert_gallery_membership_record(p_require_active);
begin
  return v_membership.gallery_id;
end;
$$;

-- Draft creation locks the gallery before the final authorization check. This
-- shares approval's lock order, so a claimant queued behind an approval cannot
-- insert a new draft after their membership has been rejected. Existing-gallery
-- claimants receive a blank venue snapshot; canonical Gallery Info defaults are
-- copied only for active owners or the creator of a still-pending new gallery.
create or replace function content_private.owner_create_exhibition_draft_impl(
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
  v_initial_gallery_id uuid := content_private.owner_assert_gallery_membership(false);
  v_membership content.gallery_memberships%rowtype;
  v_gallery content.galleries%rowtype;
  v_copy_canonical_defaults boolean;
  v_fingerprint text;
  v_is_replay boolean;
  v_stored jsonb;
  v_exhibition_id text := gen_random_uuid()::text;
  v_version_id uuid;
  v_response jsonb;
begin
  select gallery.*
  into v_gallery
  from content.galleries as gallery
  where gallery.id = v_initial_gallery_id
  for update;
  if not found then
    raise exception using errcode = '42501', message = 'gallery_membership_required';
  end if;

  v_membership := content_private.owner_assert_gallery_membership_record(false);
  if v_membership.gallery_id is distinct from v_initial_gallery_id then
    raise exception using errcode = '42501', message = 'gallery_membership_required';
  end if;

  v_copy_canonical_defaults :=
    v_membership.status = 'active'::content.gallery_membership_status
    or (
      v_membership.status = 'pending'::content.gallery_membership_status
      and v_gallery.status = 'pending'::content.gallery_status
      and v_gallery.created_by = v_user_id
      and v_membership.created_by = v_user_id
    );

  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('gallery_id', v_membership.gallery_id)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'owner_create_exhibition_draft', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored; end if;

  insert into content.exhibitions (
    id, gallery_id, owner_status, owner_status_changed_at, created_by, updated_by
  ) values (
    v_exhibition_id,
    v_membership.gallery_id,
    'draft',
    now(),
    v_user_id,
    v_user_id
  );

  insert into content.exhibition_versions (
    exhibition_id, version_number, revision, status, venue_id,
    venue_name_ko, venue_name_en, city_ko, city_en, region_ko, region_en,
    address_ko, address_en, latitude, longitude, hours, contact,
    created_by, updated_by
  )
  select
    v_exhibition_id,
    1,
    1,
    'draft',
    case when v_copy_canonical_defaults then venue.id else null end,
    case
      when v_copy_canonical_defaults then coalesce(venue.name_ko, gallery.name_ko)
      else gallery.name_ko
    end,
    case
      when v_copy_canonical_defaults then coalesce(venue.name_en, gallery.name_en)
      else gallery.name_en
    end,
    case when v_copy_canonical_defaults then coalesce(venue.city_ko, '') else '' end,
    case when v_copy_canonical_defaults then coalesce(venue.city_en, '') else '' end,
    case when v_copy_canonical_defaults then coalesce(venue.region_ko, '') else '' end,
    case when v_copy_canonical_defaults then coalesce(venue.region_en, '') else '' end,
    case when v_copy_canonical_defaults then coalesce(venue.address_ko, '') else '' end,
    case when v_copy_canonical_defaults then coalesce(venue.address_en, '') else '' end,
    case when v_copy_canonical_defaults then venue.latitude else null end,
    case when v_copy_canonical_defaults then venue.longitude else null end,
    case when v_copy_canonical_defaults then venue.default_hours else null end,
    case when v_copy_canonical_defaults then venue.default_contact else null end,
    v_user_id,
    v_user_id
  from content.galleries as gallery
  left join content.venues as venue on venue.id = gallery.canonical_venue_id
  where gallery.id = v_membership.gallery_id
  returning id into v_version_id;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, request_id, metadata
  ) values (
    v_user_id,
    'owner_exhibition.draft_created',
    'exhibition',
    v_exhibition_id,
    p_request_id,
    jsonb_build_object(
      'gallery_id', v_membership.gallery_id,
      'version_id', v_version_id
    )
  );
  v_response := content_private.owner_exhibition_json(v_exhibition_id, v_version_id);
  return content_private.complete_command_request(
    v_user_id,
    p_request_id,
    'owner_create_exhibition_draft',
    v_fingerprint,
    v_response
  );
end;
$$;

-- Pending claimants may mutate only drafts they created. Active owners retain
-- gallery-wide access after approval, but hidden/quarantined drafts remain out
-- of every owner mutation path.
create or replace function content_private.owner_assert_exhibition_draft(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer
)
returns content.exhibition_versions
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_membership content.gallery_memberships%rowtype :=
    content_private.owner_assert_gallery_membership_record(false);
  v_exhibition content.exhibitions%rowtype;
  v_version content.exhibition_versions%rowtype;
begin
  if p_expected_version_id is null or p_expected_revision is null
     or p_expected_revision < 1 then
    raise exception using errcode = '22023', message = 'owner_revision_required';
  end if;

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
    and exhibition.gallery_id = v_membership.gallery_id
  for update;

  if not found
     or v_exhibition.owner_hidden_at is not null
     or (
       v_membership.status = 'pending'::content.gallery_membership_status
       and v_exhibition.created_by is distinct from v_membership.user_id
     ) then
    raise exception using errcode = '42501', message = 'owner_exhibition_access_denied';
  end if;
  if v_exhibition.owner_status not in (
    'draft'::content.owner_exhibition_status,
    'needs_changes'::content.owner_exhibition_status
  ) then
    raise exception using errcode = '22023', message = 'owner_exhibition_not_editable';
  end if;

  select version.*
  into v_version
  from content.exhibition_versions as version
  where version.exhibition_id = p_exhibition_id
    and version.id = p_expected_version_id
    and version.status = 'draft'::content.exhibition_version_status
  for update;
  if not found then
    raise exception using errcode = '42501', message = 'owner_exhibition_access_denied';
  end if;
  if v_version.revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_version.revision::text;
  end if;
  return v_version;
end;
$$;

create or replace function content_private.owner_list_exhibitions_impl()
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_membership content.gallery_memberships%rowtype :=
    content_private.owner_assert_gallery_membership_record(false);
begin
  return query
  select content_private.owner_exhibition_json(exhibition.id, chosen.id)
  from content.exhibitions as exhibition
  join lateral (
    select version.id
    from content.exhibition_versions as version
    where version.exhibition_id = exhibition.id
      and (
        version.status = 'draft'::content.exhibition_version_status
        or version.id = exhibition.published_version_id
      )
    order by
      (version.status = 'draft'::content.exhibition_version_status) desc,
      version.version_number desc
    limit 1
  ) as chosen on true
  where exhibition.gallery_id = v_membership.gallery_id
    and exhibition.owner_status is not null
    and exhibition.owner_hidden_at is null
    and (
      v_membership.status = 'active'::content.gallery_membership_status
      or exhibition.created_by = v_membership.user_id
    )
  order by exhibition.updated_at desc, exhibition.id;
end;
$$;

-- Launch Kit and paid-promotion state belongs to the approved owner, never a
-- claimant who is only preparing an isolated draft.
create or replace function content_private.owner_list_launch_kits_impl()
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(true);
begin
  return query
  select content_private.owner_launch_kit_json(kit.id)
  from content.launch_kits as kit
  where kit.gallery_id = v_gallery_id
  order by kit.updated_at desc, kit.id;
end;
$$;

create or replace function content_private.owner_list_local_promotions_impl()
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_gallery_id uuid := content_private.owner_assert_gallery_membership(true);
begin
  return query
  select content_private.local_promotion_json(promotion.id)
  from content.local_promotions as promotion
  where promotion.gallery_id = v_gallery_id
  order by promotion.created_at desc, promotion.id;
end;
$$;

-- Resolve all losing claims in the same transaction as approval. Their draft
-- records remain available to staff for evidence, but are quarantined from the
-- approved owner's list and mutation paths.
create or replace function content_private.quarantine_gallery_claimant_drafts(
  p_gallery_id uuid,
  p_claimant_user_id uuid,
  p_actor_id uuid,
  p_request_id uuid,
  p_reason text
)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_exhibition_id text;
  v_quarantined_count integer := 0;
  v_quarantined_at timestamptz := clock_timestamp();
begin
  if p_gallery_id is null or p_claimant_user_id is null or p_request_id is null
     or nullif(btrim(p_reason), '') is null or length(p_reason) > 100 then
    raise exception using errcode = '22023', message = 'gallery_draft_quarantine_invalid';
  end if;

  for v_exhibition_id in
    with quarantined as (
      update content.exhibitions as exhibition
      set
        owner_hidden_at = v_quarantined_at,
        owner_hidden_by = coalesce(exhibition.owner_hidden_by, p_actor_id),
        updated_by = coalesce(p_actor_id, exhibition.updated_by)
      where exhibition.gallery_id = p_gallery_id
        and exhibition.created_by = p_claimant_user_id
        and exhibition.owner_status in (
          'draft'::content.owner_exhibition_status,
          'needs_changes'::content.owner_exhibition_status
        )
        and exhibition.owner_hidden_at is null
      returning exhibition.id
    )
    select quarantined.id from quarantined order by quarantined.id
  loop
    insert into content.audit_log (
      actor_user_id, action, entity_type, entity_id, request_id, metadata
    ) values (
      p_actor_id,
      'owner_exhibition.quarantined',
      'exhibition',
      v_exhibition_id,
      p_request_id,
      jsonb_build_object(
        'gallery_id', p_gallery_id,
        'claimant_user_id', p_claimant_user_id,
        'reason', p_reason
      )
    );
    v_quarantined_count := v_quarantined_count + 1;
  end loop;

  return v_quarantined_count;
end;
$$;

create or replace function content_private.reject_competing_gallery_claims(
  p_gallery_id uuid,
  p_approved_user_id uuid,
  p_actor_id uuid,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_rejected_user_id uuid;
  v_unattributed_exhibition record;
  v_rejected_count integer := 0;
  v_hidden_count integer := 0;
  v_hidden_for_user integer;
  v_reviewed_at timestamptz := clock_timestamp();
begin
  if p_gallery_id is null or p_approved_user_id is null or p_request_id is null then
    raise exception using errcode = '22023', message = 'gallery_claim_resolution_invalid';
  end if;
  if not exists (
    select 1
    from content.gallery_memberships as membership
    where membership.gallery_id = p_gallery_id
      and membership.user_id = p_approved_user_id
      and membership.status = 'active'::content.gallery_membership_status
  ) then
    raise exception using errcode = '42501', message = 'approved_gallery_membership_required';
  end if;

  for v_rejected_user_id in
    with rejected as (
      update content.gallery_memberships as membership
      set
        status = 'rejected'::content.gallery_membership_status,
        reviewed_at = v_reviewed_at,
        reviewed_by = coalesce(p_actor_id, membership.reviewed_by),
        review_notes = 'Another claim for this gallery was approved.',
        updated_by = coalesce(p_actor_id, membership.updated_by)
      where membership.gallery_id = p_gallery_id
        and membership.user_id <> p_approved_user_id
        and membership.status = 'pending'::content.gallery_membership_status
      returning membership.user_id
    )
    select rejected.user_id from rejected order by rejected.user_id
  loop
    v_hidden_for_user := content_private.quarantine_gallery_claimant_drafts(
      p_gallery_id,
      v_rejected_user_id,
      p_actor_id,
      p_request_id,
      'competing_claim_approved'
    );
    v_hidden_count := v_hidden_count + v_hidden_for_user;

    insert into content.audit_log (
      actor_user_id, action, entity_type, entity_id, request_id, metadata
    ) values (
      p_actor_id,
      'gallery.claim_rejected',
      'gallery',
      p_gallery_id::text,
      p_request_id,
      jsonb_build_object(
        'user_id', v_rejected_user_id,
        'approved_user_id', p_approved_user_id,
        'reason', 'competing_claim_approved',
        'hidden_draft_count', v_hidden_for_user
      )
    );

    insert into content.outbox_events (
      aggregate_type, aggregate_id, event_type, payload, deduplication_key
    ) values (
      'gallery',
      p_gallery_id::text,
      'gallery.claim_rejected',
      jsonb_build_object(
        'gallery_id', p_gallery_id,
        'user_id', v_rejected_user_id,
        'approved_user_id', p_approved_user_id,
        'status', 'rejected',
        'reason', 'competing_claim_approved'
      ),
      format(
        'gallery:%s:claim:rejected:%s:%s',
        p_gallery_id,
        v_rejected_user_id,
        p_request_id
      )
    );

    v_rejected_count := v_rejected_count + 1;
  end loop;

  -- A claimant may have been explicitly rejected, revoked, or suspended before
  -- this approval. Their editable records must still be quarantined even though
  -- their membership no longer participates in the pending-row update above.
  for v_rejected_user_id in
    select membership.user_id
    from content.gallery_memberships as membership
    where membership.gallery_id = p_gallery_id
      and membership.user_id <> p_approved_user_id
      and membership.status <> 'active'::content.gallery_membership_status
    order by membership.user_id
  loop
    v_hidden_for_user := content_private.quarantine_gallery_claimant_drafts(
      p_gallery_id,
      v_rejected_user_id,
      p_actor_id,
      p_request_id,
      'non_approved_claimant'
    );
    v_hidden_count := v_hidden_count + v_hidden_for_user;
  end loop;

  -- Account deletion can remove a rejected membership and set created_by to
  -- NULL. Sweep any remaining editable record not attributed to the approved
  -- owner so orphaned claimant drafts cannot become gallery-wide active-owner
  -- state merely because their Auth identity disappeared.
  for v_unattributed_exhibition in
    with quarantined as (
      update content.exhibitions as exhibition
      set
        owner_hidden_at = v_reviewed_at,
        owner_hidden_by = coalesce(exhibition.owner_hidden_by, p_actor_id),
        updated_by = coalesce(p_actor_id, exhibition.updated_by)
      where exhibition.gallery_id = p_gallery_id
        and exhibition.created_by is distinct from p_approved_user_id
        and exhibition.owner_status in (
          'draft'::content.owner_exhibition_status,
          'needs_changes'::content.owner_exhibition_status
        )
        and exhibition.owner_hidden_at is null
      returning exhibition.id, exhibition.created_by
    )
    select quarantined.id, quarantined.created_by
    from quarantined
    order by quarantined.id
  loop
    insert into content.audit_log (
      actor_user_id, action, entity_type, entity_id, request_id, metadata
    ) values (
      p_actor_id,
      'owner_exhibition.quarantined',
      'exhibition',
      v_unattributed_exhibition.id,
      p_request_id,
      jsonb_build_object(
        'gallery_id', p_gallery_id,
        'claimant_user_id', v_unattributed_exhibition.created_by,
        'reason', 'non_approved_or_orphaned_creator'
      )
    );
    v_hidden_count := v_hidden_count + 1;
  end loop;

  return jsonb_build_object(
    'rejected_claim_count', v_rejected_count,
    'hidden_draft_count', v_hidden_count
  );
end;
$$;

-- Public claim decisions enter this serialized boundary. Locking the gallery
-- row matches claim creation's lock order, so a concurrent new claim either
-- commits before cleanup and is rejected, or observes the new active owner.
create or replace function content_private.admin_decide_gallery_claim_isolated_impl(
  p_gallery_id uuid,
  p_user_id uuid,
  p_approve boolean,
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
  v_actor_id uuid := content_private.admin_assert_staff('publisher'::content.staff_role);
  v_response jsonb;
begin
  perform 1
  from content.galleries as gallery
  where gallery.id = p_gallery_id
  for update;

  v_response := content_private.admin_decide_gallery_claim_impl(
    p_gallery_id,
    p_user_id,
    p_approve,
    p_review_notes,
    p_request_id
  );

  if p_approve then
    perform content_private.reject_competing_gallery_claims(
      p_gallery_id,
      p_user_id,
      v_actor_id,
      p_request_id
    );
  else
    perform content_private.quarantine_gallery_claimant_drafts(
      p_gallery_id,
      p_user_id,
      v_actor_id,
      p_request_id,
      'claim_rejected'
    );
  end if;

  return v_response;
end;
$$;

create or replace function public.admin_approve_gallery_claim(
  p_gallery_id uuid, p_user_id uuid, p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_decide_gallery_claim_isolated_impl(
    p_gallery_id, p_user_id, true, null, p_request_id
  );
$$;

create or replace function public.admin_reject_gallery_claim(
  p_gallery_id uuid, p_user_id uuid, p_review_notes text, p_request_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.admin_decide_gallery_claim_isolated_impl(
    p_gallery_id, p_user_id, false, p_review_notes, p_request_id
  );
$$;

-- Normalize any already-vulnerable active-plus-pending state. The request ID
-- is deterministic evidence identity for this one forward migration.
do $$
declare
  active_membership record;
begin
  for active_membership in
    select
      membership.gallery_id,
      membership.user_id,
      membership.reviewed_by
    from content.gallery_memberships as membership
    where membership.status = 'active'::content.gallery_membership_status
      and (
        exists (
          select 1
          from content.gallery_memberships as other_membership
          where other_membership.gallery_id = membership.gallery_id
            and other_membership.user_id <> membership.user_id
            and (
              other_membership.status = 'pending'::content.gallery_membership_status
              or exists (
                select 1
                from content.exhibitions as exhibition
                where exhibition.gallery_id = membership.gallery_id
                  and exhibition.created_by = other_membership.user_id
                  and exhibition.owner_status in (
                    'draft'::content.owner_exhibition_status,
                    'needs_changes'::content.owner_exhibition_status
                  )
                  and exhibition.owner_hidden_at is null
              )
            )
        )
        or exists (
          select 1
          from content.exhibitions as exhibition
          where exhibition.gallery_id = membership.gallery_id
            and exhibition.created_by is distinct from membership.user_id
            and exhibition.owner_status in (
              'draft'::content.owner_exhibition_status,
              'needs_changes'::content.owner_exhibition_status
            )
            and exhibition.owner_hidden_at is null
        )
      )
    order by membership.gallery_id
  loop
    perform 1
    from content.galleries as gallery
    where gallery.id = active_membership.gallery_id
    for update;

    perform content_private.reject_competing_gallery_claims(
      active_membership.gallery_id,
      active_membership.user_id,
      active_membership.reviewed_by,
      md5(
        'pending-gallery-claim-isolation:' ||
        active_membership.gallery_id::text || ':' ||
        active_membership.user_id::text
      )::uuid
    );
  end loop;
end;
$$;

revoke all on function
  content_private.owner_assert_gallery_membership_record(boolean),
  content_private.owner_assert_gallery_membership(boolean),
  content_private.owner_create_exhibition_draft_impl(uuid),
  content_private.owner_assert_exhibition_draft(text, uuid, integer),
  content_private.quarantine_gallery_claimant_drafts(uuid, uuid, uuid, uuid, text),
  content_private.reject_competing_gallery_claims(uuid, uuid, uuid, uuid),
  content_private.admin_decide_gallery_claim_impl(uuid, uuid, boolean, text, uuid),
  content_private.admin_decide_gallery_claim_isolated_impl(uuid, uuid, boolean, text, uuid)
from public, anon, authenticated, service_role;

grant execute on function
  content_private.owner_create_exhibition_draft_impl(uuid),
  content_private.admin_decide_gallery_claim_isolated_impl(
    uuid, uuid, boolean, text, uuid
  )
to authenticated;

revoke all on function
  public.admin_approve_gallery_claim(uuid, uuid, uuid),
  public.admin_reject_gallery_claim(uuid, uuid, text, uuid)
from public, anon, authenticated, service_role;

grant execute on function
  public.admin_approve_gallery_claim(uuid, uuid, uuid),
  public.admin_reject_gallery_claim(uuid, uuid, text, uuid)
to authenticated;

commit;
