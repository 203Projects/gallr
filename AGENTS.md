# gallr

**See [`CLAUDE.md`](./CLAUDE.md) for the canonical engineering guide** — architecture boundaries,
code style, build/test commands, KMP conventions, and subsystem gotchas. The credential rules below
are bootstrap policy and apply before any credential-dependent work.

gallr is a Kotlin Multiplatform + Compose Multiplatform mobile app (Android + iOS) for discovering
art exhibitions in Seoul, plus public web, staff Admin, gallery-owner, and Supabase surfaces. When
working inside `iosApp/`, `web/`, `admin/`, `gallery/`, `supabase/`, or `scripts/`, read the nearest
`AGENTS.md` in that subtree.

The one rule to never skip: **read `DESIGN.md` before any UI change** (brutally minimal monochrome,
0dp corners, single `#FF5400` accent, Inter + Gothic A1 on an 8pt grid).

## Publishing flow

- **`develop` is the working branch and default integration target.** Feature, fix, dependency, and
  documentation pull requests target `develop`; CI and preview deployments validate work there.
- **`main` is production.** Promote reviewed work from `develop` to `main` with a dedicated pull
  request after required checks and any staging rehearsal pass. Do not push directly to `main`, and
  do not use an unmerged feature branch as the normal production source.
- A Vercel deployment is not a mobile release. Production web surfaces deploy from `main`; an
  intentional product release also updates `VERSION` and `CHANGELOG.md`, then publishes the matching
  `v<version>` GitHub Release from the exact production commit through the **Publish GitHub release**
  workflow. Never move or replace an existing release tag. Store upload/review remains an explicitly
  approved, platform-specific operation.

## Credential management

- Always use 1Password as the source of truth for passwords, tokens, API keys, and other secrets.
- When an authorized workflow requires a credential that is missing, create or add it to the
  appropriate 1Password vault and item before continuing.
- Keep production (`gallr`) and staging (`gallr-staging`) credentials in separate, clearly named
  items. Never copy or substitute a credential between environments.
- Use the 1Password CLI and secret references or hidden prompts so secret values never appear in
  source code, committed files, command arguments, shell history, logs, receipts, or assistant
  output.
- Do not use macOS Keychain or plaintext environment files as persistent credential storage.
- Never overwrite, rotate, delete, or migrate an existing credential without confirming the exact
  target environment and having authorization for that credential change.

<!--
  This file is intentionally a pointer to CLAUDE.md to avoid maintaining two copies that drift.
  Spec-kit (`.specify/scripts/bash/update-agent-context.sh`) appends to `## Recent Changes` below
  when run; the canonical guidance lives in CLAUDE.md.
-->

## Recent Changes
- 067-gallery-launch-beta: Gallery owners can activate free-beta RSVP, QR, guest-list, and check-in tools while paid promotion remains independently gated.
- 059-editor-email-invitation: Admin invitations collect only email; invited editors create an unpublished profile in the dedicated editor portal before receiving curation access.
- 052-owner-hide-exhibitions: Gallery owners can remove an exhibition from their workspace through a revision-checked soft hide without deleting canonical, review, or published records.
- 051-gallery-info: Gallery owners maintain a revisioned canonical identity and venue profile; new exhibition drafts copy an independent venue snapshot.
- 050-transparent-local-promotion: Launch Kit promotion stays labelled, locality-scoped, staff-reviewed, frequency-capped, and isolated from organic catalogue ordering.
