# Feature Specification: Editor curation workflow

## User story 1 — Curate ongoing exhibitions

As an invited editor, I can open **My curation**, browse ongoing exhibitions,
select or remove exhibitions, and send the grouped changes to gallr for admin
approval.

### Acceptance criteria

- The candidate list contains only published, non-archived exhibitions whose
  opening date is today or earlier and closing date is today or later.
- An editor sees only unassigned exhibitions and exhibitions assigned to their
  own identity; another editor's attribution is never exposed or replaceable.
- Clicking Add/Remove changes local selection state. No database draft is
  created until **Send for approval** is selected.
- Submission atomically creates the necessary unpublished exhibition drafts
  and one review request containing the exact versions, revisions, and desired
  attribution states.
- An admin can approve the grouped request, publishing only those recorded
  drafts, or reject it with a reason. Stale revisions fail closed.

## User story 2 — Edit own bio

As an invited editor, I can edit the Korean and English bio for my linked
editor identity and send the change for admin approval.

### Acceptance criteria

- The profile form is populated from the editor identity derived from the
  authenticated membership; it accepts no client-supplied editor ID.
- Submitting a bio creates a pending editor request and does not immediately
  mutate `public.editors`.
- An admin can approve the exact submitted copy or reject it with a reason.
- Editors cannot edit their name, title, schedule, visibility, membership, or
  another editor's profile through this workflow.

## User story 3 — Suggest a missing exhibition

As an invited editor, when an ongoing exhibition is absent from the candidate
list, I can submit its essential details as a draft suggestion for admin review.

### Acceptance criteria

- The editor supplies bilingual names, venue, dates, Korean address, hours,
  and optional description. Korean identity fields and a valid date range are
  required.
- The server derives the editor ID and submitter email from authenticated data,
  then inserts an `editor_workspace` exhibition submission.
- The submission appears in the existing admin Submissions queue with an
  **Editor** source label.
- Accepting it creates an unpublished canonical exhibition draft already
  attributed to the submitting editor. Normal staff review and publication
  rules still apply.

## Security and audit requirements

- Editor membership remains separate from staff authorization. Editors cannot
  call staff or admin-review RPCs.
- Editor request approval is limited to active admins. UI visibility is not the
  security boundary.
- Every editor submission and admin decision records actor-attributed audit
  evidence without storing credentials or tokens.
