// Spec 089 E-D5: the page's two counted events, posted by the page script and never by the HTML fetch.
// Run: node tests/route-events.test.js (also runs as part of `npm test`)

const assert = require("assert").strict;
const { createRouteHandler } = require("../api/_lib/route-handler.js");
const { createRouteData } = require("../api/_lib/route-data.js");

const ROUTE_ID = "6f1c2a7e-8d34-4b8e-9a51-2f0c7d1e9b10";

function stubData() {
  const recorded = [];
  return {
    recorded,
    data: {
      async loadRoute() {
        throw new Error("the event endpoint must not read the route");
      },
      async recordEvent(id, event, shared) {
        recorded.push({ id, event, shared });
      },
    },
  };
}

async function post(handler, body, headers = {}) {
  const res = {
    statusCode: 200,
    headers: {},
    body: "",
    setHeader(name, value) {
      this.headers[name.toLowerCase()] = value;
    },
    end(text = "") {
      this.body = text;
    },
  };
  const req = {
    method: "POST",
    url: `/api/route?id=${ROUTE_ID}&events=1`,
    headers: { "content-type": "application/json", "user-agent": "Mozilla/5.0 (iPhone)", ...headers },
    body,
  };
  await handler(req, res);
  return res;
}

// A run that stalls on a pending promise must not pass by exiting quietly.
process.exitCode = 1;

(async () => {
  // Accepted events are recorded and answered with 204 no-store.
  {
    const stub = stubData();
    const handler = createRouteHandler({ data: stub.data, log: () => {} });
    const opened = await post(handler, JSON.stringify({ event: "route_page_opened", shared: true }));
    const started = await post(handler, { event: "route_page_started", shared: false });
    assert.equal(opened.statusCode, 204);
    assert.equal(opened.headers["cache-control"], "no-store");
    assert.equal(started.statusCode, 204);
    assert.deepEqual(stub.recorded, [
      { id: ROUTE_ID, event: "route_page_opened", shared: true },
      { id: ROUTE_ID, event: "route_page_started", shared: false },
    ]);
  }

  // Anything else is refused without recording.
  {
    const stub = stubData();
    const handler = createRouteHandler({ data: stub.data, log: () => {} });
    const cases = [
      JSON.stringify({ event: "route_saved", shared: true }),
      JSON.stringify({ event: "route_page_opened", shared: "yes" }),
      JSON.stringify({ event: "route_page_opened" }),
      JSON.stringify({ event: "route_page_opened", shared: true, extra: "x".repeat(300) }),
      "not json",
    ];
    for (const body of cases) {
      const res = await post(handler, body);
      assert.equal(res.statusCode, 400, `refused: ${body.slice(0, 40)}`);
      assert.equal(res.headers["cache-control"], "no-store");
    }
    assert.equal(stub.recorded.length, 0);
  }

  // Link-preview fetchers are ignored but answered the same way.
  {
    const stub = stubData();
    const handler = createRouteHandler({ data: stub.data, log: () => {} });
    for (const agent of ["facebookexternalhit/1.1", "Twitterbot/1.0", "Slackbot-LinkExpanding 1.0", "kakaotalk-scrap/1.0"]) {
      const res = await post(handler, JSON.stringify({ event: "route_page_opened", shared: true }), { "user-agent": agent });
      assert.equal(res.statusCode, 204);
    }
    assert.equal(stub.recorded.length, 0);
  }

  // The data layer calls the database function with the publishable key only.
  {
    const requests = [];
    const data = createRouteData({
      supabaseUrl: "https://project.supabase.co/",
      apiKey: "sb_publishable_test",
      fetch: async (url, init) => {
        requests.push({ url, init });
        return { ok: true, status: 204, json: async () => null, text: async () => "" };
      },
    });
    await data.recordEvent(ROUTE_ID, "route_page_started", true);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, "https://project.supabase.co/rest/v1/rpc/record_route_page_event");
    assert.equal(requests[0].init.method, "POST");
    assert.equal(requests[0].init.headers.apikey, "sb_publishable_test");
    assert.deepEqual(JSON.parse(requests[0].init.body), {
      p_route_id: ROUTE_ID,
      p_event: "route_page_started",
      p_shared: true,
    });
    assert.ok(requests[0].init.signal instanceof AbortSignal, "the write is bounded by a timeout signal");
    assert.throws(() => createRouteData({ supabaseUrl: "https://x", apiKey: "sb_secret_x", fetch }));
  }

  // A write that never answers is cut off by its own timeout, and the page is still answered with 204.
  {
    const logs = [];
    const data = createRouteData({
      supabaseUrl: "https://project.supabase.co",
      apiKey: "sb_publishable_test",
      eventTimeoutMs: 10,
      fetch: (url, init) =>
        new Promise((resolve, reject) => {
          // A real socket keeps the process alive until the signal fires; this stand-in must do the same.
          const pending = setTimeout(() => reject(new Error("the timeout signal never fired")), 1000);
          init.signal.addEventListener("abort", () => {
            clearTimeout(pending);
            reject(init.signal.reason);
          });
        }),
    });
    await assert.rejects(() => data.recordEvent(ROUTE_ID, "route_page_opened", true), { name: "TimeoutError" });
    const handler = createRouteHandler({ data, log: (entry) => logs.push(entry) });
    const res = await post(handler, JSON.stringify({ event: "route_page_opened", shared: true }));
    assert.equal(res.statusCode, 204);
    assert.equal(res.headers["cache-control"], "no-store");
    assert.deepEqual(logs, [{ status: 204, latencyMs: 0, outcome: "event_failed" }]);
  }

  // A route is read by its id through the database function; the route tables are never listed (090 eng D4).
  // The payload names the author; no profile is read.
  {
    const requests = [];
    const route = {
      id: ROUTE_ID,
      name: "산책",
      author_display_name: "작가",
      is_mine: false,
      updated_at: "2026-10-08T00:00:00Z",
      stops: [],
    };
    const responses = [route, null];
    const data = createRouteData({
      supabaseUrl: "https://project.supabase.co",
      apiKey: "sb_publishable_test",
      fetch: async (url, init) => {
        requests.push({ url, init });
        const body = responses.shift();
        return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) };
      },
    });
    assert.deepEqual(await data.loadRoute(ROUTE_ID), route);
    assert.equal(await data.loadRoute(ROUTE_ID), null);
    assert.equal(requests[0].url, "https://project.supabase.co/rest/v1/rpc/get_published_route");
    assert.equal(requests[0].init.method, "POST");
    assert.equal(requests[0].init.headers.apikey, "sb_publishable_test");
    assert.deepEqual(JSON.parse(requests[0].init.body), { p_id: ROUTE_ID });
    assert.ok(requests[0].init.signal instanceof AbortSignal, "the read is bounded by a timeout signal");
    assert.ok(requests.every((request) => !request.url.includes("/rest/v1/personal_routes")));
    assert.ok(requests.every((request) => !request.url.includes("/rest/v1/profiles")));
    assert.equal(typeof data.loadAuthorName, "undefined", "there is no profile read to leak an account id through");

    const failing = createRouteData({
      supabaseUrl: "https://project.supabase.co",
      apiKey: "sb_publishable_test",
      fetch: async () => ({ ok: false, status: 503, json: async () => null, text: async () => "" }),
    });
    await assert.rejects(() => failing.loadRoute(ROUTE_ID), /read failed with 503/);
  }

  console.log("route-events: ok");
  process.exitCode = 0;
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
