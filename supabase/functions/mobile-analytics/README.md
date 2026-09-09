# Mobile analytics

`mobile-analytics` accepts Gallr's closed mobile product-event batches and
records only daily aggregate counters. It never stores a raw behavioral event,
account/session/installation identity, search text, precise location, route
geometry, local recommendation score/reasons, URL, contact value, thought, or
guest data.

## Activation

Collection is inert unless `MOBILE_ANALYTICS_ENABLED=true`. Keep it false until
the privacy/store disclosures and user preference described in spec 072 ship. A
disabled function returns `204` without parsing the request or creating a
backend client.

When enabled, configure a 32–256 character `MOBILE_ANALYTICS_HASH_SECRET` from
the matching environment's 1Password item. It derives a short-lived source quota
key from the trusted ingress address. The digest is used only across the 24-hour
rate-limit window in the private hourly quota table and is never joined to
analytics facts. Expired rows are removed by the next hourly cleanup. Confirm
that the hosted ingress overwrites `cf-connecting-ip` or `x-forwarded-for`;
spoofing degrades source isolation to the still-bounded project quota.

`MOBILE_ANALYTICS_ALLOWED_ORIGINS` defaults to `app://gallr`. This is a routing
check, not authentication. The handler also enforces body/batch limits, strict
event shapes, a 0–20 recommendation result count, calendar/range validation, and
a canonical letter/digit/underscore/hyphen exhibition-ID grammar that cannot
carry URLs, email addresses, coordinates, or search text. It also enforces
server/database quotas, event-ID dedupe, and sanitized responses. The database
prunes expired seven-day retry receipts and source-quota rows both hourly and on
the first accepted batch of each active hour. Identity-free daily aggregate
counters use a 24-calendar-month reporting window; out-of-window rows are
removed by the hourly cleanup.

This anonymous endpoint cannot prove that every accepted event came from an
untampered app. Treat aggregate reporting as directional, monitor for anomalies,
and use the kill switch when traffic is suspect. It must not drive billing,
security, entitlements, or individual decisions.

The backend uses a component-scoped secret resolved for `mobile-analytics` and
calls only `service_record_mobile_analytics`. Public clients never receive that
secret and cannot call the recorder RPC directly.

## Contract

- `POST` with `application/json`, `Origin: app://gallr`, and at most 16 KiB.
- Body is exactly `{ "events": [...] }` with 1–20 allowlisted events.
- Success or disabled collection returns `204`.
- Invalid input returns `400`, wrong origin `403`, oversized input `413`, wrong
  content type `415`, invalid configuration `500`, and backend failure `503`.
- Responses contain no event or upstream error detail.

## Verification

```sh
deno task test
deno task check
```

Database verification lives in
`supabase/tests/database/038_mobile_analytics.test.sql` and the repository's
full database workflow.
