# Feature Specification: My Gallr activation entry points

**Feature branch**: `shin/060-my-gallr-guest-archive`
**Status**: Implemented
**Depends on**: `060-my-gallr-guest-archive`, `061-my-gallr-gallery-following`,
`063-followed-gallery-publication-alerts`

## Goal

Make My Gallr discoverable at relevant catalogue moments without interrupting Featured discovery or
requiring authentication.

## User stories

### US1 — Record a visit at the exhibition (P1)

As a visitor viewing an ended exhibition, I can record that visit in place. The prompt disappears
after saving and never duplicates a visit.

### US2 — Find and follow galleries in search (P1)

As a visitor searching the catalogue, I see matching galleries before matching exhibitions, along
with visit counts and an immediate follow action. Following never opens authentication.

### US3 — Understand my relationship with a gallery (P1)

As a visitor opening a gallery, I see exhibitions I recorded there, the current exhibition, and an
expandable full programme alongside follow and device-alert controls.

## Functional requirements

- **FR-001**: Featured contains no archive activation gate or promotional insertion.
- **FR-002**: The ended-exhibition prompt uses the active guest/account-aware visit repository and
  stores the same immutable snapshot as the bulk archive flow.
- **FR-003**: The prompt appears only for ended, unrecorded exhibitions and uses a compact
  divider-bound metadata row rather than a card or full-width CTA.
- **FR-004**: Search groups gallery matches ahead of exhibition matches using stable `gallery_id`
  when available and the existing normalized gallery key as fallback.
- **FR-005**: The canonical mobile catalogue request includes `gallery_id`; the legacy request does
  not request a column that does not exist.
- **FR-006**: Gallery visit history comes from the active visit archive and remains useful when an
  exhibition is no longer in the live catalogue.
- **FR-007**: All entry points remain available without authentication and use the existing square,
  monochrome Gallr system with orange limited to directional or state emphasis.

## Out of scope

- Public gallery profiles, ratings, reviews, social feeds, or web gallery pages.
- A new global navigation tab or a second authentication gate.
- A Featured-page archive promotion or session-level dismissal state.

## Success measures

- Ended-exhibition prompt completion rate.
- Gallery follow completion rate from catalogue search.
- Percentage of gallery-detail opens containing prior visit context.
