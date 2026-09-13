# Admin email notifications — design

Date: 2026-09-13

## Problem

Gallery owners, editors, and the public submission form create work that staff
must review (exhibition submissions, gallery claims, promotion requests, editor
profile and curation requests) and make changes staff should know about
(gallery identity edits, hidden exhibitions, Launch Kit activations, invited
editors finishing onboarding). Today the only way to notice any of this is to
open the Admin portal and look. Nothing pushes the information to staff.

## Goal

Every action in the list above sends one email to every active `admin` staff
member, durably and idempotently, without adding a new provider, secret, or
worker.

## Approach

Reuse the durable outbox and the `outbox-delivery` Edge Function, which
already sends owner decision emails through Resend.

```
owner / editor / public form action
  └─ existing command writes content.audit_log (or content.exhibition_submissions)
       └─ new AFTER INSERT trigger
            └─ content_private.enqueue_admin_notification(...)
                 └─ content.outbox_events  event_type = 'admin_notification.requested'
                      └─ outbox-worker (existing) forwards to outbox-delivery
                           └─ outbox-delivery renders + sends one Resend email
```

Two alternatives were rejected:

- **Per-command edits.** Adding an outbox insert to every owner and editor
  command touches a dozen `SECURITY DEFINER` functions and is easy to forget
  for the next command. The audit log is already the one place every
  reviewed action is recorded.
- **Digest cron.** A scheduled summary needs a new scheduler and a new state
  table, and the request is "notify when it happens".

## Database

One migration, `20260913120000_admin_email_notifications.sql`, adds:

- `content_private.admin_notification_recipients()` — lowercased emails of
  active `content.staff_members` rows with role `admin`, joined to
  `auth.users`. Empty when no admin has an email.
- `content_private.enqueue_admin_notification(kind, entity_type, entity_id,
  actor_email, context, deduplication_key, occurred_at)` — resolves
  recipients, drops null and non-scalar context values, then inserts one
  `admin_notification.requested` outbox event whose aggregate is the notified
  record (`entity_type` / `entity_id`) with
  `on conflict (deduplication_key) do nothing`. It returns whether a new event
  was queued. When there are no recipients it inserts nothing; there is no one
  to email and a dead letter would only be noise. The audit trigger resolves
  the actor's email from `auth.users`; the submission trigger uses the
  submitter email.
- Trigger `exhibition_submissions_admin_notification` on
  `content.exhibition_submissions` (`AFTER INSERT OR UPDATE OF status`) —
  fires when a row becomes `submitted`. This covers the public form, the owner
  workspace, and the editor workspace with one rule, so those two audit actions
  are excluded from the audit allowlist below.
- Trigger `audit_log_admin_notification` on `content.audit_log`
  (`AFTER INSERT`) — its `WHEN` clause and the function body share one
  immutable allowlist function, `admin_notification_audit_actions()`, naming
  these external-actor actions:
  `gallery.claim_requested`, `gallery.created_and_claimed`,
  `gallery.info_saved`, `owner_exhibition.hidden`, `local_promotion.requested`,
  `launch_kit.activated`, `editor.profile_submitted`,
  `editor.curation_submitted`, `editor.onboarded`.

Both trigger functions enrich the payload with a bounded `context` object:
gallery name (from `content.galleries`), exhibition name (latest draft,
otherwise the published version, otherwise the newest version), editor id and
display name, submission source and submitter email, change count, and
entitlement source. Values are scalars only; strings are truncated to 500
characters at enqueue time. An authenticated actor is removed from the
recipient list (a self-reported submitter address never is), gallery profile
saves are keyed per gallery per hour so the first save in an hour notifies and
later ones are dropped, and both triggers return early when the transaction sets
`gallr.suppress_admin_notifications = 'on'` for bulk operations. Queued events
carry `max_attempts = 12` so a delivery function that lags the migration by a
few hours does not dead-letter them.

Payload contract (`admin_notification.requested`):

```json
{
  "kind": "exhibition_submission.submitted",
  "entity_type": "exhibition_submission",
  "entity_id": "…",
  "actor_email": "owner@example.com",
  "recipient_emails": ["admin@example.com"],
  "occurred_at": "2026-09-13T03:00:00.000000+00:00",
  "context": { "exhibition_name": "…", "source": "owner_workspace" }
}
```

Deduplication keys: `admin_notification:audit:<audit_log id>` and
`admin_notification:submission:<submission id>:<submitted_at>`.

All helpers are `SECURITY DEFINER`, pin an empty `search_path`, and are revoked
from every client and service role; triggers are the only callers.

## Edge Function

`outbox-delivery` gains `admin_notification.ts`:

- `parseAdminNotification(event)` validates kind, actor email, timestamp, and
  the bounded context. Malformed recipients are skipped rather than rejecting
  the event; control characters in context strings collapse to spaces and the
  subject detail is capped at 80 characters. The handler sends recipients in
  batches of 50 with per-batch idempotency keys and links to
  `ADMIN_PORTAL_URL` (default production) so staging stays separate.
- `renderAdminNotificationEmail(notification)` maps each kind to a subject,
  a one-line headline, the Admin section to open, and a text + HTML body.
  Unknown kinds that match the `area.action` pattern render a generic
  "Admin attention needed" email rather than dead-lettering, so the database
  can add a kind before the function learns its wording. The event type itself
  must be acknowledged first, so the function deploys before the migration.

The handler acknowledges `admin_notification.requested`, requires the existing
Resend configuration (`RESEND_API_KEY`, `OWNER_NOTIFICATION_FROM_EMAIL`),
returns `422` for invalid payloads, `500` when configuration is missing, and
`502` with an allowlisted provider code when Resend fails so the outbox retries.
The outbox deduplication key is forwarded as Resend's idempotency key. The
existing Resend call is extracted into one shared `sendEmail` used by both the
owner decision path and the admin path.

## Testing

- pgTAP `041_admin_email_notifications.test.sql`: helper privileges and
  search path, recipients resolve only active admins, submission trigger fires
  once per submitted row for each source, audit trigger fires for allowlisted
  actions and ignores others, no event without recipients, dedup on replay.
- Deno tests for the parser/renderer and for the handler paths listed above.

## Out of scope

Per-admin opt-out, digests, Korean-language email copy, Slack, and admin
in-portal notification badges.
