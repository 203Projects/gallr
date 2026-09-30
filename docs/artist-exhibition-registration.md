# Artist and organizer exhibition registration

The public `/submit/` page explains two tasks. **전시 등록** opens `/submit/exhibition/` for
artists, curators and organizers. **갤러리 등록 (관계자)** retains the gallery-owner portal's
search, claim and verification flow. A submitted venue name is an independent location snapshot;
it never creates a gallery, membership or management grant.

## Account and data contract

Public web Auth must use the same environment-specific Supabase project as mobile, Gallery,
Admin and Editor. Existing verified email/password accounts can sign in. New account signup uses
the existing shared consumer signup policy and requires email verification. No Auth settings are
changed by this feature. Google/Apple accounts can use the existing account portal to establish
an email/password login; public-web OAuth and cross-redirect draft restoration are not included.

Draft fields and the selected poster remain in memory during in-page sign-in. They are not
saved to browser draft storage and are lost on refresh or leaving the page. Signing out clears
the account-specific records, draft and poster preview. The SDK retains its usual Auth session;
the feature never records passwords, tokens, raw provider errors or contact values in logs.

Authenticated RPCs are:

- `artist_reserve_registration(request_id, mime_type, byte_size, filename)`: allocates exactly
  one immutable JPEG/PNG cover path in private `exhibition-media`. Limit: 10 MiB, three reservations
  per hour and ten per day for the verified account. The same request and metadata return the
  same reservation; changed metadata or a foreign account fail closed.
  Completed reservations return `finalized=true`, so an ambiguous successful
  submission retries the final command without attempting another cover upload.
  Expired unfinished reservations fail before Storage access with a bounded expiry error.
- `artist_submit_registration(request_id, payload, name, relationship, email)`: validates the
  reserved object owner, MIME and byte count, then reuses canonical public-form intake and media
  validation. Retries retain the request ID and exact content; changed successful retries fail.
  The transaction records `submitter_user_id`, private contact and an audit entry. It never grants
  gallery access. Participating artists appear in the intake description for current Admin review,
  and are also retained as structured intake metadata for later editorial credit resolution.
- `artist_list_registrations()`: returns only the caller's latest 20 submissions. Historical
  anonymous/public submissions are not associated by email. Staff approval is distinct from
  publication: a live canonical published pointer, without archive, is required for `published`.

Upload reservations expire for writes/finalization after 24 hours. The reservation ledger and
unsubmitted uploaded files are not automatically purged by this migration. Operators should review
and remove abandoned media under the existing private-media retention process before activation.
There is no client overwrite, delete, general bucket-read or reservation-table grant.

## Editorial review

The existing Admin queue, media signing/processing and public-form acceptance path are reused.
An accepted submission becomes a staff-owned canonical draft; staff still completes catalogue
content, resolves credits and publishes it. A rejection displays the saved notes and can be copied
into a new request. Its original review record is retained. Rejected records are not labelled as
an unsupported `needs_changes` state.

The existing staff intake notification trigger remains active. Submitter decision emails are not
added because the current decision renderer is gallery-facing. The public UI promises results in
**내 등록 내역**, not email decision delivery.

## Build and activation

The SDK and esbuild versions are pinned in `web/package.json` and its lockfile. Builds self-host the
client bundle under `/build/registration.js`; no runtime CDN is required.
The development command builds that bundle before starting Eleventy. After editing registration
JavaScript, rebuild it with `node scripts/build-registration.js` or restart the development command.

Intake is disabled by default. Set `GALLR_ENABLE_ARTIST_SUBMISSIONS=true` only after applying
`20260930012719_artist_exhibition_registration.sql` and verifying the shared project identity,
verified-email signup, private poster upload, staff review and publication in staging. The build
requires `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY`; secret/service-role keys are rejected.
Use matching 1Password items and injected environment values, never plaintext persistent secrets.
Set `GALLR_GALLERY_WORKSPACE_URL` to the matching gallery environment for isolated previews.

With intake disabled or JavaScript unavailable, the artist route gives a direct email contact and
the gallery route remains accessible. Enabling production, changing Auth configuration, applying
remote migrations and deploying are separate release operations, not consequences of local tests.

## Verification

`npm test` in `web/` includes domain/adapter/configuration tests, build, accessibility and
Playwright coverage. Registration tests intercept a synthetic local Auth/RPC/Storage origin to
exercise the SDK and complete browser flow without credentials or external email. Database tests
live in `supabase/tests/database/043_artist_registration.test.sql` and cover ordinary-account
submission, unverified/anonymous/foreign-account denial, reserved media, limits, idempotency,
private reads and existing staff acceptance. Follow the full database workflow for release gates.
Live email verification and real Auth/Storage HTTP integration still require the staging rehearsal.
`supabase/tests/artist_registration_concurrency.py` verifies simultaneous reservation retries and
account quota enforcement against an explicitly selected disposable local database.
