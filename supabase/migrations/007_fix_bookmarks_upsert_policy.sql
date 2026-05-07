-- Fix: upsert requires an UPDATE policy alongside INSERT.
-- Without this, ON CONFLICT ... DO UPDATE triggers an RLS "USING expression"
-- error because Postgres checks the UPDATE policy for the merge step.

CREATE POLICY "Owner update" ON bookmarks
    FOR UPDATE USING (auth.uid() = user_id);
