-- Staff-curated gallery profile images (spec 089).
--
-- Each gallery may have one representative logo or space photo, re-hosted as a
-- 512x512 JPEG in the public gallery-profile-images bucket. Metadata stays in a
-- private table written only by operator SQL; anonymous clients read active,
-- unmerged galleries through list_gallery_profile_images().

create table if not exists content.gallery_profile_images (
  gallery_id uuid primary key
    references content.galleries(id) on delete cascade,
  kind text not null,
  storage_path text not null unique,
  source_page_url text not null,
  license text not null,
  credit text,
  requires_attribution boolean not null,
  content_sha256 text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint gallery_profile_images_kind check (kind in ('logo', 'photo')),
  constraint gallery_profile_images_sha256 check (content_sha256 ~ '^[0-9a-f]{64}$'),
  constraint gallery_profile_images_storage_path check (
    storage_path = gallery_id::text || '/' || content_sha256 || '.jpg'
  ),
  constraint gallery_profile_images_source_page_url check (
    source_page_url ~ '^https?://' and length(source_page_url) <= 2000
  ),
  constraint gallery_profile_images_license check (
    length(btrim(license)) between 1 and 200
  ),
  constraint gallery_profile_images_credit check (
    (requires_attribution and credit is not null and length(btrim(credit)) between 1 and 300)
    or (not requires_attribution and credit is null)
  )
);

alter table content.gallery_profile_images enable row level security;

revoke all on content.gallery_profile_images
  from public, anon, authenticated;

insert into storage.buckets (
  id,
  name,
  public,
  file_size_limit,
  allowed_mime_types
)
values (
  'gallery-profile-images',
  'gallery-profile-images',
  true,
  262144,
  array['image/jpeg']
)
on conflict (id) do nothing;

create or replace function public.list_gallery_profile_images()
returns table (
  gallery_id uuid,
  name_ko text,
  name_en text,
  kind text,
  storage_path text,
  credit text
)
language sql
stable
security definer
set search_path = ''
as $$
  select
    image.gallery_id,
    gallery.name_ko,
    gallery.name_en,
    image.kind,
    image.storage_path,
    image.credit
  from content.gallery_profile_images as image
  join content.galleries as gallery
    on gallery.id = image.gallery_id
  where gallery.status = 'active'::content.gallery_status
    and gallery.merged_into_gallery_id is null
  order by image.gallery_id;
$$;

comment on function public.list_gallery_profile_images() is
  'Public read contract for curated gallery profile images (spec 089). Returns only active, unmerged galleries.';

revoke all on function public.list_gallery_profile_images()
  from public, anon, authenticated;
grant execute on function public.list_gallery_profile_images()
  to anon, authenticated;
