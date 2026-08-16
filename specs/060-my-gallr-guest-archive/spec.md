# Feature Specification: My Gallr guest archive

**Feature Branch**: `060-my-gallr-guest-archive`
**Created**: 2026-08-13
**Status**: In progress
**Input**: Replace the logged-in-only Profile tab with a useful personal archive that works before account creation.

## User Scenarios & Testing

### User Story 1 - Keep a visit without an account (Priority: P1)

As a guest, I can open My Gallr and record an exhibition I visited without creating an account, so Gallr provides personal value before asking me to register.

**Why this priority**: Removing the sign-in wall is the smallest change that makes the fourth tab useful to every visitor.

**Independent Test**: Launch Gallr while signed out, add one past exhibition from My Gallr, recreate the repository, and verify the visit remains visible without any authentication step.

**Acceptance Scenarios**:

1. **Given** an anonymous user with no visits, **When** they open the fourth tab, **Then** they see the My Gallr empty state and an Add past visits action instead of Sign In.
2. **Given** an anonymous user, **When** they add a past exhibition, **Then** the visit is stored locally and appears in the Visits archive.
3. **Given** a locally stored visit, **When** the app restarts, **Then** the same visit and its historical display details remain available.
4. **Given** an anonymous user adding visits, **When** they complete the flow, **Then** Gallr does not interrupt them with an account prompt.
5. **Given** a visit whose exhibition remains in the catalogue, **When** the user selects its archive card, **Then** the exhibition detail screen opens.

---

### User Story 2 - Build an archive quickly (Priority: P2)

As a visitor with several past exhibitions, I can search and select multiple exhibitions before saving, so creating a useful archive does not require repeating the same flow.

**Why this priority**: A populated archive communicates more value than a single saved record and reduces cold-start effort.

**Independent Test**: Search the catalogue, select three different exhibitions, save once, and verify all three appear exactly once in the archive.

**Acceptance Scenarios**:

1. **Given** the Add past visits screen, **When** the user searches by exhibition or venue name, **Then** the matching catalogue entries remain selectable.
2. **Given** multiple selected exhibitions, **When** the user saves, **Then** all selections are persisted atomically and the Visits archive opens.
3. **Given** an exhibition already in Visits, **When** the add screen opens, **Then** that exhibition cannot create a duplicate archive entry.
4. **Given** no selection, **When** the user views the save action, **Then** it remains disabled.

---

### User Story 3 - Retain voluntary account access (Priority: P3)

As a guest or member, I can deliberately open account/profile tools from My Gallr without those tools replacing the archive.

**Why this priority**: Existing authentication, profile editing, thoughts, and administrative access must remain reachable while the main tab changes purpose.

**Independent Test**: From My Gallr, open Account while signed out and while authenticated; verify the existing sign-in or profile experience opens and can return to the archive.

**Acceptance Scenarios**:

1. **Given** a guest on My Gallr, **When** they choose Account, **Then** the existing sign-in experience opens.
2. **Given** an authenticated user on My Gallr, **When** they choose Account, **Then** the existing profile and thought tools open.
3. **Given** either account experience, **When** the user returns, **Then** My Gallr remains the fourth-tab destination.

### Edge Cases

- A persisted payload that cannot be decoded must surface an explicit archive load failure rather than silently replacing the archive with an empty list.
- Removing one visit must not affect bookmarks or any other visit.
- Saving the same exhibition twice must remain idempotent.
- Search must be case-insensitive and use the current app language with bilingual fallback.
- A catalogue refresh or removal must not change the snapshot already stored in a visit.
- A persistence failure must preserve the user's current selections and expose Retry.

## Requirements

### Functional Requirements

- **FR-001**: The fourth tab MUST render My Gallr for anonymous and authenticated users.
- **FR-002**: My Gallr MUST show Visits and a Following placeholder; gallery following itself is out of scope.
- **FR-003**: Users MUST be able to select and save multiple catalogue exhibitions as visits in one operation.
- **FR-004**: Visit persistence MUST be local, offline-capable, versioned, and independent of authentication.
- **FR-005**: A visit MUST preserve a minimal immutable exhibition snapshot sufficient to render title, venue, exhibition dates, and cover image without the live catalogue row.
- **FR-006**: The repository MUST enforce at most one visit per exhibition for this MVP.
- **FR-007**: The add flow MUST exclude or disable exhibitions already archived.
- **FR-008**: The UI MUST not request account creation during visit creation.
- **FR-009**: Existing sign-in and authenticated profile tools MUST remain voluntarily reachable from My Gallr.
- **FR-010**: Visit mutations and failures MUST use structured, redacted application logging.
- **FR-011**: This slice MUST NOT add Supabase tables, remote synchronization, gallery following, push delivery, sharing, ratings, notes, public profiles, or streaks.
- **FR-012**: The UI MUST follow `DESIGN.md`, including square shapes, 8pt spacing, monochrome surfaces, and orange only for the primary archive CTA and active indicators.
- **FR-013**: Visit archive cards MUST open the matching catalogue exhibition detail when selected, while their Remove action remains independently operable.

### Key Entities

- **ExhibitionVisit**: One locally retained record that identifies a catalogue exhibition, records when the archive entry was created, and contains its immutable display snapshot.
- **ExhibitionVisitSnapshot**: The bilingual exhibition and venue names, exhibition date range, and cover image URL captured when the visit is added.
- **MyGallrUiState**: The archive, catalogue search, current selection, language, navigation mode, loading state, and recoverable error state rendered by the fourth tab.

## Success Criteria

### Measurable Outcomes

- **SC-001**: A signed-out user can add at least three past exhibitions in one save without encountering authentication.
- **SC-002**: Every successfully saved visit remains identical after repository reconstruction using the same persisted store.
- **SC-003**: Repeatedly saving one exhibition produces exactly one archive entry.
- **SC-004**: Existing sign-in or profile tools remain reachable from My Gallr in no more than one explicit Account action and can return without changing tabs.
- **SC-005**: Shared model, persistence, and ViewModel behavior pass automated common tests on both supported KMP targets.
