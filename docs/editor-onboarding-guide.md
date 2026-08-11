# gallr New Editor Onboarding Guide

Use this guide to add a new editor and publish their exhibition collection in the gallr mobile app.

## What gallr supports

- **Editor bio:** Yes. gallr supports `bio_ko` and `bio_en` as the editor's personal biography, managed under **My profile**.
- **Exhibition description:** Yes. Each exhibition supports `description_ko` and `description_en`, shown on that exhibition's detail page.
- **A separate collection description or curatorial statement:** Yes. `curation_description_ko` and `curation_description_en` introduce the editor's collection at the top of the public Editors experience and are managed under **My curation**.
- **Editor portal:** Yes. An invited editor can use **My curation** to stage changes across ongoing exhibitions, submit a missing exhibition, and send the grouped changes for review.
- **Editor bio:** Editors can propose changes to their own Korean and English bio under **My profile**. An admin must approve the submitted copy before the public profile changes.
- **Editor self-publishing:** Not currently. Editors cannot directly publish, edit canonical exhibition records or media, change profile identity/schedule, or touch another editor's collection.
- **One editor per exhibition:** An exhibition has one `editor_id`. The current data model does not support co-curation by multiple editors.

## Before you begin

The editor and gallr admin should agree on:

- The editor's public name and title in Korean and English
- A short editor bio in Korean and English
- A curatorial statement for the collection in Korean and English
- The collection's launch date and, if applicable, end date
- The list of exhibitions to include
- Who will maintain the collection after launch

Korean is the required source language. English fields may be left blank; when English is missing, the app falls back to Korean.

## Step 1 — Complete the editor profile

Send the following information to the gallr admin:

| Field | What to provide | Example |
|---|---|---|
| `name_ko` | Public Korean name | 김민정 |
| `name_en` | Public English name | Minjung Kim |
| `title_ko` | Korean role or title | 독립 큐레이터 |
| `title_en` | English role or title | Independent Curator |
| `bio_ko` | Korean personal biography | 서울을 기반으로 신진 작가와 대안 공간을 연구합니다. |
| `bio_en` | English personal biography | A Seoul-based curator researching emerging artists and independent spaces. |
| `curation_description_ko` | Korean statement introducing this exhibition collection | 서울 곳곳에서 빛과 공간의 관계를 새롭게 보여주는 전시를 연결합니다. |
| `curation_description_en` | English statement introducing this exhibition collection | Connecting exhibitions that reconsider light and space across Seoul. |
| `active_from` | Collection launch date, `YYYY-MM-DD` | 2026-08-01 |
| `active_to` | Last day as a current editor, or blank for no end date | 2026-09-30 |

Writing guidance:

- Keep the personal bio focused on who the editor is and their practice.
- Keep the curatorial statement to roughly 2–4 sentences so it reads well above the exhibition collection.
- Use the statement to explain the idea connecting the exhibition choices; do not duplicate the biography.
- Do not put private contact information in the bio.

## Step 2 — Receive the editor ID and portal invite from gallr

The gallr admin creates a stable editor ID, also called a slug. It should use lowercase letters, numbers, and hyphens only.

Example: `minjung-kim`

Use this exact value in the `editor_id` column for every exhibition in the collection. Treat it as permanent: changing it later breaks the connection between the editor and their exhibitions unless every linked row is also updated.

The gallr admin opens **Editors** at `admin.gallrmap.com` and submits the email
and profile once. The portal sends the Supabase Auth invitation and links that
new user to `content.editor_memberships`; no direct database editing or shared
staff password is required. The invitation link returns the editor to the
portal to set their first password before opening **My curation**.

An editor account is linked to exactly one editor ID. The link is the authorization boundary and must be created by a gallr admin, not inferred from profile or email metadata.

## Step 3 — Curate existing ongoing exhibitions

Use this path when the exhibitions already exist in gallr:

1. Sign in to the gallr admin portal.
2. Confirm the page says **My curation** and shows the correct public editor name.
3. Review or edit the bilingual **Curatorial statement**. Korean is required; this copy is distinct from the bio under **My profile**.
4. Search the ongoing exhibitions by exhibition or venue name.
5. Choose **Add** or **Remove**. These changes remain unsent and can be reversed without creating a database draft.
6. Select **Send for approval** once the statement and exhibition choices are ready. A statement-only request is allowed. The portal creates unpublished drafts where needed and one grouped admin review request.
7. Check the state shown on each row:
   - **Live** — already in the published collection.
   - **Awaiting publication** — added in the pending draft.
   - **Removal awaiting publication** — still live, with removal waiting in the draft.
8. The admin reviews the exact statement and exhibition decisions under **Editors → Editor requests**. Approval publishes them together; rejection keeps the current public statement and restores the previous attribution.

The editor portal never publishes directly. It also hides exhibitions assigned to another editor, so a selection cannot replace someone else's attribution.

## Step 4 — Suggest a missing exhibition

If an ongoing exhibition does not appear, choose **Suggest missing exhibition** in **My curation**. Enter its Korean name, venue, dates, Korean address, hours, and any available bilingual description. The suggestion enters the existing admin **Submissions** queue with source **Editor**. Acceptance creates an unpublished canonical draft already attributed to that editor; staff must still verify and publish it.

During the remaining legacy Sheet transition, an admin may still use the master gallr exhibition Google Sheet for bulk intake. A separate Google Sheet is not required for each editor.

### Where the fields go

- Put field names such as `name_en`, `description_ko`, and `editor_id` in **row 1 as column headers**.
- Put each exhibition on its own row below the header row.
- Column order does not matter because the sync matches columns by their header names.
- If a listed column is missing from the master sheet, a gallr admin can add it once to row 1. Do not create duplicate columns for each editor.
- Add the editor's slug to the `editor_id` cell on each of their exhibition rows.
- The editor's personal name, title, and bio do **not** go in this sheet. A gallr admin enters those fields in Supabase's `editors` table.

An editor may use a separate spreadsheet as a private drafting template, but the admin must copy the approved rows into the existing master exhibition sheet before they can sync to the app. The current sync only reads its bound master sheet and does not merge separate editor spreadsheets.

If the master sheet already has a `status` column, set the new rows to `approved`. If the sheet does not have a `status` column, do not add one casually: once it exists, every production row that should remain published must also be set to `approved`.

Required fields:

| Field | Rule |
|---|---|
| `name_ko` | Korean exhibition name; required |
| `venue_name_ko` | Korean venue name; required |
| `city_ko` | Korean city; required |
| `region_ko` | Korean district or region; required |
| `opening_date` | Required; use `YYYY-MM-DD` |
| `closing_date` | Required; use `YYYY-MM-DD` |
| `editor_id` | Required for this collection; use the exact slug supplied by gallr |

Recommended fields:

| Field | Purpose |
|---|---|
| `name_en` | English exhibition name |
| `venue_name_en` | English venue name |
| `city_en`, `region_en` | English location labels |
| `description_ko`, `description_en` | Exhibition-specific description shown on its detail page |
| `address_ko`, `address_en` | Full venue address |
| `cover_image_url` | Full HTTPS image URL, or an approved filename already uploaded by gallr |
| `latitude`, `longitude` | Numeric map coordinates |
| `hours` | Opening hours as display text |
| `contact` | Public email, phone number, or contact text |
| `reception_date` | Reception/opening event date and time |
| `opening_time` | Opening time as display text |
| `status` | If the sheet has this column, use `approved` to publish |

Example row:

| name_ko | name_en | venue_name_ko | city_ko | region_ko | opening_date | closing_date | description_ko | description_en | editor_id | status |
|---|---|---|---|---|---|---|---|---|---|---|
| 경계의 빛 | Light at the Edge | 갤러리 예시 | 서울 | 용산구 | 2026-08-01 | 2026-09-15 | 빛과 공간의 경계를 탐구하는 전시입니다. | An exhibition exploring the boundary between light and space. | minjung-kim | approved |

Important:

- Enter the same `editor_id` on every exhibition that belongs to the collection.
- An editor does not appear in the Editors screen until at least one linked exhibition has a `closing_date` of today or later.
- Closed exhibitions are not shown on the editor's collection page.
- Changing an exhibition's Korean name, Korean venue name, Korean city, or opening date may generate a new exhibition ID and can affect existing bookmarks.

## Step 5 — Submit images and descriptions

For every exhibition:

1. Confirm that gallr has permission to display the cover image.
2. Provide a landscape-friendly, high-quality image without unnecessary overlays where possible.
3. Add a concise Korean description in `description_ko`.
4. Add `description_en` when available. If it is blank, English-mode users see the Korean description.
5. Credit the artist, photographer, or venue in the description if the supplied usage terms require it.

The editor profile does not currently have a portrait/avatar field. A profile image would require an app and database change.

## Step 6 — Review before publishing

Check every row against this list:

- [ ] The `editor_id` exactly matches the ID supplied by gallr.
- [ ] All six required exhibition fields are filled.
- [ ] Dates use `YYYY-MM-DD`, and the closing date is not earlier than the opening date.
- [ ] Korean names and location labels are correct.
- [ ] English text is included where available.
- [ ] Image links open without requiring a login.
- [ ] Latitude and longitude are numbers, if provided.
- [ ] The public description and contact details are approved for publication.
- [ ] `status` is `approved`, if the sheet uses a status column.

## Step 7 — gallr admin publishes the collection

This step requires gallr admin access.

1. Sign in to `admin.gallrmap.com` with an active administrator account.
2. Open **Editors** in the primary navigation. Contributors, publishers, and editors cannot open this workspace.
3. Enter the invitation email and agreed lowercase slug.
4. Enter the bilingual name, title, personal bio, and distinct curatorial statement, then set `active_from` and optional `active_to`.
5. Leave **Publish profile immediately** off while preparing the collection, or turn it on when the editor may be publicly visible.
6. Choose **Invite editor**. The portal creates the editor profile and restricted membership and emails the password-setup link.
7. Review profile and curation requests in **Editors → Editor requests**. Review missing-exhibition suggestions in **Submissions**.
8. Publish the approved exhibition drafts through the existing admin workflow.
9. If a new exhibition still came through the legacy Sheet path, run `syncToSupabase()` or wait for the schedule, confirm a successful execution, and then verify the canonical draft before publication.

Publishing behavior to remember:

- The editor row must exist first. A spreadsheet row with an unknown `editor_id` is skipped.
- `is_active = false` hides the editor record from the public app.
- To keep an editor available under **Past editors**, leave `is_active = true` after `active_to`. Set it to `false` only when the editor should disappear completely.
- The legacy sync upserts authoritative rows and removes stale rows within its sync scope. Do not run it from a partial or filtered source sheet, because omitted rows can be treated as stale.

## Step 8 — Verify in the app

After the sync:

1. Open gallr and go to the **List** tab.
2. Tap **EDITORS ›** / **에디터 ›**.
3. Confirm the editor appears under **Currently curating** / **현재 큐레이션**.
4. Open the editor and confirm the name, title, curatorial statement, and exhibition count. The collection banner must not substitute the personal bio.
5. Open every exhibition and confirm its description, image, dates, venue, and map location.
6. Switch the app between Korean and English and verify both versions.

If the editor is missing, check in this order:

1. The editor has `is_active = true`.
2. At least one linked exhibition closes today or later.
3. That exhibition's `editor_id` exactly matches `editors.id`.
4. The exhibition row is approved, if `status` exists.
5. The Apps Script execution did not skip the row.

## Updating the collection later

- Editors can propose their own bio under **My profile**. Names, titles, dates, and visibility remain administrator-managed.
- Editors can edit their collection's curatorial statement under **My curation** and submit it alone or together with exhibition changes for admin approval.
- Add, edit, or remove exhibitions in the source Google Sheet, keeping the same `editor_id`.
- Re-run the exhibition sync after exhibition changes.
- Avoid changing the editor slug after launch.
- Remember that expired exhibitions automatically disappear from the editor collection because the app only shows exhibitions closing today or later.

## Ownership and support

Before launch, fill in these contacts:

- gallr content admin: ____________________
- Technical/sync support: ____________________
- Image-rights contact: ____________________
- Expected publication date: ____________________
