# Launch Kit checkout

`create-launch-checkout` starts or resumes the one-time Stripe Checkout flow for
a published exhibition owned by the signed-in gallery operator. Gateway JWT
verification is enabled, and the backend separately validates the bearer token
and calls the owner-authorized checkout RPC. A browser cannot choose the Price,
amount, currency, gallery, or return origins.

## Configuration

The hosted runtime supplies `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEYS`, and
`SUPABASE_SECRET_KEYS`. The function selects a component-named key first, then
`default`; local CLI single-key variables and the legacy anon/service-role keys
remain fallbacks during migration. Do not try to create custom secrets with the
reserved `SUPABASE_` prefix.

The function also requires these server-side values:

- `STRIPE_SECRET_KEY`
- `STRIPE_LAUNCH_KIT_PRICE_ID`
- `GALLERY_WORKSPACE_URL`, without a path requirement; trailing slashes are
  normalized

`LAUNCH_CHECKOUT_ALLOWED_ORIGINS` may override the exact comma-separated origin
allow-list. Keep staging and production values in separate 1Password items and
never place Stripe or Supabase server secrets in a `VITE_*` variable.

## Contract

The owner workspace sends `POST` with its bearer token and:

```json
{ "exhibition_id": "canonical-exhibition-id" }
```

The response is either an existing entitlement:

```json
{ "active": true, "launchKitId": "uuid" }
```

or a Stripe-hosted URL:

```json
{ "active": false, "url": "https://checkout.stripe.com/..." }
```

The database request UUID and Stripe idempotency key make retries converge on
the same pending Kit/attempt. An open Session is reused, an expired Session is
replaced, and an indeterminate payment is not duplicated. Checkout success does
not activate the Kit; only the verified webhook does.

Run `deno task test` and `deno task check` in this directory. Deployment order,
live/test-mode separation, and smoke checks are in the
[gallery owner release runbook](../../../docs/gallery-owner-release-runbook.md).
