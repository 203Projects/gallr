# gallr gallery

Customer-facing gallery-owner workspace intended for
`https://gallery.gallrmap.com`. It uses the same Supabase project and Auth
provider as the public readers and staff Admin, but enters only owner-scoped
RPCs.

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
links on the matching visitor deployment during rehearsals. Keep
`VITE_LAUNCH_KIT_ENABLED=false` until the paid Launch Kit services are
separately activated. Missing Supabase configuration fails closed; there is no
production fixture mode.

## Verify

```bash
npm test
npm run typecheck
npm run build
```

Production deployment, `gallery.gallrmap.com` DNS/Auth redirect activation, and
the owner-account signup gate require an explicit cutover decision. Follow the
[gallery owner release runbook](../docs/gallery-owner-release-runbook.md); do
not treat a successful build as authorization to deploy.
