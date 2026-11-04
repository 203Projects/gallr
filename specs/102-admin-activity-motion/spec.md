# Admin activity motion

## User stories and acceptance criteria
- As staff, I can distinguish a running request from an idle or empty workspace: session checks, list loading, saving, geocoding, media updates, and review/lifecycle requests show a Thinking Orb beside the existing localized status.
- As staff, I can follow content changes without distracting movement: inspector sections, loaded content, and dialogs fade in over 180ms with no artificial request delays.
- As a reduced-motion or assistive-technology user, I retain readable status text: orbs are decorative to screen readers, status announcements are polite, reduced motion renders static orbs and removes fades, and existing focus/disabled/error behavior remains intact.
- Pending human reviews and errors never animate as active work. Existing data contracts and production configuration remain unchanged.
