# Feature Specification: Admin Editor Management

**Feature Branch**: `058-admin-editor-management`
**Created**: 2026-08-13
**Status**: Complete
**Input**: User description: "there should be a way for admin to edit/remove or manage editors in admin portal"

## User Scenarios & Testing

### User Story 1 - Review existing editors (Priority: P1)

An administrator can see every editor, including unpublished or access-disabled editors, with enough profile, schedule, publication, and account state to manage them.

**Independent Test**: Open the admin-only Editors section and verify that existing editor records and their current states are visible.

**Acceptance Scenarios**:

1. **Given** an active administrator and existing editor records, **When** the administrator opens Editors, **Then** the portal lists every editor with profile publication and workspace-access state.
2. **Given** a contributor or editor account, **When** it attempts the editor-list command, **Then** the server denies access.

### User Story 2 - Edit an editor (Priority: P1)

An administrator can update an editor's bilingual profile, curation statement, publication state, and active schedule without changing the immutable editor slug or account email.

**Independent Test**: Edit one editor, save, and verify the refreshed editor card and database record contain the normalized values.

**Acceptance Scenarios**:

1. **Given** an editor record, **When** an administrator submits valid changed fields against its current revision, **Then** the update is saved, revisioned, and audited.
2. **Given** an obsolete revision, **When** an administrator saves, **Then** the server rejects the update and the portal offers a reload instead of overwriting newer work.
3. **Given** invalid required fields or dates, **When** an administrator saves, **Then** no change is persisted.

### User Story 3 - Remove and restore editor access safely (Priority: P1)

An administrator can deactivate an editor without deleting their Auth user, profile, exhibition attribution, requests, or audit history, and can later restore workspace access.

**Independent Test**: Deactivate an editor, verify their portal access and public profile are disabled while attribution remains, then restore access.

**Acceptance Scenarios**:

1. **Given** an active linked editor, **When** an administrator confirms deactivation, **Then** workspace access and public profile publication are disabled atomically and the action is audited.
2. **Given** a deactivated linked editor, **When** an administrator restores access, **Then** workspace access returns but the public profile remains unpublished until deliberately enabled.
3. **Given** an editor without a linked portal account, **When** an administrator views the record, **Then** the portal does not offer an access-removal action.

### Edge Cases

- Existing house or legacy editors may have no editor membership and remain profile-editable only.
- Deactivation preserves pending requests and historical exhibition attribution.
- Concurrent edits or access changes fail on revision mismatch.
- Empty required Korean fields, overlong copy, and inverted date ranges fail before mutation.
- Malformed RPC responses fail closed in the browser repository.

## Requirements

### Functional Requirements

- **FR-001**: The admin portal MUST list all editor profiles through an admin-scoped RPC.
- **FR-002**: Only an active administrator MUST be able to list or mutate editors.
- **FR-003**: Administrators MUST be able to update bilingual names, titles, biography, curation statement, schedule, and public-profile status.
- **FR-004**: Editor slugs and account emails MUST remain immutable in this workflow.
- **FR-005**: Editor mutations MUST require the expected revision and reject stale writes.
- **FR-006**: Removing an editor MUST be a reversible deactivation, not a hard delete.
- **FR-007**: Deactivation MUST disable editor membership access and public-profile publication atomically while preserving related records.
- **FR-008**: Restoring access MUST NOT implicitly republish the public profile.
- **FR-009**: Successful editor changes MUST record actor-attributed audit events without logging profile copy or email.
- **FR-010**: Browser code MUST validate unknown RPC responses before exposing domain records.

### Key Entities

- **Editor**: Public bilingual profile and curation identity with an immutable slug, publication schedule, active flag, and mutation revision.
- **Editor membership**: Optional link from one Auth user to an editor; its active state controls scoped portal access.
- **Audit event**: Actor-attributed record of profile updates and access-state changes.

## Success Criteria

### Measurable Outcomes

- **SC-001**: An administrator can locate and begin editing any editor from one Editors page.
- **SC-002**: Valid edits and access changes complete without direct browser table access.
- **SC-003**: Unauthorized and stale mutations persist zero editor changes.
- **SC-004**: Deactivation leaves editor, Auth, attribution, request, and audit records intact.
