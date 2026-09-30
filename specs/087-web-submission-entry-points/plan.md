# Plan

## Constitution check before and after design

- Spec precedes implementation; behavioral tests fail before the tested paths.
- Independent static web code is exempt from KMP placement (Principle VI).
- Use existing design tokens, sharp corners and monochrome errors.
- Reuse canonical `public_form` review, private contact fields, transactional
  request receipts, staff notifications and draft-only acceptance.
- A new authenticated, verified-email RPC is justified: the retired anonymous
  Edge Function must not be redeployed or its service-role API exposed.
- Browser configuration includes only the matched public URL/key. Tokens are
  verified server-side, kept in memory, and removed from callback URLs.
- Reuse the private exhibition-media bucket and stale-media cleanup. Add a verified-account reservation command and exact reserved-path INSERT policy; no overwrite/delete/public-read grants or remote URL fetching. No role enrollment.

## Design

`/submit/` is a static two-choice landing page. `/submit/individual/` is a
progressively enhanced form: prepare details, send a verification email link,
return to the same public route, then submit. Session-scoped draft data survives
the email-link round trip; no bearer tokens are saved as a draft. Callback
tokens are consumed immediately and checked through Auth's user endpoint.

The SQL wrapper permits verified ordinary identities, calls the existing
bounded canonical intake implementation, uses a server-derived account hash
for the identity rate bucket, and records the submitting Auth identity. It
retains command receipts across ambiguous retries and only returns a receipt.

## Rollout

Migration first, then public web. Staging and production use separate 1Password
configuration and the existing database guards. Auth redirect allow-lists must
include the exact public `/submit/individual/` callback before live email-link
verification. Do not change signup templates or broaden privileged roles.

## Complexity

No speculative service layer or new browser dependency is needed.

## Image follow-up design

Reserve one JPEG/PNG image with a stable request UUID and server-generated submission/asset path. Limit reservations per verified account, expire unsubmitted reservations after one hour, and validate actual Storage MIME/size before transactional intake. The existing intake implementation creates the private ready asset and queue attachment; Admin acceptance retains it as a draft cover. Files stay in memory and must be reselected after navigation. Upload retry uses the same immutable path; an already-existing response proceeds to server validation. New submissions require an owned reserved image; prior text-only receipts can still replay.
