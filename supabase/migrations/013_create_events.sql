-- Migration: 013_create_events.sql
-- Run in the Supabase dashboard SQL editor:
-- https://supabase.com/dashboard → your project → SQL Editor

-- ── New table: events ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS events (
  id                  TEXT PRIMARY KEY,
  name_ko             TEXT NOT NULL,
  name_en             TEXT NOT NULL,
  description_ko      TEXT NOT NULL DEFAULT '',
  description_en      TEXT NOT NULL DEFAULT '',
  location_label_ko   TEXT NOT NULL,
  location_label_en   TEXT NOT NULL,
  start_date          DATE NOT NULL,
  end_date            DATE NOT NULL,
  brand_color         TEXT NOT NULL,
  accent_color        TEXT,
  ticket_url          TEXT,
  is_active           BOOLEAN NOT NULL DEFAULT true,
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE events ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public read"
  ON events FOR SELECT
  USING (true);

-- ── exhibitions.event_id (nullable FK) ─────────────────────────────────────
ALTER TABLE exhibitions
  ADD COLUMN IF NOT EXISTS event_id TEXT REFERENCES events(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_exhibitions_event_id
  ON exhibitions(event_id) WHERE event_id IS NOT NULL;
