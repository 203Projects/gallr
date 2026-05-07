-- Admin moderation: new thoughts require approval, admins can read/update/delete all

-- Change default so new thoughts start unapproved
ALTER TABLE thoughts ALTER COLUMN is_approved SET DEFAULT FALSE;

-- Admin can read all thoughts (approved and unapproved)
CREATE POLICY "Admin read all" ON thoughts
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND is_admin = TRUE)
    );

-- Admin can update any thought (approve/reject)
CREATE POLICY "Admin update" ON thoughts
    FOR UPDATE USING (
        EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND is_admin = TRUE)
    );

-- Admin can delete any thought
CREATE POLICY "Admin delete" ON thoughts
    FOR DELETE USING (
        EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND is_admin = TRUE)
    );
