// Pure planning for local sample routes: which open exhibitions each definition picks, in what order, and the
// SQL that saves them through the route functions as the sample author. No I/O here.
import { createHash } from "node:crypto";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ORDERS = new Set(["nearest", "as-listed"]);
const MIN_STOPS = 2;
const MAX_STOPS = 10;

/** Throws on a definitions file the runner must not act on. */
function validateAccount(role, account) {
  if (!account || !UUID.test(account.id ?? "") || !/^[^@\s]+@[^@\s]+\.test$/.test(account.email ?? "")) {
    throw new Error(`${role} needs a UUID id and an email on the reserved .test domain`);
  }
  if (typeof account.localPassword !== "string" || account.localPassword.length < 12) {
    throw new Error(`${role} needs a local-only password of at least 12 characters`);
  }
}

export function validateDefinitions(definitions) {
  validateAccount("author", definitions?.author);
  // The optional reader copies and reports the samples, which an author cannot do to their own routes.
  if (definitions.reader !== undefined) {
    validateAccount("reader", definitions.reader);
    if (definitions.reader.id === definitions.author.id) throw new Error("reader must differ from the author");
  }
  // The optional staff account reviews listing requests and reports in a local Admin.
  if (definitions.staff !== undefined) {
    validateAccount("staff", definitions.staff);
    const others = [definitions.author.id, definitions.reader?.id];
    if (others.includes(definitions.staff.id)) throw new Error("staff must differ from the author and the reader");
  }
  if (!Array.isArray(definitions.routes) || definitions.routes.length === 0) throw new Error("no routes defined");
  const keys = new Set();
  for (const route of definitions.routes) {
    if (!/^[a-z0-9-]+$/.test(route.key ?? "")) throw new Error("route key must be kebab-case");
    if (keys.has(route.key)) throw new Error(`duplicate route key ${route.key}`);
    keys.add(route.key);
    const name = String(route.name ?? "").trim();
    if (name.length < 1 || name.length > 60) throw new Error(`route ${route.key} name must be 1-60 characters`);
    if (!Number.isInteger(route.maxStops) || route.maxStops < MIN_STOPS || route.maxStops > MAX_STOPS) {
      throw new Error(`route ${route.key} maxStops must be ${MIN_STOPS}-${MAX_STOPS}`);
    }
    if (!ORDERS.has(route.order)) throw new Error(`route ${route.key} order must be nearest or as-listed`);
    const select = route.select ?? {};
    const kinds = ["area", "ids", "closingWithinDays"].filter((kind) => select[kind] !== undefined);
    if (kinds.length !== 1) throw new Error(`route ${route.key} needs exactly one selector`);
  }
  return definitions;
}

/** Plans every route against the catalogue for the given Asia/Seoul date (YYYY-MM-DD). */
export function planRoutes(definitions, catalogue, today) {
  validateDefinitions(definitions);
  const open = catalogue.filter((show) => isOpenInSeoul(show, today));
  return definitions.routes.map((route) => {
    const chosen = routeStops(route, selected(route, open, today));
    const result = { key: route.key, name: route.name.trim(), publish: route.publish === true, routeId: routeIdFor(route.key) };
    if (chosen.length < MIN_STOPS) return { ...result, exhibitionIds: [], skipped: `fewer than ${MIN_STOPS} open stops` };
    return { ...result, exhibitionIds: chosen.map((show) => show.id) };
  });
}

function isOpenInSeoul(show, today) {
  return (
    /^seoul/i.test(show.city_en ?? "") &&
    Number.isFinite(show.latitude) &&
    Number.isFinite(show.longitude) &&
    show.opening_date <= today &&
    show.closing_date >= today
  );
}

function selected(route, open, today) {
  const { area, ids, closingWithinDays } = route.select;
  if (ids) {
    const byId = new Map(open.map((show) => [show.id, show]));
    return ids.map((id) => byId.get(id)).filter(Boolean);
  }
  if (area) {
    return open.filter(
      (show) =>
        show.latitude >= area.south && show.latitude <= area.north && show.longitude >= area.west && show.longitude <= area.east,
    );
  }
  const last = addDays(today, closingWithinDays);
  return open.filter((show) => show.closing_date <= last);
}

// Walk order is applied before the cap, so a capped nearest route is still one walkable cluster.
function routeStops(route, shows) {
  return order(distinctVenues(shows, route.onePerVenue), route.order).slice(0, route.maxStops);
}

function distinctVenues(shows, onePerVenue) {
  if (!onePerVenue) return shows;
  const venues = new Set();
  return shows.filter((show) => {
    const venue = String(show.venue_name_ko ?? "").trim();
    if (venues.has(venue)) return false;
    venues.add(venue);
    return true;
  });
}

function order(shows, mode) {
  if (mode === "as-listed" || shows.length === 0) return shows;
  const remaining = [...shows].sort((a, b) => b.latitude - a.latitude || a.id.localeCompare(b.id));
  const walk = [remaining.shift()];
  while (remaining.length > 0) {
    const last = walk[walk.length - 1];
    let best = 0;
    for (let i = 1; i < remaining.length; i += 1) {
      if (distance(last, remaining[i]) < distance(last, remaining[best])) best = i;
    }
    walk.push(remaining.splice(best, 1)[0]);
  }
  return walk;
}

function distance(a, b) {
  const dLat = a.latitude - b.latitude;
  const dLon = (a.longitude - b.longitude) * Math.cos((a.latitude * Math.PI) / 180);
  return Math.hypot(dLat, dLon);
}

function addDays(isoDate, days) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** A stable version-4-shaped UUID per route key, so reruns update the same routes. */
export function routeIdFor(key) {
  const hex = createHash("sha256").update(`gallr-sample-route:${key}`).digest("hex");
  const variant = ((parseInt(hex[16], 16) & 0x3) | 0x8).toString(16);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-${variant}${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

/** The Asia/Seoul calendar date of an instant, as YYYY-MM-DD. */
export function seoulDate(instant = new Date()) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(instant);
}

const literal = (value) => `'${String(value).replace(/'/g, "''")}'`;

/** One transaction that saves (and publishes) every planned route as the author, skipping unplannable ones. */
export function buildSeedSql(author, planned, { list = false } = {}) {
  const lines = [
    "begin;",
    "set local role authenticated;",
    `select set_config('request.jwt.claim.sub', ${literal(author.id)}, true);`,
  ];
  for (const route of planned) {
    if (route.skipped) continue;
    const ids = route.exhibitionIds.map(literal).join(", ");
    lines.push(`select public.save_personal_route(${literal(route.routeId)}, ${literal(route.name)}, array[${ids}]) is not null;`);
    if (route.publish) lines.push(`select public.publish_personal_route(${literal(route.routeId)}) is not null;`);
    if (route.publish && list) lines.push(`select public.request_route_listing(${literal(route.routeId)}) is not null;`);
  }
  lines.push("commit;");
  return `${lines.join("\n")}\n`;
}

/** Makes the local staff account an active Admin member (as the database owner). */
export function buildStaffMembershipSql(staff) {
  return [
    "insert into content.staff_members (user_id, role, active)",
    `values (${literal(staff.id)}, 'admin', true)`,
    "on conflict (user_id) do update set active = true;",
    "",
  ].join("\n");
}

const SAMPLE_EDITOR_ID = "sample-routes-editor";

/**
 * Makes the sample author an active editor (as the database owner), so `request_route_listing` approves its
 * routes at once and they appear in 추천 동선 without a staff review.
 */
export function buildEditorMembershipSql(author) {
  const name = literal(author.displayName);
  return [
    "insert into public.editors (id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en, is_active, active_from)",
    `values (${literal(SAMPLE_EDITOR_ID)}, ${name}, ${name}, '샘플 에디터', 'Sample editor', '로컬 샘플', 'Local sample', true, current_date)`,
    "on conflict (id) do nothing;",
    "insert into content.editor_memberships (user_id, editor_id, active)",
    `values (${literal(author.id)}, ${literal(SAMPLE_EDITOR_ID)}, true)`,
    "on conflict (user_id) do update set active = true;",
    "",
  ].join("\n");
}
