# gallr editor onboarding guide

Use this guide to invite an editor, prepare their public profile, and publish their exhibition
collection. The production workflow uses Gallr Admin; Google Sheets and Apps Script are retired.

## What gallr supports

- **Personal biography:** `bio_ko` and optional `bio_en`, managed by the editor under **My profile**.
- **Curatorial statement:** `curation_description_ko` and optional
  `curation_description_en`, managed separately under **My curation** and shown above the editor's
  public exhibition collection.
- **Existing exhibitions:** Editors can propose adding or removing ongoing exhibitions from their
  own collection.
- **Missing exhibitions:** Editors can suggest an exhibition that is not yet available. It enters
  the normal Admin submissions workflow as an unpublished draft.
- **Admin review:** Bio and curation changes remain private until an administrator approves them.

Editors cannot publish directly, edit canonical exhibition copy or media, change profile identity
or active dates, manage other editors, or access staff administration. One exhibition currently
supports one `editor_id`; co-curation is not supported.

## Content to collect

Agree on:

- a permanent lowercase editor slug, such as `minjung-kim`;
- Korean and optional English public name and title;
- a short Korean personal bio and optional English translation;
- a Korean curatorial statement of roughly 2–4 sentences and optional English translation;
- `active_from` and optional `active_to` dates; and
- the exhibitions in the collection, including approved images and bilingual copy.

Korean is the required source language. Optional English fields fall back to Korean. Keep the bio
focused on the editor and their practice; use the curatorial statement to explain the idea connecting
the exhibition choices. Do not put private contact information in either field.

## Invite the editor

This step requires an active administrator account.

1. Sign in to `admin.gallrmap.com` and open **Editors**.
2. Enter the invitation email and permanent editor slug.
3. Enter the bilingual name, title, personal bio, and separate curatorial statement.
4. Set `active_from` and optional `active_to`.
5. Leave **Publish profile immediately** off while preparing the collection, or turn it on when the
   editor may appear publicly.
6. Choose **Invite editor**.

The portal creates the editor profile, links the invited Auth user through
`content.editor_memberships`, and sends a password-setup link. The membership—not profile or email
metadata—is the authorization boundary. An editor account is linked to exactly one editor slug.

Treat the slug as permanent. If a correction is unavoidable, migrate every reference in one
reviewed database change.

## Editor workflow

After setting a password, the editor signs in at `editor.gallrmap.com` and
receives a restricted workspace containing only **My curation**, **My
profile**, and sign-out. Staff continue to use `admin.gallrmap.com`; signing in
on the wrong hostname routes the account to the correct portal.

### Update the personal bio

1. Open **My profile**.
2. Edit the Korean bio and optional English translation.
3. Choose **Send bio for approval**.

The current public bio remains unchanged while the proposal is pending. Identity, title, schedule,
visibility, and other editors' profiles remain administrator-managed.

### Curate existing ongoing exhibitions

1. Open **My curation** and confirm the correct public editor name.
2. Review or edit the bilingual **Curatorial statement**. It is independent from the personal bio.
3. Search ongoing exhibitions by exhibition or venue name.
4. Choose **Add** or **Remove**. Unsent decisions can be reversed locally.
5. Choose **Send for approval** when the grouped changes are ready. A statement-only request is
   allowed.

Row states have these meanings:

- **Live:** already part of the published collection.
- **Unsent addition/removal:** changed locally but not yet submitted.
- **Awaiting approval:** included in the pending editor request.
- **Awaiting publication/removal awaiting publication:** represented in an unpublished exhibition
  draft waiting for Admin publication.

The editor portal never publishes directly and excludes exhibitions assigned to another editor.

### Suggest a missing exhibition

If an ongoing exhibition is absent, choose **Suggest missing exhibition**. Enter its Korean name,
venue, dates, Korean address, hours, and any available bilingual description. The suggestion enters
**Submissions** with source **Editor**. Acceptance creates an unpublished canonical draft attributed
to that editor; staff must still verify its content, media, address, and map position before
publication.

## Admin review

### Manage editor profiles and access

1. Open **Editors → Manage editors** to see every editor, including unpublished
   profiles, removed workspace access, and legacy identities without an account.
2. Choose **Edit** to update bilingual names, titles, biography, curatorial
   statement, visibility, or active dates. The permanent slug and account email
   are intentionally read-only.
3. Choose **Deactivate** and confirm to remove workspace access and hide the
   public profile. This is reversible and preserves the Auth account, editor
   identity, exhibition attribution, pending requests, and audit history.
4. Choose **Restore access** to return the editor to My curation. Restoration
   leaves the public profile unpublished; use Edit to publish deliberately.

Management commands are admin-only and revision-checked. If another
administrator changed the record first, the portal reloads the latest revision
instead of overwriting it. Never hard-delete an editor for routine offboarding.

### Review editor requests

1. Open **Editors → Editor requests**.
2. Review the exact proposed bio or curatorial statement and each exhibition decision.
3. Approve the request, or enter a reason and reject it.

Approving a curation request publishes the statement and grouped editor associations together.
Rejecting it preserves the current public statement and attribution. Bio proposals are reviewed and
published separately.

### Prepare and publish exhibitions

For every new or changed exhibition draft:

1. Complete the Korean name, venue, address, city/region, opening date, and closing date.
2. Review coordinates, public contact details, optional English copy, hours, reception time, and
   ticket URL.
3. Attach approved cover/gallery media with required rights and credits.
4. Confirm the editor association from the Admin lookup. Use **No editor attribution** to remove one;
   never type an unverified ID or hard-delete an editor.
5. Preview the exact draft and resolve revision conflicts instead of overwriting another change.
6. Publish through the revision-checked Admin command.
7. Confirm the published projection and public image delivery.

Drafts remain private until publication. Publishing replaces the public snapshot transactionally
while preserving the permanent exhibition ID and version history.

## Verify in the app

1. Open **List → Editors** and confirm the editor appears in the correct current or past section.
2. Open the editor page and verify the name, title, personal bio, curatorial statement, exhibition
   count, and linked exhibitions.
3. Confirm the collection introduction uses the curatorial statement and does not substitute the
   personal bio.
4. Open every exhibition and verify its image, copy, dates, venue, and map position.
5. Repeat in Korean and English.

If the editor is missing, check the active-date window, `is_active`, published exhibition
associations, and the public projection. Use Admin and audit evidence; there is no supported Sheet
fallback or Apps Script execution log.

## Ongoing maintenance

- Editors may propose their own bio and curatorial statement through the restricted workspace.
- Administrators maintain identity, title, dates, visibility, canonical exhibition drafts, and
  publication.
- Leave an expired editor active to retain them in the past-editor archive. Deactivate only when the
  editor should disappear from public discovery.
- Avoid changing a published slug.
- Treat portrait support, co-curation, or direct editor self-publishing as separate product changes
  requiring specification and review.
