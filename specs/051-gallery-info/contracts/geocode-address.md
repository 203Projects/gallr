# Contract: Shared geocode-address caller boundary

`POST /functions/v1/geocode-address` retains `verify_jwt = true` and accepts
`{ "address": "..." }` from:

- active contributor/publisher/admin staff;
- active owners for their gallery;
- pending owners only for a still-pending gallery they personally created.

It rejects pending existing-gallery claimants, suspended/revoked/rejected memberships,
inactive staff, other authenticated users, and anonymous callers before provider access.

Success returns `{ "candidates": [...] }`, at most three items. Every item contains bounded
normalized Korean/English addresses, bilingual city/region labels, and string latitude and
longitude. Provider responses, credentials, and internal caller details are not returned.

The generic authorization and quota RPCs are authenticated-only SECURITY INVOKER wrappers.
Quota failure, malformed authorization data, or database unavailability fails closed and
prevents the NAVER request.
