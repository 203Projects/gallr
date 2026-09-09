# Legacy catalogue mirror receiver

This server-only Edge Function is deployed only in the frozen Singapore
compatibility project. It accepts a complete snapshot from the reviewed Seoul
coordinator and invokes the local guarded replacement RPC. The guarded RPC
applies both `exhibitions` and `exhibition_catalog_v2` atomically, independently
derives canonical-v2 checksums on the target, preserves authoritative country,
gallery, and structured art metadata, and rejects any mismatch. During the
database-first rollout it also accepts the immediately previous snapshot shape
with both metadata arrays absent and normalizes that pair to empty arrays.

The receiver validates a dedicated `LEGACY_CATALOG_RECEIVER_TOKEN`, the Seoul
project ref, payload size, and target project identity. Its component-scoped
`legacy-catalog-mirror-receiver` Supabase secret key is local to Singapore and
is never copied into Seoul.

Deploying the function is inert until the Singapore target configuration is
enabled by the production change gate. Verify with:

```sh
deno task test
deno task check
```
