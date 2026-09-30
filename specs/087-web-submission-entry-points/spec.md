# Public exhibition submission entry points

The public 전시 등록 / SUBMIT link currently sends everyone to Gallery. It must
open a choice between gallery operators and non-gallery individuals.

## Stories and acceptance

1. Public navigation, homepage CTA and `/submit/` expose two bilingual choices:
   gallery operators enter the configured Gallery workspace; individuals,
   artists and visitors open `/submit/individual/` without a gallery claim.
2. Individuals fill exhibition/venue names, dates, address, hours and optional
   description. They verify their email through a sign-in link, remain in the
   public-site flow, and submit to staff review. Missing configuration and
   no-JavaScript states explain how to contact the team without false success.
3. A verified ordinary Gallr identity can submit without gallery/editor/staff
   membership. The server derives email and actor identity, validates bounded
   data, rate-limits submissions, and retains idempotent retry receipts. No
   membership, canonical exhibition or published catalogue entry is created.
   Existing Admin review and notification workflows process the request.

The user explicitly chose a form with email verification. Use the shared Auth
plane and its existing email-link templates, not the retired anonymous uploader.
Text intake is sufficient; staff attaches/validates media before publication.

## Verification

Test choice navigation on desktop/mobile and with JavaScript off; email-link
callback cleanup, form validation, success/failure, retries and no-gallery flow;
database authentication/verified-email denial, no role grants, request replay,
payload/rate-limit rejection and staff review. Run Web and database CI gates.
