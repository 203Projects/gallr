# Legacy catalogue mirror coordinator

This server-only Seoul Edge Function reads the complete public mobile catalogue
and sends one snapshot to the authenticated Singapore receiver. It never stores
or receives a Singapore database credential.

It accepts only authenticated `outbox` and `five-minute-reconciliation` POSTs.
The source URL is pinned to the reviewed Seoul project and the receiver URL is
pinned to the reviewed Singapore function. Empty exhibition snapshots and
invalid receipts fail retryably.

## Seoul secrets

- `LEGACY_CATALOG_MIRROR_TOKEN`: inbound token shared only with
  `outbox-delivery` and the Seoul Vault scheduler.
- `LEGACY_CATALOG_RECEIVER_URL`: exact Singapore receiver URL.
- `LEGACY_CATALOG_RECEIVER_TOKEN`: token shared only with the Singapore
  receiver.
- `LEGACY_CATALOG_MIRROR_REASON`: operator/change-record prefix.
- A component-scoped `legacy-catalog-mirror` Supabase secret key supplied by the
  hosted project key map. This is the Seoul key only.

The function is inert until callers and secrets are configured. Verify with:

```sh
deno task test
deno task check
```
