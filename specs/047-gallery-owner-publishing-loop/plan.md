# Implementation Plan: Gallery Owner Publishing Loop

## Approach

Extend the canonical versioned CMS rather than introduce a second exhibition
store. Owner RPCs create and edit the same `content.exhibitions` and
`content.exhibition_versions` records that staff Admin and public readers already
use. A nullable owner-workflow column adds customer-visible state without
changing legacy staff records or the public publication pointer.

Reuse `content.exhibition_submissions` for durable review rounds by adding a
source and owner-exhibition link. Anonymous submissions keep their current
accept-into-a-new-draft behavior. Account-backed submissions snapshot an
existing owner draft; staff acceptance opens that same draft in Admin, while
rejection becomes an actionable needs-changes round.

## State model

`draft -> submitted -> needs_changes -> submitted -> published -> archived`

- Pending gallery claims can create/save drafts but cannot submit.
- Active gallery owners can submit a complete draft.
- Staff rejection moves only owner-backed submissions to needs changes.
- Staff acceptance preserves submitted state until the existing publish command
  publishes the canonical version.
- Existing publish/archive operations synchronize the owner-visible state.

## Command surface

Owner:

- `owner_list_exhibitions()`
- `owner_create_exhibition_draft(p_request_id)`
- `owner_save_exhibition_draft(...)`
- `owner_reserve_cover_upload(...)`
- `owner_complete_cover_upload(...)`
- `owner_submit_exhibition(..., p_request_id)`

Staff additions:

- `admin_list_gallery_claims(...)`
- `admin_approve_gallery_claim(..., p_request_id)`
- `admin_reject_gallery_claim(..., p_review_notes, p_request_id)`

Existing submission and lifecycle commands are extended for owner-backed review
rounds. All public wrappers are SECURITY INVOKER; private implementations
independently resolve `auth.uid()` and authorize membership/role.

## Frontend structure

- Owner dashboard becomes a compact status table/list with one orange create
  action and review notes inline when needed.
- A focused owner editor owns public fields, save state, validation, cover upload,
  and submit confirmation. It excludes curation and staff-only metadata.
- Staff Admin adds a Gallery claims section and shows the submission source,
  gallery identity, and owner-specific action language in Submissions.
- Existing Admin exhibition editor remains the only publication surface.

## Verification

1. Add and observe failing pgTAP authorization/state/media/review contracts.
2. Apply the additive migration locally and run focused/full database tests.
3. Add and observe failing owner and Admin component/repository tests.
4. Implement the real Supabase adapters and UI state transitions.
5. Run frontend tests, typechecks, builds, migration lineage, and DB lint.
6. Exercise claim approval, owner creation/save/upload/submit, request-changes,
   staff acceptance, and published confirmation in a browser at desktop/mobile.
7. Compare browser screenshots with accepted generated concepts using
   `view_image` and record a fidelity ledger.

## Complexity tracking

| Decision | Added complexity | Why it is justified | Simpler alternative rejected |
|---|---|---|---|
| Reuse canonical exhibition versions | Owner-specific RPC adapter | Prevents a second source of truth and later migration | Separate owner draft table |
| One cover upload | Storage reservation/completion pair | Produces a publishable visitor record without broad media scope | External image URL |
| Review rounds in submissions | Source/link columns and branch behavior | Preserves staff queue/history and avoids duplicate drafts | New parallel review table |
