-- Durable remote-delivery state for explicitly opted-in gallery alerts.
--
-- Provider addresses remain private. Anonymous and authenticated clients can
-- rotate only the address belonging to an installation whose high-entropy
-- secret they prove. Fan-out claim/completion APIs are service-role only.

create table content_private.gallery_alert_push_tokens (
  installation_id uuid primary key
    references content_private.gallery_alert_installations(id)
    on delete cascade,
  provider text not null,
  provider_token text not null,
  token_digest text not null,
  provider_environment text not null,
  status text not null default 'active',
  revision integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_registered_at timestamptz not null default now(),
  invalidated_at timestamptz,
  constraint gallery_alert_push_tokens_provider check (
    provider in ('apns', 'fcm')
  ),
  constraint gallery_alert_push_tokens_environment check (
    provider_environment in ('sandbox', 'production')
    and (provider <> 'fcm' or provider_environment = 'production')
  ),
  constraint gallery_alert_push_tokens_status check (
    status in ('active', 'invalid', 'disabled')
  ),
  constraint gallery_alert_push_tokens_revision check (revision >= 1),
  constraint gallery_alert_push_tokens_digest check (
    token_digest ~ '^[0-9a-f]{64}$'
  )
);

create unique index gallery_alert_push_tokens_provider_digest_idx
  on content_private.gallery_alert_push_tokens (provider, token_digest);

create table content_private.gallery_alert_delivery_jobs (
  id bigint generated always as identity primary key,
  outbox_event_id uuid not null
    references content.outbox_events(id)
    on delete cascade,
  installation_id uuid not null
    references content_private.gallery_alert_installations(id)
    on delete cascade,
  gallery_id uuid not null
    references content.galleries(id)
    on delete restrict,
  exhibition_id text not null,
  version_id uuid not null,
  gallery_name_ko text not null,
  gallery_name_en text not null,
  exhibition_name_ko text not null,
  exhibition_name_en text not null,
  deduplication_key text not null,
  status text not null default 'pending',
  attempts integer not null default 0,
  max_attempts integer not null default 5,
  available_at timestamptz not null default now(),
  lease_token uuid,
  lease_owner text,
  locked_until timestamptz,
  last_error_code text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  delivered_at timestamptz,
  dead_lettered_at timestamptz,
  unique (outbox_event_id, installation_id),
  unique (deduplication_key),
  constraint gallery_alert_delivery_jobs_status check (
    status in ('pending', 'processing', 'delivered', 'dead')
  ),
  constraint gallery_alert_delivery_jobs_attempts check (
    attempts >= 0 and max_attempts between 1 and 10
  ),
  constraint gallery_alert_delivery_jobs_lease check (
    (status = 'processing'
      and lease_token is not null
      and lease_owner is not null
      and locked_until is not null)
    or
    (status <> 'processing'
      and lease_token is null
      and lease_owner is null
      and locked_until is null)
  ),
  constraint gallery_alert_delivery_jobs_delivered check (
    status <> 'delivered' or delivered_at is not null
  ),
  constraint gallery_alert_delivery_jobs_dead check (
    status <> 'dead' or dead_lettered_at is not null
  ),
  constraint gallery_alert_delivery_jobs_error_code check (
    last_error_code is null
    or last_error_code ~ '^[a-z][a-z0-9_]{2,79}$'
  )
);

create index gallery_alert_delivery_jobs_claim_idx
  on content_private.gallery_alert_delivery_jobs (
    outbox_event_id,
    available_at,
    created_at,
    id
  )
  where status in ('pending', 'processing');

alter table content_private.gallery_alert_push_tokens enable row level security;
alter table content_private.gallery_alert_delivery_jobs enable row level security;

revoke all on table
  content_private.gallery_alert_push_tokens,
  content_private.gallery_alert_delivery_jobs
from public, anon, authenticated;

grant select, insert, update on table
  content_private.gallery_alert_push_tokens,
  content_private.gallery_alert_delivery_jobs
to service_role;

grant usage, select on sequence
  content_private.gallery_alert_delivery_jobs_id_seq
to service_role;

create or replace function content_private.gallery_alert_push_token_json(
  p_installation_id uuid
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
set timezone = 'UTC'
as $function$
  select jsonb_build_object(
    'installation_id', token.installation_id,
    'push_provider', token.provider,
    'push_environment', token.provider_environment,
    'push_token_status', token.status,
    'push_token_revision', token.revision
  )
  from content_private.gallery_alert_push_tokens as token
  where token.installation_id = p_installation_id;
$function$;

create or replace function content_private.register_gallery_alert_push_token_impl(
  p_installation_id uuid,
  p_installation_secret text,
  p_provider text,
  p_provider_token text,
  p_provider_environment text,
  p_expected_revision integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_token content_private.gallery_alert_push_tokens%rowtype;
  v_provider text := lower(btrim(coalesce(p_provider, '')));
  v_provider_token text := btrim(coalesce(p_provider_token, ''));
  v_environment text := lower(btrim(coalesce(p_provider_environment, '')));
  v_digest text;
begin
  perform content_private.assert_gallery_alert_installation(
    p_installation_id,
    p_installation_secret
  );

  if v_provider not in ('apns', 'fcm')
     or v_environment not in ('sandbox', 'production')
     or (v_provider = 'fcm' and v_environment <> 'production') then
    raise exception using
      errcode = '22023', message = 'push_provider_invalid';
  end if;
  if (
    v_provider = 'apns'
    and v_provider_token !~ '^[0-9a-fA-F]{64}$'
  ) or (
    v_provider = 'fcm'
    and (
      length(v_provider_token) not between 20 and 4096
      or v_provider_token !~ '^[A-Za-z0-9:_-]+$'
    )
  ) then
    raise exception using errcode = '22023', message = 'push_token_invalid';
  end if;
  if p_expected_revision is null or p_expected_revision < 0 then
    raise exception using
      errcode = '22023', message = 'expected_revision_invalid';
  end if;

  v_provider_token := case
    when v_provider = 'apns' then lower(v_provider_token)
    else v_provider_token
  end;
  v_digest := encode(
    extensions.digest(v_provider_token, 'sha256'),
    'hex'
  );

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      'gallery-alert:' || p_installation_id::text,
      0
    )
  );

  select token.*
  into v_token
  from content_private.gallery_alert_push_tokens as token
  where token.installation_id = p_installation_id
  for update;

  if not found then
    if p_expected_revision <> 0 then
      raise exception using errcode = '40001', message = 'revision_conflict';
    end if;
    insert into content_private.gallery_alert_push_tokens (
      installation_id,
      provider,
      provider_token,
      token_digest,
      provider_environment
    ) values (
      p_installation_id,
      v_provider,
      v_provider_token,
      v_digest,
      v_environment
    );
  elsif v_token.provider = v_provider
     and v_token.token_digest = v_digest
     and v_token.provider_environment = v_environment
     and v_token.status = 'active' then
    update content_private.gallery_alert_push_tokens
    set last_registered_at = now()
    where installation_id = p_installation_id;
    return content_private.gallery_alert_push_token_json(p_installation_id);
  else
    if p_expected_revision <> v_token.revision then
      raise exception using
        errcode = '40001',
        message = 'revision_conflict',
        detail = v_token.revision::text;
    end if;
    update content_private.gallery_alert_push_tokens
    set provider = v_provider,
        provider_token = v_provider_token,
        token_digest = v_digest,
        provider_environment = v_environment,
        status = 'active',
        revision = revision + 1,
        updated_at = now(),
        last_registered_at = now(),
        invalidated_at = null
    where installation_id = p_installation_id
      and revision = p_expected_revision;
    if not found then
      raise exception using errcode = '40001', message = 'revision_conflict';
    end if;
  end if;

  update content_private.gallery_alert_installations
  set last_seen_at = now()
  where id = p_installation_id;

  return content_private.gallery_alert_push_token_json(p_installation_id);
end;
$function$;

create or replace function public.register_gallery_alert_push_token(
  p_installation_id uuid,
  p_installation_secret text,
  p_provider text,
  p_provider_token text,
  p_provider_environment text,
  p_expected_revision integer
)
returns jsonb
language sql
volatile
security definer
set search_path = ''
as $function$
  select content_private.register_gallery_alert_push_token_impl(
    p_installation_id,
    p_installation_secret,
    p_provider,
    p_provider_token,
    p_provider_environment,
    p_expected_revision
  );
$function$;

create or replace function content_private.enrich_gallery_publication_event()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_version content.exhibition_versions%rowtype;
  v_gallery_id uuid;
begin
  if new.event_type <> 'exhibition.published'
     or new.aggregate_type <> 'exhibition'
     or not (new.payload ?& array['exhibition_id', 'version_id']) then
    return new;
  end if;
  if new.payload ?& array[
    'gallery_id',
    'gallery_name_ko',
    'gallery_name_en',
    'exhibition_name_ko',
    'exhibition_name_en'
  ] then
    return new;
  end if;

  select version.*
  into v_version
  from content.exhibition_versions as version
  where version.id = (new.payload ->> 'version_id')::uuid
    and version.exhibition_id = new.aggregate_id;

  if not found then
    raise exception using
      errcode = '23503', message = 'gallery_alert_publication_version_missing';
  end if;

  v_gallery_id := content_private.sync_gallery_from_catalog(
    v_version.venue_name_ko,
    v_version.venue_name_en
  );
  if v_gallery_id is null then
    raise exception using
      errcode = '23502', message = 'gallery_alert_publication_gallery_missing';
  end if;

  new.payload := new.payload || jsonb_build_object(
    'gallery_id', v_gallery_id,
    'gallery_name_ko', v_version.venue_name_ko,
    'gallery_name_en', v_version.venue_name_en,
    'exhibition_name_ko', v_version.name_ko,
    'exhibition_name_en', v_version.name_en
  );
  return new;
end;
$function$;

create trigger outbox_events_enrich_gallery_publication
  before insert on content.outbox_events
  for each row
  when (new.event_type = 'exhibition.published')
  execute function content_private.enrich_gallery_publication_event();

create or replace function content_private.claim_gallery_alert_delivery_jobs_impl(
  p_outbox_event_id uuid,
  p_lease_owner text,
  p_lease_seconds integer,
  p_batch_size integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_event content.outbox_events%rowtype;
  v_gallery_id uuid;
  v_version_id uuid;
  v_exhibition_id text;
  v_claimed_ids bigint[] := array[]::bigint[];
  v_jobs jsonb := '[]'::jsonb;
  v_has_more boolean := false;
begin
  if p_outbox_event_id is null
     or p_lease_owner is null
     or p_lease_owner !~ '^[A-Za-z0-9._:-]{3,100}$'
     or p_lease_seconds not between 30 and 900
     or p_batch_size not between 1 and 100 then
    raise exception using
      errcode = '22023', message = 'gallery_alert_claim_invalid';
  end if;

  select event.*
  into v_event
  from content.outbox_events as event
  where event.id = p_outbox_event_id;

  if not found
     or v_event.event_type <> 'exhibition.published'
     or v_event.aggregate_type <> 'exhibition'
     or v_event.payload ->> 'exhibition_id' is distinct from v_event.aggregate_id
     or not (v_event.payload ?& array[
       'version_id',
       'gallery_id',
       'gallery_name_ko',
       'gallery_name_en',
       'exhibition_name_ko',
       'exhibition_name_en'
     ]) then
    raise exception using
      errcode = '22023', message = 'gallery_alert_event_invalid';
  end if;

  begin
    v_gallery_id := (v_event.payload ->> 'gallery_id')::uuid;
    v_version_id := (v_event.payload ->> 'version_id')::uuid;
  exception when invalid_text_representation then
    raise exception using
      errcode = '22023', message = 'gallery_alert_event_invalid';
  end;
  v_exhibition_id := v_event.payload ->> 'exhibition_id';

  insert into content_private.gallery_alert_delivery_jobs (
    outbox_event_id,
    installation_id,
    gallery_id,
    exhibition_id,
    version_id,
    gallery_name_ko,
    gallery_name_en,
    exhibition_name_ko,
    exhibition_name_en,
    deduplication_key
  )
  select
    v_event.id,
    subscription.installation_id,
    v_gallery_id,
    v_exhibition_id,
    v_version_id,
    v_event.payload ->> 'gallery_name_ko',
    v_event.payload ->> 'gallery_name_en',
    v_event.payload ->> 'exhibition_name_ko',
    v_event.payload ->> 'exhibition_name_en',
    format(
      'gallery:%s:exhibition:%s:version:%s:installation:%s',
      v_gallery_id,
      v_exhibition_id,
      v_version_id,
      subscription.installation_id
    )
  from content_private.gallery_alert_subscriptions as subscription
  join content_private.gallery_alert_installations as installation
    on installation.id = subscription.installation_id
  join content_private.gallery_alert_push_tokens as token
    on token.installation_id = subscription.installation_id
   and token.status = 'active'
  where subscription.gallery_id = v_gallery_id
    and subscription.enabled
    and installation.last_seen_at >= now() - interval '180 days'
  on conflict (outbox_event_id, installation_id) do nothing;

  with candidate as (
    select job.id
    from content_private.gallery_alert_delivery_jobs as job
    where job.outbox_event_id = p_outbox_event_id
      and job.attempts < job.max_attempts
      and (
        (job.status = 'pending' and job.available_at <= now())
        or
        (job.status = 'processing' and job.locked_until <= now())
      )
    order by job.created_at, job.id
    for update skip locked
    limit p_batch_size
  ),
  claimed as (
    update content_private.gallery_alert_delivery_jobs as job
    set status = 'processing',
        attempts = attempts + 1,
        lease_token = extensions.gen_random_uuid(),
        lease_owner = p_lease_owner,
        locked_until = now() + make_interval(secs => p_lease_seconds),
        updated_at = now()
    from candidate
    where job.id = candidate.id
    returning job.id
  )
  select coalesce(array_agg(claimed.id order by claimed.id), array[]::bigint[])
  into v_claimed_ids
  from claimed;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'job_id', job.id,
        'lease_token', job.lease_token,
        'provider', token.provider,
        'provider_token', token.provider_token,
        'provider_environment', token.provider_environment,
        'locale', installation.locale,
        'gallery_name_ko', job.gallery_name_ko,
        'gallery_name_en', job.gallery_name_en,
        'exhibition_name_ko', job.exhibition_name_ko,
        'exhibition_name_en', job.exhibition_name_en,
        'exhibition_id', job.exhibition_id,
        'deduplication_key', job.deduplication_key
      )
      order by job.id
    ),
    '[]'::jsonb
  )
  into v_jobs
  from content_private.gallery_alert_delivery_jobs as job
  join content_private.gallery_alert_push_tokens as token
    on token.installation_id = job.installation_id
   and token.status = 'active'
  join content_private.gallery_alert_installations as installation
    on installation.id = job.installation_id
  where job.id = any(v_claimed_ids);

  select exists (
    select 1
    from content_private.gallery_alert_delivery_jobs as job
    where job.outbox_event_id = p_outbox_event_id
      and job.id <> all(v_claimed_ids)
      and job.status in ('pending', 'processing')
      and job.attempts < job.max_attempts
  )
  into v_has_more;

  return jsonb_build_object('jobs', v_jobs, 'has_more', v_has_more);
end;
$function$;

create or replace function content_private.complete_gallery_alert_delivery_job_impl(
  p_job_id bigint,
  p_lease_token uuid
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  update content_private.gallery_alert_delivery_jobs
  set status = 'delivered',
      delivered_at = now(),
      lease_token = null,
      lease_owner = null,
      locked_until = null,
      last_error_code = null,
      updated_at = now()
  where id = p_job_id
    and status = 'processing'
    and lease_token = p_lease_token
    and locked_until > now();
  if not found then
    raise exception using
      errcode = '40001', message = 'gallery_alert_lease_conflict';
  end if;
end;
$function$;

create or replace function content_private.fail_gallery_alert_delivery_job_impl(
  p_job_id bigint,
  p_lease_token uuid,
  p_error_code text,
  p_retryable boolean,
  p_invalid_token boolean
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_job content_private.gallery_alert_delivery_jobs%rowtype;
  v_dead boolean;
begin
  if p_error_code is null
     or p_error_code !~ '^[a-z][a-z0-9_]{2,79}$'
     or p_retryable is null
     or p_invalid_token is null then
    raise exception using
      errcode = '22023', message = 'gallery_alert_failure_invalid';
  end if;

  select job.*
  into v_job
  from content_private.gallery_alert_delivery_jobs as job
  where job.id = p_job_id
    and job.status = 'processing'
    and job.lease_token = p_lease_token
    and job.locked_until > now()
  for update;
  if not found then
    raise exception using
      errcode = '40001', message = 'gallery_alert_lease_conflict';
  end if;

  v_dead := p_invalid_token or not p_retryable
    or v_job.attempts >= v_job.max_attempts;

  update content_private.gallery_alert_delivery_jobs
  set status = case when v_dead then 'dead' else 'pending' end,
      available_at = case
        when v_dead then available_at
        else now() + make_interval(
          secs => least(3600, 30 * power(2, greatest(0, attempts - 1)))::integer
        )
      end,
      lease_token = null,
      lease_owner = null,
      locked_until = null,
      last_error_code = p_error_code,
      dead_lettered_at = case when v_dead then now() else null end,
      updated_at = now()
  where id = p_job_id;

  if p_invalid_token then
    update content_private.gallery_alert_push_tokens
    set status = 'invalid',
        revision = revision + 1,
        invalidated_at = now(),
        updated_at = now()
    where installation_id = v_job.installation_id
      and status = 'active';
  end if;
end;
$function$;

create or replace function public.claim_gallery_alert_delivery_jobs(
  p_outbox_event_id uuid,
  p_lease_owner text,
  p_lease_seconds integer,
  p_batch_size integer
)
returns jsonb
language sql
volatile
security definer
set search_path = ''
as $function$
  select content_private.claim_gallery_alert_delivery_jobs_impl(
    p_outbox_event_id,
    p_lease_owner,
    p_lease_seconds,
    p_batch_size
  );
$function$;

create or replace function public.complete_gallery_alert_delivery_job(
  p_job_id bigint,
  p_lease_token uuid
)
returns void
language sql
volatile
security definer
set search_path = ''
as $function$
  select content_private.complete_gallery_alert_delivery_job_impl(
    p_job_id,
    p_lease_token
  );
$function$;

create or replace function public.fail_gallery_alert_delivery_job(
  p_job_id bigint,
  p_lease_token uuid,
  p_error_code text,
  p_retryable boolean,
  p_invalid_token boolean
)
returns void
language sql
volatile
security definer
set search_path = ''
as $function$
  select content_private.fail_gallery_alert_delivery_job_impl(
    p_job_id,
    p_lease_token,
    p_error_code,
    p_retryable,
    p_invalid_token
  );
$function$;

revoke all on function
  content_private.gallery_alert_push_token_json(uuid),
  content_private.register_gallery_alert_push_token_impl(uuid, text, text, text, text, integer),
  content_private.enrich_gallery_publication_event(),
  content_private.claim_gallery_alert_delivery_jobs_impl(uuid, text, integer, integer),
  content_private.complete_gallery_alert_delivery_job_impl(bigint, uuid),
  content_private.fail_gallery_alert_delivery_job_impl(bigint, uuid, text, boolean, boolean),
  public.register_gallery_alert_push_token(uuid, text, text, text, text, integer),
  public.claim_gallery_alert_delivery_jobs(uuid, text, integer, integer),
  public.complete_gallery_alert_delivery_job(bigint, uuid),
  public.fail_gallery_alert_delivery_job(bigint, uuid, text, boolean, boolean)
from public, anon, authenticated, service_role;

grant execute on function
  public.register_gallery_alert_push_token(uuid, text, text, text, text, integer)
to anon, authenticated, service_role;

grant execute on function
  public.claim_gallery_alert_delivery_jobs(uuid, text, integer, integer),
  public.complete_gallery_alert_delivery_job(bigint, uuid),
  public.fail_gallery_alert_delivery_job(bigint, uuid, text, boolean, boolean)
to service_role;

comment on table content_private.gallery_alert_push_tokens is
  'Private current APNs/FCM address for one proven installation. Never exposed through direct client reads.';
comment on table content_private.gallery_alert_delivery_jobs is
  'Idempotent per-installation fan-out queue for exhibition publication alerts.';
