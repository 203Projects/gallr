# Data Model: Opening Time Display

**Date**: 2026-04-07
**Feature**: 025-opening-time-display

## Entity Changes

### Exhibition (modified)

**New attribute:**

| Attribute | Type | Nullable | Default | Description |
|-----------|------|----------|---------|-------------|
| opening_time | TEXT | Yes | NULL | Free-text opening reception time (e.g., "5 PM", "6:30 PM"). Displayed as-is, no parsing. |

**Relationships**: None changed. `opening_time` is a standalone attribute with no foreign key or reference.

**Validation rules**: None. The field is free-text and displayed verbatim. Empty string and NULL are treated identically (no time shown).

**State transitions**: None. The field is static data entered by content managers.

## Schema Changes

### Supabase Migration (005_add_opening_time.sql)

```sql
ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS opening_time TEXT;
```

No index needed (field is not queried or filtered, only displayed).

## Data Flow

```
Google Sheet (opening_time column)
    ↓ SyncExhibitions.gs reads as string
Supabase exhibitions.opening_time (TEXT, nullable)
    ↓ Ktor HTTP GET /rest/v1/exhibitions
ExhibitionDto.openingTime (String?, nullable)
    ↓ toDomain()
Exhibition.openingTime (String?, nullable)
    ↓ receptionDateLabel(receptionDate, closingDate, lang, openingTime)
UI label: "Opening today, 5 PM"
```

## Layer-by-Layer Field Mapping

| Layer | Field Name | Type | Notes |
|-------|-----------|------|-------|
| Google Sheet | opening_time (column header) | Cell text | Free-text, optional |
| Supabase | opening_time | TEXT (nullable) | Stored as-is |
| ExhibitionDto | openingTime | String? = null | @SerialName("opening_time") |
| Exhibition | openingTime | String? = null | Passed through from DTO |
| UI | Appended to label | Inline text | ", $openingTime" when non-null/non-blank |
