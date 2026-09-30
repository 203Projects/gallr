# Retired anonymous gallery submission history

The account-free submission implementations were removed from the current repository after the
account-backed gallery-owner workspace became the production entry point. The public `/submit/`
route offers two choices: gallery operators enter `gallery.gallrmap.com` for claims and managed
drafts, while individuals enter `/submit/individual/` and verify their email before sending
text information to the existing staff review queue. The new individual transport is
authenticated; it does not restore any retired anonymous uploader.

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
Admin previews sign unpublished originals but use the public delivery URL after an asset is
published; published originals remain private and do not require a broader Storage read policy.

Historical behavior remains available through Git history, completed specifications, changelog
entries, and immutable migrations. Those records explain old data and audit evidence but are not
operational setup instructions.


## Verified individual intake

The public header and homepage CTA open the static `/submit/` chooser. Individuals,
artists, and visitors use `/submit/individual/` without claiming a gallery.
A Supabase email verification link returns to that exact public route. It establishes
an ordinary account in the same environment's identity plane and grants no staff,
editor, or gallery membership.

`submit_individual_exhibition(jsonb,uuid)` derives the private contact email from
`auth.users`, requires confirmed email and a non-anonymous identity, validates a
bounded text payload, and writes a `public_form` queue row. Staff review and existing
notification triggers remain authoritative. Acceptance creates an unpublished draft;
staff obtain and validate imagery before publication.

The existing email (three per hour), account-derived bucket (ten per hour), and
global intake (forty per hour) limits apply. The account bucket is a SHA-256 identity
hash stored in the legacy `source_ip_hash` column; the browser cannot supply an IP.
A request UUID remains stable across ambiguous retries, and the atomic command
receipt prevents duplicate rows and notifications. Tokens stay in browser memory;
only form details and the request UUID are saved in session-scoped draft storage.
Opening a verification link in a different tab/device may require re-entering details.

## Individual flow rollout

Keep `GALLR_ENABLE_INDIVIDUAL_SUBMISSION=0` until all of the following are verified
in the target environment:

1. Apply migration `20260930031557` through the reviewed, environment-matched
   migration rollout. Use staging first and preserve the migration lineage.
2. Add the exact public origin plus `/submit/individual/` to that project's
   Supabase Auth redirect allowlist. Use the existing email-link template; no
   Gallery Site URL or provider template change is needed. Avoid wildcard URLs.
3. Verify project-reference parity with other account-bearing surfaces. The
   public form takes the same `SUPABASE_URL` and publishable key as the public reader;
   inject values from the matching 1Password item.
4. Exercise a real staging email link, a private queue row, staff review, and the
   unpublished result. Then enable the flag in the matching web deployment.

With the flag disabled or JavaScript unavailable, the chooser remains usable and
the individual page offers the support email without claiming a submission succeeded.
Rollback disables the flag and rebuilds the public site; retain existing review
records, receipts, and the additive migration.
