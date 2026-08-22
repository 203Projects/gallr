# Feature Specification: Bilingual Admin and Gallery portals

**Feature branch**: `shin/066-admin-gallery-i18n`
**Created**: 2026-08-22
**Status**: Implemented
**Input**: User description: "currently admin and gallery portal only supports english. support both korean and english"

## User Scenarios & Testing

### User Story 1 — Work in either language (Priority: P1)

As a staff member or gallery owner, I can switch the portal interface between Korean and English so
I can complete the same workflow in the language I understand best.

**Why this priority**: The portals are operational tools for Seoul galleries and staff; untranslated
controls block their primary users from working confidently.

**Independent Test**: Open either portal, switch to Korean, and confirm that the current screen,
navigation, controls, help text, validation, feedback, and dialogs use Korean without losing state;
switch back to English and confirm the same workflow remains available.

**Acceptance Scenarios**:

1. **Given** any portal entry state, **When** the user selects Korean, **Then** all interface-owned
   copy on that state is shown in Korean and the current workflow state is preserved.
2. **Given** a Korean portal session, **When** the user selects English, **Then** all interface-owned
   copy is shown in English without reloading or signing the user out.
3. **Given** a dialog, validation error, loading state, empty state, or success notice, **When** it is
   displayed, **Then** the copy follows the currently selected language.

---

### User Story 2 — Keep the chosen language (Priority: P1)

As a returning staff member or gallery owner, I keep my language choice on that portal so I do not
need to select it on every visit.

**Why this priority**: A language switch is only practical for a daily workspace if it is stable
across reloads and sign-in transitions.

**Independent Test**: Select Korean, reload the portal, and confirm Korean remains active; clear the
saved preference and confirm a Korean browser starts in Korean while other browser languages retain
the existing English default.

**Acceptance Scenarios**:

1. **Given** a saved language preference, **When** the portal is reopened, **Then** that preference
   takes priority over the browser language.
2. **Given** no saved preference and a Korean browser language, **When** the portal opens, **Then**
   Korean is selected.
3. **Given** no saved preference and a non-Korean browser language, **When** the portal opens, **Then**
   English is selected so the existing experience is preserved.

---

### User Story 3 — Read localized content and formatting (Priority: P2)

As a staff member or gallery owner, I see bilingual exhibition and gallery content, statuses, dates,
and counts presented for my selected language so records are easy to scan and interpret.

**Why this priority**: Translating controls without localizing the records and formatting would leave
high-frequency catalogue and publishing work only partially usable.

**Independent Test**: Open representative list, detail, setup, and review states in both languages;
confirm bilingual names prefer the selected language with a safe fallback and locale-sensitive
display values change while stored form values do not.

**Acceptance Scenarios**:

1. **Given** a record with Korean and English names, **When** the locale changes, **Then** the preferred
   name changes and the other language remains available where the workflow intentionally edits both.
2. **Given** a record missing its preferred-language name, **When** it is displayed, **Then** the other
   available name is used rather than a blank label.
3. **Given** display-only dates, times, or counts, **When** the locale changes, **Then** they use the
   matching Korean or English locale; canonical form and API values remain unchanged.

### Edge Cases

- Browser storage can be unavailable or contain an unsupported value; the portal must still render
  using the browser-derived fallback.
- A user can switch languages while a form is dirty, a request is loading, or a dialog is open; the
  switch must not reset form data, selection, or request identity.
- User-entered text and unrecognized server-provided error details are not machine translated.
  Interface-owned fallback and recovery copy must still follow the selected language.
- Korean or English record copy can be blank; the available counterpart must be used for read-only
  labels without changing the underlying record.
- Configuration-blocked, signed-out, onboarding, access-pending, access-denied, and signed-in states
  must all expose a language control.

## Requirements

### Functional Requirements

- **FR-001**: Admin and Gallery MUST each support Korean (`ko`) and English (`en`) interface locales.
- **FR-002**: A visible, keyboard-accessible language control MUST be available in every top-level
  portal state, including configuration and authentication states.
- **FR-003**: Changing locale MUST update interface-owned visible copy, accessible names, placeholders,
  validation, progress, empty/error states, notices, and confirmation dialogs without a page reload.
- **FR-004**: Each portal MUST persist its locale preference in origin-scoped browser storage.
- **FR-005**: Initial locale resolution MUST use saved preference first, then a Korean browser locale,
  then English as the fallback.
- **FR-006**: Each portal MUST keep the document language metadata synchronized with the active locale.
- **FR-007**: Read-only bilingual entity labels MUST prefer the active locale and fall back to the
  other nonblank value. Forms that explicitly edit both language fields MUST continue to show both.
- **FR-008**: Display-only date, time, and numeric formatting owned by the interface MUST use the active
  locale without modifying API payloads, persisted data, or native form-control values.
- **FR-009**: Locale changes MUST preserve authentication, navigation, selected record, dirty form
  values, dialogs, in-flight work, and retained mutation request IDs.
- **FR-010**: The implementation MUST add no runtime localization dependency and MUST not change
  Supabase schemas, RPCs, authentication, authorization, or deployment configuration.
- **FR-011**: Automated tests MUST cover locale resolution, persistence, document metadata, live
  switching, and representative Admin and Gallery workflows in Korean and English.
- **FR-012**: Korean copy MUST use the existing Gothic A1 fallback and preserve the square,
  monochrome, 8-point-grid visual system.

### Key Entities

- **Portal locale**: The active `ko` or `en` interface preference, resolved from saved and browser
  inputs and persisted independently on each portal origin.
- **Localized message**: Interface-owned copy with a Korean and English value, optionally containing
  named runtime values.
- **Localized display value**: A date, number, status, or bilingual entity label formatted for the
  active locale without changing its canonical stored representation.

## Out of Scope

- Machine translation of user-entered text, gallery content, or arbitrary backend error messages.
- A server-side/account-wide language setting shared across portal subdomains or devices.
- URL-based locale routing, new authentication behavior, backend changes, or deployment.
- Redesigning portal layouts beyond the compact language control and text-width accommodations.

## Success Criteria

### Measurable Outcomes

- **SC-001**: A user can complete the representative sign-in/onboarding and primary signed-in
  workflows in either Korean or English with no interface-owned English remaining in Korean mode.
- **SC-002**: Locale changes are reflected immediately and survive reload in 100% of automated
  persistence scenarios without resetting workflow state.
- **SC-003**: Both portals pass their full test, typecheck, and production-build gates.
- **SC-004**: Rendered desktop and mobile QA confirms the language control is reachable, Korean copy
  does not clip or overlap, and no relevant console errors or framework overlays appear.
