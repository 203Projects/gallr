# Feature Specification: Gallery Owner Foundation

## User stories

### Story 1 — Resolve owner access

As a signed-in gallery operator, I can open `gallery.gallrmap.com` and see the
single gallery workspace I am currently allowed to use, without receiving any
staff privileges or data from another gallery.

### Story 2 — Request a gallery workspace

As a signed-in gallery operator without access, I can search existing active
galleries and either request ownership of one or create a new pending gallery
claim, so later exhibition work has a durable customer identity.

### Story 3 — Enter the owner workspace

As an owner with a pending or active membership, I can see the My Exhibitions
workspace shell and the correct claim status. A new gallery receives an honest
empty state and no fake analytics or paid-product controls.

## Acceptance criteria

1. A new `content.galleries` record represents the customer organization and
   remains separate from `content.venues`, with an optional canonical venue
   reference for reusable defaults.
2. `content.gallery_memberships` records the authenticated gallery relationship
   with role `owner` and statuses `pending`, `active`, `rejected`, `suspended`,
   and `revoked`.
3. At most one active owner exists for a gallery. Multiple pending claimants do
   not block legitimate review, while one user cannot hold more than one
   pending or active V1 workspace.
4. `content.exhibitions` receives a nullable, indexed `gallery_id`; existing
   exhibitions and public reader projections continue working unchanged.
5. New tables have RLS enabled and browser roles receive no generic table write
   access. Owner access is exposed only through authenticated RPCs whose private
   implementations independently validate `auth.uid()` and membership state.
6. A signed-out or anonymous caller cannot execute owner RPCs.
7. `owner_current_access()` returns only the caller's relevant membership and
   gallery, including suspended state so the UI cannot treat suspension as an
   unclaimed account.
8. `owner_search_galleries()` returns only active gallery identities and safe
   location/display fields. It never returns claim evidence, owner identity, or
   operational contact data.
9. Claiming an existing gallery requires a confirmed email plus at least one
   evidence value: official website, official social URL, or a claim note.
10. Creating a new gallery claim creates the gallery and pending membership in
    one transaction with the same evidence and confirmed-email requirements.
11. Claim commands accept a request UUID and are idempotent for the same actor,
    action, and request UUID. Conflicting reuse is rejected.
12. Claim creation emits one audit record and one durable outbox event without
    placing private evidence or email content in the outbox payload.
13. The owner web application uses the same Supabase project and Auth provider
    as Admin but has its own React/Vite bundle, environment contract, and
    deployment root.
14. The owner sign-in flow uses email OTP. Missing configuration fails closed;
    it never falls back to fixtures in a production build.
15. Accounts without a membership see the gallery search/create onboarding;
    pending and active owners see My Exhibitions; suspended owners see a
    non-destructive access message and can sign out.
16. The owner UI follows `DESIGN.md`: true white, monochrome rules and text,
    sharp corners, Inter + Gothic A1, an 8pt grid, and `#FF5400` only for the
    active indicator and the single primary action.
17. Database and frontend tests cover authorization, cross-gallery isolation,
    idempotency, claim validation, configuration failure, authentication state,
    onboarding, pending access, and suspension.

## Out of scope

- Exhibition draft creation, editing, media, review rounds, or publication.
- Staff claim-verification UI and activation commands.
- Linking existing exhibitions to a gallery during claim review.
- Gallery teams, invitations, ownership transfer, or multiple active owners.
- Public gallery pages, visitor analytics, Launch Kit, billing, or promotion.
- Production deployment, DNS, auth redirect changes, or credential creation.
