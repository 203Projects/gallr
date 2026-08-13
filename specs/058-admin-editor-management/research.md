# Research: Admin Editor Management

## Decisions

- Use reversible deactivation instead of deletion. Editor IDs are referenced by exhibitions and requests, and historical attribution must remain intact.
- Keep public profile state (`editors.is_active`) separate from workspace access (`editor_memberships.active`). Deactivation disables both; restoring access does not republish.
- Add an editor revision integer. Admin mutations use expected-revision checks to prevent silent concurrent overwrites.
- Keep editor slug and Auth email immutable. Changing either requires identity/Auth migration semantics outside this request.
- Use admin-scoped `SECURITY INVOKER` public wrappers over private `SECURITY DEFINER` implementations with independent admin assertions.
