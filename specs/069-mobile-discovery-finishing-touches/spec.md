# Specification: Mobile discovery finishing touches

## Scope

Finish three quality-of-life items from `TODOS.md`: hand an exhibition location to the native map
application, expose existing Featured and gallr Editors curation state on exhibition cards and the
detail screen, and verify that visit history already belongs to the Profile/My Gallr experience.

## User Story 1 — Open an exhibition in Maps (P1)

As a visitor viewing an exhibition, I can open its valid coordinates in my platform map app so I
can navigate to the venue without copying an address.

### Acceptance criteria

1. An exhibition with finite coordinates inside the legal latitude/longitude ranges shows one
   full-width, outlined `OPEN IN MAPS` / `지도에서 열기` action on the detail screen.
2. Android sends a `geo:` intent containing the coordinates and an exhibition/venue label. iOS
   opens the equivalent Apple Maps URL. Platform adapters contain no business decisions.
3. Missing, non-finite, or out-of-range coordinates show no action and never reach a platform API.
4. If no map application can handle the request or the platform call fails, Gallr remains open and
   records only a stable, privacy-safe operation name—never coordinates, addresses, or user data.
5. The action is a sharp 0dp-corner, monochrome outlined button with a minimum 44dp target.

## User Story 2 — Recognize curated exhibitions (P2)

As a visitor scanning cards or reading a detail page, I can tell when an exhibition is Featured or
is a gallr Editors pick.

### Acceptance criteria

1. `isFeatured == true` produces a localized `FEATURED` / `추천` badge.
2. `editorId == "gallr-editors"` produces a localized `EDITOR'S PICK` / `에디터 추천` badge.
   Other editor identities keep their existing editor surfaces and do not receive the house badge.
3. If both states apply, both badges render in the stable order Featured then Editor's Pick.
4. Badges appear on shared exhibition cards and on the exhibition detail screen.
5. Badges are monochrome outlined labels with square corners. They do not use the orange accent,
   obscure poster imagery, or reduce the bookmark target below 44dp.

## User Story 3 — Keep visit history in Profile/My Gallr (P2 verification)

As a visitor, I find my visit archive under Profile/My Gallr while the Map remains focused on
geographic discovery and bookmark exploration.

### Acceptance criteria

1. Profile/My Gallr exposes the visit archive and Add Past Visits flow for guests and signed-in
   accounts.
2. The Map may visualize visited state, but it is not the only place where visit history can be
   reviewed or managed.
3. Existing automated coverage for archive persistence, account isolation, and profile navigation
   remains green; remaining physical-device accessibility/account checks stay documented as manual
   release evidence rather than being misrepresented as automated.

## Out of scope

- Turn-by-turn navigation inside Gallr.
- Choosing between multiple installed map providers.
- New curation fields or schema migrations.
- Redesigning My Gallr or moving its existing visit repository.

## Success criteria

- Valid-location detail screens can hand off to the platform map application on Android and iOS.
- Featured and house-editor state is visible on every shared exhibition card and detail screen.
- `TODOS.md` no longer lists Profile visit history as unimplemented once the existing behavior and
  automated checks are reverified.
