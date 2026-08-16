# Design QA — Build 2: Contextual Commitment

## Evidence

- Source visual truth: `/Users/hanshin/.codex/generated_images/019ffa82-113d-7921-b69e-9232376a7ec1/exec-00fe575d-8d2e-4e31-9ecb-4cbc76dff95e.png`
- Rendered implementation: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/gallr-build-2-contextual-commitment.png`
- Full-view comparison: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/gallr-build-2-design-comparison.png`
- Focused rationale comparison: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/gallr-build-2-focused-comparison.png`
- Local preview: `http://localhost:3200`

## Capture Context

- Target state: iOS dark mode, Korean, a followed gallery, and the app-owned “new exhibition alert” rationale open before the system permission request.
- Implementation device: iPhone 17 simulator, logical viewport `402 × 874`, native screenshot `1206 × 2622`, `@3x` density.
- Source pixels: `853 × 1844`; the concept is approximately a `426.5 × 922` logical viewport at `@2x`.
- Density normalization: both artifacts were normalized to `1844 px` height for comparison. The source remained `853 × 1844`; the implementation became `848 × 1844`.
- Browser presentation: the running simulator is streamed through serve-sim in the Codex in-app browser. Browser error and warning logs were checked after the final capture and were empty.

## Findings

- No actionable P0, P1, or P2 differences remain.
- Fonts and typography: the implementation preserves the source’s bold two-line Korean headline, compact label hierarchy, readable supporting copy, and square CTA treatment. The final explicit foreground colors retain contrast on the dark rationale surface.
- Spacing and layout rhythm: the gallery image uses the corrected wide crop; the rationale’s label, headline, explanation, device-only row, CTA, dismissal, and settings note follow the source’s order and square 8pt-grid rhythm.
- Colors and visual tokens: the implementation uses Gallr’s monochrome dark tokens, `#FF5400` only as the active indicator, subtle gray borders, and a high-contrast white CTA.
- Image quality and asset fidelity: the implementation uses the catalogue’s real high-resolution exhibition image and the product’s existing Gallr logo asset. No placeholder, emoji, handcrafted SVG, or CSS-drawn asset replaces a source asset.
- Copy and content: the Korean rationale communicates the same commitment as the source—one-device alerts, no account required for local follows or visits, a clear allow action, a reversible dismissal, and settings recovery.

## Accepted Product/Runtime Differences

- The source concept uses Kukje Gallery and a generated installation image; the implementation intentionally uses the selected catalogue record for Sehwa Museum and its real exhibition image.
- The source concept contains a square “G” mark; the implementation intentionally retains Gallr’s current product logo rather than introducing a second brand mark.
- The native iOS status bar and safe areas remain visible. The source concept omits device chrome, but removing native chrome would conflict with the real mobile runtime.

## Comparison History

1. Initial comparison found a P2 composition mismatch: the exhibition image was too tall and pushed the alert rationale lower-context content out of alignment. The gallery crop changed from `4:3` to `8:5`, and title-to-image spacing was tightened. Navigation testing also found that a parent focus-clearing click handler intercepted venue taps; it was removed so the gallery route is reachable.
2. Post-layout comparison found a P1 contrast issue: the rationale label and headline inherited a dark local content color on the dark sheet. Explicit `onBackground` foreground colors and an `onSurfaceVariant` notification icon tint were applied.
3. Final full-view and focused comparisons show the corrected wide image composition, readable rationale hierarchy, matching button order and proportions, and no remaining actionable P0/P1/P2 issue.

## Primary Interactions Tested

- Featured exhibition → exhibition detail → gallery name → gallery detail.
- Follow/unfollow state and local persistence.
- New exhibition alert control → Gallr rationale.
- “Allow alerts” → native iOS notification permission sheet.
- Permission denial → follow remains saved, gallery alert remains off, and a non-blocking settings recovery message appears.

## Implementation Checklist

- [x] Gallery detail route is reachable from exhibition detail and My Gallr following.
- [x] Follow and per-gallery alert preference persist locally.
- [x] Gallr rationale appears before the native notification permission sheet.
- [x] Denial preserves follow state and offers recovery guidance.
- [x] Source and implementation were compared at normalized density in full and focused views.
- [x] KMP tests, formatting, Android debug assembly, iOS framework link, and iOS host build passed.

## Follow-up Polish

- P3: Re-evaluate the concept’s square “G” mark only as part of a deliberate product-wide brand update, not within this isolated flow.

Prior build result: passed

---

# Design QA — My Gallr Activation Entry Points (Superseded)

## Evidence

- Source visual truth: `/Users/hanshin/.codex/generated_images/019ffa82-113d-7921-b69e-9232376a7ec1/exec-df4fbdfb-1d33-4aa6-bf5f-ed3cafa9ac93.png`
- Featured activation implementation: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-activation-implemented.png`
- Gallery search implementation: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-gallery-search-implemented.png`
- Gallery history implementation: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-gallery-history-implemented.png`
- Full-view activation comparison: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-qa-comparison.png`
- Focused search comparison: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-search-qa-comparison.png`
- Focused gallery-history comparison: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-history-qa-comparison.png`

## Capture Context

- Device and viewport: iPhone 17 simulator, `402 × 874` logical points, native screenshots `1206 × 2622`, `@3x`.
- Source pixels: the four-panel board is `1693 × 929`; each compared phone panel was cropped to approximately `423 × 929`.
- Density normalization: source phone crops and native implementation captures were normalized to `402 × 874` before side-by-side comparison. The comparison images are `804 × 874`.
- State: light mode, anonymous/device-only archive, Korean product language, live catalogue imagery. The source uses English concept copy and fixed Kukje fixtures; content and locale differences were not treated as spacing defects.
- Runtime proof: the branch was built, installed, and launched as `com.gallr.app` through the iOS simulator workflow. Runtime and OS logs contained no app fatal, uncaught, crash, or exception entry; one simulator WebKit accessibility duplicate-class warning is external to Gallr.

## Findings

- No actionable P0, P1, or P2 differences remain.
- Fonts and typography: the existing Inter/Gothic A1 hierarchy produces the same bold headline, compact metadata, and uppercase/label rhythm as the concept. Korean copy wraps cleanly without truncation at the narrower real device viewport.
- Spacing and layout rhythm: all new surfaces use square borders, zero radius, the established screen margin, and 8pt spacing. The archive card now matches the concept's compact header-dismiss/description/CTA/learn-more order.
- Colors and visual tokens: the implementation stays monochrome and uses `#FF5400` only for the primary archive CTA and existing active indicators. Follow and alert actions remain outlined until committed.
- Image quality and asset fidelity: exhibition and visit thumbnails use real catalogue images with stable crops. Gallr's existing product logo is retained. No placeholder drawing, emoji, handcrafted SVG, or generated substitute was introduced.
- Copy and content: the Korean copy preserves the concept's private-archive promise and explicitly says no account is needed. Search groups galleries before exhibitions, and the gallery page shows visit history, follow/alert state, and the expandable programme.
- Accessibility and behavior: all primary actions expose native button labels; CTA and programme controls meet the existing 48dp target; the archive prompt disappears after save; the search follow action persists immediately.

## Accepted Product and Data Differences

- The concept uses generated Kukje/PKM fixtures and square gallery logo blocks. The running app uses live catalogue galleries and omits unavailable gallery logos rather than fabricating assets.
- The source combines follow and alert copy into one prominent action. The implementation keeps follow and per-gallery notification permission as separate reversible actions, matching the already-approved alert model.
- Dynamic live content produced one recorded visit in the gallery-history proof rather than the source's two. The row structure, border, image treatment, metadata hierarchy, and count update correctly.
- The native iOS status bar and current Gallr logo remain visible; the concept is an unframed product board.

## Comparison History

1. Initial simulator QA found a P2 density mismatch in the Featured gate: an extra full-width dismissal action made the card materially taller than the concept. It was replaced with the source's compact header close control.
2. Initial gallery-search interaction found a P1 usability defect: retaining the existing lazy-list anchor could skip newly inserted gallery results after typing a query. Search changes now reset the result list to its first item.
3. Post-fix activation, search, ended-exhibition prompt, and gallery-history states were recaptured. Normalized full-view and focused comparisons show the corrected card proportion, visible gallery-first results, square visual language, and no remaining actionable P0/P1/P2 finding.

## Primary Interactions Tested

- Featured activation card → Start your archive → Add past visits mode.
- List search → grouped gallery results → follow without authentication.
- Gallery result → gallery detail → expand full programme → open exhibition.
- Ended exhibition → Yes, I visited → prompt disappears and visit persists.
- Visited exhibition venue → gallery detail → visit-history row and count appear.
- Gallery back navigation returns to the originating List or exhibition detail state.

## Implementation Checklist

- [x] Featured gate is visible only for an empty, undismissed archive.
- [x] Archive CTA opens Add past visits directly without an auth gate.
- [x] Ended, unrecorded exhibitions offer one-tap visit capture.
- [x] Gallery search results appear before exhibition results and expose follow state.
- [x] Canonical catalogue requests stable `gallery_id`; legacy selection remains unchanged.
- [x] Gallery detail renders archived visits and an expandable full programme.
- [x] Source and implementation were compared at normalized density in full and focused views.
- [x] KMP formatting and all shared/Compose Android and iOS simulator tests pass.

## Follow-up Polish

- P3: Add real gallery logo assets only after the catalogue owns a verified logo field; do not synthesize monograms to imitate the concept.

Prior iteration result: passed

---

# Design QA — Exhibition-Detail Visit Row

This pass supersedes the Featured activation gate above. The current direction keeps discovery uninterrupted and places visit capture only in exhibition detail.

## Evidence

- Source visual truth: `/Users/hanshin/.codex/generated_images/019ffa82-113d-7921-b69e-9232376a7ec1/exec-07bd82b3-f728-4480-b0de-8c8df9e3c0e6.png`
- Rendered component: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-detail-visit-row-implemented.png`
- Featured without activation gate: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-featured-without-gate.png`
- Full-view comparison: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-detail-visit-row-comparison.png`
- Focused row comparison: `/Users/hanshin/.codex/visualizations/2026/08/13/019ffa82-113d-7921-b69e-9232376a7ec1/my-gallr-detail-visit-row-focused-comparison.png`

## Capture Context

- Device and viewport: iPhone 17 simulator, `402 × 874` logical points, native screenshots `1206 × 2622`, `@3x`.
- Source pixels: `853 × 1844`; both full views were normalized to `402 × 874` for comparison.
- State: light mode, Korean product language, live catalogue imagery.
- The row was exposed on an unvisited current exhibition only for visual capture because the live catalogue had no navigable unvisited ended-detail fixture. The diagnostic condition was reverted after capture; production logic and tests restrict the prompt to ended, unrecorded exhibitions.

## Findings

- No actionable P0, P1, or P2 differences remain.
- Typography: the implementation preserves the compact two-level prompt (`이 전시를 방문했나요?` / `MY GALLR`) and a single right-aligned action.
- Spacing: the row uses the selected slim, full-width composition with 8pt-grid padding and a minimum 44dp action target.
- Colors and shape: the surface remains monochrome and square; only the directional arrow uses Gallr orange `#FF5400`.
- Hierarchy: hairline dividers integrate the prompt into exhibition metadata instead of presenting a promotional card.
- Copy: the Korean and English labels describe the concrete action—recording a visit—without introducing account language or an authentication gate.

## Accepted Product and Data Differences

- The source mock uses a generated ended Kukje Gallery fixture. The runtime proof uses a real current Sehwa Museum catalogue record solely to expose the component for capture.
- The implementation keeps Gallr's existing native detail toolbar, venue address, hours, ticket action, and real exhibition image rather than replacing surrounding production content with mock fixtures.

## Primary Interactions and Rules Tested

- Featured launches without the archive activation gate.
- The visit row exposes one enabled `기록하기 →` action with a native button target.
- Ended and unrecorded returns `true`; visited or current returns `false` in `MyGallrActivationRulesTest`.
- Recording remains local-first and does not require account creation.
- Final iOS simulator build and launch succeeded after restoring the ended-only condition.

## Comparison History

1. The first implementation used a bordered activation card and a full orange CTA. Product review found it too conspicuous for discovery.
2. The Featured activation gate was removed entirely and the detail action was reduced to a divider-bound metadata row.
3. The final full-view and focused comparisons show matching prompt/action hierarchy, square treatment, monochrome palette, and orange-arrow emphasis.

final result: passed
