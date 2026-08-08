# RPC contract

`owner_hide_exhibition(p_exhibition_id text, p_expected_version_id uuid,
p_expected_revision integer) -> jsonb`

Returns `{ "id": string, "hidden": true }`. The public wrapper is
`SECURITY INVOKER` with an empty search path and executable only by
`authenticated`. Authorization and canonical writes live in the private
implementation. Errors are tenant-safe and include `revision_conflict` for a
stale displayed record.
