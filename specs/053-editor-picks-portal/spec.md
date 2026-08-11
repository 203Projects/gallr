# Feature Specification: Editor picks portal

## User story

As an invited gallr editor, I can sign in to the admin portal and manage only
the exhibitions in my own editor collection without receiving staff access.

## Requirements

- An editor account is explicitly linked to one `public.editors` row. This
  link is separate from `content.staff_members`; profile metadata is never an
  authorization source.
- After authentication, an active linked editor enters a dedicated **My picks**
  workspace. Staff continue to enter the existing admin workspace.
- The editor workspace lists published, non-archived exhibitions that are
  either unassigned or assigned to that editor in the current working version.
  Search covers exhibition and venue names.
- An editor may add only their own `editor_id`, remove only their own
  `editor_id`, and may never overwrite another editor's assignment.
- Each accepted change creates or updates an unpublished draft with optimistic
  version/revision checks. It does not publish or mutate the published snapshot.
  Existing media and exhibition content are preserved when a published version
  is cloned.
- Editors receive no access to exhibition fields, media, publication,
  lifecycle actions, submissions, gallery claims, promotions, or staff RPCs.
- The workspace distinguishes live picks, additions awaiting publication, and
  removals awaiting publication, and explains that gallr staff publishes the
  pending draft.
- Every accepted pick change records the authenticated actor, editor ID,
  previous assignment, requested state, and working version in the audit log.
- No deployment, production data mutation, credential creation, staging,
  commit, or invitation email is part of this feature.

## Acceptance scenarios

1. An active editor membership resolves to the editor portal with its own
   editor ID; staff access continues to resolve unchanged.
2. An editor adds an unassigned published exhibition and receives a draft that
   preserves its content and media while the public published version remains
   unchanged.
3. The same editor removes their own pick, including canceling an unpublished
   addition; stale revisions fail closed.
4. An editor cannot see or replace another editor's working assignment and
   cannot call the existing admin query or command API.
5. The editor UI renders only My picks, performs one scoped mutation per
   confirmed toggle, and reports pending versus live status.
