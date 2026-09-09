# Specification: Local discovery experience

## Product boundary

Gallr exposes its existing local recommendation and neighborhood-route engines
as mobile experiences. Catalogue preparation, taste matching, route selection,
and distance/time estimation run in shared Kotlin on the device. The feature
must not call a hosted model or directions service, require an AI credential,
incur per-request cost, or allow paid promotion into organic ranking.

## User Story 1 — For You recommendations (P1)

As a visitor, I can open a short, explainable list of exhibitions selected from
the organic catalogue using my saves, visits, and followed galleries.

### Acceptance criteria

1. Featured exposes a bilingual `FOR YOU / 내 취향 추천` entry labelled as
   computed only on this device.
2. The screen presents at most six current/upcoming exhibitions, excludes saved
   and visited items, and shows at most two localized rule-based reasons.
3. A visitor without history receives honest editorial/time-based cold-start
   choices and is never told that Gallr inferred a taste match.
4. Bookmarking re-ranks through the same immutable prepared catalogue index on
   a background dispatcher. No profile vector is persisted or transmitted.
5. Recommendations never consume `PromotedExhibition` or paid-placement state.
6. Cards preserve standard detail and bookmark behavior, accessibility, and
   back navigation to the recommendation list.

## User Story 2 — Neighborhood route (P1)

As a visitor, I can use the current map center to create a two-to-five-stop
route curated for proximity, my interests, closing dates, or saved exhibitions.

### Acceptance criteria

1. Map exposes a bilingual 44dp route action using its current camera center;
   location permission is not required and the origin never leaves the device.
2. Modes are Neighborhood, For You, Closing Soon, and Saved. Stop count is 2–5;
   MVP radius is 5 km and visit allowance is 45 minutes per stop.
3. A successful route shows ordered stops, per-leg and total estimated distance,
   estimated travel time, total time including visits, and visible approximation
   and unverified-hours warnings.
4. Straight-line-derived estimates are never presented as turn-by-turn or road
   distance, and no approximate solid route geometry is drawn.
5. `START ROUTE` and per-stop map actions hand off one stop at a time through the
   existing platform map boundary. A failed handoff is visible and retryable.
6. Returning from exhibition detail or an external map preserves the route.
7. Insufficient candidates name the shortage and let the visitor reduce the
   stop count or choose another mode.

## Analytics boundary

- Existing coarse events record only result count, rank bands, route mode,
  stop-count, distance band, and total-duration band.
- `recommendations_shown` records once per mounted recommendation-screen visit;
  recomposition does not duplicate it, while a later return is a new display.
- Never record recommendation score/reasons/profile inputs, bookmarks, visits,
  follows, route origin, coordinates, geometry, stop IDs/order, or route ID.
- Analytics remains separately default-off behind both release and user gates.
- Paid-promotion interactions remain suppressed and outside this feature.

## Success criteria

- Pure ViewModel/presentation tests cover warm and cold recommendations, index
  reuse, route modes, insufficient candidates, formatting, and retry state.
- Navigation tests cover both entry points and return destinations.
- Android and iOS runtime inspection confirms bilingual layout, radio semantics,
  numbered stops, visible warnings, and minimum touch targets.
- No hosted model, paid routing API, vector database, new permission, secret, or
  persistent taste/route store is introduced.
