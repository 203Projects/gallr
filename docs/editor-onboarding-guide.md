# gallr editor onboarding guide

Use this guide to add a guest editor and publish their exhibition collection. The production
workflow is Admin-only; Google Sheets and Apps Script are retired.

## Content to collect

Agree on:

- a permanent lowercase editor slug, such as `minjung-kim`;
- Korean and English public name and title;
- a short Korean bio and optional English translation;
- `active_from` and optional `active_to` dates; and
- the exhibitions in the collection, including approved images and bilingual copy.

Korean is the required source language. The app falls back to Korean when optional English text is
blank. One exhibition currently supports one `editor_id`.

## Create the editor

This step requires staff access.

1. Sign in to Gallr Admin.
2. Create the editor record before linking exhibitions.
3. Enter the permanent slug, bilingual name, title, bio, and active dates.
4. Keep the editor inactive while preparing the collection; activate it when it may appear publicly.
5. Do not change a published slug. If a correction is unavoidable, migrate every reference in one
   reviewed database change.

## Prepare exhibitions

Create or open each exhibition draft in Admin and complete the normal publication fields:

- Korean name, venue, address, city/region, opening date, and closing date;
- reviewed coordinates and public contact details;
- optional English copy, description, hours, reception time, and ticket URL;
- approved cover/gallery media with required rights and credits; and
- the editor association selected from the Admin lookup.

The lookup uses stable IDs and includes inactive historical references. Do not type an unverified ID
or hard-delete an editor to remove an association; select **No editor attribution** on the draft.

## Review and publish

For each exhibition:

1. Preview the exact draft in Admin.
2. Confirm the editor, dates, bilingual copy, image rights, address, and map position.
3. Resolve any revision conflict instead of overwriting another editor's changes.
4. Publish through the revision-checked Admin command.
5. Confirm the published projection and public image delivery complete successfully.

Drafts remain private until publication. A published update replaces the public snapshot
transactionally while preserving the permanent exhibition ID and version history.

## Verify in the app

1. Open **List → Editors** and confirm the editor appears in the correct current/past section.
2. Open the editor page and verify the name, title, bio, collection count, and linked exhibitions.
3. Open every exhibition and verify its image, copy, dates, venue, and map position.
4. Repeat in Korean and English.

If an editor is missing, check the active-date window, editor activation state, published exhibition
associations, and public projection. Use Admin/audit evidence; there is no Apps Script execution log
or supported Sheet fallback.

## Ongoing maintenance

- Edit the editor record and exhibition drafts through Admin.
- Publish each reviewed change; never update the public projection directly.
- Leave an expired editor active to keep them in the past-editor archive. Deactivate only when they
  should disappear from public editor discovery.
- Treat a separate collection statement, editor portrait, co-curation, or editor self-publishing as
  a product change requiring its own specification and review.
