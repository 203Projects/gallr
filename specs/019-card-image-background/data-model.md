# Data Model: Exhibition Card Image Background

**Feature**: 019-card-image-background
**Date**: 2026-03-26

## Entities

### Exhibition (existing — no changes)

The `Exhibition` data class already contains the `coverImageUrl: String?` field needed for this feature. No schema changes, new entities, or data migrations are required.

**Relevant field**:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `coverImageUrl` | `String?` | Yes | URL to the exhibition's installation view image. Null when no image is available. |

**Location**: `shared/src/commonMain/kotlin/com/gallr/shared/data/model/Exhibition.kt`

### Card Display State (UI-only, no persistence)

The card's visual mode is derived at render time — not persisted or modeled as a domain entity.

| State | Condition | Visual Treatment |
|-------|-----------|-----------------|
| Image-backed | `coverImageUrl != null` AND image loaded successfully | Full-bleed image + scrim overlay + fixed text colors |
| Solid fallback | `coverImageUrl == null` OR image load failed | `surfaceVariant` background + existing animated text colors |

## State Transitions

```
Card renders
  ├── coverImageUrl is null → Solid fallback (surfaceVariant)
  └── coverImageUrl is non-null
       ├── Image loads successfully → Image-backed card
       └── Image fails to load → Solid fallback (surfaceVariant)
```

No user-initiated state transitions. The display mode is determined entirely by data availability and network conditions.

## Validation Rules

- No new validation rules. The `coverImageUrl` field is already nullable and handled at the DTO mapping layer.
- Image URL validity is implicitly validated by Coil's loading pipeline — invalid URLs result in the same fallback as null URLs.
