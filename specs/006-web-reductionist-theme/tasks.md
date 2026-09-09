# Tasks: Web Reductionist Design System

> **Status: Implemented historical plan.** The web theme shipped in commit
> `16c5bb2`. The remaining unchecked box records an original manual acceptance
> pass whose evidence was not captured; it is not an active roadmap item.
> Current public-web verification is defined by `web/AGENTS.md`.

**Input**: Design documents from `/specs/006-web-reductionist-theme/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, quickstart.md ✅

**Tests**: No test tasks generated — this feature is a CSS-only visual update with no new shared-module logic. Acceptance is via visual inspection per quickstart.md (SC-001 through SC-008) and the existing pa11y/Playwright test suite.

**Organization**: Tasks grouped by user story for independent delivery.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: User story this task belongs to
- All paths are relative to repository root

---

## Phase 1: Setup (Font Package Swap)

**Purpose**: Swap out the old font npm packages and wire in `@fontsource/inter` — required before any CSS font-face or build pipeline changes can succeed.

- [x] T001 In `web/package.json`, remove `@fontsource/playfair-display` and `@fontsource/jetbrains-mono` from devDependencies and add `"@fontsource/inter": "^5.1.0"`; then run `npm install` inside `web/` to install the Inter package

**Checkpoint**: `web/node_modules/@fontsource/inter/files/` exists and contains WOFF2 files — Phase 2 can begin.

---

## Phase 2: Foundational (Font Pipeline — Blocks User Story 1)

**Purpose**: Update the font copy build script to produce `inter-400.woff2`, `inter-500.woff2`, and `inter-700.woff2` in `web/public/fonts/`. This is the physical font asset that all typography CSS depends on.

**⚠️ CRITICAL**: US1 typography changes cannot be validated until these WOFF2 files are in place.

- [x] T002 Update `web/scripts/copy-fonts.js` — replace the two existing FONT_MAP entries (playfair-display and jetbrains-mono) with a single Inter entry that copies `inter-latin-400-normal.woff2` → `public/fonts/inter-400.woff2`, `inter-latin-500-normal.woff2` → `public/fonts/inter-500.woff2`, and `inter-latin-700-normal.woff2` → `public/fonts/inter-700.woff2` from `node_modules/@fontsource/inter/files`

**Checkpoint**: Run `node web/scripts/copy-fonts.js` — `web/public/fonts/inter-400.woff2`, `inter-500.woff2`, and `inter-700.woff2` are present. No playfair or jetbrains files needed.

---

## Phase 3: User Story 1 — Editorial Identity Through Neo-Grotesque Typography (Priority: P1) 🎯 MVP

**Goal**: All text on the site renders in Inter; Playfair Display and JetBrains Mono are completely replaced. Visual parity with the KMP app (005) is achieved at the typographic level.

**Independent Test**: Open `http://localhost:8080` after `npm run build && npm run dev`. In browser DevTools, inspect computed `font-family` on the hero headline, a feature card title, the features section label, a button label, and footer copy. All must resolve to `Inter` (or system-ui fallback). No serif or monospaced text is visible anywhere.

### Implementation for User Story 1

- [x] T003 [US1] In `web/styles/tokens.css`: replace the three `@font-face` blocks (Playfair Display 400, Playfair Display 700, JetBrains Mono 400) with three new `@font-face` blocks for Inter — `font-family: "Inter"`, weights 400/500/700, `src: url("/fonts/inter-400.woff2")`, `url("/fonts/inter-500.woff2")`, `url("/fonts/inter-700.woff2")` respectively, all with `font-display: swap`; then update `--font-display` token value from `"Playfair Display", Georgia, "Times New Roman", serif` to `"Inter", system-ui, -apple-system, Arial, sans-serif`, and `--font-meta` from `"JetBrains Mono", "Courier New", Courier, monospace` to the same Inter stack

- [x] T004 [P] [US1] In `web/_data/tokens.js`: update `font.display` value from `"'Playfair Display', Georgia, 'Times New Roman', serif"` to `"'Inter', system-ui, -apple-system, Arial, sans-serif"`, and `font.meta` from `"'JetBrains Mono', 'Courier New', Courier, monospace"` to the same Inter stack

- [x] T005 [P] [US1] In `web/_includes/base.html`: update the `<link rel="preload">` tag's `href` from `/fonts/playfair-display-700.woff2` to `/fonts/inter-700.woff2`

**Checkpoint**: Run `npm run build && npm run dev` in `web/`. Visit `http://localhost:8080` — all text uses Inter. No serif or monospaced text visible. US1 independently verifiable.

---

## Phase 4: User Story 2 — #FF5400 Accent on Primary CTAs (Priority: P2)

**Goal**: Primary CTA buttons render in vivid orange (#FF5400) as the sole colour-differentiated element on the page. Hover/focus states shift contrast within 100ms via `color-mix()`. All other controls remain monochrome.

**Independent Test**: Open `http://localhost:8080`. Scan every button — only `btn--primary` is orange. All other buttons and links are monochrome (black/white). Hover over the primary CTA — background darkens to approximately #CC4400 with no animation. Confirm no other element on the page uses orange.

### Implementation for User Story 2

- [x] T006 [US2] In `web/styles/tokens.css`: add `--color-accent: #FF5400;` to the `:root` block under the color tokens section (after `--color-ink-secondary`)

- [x] T007 [P] [US2] In `web/_data/tokens.js`: add `accent: "#FF5400"` to the `color` object (after `inkSecondary`)

- [x] T008 [US2] In `web/styles/main.css`: update the `.btn--primary` rule — change `background-color` from `var(--color-ink)` to `var(--color-accent)` and `color` from `var(--color-paper)` to `var(--color-paper)`; update `.btn--primary:hover` — change `background-color` from `var(--color-paper)` to `color-mix(in srgb, var(--color-accent) 80%, black)` and `color` from `var(--color-ink)` to `var(--color-paper)` (white text stays white on hover)

- [x] T009 [P] [US2] In `web/styles/main.css`: update the `.btn:focus-visible` rule — change `outline: 3px solid var(--color-ink)` to `outline: 3px solid var(--color-accent)`

**Checkpoint**: Primary CTA buttons are orange; outline buttons remain monochrome; focus ring is orange. US2 independently verifiable without US3.

---

## Phase 5: User Story 3 — Whitespace as the Sole Section Separator (Priority: P3)

**Goal**: The thick 4px black `border-top` separating page sections is removed. Section transitions are communicated by vertical spacing alone, matching the KMP app's whitespace-only zone separator approach.

**Independent Test**: Open `http://localhost:8080`. Scroll through Hero → Features → Downloads → About. No thick horizontal line appears between any sections. In DevTools, confirm `#features`, `#downloads`, and `#about` have no `border-top` applied.

### Implementation for User Story 3

- [x] T010 [US3] In `web/styles/main.css`: remove the `border-top: var(--border-section);` property from the `#features, #downloads, #about` selector block (retain all other properties on those selectors unchanged)

**Checkpoint**: Scroll the homepage — zero decorative borders between sections. Whitespace alone separates content zones.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Build verification and final visual acceptance.

- [x] T011 [P] Run `npm run build` in `web/` — verify `web/dist/fonts/` contains `inter-400.woff2`, `inter-500.woff2`, `inter-700.woff2` and does NOT contain `playfair-display-*.woff2` or `jetbrains-mono-*.woff2`; verify `web/dist/styles/tokens.css` contains `--color-accent` and Inter `@font-face` blocks; verify `web/dist/styles/main.css` has no `border-top: var(--border-section)` on the section selectors
- [ ] T012 Run quickstart.md acceptance criteria — open all 3 tabs, verify SC-001 through SC-008 pass; run `npm test` in `web/` for pa11y + Playwright checks ⚠️ MANUAL STEP — requires T001–T010 complete

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — needs `@fontsource/inter` installed
- **Phase 3 (US1)**: Depends on Phase 2 — Inter WOFF2 files must exist in `public/fonts/` before `@font-face` declarations can be validated
- **Phase 4 (US2)**: Depends on Phase 1 completion only — no dependency on US1; token and CSS changes are independent
- **Phase 5 (US3)**: No dependencies — single CSS property removal; can run in parallel with US1 and US2
- **Phase 6 (Polish)**: Depends on all story phases complete

### User Story Dependencies

- **US1 (P1)**: After Phase 2 — no dependency on US2/US3
- **US2 (P2)**: After Phase 1 — no dependency on US1/US3
- **US3 (P3)**: After Phase 1 — no dependency on US1/US2

### Parallel Opportunities

- **Phase 3**: T004 and T005 are parallel after T003 completes (different files)
- **Phase 4**: T007 and T009 are parallel after T006 completes (different files); T007 is parallel with T006 (different files)
- **Phase 5**: T010 can run in parallel with Phase 3 and Phase 4 entirely (different CSS selector, different concern)
- **Phase 6**: T011 is parallel with T012 (one is automated, one manual)

---

## Parallel Example: After Phase 2 completes

```
# US1, US2, and US3 can all proceed in parallel:
Parallel:
  T003 → T004 + T005    (US1 — typography: tokens.css, then tokens.js + base.html)
  T006 → T007 + T008 + T009  (US2 — accent: tokens.css, then tokens.js + main.css x2)
  T010                  (US3 — section borders: single CSS line removal)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Install `@fontsource/inter`
2. Phase 2: Update `copy-fonts.js`
3. Phase 3: Update `tokens.css` + `tokens.js` + `base.html`
4. **STOP and VALIDATE**: Build and open site — all text is Inter, no serif/monospaced
5. US1 is shippable independently as a clean typography parity release

### Incremental Delivery

1. Setup (T001) → Foundational (T002) → US1 (T003–T005) → **visual MVP**
2. US2 (T006–T009) → orange primary CTA active
3. US3 (T010) → section borders removed
4. Polish (T011–T012) → build verified, acceptance criteria confirmed

---

## Notes

- No test tasks generated — this is a CSS design system update; acceptance via quickstart.md criteria
- All changes are in `web/styles/`, `web/_data/`, `web/_includes/`, `web/scripts/`, `web/package.json`
- Zero changes to `shared/` KMP module (marketing website is an independent artifact per constitution)
- Do NOT hardcode `#FF5400` anywhere — always reference `var(--color-accent)` in CSS
- Do NOT remove `--border-section` token definition from `tokens.css` — only stop applying it; the token may be useful for future structural borders
- Commit after each checkpoint to enable incremental review
