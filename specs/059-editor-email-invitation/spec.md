# Feature Specification: Editor email invitation

## User stories

- As an administrator, I send an editor invitation using only an email address.
- As an invited editor, I set my password and create my own editor profile in
  the dedicated editor portal.
- As an administrator, I retain control over profile publication and schedule.

## Requirements

- Admin invitation collects and transmits only a normalized email address.
- The trusted Edge Function authorizes an active Admin before reading the body,
  creates the Auth invitation, and records a server-only pending invitation.
- A pending invitation grants only `editor_onboarding` access. It cannot satisfy
  staff authorization, editor membership checks, or editor curation RPCs.
- Staff membership takes precedence and staff accounts cannot be invited as
  editors.
- The invited account supplies its slug, bilingual identity, personal bio, and
  separate curatorial statement. Completion atomically creates the profile and
  membership, consumes the invitation, and records audit evidence.
- New self-created profiles are unpublished. Only Admin can change visibility
  or scheduling through the existing revision-checked editor management flow.
- Existing/pending emails, rate limits, authorization denial, and temporary
  service failures receive distinct safe UI messages without provider details.

## Acceptance scenarios

1. Admin enters one valid email and sees an invitation confirmation without
   supplying profile or schedule fields.
2. A failed existing-email invitation explains the conflict without exposing
   the Auth provider response.
3. The invitation link opens `editor.gallrmap.com`, allows password setup, and
   displays profile onboarding rather than redirecting to Admin.
4. An uninvited or staff account cannot complete editor onboarding.
5. Completion creates exactly one inactive editor and active membership,
   consumes the pending invitation, and then opens My curation.
