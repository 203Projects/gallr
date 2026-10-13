// Network-free tests for sample route planning. Run: node --test scripts/sample-routes/sample-routes.test.mjs
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { isLocalSupabaseDatabase, parseArgs } from "./create-local-sample-routes.mjs";
import {
  buildEditorMembershipSql,
  buildStaffMembershipSql,
  buildSeedSql,
  planRoutes,
  routeIdFor,
  seoulDate,
  validateDefinitions,
} from "./sample-routes.mjs";

const definitions = JSON.parse(readFileSync(new URL("./routes.json", import.meta.url), "utf8"));
const TODAY = "2026-10-08";

function show(id, overrides = {}) {
  return {
    id,
    venue_name_ko: `갤러리 ${id}`,
    opening_date: "2026-10-01",
    closing_date: "2026-10-31",
    latitude: 37.538,
    longitude: 127.0,
    city_en: "Seoul",
    ...overrides,
  };
}

function plan(route, catalogue) {
  return planRoutes({ author: definitions.author, routes: [route] }, catalogue, TODAY)[0];
}

test("an area route keeps open shows inside the box, one per venue, capped", () => {
  const route = {
    key: "area",
    name: "구역",
    publish: true,
    select: { area: { south: 37.53, north: 37.545, west: 126.995, east: 127.015 } },
    onePerVenue: true,
    maxStops: 3,
    order: "nearest",
  };
  const result = plan(route, [
    show("a", { latitude: 37.531, longitude: 127.0 }),
    show("b", { latitude: 37.535, longitude: 127.001 }),
    show("same-venue", { latitude: 37.535, longitude: 127.001, venue_name_ko: "갤러리 b" }),
    show("outside", { latitude: 37.6, longitude: 127.0 }),
    show("ended", { closing_date: "2026-10-07" }),
    show("not-yet", { opening_date: "2026-10-09" }),
    show("no-location", { latitude: null, longitude: null }),
    show("busan", { city_en: "Busan" }),
    show("c", { latitude: 37.54, longitude: 127.01 }),
    show("d", { latitude: 37.544, longitude: 127.012 }),
  ]);
  assert.equal(result.skipped, undefined);
  assert.equal(result.exhibitionIds.length, 3);
  assert.ok(!result.exhibitionIds.includes("same-venue"));
  for (const id of ["outside", "ended", "not-yet", "no-location", "busan"]) {
    assert.ok(!result.exhibitionIds.includes(id), `${id} must be excluded`);
  }
});

test("shows open on the first and last day count as open", () => {
  const route = { key: "edges", name: "경계", publish: true, select: { closingWithinDays: 7 }, maxStops: 5, order: "nearest" };
  const result = plan(route, [
    show("opens-today", { opening_date: TODAY, closing_date: "2026-10-10" }),
    show("closes-today", { closing_date: TODAY, latitude: 37.54 }),
    show("closes-in-8", { closing_date: "2026-10-16" }),
  ]);
  assert.deepEqual([...result.exhibitionIds].sort(), ["closes-today", "opens-today"]);
});

test("a listed route keeps its curated order and drops closed or unknown ids", () => {
  const route = { key: "list", name: "큐레이션", publish: true, select: { ids: ["z", "gone", "y", "x"] }, maxStops: 6, order: "as-listed" };
  const result = plan(route, [show("x"), show("y"), show("z"), show("gone", { closing_date: "2026-10-01" })]);
  assert.deepEqual(result.exhibitionIds, ["z", "y", "x"]);
});

test("nearest order starts from the northernmost stop and walks to the closest next", () => {
  const route = { key: "walk", name: "걷기", publish: true, select: { ids: ["south", "north", "middle"] }, maxStops: 6, order: "nearest" };
  const result = plan(route, [
    show("south", { latitude: 37.50 }),
    show("north", { latitude: 37.60 }),
    show("middle", { latitude: 37.55 }),
  ]);
  assert.deepEqual(result.exhibitionIds, ["north", "middle", "south"]);
});

test("a route with fewer than two open stops is skipped with a reason", () => {
  const route = { key: "thin", name: "부족", publish: true, select: { ids: ["only"] }, maxStops: 6, order: "as-listed" };
  const result = plan(route, [show("only")]);
  assert.deepEqual(result.exhibitionIds, []);
  assert.equal(result.skipped, "fewer than 2 open stops");
});

test("route ids are stable UUIDs derived from the key", () => {
  const id = routeIdFor("hannam-walk");
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(id, routeIdFor("hannam-walk"));
  assert.notEqual(id, routeIdFor("contemporary-walk"));
});

test("the committed definitions are valid and include the requested walks", () => {
  assert.doesNotThrow(() => validateDefinitions(definitions));
  const names = definitions.routes.map((route) => route.name);
  assert.ok(names.includes("한남동 워크"));
  assert.ok(names.includes("컨템포러리 워크"));
});

test("malformed definitions are rejected", () => {
  const good = definitions.routes[0];
  const cases = [
    { routes: [] },
    { author: definitions.author, routes: [{ ...good, key: "" }] },
    { author: definitions.author, routes: [good, { ...good }] },
    { author: definitions.author, routes: [{ ...good, name: "x".repeat(61) }] },
    { author: definitions.author, routes: [{ ...good, maxStops: 11 }] },
    { author: definitions.author, routes: [{ ...good, select: {} }] },
    { author: definitions.author, routes: [{ ...good, order: "random" }] },
    { author: { ...definitions.author, id: "nope" }, routes: [good] },
    { author: { ...definitions.author, email: "real@gallrmap.com" }, routes: [good] },
    { author: { ...definitions.author, localPassword: "short" }, routes: [good] },
  ];
  for (const candidate of cases) assert.throws(() => validateDefinitions(candidate));
});

test("seed SQL saves through the route functions as the author and quotes names", () => {
  const sql = buildSeedSql(definitions.author, [
    { key: "q", name: "작가's 동선", publish: true, routeId: routeIdFor("q"), exhibitionIds: ["a", "b'c"] },
    { key: "p", name: "비공개", publish: false, routeId: routeIdFor("p"), exhibitionIds: ["a", "b"] },
    { key: "s", name: "건너뜀", publish: true, routeId: routeIdFor("s"), exhibitionIds: [], skipped: "fewer than 2 open stops" },
  ]);
  assert.match(sql, /set local role authenticated;/);
  assert.match(sql, new RegExp(`request\\.jwt\\.claim\\.sub', '${definitions.author.id}'`));
  assert.match(sql, /public\.save_personal_route\('[0-9a-f-]{36}', '작가''s 동선', array\['a', 'b''c'\]\)/);
  assert.equal((sql.match(/public\.publish_personal_route/g) || []).length, 1);
  assert.ok(!sql.includes("건너뜀"));
  assert.ok(sql.trimStart().startsWith("begin;") && sql.trimEnd().endsWith("commit;"));
});

test("listing asks for each published route to be listed, after it is published", () => {
  const planned = [
    { key: "q", name: "공개", publish: true, routeId: routeIdFor("q"), exhibitionIds: ["a", "b"] },
    { key: "p", name: "비공개", publish: false, routeId: routeIdFor("p"), exhibitionIds: ["a", "b"] },
  ];
  const plain = buildSeedSql(definitions.author, planned);
  assert.ok(!plain.includes("request_route_listing"));

  const listed = buildSeedSql(definitions.author, planned, { list: true });
  const requests = listed.match(/public\.request_route_listing\('([0-9a-f-]{36})'\)/g) || [];
  assert.deepEqual(requests, [`public.request_route_listing('${routeIdFor("q")}')`]);
  assert.ok(listed.indexOf("publish_personal_route") < listed.indexOf("request_route_listing"));
});

test("the sample author becomes an active editor so its listings are approved at once", () => {
  const sql = buildEditorMembershipSql(definitions.author);
  assert.match(sql, /insert into public\.editors \(/);
  assert.match(sql, /insert into content\.editor_memberships \(user_id, editor_id, active\)/);
  assert.match(sql, new RegExp(`'${definitions.author.id}', 'sample-routes-editor', true`));
  assert.match(sql, /on conflict \(user_id\) do update set active = true/);
});

test("a local reader account, separate from the author, can copy and report the samples", () => {
  const { reader, author } = definitions;
  assert.ok(reader, "routes.json defines a reader");
  assert.notEqual(reader.id, author.id);
  assert.match(reader.email, /\.test$/);
  const withBadReader = { ...definitions, reader: { ...reader, email: "reader@example.com" } };
  assert.throws(() => validateDefinitions(withBadReader), /reader/);
  const sameAsAuthor = { ...definitions, reader: { ...reader, id: author.id } };
  assert.throws(() => validateDefinitions(sameAsAuthor), /reader/);
});

test("a local staff account reviews listings in a local Admin", () => {
  const { staff, author, reader } = definitions;
  assert.ok(staff, "routes.json defines a staff account");
  assert.equal(new Set([staff.id, author.id, reader.id]).size, 3);
  assert.throws(() => validateDefinitions({ ...definitions, staff: { ...staff, id: reader.id } }), /staff/);
  const sql = buildStaffMembershipSql(staff);
  assert.match(sql, /insert into content\.staff_members \(user_id, role, active\)/);
  assert.match(sql, new RegExp(`'${staff.id}', 'admin', true`));
  assert.match(sql, /on conflict \(user_id\) do update set active = true/);
});

test("today is the Asia/Seoul calendar date", () => {
  assert.equal(seoulDate(new Date("2026-10-07T15:30:00Z")), "2026-10-08");
  assert.equal(seoulDate(new Date("2026-10-07T14:59:00Z")), "2026-10-07");
});

test("the runner accepts only a local supabase database container", () => {
  assert.deepEqual(parseArgs(["--container", "supabase_db_gallr089", "--today", "2026-10-08"]), {
    container: "supabase_db_gallr089",
    catalogue: null,
    today: "2026-10-08",
    list: false,
  });
  assert.equal(parseArgs(["--list", "--container", "supabase_db_gallr089"]).list, true);
  assert.equal(parseArgs(["--container", "supabase_db_gallr089", "--list"]).list, true);
  assert.throws(() => parseArgs([]), /--container/);
  assert.throws(() => parseArgs(["--container", "postgres://db.example.supabase.co"]), /--container/);
  assert.throws(() => parseArgs(["--container", "supabase_db_x", "--today", "8 Oct"]), /YYYY-MM-DD/);
  assert.throws(() => parseArgs(["--container", "supabase_db_x", "--project-ref", "abc"]), /unknown argument/);
  assert.throws(() => parseArgs(["--container"]), /missing value/);
});

test("the runner acts only on a running local Supabase Postgres container", () => {
  const running = { State: { Running: true }, Config: { Image: "public.ecr.aws/supabase/postgres:17.6.1.166" } };
  assert.equal(isLocalSupabaseDatabase(running), true);
  assert.equal(isLocalSupabaseDatabase({ ...running, State: { Running: false } }), false);
  assert.equal(isLocalSupabaseDatabase({ ...running, Config: { Image: "postgres:17" } }), false);
  assert.equal(isLocalSupabaseDatabase(undefined), false);
});
