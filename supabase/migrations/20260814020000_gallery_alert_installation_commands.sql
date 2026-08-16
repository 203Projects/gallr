-- Private installation ownership and per-gallery alert preferences.
--
-- This migration does not store provider tokens or deliver notifications.
-- Clients prove ownership with a high-entropy installation secret on every
-- read or mutation. Only a salted digest is retained.

create table content_private.gallery_alert_installations (
  id uuid primary key,
  secret_digest text not null,
  platform text not null,
  locale text not null,
  user_id uuid references auth.users(id) on delete set null,
  revision integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  constraint gallery_alert_installations_platform check (
    platform in ('android', 'ios')
  ),
  constraint gallery_alert_installations_locale check (
    length(locale) between 2 and 35
    and locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'
  ),
  constraint gallery_alert_installations_revision check (revision >= 1)
);

create table content_private.gallery_alert_subscriptions (
  installation_id uuid not null
    references content_private.gallery_alert_installations(id)
    on delete cascade,
  gallery_id uuid not null
    references content.galleries(id)
    on delete restrict,
  enabled boolean not null,
  revision integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (installation_id, gallery_id),
  constraint gallery_alert_subscriptions_revision check (revision >= 1)
);

create index gallery_alert_subscriptions_enabled_gallery_idx
  on content_private.gallery_alert_subscriptions (gallery_id, installation_id)
  where enabled;

alter table content_private.gallery_alert_installations enable row level security;
alter table content_private.gallery_alert_subscriptions enable row level security;

revoke all on table
  content_private.gallery_alert_installations,
  content_private.gallery_alert_subscriptions
from public, anon, authenticated;

grant select on table
  content_private.gallery_alert_installations,
  content_private.gallery_alert_subscriptions
to service_role;

create or replace function content_private.gallery_alert_installation_json(
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
    'installation_id', installation.id,
    'platform', installation.platform,
    'locale', installation.locale,
    'account_linked', installation.user_id is not null,
    'revision', installation.revision,
    'subscriptions', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'gallery_id', subscription.gallery_id,
            'gallery_name_ko', gallery.name_ko,
            'gallery_name_en', gallery.name_en,
            'enabled', subscription.enabled,
            'revision', subscription.revision
          )
          order by gallery.name_ko, subscription.gallery_id
        )
        from content_private.gallery_alert_subscriptions as subscription
        join content.galleries as gallery on gallery.id = subscription.gallery_id
        where subscription.installation_id = installation.id
      ),
      '[]'::jsonb
    )
  )
  from content_private.gallery_alert_installations as installation
  where installation.id = p_installation_id;
$function$;

create or replace function content_private.assert_gallery_alert_installation(
  p_installation_id uuid,
  p_installation_secret text
)
returns content_private.gallery_alert_installations
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_installation content_private.gallery_alert_installations%rowtype;
begin
  if p_installation_id is null
     or p_installation_secret is null
     or length(p_installation_secret) < 32
     or length(p_installation_secret) > 256 then
    raise exception using
      errcode = '42501',
      message = 'gallery_alert_installation_unauthorized';
  end if;

  select installation.*
  into v_installation
  from content_private.gallery_alert_installations as installation
  where installation.id = p_installation_id;

  if not found
     or extensions.crypt(
       p_installation_secret,
       v_installation.secret_digest
     ) <> v_installation.secret_digest then
    raise exception using
      errcode = '42501',
      message = 'gallery_alert_installation_unauthorized';
  end if;

  return v_installation;
end;
$function$;

create or replace function content_private.register_gallery_alert_installation_impl(
  p_installation_id uuid,
  p_installation_secret text,
  p_platform text,
  p_locale text,
  p_expected_revision integer default null
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
  v_user_id uuid := case
    when coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then null
    else (select auth.uid())
  end;
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

create or replace function content_private.get_gallery_alert_installation_impl(
  p_installation_id uuid,
  p_installation_secret text
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  perform content_private.assert_gallery_alert_installation(
    p_installation_id,
    p_installation_secret
  );
  return content_private.gallery_alert_installation_json(p_installation_id);
end;
$function$;

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

create or replace function public.set_gallery_alert_subscription(
  p_installation_id uuid,
  p_installation_secret text,
  p_gallery_id uuid,
  p_enabled boolean,
  p_expected_revision integer
)
returns jsonb
language sql
volatile
security definer
set search_path = ''
as $function$
  select content_private.set_gallery_alert_subscription_impl(
    p_installation_id,
    p_installation_secret,
    p_gallery_id,
    p_enabled,
    p_expected_revision
  );
$function$;

create or replace function public.get_gallery_alert_installation(
  p_installation_id uuid,
  p_installation_secret text
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select content_private.get_gallery_alert_installation_impl(
    p_installation_id,
    p_installation_secret
  );
$function$;

revoke all on function
  content_private.gallery_alert_installation_json(uuid),
  content_private.assert_gallery_alert_installation(uuid, text),
  content_private.register_gallery_alert_installation_impl(uuid, text, text, text, integer),
  content_private.set_gallery_alert_subscription_impl(uuid, text, uuid, boolean, integer),
  content_private.get_gallery_alert_installation_impl(uuid, text),
  public.register_gallery_alert_installation(uuid, text, text, text, integer),
  public.set_gallery_alert_subscription(uuid, text, uuid, boolean, integer),
  public.get_gallery_alert_installation(uuid, text)
from public, anon, authenticated, service_role;

grant execute on function
  public.register_gallery_alert_installation(uuid, text, text, text, integer),
  public.set_gallery_alert_subscription(uuid, text, uuid, boolean, integer),
  public.get_gallery_alert_installation(uuid, text)
to anon, authenticated, service_role;
