# Editor invitation Edge Function

`invite-editor` lets an active administrator send a Supabase Auth invitation
from the Admin portal using only the editor's email. The invited editor creates
their own unpublished profile after setting a password. Editors and other
signed-in roles cannot invoke this workflow.

The Supabase gateway requires a valid user JWT. The function then calls
`admin_current_staff()` with that JWT and proceeds only when the caller has the
active `admin` role. The service-role credential is used only after this check
to create the invited Auth user. A server-only pending invitation is recorded
through `admin_register_editor_invitation()` using the original administrator
authorization. If that command fails, the function deletes only the Auth user
created by the current request so a retry does not leave an orphaned account.

## Environment

Supabase provides the project URL and project keys to the hosted runtime. Set
these function-specific values through the project secret store:

- `EDITOR_PORTAL_URL`: canonical editor portal origin used for the invitation
  redirect, normally `https://editor.gallrmap.com` in production.
- `INVITE_EDITOR_ALLOWED_ORIGINS`: optional comma-separated origin allowlist.
  Production defaults to `https://admin.gallrmap.com`, because administrators
  initiate invitations; localhost origins are included for local development.

Keep production and staging values in their separate 1Password items. Do not
commit credentials or copy values between environments. Apply the editor
onboarding database migrations before deploying this function.

## Request

The authenticated Admin client sends a `POST` request containing only an email:

```json
{
  "email": "editor@example.com"
}
```

A successful response returns `201` with the email and `invited` status.
Authorization is checked before the request body is read. Invalid requests and
failed invitations return stable, sanitized error codes without exposing
provider responses or credentials.

## Verify locally

The checks require no credentials or network access:

```bash
cd supabase/functions/invite-editor
deno task check
deno task test
```

For staging, verify the target project against the staging 1Password item, apply
migrations through the normal release workflow, configure the staging portal URL
and allowed origin, and then deploy only this function. Acceptance testing must
cover an administrator invitation, an editor denial, invitation redirect, and
editor-owned profile completion before any production deployment. Confirm the
new profile remains unpublished until Admin enables it.
