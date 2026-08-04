# Stripe Launch Kit webhook

`stripe-launch-webhook` is the only payment path that activates a pending
Gallery Launch Kit. Gateway JWT verification is disabled because Stripe is the
caller; the function authenticates the exact raw request body with the
`stripe-signature` header before using any event data.

## Configuration

Server-only values:

- `STRIPE_SECRET_KEY`
- `STRIPE_LAUNCH_WEBHOOK_SECRET`

Supabase automatically provides the hosted `SUPABASE_SECRET_KEYS` map. The
function selects `stripe-launch-webhook` when present and otherwise `default`;
the local single-key and legacy service-role variables remain migration
fallbacks. Do not create a custom secret using the reserved `SUPABASE_` prefix.

Register the exact hosted function URL for `checkout.session.completed`. Use
Stripe test mode for staging and live mode for production, with separate
1Password items and separate signing secrets.

## Activation rules

The handler ignores unrelated verified event types. A completed Session is
accepted only when it is a paid `payment` Session with a payment-intent ID, a
safe integer amount, and a lowercase three-letter currency. Postgres then
matches the Session to the pending Kit and records the Stripe event,
payment-intent, amount, and currency through the service-only activation RPC.
Duplicate webhook deliveries are idempotent at that boundary.

Missing/invalid signatures, malformed paid Sessions, and activation failures
return non-2xx so Stripe can report or retry the delivery. Do not log raw
request bodies, signatures, customer data, or secrets.

Run `deno task test` and `deno task check` in this directory. Registration and
live smoke gates are in the
[gallery owner release runbook](../../../docs/gallery-owner-release-runbook.md).
