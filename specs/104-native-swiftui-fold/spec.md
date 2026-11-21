# Native SwiftUI iOS and adaptive browsing

iOS presents gallr in SwiftUI using the existing Kotlin application and data contracts. Android continues to use its Compose presentation.

## User stories

1. A visitor can browse Home, Search, Map, and My gallr, open exhibitions, galleries, events, and curators, and return to the same place in the browse flow. Existing filters, bookmarks, localization, loading, failure, sharing, and deep links work through the same Kotlin APIs.
2. A visitor using a wider display can browse lists and inspect a selection side by side. A width change preserves the selected tab, navigation destination, list position and filters. Controls stay reachable around safe areas, on-screen keyboards, and any platform-provided display obstruction.
3. A signed-in visitor can use existing profiles, thoughts, diary, followed-gallery alerts and routes. Startup, foreground refresh, device reminders, account sync, and permission flows preserve the existing Kotlin data contracts.

## Acceptance

- The iOS application starts on a SwiftUI root and reachable product routes render native views.
- Compact and wide Simulator configurations exercise navigation, selection and return paths. At least one compact to wide transition preserves the selected destination.
- The framework and iOS host build successfully. Android Compose remains buildable against the shared contracts.
- Use the existing gallr design system and native accessibility semantics, including Dynamic Type and VoiceOver labels.
