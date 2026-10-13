# Feature Specification: Gallery Profile Images

**Feature Branch**: `089-gallery-profile-images`
**Created**: 2026-10-09
**Status**: Draft
**Input**: User description: "Galleries currently render as a plain 3-letter text monogram, which looks bland and indistinct (most visibly in the followed-gallery list used for new-exhibition notifications). Find and show a representative image for each gallery, museum, or art space: its own logo or a photo of the space, never exhibition artwork."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Recognise followed galleries at a glance (Priority: P1)

A visitor who follows galleries for new-exhibition alerts opens My Gallr. Each followed
gallery row shows the gallery's own logo or a photo of its space in the square slot
beside its name, so the list is easy to scan and each gallery is distinct.

**Why this priority**: This list prompted the feature; it is where the monogram-only
presentation hurts most, and it drives notification follow-ups.

**Independent Test**: Follow two galleries, one with a curated image and one without,
open My Gallr, and confirm the first shows its image and the second shows its monogram.

**Acceptance Scenarios**:

1. **Given** a followed gallery with a curated image, **When** My Gallr loads, **Then** the
   square slot shows that image at the same size and position as today's monogram.
2. **Given** a followed gallery without a curated image, **When** My Gallr loads, **Then**
   the existing monogram is shown unchanged.
3. **Given** a curated image that fails to load (offline, removed), **When** the row
   renders, **Then** the monogram is shown and the row remains usable.

---

### User Story 2 - Choose galleries to follow by their identity (Priority: P2)

While adding galleries to follow, the visitor sees the same images in the candidate
list, so recognisable institutions are easy to pick out.

**Why this priority**: Same component and data as P1; extends value to the follow flow.

**Independent Test**: Open Add Galleries and confirm candidates with curated images show
them and the rest show monograms.

**Acceptance Scenarios**:

1. **Given** a candidate gallery that matches a curated image only by its Korean or
   English name (no gallery identifier on its exhibitions), **When** the list renders,
   **Then** the curated image is shown.
2. **Given** two different galleries with similar but not identical names, **When** the
   list renders, **Then** neither receives the other's image.

---

### User Story 3 - Credit freely licensed photos (Priority: P3)

When a gallery's image is a freely licensed photo that requires attribution, the gallery
detail screen shows a short credit line (author and license).

**Why this priority**: Required for licence compliance of 15 of the curated photos;
does not affect the list experience.

**Independent Test**: Open the detail screen of a gallery whose image needs attribution
and confirm the credit appears; open one with an official logo and confirm none appears.

**Acceptance Scenarios**:

1. **Given** a gallery whose image requires attribution, **When** its detail screen
   opens, **Then** a credit line naming the author and license is visible.
2. **Given** a gallery whose image is its own official logo or official-site photo,
   **When** its detail screen opens, **Then** no credit line is shown.

---

### User Story 4 - Operators publish the curated set (Priority: P1)

An operator publishes the reviewed set of gallery images (76 galleries: 46 logos, 30 space photos) to staging, verifies them, and then to production only after explicit approval.

**Why this priority**: Without published images, stories 1–3 show only monograms.

**Independent Test**: Run the publishing step against staging and confirm each approved
gallery resolves to a stored square image whose source and license are recorded.

**Acceptance Scenarios**:

1. **Given** the approved manifest, **When** the operator publishes it, **Then** every
   entry is stored as a square image in gallr-owned storage with its kind, source page,
   license, and credit recorded.
2. **Given** a manifest entry whose source can no longer be fetched or is not an image,
   **When** publishing runs, **Then** that entry is reported and skipped without affecting
   the others.
3. **Given** the set was already published, **When** publishing runs again, **Then** no
   duplicates are created and unchanged entries stay the same.

### Edge Cases

- Gallery records that are duplicates or branches (e.g., two records for the same venue)
  may share one image; each record is matched independently.
- Galleries merged into another gallery or no longer active do not expose images.
- Logo artwork with transparency is placed on a white square so it reads in both themes.
- Very wide wordmarks or tiny icons were excluded during review; they keep the monogram.
- Source sites that only serve insecure connections or rotate URLs never reach the app,
  because the app only loads gallr-hosted copies.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST show a gallery's curated image in the existing square gallery
  slot (56dp, square corners) on My Gallr followed-gallery rows and Add Galleries candidates.
- **FR-002**: The app MUST fall back to the existing text monogram when a gallery has no
  curated image or its image cannot be loaded.
- **FR-003**: The app MUST match a gallery to its image by gallery identifier when known,
  otherwise by the same normalized Korean/English name key used to identify followed
  galleries; it MUST NOT match on partial or fuzzy names.
- **FR-004**: Curated images MUST be gallery logos or photos of the gallery's space taken
  from the gallery's official channels or freely licensed sources; exhibition artwork and
  exhibition cover images MUST NOT be used.
- **FR-005**: Each curated image MUST record its kind (logo or photo), original source
  page, license, and attribution credit when the license requires one.
- **FR-006**: The gallery detail screen MUST show the attribution credit for images whose
  license requires attribution, and MUST NOT show one otherwise.
- **FR-007**: The app MUST load images only from gallr-owned storage, never from
  third-party gallery websites.
- **FR-008**: Stored images MUST be square: logos centred on a white background with
  margin, photos centre-cropped.
- **FR-009**: Curated image data MUST be publicly readable but writable only by staff or
  operator tooling.
- **FR-010**: Publishing the curated set MUST be repeatable without creating duplicates
  and MUST report entries it skipped.
- **FR-011**: Images for galleries that are merged or inactive MUST NOT be exposed.
- **FR-012**: Loading curated image data MUST NOT block or delay showing gallery lists;
  rows render with monograms first if image data is not yet available.

### Key Entities

- **Gallery profile image**: The curated representative image of one gallery. Belongs to
  exactly one gallery; records kind, stored square image, source page, license, credit,
  and when and by whom it was curated.
- **Gallery**: Existing gallery identity (identifier, Korean and English names, status).
  A gallery has zero or one profile image.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: At least 76 active galleries show a recognisable image instead of a
  monogram after publication.
- **SC-002**: No gallery shows another gallery's image (0 mismatches in a review of all
  published entries).
- **SC-003**: Gallery lists appear as quickly as before the change; images never prevent
  a row from rendering.
- **SC-004**: 100% of published images that require attribution display a credit on the
  gallery detail screen.
- **SC-005**: 0 images in the app are loaded from third-party gallery websites.

## Assumptions

- The image set was researched and reviewed on 2026-10-09; galleries with only
  low-confidence matches, unusable icons, or Instagram-only presence keep the monogram.
- Using a gallery's own logo or official-site photo to identify it in a directory is an
  accepted practice; gallery owners can request removal through existing support contact.
- Owner-uploaded images in the Gallery workspace, exhibition-card venue rows, map pins,
  search results, and the public website are out of scope for this feature.
- Five test gallery records found in production are not given images; cleaning them up is
  separate work.
