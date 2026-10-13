-- Spec 089 review follow-up: the database changes of the routes review, as their own migration. The routes
-- migrations 20261010014900 to 20261010170000 were already recorded on staging when the review fixes were written,
-- so the fixes cannot live inside those files; this migration brings every database to the same definitions.
--
--   * A save and a delete of one route id run one at a time (advisory lock), and a save re-checks the tombstone
--     under the row lock, so a save racing the owner's delete cannot slip in under the freed id (E-D12).
--   * The tombstone records the listing state: a removed route its owner deleted and saved again starts removed.
--   * Staff unlist of an unlisted route that was never approved is refused: nothing was ever shown.
--   * Index hygiene: the tombstone owner key gets an index for the account-deletion cascade, and the plain
--     route_id index on route_reports goes, since the unique per-version index already leads with route_id.
begin;

-- The owner foreign key is set null when the account is deleted; that cascade must not scan the table.
create index if not exists personal_route_tombstones_owner_idx
  on public.personal_route_tombstones (owner)
  where owner is not null;

drop index if exists public.route_reports_route_idx;

-- The tombstone keeps the listing state the route had when it was deleted. The same owner may re-save a deleted id
-- (089); when staff had removed the route, the new row starts removed, so deleting and re-saving cannot turn a
-- removal into a clean unlisted route at the same link. An account-deletion cascade records the state as well.
alter table public.personal_route_tombstones
  add column if not exists listing_state text;
alter table public.personal_route_tombstones drop constraint if exists personal_route_tombstones_listing_state;
alter table public.personal_route_tombstones add constraint personal_route_tombstones_listing_state
  check (listing_state is null or listing_state in ('unlisted', 'requested', 'approved', 'declined', 'removed'));
comment on column public.personal_route_tombstones.listing_state is
  'Listing state of the route when it was deleted; removed makes the same owner''s re-save of the id start removed.';

create or replace function content_private.record_personal_route_tombstone()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  -- Inside an account-deletion cascade the owner row is already gone; the tombstone then has no owner.
  insert into public.personal_route_tombstones (id, owner, deleted_at, listing_state)
  values (
    old.id,
    (select account.id from auth.users as account where account.id = old.owner),
    clock_timestamp(),
    old.listing_state
  )
  on conflict (id) do update
    set owner = excluded.owner, deleted_at = excluded.deleted_at, listing_state = excluded.listing_state;
  return old;
end;
$$;

revoke all on function content_private.record_personal_route_tombstone() from public, anon, authenticated;

-- A deleted id belongs to whoever deleted it; to anyone else it reads as missing, like the link it once served.
create or replace function content_private.route_id_withheld_from(p_id uuid, p_actor uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.personal_route_tombstones as tombstone
    where tombstone.id = p_id
      and tombstone.owner is distinct from p_actor
  );
$$;

revoke all on function content_private.route_id_withheld_from(uuid, uuid) from public, anon, authenticated;

-- 089 save with two additions: a change to the name or the stops of a requested or approved route of a non-editor
-- returns it to review in the same transaction (R10), and a new row under an id the same owner deleted while staff
-- had it removed starts removed (the tombstone carries the state), so only staff restore can list it again.
create or replace function content_private.save_personal_route_impl(
  p_id uuid,
  p_name text,
  p_exhibition_ids text[]
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_now timestamptz := clock_timestamp();
  v_name text := btrim(coalesce(p_name, ''));
  v_ids text[] := coalesce(p_exhibition_ids, array[]::text[]);
  v_route public.personal_routes%rowtype;
  v_recreates_removed boolean;
  v_previous_ids text[];
  v_changed boolean;
  v_resets boolean;
  v_missing_location text[] := array[]::text[];
  v_unavailable text[] := array[]::text[];
  v_stops jsonb := '[]'::jsonb;
  v_position integer := 0;
  v_id text;
  v_catalog public.exhibition_catalog_v2%rowtype;
  v_previous public.personal_route_stops%rowtype;
begin
  if v_actor is null then
    raise exception using errcode = '42501', message = 'personal_route_unauthenticated';
  end if;
  if p_id is null then
    raise exception using errcode = '22023', message = 'personal_route_id_required';
  end if;
  if char_length(v_name) not between 1 and 60 then
    raise exception using errcode = '22023', message = 'personal_route_invalid_name';
  end if;
  if cardinality(v_ids) not between 2 and 10 then
    raise exception using errcode = '22023', message = 'personal_route_invalid_stop_count';
  end if;
  if (select count(distinct id) from unnest(v_ids) as id) <> cardinality(v_ids) then
    raise exception using errcode = '22023', message = 'personal_route_duplicate_stop';
  end if;

  -- A save and a delete of one id run one at a time (E-D12): the lock comes before the tombstone check, so a save
  -- racing the owner's delete waits for it and then reads the tombstone instead of slipping in under the freed id.
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('personal-route:' || p_id::text, 0));
  if content_private.route_id_withheld_from(p_id, v_actor) then
    raise sqlstate 'PT404' using message = 'personal_route_not_found';
  end if;

  -- Under the advisory lock the tombstone is settled: a removed route the owner deleted comes back removed.
  v_recreates_removed := exists (
    select 1
    from public.personal_route_tombstones as tombstone
    where tombstone.id = p_id
      and tombstone.owner = v_actor
      and tombstone.listing_state = 'removed'
  );
  insert into public.personal_routes (id, owner, name, listing_state, listing_decided_at)
  values (
    p_id,
    v_actor,
    v_name,
    case when v_recreates_removed then 'removed' else 'unlisted' end,
    case when v_recreates_removed then v_now else null end
  )
  on conflict (id) do nothing;

  select * into v_route from public.personal_routes where id = p_id for update;
  if not found then
    -- Deleted between the insert and the lock: an account-deletion cascade takes no advisory lock.
    raise sqlstate 'PT404' using message = 'personal_route_not_found';
  end if;
  -- The row lock's fresh snapshot sees a delete that committed meanwhile; its tombstone decides again.
  if content_private.route_id_withheld_from(p_id, v_actor) then
    raise sqlstate 'PT404' using message = 'personal_route_not_found';
  end if;
  if v_route.owner <> v_actor then
    raise exception using errcode = '42501', message = 'personal_route_not_owner';
  end if;
  if v_route.revoked_at is not null then
    raise sqlstate 'PT409' using message = 'personal_route_revoked';
  end if;

  v_previous_ids := array(
    select stop.exhibition_id from public.personal_route_stops as stop
    where stop.route_id = p_id order by stop.position
  );

  foreach v_id in array v_ids loop
    select * into v_catalog from public.exhibition_catalog_v2 where id = v_id;
    if found then
      if v_catalog.latitude is null or v_catalog.longitude is null then
        v_missing_location := v_missing_location || v_id;
      else
        v_stops := v_stops || jsonb_build_object(
          'position', v_position,
          'exhibition_id', v_id,
          'name_ko', v_catalog.name_ko,
          'name_en', v_catalog.name_en,
          'venue_name_ko', v_catalog.venue_name_ko,
          'venue_name_en', v_catalog.venue_name_en,
          'latitude', v_catalog.latitude,
          'longitude', v_catalog.longitude,
          'region_ko', v_catalog.region_ko,
          'region_en', v_catalog.region_en,
          'city_ko', v_catalog.city_ko
        );
      end if;
    else
      select * into v_previous
      from public.personal_route_stops
      where route_id = p_id and exhibition_id = v_id
      limit 1;
      if found then
        v_stops := v_stops || jsonb_build_object(
          'position', v_position,
          'exhibition_id', v_id,
          'name_ko', v_previous.name_ko,
          'name_en', v_previous.name_en,
          'venue_name_ko', v_previous.venue_name_ko,
          'venue_name_en', v_previous.venue_name_en,
          'latitude', v_previous.latitude,
          'longitude', v_previous.longitude,
          'region_ko', v_previous.region_ko,
          'region_en', v_previous.region_en,
          'city_ko', v_previous.city_ko
        );
      else
        v_unavailable := v_unavailable || v_id;
      end if;
    end if;
    v_position := v_position + 1;
  end loop;

  if cardinality(v_missing_location) > 0 then
    raise exception using
      errcode = '22023',
      message = 'personal_route_missing_location',
      detail = to_jsonb(v_missing_location)::text;
  end if;
  if cardinality(v_unavailable) > 0 then
    raise exception using
      errcode = '22023',
      message = 'personal_route_unavailable_stops',
      detail = to_jsonb(v_unavailable)::text;
  end if;

  delete from public.personal_route_stops where route_id = p_id;
  insert into public.personal_route_stops (
    route_id, position, exhibition_id, name_ko, name_en, venue_name_ko, venue_name_en,
    latitude, longitude, region_ko, region_en, city_ko
  )
  select
    p_id, stop.position, stop.exhibition_id, stop.name_ko, stop.name_en, stop.venue_name_ko, stop.venue_name_en,
    stop.latitude, stop.longitude, stop.region_ko, stop.region_en, stop.city_ko
  from jsonb_to_recordset(v_stops) as stop(
    position smallint, exhibition_id text, name_ko text, name_en text, venue_name_ko text, venue_name_en text,
    latitude double precision, longitude double precision, region_ko text, region_en text, city_ko text
  );

  v_changed := v_route.name is distinct from v_name or v_previous_ids is distinct from v_ids;
  v_resets := v_changed
    and v_route.listing_state in ('requested', 'approved')
    and not content_private.is_active_route_editor(v_actor);

  update public.personal_routes
  set name = v_name,
      updated_at = clock_timestamp(),
      listing_state = case when v_resets then 'requested' else listing_state end,
      listing_requested_at = case
        when v_resets and listing_state = 'approved' then clock_timestamp()
        else listing_requested_at
      end
  where id = p_id;

  return content_private.personal_route_json(p_id);
end;
$$;

-- A revoked route is not deletable: deleting it would free its id for the same owner to recreate, and with it
-- the link staff took down. The advisory lock pairs with save_personal_route_impl: a save of the same id waits
-- for the delete to commit and then finds the tombstone.
create or replace function content_private.delete_personal_route_impl(p_id uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype;
begin
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('personal-route:' || p_id::text, 0));
  v_route := content_private.owned_route_for_update(p_id);
  if v_route.revoked_at is not null then
    raise sqlstate 'PT409' using message = 'personal_route_revoked';
  end if;
  delete from public.personal_routes where id = p_id;
end;
$$;

-- Staff take a route off the list from any state but removed, with one exception: an unlisted route that was never
-- approved (private, or requested and withdrawn) was never shown, so there is nothing to take off, and refusing the
-- call keeps a mis-click from marking a private route removed. A route withdrawn after an approval still carries
-- listing_last_approved_at and stays removable, so withdrawing first does not shield an author.
create or replace function content_private.unlist_route_impl(p_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_route public.personal_routes%rowtype := content_private.staff_route_for_update(p_id);
  v_actor uuid := auth.uid();
begin
  if v_route.listing_state = 'unlisted' and v_route.listing_last_approved_at is null then
    raise sqlstate 'PT409' using message = 'route_listing_invalid_transition';
  end if;
  if v_route.listing_state <> 'removed' then
    update public.personal_routes
    set listing_state = 'removed', listing_decided_by = v_actor,
        listing_decline_reason = null, listing_decline_note = null
    where id = p_id;
  end if;
  perform content_private.record_route_audit(
    v_actor,
    'route_unlisted',
    p_id,
    jsonb_build_object('listing_state_before', v_route.listing_state, 'listing_state_after', 'removed')
  );
  return content_private.get_route_for_moderation_impl(p_id)
    || jsonb_build_object('listing_state', 'removed');
end;
$$;

commit;
