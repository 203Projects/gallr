-- Migration: 018_add_event_short_label.sql
-- Adds a nullable short_label column to the events table. This is a compact
-- (recommended ≤ 12 chars) event identifier shown in the pink corner ribbon
-- on exhibition cards (EventTreatment.label), where the full localized event
-- name truncates awkwardly. When null, the app falls back to truncating the
-- localized name to 12 chars. The column may be reused anywhere a short event
-- tag is needed in the future.

ALTER TABLE events
  ADD COLUMN IF NOT EXISTS short_label TEXT;
