-- Private account-backed My Gallr archive with ordered, idempotent mutations.

create table content_private.my_gallr_archives (
  user_id uuid primary key references auth.users(id) on delete cascade,
  revision bigint not null default 0 check (revision >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table content_private.my_gallr_visits (
  user_id uuid not null references auth.users(id) on delete cascade,
  exhibition_id text not null,
  client_record_id text not null,
  name_ko text not null,
  name_en text not null,
  venue_name_ko text not null,
  venue_name_en text not null,
  opening_date date not null,
  closing_date date not null,
  cover_image_url text,
  visited_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, exhibition_id),
  constraint my_gallr_visits_exhibition_id check (length(exhibition_id) between 1 and 200),
  constraint my_gallr_visits_client_record_id check (length(client_record_id) between 1 and 200),
  constraint my_gallr_visits_names check (
    length(name_ko) between 1 and 500
    and length(name_en) <= 500
    and length(venue_name_ko) between 1 and 500
    and length(venue_name_en) <= 500
  ),
  constraint my_gallr_visits_dates check (closing_date >= opening_date),
  constraint my_gallr_visits_cover_url check (
    cover_image_url is null
    or (length(cover_image_url) <= 2000 and cover_image_url ~ '^https://')
  )
);

create table content_private.my_gallr_followed_galleries (
  user_id uuid not null references auth.users(id) on delete cascade,
  gallery_key text not null,
  gallery_id uuid references content.galleries(id) on delete restrict,
  name_ko text not null,
  name_en text not null,
  city_ko text not null,
  city_en text not null,
  region_ko text not null,
  region_en text not null,
  known_exhibition_ids text[] not null default '{}',
  followed_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, gallery_key),
  constraint my_gallr_followed_gallery_key check (length(gallery_key) between 1 and 1000),
  constraint my_gallr_followed_gallery_names check (
    length(name_ko) between 1 and 500
    and length(name_en) <= 500
    and length(city_ko) <= 200
    and length(city_en) <= 200
    and length(region_ko) <= 200
    and length(region_en) <= 200
  ),
  constraint my_gallr_followed_gallery_known_ids check (
    cardinality(known_exhibition_ids) <= 500
  )
);

create unique index my_gallr_followed_galleries_user_gallery_id_idx
  on content_private.my_gallr_followed_galleries (user_id, gallery_id)
  where gallery_id is not null;

create index my_gallr_followed_galleries_gallery_id_idx
  on content_private.my_gallr_followed_galleries (gallery_id)
  where gallery_id is not null;

create table content_private.my_gallr_mutation_receipts (
  user_id uuid not null references auth.users(id) on delete cascade,
  mutation_id uuid not null,
  request_hash text not null,
  applied_revision bigint not null check (applied_revision >= 1),
  created_at timestamptz not null default now(),
  primary key (user_id, mutation_id)
);

alter table content_private.my_gallr_archives enable row level security;
alter table content_private.my_gallr_visits enable row level security;
alter table content_private.my_gallr_followed_galleries enable row level security;
alter table content_private.my_gallr_mutation_receipts enable row level security;

revoke all on table
  content_private.my_gallr_archives,
  content_private.my_gallr_visits,
  content_private.my_gallr_followed_galleries,
  content_private.my_gallr_mutation_receipts
from public, anon, authenticated;

grant select on table
  content_private.my_gallr_archives,
  content_private.my_gallr_visits,
  content_private.my_gallr_followed_galleries,
  content_private.my_gallr_mutation_receipts
to service_role;

create or replace function content_private.my_gallr_archive_json(p_user_id uuid)
returns jsonb
language sql
stable
security invoker
set search_path = ''
set timezone = 'UTC'
as $function$
  select jsonb_build_object(
    'revision', archive.revision,
    'visits', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'client_record_id', visit.client_record_id,
            'exhibition_id', visit.exhibition_id,
            'snapshot', jsonb_build_object(
              'name_ko', visit.name_ko,
              'name_en', visit.name_en,
              'venue_name_ko', visit.venue_name_ko,
              'venue_name_en', visit.venue_name_en,
              'opening_date', visit.opening_date,
              'closing_date', visit.closing_date,
              'cover_image_url', visit.cover_image_url
            ),
            'created_at', visit.visited_at
          ) order by visit.visited_at desc, visit.exhibition_id
        )
        from content_private.my_gallr_visits as visit
        where visit.user_id = archive.user_id
      ),
      '[]'::jsonb
    ),
    'followed_galleries', coalesce(
      (
        select jsonb_agg(
          jsonb_build_object(
            'gallery_key', gallery.gallery_key,
            'gallery_id', gallery.gallery_id,
            'snapshot', jsonb_build_object(
              'name_ko', gallery.name_ko,
              'name_en', gallery.name_en,
              'city_ko', gallery.city_ko,
              'city_en', gallery.city_en,
              'region_ko', gallery.region_ko,
              'region_en', gallery.region_en
            ),
            'known_exhibition_ids', gallery.known_exhibition_ids,
            'followed_at', gallery.followed_at
          ) order by gallery.followed_at desc, gallery.gallery_key
        )
        from content_private.my_gallr_followed_galleries as gallery
        where gallery.user_id = archive.user_id
      ),
      '[]'::jsonb
    )
  )
  from content_private.my_gallr_archives as archive
  where archive.user_id = p_user_id;
$function$;

create or replace function content_private.sync_my_gallr_archive_impl(
  p_mutations jsonb default '[]'::jsonb
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := auth.uid();
  v_mutation jsonb;
  v_mutation_id uuid;
  v_kind text;
  v_request_hash text;
  v_existing_hash text;
  v_revision bigint;
  v_record jsonb;
  v_snapshot jsonb;
  v_gallery_id uuid;
  v_known_ids text[];
begin
  if v_user_id is null
     or coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if p_mutations is null or jsonb_typeof(p_mutations) <> 'array' then
    raise exception using errcode = '22023', message = 'mutations_invalid';
  end if;
  if jsonb_array_length(p_mutations) > 100 then
    raise exception using errcode = '22023', message = 'too_many_mutations';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('my-gallr:' || v_user_id::text, 0)
  );
  insert into content_private.my_gallr_archives (user_id)
  values (v_user_id)
  on conflict (user_id) do nothing;

  for v_mutation in select value from jsonb_array_elements(p_mutations) loop
    if jsonb_typeof(v_mutation) <> 'object' then
      raise exception using errcode = '22023', message = 'mutation_invalid';
    end if;
    begin
      v_mutation_id := (v_mutation ->> 'mutation_id')::uuid;
    exception when others then
      raise exception using errcode = '22023', message = 'mutation_id_invalid';
    end;
    v_kind := v_mutation ->> 'kind';
    if v_kind not in (
      'add_visit', 'remove_visit', 'follow_gallery',
      'unfollow_gallery', 'acknowledge_gallery'
    ) then
      raise exception using errcode = '22023', message = 'mutation_kind_invalid';
    end if;
    v_request_hash := encode(extensions.digest(v_mutation::text, 'sha256'), 'hex');

    select receipt.request_hash
    into v_existing_hash
    from content_private.my_gallr_mutation_receipts as receipt
    where receipt.user_id = v_user_id
      and receipt.mutation_id = v_mutation_id;
    if found then
      if v_existing_hash <> v_request_hash then
        raise exception using errcode = '22023', message = 'mutation_id_conflict';
      end if;
      continue;
    end if;

    v_record := coalesce(v_mutation -> 'record', '{}'::jsonb);
    v_snapshot := coalesce(v_record -> 'snapshot', '{}'::jsonb);

    if v_kind = 'add_visit' then
      if length(coalesce(v_record ->> 'exhibition_id', '')) not between 1 and 200
         or length(coalesce(v_record ->> 'client_record_id', '')) not between 1 and 200
         or length(coalesce(v_snapshot ->> 'name_ko', '')) not between 1 and 500
         or length(coalesce(v_snapshot ->> 'venue_name_ko', '')) not between 1 and 500 then
        raise exception using errcode = '22023', message = 'visit_invalid';
      end if;
      insert into content_private.my_gallr_visits (
        user_id, exhibition_id, client_record_id,
        name_ko, name_en, venue_name_ko, venue_name_en,
        opening_date, closing_date, cover_image_url, visited_at
      ) values (
        v_user_id,
        v_record ->> 'exhibition_id',
        v_record ->> 'client_record_id',
        v_snapshot ->> 'name_ko',
        coalesce(v_snapshot ->> 'name_en', ''),
        v_snapshot ->> 'venue_name_ko',
        coalesce(v_snapshot ->> 'venue_name_en', ''),
        (v_snapshot ->> 'opening_date')::date,
        (v_snapshot ->> 'closing_date')::date,
        nullif(v_snapshot ->> 'cover_image_url', ''),
        (v_record ->> 'created_at')::timestamptz
      ) on conflict (user_id, exhibition_id) do nothing;
    elsif v_kind = 'remove_visit' then
      if length(coalesce(v_record ->> 'exhibition_id', '')) not between 1 and 200 then
        raise exception using errcode = '22023', message = 'visit_invalid';
      end if;
      delete from content_private.my_gallr_visits
      where user_id = v_user_id
        and exhibition_id = v_record ->> 'exhibition_id';
    elsif v_kind = 'follow_gallery' then
      if length(coalesce(v_record ->> 'gallery_key', '')) not between 1 and 1000
         or length(coalesce(v_snapshot ->> 'name_ko', '')) not between 1 and 500 then
        raise exception using errcode = '22023', message = 'gallery_invalid';
      end if;
      begin
        v_gallery_id := nullif(v_record ->> 'gallery_id', '')::uuid;
      exception when others then
        raise exception using errcode = '22023', message = 'gallery_id_invalid';
      end;
      select coalesce(array_agg(distinct known_id), '{}')
      into v_known_ids
      from jsonb_array_elements_text(
        coalesce(v_record -> 'known_exhibition_ids', '[]'::jsonb)
      ) as known_id;
      if cardinality(v_known_ids) > 500 then
        raise exception using errcode = '22023', message = 'too_many_known_exhibitions';
      end if;
      insert into content_private.my_gallr_followed_galleries (
        user_id, gallery_key, gallery_id,
        name_ko, name_en, city_ko, city_en, region_ko, region_en,
        known_exhibition_ids, followed_at
      ) values (
        v_user_id,
        v_record ->> 'gallery_key',
        v_gallery_id,
        v_snapshot ->> 'name_ko',
        coalesce(v_snapshot ->> 'name_en', ''),
        coalesce(v_snapshot ->> 'city_ko', ''),
        coalesce(v_snapshot ->> 'city_en', ''),
        coalesce(v_snapshot ->> 'region_ko', ''),
        coalesce(v_snapshot ->> 'region_en', ''),
        v_known_ids,
        (v_record ->> 'followed_at')::timestamptz
      ) on conflict (user_id, gallery_key) do update
      set gallery_id = coalesce(
            content_private.my_gallr_followed_galleries.gallery_id,
            excluded.gallery_id
          ),
          known_exhibition_ids = (
            select array_agg(distinct exhibition_id)
            from unnest(
              content_private.my_gallr_followed_galleries.known_exhibition_ids
              || excluded.known_exhibition_ids
            ) as exhibition_id
          ),
          updated_at = now();
    elsif v_kind = 'unfollow_gallery' then
      if length(coalesce(v_record ->> 'gallery_key', '')) not between 1 and 1000 then
        raise exception using errcode = '22023', message = 'gallery_invalid';
      end if;
      delete from content_private.my_gallr_followed_galleries
      where user_id = v_user_id
        and gallery_key = v_record ->> 'gallery_key';
    elsif v_kind = 'acknowledge_gallery' then
      select coalesce(array_agg(distinct known_id), '{}')
      into v_known_ids
      from jsonb_array_elements_text(
        coalesce(v_record -> 'known_exhibition_ids', '[]'::jsonb)
      ) as known_id;
      if length(coalesce(v_record ->> 'gallery_key', '')) not between 1 and 1000
         or cardinality(v_known_ids) > 500 then
        raise exception using errcode = '22023', message = 'gallery_invalid';
      end if;
      update content_private.my_gallr_followed_galleries
      set known_exhibition_ids = (
            select array_agg(distinct exhibition_id)
            from unnest(known_exhibition_ids || v_known_ids) as exhibition_id
          ),
          updated_at = now()
      where user_id = v_user_id
        and gallery_key = v_record ->> 'gallery_key';
    end if;

    update content_private.my_gallr_archives
    set revision = revision + 1,
        updated_at = now()
    where user_id = v_user_id
    returning revision into v_revision;
    insert into content_private.my_gallr_mutation_receipts (
      user_id, mutation_id, request_hash, applied_revision
    ) values (v_user_id, v_mutation_id, v_request_hash, v_revision);
  end loop;

  return content_private.my_gallr_archive_json(v_user_id);
exception
  when check_violation or invalid_datetime_format or datetime_field_overflow then
    raise exception using errcode = '22023', message = 'mutation_record_invalid';
end;
$function$;

create or replace function public.sync_my_gallr_archive(
  p_mutations jsonb default '[]'::jsonb
)
returns jsonb
language sql
volatile
security definer
set search_path = ''
as $function$
  select content_private.sync_my_gallr_archive_impl(p_mutations);
$function$;

revoke all on function
  content_private.my_gallr_archive_json(uuid),
  content_private.sync_my_gallr_archive_impl(jsonb),
  public.sync_my_gallr_archive(jsonb)
from public, anon, authenticated, service_role;

grant execute on function public.sync_my_gallr_archive(jsonb)
to authenticated;
