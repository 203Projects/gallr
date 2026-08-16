# Research: My Gallr guest archive

## Decisions

### Store a visit snapshot, not only an exhibition ID

The current `Exhibition` domain model does not expose the canonical published version ID. Storing only an ID would also make archive rendering depend on a catalogue row that can later change or disappear. Each visit therefore captures the minimal bilingual display fields used by My Gallr.

### Use the existing Preferences DataStore

The current application already supplies one cross-platform DataStore and already persists serialized exhibition catalogues. A versioned JSON payload keeps this slice small and testable without adding SQLDelight or a new database. Decoding is strict at the repository boundary so corruption becomes an explicit failure rather than an apparent empty archive.

### Keep account/profile as an opt-in subview

Anonymous users currently receive only `SignInScreen`, while members receive `ProfileScreen`. `ProfileTab` will instead open My Gallr for both states and allow an explicit Account action to reuse those existing screens. This prevents a regression in authentication, profile editing, thoughts, and administrator access without making identity the tab's purpose.

### Keep bookmarks and visits separate

Bookmarks represent planned or saved exhibitions and already drive map/list filters and local reminders. A visit represents completed attendance. Reusing the bookmark repository would corrupt both meanings and notification behavior.

## Rejected alternatives

- **Reuse `public.bookmarks` or `BookmarkRepository`**: rejected because planned and completed exhibitions are distinct user intentions.
- **Require authentication before adding visits**: rejected because pre-account value is the purpose of the slice.
- **Add Supabase synchronization now**: rejected because it would couple the first user-value experiment to authentication, RLS, migration, and failure recovery.
- **Store only live exhibition IDs**: rejected because historical archives must remain renderable when catalogue content changes.
- **Add a generic local/cloud sync framework**: rejected as speculative; the existing bookmark wrapper is a reference for a later concrete sync slice.
