-- Migration: 014_add_event_cover_image_url.sql
-- Adds a nullable cover_image_url column to the events table for the
-- Phase 2a hero image on the Featured promotion card. The column accepts
-- either a full HTTPS URL (used as-is) or a bare filename (resolved at
-- sync time to the public event-images Supabase Storage bucket).

ALTER TABLE events
  ADD COLUMN IF NOT EXISTS cover_image_url TEXT;
