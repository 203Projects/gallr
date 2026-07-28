# Feature Specification: Permanently Delete Never-Published Drafts

## User story

As a Gallr administrator, I can permanently delete an accidental exhibition
draft so the Admin list does not accumulate disposable records.

## Acceptance criteria

1. A signed-in administrator sees **Delete permanently** only when the selected
   exhibition is an active draft that has never been published.
2. Publishers and contributors cannot permanently delete exhibitions.
3. Published and archived identities continue to use Archive/Restore and cannot
   be hard-deleted through the Admin application.
4. The confirmation dialog explains that deletion cannot be undone and requires
   the administrator to type `DELETE`.
5. The database command requires the exact working-version ID and revision.
6. The database rejects deletion when the identity has ever been published or
   when it has attached media, import provenance, accepted submissions, curation
   placements, or outbox events.
7. Successful deletion removes the draft version and exhibition identity,
   preserves prior audit rows, and appends an `exhibition.draft_deleted` audit
   row containing the deleted identity and version metadata.
8. Successful deletion removes the record from the current Admin list and
   closes the inspector.

## Out of scope

- Hard-deleting published or archived editorial history.
- Deleting Storage objects or media assets.
- Bulk deletion.
- Deleting legacy `public.exhibitions` rows.

