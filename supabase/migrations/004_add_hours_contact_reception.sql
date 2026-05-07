-- Migration: 004_add_hours_contact_reception.sql
-- Run this in the Supabase dashboard SQL editor.
--
-- Adds three nullable columns for gallery operational info:
--   hours          — free text (e.g. "Tue–Sun 11:00–18:00")
--   contact        — email or phone number
--   reception_date — opening reception/vernissage datetime

ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS hours TEXT;
ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS contact TEXT;
ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS reception_date TIMESTAMPTZ;
