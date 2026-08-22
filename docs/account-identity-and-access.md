# Account identity and access architecture

## Invariant: one account plane per environment

Every account-bearing gallr surface in one environment must use the **same
Supabase project and the same `auth.users` identity store**:

- Android and iOS consumer apps
- `gallery.gallrmap.com` gallery-owner portal
- `admin.gallrmap.com` staff portal
- `editor.gallrmap.com` invited-editor portal

Production and staging are intentionally separate environments and therefore
use separate Supabase projects. Do not point one production surface at staging,
or one staging surface at production, in order to make a login work.

Authentication providers do not define authorization. Email/password, email
OTP, Google, and other approved providers all establish a Supabase Auth
identity. Database membership rows determine what that identity may do.

## Self-service identity policy

Production Gallr and Gallery permit approved email, Google, and Apple flows to
create a new identity in the shared account plane. This is consumer account
creation, not role enrollment. A new identity may use consumer features under
RLS and may enter Gallery claim onboarding, but it has no gallery-owner,
editor, or staff authority until the corresponding server-owned membership is
created through its reviewed workflow.

Because the global Supabase signup gate applies to the entire project, it cannot
be used to make Gallr self-service while keeping Gallery identities invite-only.
Restrict privileged products at their membership/RPC boundary instead. Editor
and staff portals must remain fail-closed for authenticated users without their
membership.

Password signup requires verified email in production. Email OTP, OAuth, and
mobile deep links must return only to exact environment-matched URLs. Callback
failures are shown as bounded user-facing categories; clients and logs must not
surface tokens, raw provider payloads, or personal data.

## Identity and role model

`auth.users.id` is the canonical account identifier. Roles are **additive
server-owned memberships**, not one client-selected enum or a mutable claim in
profile metadata. One person may legitimately be a consumer and a gallery owner,
or hold another reviewed combination; every privileged surface still checks its
own membership.

| Account capability | Authoritative relation | States / roles | Authorization boundary |
| --- | --- | --- | --- |
| Consumer user | `auth.users` + `public.profiles` | authenticated user | RLS scoped to `auth.uid()` for private writes; a profile is created from the Auth user trigger |
| Gallery owner | `content.gallery_memberships` | role `owner`; `pending`, `active`, `rejected`, `suspended`, `revoked` | owner RPCs resolve the caller's membership; exhibition submission requires `active` |
| Invited editor | `content.editor_memberships` | active/inactive link to one `public.editors` identity | editor RPCs require an active editor membership and never satisfy staff checks |
| Staff Admin | `content.staff_members` | `contributor`, `publisher`, `admin`; active/inactive | staff RPCs use the server-side hierarchical role helper |

`public.profiles.is_admin` is a legacy read-compatible mirror only. It is derived
from an active `content.staff_members` admin row and must never be used as an
authorization source. A profile owner cannot promote themselves.

## Gallery verification boundary

Gallery authentication and gallery verification are separate:

1. Email OTP or Google authenticates the person into the shared `auth.users`
   account plane.
2. An identity with no gallery membership searches for an existing gallery or
   creates a pending gallery claim with evidence.
3. A pending membership may prepare draft data where explicitly allowed, but it
   cannot submit an exhibition for publication.
4. Staff reviews the evidence in Admin. Approval changes the membership to
   `active`; rejection, suspension, and revocation remain fail-closed.
5. Only active owner membership unlocks the submission path.

Changing or adding an Auth provider must never write a privileged membership or
skip this review.

## Deployment parity gate

Before promoting any account-bearing surface, record or derive the Supabase
project reference without printing it, hash the exact reference, and compare
fingerprints:

1. Production Android, iOS, Gallery, Admin, and Editor fingerprints must all be
   identical.
2. Staging account-bearing surfaces must all share one staging fingerprint.
3. The staging and production fingerprints must be different.
4. Each surface must use the matching environment's publishable browser/mobile
   key. A service-role or secret key never belongs in a client.
5. Auth redirect allow-lists must contain the exact portal origins. Do not use a
   broad preview-domain wildcard.

A successful login on one surface is not proof of parity. Verify the configured
or compiled project reference for every surface. Stop the release if any
fingerprint differs.

## Server-side enforcement rules

- Privileged role tables remain in the non-exposed `content` schema with direct
  client writes revoked.
- Public RPC wrappers expose only the narrow capability needed by a surface and
  re-check `auth.uid()` through private helpers.
- Privileged helpers pin an empty `search_path` and keep least-privilege grants.
- Client-side navigation or hidden buttons are UX only, never authorization.
- New roles require an additive membership relation or a reviewed extension of
  an existing server-owned role type, plus unauthorized-role and cross-tenant
  tests.
