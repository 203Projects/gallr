# Feature Investigation: Followed-gallery publication alerts

**Feature branch**: `shin/060-my-gallr-guest-archive`
**Status**: Local implementation complete through platform registration and staged provider
delivery; external staging activation and sandbox delivery remain gated
**Depends on**: `061-my-gallr-gallery-following`

## Goal

Notify an opted-in visitor when a followed gallery publishes a new exhibition, including while
Gallr is closed, without requiring an account merely to receive alerts or claiming that foreground
catalogue checks are push delivery.

## Current-system findings

1. Gallr's notification scheduler is local and date-based. It schedules opening, closing,
   reception, and inactivity reminders for exhibitions already known on the device.
2. The mobile catalogue refreshes on launch/resume and periodically while the app is alive. It
   cannot discover a publication while the app is suspended.
3. Publication already writes a durable, idempotent `exhibition.published` outbox event.
4. The database already has stable `content.galleries.id` identities and a private mapping from
   catalogue gallery names to those identities.
5. Before this slice, the public mobile catalogue and `Exhibition` model did not expose
   `gallery_id`; My Gallr therefore followed a normalized bilingual name key.
6. There is no installation/device-token registry, remote push provider adapter, or server-side
   gallery subscription table.

## Product decisions

- Following remains useful without an account and keeps the in-app `NEW` signal.
- Push alerts are a separate, explicit per-device opt-in. Following alone never triggers the OS
  permission sheet.
- An account is not required simply to receive an alert on the current device. Signing in becomes
  valuable for restoring and synchronizing gallery follows across devices.
- Until remote delivery ships, Following says that Gallr checks for new exhibitions in-app and
  does not display a bell, “alerts on,” or equivalent promise.
- Tapping a publication alert uses the existing exhibition deep link with My Gallr as the missing
  exhibition fallback.

## Required delivery architecture

### 1. Stable public identity

- Add read-only `gallery_id` to the public catalogue contract and mobile `Exhibition` model.
  **Implemented locally.**
- Preserve immutable venue-name/location snapshots on exhibitions.
- Migrate local name-key follows to stable IDs when an unambiguous catalogue match is available;
  retain the stored snapshot and legacy key as a safe fallback. **Implemented locally.**

### 2. Installation and subscription contracts

- Register a random installation ID, platform, locale, push token, token status, and last-seen time
  through authenticated-or-anonymous command APIs.
- Store gallery subscriptions by installation ID and stable gallery ID.
- Installation ownership uses a client-generated high-entropy secret. Only a salted digest is
  stored; every state read or mutation must prove the secret. **Implemented locally.**
- Registration and per-gallery enable/disable commands are idempotent and revision checked, with
  two-session concurrency coverage. **Implemented locally.**
- When a user signs in, merge local follows into account-backed follows and associate the current
  installation. Never discard anonymous follows during migration.
- Keep raw provider tokens unavailable to `anon` and `authenticated` table reads; all mutations
  use narrow, revision-safe commands and delivery uses service-role access only.

The mobile implementation now registers APNs addresses on iOS and the current FCM Firebase
Installation ID on Android only after the visitor explicitly enables a gallery alert and OS
permission is granted. Existing opted-in galleries refresh their current address on launch. The
server worker targets Android by HTTP v1 `fid`, uses direct APNs delivery for iOS, and remains inert
unless the exact staging feature flag is enabled.

### 3. Publication fan-out

- Enrich `exhibition.published` with stable `gallery_id`, or resolve it transactionally in the
  server-side receiver.
- Extend the durable outbox delivery boundary to enqueue one idempotent fan-out job per published
  version.
- Fan out to active installation subscriptions through a provider adapter, deduplicated by
  `gallery:{gallery_id}:exhibition:{exhibition_id}:version:{version_id}`.
- Disable invalid tokens, bound retries, dead-letter permanent failures, and never log tokens or
  provider response bodies.

### 4. Permission and settings UX

- Add an “Alert me about new exhibitions” control to each followed gallery.
- Show Gallr's rationale only after the visitor enables that control, then request OS permission.
- If permission is denied, retain the follow and in-app `NEW` behavior; show a non-blocking route to
  system settings.
- Provide global gallery-alert enable/disable and per-gallery controls.

## Delivery order

1. Public stable gallery identity and local follow migration.
2. Installation/subscription command APIs with RLS and contract tests.
3. Platform token registration and explicit permission UX.
4. Outbox fan-out worker/provider adapter and delivery observability.
5. Account merge/cross-device restore and staged rollout.

## Release gates

- Staging-only provider credentials sourced from the staging 1Password item.
- Clean migration replay, pgTAP authorization coverage, Deno tests, Android/iOS token lifecycle
  tests, and provider sandbox delivery.
- No production migration, function deployment, provider provisioning, or remote permission prompt
  is authorized by this investigation.
