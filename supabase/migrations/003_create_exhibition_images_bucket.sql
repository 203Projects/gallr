-- Migration: 003_create_exhibition_images_bucket.sql
-- Run this in the Supabase dashboard SQL editor:
-- https://supabase.com/dashboard → your project → SQL Editor
--
-- Creates a public Storage bucket for exhibition cover images.
-- Images are uploaded manually via the Supabase dashboard or API.
-- The Google Sheet references filenames; the Apps Script builds the full public URL.

-- 1. Create the public bucket
INSERT INTO storage.buckets (id, name, public)
VALUES ('exhibition-images', 'exhibition-images', true)
ON CONFLICT (id) DO NOTHING;

-- 2. Allow anyone to read (public bucket — no auth needed for GET)
CREATE POLICY "Public read exhibition images"
  ON storage.objects FOR SELECT
  USING (bucket_id = 'exhibition-images');

-- 3. Allow service role to upload/update/delete (used by admin or future automation)
-- Service role bypasses RLS by default, so no explicit INSERT/UPDATE/DELETE policies needed.
