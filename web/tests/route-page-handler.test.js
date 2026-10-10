// Spec 089 US3: the server-rendered route page (E-D13, E-D19, DR-D12, DR-D20, DR-D28, RR2).
// Run: node tests/route-page-handler.test.js (also runs as part of `npm test`)

const assert = require("assert").strict;
const { createRouteHandler } = require("../api/_lib/route-handler.js");
const { buildRouteModel, durationLabel, renderRoutePage } = require("../api/_lib/route-page.js");

const ROUTE_ID = "6f1c2a7e-8d34-4b8e-9a51-2f0c7d1e9b10";
const THURSDAY_NOON_KST = new Date("2026-10-08T03:00:00Z");

function routeRow(overrides = {}) {
  return {
    id: ROUTE_ID,
    name: "토요일 한남 산책",
    owner: "owner-1",
    updated_at: "2026-10-08T01:00:00Z",
    stops: [
      snapshot(0, "e-hannam", "용산구", "서울"),
      snapshot(1, "e-ansan", "단원구", "안산시"),
      snapshot(2, "e-gone", "종로구", "서울"),
    ],
    ...overrides,
  };
}

function snapshot(position, id, region, city) {
  return {
    position,
    exhibition_id: id,
    name_ko: `전시 ${position + 1}`,
    name_en: `Show ${position + 1}`,
    venue_name_ko: `공간 ${position + 1}`,
    venue_name_en: `Venue ${position + 1}`,
    latitude: 37.53 + position / 100,
    longitude: 126.99,
    region_ko: region,
    region_en: `${region} EN`,
    city_ko: city,
  };
}

function catalogueRow(id, overrides = {}) {
  return {
    id,
    name_ko: `현재 ${id}`,
    name_en: `Current ${id}`,
    venue_name_ko: `공간 ${id}`,
    venue_name_en: `Venue ${id}`,
    city_en: id === "e-ansan" ? "Ansan" : "Seoul",
    opening_date: "2026-09-01",
    closing_date: "2026-12-31",
    hours: "10am - 6pm\nTuesday - Saturday",
    address_ko: `서울 용산구 ${id} 1`,
    cover_image_url: null,
    ...overrides,
  };
}

function stubData(options = {}) {
  const calls = { route: 0, catalogue: [], author: 0 };
  const data = {
    async loadRoute(id) {
      calls.route += 1;
      if (options.routeError) throw new Error("boom");
      if (options.delayMs) await new Promise((resolve) => setTimeout(resolve, options.delayMs));
      return options.route === undefined ? routeRow() : options.route;
    },
    async loadCatalogue(ids) {
      calls.catalogue.push(ids);
      const rows = options.catalogue || [catalogueRow("e-hannam"), catalogueRow("e-ansan")];
      return rows.filter((row) => ids.includes(row.id));
    },
    async loadAuthorName() {
      calls.author += 1;
      return options.author === undefined ? "hanshin" : options.author;
    },
  };
  return { data, calls };
}

function request(path, headers = {}) {
  return { method: "GET", url: path, headers };
}

async function call(handler, req) {
  const res = {
    statusCode: 200,
    headers: {},
    body: "",
    setHeader(name, value) {
      this.headers[name.toLowerCase()] = value;
    },
    end(body = "") {
      this.body = body;
    },
  };
  await handler(req, res);
  return res;
}

function handlerWith(stub, overrides = {}) {
  const logs = [];
  const handler = createRouteHandler({
    data: stub.data,
    now: () => THURSDAY_NOON_KST,
    log: (entry) => logs.push(entry),
    ...overrides,
  });
  return { handler, logs };
}

(async () => {
  // --- 200: the full page ---
  {
    const stub = stubData();
    const { handler, logs } = handlerWith(stub);
    const res = await call(handler, request(`/api/route?id=${ROUTE_ID}&s=share&v=1`));

    assert.equal(res.statusCode, 200);
    assert.equal(res.headers["content-type"], "text/html; charset=utf-8");
    assert.equal(res.headers["cache-control"], "public, s-maxage=60, stale-while-revalidate=60");
    assert.equal(stub.calls.route, 1, "exactly one route request with its stops (E-D19)");
    assert.deepEqual(stub.calls.catalogue, [["e-hannam", "e-ansan", "e-gone"]]);

    const html = res.body;
    assert.match(html, /<html lang="ko">/);
    assert.match(html, /<h1[^>]*>토요일 한남 산책<\/h1>/);
    assert.match(html, /전시 동선/);
    assert.match(html, /<h2 class="route-label">전시<\/h2>/);
    assert.doesNotMatch(html, /정류장/, "a stop is a place on the walk, not a transit stop");
    assert.match(html, /오늘\(목\) · 3곳 중 2곳 열림 · 1곳 볼 수 없음/);
    assert.match(html, /hanshin 님 · 3곳 · 약 /);
    // District labels: Seoul shows the district, elsewhere the city comes first; the unavailable stop renders
    // from its snapshot.
    assert.match(html, />용산구</);
    assert.match(html, />안산시 단원구</);
    assert.match(html, />전시 3</);
    assert.match(html, /! 더 이상 볼 수 없는 전시/);
    // Directions only for listed stops, to Naver Map by Korean address (DR-D28).
    const links = [...html.matchAll(/href="(https:\/\/map\.naver\.com\/v5\/search\/[^"]+)"/g)].map((m) => m[1]);
    assert.ok(links.includes(`https://map.naver.com/v5/search/${encodeURIComponent("서울 용산구 e-hannam 1")}`));
    assert.ok(links.includes(`https://map.naver.com/v5/search/${encodeURIComponent("서울 용산구 e-ansan 1")}`));
    assert.ok(!links.some((link) => link.includes("e-gone")), "no directions to an unlisted stop");
    // The primary action points at the first open stop.
    assert.match(html, /첫 전시 길찾기/);
    // Current catalogue names win over the snapshot for listed stops.
    assert.match(html, />현재 e-hannam</);
    // Open Graph (DR-D20): no listed cover, so no image is claimed.
    assert.match(html, /<meta property="og:title" content="토요일 한남 산책 · gallr 전시 동선"/);
    assert.match(html, /<meta property="og:description" content="3곳 · 약 [^"]+ · 현재 e-hannam, 현재 e-ansan…"/);
    assert.match(html, /<meta property="og:site_name" content="gallr"/);
    assert.doesNotMatch(html, /og:image/);
    // Logs carry status, latency and outcome only: never the id, name or content.
    assert.equal(logs.length, 1);
    assert.deepEqual(Object.keys(logs[0]).sort(), ["latencyMs", "outcome", "status"]);
    assert.equal(logs[0].status, 200);
    assert.ok(!JSON.stringify(logs).includes(ROUTE_ID));
  }

  // --- walking times read like the app's: hours past sixty minutes ---
  assert.equal(durationLabel(9, "ko"), "약 9분");
  assert.equal(durationLabel(94, "ko"), "약 1시간 34분");
  assert.equal(durationLabel(120, "ko"), "약 2시간");
  assert.equal(durationLabel(523, "en"), "~8 HR 43 MIN");
  assert.equal(durationLabel(45, "en"), "~45 MIN");

  // --- the drawing stays legible when stops sit almost on top of each other ---
  {
    const near = (position, latitude, region) => ({ ...snapshot(position, `e-${position}`, region, "서울"), latitude, longitude: 126.98 });
    const route = routeRow({ stops: [near(0, 37.53, "용산구"), near(1, 37.7, "종로구"), near(2, 37.7001, "종로구")] });
    const model = buildRouteModel({ route, catalogue: [], authorName: null, today: "2026-10-08", lang: "ko" });
    const html = renderRoutePage(model, { routeId: ROUTE_ID, shared: false });
    const centers = [...html.matchAll(/<circle cx="([\d.]+)" cy="([\d.]+)"/g)].map((m) => [Number(m[1]), Number(m[2])]);
    assert.equal(centers.length, 3);
    const [, b, c] = centers;
    assert.ok(Math.hypot(b[0] - c[0], b[1] - c[1]) >= 24, "overlapping stops are pulled apart");
    assert.equal((html.match(/class="route-svg__district"[^>]*>종로구</g) || []).length, 1, "a shared district is labelled once");
    assert.doesNotMatch(html, /<g class="route-svg__scale"[^>]*stroke=/, "the scale's text is never stroked");
  }

  // --- og:image: the first still-listed cover ---
  {
    const covers = [
      catalogueRow("e-hannam", { cover_image_url: null }),
      catalogueRow("e-ansan", { cover_image_url: "https://img.example/ansan.jpg" }),
    ];
    const { handler } = handlerWith(stubData({ catalogue: covers }));
    const res = await call(handler, request(`/api/route?id=${ROUTE_ID}`));
    assert.match(res.body, /<meta property="og:image" content="https:\/\/img\.example\/ansan\.jpg"/);
  }

  // --- the revision in the link never changes the page ---
  {
    const { handler } = handlerWith(stubData());
    const first = await call(handler, request(`/api/route?id=${ROUTE_ID}&v=1`));
    const second = await call(handler, request(`/api/route?id=${ROUTE_ID}&v=2`));
    assert.equal(first.body, second.body);
  }

  // --- English by query or Accept-Language ---
  {
    const { handler } = handlerWith(stubData());
    const byQuery = await call(handler, request(`/api/route?id=${ROUTE_ID}&lang=en`));
    assert.match(byQuery.body, /<html lang="en">/);
    assert.match(byQuery.body, /TODAY \(THU\) · 2 OF 3 OPEN · 1 NO LONGER LISTED/);
    assert.match(byQuery.body, /DIRECTIONS TO STOP 1/);
    assert.match(byQuery.body, />Ansan 단원구 EN</);
    const byHeader = await call(handler, request(`/api/route?id=${ROUTE_ID}`, { "accept-language": "en-US,en;q=0.9" }));
    assert.match(byHeader.body, /<html lang="en">/);
    const korean = await call(handler, request(`/api/route?id=${ROUTE_ID}`, { "accept-language": "ko-KR,ko;q=0.9" }));
    assert.match(korean.body, /<html lang="ko">/);
  }

  // --- 404: missing, unpublished and revoked look the same ---
  {
    const stub = stubData({ route: null });
    const { handler, logs } = handlerWith(stub);
    const res = await call(handler, request(`/api/route?id=${ROUTE_ID}`));
    assert.equal(res.statusCode, 404);
    assert.equal(res.headers["cache-control"], "s-maxage=60");
    assert.match(res.body, /이 동선은 더 이상 볼 수 없어요/);
    assert.match(res.body, /지금 열린 전시 보기/);
    assert.match(res.body, /class="route-wordmark"/, "the message pages keep the wordmark");
    assert.match(res.body, /<meta property="og:title" content="gallr 전시 동선"/);
    assert.equal(logs[0].outcome, "not_found");
  }
  {
    // A malformed id never reaches the database.
    const stub = stubData();
    const { handler } = handlerWith(stub);
    const res = await call(handler, request("/api/route?id=not-a-uuid"));
    assert.equal(res.statusCode, 404);
    assert.equal(stub.calls.route, 0);
  }

  // --- 503: read failure or more than the timeout ---
  {
    const { handler, logs } = handlerWith(stubData({ routeError: true }));
    const res = await call(handler, request(`/api/route?id=${ROUTE_ID}`));
    assert.equal(res.statusCode, 503);
    assert.equal(res.headers["cache-control"], "no-store");
    assert.match(res.body, /잠시 후 다시 시도해 주세요/);
    assert.equal(logs[0].outcome, "read_failed");
  }
  {
    const { handler } = handlerWith(stubData({ delayMs: 200 }), { timeoutMs: 20 });
    const res = await call(handler, request(`/api/route?id=${ROUTE_ID}`));
    assert.equal(res.statusCode, 503);
    assert.equal(res.headers["cache-control"], "no-store");
  }

  console.log("route-page-handler: ok");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
