# Retired anonymous gallery submission history

The account-free submission implementations were removed from the current repository after the
account-backed gallery-owner workspace became the production entry point. The public `/submit/`
route now only hands operators to `gallery.gallrmap.com`, where authenticated ownership, gallery
claims, revisioned drafts, review rounds, and publication status are maintained.

Removed implementation surfaces:

- the Apps Script `FormEndpoint.gs` and its HMAC-backed static form contract;
- the dormant `submit-exhibition` Supabase Edge Function; and
- the unshipped `web/submit/submit.js` anonymous form client and rollback-only tests.

There is no supported anonymous-intake rollback in the current tree. Restoring one is a new
security-sensitive product change: write a new specification, threat-review unauthenticated upload
and rate-limit boundaries, use current dependencies and schemas, test it in staging, and obtain
explicit rollout authorization. Do not recover old source from Git history and deploy it directly.

The `content.exhibition_submissions` review model, Admin submission queue, private
`exhibition-media` bucket, and related immutable migrations remain current because the authenticated
owner workflow uses them. They are not part of the removed anonymous transport.

Historical behavior remains available through Git history, completed specifications, changelog
entries, and immutable migrations. Those records explain old data and audit evidence but are not
operational setup instructions.
