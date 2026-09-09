begin;

-- Bound anonymous gallery-alert enrollment.
--
-- Installation identities are chosen by the caller, so any anonymous client
-- could mint unlimited installations, each costing a bcrypt hash and a durable
-- row, then subscribe them and amplify one publication into one provider job
-- per synthetic installation.
--
-- Enrollment is metered in two tiers. The `gallery-alert-enrollment` Edge
-- Function verifies the request, derives a pseudonymous source key the caller
-- cannot choose, and spends the trusted budget. Released clients through
-- 1.10.1 still call the public RPCs directly and spend a separate legacy
-- budget, so abuse on the legacy path cannot starve the trusted path.
-- Subscriptions per installation and delivery jobs per publication carry
-- their own ceilings.

create table if not exists content_private.gallery_alert_enrollment_quotas (
  scope text not null,
  quota_key text not null,
  window_start timestamptz not null,
  hits integer not null default 1,
  updated_at timestamptz not null default now(),
  primary key (scope, quota_key, window_start),
  constraint gallery_alert_enrollment_quota_scope check (
    scope in ('source', 'trusted_total', 'legacy_total')
  ),
  constraint gallery_alert_enrollment_quota_key_format check (
    quota_key ~ '^[0-9a-f]{64}$'
  ),
  constraint gallery_alert_enrollment_quota_hits_positive check (hits > 0)
);

create index if not exists gallery_alert_enrollment_quotas_window_idx
  on content_private.gallery_alert_enrollment_quotas (window_start);

alter table content_private.gallery_alert_enrollment_quotas
  enable row level security;

revoke all on table content_private.gallery_alert_enrollment_quotas
  from public, anon, authenticated;

grant select on table content_private.gallery_alert_enrollment_quotas
  to service_role;

-- Single source of truth for every enrollment ceiling.
--
-- `source_hourly` is deliberately well above one real device per hour because
-- Korean mobile carriers place many subscribers behind one address; it bounds a
-- single source without breaking shared egress. The totals bound durable growth
-- per hour. `installation_subscriptions` bounds rows per installation, and
-- `publication_fanout` bounds provider jobs created for one publication.
create or replace function content_private.gallery_alert_enrollment_limits()
returns jsonb
language sql
immutable
set search_path = ''
as $function$
  select jsonb_build_object(
    'source_hourly', 30,
    'trusted_total_hourly', 5000,
    'legacy_total_hourly', 1000,
    'installation_subscriptions', 200,
    'publication_fanout', 50000
  );
$function$;

-- Normalize any state created before the ceilings existed. Keep enabled and
-- recently changed preferences first, then remove the oldest excess rows.
with ranked_subscription as (
  select
    subscription.installation_id,
    subscription.gallery_id,
    row_number() over (
      partition by subscription.installation_id
      order by
        subscription.enabled desc,
        subscription.updated_at desc,
        subscription.gallery_id
    ) as position
  from content_private.gallery_alert_subscriptions as subscription
), excess_subscription as (
  select installation_id, gallery_id
  from ranked_subscription
  where position > (
    content_private.gallery_alert_enrollment_limits()
      ->> 'installation_subscriptions'
  )::integer
)
delete from content_private.gallery_alert_subscriptions as subscription
using excess_subscription as excess
where subscription.installation_id = excess.installation_id
  and subscription.gallery_id = excess.gallery_id;

-- Preserve completed/dead evidence first and remove only excess work that a
-- worker could still deliver. An event that already consumed the full budget
-- before this migration cannot enqueue or claim additional provider work.
with ranked_job as (
  select
    job.id,
    job.status,
    row_number() over (
      partition by job.outbox_event_id
      order by
        case job.status
          when 'delivered' then 0
          when 'dead' then 1
          else 2
        end,
        job.created_at,
        job.id
    ) as position
  from content_private.gallery_alert_delivery_jobs as job
), excess_job as (
  select id
  from ranked_job
  where position > (
    content_private.gallery_alert_enrollment_limits()
      ->> 'publication_fanout'
  )::integer
    and status in ('pending', 'processing')
)
delete from content_private.gallery_alert_delivery_jobs as job
using excess_job as excess
where job.id = excess.id;

-- Fixed key for the aggregate scopes; the table keeps one 64-hex key format.
create or replace function content_private.gallery_alert_total_quota_key()
returns text
language sql
immutable
set search_path = ''
as $function$
  select repeat('0', 64);
$function$;

-- Atomic hourly counter. The upsert increments and returns under one row lock,
-- so concurrent callers cannot both observe a below-limit count.
create or replace function content_private.consume_gallery_alert_enrollment_quota(
  p_scope text,
  p_quota_key text,
  p_limit integer
)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_window timestamptz := date_trunc('hour', now());
  v_hits integer;
begin
  if p_scope is null
     or p_quota_key is null
     or p_quota_key !~ '^[0-9a-f]{64}$'
     or p_limit is null
     or p_limit < 1 then
    raise exception using
      errcode = '22023', message = 'gallery_alert_quota_invalid';
  end if;

  insert into content_private.gallery_alert_enrollment_quotas (
    scope, quota_key, window_start, hits
  ) values (p_scope, p_quota_key, v_window, 1)
  on conflict (scope, quota_key, window_start) do update
  set hits = content_private.gallery_alert_enrollment_quotas.hits + 1,
      updated_at = now()
  returning hits into v_hits;

  if v_hits > p_limit then
    raise exception using
      errcode = 'P0001', message = 'gallery_alert_rate_limited';
  end if;

  return v_hits;
end;
$function$;

-- Spend one enrollment budget. A trusted caller carries a server-derived source
-- key and is metered per source and against the trusted total; the legacy direct
-- RPC path has no trustworthy source key, so it is metered only in aggregate.
create or replace function content_private.consume_gallery_alert_enrollment_budget(
  p_source_digest text,
  p_trusted boolean
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_limits jsonb := content_private.gallery_alert_enrollment_limits();
begin
  if coalesce(p_trusted, false) then
    if p_source_digest is null or p_source_digest !~ '^[0-9a-f]{64}$' then
      raise exception using
        errcode = '22023', message = 'gallery_alert_source_digest_invalid';
    end if;

    perform content_private.consume_gallery_alert_enrollment_quota(
      'source',
      p_source_digest,
      (v_limits ->> 'source_hourly')::integer
    );
    perform content_private.consume_gallery_alert_enrollment_quota(
      'trusted_total',
      content_private.gallery_alert_total_quota_key(),
      (v_limits ->> 'trusted_total_hourly')::integer
    );
    return;
  end if;

  perform content_private.consume_gallery_alert_enrollment_quota(
    'legacy_total',
    content_private.gallery_alert_total_quota_key(),
    (v_limits ->> 'legacy_total_hourly')::integer
  );
end;
$function$;

-- The added parameters change the signature, so the previous overload is
-- dropped to keep the five-argument public wrapper unambiguous.
drop function if exists content_private.register_gallery_alert_installation_impl(
  uuid, text, text, text, integer
);

create or replace function content_private.register_gallery_alert_installation_impl(
  p_installation_id uuid,
  p_installation_secret text,
  p_platform text,
  p_locale text,
  p_expected_revision integer default null,
  p_source_digest text default null,
  p_trusted boolean default false,
  p_actor_user_id uuid default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_installation content_private.gallery_alert_installations%rowtype;
  v_platform text := lower(btrim(coalesce(p_platform, '')));
  v_locale text := replace(btrim(coalesce(p_locale, '')), '_', '-');
  v_user_id uuid := coalesce(
    p_actor_user_id,
    case
      when coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then null
      else (select auth.uid())
    end
  );
  v_target_user_id uuid;
begin
  if p_installation_id is null then
    raise exception using errcode = '22023', message = 'installation_id_required';
  end if;
  if p_installation_secret is null
     or length(p_installation_secret) < 32
     or length(p_installation_secret) > 256 then
    raise exception using
      errcode = '22023', message = 'installation_secret_invalid';
  end if;
  if v_platform not in ('android', 'ios') then
    raise exception using errcode = '22023', message = 'platform_invalid';
  end if;
  if length(v_locale) not between 2 and 35
     or v_locale !~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$' then
    raise exception using errcode = '22023', message = 'locale_invalid';
  end if;
  if p_expected_revision is not null and p_expected_revision < 0 then
    raise exception using
      errcode = '22023', message = 'expected_revision_invalid';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('gallery-alert:' || p_installation_id::text, 0)
  );

  select installation.*
  into v_installation
  from content_private.gallery_alert_installations as installation
  where installation.id = p_installation_id
  for update;

  if not found then
    if p_expected_revision is not null and p_expected_revision <> 0 then
      raise exception using errcode = '40001', message = 'revision_conflict';
    end if;

    -- Only the creation of a durable row is metered. A returning device
    -- refreshing its own installation must never be rate limited.
    perform content_private.consume_gallery_alert_enrollment_budget(
      p_source_digest,
      p_trusted
    );

    insert into content_private.gallery_alert_installations (
      id,
      secret_digest,
      platform,
      locale,
      user_id
    ) values (
      p_installation_id,
      extensions.crypt(
        p_installation_secret,
        extensions.gen_salt('bf', 8)
      ),
      v_platform,
      v_locale,
      v_user_id
    );
  else
    if extensions.crypt(
      p_installation_secret,
      v_installation.secret_digest
    ) <> v_installation.secret_digest then
      raise exception using
        errcode = '42501',
        message = 'gallery_alert_installation_unauthorized';
    end if;

    if v_installation.user_id is not null
       and v_user_id is not null
       and v_installation.user_id <> v_user_id then
      raise exception using
        errcode = '42501', message = 'installation_account_conflict';
    end if;
    v_target_user_id := coalesce(v_installation.user_id, v_user_id);

    if v_installation.platform = v_platform
       and v_installation.locale = v_locale
       and v_installation.user_id is not distinct from v_target_user_id then
      update content_private.gallery_alert_installations
      set last_seen_at = now()
      where id = p_installation_id;
      return content_private.gallery_alert_installation_json(p_installation_id);
    end if;

    if p_expected_revision is null
       or p_expected_revision <> v_installation.revision then
      raise exception using
        errcode = '40001',
        message = 'revision_conflict',
        detail = v_installation.revision::text;
    end if;

    update content_private.gallery_alert_installations
    set platform = v_platform,
        locale = v_locale,
        user_id = v_target_user_id,
        revision = revision + 1,
        updated_at = now(),
        last_seen_at = now()
    where id = p_installation_id
      and revision = p_expected_revision;

    if not found then
      raise exception using errcode = '40001', message = 'revision_conflict';
    end if;
  end if;

  return content_private.gallery_alert_installation_json(p_installation_id);
end;
$function$;
create or replace function content_private.set_gallery_alert_subscription_impl(
  p_installation_id uuid,
  p_installation_secret text,
  p_gallery_id uuid,
  p_enabled boolean,
  p_expected_revision integer
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_subscription content_private.gallery_alert_subscriptions%rowtype;
  v_subscription_count integer;
begin
  if p_gallery_id is null or p_enabled is null then
    raise exception using
      errcode = '22023', message = 'gallery_alert_subscription_invalid';
  end if;
  if p_expected_revision is null or p_expected_revision < 0 then
    raise exception using
      errcode = '22023', message = 'expected_revision_invalid';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      'gallery-alert:' || p_installation_id::text,
      0
    )
  );
  perform content_private.assert_gallery_alert_installation(
    p_installation_id,
    p_installation_secret
  );

  if not exists (
    select 1
    from content.galleries as gallery
    where gallery.id = p_gallery_id
      and gallery.status = 'active'::content.gallery_status
  ) then
    raise exception using errcode = '22023', message = 'gallery_not_alertable';
  end if;

  select subscription.*
  into v_subscription
  from content_private.gallery_alert_subscriptions as subscription
  where subscription.installation_id = p_installation_id
    and subscription.gallery_id = p_gallery_id
  for update;

  if not found then
    if p_expected_revision <> 0 then
      raise exception using errcode = '40001', message = 'revision_conflict';
    end if;

    -- Bound durable rows per installation. Disabling a gallery still stores a
    -- row, so the ceiling covers every subscription an installation owns.
    select count(*)
    into v_subscription_count
    from content_private.gallery_alert_subscriptions as subscription
    where subscription.installation_id = p_installation_id;

    if v_subscription_count >= (
      content_private.gallery_alert_enrollment_limits()
        ->> 'installation_subscriptions'
    )::integer then
      raise exception using
        errcode = 'P0001',
        message = 'gallery_alert_subscription_limit_reached';
    end if;

    insert into content_private.gallery_alert_subscriptions (
      installation_id,
      gallery_id,
      enabled
    ) values (
      p_installation_id,
      p_gallery_id,
      p_enabled
    );
  elsif v_subscription.enabled = p_enabled then
    update content_private.gallery_alert_installations
    set last_seen_at = now()
    where id = p_installation_id;
    return content_private.gallery_alert_installation_json(p_installation_id);
  else
    if p_expected_revision <> v_subscription.revision then
      raise exception using
        errcode = '40001',
        message = 'revision_conflict',
        detail = v_subscription.revision::text;
    end if;
    update content_private.gallery_alert_subscriptions
    set enabled = p_enabled,
        revision = revision + 1,
        updated_at = now()
    where installation_id = p_installation_id
      and gallery_id = p_gallery_id
      and revision = p_expected_revision;
    if not found then
      raise exception using errcode = '40001', message = 'revision_conflict';
    end if;
  end if;

  update content_private.gallery_alert_installations
  set last_seen_at = now()
  where id = p_installation_id;

  return content_private.gallery_alert_installation_json(p_installation_id);
end;
$function$;
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
  v_fanout_limit integer := (
    content_private.gallery_alert_enrollment_limits()
      ->> 'publication_fanout'
  )::integer;
  v_existing_jobs integer;
begin
  if p_outbox_event_id is null
     or p_lease_owner is null
     or p_lease_owner !~ '^[A-Za-z0-9._:-]{3,100}$'
     or p_lease_seconds not between 30 and 900
     or p_batch_size not between 1 and 100 then
    raise exception using
      errcode = '22023', message = 'gallery_alert_claim_invalid';
  end if;

  -- Serialize materialization for one publication. Without this lock two
  -- callers can count the same committed rows and insert disjoint candidate
  -- sets above the ceiling.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      'gallery-alert-fanout:' || p_outbox_event_id::text,
      0
    )
  );

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

  select count(*)
  into v_existing_jobs
  from content_private.gallery_alert_delivery_jobs as job
  where job.outbox_event_id = p_outbox_event_id;

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
  order by installation.last_seen_at desc, subscription.installation_id
  limit greatest(0, v_fanout_limit - v_existing_jobs)
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


-- Recreated so no cached plan can still reference the dropped implementation.
-- The signature and grants are unchanged, so released clients through 1.10.1 keep
-- working; their traffic is now metered against the legacy budget.
create or replace function public.register_gallery_alert_installation(
  p_installation_id uuid,
  p_installation_secret text,
  p_platform text,
  p_locale text,
  p_expected_revision integer default null
)
returns jsonb
language sql
volatile
security definer
set search_path = ''
as $function$
  select content_private.register_gallery_alert_installation_impl(
    p_installation_id,
    p_installation_secret,
    p_platform,
    p_locale,
    p_expected_revision
  );
$function$;

-- Trusted enrollment entry point for the `gallery-alert-enrollment` Edge
-- Function. The function verifies the bearer token itself and passes the
-- resolved account, because the service role carries no `auth.uid()`.
create or replace function public.service_register_gallery_alert_installation(
  p_source_digest text,
  p_installation_id uuid,
  p_installation_secret text,
  p_platform text,
  p_locale text,
  p_expected_revision integer,
  p_actor_user_id uuid default null
)
returns jsonb
language sql
volatile
security definer
set search_path = ''
as $function$
  select content_private.register_gallery_alert_installation_impl(
    p_installation_id,
    p_installation_secret,
    p_platform,
    p_locale,
    p_expected_revision,
    p_source_digest,
    true,
    p_actor_user_id
  );
$function$;

revoke all on function
  content_private.gallery_alert_enrollment_limits(),
  content_private.gallery_alert_total_quota_key(),
  content_private.consume_gallery_alert_enrollment_quota(text, text, integer),
  content_private.consume_gallery_alert_enrollment_budget(text, boolean),
  content_private.register_gallery_alert_installation_impl(
    uuid, text, text, text, integer, text, boolean, uuid
  ),
  content_private.set_gallery_alert_subscription_impl(
    uuid, text, uuid, boolean, integer
  ),
  content_private.claim_gallery_alert_delivery_jobs_impl(
    uuid, text, integer, integer
  )
from public, anon, authenticated, service_role;

revoke all on function
  public.service_register_gallery_alert_installation(
    text, uuid, text, text, text, integer, uuid
  )
from public, anon, authenticated, service_role;

grant execute on function
  public.service_register_gallery_alert_installation(
    text, uuid, text, text, text, integer, uuid
  )
to service_role;

commit;
