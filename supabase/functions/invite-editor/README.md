# Editor invitation Edge Function

`invite-editor` lets an active administrator create an editor profile and send
the matching Supabase Auth invitation from the Admin portal. Editors and other
signed-in roles cannot invoke this workflow.

The Supabase gateway requires a valid user JWT. The function then calls
`admin_current_staff()` with that JWT and proceeds only when the caller has the
active `admin` role. The service-role credential is used only after this check
to create the invited Auth user. The database profile and user link are created
through `admin_create_editor_onboarding()` using the original administrator
authorization. If that database command fails, the function deletes only the
Auth user created by the current request so a retry does not leave an orphaned
invitation.

## Environment

Supabase provides the project URL and project keys to the hosted runtime. Set
these function-specific values through the project secret store:

- `ADMIN_PORTAL_URL`: canonical portal origin used for the invitation redirect.
- `INVITE_EDITOR_ALLOWED_ORIGINS`: optional comma-separated origin allowlist.
  Production defaults to `https://admin.gallrmap.com`; localhost origins are
  included for local development.

Keep production and staging values in their separate 1Password items. Do not
commit credentials or copy values between environments. Apply the editor
onboarding database migrations before deploying this function.

## Request

The authenticated Admin client sends a `POST` request containing the editor's
identity, profile biography, separate curation description, and active dates:

```json
{
  "email": "editor@example.com",
  "editor_id": "example-editor",
  "name_ko": "에디터 이름",
  "name_en": "Editor Name",
  "title_ko": "에디터",
  "title_en": "Editor",
  "bio_ko": "에디터 소개",
  "bio_en": "Editor biography",
  "curation_description_ko": "큐레이션 소개",
  "curation_description_en": "Curatorial statement",
  "is_active": true,
  "active_from": "2026-08-11",
  "active_to": null
}
```

A successful response returns `201` with the editor ID, email, names, and active
state. Authorization is checked before the request body is read. Invalid
requests and failed invitations return sanitized error codes without exposing
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
the linked editor profile before any production deployment.
