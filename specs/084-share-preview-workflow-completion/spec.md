# Feature Specification: Share preview and workflow emails

**Branch**: `084-share-preview-workflow-completion` · **Created**: 2026-09-21 · **Status**: Ready

Input: the two P1 requests dated 2026-09-14, exhibition-share-card-preview and gallery-admin-email-notifications.

## User Scenarios & Testing

### US1 — Preview an exhibition share (P1)

As a visitor, I inspect the exact exported card before selecting a recipient.
Independent test: open preview from detail, inspect, share, dismiss, and return.

1. Share icon opens a full-screen preview immediately, without a system sheet.
2. Rendering shows a 9:16 placeholder and progress with Share disabled. Failure shows localized Retry.
3. Ready displays the exact PNG that is shared. Double taps cannot open overlapping sheets.
4. Back cancels rendering and returns to detail. Sheet dismissal leaves the preview open.
5. Card follows Light/Dark/resolved System theme and DESIGN.md; iOS/Android sheets show thumbnail and localized title; iPad has a popover anchor.

### US2 — Gallery decision emails (P1)

As an operator, I receive a decision email once my claim or exhibition decision is saved.
Independent test: approve/reject both claim types and exhibitions; inspect recipient/content.

1. Existing/new gallery decisions reach the authenticated email captured with the claim.
2. Exhibition decisions reach the entered submission contact email.
3. Rejections include saved review notes; all decisions identify the entity and link to the gallery workspace.

### US3 — Admin intake emails (P1)

As staff, I receive an alert when a new claim or gallery exhibition submission is saved.
Independent test: submit both claim types and an exhibition and inspect the alert.

1. Production alerts reach hello@gallrmap.com and contain entity name, submitter email, submission time, and correct admin review-area link.
2. Claims distinguish existing/new gallery and include the claim note when present.

### Edge Cases

Missing/slow/invalid covers use a placeholder after 3 seconds. Long KO/EN titles retain the card layout. Back during rendering cannot later present a sheet. Invalid payloads/recipients/configuration and provider failure are never acknowledged as delivered. Staging never sends to production recipients. Retries retain delivery identity.

## Requirements

- FR-001: Preserve card layout, DESIGN.md tokens, square corners, no shadows, safe areas and localized controls.
- FR-002: Deliver only after committed workflow events, using the durable outbox, stable deduplication and independent retries.
- FR-003: Log attempts/final failure without full addresses, contents, notes or credentials.
- FR-004: Use recognizable gallr transactional branding, concise bilingual fallback copy and escaped untrusted text.
- FR-005: No approval/rejection action links, evidence/storage links, auth links, internal IDs or staff metadata in gallery-facing emails.
- FR-006: Missing/invalid settings fail closed; verified gallrmap.com sender and separate 1Password-backed environment settings are deployment prerequisites.
- FR-007: Staging delivery goes only to an explicitly configured test recipient, never real operators or production hello@gallrmap.com.

### Key Entities

- Story card: PNG bytes, localized descriptor, palette; owned by preview.
- Preview: rendering/ready/failed with sharing status.
- Workflow email: committed event identity, audience, entity, saved notes, timestamp, recipient and delivery status.

## Success Criteria

- Every tested preview/export uses identical bytes and no sheet opens before explicit Share.
- Light/dark/system × KO/EN behavior verified on Android/iPhone/iPad.
- Four notification moments, both claim types and both decision outcomes reach their specified audience.
- Retries do not intentionally duplicate emails; outages preserve workflow results.
- Automated coverage includes preview cancellation/identity, theme/palette, email validation/escaping/isolation and retry behavior.

## Assumptions and Scope

Use #121212 dark background; no preview theme toggle; keep preview open after sharing. No editing, direct Instagram posting, new Save action, list/map sharing, or shareApp changes. No marketing/push/SMS/notification center. Implementation/local tests authorized; deployment and real-recipient rehearsal remain separate.
