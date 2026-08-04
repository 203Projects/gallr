-- Make the cross-project compatibility snapshot self-healing. The original
-- receiver correctly skipped a replay when the incoming Seoul snapshot hash
-- matched the last applied hash, but it could not detect a later out-of-band
-- change to Singapore's legacy reader tables. Invalidate that remembered hash
-- whenever a mirrored target resource changes; the next outbox delivery or
-- scheduled reconciliation then reapplies the complete authoritative snapshot.

create or replace function content_private.invalidate_legacy_mobile_catalog_snapshot()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  update content_private.legacy_mobile_catalog_mirror_config as config
  set last_snapshot_sha256 = null
  where config.singleton
    and config.enabled
    and config.last_snapshot_sha256 is not null;

  return null;
end;
$function$;

revoke all on function content_private.invalidate_legacy_mobile_catalog_snapshot()
  from public, anon, authenticated, service_role;

comment on function content_private.invalidate_legacy_mobile_catalog_snapshot() is
  'Private statement trigger that makes the next Seoul snapshot replay repair any drift in a configured legacy compatibility target.';

drop trigger if exists exhibitions_invalidate_legacy_mobile_catalog_snapshot
  on public.exhibitions;
create trigger exhibitions_invalidate_legacy_mobile_catalog_snapshot
  after insert or update or delete or truncate on public.exhibitions
  for each statement
  execute function content_private.invalidate_legacy_mobile_catalog_snapshot();

drop trigger if exists events_invalidate_legacy_mobile_catalog_snapshot
  on public.events;
create trigger events_invalidate_legacy_mobile_catalog_snapshot
  after insert or update or delete or truncate on public.events
  for each statement
  execute function content_private.invalidate_legacy_mobile_catalog_snapshot();

drop trigger if exists editors_invalidate_legacy_mobile_catalog_snapshot
  on public.editors;
create trigger editors_invalidate_legacy_mobile_catalog_snapshot
  after insert or update or delete or truncate on public.editors
  for each statement
  execute function content_private.invalidate_legacy_mobile_catalog_snapshot();

-- An enabled compatibility target may already have drift that predates these
-- triggers. Force exactly one complete replay after deployment; source projects
-- remain unchanged because their target configuration is disabled.
update content_private.legacy_mobile_catalog_mirror_config as config
set last_snapshot_sha256 = null
where config.singleton
  and config.enabled
  and config.last_snapshot_sha256 is not null;
