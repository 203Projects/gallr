# Feature Specification: Gallery Public Impact

## User stories

### Story 1 — Follow a published listing

As a gallery owner, I can open the canonical public exhibition page from my
workspace after publication, so I can verify and share the same visitor record
that Gallr distributes organically.

### Story 2 — See basic public impact

As a gallery owner, I can see the number of loads of my published exhibition's
public detail page during the last 30 days and across its lifetime, so I have a
simple directional signal before purchasing any launch product.

### Story 3 — Preserve visitor privacy

As a visitor, opening an exhibition page may increment a daily aggregate without
creating a visitor profile, identifier, cookie, or raw request log in Gallr's
impact store. Do Not Track requests are not recorded.

## Acceptance criteria

1. Public detail pages expose their canonical exhibition ID to one small,
   progressively enhanced impact script only when an impact endpoint is
   configured at build time.
2. The browser request contains only the exhibition ID, omits credentials, and
   is skipped when the browser sends Do Not Track.
3. A keyless Edge endpoint accepts only bounded JSON POST requests from an
   allowlist of Gallr public origins and never reads or persists IP address,
   user agent, referrer, cookies, or other visitor identifiers.
4. Recording succeeds only for an existing, currently published, non-archived
   canonical exhibition. Invalid, draft, and archived IDs do not create rows.
5. Counts are stored as one aggregate row per exhibition and UTC day. No public
   role can read or mutate the aggregate table or call its private recorder.
6. Owner exhibition responses include 30-day and all-time page-load counts for
   published exhibitions. Tenant authorization remains derived from the
   authenticated gallery membership; another gallery's impact is never exposed.
7. The owner dashboard and published confirmation label the metric as public
   page loads and explain that it is not a unique-visitor count.
8. Draft, submitted, needs-changes, and archived records do not display impact.
9. Failure to record impact never blocks or changes the visitor page.
10. Database, Edge-handler, public-web, and owner-frontend tests cover the
    privacy boundary, publication checks, origin/method/input validation,
    disabled configuration, DTO validation, and owner-visible labels.

## Out of scope

- Unique visitors, sessions, identities, cross-device attribution, referrers,
  geography, demographics, saves, directions, inquiries, or conversion funnels.
- Bot classification or billing-grade measurement; counts are directional page
  loads and may include automated or repeated requests.
- RSVP, guest lists, launch assets, check-in, paid reports, or promotion.
- Changing Featured, search, map ranking, catalogue ordering, or organic reach.
- Production deployment, DNS, credential changes, or historical backfill.
