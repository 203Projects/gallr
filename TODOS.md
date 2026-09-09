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

## Deferred Product Inputs — Not Work-Ready

- Reconsider the square `G` mark only in a deliberate product-wide brand
  project, not as an isolated flow tweak (`design-qa.md`).
- Add gallery logos only after the canonical catalogue owns verified logo
  assets; do not synthesize monograms (`design-qa.md`).
- Routine dependency updates remain owned by Dependabot PRs and are not product
  roadmap items.
