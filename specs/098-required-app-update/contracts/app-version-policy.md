# Contract: app version policy

## Read (app, anonymous)

`POST /rest/v1/rpc/get_app_version_policy` with `{"p_platform": "android" | "ios"}`.

- `200` with `{"minimum_version": "N.N.N", "recommended_version": "N.N.N"}`, or `null` when no row exists.
- Any other status, a body that is not that shape, or a version that does not parse as three numbers: the app treats
  the read as failed and keeps its last answer (spec FR-005). Nothing else is sent.

## List (Admin, staff)

`POST /rest/v1/rpc/admin_list_app_version_policy` with the staff member's access token and no body.

- `200` with an array ordered by platform: `[{platform, minimum_version, recommended_version,
  latest_released_version, updated_at, updated_by, updated_by_email}]`. `updated_by_email` is the staff member's
  sign-in email, null for the seeded row.
- Raises `42501` for a caller who is not an active admin.

## Write (Admin, staff)

`POST /rest/v1/rpc/admin_set_app_version_policy` with the staff member's access token and
`{"p_platform", "p_minimum", "p_recommended", "p_latest_released" (nullable)}`.

- `200` with the stored row in the same shape as the listing.
- Raises `42501` for a caller who is not an active admin and `22023` for an unknown platform, a version that is not
  `N.N.N`, or `recommended < minimum`; the previous row is unchanged.

## Comparison

Versions compare by major, then minor, then patch, numerically: `1.13.1 < 1.14.0 < 1.14.10`. A suffix after the third
number is ignored.
