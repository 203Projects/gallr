import { test, expect } from "@playwright/test";

// The /map/ page uses the Naver Maps JS SDK, which authenticates by
// HTTP-Referer in the Naver Cloud console. Tests can't hit a real
// allowlisted origin, so we stub the SDK with just enough surface for
// our client/map.js to call: Map, LatLng, LatLngBounds, Marker, Point,
// Event.addListener.

const sdkStub = `
  (function () {
    var markerClickHandlers = {};
    var markerSeq = 0;
    var lastSetCenter = null;
    var lastSetZoom = null;
    window.naver = {
      maps: {
        Map: function (el, opts) {
          var self = this;
          self._mapHandlers = {};
          // Match the production SDK, which writes position: relative
          // inline on the host element during initialization.
          el.style.position = "relative";
          this.fitBounds = function () {};
          this.setCenter = function (latlng) { lastSetCenter = latlng; };
          this.setZoom = function (z) { lastSetZoom = z; };
          window.__testMaps = window.__testMaps || [];
          window.__testMaps.push(self);
        },
        LatLng: function (lat, lng) { this.lat = lat; this.lng = lng; },
        LatLngBounds: function () { this.extend = function () {}; },
        Marker: function (opts) {
          this._id = ++markerSeq;
          if (opts && opts.icon && opts.icon.content) {
            // Render the marker's HTML into the page so map-pin selectors
            // can find them. Anchor them to the map container so they're
            // queryable by tests.
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
            // Match the production semantic: fire once, then remove.
            // Avoids stub-only false passes where firing the same event
            // twice would invoke the handler twice.
            if (target && target._mapHandlers) {
              var arr = (target._mapHandlers[evt] = target._mapHandlers[evt] || []);
              var wrapped = function () {
                handler();
                var i = arr.indexOf(wrapped);
                if (i !== -1) arr.splice(i, 1);
              };
              arr.push(wrapped);
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
            var maps = window.__testMaps || [];
            maps.forEach(function (m) {
              (m._mapHandlers[evt] || []).forEach(function (h) { h(); });
            });
          },
        },
      },
    };
  })();
`;

test.describe("Map page", () => {
  test.beforeEach(async ({ page }) => {
    await page.route("**/maps.js**", (route) => {
      route.fulfill({
        status: 200,
        contentType: "application/javascript",
        body: sdkStub,
      });
    });
  });

  test("sidebar lists all non-closed fixture exhibitions", async ({ page }) => {
    await page.goto("/map/");
    // Fixture has 4 rows: fx-001 (current), fx-002 (closing_soon),
    // fx-003 (opening_soon), fx-004 (closed). mapData filters closed,
    // so 3 should render in the sidebar.
    await expect(page.locator(".map-page__list-item")).toHaveCount(3);
  });

  test("each sidebar row carries data-exhibition-id and data-slug", async ({ page }) => {
    await page.goto("/map/");
    const first = page.locator(".map-page__list-item").first();
    await expect(first).toHaveAttribute("data-exhibition-id", /fx-/);
    await expect(first).toHaveAttribute("data-slug", /.+/);
  });

  test("JSON island is parseable and matches the sidebar count", async ({ page }) => {
    await page.goto("/map/");
    const islandCount = await page.evaluate(() => {
      const el = document.getElementById("exhibitions-data");
      return JSON.parse(el!.textContent!).length;
    });
    expect(islandCount).toBe(3);
  });

  test("map.js drops one pin per island row", async ({ page }) => {
    await page.goto("/map/");
    // The stub renders each Marker's HTML into #naver-map. With 3 valid
    // rows we expect 3 pins.
    await expect(page.locator("#naver-map .map-pin")).toHaveCount(3);
  });

  test("map keeps a visible height after the SDK rewrites its positioning", async ({ page }) => {
    await page.goto("/map/");
    const mapBox = await page.locator("#naver-map").boundingBox();
    expect(mapBox).not.toBeNull();
    expect(mapBox!.height).toBeGreaterThan(0);
  });

  test("map host opts out of browser scroll anchoring during SDK zooms", async ({ page }) => {
    await page.goto("/map/");
    await expect(page.locator("#naver-map")).toHaveCSS("overflow-anchor", "none");
  });

  test("clicking a pin activates the matching sidebar row", async ({ page }) => {
    await page.goto("/map/");
    // Click the 2nd marker via the test hook the stub exposes.
    await page.evaluate(() => (window as any).naver.maps.__test.fireMarkerClick(2));
    // The 2nd row should now have .is-active and its pin should too.
    const activeItems = page.locator(".map-page__list-item.is-active");
    await expect(activeItems).toHaveCount(1);
    await expect(page.locator(".map-pin.is-active")).toHaveCount(1);
  });

  test("clicking a pin shows exhibition details and a full-page link", async ({ page }) => {
    await page.goto("/map/");
    const selected = await page.evaluate(() => {
      const el = document.getElementById("exhibitions-data");
      return JSON.parse(el!.textContent!)[1];
    });

    await page.evaluate(() => (window as any).naver.maps.__test.fireMarkerClick(2));

    const detail = page.locator("[data-map-detail]");
    await expect(detail).toBeVisible();
    await expect(detail.locator("[data-map-detail-title]")).toContainText(selected.nameKo);
    await expect(detail.locator("[data-map-detail-title]")).toContainText(selected.nameEn);
    await expect(detail.locator("[data-map-detail-venue]")).toContainText(selected.venueKo);
    await expect(detail.locator("[data-map-detail-venue]")).toContainText(selected.venueEn);
    await expect(detail.locator("[data-map-detail-dates]")).toHaveText(
      `${selected.openingDate} — ${selected.closingDate}`
    );
    await expect(detail.locator("[data-map-detail-address]")).toHaveText(
      selected.addressKo
    );
    await expect(detail.locator("[data-map-detail-status]")).toHaveAttribute(
      "data-status",
      selected.status
    );
    await expect(detail.locator("[data-map-detail-link]")).toHaveAttribute(
      "href",
      `/exhibitions/${selected.slug}/`
    );
  });

  test("map detail panel can be closed", async ({ page }) => {
    await page.goto("/map/");
    await page.evaluate(() => (window as any).naver.maps.__test.fireMarkerClick(1));

    const detail = page.locator("[data-map-detail]");
    await expect(detail).toBeVisible();
    await detail.locator("[data-map-detail-close]").click();
    await expect(detail).toBeHidden();
    await expect(
      page.locator(".map-page__list-item.is-active [data-focus-id]")
    ).toBeFocused();
  });

  test("focus button pans the map and activates pin + row without navigating", async ({ page }) => {
    await page.goto("/map/");
    const urlBefore = page.url();
    const focusButton = page.locator(".map-page__list-focus").nth(1);
    await focusButton.click();

    // URL didn't change — we panned, we didn't navigate.
    expect(page.url()).toBe(urlBefore);
    // setCenter was called with a LatLng instance carrying real coords.
    const setCenterCall = await page.evaluate(() => {
      const latlng = (window as any).naver.maps.__test.lastSetCenter();
      return latlng ? { lat: latlng.lat, lng: latlng.lng } : null;
    });
    expect(setCenterCall).not.toBeNull();
    expect(typeof setCenterCall!.lat).toBe("number");
    expect(typeof setCenterCall!.lng).toBe("number");
    // Pin + sidebar row both pick up .is-active.
    await expect(page.locator(".map-pin.is-active")).toHaveCount(1);
    await expect(page.locator(".map-page__list-item.is-active")).toHaveCount(1);
    await expect(page.locator("[data-map-detail]")).toBeVisible();
  });

  test("mobile shows the map above the exhibition list and keeps details in view", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/map/");
    await page.evaluate(() => (window as any).naver.maps.__test.fireMarkerClick(1));

    const mapBox = await page.locator(".map-page__map-shell").boundingBox();
    const sidebarBox = await page.locator(".map-page__sidebar").boundingBox();
    const detailBox = await page.locator("[data-map-detail]").boundingBox();

    expect(mapBox).not.toBeNull();
    expect(sidebarBox).not.toBeNull();
    expect(detailBox).not.toBeNull();
    expect(mapBox!.y).toBeLessThan(sidebarBox!.y);
    expect(detailBox!.x).toBeGreaterThanOrEqual(0);
    expect(detailBox!.x + detailBox!.width).toBeLessThanOrEqual(390);
  });

  test("missing SDK marks the map container .map-failed", async ({ page }) => {
    await page.route("**/maps.js**", (route) => {
      // Empty body — window.naver never gets set.
      route.fulfill({ status: 200, contentType: "application/javascript", body: "" });
    });
    await page.goto("/map/");
    await expect(page.locator("#naver-map")).toHaveClass(/map-failed/);
  });

  test("focus action falls back to an external Naver Map when the SDK is unavailable", async ({ page }) => {
    await page.route("**/maps.js**", (route) => {
      route.fulfill({ status: 200, contentType: "application/javascript", body: "" });
    });
    await page.route("https://map.naver.com/**", (route) => {
      route.fulfill({ status: 200, contentType: "text/html", body: "<title>Naver Map</title>" });
    });
    await page.goto("/map/");

    const focusAction = page.locator(".map-page__list-focus").first();
    await expect(focusAction).toHaveAttribute(
      "href",
      /^https:\/\/map\.naver\.com\/v5\/search\/.+/
    );

    const popupPromise = page.waitForEvent("popup");
    await focusAction.click();
    const popup = await popupPromise;
    await expect(popup).toHaveURL(
      /^https:\/\/map\.naver\.com\/(?:v5|p)\/search\/.+/
    );
    await popup.close();
  });

  test("navermap_authFailure adds .map-failed to the map container", async ({ page }) => {
    await page.route("https://map.naver.com/**", (route) => {
      route.fulfill({ status: 200, contentType: "text/html", body: "<title>Naver Map</title>" });
    });
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

    const popupPromise = page.waitForEvent("popup");
    await page.locator(".map-page__list-focus").first().click();
    const popup = await popupPromise;
    await expect(popup).toHaveURL(
      /^https:\/\/map\.naver\.com\/(?:v5|p)\/search\/.+/
    );
    await popup.close();
  });

  test("map-loading class is added during init and removed after tilesloaded", async ({ page }) => {
    await page.goto("/map/");
    await expect(page.locator("#naver-map")).toHaveClass(/map-loading/);
    await page.evaluate(() => (window as any).naver.maps.__test.fireMapEvent("tilesloaded"));
    await expect(page.locator("#naver-map")).not.toHaveClass(/map-loading/);
  });
});
