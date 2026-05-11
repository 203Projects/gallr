-- Migration: 015_add_is_homepage_featured.sql
-- Adds a boolean flag to exhibitions so the gallrmap.com homepage can
-- render a manually-curated set instead of a daily date-seeded rotation.
-- The partial index keeps lookups O(curated_count) as the table grows.
-- See docs/2026-05-11-homepage-curation-decouple-design.md for context.

ALTER TABLE public.exhibitions
  ADD COLUMN IF NOT EXISTS is_homepage_featured boolean NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS exhibitions_homepage_featured_idx
  ON public.exhibitions (is_homepage_featured)
  WHERE is_homepage_featured = true;
