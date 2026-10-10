# Public exhibition submission entry points

The public 전시 등록 / SUBMIT link currently sends everyone to Gallery. It must
open a choice between gallery operators and non-gallery individuals.

## Stories and acceptance

1. Public navigation, homepage CTA and `/submit/` expose two bilingual choices:
   exhibition registration is first for artists and organizers submitting their
   own exhibition without a gallery claim. The secondary 갤러리 등록 (관계자)
   choice opens the configured Gallery workspace for gallery/institution operators.
2. Individuals fill exhibition/venue names, dates, address, hours and optional
   description plus one required JPEG or PNG exhibition image (maximum 5 MiB). They verify their email through a sign-in link, remain in the
   public-site flow, and submit to staff review. Missing configuration and
   no-JavaScript states explain how to contact the team without false success.
3. A verified ordinary Gallr identity can submit without gallery/editor/staff
   membership. The server derives email and actor identity, validates bounded
   data, rate-limits submissions, and retains idempotent retry receipts. No
   membership, canonical exhibition or published catalogue entry is created.
   Existing Admin review and notification workflows process the request.

The user explicitly chose a form with email verification. Use the shared Auth
plane and its existing email-link templates, not the retired anonymous uploader.
The user additionally requires image submission. Images upload only after email verification, remain private during review, and carry into the accepted draft. Invalid, missing, oversized, expired, or another user's uploads cannot be submitted. Ambiguous retries retain both upload and submission identities; abandoned uploads use the existing stale-media cleanup. Files must be selected again after reloading; bearer tokens and file bytes are never stored in browser draft storage.

## Verification

Test choice navigation on desktop/mobile and with JavaScript off; email-link
callback cleanup, form validation, success/failure, retries and no-gallery flow;
database authentication/verified-email denial, no role grants, request replay,
payload/rate-limit rejection, upload ownership/expiry/metadata validation, stale cleanup, and staff review with the attached image. Run Web and database CI gates.

Expired pending-upload recovery: a reservation that expires before Storage accepts the image must renew without losing the verified identity. Uploaded images retain their original submission retry identity after reservation expiry, so an ambiguous successful submission returns its existing receipt rather than creating another request.
