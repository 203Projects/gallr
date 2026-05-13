# Map — Naver Open API auth-failure fix

**Date:** 2026-05-13
**Surface:** gallr web (`web/map/`)
**Scope:** static site only; iOS/Android map code is unaffected.

## Problem

The `/map/` page briefly renders the Naver Maps auth-failure placeholder image (gray N tile with "네이버 지도 Open API 인증이 실패했습니다.") in place of map tiles before resolving. Observed on production (`gallr.app`) even though the production domain is registered as an allowed Web Service URL for client ID `dkd2c8bh63` in the Naver Cloud Platform console.

The existing fallback in `web/client/map.js` only handles the case where the entire `window.naver.maps` SDK fails to load. When the SDK loads but tile requests transiently return the auth-fail PNG, the existing code has no detection path and the broken tiles render to the user.

A secondary risk: if a future deploy goes to a domain that *isn't* on the Naver allowlist (e.g., a Vercel preview URL), the SDK loads successfully but every tile is the auth-fail PNG with no recovery. Today users would see the broken grid; we should show our own fallback message instead.

## Goals

1. Eliminate the visible flash of Naver's auth-fail PNG on the production map page.
2. When auth actually fails (unlisted domain), show the existing branded fallback ("지도를 불러올 수 없습니다.") instead of Naver's placeholder grid.
3. No new dependencies, no build-step changes, no data changes.

## Non-goals

- Retry logic on auth failure (Naver does not recover within a session).
- Telemetry for auth-fail events (no analytics pipeline on the static site).
- Designed loading state (skeleton/shimmer) — visibility hiding is sufficient because the fast-path is sub-second.
- Changes to iOS/Android map (NMapsMap native SDK, unrelated codepath).

## Approach

Two complementary changes, both small.

### Part A — Visual stabilization

Hide the tile imagery until the Naver SDK fires its first `tilesloaded` event, with a 2-second safety timeout. This removes the brief flash where Naver's auth-fail PNG renders before real tiles arrive.

### Part B — Real auth-failure detection

Register `window.navermap_authFailure` — Naver's documented global hook — before the SDK script tag. When it fires, add the existing `.map-failed` class so the existing branded fallback renders.

Parts A and B are independent: A handles the production race, B handles true auth failures on unlisted-domain deploys.

## Changes

### `web/map/index.html`

Add an inline `<script>` that registers the auth-failure callback **before** the Naver SDK loads. The callback exists on `window` at SDK boot so Naver can call it synchronously.

```html
<script>
  window.navermap_authFailure = function () {
    var el = document.getElementById("naver-map");
    if (el) el.classList.add("map-failed");
  };
</script>
<script src="https://oapi.map.naver.com/openapi/v3/maps.js?ncpClientId={{ site.naverClientId }}"></script>
<script defer src="/scripts/map.js"></script>
```

The callback is intentionally minimal and inline — it must not depend on `client/map.js` having loaded, and it must not depend on the DOM being fully ready beyond `#naver-map` existing (it's directly above the script tag in the document, so it's in the DOM by parse time).

### `web/client/map.js`

Inside `initMap()`, after constructing `new naver.maps.Map(container, ...)`:

1. Add `container.classList.add("map-loading")` before the marker loop.
2. Register a one-shot listener with `naver.maps.Event.once(map, "tilesloaded", () => container.classList.remove("map-loading"))`.
3. Start a 2000ms `setTimeout` that also removes `map-loading` as a safety net (covers cases where tiles never load — ad-blocker, network failure — without leaving the map frozen-looking).

The 2-second timeout is unconditional and idempotent: even if `tilesloaded` already fired, removing an already-removed class is a no-op.

### `web/styles/main.css`

Add one rule alongside the existing `.map-failed` and `.map-empty` selectors:

```css
/* Hide tile imagery during initial load to avoid the brief flash
   where Naver's auth-fail placeholder PNG renders before real
   tiles arrive. */
.map-page__map.map-loading > * { visibility: hidden; }
```

The selector targets `> *` (children) rather than the container itself so that, if both `.map-loading` and `.map-failed` were ever present simultaneously, the existing `.map-failed::after` pseudo-element (rendered on the container) still shows the branded fallback text.

## Data flow

Three terminal states, each fully defined:

1. **Auth failure** (unlisted domain): SDK loads → `navermap_authFailure` fires → `.map-failed` added → existing CSS renders "지도를 불러올 수 없습니다.".
2. **Normal load** (allowlisted domain, tiles arrive): SDK loads → `initMap()` runs → `.map-loading` added → first `tilesloaded` fires → `.map-loading` removed → real map visible.
3. **Tiles blocked** (extension, network failure, allowlisted but tile CDN unreachable): SDK loads → `initMap()` runs → `.map-loading` added → no `tilesloaded` → 2s timeout removes `.map-loading` → map container shows Naver's default empty-tile background (no auth-fail PNG because Naver's auth check passed).

## Testing

Extend `web/tests/map-page.test.ts` with two new Playwright tests:

1. **`navermap_authFailure marks the map container .map-failed`** — install a route handler or SDK stub that invokes `window.navermap_authFailure()` synchronously on script load. Assert `#naver-map` has class `map-failed`.
2. **`map-loading class is removed after tilesloaded`** — stub the SDK with a `Map` constructor that exposes an event emitter; assert `.map-loading` is present immediately after construction and absent after firing `tilesloaded`.

The existing test `missing SDK marks the map container .map-failed` (lines 127–133) stays unchanged.

The 2-second timeout is not Playwright-tested (timing-based, brittle). It is exercised in manual verification only.

## Verification

Before/after on production (`gallr.app/map/`):
- Hard-refresh (Cmd-Shift-R) ten times. The auth-fail PNG must never be visible. Map either renders real tiles or shows the branded "지도를 불러올 수 없습니다." fallback.

Before/after on a Vercel preview URL (unlisted domain):
- Visit the preview `/map/`. The branded fallback must appear within ~2 seconds. The Naver N-tile grid must never be visible.

## Risks

- **Naver changes the callback name.** Low probability; `navermap_authFailure` is documented and stable across SDK v3. If it changes, only Part B regresses; Part A continues to work.
- **`tilesloaded` semantics change between SDK versions.** Mitigated by the 2-second safety timeout — worst case is the user sees the map become visible after 2s instead of on first tile paint.
- **A page load takes longer than 2s for legitimate reasons** (slow network). Tiles will become visible on `tilesloaded` whenever that fires; the timeout only triggers the *reveal*, not a *failure*. Acceptable.

## Out of scope

- Adding a designed loading state (skeleton/shimmer).
- Adding telemetry for auth-fail events.
- Adding retry logic.
- Any change to the Compose Multiplatform map screens.
