# Legacy catalogue mirror coordinator

This server-only Seoul Edge Function reads both complete public exhibition
catalogues plus their event/editor dependencies and sends one snapshot to the
authenticated Singapore receiver. The snapshot includes `exhibition_catalog_v2`,
which is the reader used by iOS 1.7.4 and 1.7.5, with its database-derived
content checksums, country/gallery identity, and structured artist/art-term
arrays. It never stores or receives a Singapore database credential.

It accepts only authenticated `outbox` and `five-minute-reconciliation` POSTs.
The source URL is pinned to the reviewed Seoul project and the receiver URL is
pinned to the reviewed Singapore function. Empty legacy or canonical-v2
exhibition snapshots and invalid receipts fail retryably.

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
