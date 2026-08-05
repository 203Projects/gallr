-- Keep installed pre-1.7.7 clients on the Singapore compatibility catalogue
-- current within seconds of an Admin write. The durable outbox delivery and
-- five-minute reconciliation remain independent fallbacks.

create or replace function content_private.invoke_legacy_catalog_mirror(
  p_source text
)
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_source_enabled boolean;
  v_mirror_url text;
  v_mirror_token text;
  v_request_id bigint;
begin
  if p_source not in ('outbox', 'five-minute-reconciliation') then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_mirror_source_is_invalid';
  end if;

  select config.source_outbox_enabled
  into strict v_source_enabled
  from content_private.legacy_mobile_catalog_mirror_config as config
  where config.singleton;

  if not v_source_enabled then
    raise exception using
      errcode = '55000',
      message = 'legacy_mobile_catalog_source_disabled';
  end if;

  select secret.decrypted_secret
  into v_mirror_url
  from vault.decrypted_secrets as secret
  where secret.name = 'gallr_legacy_catalog_mirror_url';

  select secret.decrypted_secret
  into v_mirror_token
  from vault.decrypted_secrets as secret
  where secret.name = 'gallr_legacy_catalog_mirror_token';

  v_mirror_url := btrim(v_mirror_url);
  v_mirror_token := btrim(v_mirror_token);

  if v_mirror_url is null or v_mirror_token is null then
    raise exception using
      errcode = '55000',
      message = 'legacy_mobile_catalog_schedule_not_configured';
  end if;
  if v_mirror_url <>
      'https://oqrvbstopuppznxqoonp.supabase.co/functions/v1/legacy-catalog-mirror' then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_mirror_url_is_invalid';
  end if;
  if length(v_mirror_token) < 32
      or v_mirror_token ~ '[[:space:][:cntrl:]]' then
    raise exception using
      errcode = '22023',
      message = 'legacy_mobile_catalog_mirror_token_is_invalid';
  end if;

  select net.http_post(
    url := v_mirror_url,
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || v_mirror_token
    ),
    body := jsonb_build_object(
      'source', p_source,
      'requested_at', clock_timestamp()
    ),
    timeout_milliseconds := 30000
  )
  into v_request_id;

  return v_request_id;
end;
$function$;

revoke all on function content_private.invoke_legacy_catalog_mirror(text)
  from public, anon, authenticated, service_role;

comment on function content_private.invoke_legacy_catalog_mirror(text) is
  'Database-owner-only asynchronous Seoul catalogue mirror request with a validated internal source.';

create or replace function content_private.invoke_legacy_catalog_mirror()
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  return content_private.invoke_legacy_catalog_mirror(
    'five-minute-reconciliation'
  );
end;
$function$;

revoke all on function content_private.invoke_legacy_catalog_mirror()
  from public, anon, authenticated, service_role;

comment on function content_private.invoke_legacy_catalog_mirror() is
  'Database-owner-only five-minute Seoul catalogue reconciliation entry point.';

create or replace function content_private.enqueue_legacy_mobile_catalog_sync()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_enabled boolean;
  v_enqueued integer;
begin
  select config.source_outbox_enabled
  into strict v_enabled
  from content_private.legacy_mobile_catalog_mirror_config as config
  where config.singleton;

  if not v_enabled then
    return null;
  end if;

  insert into content.outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    deduplication_key
  ) values (
    'public_catalogue',
    'public-catalogue',
    'legacy_catalog.sync_requested',
    jsonb_build_object('source', 'database_catalog_trigger'),
    format('legacy_catalog:sync_requested:%s', pg_catalog.txid_current())
  )
  on conflict (deduplication_key) do nothing;

  get diagnostics v_enqueued = row_count;

  -- Row triggers may fire many times in one publishing transaction. Only the
  -- trigger that inserted the transaction-deduplicated outbox event requests
  -- the immediate asynchronous delivery.
  if v_enqueued = 1 then
    begin
      perform content_private.invoke_legacy_catalog_mirror('outbox');
    exception when others then
      -- Never fail an Admin publish because the fast path is unavailable. The
      -- outbox event above retries durably and Cron still reconciles every five
      -- minutes. Log only the SQLSTATE, never a credential or request payload.
      raise log 'legacy_catalog_immediate_sync_deferred sqlstate=%', sqlstate;
    end;
  end if;

  return null;
end;
$function$;

revoke all on function content_private.enqueue_legacy_mobile_catalog_sync()
  from public, anon, authenticated, service_role;

comment on function content_private.enqueue_legacy_mobile_catalog_sync() is
  'Enqueues one durable catalogue sync per transaction and requests the same sync immediately without blocking Admin writes.';

create or replace function content_private.invalidate_legacy_mobile_catalog_snapshot()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  -- The receiver opens this private backend-scoped context around its own DML.
  -- Those writes establish the authoritative snapshot and must not invalidate
  -- its marker. Any other target write still clears the marker so the next
  -- reconciliation repairs drift.
  if exists (
    select 1
    from content_private.exhibition_catalog_legacy_write_context as context
    where context.backend_pid = pg_catalog.pg_backend_pid()
  ) then
    return null;
  end if;

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
  'Invalidates the compatibility snapshot after target drift while preserving the marker during mirror-owned writes.';
