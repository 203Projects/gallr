-- Canonicalize RLS policies that drifted via dashboard edits.
--
-- Migration 008 has been rewritten to create the extension-flexible
-- "Owner upload avatars" / "Owner update avatars" policies directly.
-- This migration drops the jpg-only singular policies from the old
-- version of 008 that still exist on the remote DB from when 008
-- first ran.
--
-- Similarly, drop the three plural thoughts admin policies that were
-- added via the dashboard — the singulars from migration 009 are the
-- canonical versions (defensive against NULL is_admin via
-- AND is_admin = TRUE).

DROP POLICY IF EXISTS "Owner upload avatar" ON storage.objects;
DROP POLICY IF EXISTS "Owner update avatar" ON storage.objects;

DROP POLICY IF EXISTS "Admin read all thoughts" ON thoughts;
DROP POLICY IF EXISTS "Admin moderate thoughts" ON thoughts;
DROP POLICY IF EXISTS "Admin delete thoughts" ON thoughts;
