"use strict";

// HTML for the shared route page and its 404 and 503 pages (spec 089 contracts/route-page.md, DR-D3, DR-D4,
// DR-D12, DR-D14, DR-D19, DR-D20, DR-D25, DR-D27, DR-D28). Pure functions of prepared data: no I/O here.

const { parseOpeningHours } = require("./opening-hours.js");
const { routeVerdict, weekdayOf } = require("./route-verdict.js");

const NAVER_SEARCH = "https://map.naver.com/v5/search/";
const APP_STORE_URL = "https://apps.apple.com/app/gallr/id6760855059";
const PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.gallr.app";
const WALKING_CIRCUITY_MULTIPLIER = 1.25;
const WALKING_SPEED_KMH = 4.5;
const EARTH_RADIUS_KM = 6371;
const SEOUL_CITY = "서울";
const STOP_SEPARATION = 26;
const SEPARATION_ATTEMPTS = 6;
const LABEL_REPEAT_DISTANCE = 60;

const COPY = {
  ko: {
    eyebrow: "전시 동선",
    order: "방문 순서",
    stops: "전시",
    author: (name) => `${name} 님`,
    places: (count) => `${count}곳`,
    distance: (label) => `약 ${label}`,
    today: "오늘",
    directions: "길찾기",
    directionsTo: (title) => `${title} 길찾기`,
    primary: (index) => (index === 0 ? "첫 전시 길찾기" : `${index + 1}번 전시 길찾기`),
    walk: (from, distance, duration) => `${from}번에서 약 ${distance} · ${duration}`,
    installApp: "gallr 앱 설치",
    north: "북",
    drawing: (parts) => `방문 순서: ${parts.join(", ")}`,
    ogSuffix: "gallr 전시 동선",
    notFoundTitle: "이 동선은 더 이상 볼 수 없어요",
    notFoundAction: "지금 열린 전시 보기",
    unavailableTitle: "잠시 후 다시 시도해 주세요",
    retry: "다시 시도",
  },
  en: {
    eyebrow: "EXHIBITION ROUTE",
    order: "VISIT ORDER",
    stops: "STOPS",
    author: (name) => name,
    places: (count) => `${count} STOPS`,
    distance: (label) => `~${label}`,
    today: "TODAY",
    directions: "DIRECTIONS",
    directionsTo: (title) => `Directions to ${title}`,
    primary: (index) => `DIRECTIONS TO STOP ${index + 1}`,
    walk: (from, distance, duration) => `FROM STOP ${from} · ~${distance} · ${duration}`,
    installApp: "GET THE GALLR APP",
    north: "N",
    drawing: (parts) => `Visit order: ${parts.join(", ")}`,
    ogSuffix: "gallr exhibition route",
    notFoundTitle: "This route is no longer available",
    notFoundAction: "SEE EXHIBITIONS OPEN NOW",
    unavailableTitle: "Please try again in a moment",
    retry: "TRY AGAIN",
  },
};

/** Everything the page shows, derived from the route row, current catalogue rows and the author's name. */
function buildRouteModel({ route, catalogue, authorName, today, lang }) {
  const copy = COPY[lang];
  const byId = new Map(catalogue.map((row) => [row.id, row]));
  const ordered = [...route.stops].sort((a, b) => a.position - b.position);
  const verdict = routeVerdict(
    ordered.map((stop) => {
      const listed = byId.get(stop.exhibition_id);
      return listed
        ? { listed: true, openingDate: listed.opening_date, closingDate: listed.closing_date, hours: listed.hours }
        : { listed: false };
    }),
    today,
  );

  let totalMeters = 0;
  const stops = ordered.map((stop, index) => {
    const listed = byId.get(stop.exhibition_id) || null;
    const previous = ordered[index - 1];
    let walk = null;
    if (previous) {
      const leg = estimateWalk(previous, stop);
      totalMeters += leg.meters;
      walk = copy.walk(index, distanceLabel(leg.meters), durationLabel(leg.minutes, lang));
    }
    const status = verdict.statuses[index];
    return {
      number: index + 1,
      title: pick(lang, listed ? listed.name_ko : stop.name_ko, listed ? listed.name_en : stop.name_en),
      venue: pick(lang, listed ? listed.venue_name_ko : stop.venue_name_ko, listed ? listed.venue_name_en : stop.venue_name_en),
      district: districtLabel(stop, listed, lang),
      hoursToday: listed ? hoursToday(listed.hours, today, copy) : null,
      status: status.label ? { text: status.label[lang], blocking: status.blocking } : null,
      walk,
      directionsUrl: listed && listed.address_ko ? NAVER_SEARCH + encodeURIComponent(listed.address_ko) : null,
      coverImageUrl: listed ? listed.cover_image_url || null : null,
      latitude: stop.latitude,
      longitude: stop.longitude,
    };
  });

  const primaryStop =
    (stops[verdict.primaryIndex] && stops[verdict.primaryIndex].directionsUrl ? stops[verdict.primaryIndex] : null) ||
    stops.find((stop) => stop.directionsUrl) ||
    null;
  const distance = copy.distance(distanceLabel(totalMeters));
  const summaryParts = [copy.places(stops.length), distance];
  if (authorName) summaryParts.unshift(copy.author(authorName));
  const firstTitles = stops.slice(0, 2).map((stop) => stop.title).join(", ");

  return {
    lang,
    name: route.name,
    verdictLine: verdict.line[lang],
    summary: summaryParts.join(" · "),
    stops,
    primary: primaryStop ? { label: copy.primary(primaryStop.number - 1), url: primaryStop.directionsUrl } : null,
    drawing: drawingPoints(stops),
    og: {
      title: `${route.name} · ${copy.ogSuffix}`,
      description: `${copy.places(stops.length)} · ${distance} · ${firstTitles}${stops.length > 2 ? "…" : ""}`,
      image: (stops.find((stop) => stop.coverImageUrl) || {}).coverImageUrl || null,
    },
  };
}

function renderRoutePage(model, { routeId, shared }) {
  const copy = COPY[model.lang];
  const stops = model.stops.map((stop) => renderStop(stop, copy)).join("\n");
  const primary = model.primary
    ? `<a class="route-primary" href="${attr(model.primary.url)}" rel="noopener" data-route-start>${text(model.primary.label)}</a>`
    : "";
  const body = `${wordmarkHeader()}
<main class="route-page" id="main-content">
  <p class="route-eyebrow">${text(copy.eyebrow)}</p>
  <h1 class="route-title">${text(model.name)}</h1>
  <p class="route-verdict">${text(model.verdictLine)}</p>
  <p class="route-summary">${text(model.summary)}</p>
  <figure class="route-figure">
    <figcaption class="route-label">${text(copy.order)}</figcaption>
    ${renderDrawing(model, copy)}
  </figure>
  <h2 class="route-label">${text(copy.stops)}</h2>
  <ol class="route-stops">
${stops}
  </ol>
${primary ? `  <div class="route-primary-bar">${primary}</div>
` : ""}</main>
<footer class="route-footer">
  <a class="route-app-link" href="${attr(APP_STORE_URL)}" data-android-href="${attr(PLAY_STORE_URL)}">${text(copy.installApp)}</a>
</footer>
${renderEventScript(routeId, shared)}`;
  return documentShell({ lang: model.lang, title: model.og.title, og: model.og, body });
}

function renderStop(stop, copy) {
  const lines = [
    `<span class="route-stop__venue">${text(stop.venue)}</span>`,
    `<span class="route-stop__district">${text(stop.district)}</span>`,
  ];
  if (stop.hoursToday) lines.push(`<span class="route-stop__hours">${text(stop.hoursToday)}</span>`);
  if (stop.walk) lines.push(`<span class="route-stop__walk">${text(stop.walk)}</span>`);
  if (stop.status) {
    const role = stop.status.blocking ? ' role="note"' : "";
    const kind = stop.status.blocking ? "route-stop__status route-stop__status--blocking" : "route-stop__status";
    lines.push(`<span class="${kind}"${role}>${text(stop.status.text)}</span>`);
  }
  const directions = stop.directionsUrl
    ? `<a class="route-stop__directions" href="${attr(stop.directionsUrl)}" rel="noopener" aria-label="${attr(
        copy.directionsTo(stop.title),
      )}" data-route-start>${text(copy.directions)}</a>`
    : "";
  return `    <li class="route-stop">
      <span class="route-stop__number" aria-hidden="true">${String(stop.number).padStart(2, "0")}</span>
      <div class="route-stop__body">
        <span class="route-stop__title">${text(stop.title)}</span>
        ${lines.join("\n        ")}
      </div>
      ${directions}
    </li>`;
}

/**
 * The "방문 순서" drawing: dashed connectors, numbered circles, district labels, a north mark and a scale.
 * Presentation attributes keep it legible without the stylesheet (link previews, blocked CSS); the stylesheet
 * overrides them with the theme's colours.
 */
function renderDrawing(model, copy) {
  const size = 320;
  const pad = 40;
  const inner = size - pad * 2;
  const points = separated(
    model.drawing.points.map((point) => ({ x: pad + point.x * inner, y: pad + point.y * inner })),
    { min: pad / 2, max: size - pad / 2 },
  );
  const labelled = [];
  const districtLabel = (index, point) => {
    const district = model.stops[index].district;
    const repeated = labelled.some(
      (other) => other.text === district && Math.hypot(other.x - point.x, other.y - point.y) < LABEL_REPEAT_DISTANCE,
    );
    if (repeated) return "";
    labelled.push({ text: district, x: point.x, y: point.y });
    return `<text x="${num(point.x + 16)}" y="${num(point.y - 14)}" class="route-svg__district" fill="#525252">${text(district)}</text>`;
  };
  const connectors = points
    .slice(1)
    .map((point, index) => {
      const from = points[index];
      return `<line x1="${num(from.x)}" y1="${num(from.y)}" x2="${num(point.x)}" y2="${num(point.y)}" class="route-svg__leg" stroke="#000" stroke-width="2" stroke-dasharray="6 5" />`;
    })
    .join("");
  const circles = points
    .map(
      (point, index) =>
        `<g class="route-svg__stop"><circle cx="${num(point.x)}" cy="${num(point.y)}" r="12" fill="#fff" stroke="#000" stroke-width="1.5" />` +
        `<text x="${num(point.x)}" y="${num(point.y)}" dy="0.35em" text-anchor="middle" fill="#000">${index + 1}</text>` +
        `${districtLabel(index, point)}</g>`,
    )
    .join("");
  const scale = model.drawing.scale;
  const scaleWidth = scale ? (scale.meters / scale.spanMeters) * inner : 0;
  const scaleMark = scale
    ? `<g class="route-svg__scale" fill="#000"><line x1="${pad}" y1="${size - 14}" x2="${num(pad + scaleWidth)}" y2="${size - 14}" stroke="#000" stroke-width="2" />` +
      `<text x="${pad}" y="${size - 20}">${text(distanceLabel(scale.meters))}</text></g>`
    : "";
  const label = copy.drawing(model.stops.map((stop) => `${stop.number} ${stop.district}`));
  return `<svg class="route-svg" viewBox="0 0 ${size} ${size}" role="img" aria-label="${attr(label)}">
      <rect x="0.5" y="0.5" width="${size - 1}" height="${size - 1}" class="route-svg__frame" fill="none" stroke="#000" />
      ${connectors}${circles}
      <g class="route-svg__north" fill="#000"><path d="M${size - 24} 30 l6 -14 l6 14 z" /><text x="${size - 18}" y="44" text-anchor="middle">${text(copy.north)}</text></g>
      ${scaleMark}
    </svg>`;
}

function renderNotFoundPage(lang) {
  const copy = COPY[lang];
  const body = `${wordmarkHeader()}
<main class="route-page route-page--message" id="main-content">
  <p class="route-eyebrow">${text(copy.eyebrow)}</p>
  <h1 class="route-title">${text(copy.notFoundTitle)}</h1>
  <a class="route-primary route-primary--inline" href="/exhibitions/">${text(copy.notFoundAction)}</a>
</main>`;
  return documentShell({
    lang,
    title: copy.notFoundTitle,
    og: { title: `gallr ${lang === "en" ? "exhibition route" : "전시 동선"}`, description: copy.notFoundTitle, image: null },
    body,
  });
}

function renderUnavailablePage(lang, retryUrl) {
  const copy = COPY[lang];
  const body = `${wordmarkHeader()}
<main class="route-page route-page--message" id="main-content">
  <p class="route-eyebrow">${text(copy.eyebrow)}</p>
  <h1 class="route-title">${text(copy.unavailableTitle)}</h1>
  <a class="route-primary route-primary--inline" href="${attr(retryUrl)}">${text(copy.retry)}</a>
</main>`;
  return documentShell({ lang, title: copy.unavailableTitle, og: null, body });
}

function wordmarkHeader() {
  return `
<header class="route-header">
  <a href="/" class="route-wordmark" aria-label="gallr">
    <img src="/logos/b-arch-pin.svg" width="24" height="24" alt="" aria-hidden="true" />
    <span>gallr</span>
  </a>
</header>`;
}

/**
 * Pulls stops that would overlap apart so every number stays readable, keeping each one inside the square.
 * The drawing shows order and rough direction, not exact positions, so a small nudge is honest.
 */
function separated(points, bounds) {
  const placed = [];
  const inside = (x, y) => x >= bounds.min && x <= bounds.max && y >= bounds.min && y <= bounds.max;
  const crowded = (x, y) => placed.some((other) => Math.hypot(other.x - x, other.y - y) < STOP_SEPARATION);
  for (const point of points) {
    let { x, y } = point;
    for (let attempt = 0; attempt < SEPARATION_ATTEMPTS && crowded(x, y); attempt += 1) {
      const crowding = placed.find((other) => Math.hypot(other.x - x, other.y - y) < STOP_SEPARATION);
      const dx = x - crowding.x;
      const dy = y - crowding.y;
      const length = Math.hypot(dx, dy);
      // Away from the crowding stop first (lower right when identical), then sideways, then back.
      const ux = length === 0 ? Math.SQRT1_2 : dx / length;
      const uy = length === 0 ? Math.SQRT1_2 : dy / length;
      const directions = [[ux, uy], [-uy, ux], [uy, -ux], [-ux, -uy]];
      const free = directions
        .map(([vx, vy]) => ({ x: crowding.x + vx * STOP_SEPARATION, y: crowding.y + vy * STOP_SEPARATION }))
        .find((candidate) => inside(candidate.x, candidate.y) && !crowded(candidate.x, candidate.y));
      const next = free || { x: crowding.x + ux * STOP_SEPARATION, y: crowding.y + uy * STOP_SEPARATION };
      x = clamp(next.x, bounds.min, bounds.max);
      y = clamp(next.y, bounds.min, bounds.max);
    }
    placed.push({ x, y });
  }
  return placed;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

/** "약 9분", "약 1시간 34분", "~8 HR 43 MIN": the app's duration wording. */
function durationLabel(minutes, lang) {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (lang === "en") {
    if (hours === 0) return `~${rest} MIN`;
    return rest === 0 ? `~${hours} HR` : `~${hours} HR ${rest} MIN`;
  }
  if (hours === 0) return `약 ${rest}분`;
  return rest === 0 ? `약 ${hours}시간` : `약 ${hours}시간 ${rest}분`;
}

function documentShell({ lang, title, og, body }) {
  const ogTags = og
    ? [
        `<meta property="og:title" content="${attr(og.title)}" />`,
        `<meta property="og:description" content="${attr(og.description)}" />`,
        '<meta property="og:site_name" content="gallr" />',
        '<meta property="og:type" content="website" />',
        og.image ? `<meta property="og:image" content="${attr(og.image)}" />` : "",
      ]
        .filter(Boolean)
        .join("\n  ")
    : "";
  return `<!DOCTYPE html>
<html lang="${lang}">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${text(title)}</title>
  ${ogTags}
  <link rel="preload" as="font" type="font/woff2" href="/fonts/inter-700.woff2" crossorigin />
  <link rel="stylesheet" href="/styles/tokens.css" />
  <link rel="stylesheet" href="/styles/main.css" />
  <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
</head>
<body class="route-body">
${body}
</body>
</html>
`;
}

/** Posts route_page_opened on load and route_page_started on the primary or any directions tap (E-D5). */
function renderEventScript(routeId, shared) {
  const endpoint = `/route/${encodeURIComponent(routeId)}/events`;
  return `<script>
(function () {
  var endpoint = ${JSON.stringify(endpoint)};
  var shared = ${shared ? "true" : "false"};
  function send(event) {
    var body = JSON.stringify({ event: event, shared: shared });
    try {
      if (navigator.sendBeacon) {
        navigator.sendBeacon(endpoint, new Blob([body], { type: "application/json" }));
        return;
      }
      fetch(endpoint, { method: "POST", headers: { "content-type": "application/json" }, body: body, keepalive: true });
    } catch (e) {}
  }
  send("route_page_opened");
  var started = false;
  document.addEventListener("click", function (event) {
    var link = event.target.closest && event.target.closest("[data-route-start]");
    if (!link || started) return;
    started = true;
    send("route_page_started");
  });
  if (/Android/i.test(navigator.userAgent)) {
    var app = document.querySelector("[data-android-href]");
    if (app) app.setAttribute("href", app.getAttribute("data-android-href"));
  }
})();
</script>`;
}

function hoursToday(hoursText, today, copy) {
  const hours = parseOpeningHours(hoursText);
  const opening = hours.byDay[weekdayOf(today)];
  return opening ? `${copy.today} ${opening.opens}–${opening.closes}` : null;
}

function districtLabel(stop, listed, lang) {
  const region = lang === "en" ? stop.region_en : stop.region_ko;
  if (stop.city_ko === SEOUL_CITY) return region;
  const city = lang === "en" ? (listed && listed.city_en) || stop.city_ko : stop.city_ko;
  return `${city} ${region}`;
}

function pick(lang, ko, en) {
  return lang === "en" && en && en.trim() ? en : ko;
}

function estimateWalk(from, to) {
  const km = haversineKm(from.latitude, from.longitude, to.latitude, to.longitude) * WALKING_CIRCUITY_MULTIPLIER;
  return { meters: Math.round(km * 1000), minutes: Math.ceil((km / WALKING_SPEED_KMH) * 60) };
}

function haversineKm(lat1, lon1, lat2, lon2) {
  const toRad = (degrees) => (degrees * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/** "600 M" below a kilometre, else one decimal "1.2 KM", as the app labels distances. */
function distanceLabel(meters) {
  if (meters < 1000) return `${Math.round(meters / 10) * 10} M`;
  const tenths = Math.round(meters / 100);
  return `${Math.floor(tenths / 10)}.${tenths % 10} KM`;
}

/** Stops projected (Web Mercator) into the unit square, shape kept, plus a round-number scale. */
function drawingPoints(stops) {
  if (stops.length === 0) return { points: [], scale: null };
  const projected = stops.map((stop) => ({ x: stop.longitude, y: mercatorY(stop.latitude) }));
  const minX = Math.min(...projected.map((p) => p.x));
  const maxX = Math.max(...projected.map((p) => p.x));
  const minY = Math.min(...projected.map((p) => p.y));
  const maxY = Math.max(...projected.map((p) => p.y));
  const span = Math.max(maxX - minX, maxY - minY);
  if (span === 0) return { points: projected.map(() => ({ x: 0.5, y: 0.5 })), scale: null };
  const offsetX = (span - (maxX - minX)) / 2;
  const offsetY = (span - (maxY - minY)) / 2;
  const points = projected.map((p) => ({ x: (p.x - minX + offsetX) / span, y: 1 - (p.y - minY + offsetY) / span }));
  // Degrees of longitude across the square, in metres at the route's mean latitude.
  const meanLatitude = stops.reduce((sum, stop) => sum + stop.latitude, 0) / stops.length;
  const spanMeters = span * 111320 * Math.cos((meanLatitude * Math.PI) / 180);
  const steps = [100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000];
  const meters = steps.filter((step) => step <= spanMeters / 3).pop() || steps[0];
  return { points, scale: { meters, spanMeters } };
}

function mercatorY(latitude) {
  return (Math.log(Math.tan(Math.PI / 4 + (latitude * Math.PI) / 360)) * 180) / Math.PI;
}

function num(value) {
  return Number(value.toFixed(1));
}

function text(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function attr(value) {
  return text(value).replace(/"/g, "&quot;");
}

module.exports = {
  buildRouteModel,
  renderRoutePage,
  renderNotFoundPage,
  renderUnavailablePage,
  distanceLabel,
  durationLabel,
};
