"use strict";

// The /route/{id} function (spec 089 contracts/route-page.md). GET renders the page; POST to /route/{id}/events
// records one of two page events. Logs carry status, read latency and outcome only: never the route id, its
// name or any content.

const { buildRouteModel, renderRoutePage, renderNotFoundPage, renderUnavailablePage } = require("./route-page.js");
const { seoulDate } = require("./route-verdict.js");

const ROUTE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PAGE_EVENTS = new Set(["route_page_opened", "route_page_started"]);
const MAX_EVENT_BYTES = 256;
const DEFAULT_TIMEOUT_MS = 3000;
// Link-preview fetchers open the page without a person behind them, so they are never counted.
const PREVIEW_AGENTS =
  /facebookexternalhit|facebot|twitterbot|slackbot|discordbot|telegrambot|whatsapp|kakaotalk-scrap|linkedinbot|skypeuripreview|embedly|pinterest|googlebot|bingbot|yeti|daum/i;

const CACHE_PAGE = "public, s-maxage=60, stale-while-revalidate=60";
const CACHE_MISSING = "s-maxage=60";
const CACHE_NONE = "no-store";

function createRouteHandler({
  data,
  now = () => new Date(),
  log = (entry) => console.log(JSON.stringify({ component: "route_page", ...entry })),
  timeoutMs = DEFAULT_TIMEOUT_MS,
}) {
  return async function routeHandler(req, res) {
    const url = new URL(req.url || "/", "https://gallrmap.com");
    const id = url.searchParams.get("id") || "";
    if (req.method === "POST" && url.searchParams.has("events")) return handleEvent(req, res, id);
    if (req.method !== "GET" && req.method !== "HEAD") return send(res, 405, CACHE_NONE, "text/plain; charset=utf-8", "");
    return handlePage(req, res, id, url);
  };

  async function handlePage(req, res, id, url) {
    const lang = pageLanguage(url, req.headers || {});
    const started = Date.now();
    const finish = (status, outcome) => log({ status, latencyMs: Date.now() - started, outcome });

    if (!ROUTE_ID.test(id)) {
      send(res, 404, CACHE_MISSING, "text/html; charset=utf-8", renderNotFoundPage(lang));
      return finish(404, "not_found");
    }
    let loaded;
    try {
      loaded = await withTimeout(readRoute(id), timeoutMs);
    } catch {
      const retryUrl = `/route/${id}${url.search}`;
      send(res, 503, CACHE_NONE, "text/html; charset=utf-8", renderUnavailablePage(lang, retryUrl));
      return finish(503, "read_failed");
    }
    if (!loaded) {
      send(res, 404, CACHE_MISSING, "text/html; charset=utf-8", renderNotFoundPage(lang));
      return finish(404, "not_found");
    }
    const model = buildRouteModel({ ...loaded, today: seoulDate(now()), lang });
    const html = renderRoutePage(model, { routeId: id, shared: url.searchParams.get("s") === "share" });
    send(res, 200, CACHE_PAGE, "text/html; charset=utf-8", html);
    return finish(200, "rendered");
  }

  async function readRoute(id) {
    const route = await data.loadRoute(id);
    if (!route) return null;
    const ids = [...route.stops].sort((a, b) => a.position - b.position).map((stop) => stop.exhibition_id);
    const catalogue = await data.loadCatalogue(ids);
    return { route, catalogue, authorName: authorNameOf(route) };
  }

  // A write that fails or times out is answered the same way: the page never waits on a count.
  async function handleEvent(req, res, id) {
    const event = readEvent(req.body);
    if (!ROUTE_ID.test(id) || !event) return send(res, 400, CACHE_NONE, "text/plain; charset=utf-8", "");
    const agent = (req.headers && req.headers["user-agent"]) || "";
    if (!PREVIEW_AGENTS.test(agent)) {
      try {
        await data.recordEvent(id, event.event, event.shared);
      } catch {
        log({ status: 204, latencyMs: 0, outcome: "event_failed" });
      }
    }
    return send(res, 204, CACHE_NONE, null, "");
  }
}

/** The author's display name from the route payload, or null when the author has not set one. */
function authorNameOf(route) {
  const name = route.author_display_name;
  return typeof name === "string" && name.trim() ? name.trim() : null;
}

/** `{ event, shared }` when the body is a small JSON object naming a page event; otherwise null. */
function readEvent(body) {
  let raw = body;
  if (raw && typeof raw === "object" && !Buffer.isBuffer(raw)) raw = JSON.stringify(raw);
  if (Buffer.isBuffer(raw)) raw = raw.toString("utf8");
  if (typeof raw !== "string" || Buffer.byteLength(raw, "utf8") > MAX_EVENT_BYTES) return null;
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!parsed || !PAGE_EVENTS.has(parsed.event) || typeof parsed.shared !== "boolean") return null;
  return { event: parsed.event, shared: parsed.shared };
}

function pageLanguage(url, headers) {
  const requested = url.searchParams.get("lang");
  if (requested === "en" || requested === "ko") return requested;
  const accept = String(headers["accept-language"] || "").trim().toLowerCase();
  return accept.startsWith("en") ? "en" : "ko";
}

function withTimeout(promise, ms) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error("timeout")), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

function send(res, status, cacheControl, contentType, body) {
  res.statusCode = status;
  res.setHeader("Cache-Control", cacheControl);
  // The page is cached at the CDN and negotiates its language from Accept-Language.
  if (cacheControl !== CACHE_NONE) res.setHeader("Vary", "Accept-Language");
  if (contentType) res.setHeader("Content-Type", contentType);
  res.end(body);
}

module.exports = { createRouteHandler };
