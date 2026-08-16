# Feature Specification: My Gallr gallery following

**Feature branch**: `shin/060-my-gallr-guest-archive`
**Status**: Implemented
**Depends on**: `060-my-gallr-guest-archive`

## Goal

Let anonymous and authenticated visitors keep a local list of galleries and notice exhibitions that
appear in the catalogue after they followed a gallery. This creates a recurring reason to reopen My
Gallr without interrupting the value moment with account creation.

## User stories

### US1 — Follow galleries without an account (P1)

As a visitor, I can search the galleries represented in the current exhibition catalogue and add
several to My Gallr without signing in.

**Acceptance criteria**

1. Following is available in the fourth tab for every authentication state.
2. Gallery search matches Korean and English venue names case-insensitively.
3. Following a gallery stores a bilingual venue snapshot and the IDs of exhibitions currently
   known for that gallery.
4. A gallery can only be followed once and the list survives app restarts.
5. Unfollowing requires confirmation and does not alter visit records.

### US2 — See trustworthy new-exhibition signals (P1)

As a returning visitor, I can see when a followed gallery has exhibitions that were not in the
catalogue when I followed or last acknowledged that gallery.

**Acceptance criteria**

1. Existing exhibitions are the baseline when a gallery is followed and are not labelled new.
2. Catalogue exhibition IDs added later for the same gallery produce an in-app `NEW` marker and
   count.
3. Opening a followed gallery's newest unseen exhibition acknowledges all currently known
   exhibitions for that gallery and clears its marker.
4. If there is no unseen exhibition, opening the row shows the gallery's latest current exhibition.
5. The product does not claim push delivery, email delivery, or account backup in this slice.

### US3 — Understand notification availability (P2)

As a visitor, I understand that this version checks for new exhibitions inside Gallr and that device
notifications will require a later opt-in capability.

**Acceptance criteria**

1. Following empty and populated states use “CHECK IN MY GALLR” language.
2. No notification permission or sign-in prompt appears while following.

## Functional requirements

- **FR-001**: Gallery identity MUST be derived consistently from normalized Korean and English venue
  names because the public mobile catalogue does not currently expose `gallery_id`.
- **FR-002**: The derivation limitation MUST be documented; a later stable-ID migration must not be
  implied to exist.
- **FR-003**: Persistence MUST be versioned, atomic, local, and shared by Android and iOS.
- **FR-004**: Followed records MUST contain immutable venue display fields plus known exhibition IDs.
- **FR-005**: Current gallery cards MUST be derived from the live catalogue while retaining the
  stored snapshot if a venue is temporarily absent.
- **FR-006**: Newness MUST use exhibition-ID set difference, not opening date or guessed publication
  time.
- **FR-007**: Failures MUST be explicit and MUST NOT silently discard following data.

## Out of scope

- Push notifications, notification permission, background refresh, email alerts.
- Supabase account sync or cross-device restore.
- A new public gallery catalogue/API or database migration.
- Gallery detail pages, public profiles, social activity, recommendations.

## Success measures

- Follow completion rate from the Following empty state.
- Percentage of followed-gallery users returning to My Gallr within 30 days.
- Open rate on in-app `NEW` gallery signals.
- No increase in sign-in abandonment during follow creation.
