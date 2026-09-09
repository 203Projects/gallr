# gallr gallery

Customer-facing gallery-owner workspace intended for
`https://gallery.gallrmap.com`. It uses the same environment-specific Supabase
project and Auth identity plane as the mobile clients and staff Admin, but enters
only owner-scoped RPCs. See
[`account identity and access`](../docs/account-identity-and-access.md).

## Local development

Use Node.js 22.23.1 as declared by the root `.node-version` file.

```bash
cd gallery
npm ci
npm run dev
```

Configure `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`, and
`VITE_PUBLIC_SITE_URL` through process values injected from the matching 1Password item.
`.env.example` is a variable-name reference, not persistent credential storage. Only the
publishable browser key belongs in the client. `VITE_PUBLIC_SITE_URL` keeps owner-facing public
links and downloadable exhibition and RSVP QR codes on the matching visitor deployment during rehearsals. Keep
`VITE_LAUNCH_KIT_ENABLED=false` until the free Launch Kit beta is separately approved. Keep
`VITE_OWNER_PROMOTION_ENABLED=false`; paid-entitlement promotion is outside the active roadmap,
and enabling the free Launch Kit does not expose or query promotion controls. Missing Supabase
configuration fails closed; there is no production fixture mode.

An active Launch Kit can copy its environment-matched RSVP URL and download a monochrome SVG QR
code. QR rendering happens on demand in the browser; the URL or generated asset is not sent to a QR
service or persisted by Gallery. Replacing the RSVP link immediately invalidates previously
downloaded QR codes.

A published exhibition can also preview and download a square SVG QR for its current public page.
Gallery samples up to five colors from the stable public poster in the browser, darkens every tone
to scanner-safe contrast, keeps function modules square, and uses ECC H with a four-module white
quiet zone. The poster, URL, and generated SVG are not sent to a third-party service or persisted.
If the public poster cannot be read, Gallery still produces a monochrome QR. Regenerate the QR after
changing a published exhibition title because the current public-page path includes that title.

Owners can authenticate with either an emailed one-time sign-in code or Google
OAuth. Both methods may create the shared consumer identity when one does not
exist. They establish only the Auth identity: a new account still has
no gallery access until it searches for or creates a gallery claim and staff
approves that claim in Admin. The matching Supabase project must have the
Google provider enabled and the exact gallery portal origin in its Auth
redirect allow-list; do not add a broad preview-domain wildcard. OAuth callback
errors are consumed from the return URL and shown as bounded bilingual messages
instead of leaking provider details or silently looping to sign-in.

## Verify

```bash
npm test
npm run typecheck
npm run build
```

Production deployment, `gallery.gallrmap.com` DNS/Auth redirect activation, and
the hosted shared-account signup configuration require an explicit cutover
decision. Follow the
[gallery owner release runbook](../docs/gallery-owner-release-runbook.md); do
not treat a successful build as authorization to deploy.
