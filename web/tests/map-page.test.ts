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
    window.naver = {
      maps: {
        Map: function (el, opts) {
          this.fitBounds = function () {};
          this.setCenter = function () {};
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
          addListener: function (marker, evt, handler) {
            markerClickHandlers[marker._id] = handler;
          },
        },
        __test: { fireMarkerClick: function (i) { markerClickHandlers[i] && markerClickHandlers[i](); } },
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

  test("clicking a pin activates the matching sidebar row", async ({ page }) => {
    await page.goto("/map/");
    // Click the 2nd marker via the test hook the stub exposes.
    await page.evaluate(() => (window as any).naver.maps.__test.fireMarkerClick(2));
    // The 2nd row should now have .is-active and its pin should too.
    const activeItems = page.locator(".map-page__list-item.is-active");
    await expect(activeItems).toHaveCount(1);
    await expect(page.locator(".map-pin.is-active")).toHaveCount(1);
  });

  test("missing SDK marks the map container .map-failed", async ({ page }) => {
    await page.route("**/maps.js**", (route) => {
      // Empty body — window.naver never gets set.
      route.fulfill({ status: 200, contentType: "application/javascript", body: "" });
    });
    await page.goto("/map/");
    await expect(page.locator("#naver-map")).toHaveClass(/map-failed/);
  });
});
