# Implementation Plan: Transparent Local Promotion

## Technical approach

Add an isolated `content.local_promotions` aggregate and pseudonymous daily-impression table.
Owners and staff use explicit security-definer command/query RPCs. An Edge Function validates
coarse locality plus an installation key, hashes the key, and calls one service-role atomic
selection RPC. Web and KMP clients consume the Edge response through separate promotion
repositories. The existing exhibition catalogue readers and curation tables are unchanged.

## Constitution check — before implementation

- **I Spec-first**: PASS — this spec, plan, and tasks precede code.
- **II Test-first**: PASS — pgTAP, Edge, TypeScript, and shared KMP tests will be added and
  observed failing before each implementation path.
- **III Simplicity/YAGNI**: PASS — one request per Kit, coarse existing locality, one daily cap,
  no auction/analytics/creative subsystem.
- **IV Incremental delivery**: PASS — owner/staff workflow and visitor delivery have isolated
  executable acceptance tests.
- **V Observability**: PASS — request outcomes use structured logs without viewer keys.
- **VI Shared-first**: PASS — mobile model, client, repository, persistence, and state live in
  `shared/commonMain`; Compose contains display only. Web/admin/gallery remain independent apps.

## Data and API design

- `content.local_promotions`: unique `launch_kit_id`, derived tenant/exhibition/locality,
  lifecycle, schedule, review metadata, revision and audit fields.
- Both owner request and service selection join through a Launch Kit whose
  explicit entitlement source is `paid`; R3 `free_beta` Kits fail closed.
- `content.local_promotion_impressions`: promotion FK, 64-character viewer digest, Seoul date,
  coarse locality and timestamp; unique viewer/day global cap.
- Partial/composite eligibility index: status + schedule for approved/active rows.
- Indexed foreign keys for gallery, exhibition, Kit, reviewer, and promotion.
- Owner RPCs: request and list own promotion.
- Admin RPCs: filter/list requests; approve/reject with schedule or note.
- Service RPC: atomically select and record one eligible placement.
- Edge `promoted-nearby`: POST-only public facade; hashes raw installation key before RPC.

## UI design source

- Desktop concept:
  `/Users/hanshin/.codex/generated_images/019fb78a-01e0-7000-bd37-d1fec8eae08b/exec-92e74bd0-8889-41d1-afa1-bd4a49261c05.png`
- Mobile concept:
  `/Users/hanshin/.codex/generated_images/019fb78a-01e0-7000-bd37-d1fec8eae08b/exec-d215d4a7-3cfb-4763-9e43-b805b69961b6.png`

The concepts establish the open horizontal band, explicit paid/frequency disclosure, explanation
link, square geometry, and hard boundary before organic results. Existing product typography,
spacing, filter controls, and exhibition cards remain authoritative where the concepts differ.

## Complexity tracking

| Choice | Why required | Simpler alternative rejected |
|---|---|---|
| Runtime Edge selector | Static web build cannot enforce per-installation frequency | Build-time promotion would show repeatedly and violate FR-006 |
| Pseudonymous impression table | A cap shared across sessions requires durable atomic state | Local-only cap is bypassable and differs across clients |
| Separate promotion repository in KMP | Keeps paid data out of canonical catalogue model | Adding fields to `Exhibition` risks organic ranking coupling |

## Constitution check — after design

PASS. No business logic is proposed in Android/iOS source sets; no existing reader, curation,
or discovery contract is modified; all new privilege is function-scoped.
