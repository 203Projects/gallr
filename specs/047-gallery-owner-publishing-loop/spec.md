# Feature Specification: Gallery Owner Publishing Loop

## User stories

### Story 1 — Verify a gallery claim

As a publisher in staff Admin, I can review a pending gallery claim and approve
or reject it with notes, so exhibition submission rights are granted deliberately
instead of through UI-only trust.

### Story 2 — Prepare an exhibition

As a pending or active gallery owner, I can create and save exhibition drafts for
my gallery, including the public identity, venue, schedule, description, and one
cover image. I cannot read or mutate another gallery's records.

### Story 3 — Submit for review

As an active gallery owner, I can submit a complete draft for staff review. A
pending claim may prepare drafts but cannot submit. Submitted and published
records are read-only in the owner workspace.

### Story 4 — Complete editorial review

As a staff publisher, I can review an owner submission in the existing dedicated
Submissions workspace, request changes with a reason, or accept the existing
canonical draft into the staff exhibition editor without creating a duplicate.
Publication continues through the existing staff-only publish command and keeps
Featured curation separate.

### Story 5 — Follow status

As a gallery owner, I can see Draft, Submitted, Needs changes, Published, or
Archived for each exhibition. Needs changes shows the latest staff note;
Published exposes the public Gallr link and a non-paid “Launch this exhibition”
next-step placeholder without changing organic discovery.

## Acceptance criteria

1. `content.exhibitions` gains a nullable owner-workflow status and latest review
   note. Legacy/staff-only records remain compatible and are not reclassified.
2. Owner-managed exhibitions always have `gallery_id`, a working canonical
   exhibition version, and status `draft` at creation.
3. Draft creation seeds venue/location defaults from the gallery's canonical
   venue when available. Gallery identity and venue snapshots remain distinct.
4. Pending and active owners may list, create, and save only their own gallery's
   editable drafts. Only active owners may submit.
5. Submitted, published, and archived owner records cannot be changed through
   owner save/media commands. Needs-changes records may be edited and resubmitted.
6. Owner patches are allowlisted and cannot change curation, publication,
   editorial event/editor assignment, gallery ownership, or another version.
7. Owner writes use optimistic revision checks. A stale version/revision fails
   without silently overwriting newer staff or owner work.
8. One JPEG, PNG, or WebP cover up to 10 MB can be reserved, uploaded to a
   user-scoped private path, verified against Storage metadata, and attached to
   the editable working version. No generic media-table write grant is added.
9. Submission validates the owner-supplied minimum: Korean exhibition/venue
   identity, city/region/address, valid date range, hours, and a ready or
   published cover. Staff publication retains the stricter existing coordinate
   requirement. Submission creates one durable snapshot and outbox event.
10. Resubmission creates a new review row; previous rounds remain immutable
    review history. Replayed request IDs are idempotent and conflicting reuse is
    rejected.
11. Staff claim approval/rejection requires publisher role, is idempotent, and
    records audit/outbox evidence. Rejection requires a bounded note.
12. Staff review distinguishes account-backed owner submissions from anonymous
    public submissions. Accepting an owner submission returns its existing
    canonical draft for the staff editor and never creates a duplicate identity.
13. Requesting changes rejects only that review round, moves the owner workflow
    to `needs_changes`, and exposes the bounded note to that exhibition's owner.
14. Existing staff publication and archive commands move owner workflow state to
    `published` and `archived`; existing public reader pointers remain the only
    public-read authority.
15. Editorial Featured and homepage placement stay staff-only and are never
    presented as a paid or owner-controlled field.
16. The owner UI has a list dashboard, responsive editor, review status, public
    link confirmation, and working cover upload. It follows `DESIGN.md` and the
    accepted owner workspace visual system.
17. Database and frontend tests cover claim review, tenant isolation, pending
    submission denial, revision conflicts, media path isolation, state
    transitions, duplicate prevention, and malformed repository payloads.

## Out of scope

- Multiple gallery users, invitations, ownership transfer, or teams.
- More than one owner-uploaded cover, gallery image ordering, video, or PDFs.
- Owner-side geocoding for off-site venues; staff may correct/geocode during
  review before publication.
- Owner edits to a published exhibition or owner-requested archival.
- RSVP, guest lists, check-in, launch assets, analytics, billing, or promotion.
- Production deployment, DNS, Auth redirect activation, or credential changes.
