# Feature Specification: Admin editor onboarding

> Superseded for new invitations by
> [`059-editor-email-invitation`](../059-editor-email-invitation/spec.md).
> This document preserves the original combined onboarding contract as history.

## User story

As a gallr administrator, I can invite a new editor and create the editor's
public profile from the admin portal so the editor can enter the scoped My
picks workspace.

## Requirements

- The primary navigation exposes **Editors** only as an enabled destination to
  an active `admin`. Contributors and publishers see it disabled; editor
  accounts remain in the separate My picks portal.
- The onboarding form collects an invitation email, editor slug, Korean and
  English profile copy, active dates, and public visibility.
- The invitation is sent by a Supabase Edge Function. The browser never
  receives a service key and cannot call the Auth admin API directly.
- Before reading the request body or sending an invitation, the Edge Function
  resolves the caller through the authoritative staff RPC and requires the
  active `admin` role. Editor, contributor, publisher, inactive, and anonymous
  callers fail closed.
- The database command independently requires an authenticated active admin,
  inserts the `public.editors` row and `content.editor_memberships` link, and
  records actor-attributed audit evidence.
- Slugs are lowercase URL-safe identifiers. Required copy is nonblank, dates
  are valid and ordered, and duplicate editor or membership identities fail.
- A successful response confirms the invited email and created editor. No
  credential, token, or invitation link is exposed in logs or the browser.

## Acceptance scenarios

1. An admin opens Editors, submits a valid profile, and sees confirmation that
   the editor was invited and linked to My picks.
2. A contributor or publisher cannot navigate to Editors and a direct Edge
   Function request is rejected before the invitation backend runs.
3. An editor session cannot use the Edge Function or database command even if
   it calls either endpoint directly.
4. Malformed profile data is rejected without an invitation or partial editor
   row.
5. The database command creates exactly one editor/membership pair and an
   `editor.created` audit record attributed to the admin.
