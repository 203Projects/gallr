# Gallery-owner submission control

## User stories and acceptance criteria
1. As an active gallery owner, withdraw a submitted or in-review exhibition to edit it. The open review round becomes withdrawn, the draft and cover remain, edits are enabled, and resubmission creates a fresh review round. An accepted or published round cannot be withdrawn. Stale revisions and foreign galleries fail without mutation.
2. As an eligible gallery owner, discard an unpublished draft, including one still in review. Confirm the action; atomically withdraw the open review and remove the draft from the owner list. Preserve audit, media, and historical review records. Published or accepted work cannot be discarded. Existing published-list hiding retains its semantics.
3. Publication notification is fulfilled by companion PR #275 (owner_exhibition.published). This branch must not add a second publication event or email. Acceptance alone must not claim publication.

## Boundaries
No remote deployment, provider credential change, or actual email sending in this implementation. Public web rebuild remains asynchronous; notification copy explains the short delay. Discard is an archival action, not permanent erasure.
