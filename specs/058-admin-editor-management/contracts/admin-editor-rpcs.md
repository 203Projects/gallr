# Contract: Admin Editor RPCs

All public wrappers are executable only by `authenticated`; each private implementation independently requires an active `admin` staff membership.

## `admin_list_editors()`

Returns every editor as JSON with profile fields, active dates, revision, optional membership email, `has_access`, and `access_active`.

## `admin_update_editor(...)`

Requires editor ID, expected revision, bilingual profile/curation fields, public active state, and date range. Returns the updated editor JSON. A stale revision raises `40001 editor_revision_conflict`.

## `admin_set_editor_access(p_editor_id, p_expected_revision, p_active)`

Requires a linked membership. Deactivation also hides the public profile; restoration leaves it hidden. Returns the updated editor JSON. A stale revision raises `40001 editor_revision_conflict`.
