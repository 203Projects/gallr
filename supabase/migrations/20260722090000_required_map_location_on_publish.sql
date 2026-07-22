-- Map consumers require every newly published canonical version to have a
-- usable Korean address and a complete WGS-84 coordinate pair. Keep drafts
-- permissive so staff can save work incrementally. This applies to UPDATE
-- transitions into published and later location edits on published rows; the
-- legacy migration bridge can still insert historical snapshots directly.

-- An address and its coordinates form one logical value. Partial RPC patches
-- are intentionally supported, so an address-only edit must invalidate the
-- old pair instead of silently moving those coordinates to a different place.
-- A caller may replace the address and a genuinely changed pair atomically;
-- coordinate-only manual corrections remain valid.
create or replace function content_private.invalidate_stale_map_coordinates()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if old.address_ko is distinct from new.address_ko
     and (
       new.latitude is not distinct from old.latitude
       and new.longitude is not distinct from old.longitude
     ) then
    new.latitude := null;
    new.longitude := null;
  end if;

  return new;
end;
$$;

revoke all on function content_private.invalidate_stale_map_coordinates()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_invalidate_stale_map_coordinates
  on content.exhibition_versions;
create trigger exhibition_versions_invalidate_stale_map_coordinates
  before update of address_ko, latitude, longitude
  on content.exhibition_versions
  for each row
  execute function content_private.invalidate_stale_map_coordinates();

create or replace function content_private.require_map_location_on_publish()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.status = 'published'::content.exhibition_version_status then
    if nullif(btrim(new.address_ko), '') is null then
      raise exception using
        errcode = '23514',
        message = 'address_ko_is_required_for_publication';
    end if;

    if new.latitude is null or new.longitude is null then
      raise exception using
        errcode = '23514',
        message = 'map_coordinates_are_required_for_publication';
    end if;
  end if;

  return new;
end;
$$;

revoke all on function content_private.require_map_location_on_publish()
  from public, anon, authenticated, service_role;

drop trigger if exists exhibition_versions_require_map_location_on_publish
  on content.exhibition_versions;
create trigger exhibition_versions_require_map_location_on_publish
  before update of status, address_ko, latitude, longitude
  on content.exhibition_versions
  for each row
  execute function content_private.require_map_location_on_publish();
