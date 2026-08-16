# Feature Specification: My Gallr contextual account conversion

**Feature branch**: `shin/060-my-gallr-guest-archive`
**Status**: Implemented
**Depends on**: `060-my-gallr-guest-archive`, `061-my-gallr-gallery-following`

## Goal

Invite account creation only after a visitor has received personal value from My Gallr, using benefits
that exist today and clearly separating cloud-backed member features from device-only My Gallr data.

## User story

As an anonymous visitor with a growing My Gallr collection, I see a compact account invitation that
explains why an account may be useful without blocking my archive or misleading me about backup.

## Acceptance criteria

1. The invitation appears only for anonymous users after at least three combined visit and gallery
   records.
2. It does not appear for loading or authenticated users.
3. It names existing benefits: bookmark sync, exhibition diary, and profile.
4. It explicitly says visits and followed galleries remain on this device for now.
5. The primary action opens the existing Account sign-in/signup flow.
6. Dismissal is persisted locally and prevents the invitation from appearing again.
7. Saving visits or following galleries remains uninterrupted and never opens the invitation as a
   modal.

## Out of scope

- Visit/gallery cloud sync, push notifications, email campaigns, experiments, analytics SDK changes.
- Changes to authentication providers or signup forms.
