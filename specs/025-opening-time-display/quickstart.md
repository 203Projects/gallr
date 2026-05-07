# Quickstart: Opening Time Display

**Date**: 2026-04-07
**Feature**: 025-opening-time-display

## Prerequisites

- Supabase project access (to run migration)
- Google Sheet access (to add column)
- Android Studio or compatible IDE with KMP support
- Existing development environment for gallr (see project README)

## Setup Steps

1. **Run Supabase migration**:
   ```sql
   ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS opening_time TEXT;
   ```

2. **Add column to Google Sheet**:
   - Open the `gallr_gallery_list` sheet
   - Add `opening_time` column header next to the existing `reception_date` column
   - Enter test values (e.g., "5 PM", "6:30 PM") for a few exhibitions that have reception dates

3. **Deploy updated sync script**:
   - Open `gas/SyncExhibitions.gs`
   - The `KNOWN_COLUMNS` array change will be committed in the feature branch
   - Copy updated script to Google Apps Script editor and save

4. **Run sync**:
   - Trigger the sync function to populate `opening_time` in Supabase
   - Verify in Supabase dashboard: `SELECT id, reception_date, opening_time FROM exhibitions WHERE opening_time IS NOT NULL`

5. **Build and run app**:
   - Android: `./gradlew :composeApp:installDebug`
   - iOS: Open `iosApp/iosApp.xcodeproj` in Xcode, build and run

## Verification

- Navigate to an exhibition detail page for an exhibition with both `reception_date` and `opening_time` populated
- Confirm the label reads e.g., "Opening today, 5 PM"
- Navigate to an exhibition without `opening_time` — label should be unchanged (e.g., "Opening today")

## Running Tests

```bash
./gradlew :shared:allTests
```
