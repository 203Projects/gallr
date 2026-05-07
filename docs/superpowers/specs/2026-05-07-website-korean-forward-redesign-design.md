# Design Spec — gallrmap.com Korean-Forward Redesign

**Date:** 2026-05-07
**Status:** Approved (ready for implementation plan)
**Source:** `Downloads/260427-website-korean-forward-redesign-p1.md` (approved feature request, 2026-04-27)

---

## Overview

Make gallrmap.com Korean-primary. Korean copy renders as the dominant reading layer; English appears stacked beneath each Korean block in smaller, muted type as a secondary affordance for international readers. Activate live App Store and Google Play links — both apps have shipped, so the existing `/coming-soon` placeholders go away. No mobile app changes. No new dependencies.

---

## Goals

1. The site reads as a Korean site to a Korean speaker — visual hierarchy and rhythm centered on Korean.
2. Non-Korean readers can still get the message from the muted English beneath each Korean block.
3. Both store CTAs route to the live store listings.

---

## Non-goals

- No KO ↔ EN language toggle UI. The stack is the design.
- No per-locale routes (`/ko/`, `/en/`).
- No i18n framework. Copy is hand-curated in `_data/features.js`.
- No changes to pages other than the index sections (hero, features, downloads, about) and the shared card mockup. Privacy and coming-soon pages are untouched.
- No new design tokens. `#999999` is the existing secondary text color; we are consuming it.
- No mobile app (Compose / KMP) changes.
- No SEO / Open Graph / hreflang rework, no new analytics events.
- `coming-soon/index.html` stays on disk as an orphan to avoid breaking any externally-shared link.

---

## Architecture

A content + light-CSS update to the existing Eleventy site under `web/`. No runtime JavaScript added.

| Layer | Change |
|---|---|
| **Content** (`_data/features.js`) | Add `headlineKo` + `descriptionKo` to each feature entry. Replace mockup data with three Seoul galleries (Leeum, Amorepacific, Kukje) using KO-only fields. |
| **Templates** (`_includes/*.html`) | Render KO copy as primary text; render EN copy beneath inside a `<span class="bi-en">` (or `<p class="bi-en">` for the About paragraph). The hero `<h1>` is the lone exception — KO only. |
| **Style** (`web/styles/main.css`) | Add a single `.bi-en` utility class. Add a section-scoped restyle for the dark-section download buttons. |
| **Document** (`base.html` or equivalent) | Set `<html lang="ko">`. Every `.bi-en` element gets `lang="en"`. |
| **Links** | Hard-replace every `/coming-soon` href with the corresponding live store URL. |

---

## The bilingual pattern

### When KO-only

- Hero `<h1>` headline. The brand moment — Korean alone carries weight here, and English is dropped intentionally.

### When KO + muted EN stacked

Everywhere else with translatable content: hero tagline, section labels (`기능`, `gallr 소개`), feature headlines, feature descriptions, downloads heading and tagline, the About paragraphs.

**Rule of thumb:** Korean is the primary reading layer. English is a parallel rendering of the same idea, visually downweighted but never hidden.

### About section — paragraph-level stacking

Two full `<p>` elements: one Korean paragraph, then one English paragraph styled with `.bi-en`. **Not** sentence-by-sentence interleaving.

### Feature cards (mockups)

Cards inside the feature mockups are KO-only. They're a visual preview of the app, and the app is itself becoming KO-primary — bilingual cards would mislead about what the app shows.

---

## The `.bi-en` utility

```css
.bi-en {
  display: block;
  font-size: 0.78em;        /* em-relative — scales with the parent */
  color: #999999;            /* existing secondary text token */
  line-height: 1.4;
  margin-top: 0.25em;        /* tight pairing — KO and EN read as a unit */
  font-weight: 400;          /* always normal weight, even under bold KO */
}
```

**Three structural rules:**

1. **`em`-relative sizing.** A `.bi-en` under an `<h2>` ends up larger than one under a `<p>`. This preserves the typographic hierarchy of the page.
2. **Block display, span semantics.** Use `<span>` for inline-stacked secondary content (it's the "other rendering" of the same idea, part of the same heading or paragraph). `display: block` makes it stack visually. The exception is the About section, which uses two separate `<p>` elements because they are full paragraphs.
3. **`lang="en"` on every `.bi-en`.** With `<html lang="ko">` set, the muted English needs its own language attribute for screen readers and language-aware browsers.

### Markup pattern

```html
<!-- Section heading -->
<h2 class="features__heading">
  기능
  <span class="bi-en" lang="en">Features</span>
</h2>

<!-- Body / tagline -->
<p class="hero__tagline">
  오픈 예정 / 종료 예정 전시를 한눈에 — 더이상 보고싶었던 전시를 놓치지 마세요.
  <span class="bi-en" lang="en">Browse exhibitions opening and closing soon — so you never miss what matters.</span>
</p>

<!-- About — paragraph-level stacking -->
<div class="about__body">
  <p>좋은 전시를 너무 늦게 알게 되는 일, 이제는 없도록. ...</p>
  <p class="bi-en" lang="en">gallr is the easiest way to discover exhibitions in your city. ...</p>
</div>
```

---

## Approved copy

### Hero

| Element | Korean | English (muted, beneath) |
|---|---|---|
| Headline (KO-only) | 내 주변 전시를 발견하는 가장 쉬운 방법 | — *(English headline dropped intentionally; the spec lists `Discover the exhibitions defining your city.` for reference but it is not rendered in the hero)* |
| Tagline | 오픈 예정 / 종료 예정 전시를 한눈에 — 더이상 보고싶었던 전시를 놓치지 마세요. | Browse exhibitions opening and closing soon — so you never miss what matters. |

### Features section

**Section heading:** `기능` + muted `Features`

| Feature id | Korean headline | Korean description | English description |
|---|---|---|---|
| `discovery` | 내 근처 전시 찾기 | 지금 진행 중이거나 오픈 예정인 전시를 한눈에 확인하세요. 추천 전시, 에디터 픽, 그리고 이번 주 오픈·종료 전시를 큐레이션합니다. | Browse ongoing and upcoming exhibitions in your city with filters — Featured, Editor's picks, Opening This Week, and Closing This Week. |
| `bookmarking` | 관심 전시 저장하기 | 마음에 드는 전시를 저장해 나만의 리스트를 만들어보세요. 저장한 전시는 오프라인에서도 언제든 확인할 수 있어요. | Bookmark any exhibition to build your personal shortlist. Your saved exhibitions are available offline, so you always have your list at hand. |
| `filtering` | 원하는 기준으로 필터링 | 지역, 추천, 에디터 픽, 일정별로 전시를 필터링하고 나에게 필요한 전시만 골라보세요. | Narrow your view by region, featured picks, editor's picks, or timing — opening this week, closing this week. See only what's relevant to you. |

**Card mockups (KO-only):**

| Feature id | Title (KO) | Venue (KO) | Dates (KO) |
|---|---|---|---|
| `discovery` | 리움: 소장품 특별전 | 리움미술관 | 2026년 1월 15일 — 4월 28일 |
| `bookmarking` | 추상 기하학의 세계 | 아모레퍼시픽미술관 | 2026년 3월 3일 — 6월 12일 |
| `filtering` | 사진, 지금 | 국제갤러리 | 2026년 3월 20일 오픈 |

Card label: `전시` (replaces `Exhibition`).

### Downloads section

| Element | Korean | English (muted) |
|---|---|---|
| Heading | gallr 다운로드 | Download gallr |
| Tagline | 애플/안드로이드에서 무료로 다운로드하세요. | Available for iPhone and Android. Free to download. |
| Button labels | (English, unchanged) `App Store` / `Google Play` | — |

**Button styling on the dark download section:** white fill, black text, hover → orange (`#FF5400`). Hero buttons (light section) keep their existing styling.

### About section

**Section heading:** `gallr 소개` + muted `About`

**Korean paragraph:**
> 좋은 전시를 너무 늦게 알게 되는 일, 이제는 없도록.
> gallr는 국내 기관부터 갤러리, 대안공간까지 — 도시의 전시를 한곳에 모았습니다.

**English paragraph (beneath, muted):**
> gallr is the easiest way to discover exhibitions in your city. Great shows come and go, and sometimes people find out too late. From major institutions to independent spaces, we bring the full picture together in one place.

---

## Live store links

| Store | URL |
|---|---|
| Apple App Store | `https://apps.apple.com/kr/app/gallr-%EA%B0%A4%EB%9F%AC-%EC%A0%84%EC%8B%9C-%EC%A0%95%EB%B3%B4/id6760855059` |
| Google Play | `https://play.google.com/store/apps/details?id=com.gallr.app` |

Both hero CTA buttons and both download-section buttons use these URLs. After the change, `/coming-soon` must not appear in any rendered href on the index page.

---

## Design tokens (unchanged)

| Role | Value |
|---|---|
| Primary text | `#000000` |
| Secondary text (`.bi-en`) | `#999999` |
| Accent / hover | `#FF5400` |
| Background | `#ffffff` |
| Dark section (downloads) | `#000000` background, `#ffffff` button fill |

---

## Files to change

| File | Change |
|---|---|
| `web/_data/features.js` | Add `headlineKo`, `descriptionKo` per entry; swap mockup data to Seoul galleries with KO-only `titleKo`, `venueKo`, `dateRangeKo` fields. |
| `web/_includes/hero.html` | KO-only headline; stacked KO + muted EN tagline; CTA hrefs → live store URLs. |
| `web/_includes/features.html` | Section heading stacks (`기능` + muted `Features`); each feature renders `headlineKo` as KO-only `<h3>` and `descriptionKo` as primary with EN inside `.bi-en`. |
| `web/_includes/card-mockup.html` | Label hardcoded to `전시`; reads `mockup.titleKo`, `mockup.venueKo`, `mockup.dateRangeKo`; aria label uses Korean title. |
| `web/_includes/downloads.html` | Heading and tagline stack (KO + muted EN); both button hrefs → live store URLs; button styling for dark section per spec. |
| `web/_includes/about.html` | Section heading stacks; body becomes one KO `<p>` followed by one `.bi-en` `<p>` (paragraph-level stacking). |
| `web/_includes/base.html` *(or wherever `<html>` lives)* | Set `<html lang="ko">`. |
| `web/styles/main.css` | Add `.bi-en` utility; add section-scoped `.downloads .btn--primary` (white fill, black text) and its `:hover` (orange fill). |
| `web/coming-soon/index.html` | Untouched on disk. De-linked everywhere. |

---

## Acceptance criteria

### Build

- `npm run build` succeeds.
- Built `index.html` contains the new Korean strings (spot-check headline, taglines, section labels, mockup titles).
- Built `index.html` contains zero `href="/coming-soon"` references.
- Built `<html>` element has `lang="ko"`.
- Every `.bi-en` element in the build has `lang="en"`.

### Accessibility (pa11y)

- Zero new violations vs. the current baseline.
- Specific risk to verify: contrast of `#999999` on `#ffffff`. WCAG AA requires ≥4.5:1 for body text below 18px (or below 14px bold). At `0.78em` under typical body sizes the resulting EN text may fall under 14px. If pa11y flags this, the mitigation is `font-size: max(0.78em, 14px)` on `.bi-en` and re-verify. Color is fixed by token; do not darken without coming back to the user.

### Visual acceptance (Playwright)

One spec per section asserting:
- KO copy is present in the rendered text.
- EN copy is present **and** computed `color` is `rgb(153, 153, 153)`.
- Hero `<h1>` does not contain any English substring from the spec.

One spec for links:
- Hero App Store CTA `href` matches the App Store URL exactly.
- Hero Google Play CTA `href` matches the Google Play URL exactly.
- Same assertions for the downloads section.
- No element on the page has `href="/coming-soon"`.

One responsive spec at 375px (mobile) and 1280px (desktop) confirming the stacked KO/EN does not overflow either container.

### Manual QA (gstack `/browse`)

- Read-through in Korean: feels native, English does not visually compete.
- Read-through ignoring Korean: muted English alone still conveys the message.
- Tap both store buttons in mobile viewport — open the correct store listings.

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| `.bi-en` contrast fails WCAG AA at small sizes | Floor `.bi-en` font-size at 14px via `max(0.78em, 14px)`. |
| External link still references `/coming-soon` from social previews / cached pages | Leave the page on disk as an orphan; no 404 for stale inbound links. |
| Korean Web fonts (Inter) lack glyph coverage for the chosen Korean copy | Verified: copy uses standard Hangul + Latin punctuation. If a glyph regression surfaces in build, fall back stack should include a system Korean font (e.g., `system-ui`). Do not introduce a new font dependency. |
| Reference mockup at `.superpowers/brainstorm/96533-1777291306/content/full-page-v5.html` is not present locally | Reference is informational only; this spec is the authoritative source of truth for copy and styling. |

---

## Reference

Original feature request: `Downloads/260427-website-korean-forward-redesign-p1.md` (approved 2026-04-27).
