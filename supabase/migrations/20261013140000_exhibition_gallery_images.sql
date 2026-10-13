-- Spec 090, pictures in the curator's note (owner decision 2026-10-13): readers receive only an exhibition's
-- cover, so the note on the exhibition page could not show the other images staff attach under Media (role
-- "gallery"). This publishes those images, and nothing else about them, for exhibitions in the public catalogue.
-- Additive: one function and one view; no table or existing grant changes.

-- The gallery images of each published exhibition, in the order staff set. Only published, unpurged assets with
-- an address, and only for exhibitions the public catalogue lists, so a hidden or archived exhibition shows none.
create or replace function content_private.published_exhibition_gallery_images()
returns table (
  exhibition_id text,
  "position" integer,
  url text,
  alt_ko text,
  alt_en text,
  credit text,
  width integer,
  height integer
)
language sql
stable
security definer
set search_path = ''
as $$
  select
    exhibition.id,
    (row_number() over (
      partition by exhibition.id
      order by attachment.sort_order, attachment.created_at, attachment.media_id
    ))::integer,
    asset.public_url,
    attachment.alt_ko,
    attachment.alt_en,
    attachment.credit,
    asset.width,
    asset.height
  from content.exhibitions as exhibition
  join public.exhibition_catalog_v2 as catalog on catalog.id = exhibition.id
  join content.exhibition_version_media as attachment on attachment.version_id = exhibition.published_version_id
  join content.media_assets as asset on asset.id = attachment.media_id
  where attachment.role = 'gallery'::content.media_role
    and asset.status = 'published'::content.media_asset_status
    and asset.purged_at is null
    and nullif(btrim(asset.public_url), '') is not null;
$$;

-- The view runs with the caller's rights and reads through the function, so it is not a definer view.
create or replace view public.exhibition_gallery_images
with (security_invoker = true) as
select exhibition_id, "position", url, alt_ko, alt_en, credit, width, height
from content_private.published_exhibition_gallery_images();

comment on view public.exhibition_gallery_images is
  'Published gallery images of exhibitions in the public catalogue, in staff order; read by the apps for the curator''s note (spec 090).';

revoke all on function content_private.published_exhibition_gallery_images() from public;
grant execute on function content_private.published_exhibition_gallery_images() to anon, authenticated, service_role;
revoke all on public.exhibition_gallery_images from public, anon, authenticated, service_role;
grant select on public.exhibition_gallery_images to anon, authenticated, service_role;
