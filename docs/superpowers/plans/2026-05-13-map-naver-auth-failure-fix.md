# Map Naver Auth-Failure Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the brief flash of Naver's auth-fail placeholder PNG on the production `/map/` page, and add real auth-failure detection so unlisted-domain deploys render the branded fallback instead of the Naver N-tile grid.

**Architecture:** Two small, independent client-side changes inside `web/`. Part A hides the map's tile imagery via a `.map-loading` class that is removed on Naver's `tilesloaded` event (with a 2s safety timeout). Part B registers the `window.navermap_authFailure` global callback before the SDK loads, so authentic auth failures add the existing `.map-failed` class and render the existing branded fallback.

**Tech Stack:** Eleventy 3.x static site, Nunjucks templates (`.html` in `_includes` and routes), vanilla ES2022 client JS (`web/client/map.js`), Playwright 1.49 for tests, Naver Maps JS SDK v3 (`https://oapi.map.naver.com/openapi/v3/maps.js`).

---

## Context for the engineer

- **Repo root:** `/Users/hanshin/Documents/Projects/gallr`
- **Working branch (already checked out):** `042-map-naver-auth-failure-fix`, based on `develop`. Spec is already committed at `docs/superpowers/specs/2026-05-13-map-naver-auth-failure-fix-design.md`.
- **Web app directory:** `/Users/hanshin/Documents/Projects/gallr/web/` — all paths below are relative to this unless absolute.
- **Test command:** `cd web && npx playwright test tests/map-page.test.ts`. The full website test suite is `cd web && npm test`, but for iteration the targeted command is faster.
- **Dev server:** `cd web && npm run dev`. Eleventy hot-rebuilds on file changes; refresh the browser at the URL it prints (typically `http://localhost:8080/map/`).
- **Naver client ID:** `dkd2c8bh63`, from `web/_data/site.json`. Production `gallr.app` is registered as an allowed Web Service URL for this client ID. The auth-fail flash on production is a transient/race condition, not a real auth failure.
- **Existing failure handling:** `web/client/map.js:78-82` already adds `.map-failed` when `window.naver.maps` is missing entirely. The CSS at `web/styles/main.css:1416-1427` renders "지도를 불러올 수 없습니다." over the empty container. We are *extending* this pattern — same class, same fallback, but triggered by additional conditions.

## File Structure

**Modified files (3):**
- `web/map/index.html` — add inline `<script>` that defines `window.navermap_authFailure` *before* the Naver SDK `<script>` tag.
- `web/client/map.js` — add `.map-loading` class management inside `initMap()`: add it, remove it on `tilesloaded`, also remove it after a 2s safety timeout.
- `web/styles/main.css` — add one new CSS rule `.map-page__map.map-loading > *` that hides children via `visibility: hidden`.

**Modified test file (1):**
- `web/tests/map-page.test.ts` — extend the SDK stub to support an event-emitter API for `Map` and a `__test.fireAuthFailure()` hook, then add two new tests (auth-failure callback, loading-class removal on tilesloaded).

No new files are created. No build configuration, no dependencies, no data changes.

---

## Task 1: Add the `navermap_authFailure` global callback (Part B)

**Files:**
- Modify: `web/map/index.html` (lines 46-48)
- Test: `web/tests/map-page.test.ts`

We register the callback first because it's the simpler change and gives us a stable foundation: any later failure during tile reveal can still surface the branded fallback if Naver decides the request was unauthorized.

- [ ] **Step 1: Extend the test SDK stub with an auth-failure hook**

The current stub in `web/tests/map-page.test.ts:9-52` doesn't expose a way to trigger `navermap_authFailure`. Add a `__test.fireAuthFailure()` method that calls the global if it's defined.

Replace the `__test` block (currently lines 44-48) so it reads:

```javascript
        __test: {
          fireMarkerClick: function (i) { markerClickHandlers[i] && markerClickHandlers[i](); },
          lastSetCenter: function () { return lastSetCenter; },
          lastSetZoom: function () { return lastSetZoom; },
          fireAuthFailure: function () {
            if (typeof window.navermap_authFailure === "function") {
              window.navermap_authFailure();
            }
          },
        },
```

- [ ] **Step 2: Write the failing test**

Add this test to `web/tests/map-page.test.ts`, immediately before the closing `});` of the `test.describe` block (after the existing "missing SDK marks the map container .map-failed" test at line 127):

```typescript
  test("navermap_authFailure adds .map-failed to the map container", async ({ page }) => {
    await page.goto("/map/");
    // Wait until our inline callback is defined on window. The route
    // stub above already replaces the SDK, so this only verifies the
    // page's <script> tag set the global before the SDK ran.
    await page.waitForFunction(() => typeof (window as any).navermap_authFailure === "function");
    // Fire the auth-failure callback as Naver's tile CDN would on a
    // referrer-rejection — via the test hook so we don't depend on
    // SDK internals.
    await page.evaluate(() => (window as any).naver.maps.__test.fireAuthFailure());
    await expect(page.locator("#naver-map")).toHaveClass(/map-failed/);
  });
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd web && npx playwright test tests/map-page.test.ts -g "navermap_authFailure" --reporter=list`

Expected: FAIL — `waitForFunction` times out because `window.navermap_authFailure` is never defined.

- [ ] **Step 4: Add the inline callback to the map page**

Open `web/map/index.html`. The current tail of the file (lines 46-48) is:

```html
{# Naver Maps SDK — public client ID is referrer-restricted in the Naver console. #}
<script src="https://oapi.map.naver.com/openapi/v3/maps.js?ncpClientId={{ site.naverClientId }}"></script>
<script defer src="/scripts/map.js"></script>
```

Replace with:

```html
{# Naver Maps SDK — public client ID is referrer-restricted in the Naver console. #}
{# Define the documented auth-failure callback BEFORE the SDK loads so
   Naver can invoke it synchronously when a referrer rejection occurs. #}
<script>
  window.navermap_authFailure = function () {
    var el = document.getElementById("naver-map");
    if (el) el.classList.add("map-failed");
  };
</script>
<script src="https://oapi.map.naver.com/openapi/v3/maps.js?ncpClientId={{ site.naverClientId }}"></script>
<script defer src="/scripts/map.js"></script>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd web && npx playwright test tests/map-page.test.ts -g "navermap_authFailure" --reporter=list`

Expected: PASS, 1 test.

- [ ] **Step 6: Run the full map-page test file to verify nothing regressed**

Run: `cd web && npx playwright test tests/map-page.test.ts --reporter=list`

Expected: all 8 tests pass (7 pre-existing + 1 new).

- [ ] **Step 7: Commit**

```bash
git add web/map/index.html web/tests/map-page.test.ts
git commit -m "feat(map): handle navermap_authFailure with branded fallback"
```

---

## Task 2: Add `.map-loading` CSS rule

**Files:**
- Modify: `web/styles/main.css` (insert after the `.map-failed` block at line 1427)

This is a small CSS-only step. We add it before wiring the class in JS so the visual rule exists when Task 3's JS code starts toggling it.

- [ ] **Step 1: Add the CSS rule**

Open `web/styles/main.css`. Locate the block ending at line 1427 — it closes the `.map-page__map.map-failed::after` rule with `}`. Immediately *after* that closing brace and before the comment `/* No exhibitions had lat/lng. */` on line 1428, insert:

```css
/* Hide tile imagery during initial load to avoid the brief flash where
   Naver's auth-fail placeholder PNG renders before real tiles arrive.
   Targets children (> *) so .map-failed::after (rendered on the
   container itself) still surfaces if both classes coexist. */
.map-page__map.map-loading > * { visibility: hidden; }
```

- [ ] **Step 2: Smoke-check the CSS builds**

The site has no CSS preprocessing; Eleventy passes through `styles/main.css` as-is. Verify by running:

```bash
cd web && npm run build
```

Expected: build succeeds, no warnings about CSS. The output `web/dist/styles/main.css` should contain the new rule. Quick check:

```bash
grep -n "map-loading" web/dist/styles/main.css
```

Expected: one match showing the new selector.

- [ ] **Step 3: Commit**

```bash
git add web/styles/main.css
git commit -m "style(map): hide tiles during load via .map-loading"
```

---

## Task 3: Toggle `.map-loading` from `initMap()` (Part A)

**Files:**
- Modify: `web/client/map.js` (the `initMap()` function, lines 78-126)
- Test: `web/tests/map-page.test.ts`

This is the core visual-stabilization change. The `Map` constructor in the production SDK exposes events via `naver.maps.Event.addListener` / `naver.maps.Event.once`. Our test stub currently has a no-op `addListener` for markers only; we extend it to handle the `Map` instance's `tilesloaded` event too.

- [ ] **Step 1: Extend the test SDK stub to support `Map` events**

Open `web/tests/map-page.test.ts`. The stub's `Map` constructor (currently lines 17-21) needs to expose an internal event channel. Modify the stub so the `Map` constructor and `Event` object look like:

```javascript
        Map: function (el, opts) {
          var self = this;
          self._mapHandlers = {};
          this.fitBounds = function () {};
          this.setCenter = function (latlng) { lastSetCenter = latlng; };
          this.setZoom = function (z) { lastSetZoom = z; };
        },
        LatLng: function (lat, lng) { this.lat = lat; this.lng = lng; },
        LatLngBounds: function () { this.extend = function () {}; },
        Marker: function (opts) {
          this._id = ++markerSeq;
          if (opts && opts.icon && opts.icon.content) {
            var host = document.getElementById("naver-map");
            if (host) {
              var wrap = document.createElement("div");
              wrap.innerHTML = opts.icon.content;
              host.appendChild(wrap.firstElementChild);
            }
          }
        },
        Point: function () {},
        Event: {
          addListener: function (target, evt, handler) {
            // Markers route through markerClickHandlers as before.
            if (target && target._id !== undefined) {
              markerClickHandlers[target._id] = handler;
              return;
            }
            // Map instances store handlers per event name.
            if (target && target._mapHandlers) {
              (target._mapHandlers[evt] = target._mapHandlers[evt] || []).push(handler);
            }
          },
          once: function (target, evt, handler) {
            // For the Map instance, store as a one-shot via _mapHandlers
            // with a wrapper that clears itself on fire. The test hook
            // below calls handlers without distinguishing once vs on.
            if (target && target._mapHandlers) {
              (target._mapHandlers[evt] = target._mapHandlers[evt] || []).push(handler);
            }
          },
        },
        __test: {
          fireMarkerClick: function (i) { markerClickHandlers[i] && markerClickHandlers[i](); },
          lastSetCenter: function () { return lastSetCenter; },
          lastSetZoom: function () { return lastSetZoom; },
          fireAuthFailure: function () {
            if (typeof window.navermap_authFailure === "function") {
              window.navermap_authFailure();
            }
          },
          fireMapEvent: function (evt) {
            // Walk every Marker/Map we know about. We don't have a
            // direct handle to the Map instance from outside, so we
            // dispatch via a captured reference exposed by client/map.js
            // through document. For the test we read the handlers from
            // a global the stub exposes.
            var maps = window.__testMaps || [];
            maps.forEach(function (m) {
              (m._mapHandlers[evt] || []).forEach(function (h) { h(); });
            });
          },
        },
```

Also, **inside the `Map` constructor**, register the instance into `window.__testMaps` so the test hook above can dispatch into it. Change the constructor body to:

```javascript
        Map: function (el, opts) {
          var self = this;
          self._mapHandlers = {};
          this.fitBounds = function () {};
          this.setCenter = function (latlng) { lastSetCenter = latlng; };
          this.setZoom = function (z) { lastSetZoom = z; };
          window.__testMaps = window.__testMaps || [];
          window.__testMaps.push(self);
        },
```

- [ ] **Step 2: Write the failing test**

Add this test to `web/tests/map-page.test.ts`, immediately after the `navermap_authFailure` test from Task 1:

```typescript
  test("map-loading class is added during init and removed after tilesloaded", async ({ page }) => {
    await page.goto("/map/");
    // After init, .map-loading should be present (tilesloaded hasn't fired).
    await expect(page.locator("#naver-map")).toHaveClass(/map-loading/);
    // Fire the tilesloaded event on every test Map instance.
    await page.evaluate(() => (window as any).naver.maps.__test.fireMapEvent("tilesloaded"));
    // The class should be gone.
    await expect(page.locator("#naver-map")).not.toHaveClass(/map-loading/);
  });
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd web && npx playwright test tests/map-page.test.ts -g "map-loading class" --reporter=list`

Expected: FAIL — the first `toHaveClass(/map-loading/)` assertion fails because `client/map.js` doesn't add the class yet.

- [ ] **Step 4: Modify `initMap()` to toggle `.map-loading`**

Open `web/client/map.js`. The current `initMap()` body starts at line 78. We're modifying three regions: the SDK-missing check stays exactly as-is, but immediately after the `map = new naver.maps.Map(...)` block (currently lines 93-101) we add the loading-class lifecycle.

Locate this block (lines 93-101):

```javascript
    map = new naver.maps.Map(container, {
      center: new naver.maps.LatLng(valid[0].lat, valid[0].lng),
      zoom: 11,
      // Tone down the Naver default UI to match the editorial aesthetic.
      mapTypeControl: false,
      scaleControl: false,
      logoControl: true,
      mapDataControl: false,
    });
```

Immediately after the closing `});` of `new naver.maps.Map(...)`, insert these lines (before the existing `const bounds = new naver.maps.LatLngBounds();` line):

```javascript

    // Hide tile imagery until the SDK confirms tiles are painted.
    // Eliminates the brief flash where Naver's auth-fail placeholder
    // PNG renders before real tiles arrive on cold-cache loads.
    container.classList.add("map-loading");
    const clearLoading = () => container.classList.remove("map-loading");
    naver.maps.Event.once(map, "tilesloaded", clearLoading);
    // Safety net: if tilesloaded never fires (ad-blocker, network
    // failure on the tile CDN, etc.), reveal whatever is there after
    // 2 seconds rather than leaving the map looking frozen.
    setTimeout(clearLoading, 2000);

```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd web && npx playwright test tests/map-page.test.ts -g "map-loading class" --reporter=list`

Expected: PASS.

- [ ] **Step 6: Run the full map-page test file to confirm no regressions**

Run: `cd web && npx playwright test tests/map-page.test.ts --reporter=list`

Expected: all 9 tests pass.

- [ ] **Step 7: Commit**

```bash
git add web/client/map.js web/tests/map-page.test.ts
git commit -m "feat(map): hide tiles until tilesloaded to prevent auth-fail flash"
```

---

## Task 4: Full website test suite + manual verification

**Files:** none modified; verification only.

- [ ] **Step 1: Run the full web test suite**

Run: `cd web && npm test`

Expected: all tests pass. This runs status/slug/fetch/seed/multipage/showcase/a11y/refresh-seed Node tests, the Eleventy build, and the full Playwright suite. Total runtime is typically 60-120s.

If any non-map test fails, investigate before continuing — it's unlikely to be related to this change, but verify.

- [ ] **Step 2: Manual visual check on the dev server**

Start the dev server:

```bash
cd web && npm run dev
```

Open the URL it prints (typically `http://localhost:8080/map/`). Hard-refresh (Cmd-Shift-R) several times. On each refresh:

- The Naver auth-fail PNG must never be visible.
- The map either renders real tiles or shows the branded "지도를 불러올 수 없습니다." fallback (this latter case only happens if localhost isn't on the Naver allowlist for this client ID — note which it is).
- Sidebar pins still render; clicking a focus button still pans the map.

Stop the dev server (Ctrl-C) when done.

- [ ] **Step 3: Final summary commit (if any incidental fixes were made)**

If steps 1-2 surfaced any incidental issues that needed fixing, commit them now with a clear message. Otherwise skip this step — no empty commit.

---

## Self-Review

**Spec coverage:**
- "Eliminate the visible flash of Naver's auth-fail PNG" → Task 3 (`.map-loading` lifecycle).
- "Show branded fallback for actual auth failures" → Task 1 (`navermap_authFailure` callback).
- "No new dependencies, no build-step changes, no data changes" → confirmed: only HTML/JS/CSS/test edits.
- Three terminal states (auth failure / normal load / tiles blocked) → covered by Tasks 1+3 plus the 2s safety timeout in Task 3 step 4.
- Two new Playwright tests → Task 1 step 2 (auth-failure) + Task 3 step 2 (loading class).
- Existing "missing SDK" test stays unchanged → verified by Task 1 step 6 and Task 3 step 6 ("all tests pass").

**Placeholder scan:** None. Every code block is complete; every command has an expected outcome.

**Type/method consistency:**
- `navermap_authFailure` spelled identically in HTML, test stub, and test code.
- `.map-loading`, `.map-failed`, `#naver-map` consistent across HTML, JS, CSS, tests.
- `clearLoading` defined once in Task 3 step 4 and referenced by both `Event.once` and `setTimeout` in the same step.
- `window.__testMaps` defined in Task 3 step 1's `Map` constructor and read in the same step's `fireMapEvent`.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-13-map-naver-auth-failure-fix.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
