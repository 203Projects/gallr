# TODOS

Last updated: 2026-09-03. Revalidate external service and release status before
acting on older operational entries.

This file is the authoritative open-work list. Unchecked boxes in completed or
superseded specifications are historical execution records unless an item below
links back to them explicitly.

## Rollout Queue — Integrated, Not Yet Activated

### Local discovery, aggregate analytics, and explainable recommendations

Four completed specifications are now integrated into `develop`:

- `071-local-discovery-intelligence`: deterministic, private on-device
  recommendations and neighborhood route planning.
- `072-mobile-product-analytics`: aggregate-only mobile analytics with a bounded
  offline queue, Supabase ingestion, disclosure, and user/release gates.
- `073-local-discovery-experience`: the mobile For You and neighborhood-route
  presentation.
- `074-explainable-art-recommendations`: reviewed artist/art metadata and
  bilingual evidence for personalized recommendations.

PRs #246–#253 and #259 landed bottom-up on 2026-09-03 with green automated
checks. Repository integration does not authorize applying the metadata or
analytics migrations, deploying `mobile-analytics`, enabling analytics
collection, changing hosted configuration, or releasing new mobile builds.

Roll out through staging in contract order: apply the migrations; deploy the
disabled function with an environment-specific component secret; verify Admin,
Gallery, canonical-v2, legacy fallback, mobile analytics-disabled behavior, and
recommendation evidence; then make a separate production enablement decision.
Keep both mobile analytics release flags and `MOBILE_ANALYTICS_ENABLED` false
until disclosure, user preference, and staged aggregate evidence are approved.

## P1 — Post-Launch

### Push Notifications
Weekly "N new exhibitions near you" push via FCM (Android) + APNs (iOS). Primary retention mechanism. Needs a reviewed server-side scheduler and delivery worker; do not revive the retired Apps Script pipeline. Depends on basic analytics being in place.
- Effort: M (human) → S (CC: ~1 day)
- Context: Design doc identifies retention as key initiative. Without a trigger, users forget to open the app.
- Gate: Stage the aggregate analytics rollout above before designing the
  notification scheduler so delivery can be measured without introducing a
  second identity or event pipeline.

### Close My Gallr physical-device validation

Automated, simulator, disposable Auth/Data API, and hosted-branch isolation
evidence is complete. The remaining release evidence is a signed-in physical-
device account-isolation pass plus hands-on VoiceOver gesture and spoken-pacing
validation.

- Source: `specs/060-my-gallr-guest-archive/tasks.md` T022 and
  `specs/064-my-gallr-account-sync/tasks.md` T008.
- Do not mark these complete from simulator or accessibility-tree inspection
  alone; the remaining checks explicitly require a physical device and human
  listening/interaction.

## P3 — Technical Debt

### Full Analytics Dashboard
Turn the aggregate counters from `072-mobile-product-analytics` into a useful
operator dashboard for discovery, recommendation, route, and intent rates.

- Effort: M (CC: ~1 day after the analytics stack is integrated and staged).
- Start with the planned Supabase SQL views/queries. Evaluate an external
  dashboard only after the first-party aggregates and privacy boundaries are
  proven insufficient.
- Do not report unique users, sessions, cross-visit funnels, or retention: the
  aggregate-only event model intentionally has no stable person/device identity.

### Route-shaped link preview image for shared personal routes

**What:** Render a route-shaped Open Graph PNG (numbered stops joined by a line, plus the route name) for each shared route page, cached by route id and revision.

**Why:** Personal routes v1 previews use the first still-published stop's cover, so a shared route reads like a single exhibition in KakaoTalk and iMessage.

**Context:** Deferred by the personal routes engineering review (D20, 2026-10-08; design doc `docs/designs/2026-10-07-personal-routes-design.md`, Open Question 2). Link scrapers do not render SVG. Start from the route page's inline SVG in its Vercel Function. Build only if the recipient-open rate (shared routes opened within 7 days) is low after launch.

**Effort:** M (CC: ~1 hour)
**Priority:** P3
**Depends on:** Personal routes v1 shipped and its recipient-open numbers.

### Abuse limits on personal route writes and page counts

**What:** Add a per-account cap on saved personal routes (for example 100) and a per-route daily ceiling on route page-count increments.

**Why:** v1 has no limit on routes per account, and the page-count database function is callable by anyone holding the publishable key, so spam routes or inflated opens could skew the recipient-open metric.

**Context:** Deferred by the personal routes engineering review (D21, 2026-10-08; design doc `docs/designs/2026-10-07-personal-routes-design.md`, decisions D5 and D14). Page counts are labelled unauthenticated traffic. Start in `save_personal_route` and `record_route_page_event`, with pgTAP cases for each limit.

**Effort:** S (CC: ~20 min)
**Priority:** P3
**Depends on:** v1 traffic showing spam routes or skewed page counts.

### Guard public route ranking against throwaway accounts

**What:** Count or flag saves from very new accounts in the 090 public route ranking.

**Why:** Saves rank the public list and any signed-in account counts once, so one person with throwaway accounts could promote a route.

**Context:** Raised by the public routes engineering review (Section 1 #4, D21, 2026-10-08; design doc `docs/designs/2026-10-08-public-routes-design.md` §Ranking). Pre-approval and launch volume make it unlikely early. Start in `list_public_routes_impl` and `route_saves`, with pgTAP cases.

**Effort:** S (CC: ~45 min)
**Priority:** P3
**Depends on:** 090 launched and saves concentrated on a few new accounts.

### Full public route list with keyset paging

**What:** Add a '모두 보기' screen listing every eligible public route with stable keyset paging over the ranking.

**Why:** 090 shows only the top 10 inline, so routes ranked 11 and lower are unreachable in the app.

**Context:** Deferred by the public routes engineering review (D1, D22, 2026-10-08). Start from `list_public_routes(p_limit)`. Offset paging skips or repeats rows as saves change the order (office-hours reviewer R2-8), so page by (save count, approval time, id).

**Effort:** M (CC: ~1-2 hours)
**Priority:** P3
**Depends on:** more than about 30 eligible listed routes.

### Weekday-hours check for listed routes

**What:** Require at least one shared date on which every stop's parsed opening hours are open, treating unknown hours as open.

**Why:** The 090 shared date window (D15) can still list a route whose only common days are a gallery's closing days.

**Context:** Deferred by the public routes engineering review (D23, 2026-10-08). The 089 opening-hours parser and its JS port (`web/api/_lib/opening-hours.js`) are the starting point; hours text is often partial.

**Effort:** M (CC: ~2 hours)
**Priority:** P4
**Depends on:** listed routes found unwalkable because of weekday closures.

### Cap route screen width on tablets

**What:** One maximum content width (for example 600dp, centred) for the Map route sheet, the 089 composer and the 090 public route preview.

**Why:** No route screen has a maximum width today, so on tablets stop rows and bottom-bar buttons run edge to edge and text lines get very long.

**Pros:** One DESIGN.md token fixes all three screens at once.

**Cons:** Needs a DESIGN.md decision on the value and a tablet visual pass on Android and iPad.

**Context:** Found by the public routes design review (D28, D30, 2026-10-08). 089 and 090 were designed phone-first and DESIGN.md has no tablet layout rules; 090 keeps the existing full-width behaviour.

**Effort:** S (CC: ~30 minutes plus a device pass)
**Priority:** P4
**Depends on:** best after 090 lands so the preview is included.

## Deferred Product Inputs — Not Work-Ready

- Reconsider the square `G` mark only in a deliberate product-wide brand
  project, not as an isolated flow tweak (`design-qa.md`).
- Add gallery logos only after the canonical catalogue owns verified logo
  assets; do not synthesize monograms (`design-qa.md`).
- Routine dependency updates remain owned by Dependabot PRs and are not product
  roadmap items.
