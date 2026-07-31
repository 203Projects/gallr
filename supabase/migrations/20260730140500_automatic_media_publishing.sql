-- Provide an inert, database-owned entry point for Supabase Cron to invoke the
-- existing media-only outbox worker. Environment activation is intentionally
-- separate: applying this migration does not create a Cron job or process an
-- outbox event.

create extension if not exists pg_net with schema extensions;
create extension if not exists supabase_vault with schema vault;

create or replace function content_private.invoke_media_outbox_worker()
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_worker_url text;
  v_worker_token text;
  v_request_id bigint;
begin
  select decrypted_secret
  into v_worker_url
  from vault.decrypted_secrets
  where name = 'gallr_outbox_worker_url';

  select decrypted_secret
  into v_worker_token
  from vault.decrypted_secrets
  where name = 'gallr_outbox_worker_token';

  v_worker_url := btrim(v_worker_url);
  v_worker_token := btrim(v_worker_token);

  if v_worker_url is null or v_worker_token is null then
    raise exception using
      errcode = '55000',
      message = 'media_worker_schedule_not_configured';
  end if;

  if v_worker_url !~ '^https://[a-z0-9]+[.]supabase[.]co/functions/v1/outbox-worker$' then
    raise exception using
      errcode = '22023',
      message = 'media_worker_url_is_invalid';
  end if;

  if length(v_worker_token) < 32
     or v_worker_token ~ '[[:space:][:cntrl:]]' then
    raise exception using
      errcode = '22023',
      message = 'media_worker_token_is_invalid';
  end if;

  select net.http_post(
    url := v_worker_url,
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || v_worker_token
    ),
    body := jsonb_build_object(
      'source', 'supabase-cron',
      'requested_at', clock_timestamp()
    ),
    timeout_milliseconds := 10000
  )
  into v_request_id;

  return v_request_id;
end;
$$;

revoke all on function content_private.invoke_media_outbox_worker()
  from public, anon, authenticated, service_role;

comment on function content_private.invoke_media_outbox_worker() is
  'Queues one authenticated invocation of the media-only outbox worker using Vault configuration.';
