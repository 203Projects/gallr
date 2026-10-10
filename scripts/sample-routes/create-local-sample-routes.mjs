#!/usr/bin/env node
// Creates the sample routes in a LOCAL Supabase database container (never a hosted project).
// Usage: node scripts/sample-routes/create-local-sample-routes.mjs --container supabase_db_<id> [--catalogue rows.json]
//        [--today YYYY-MM-DD] [--list]
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import {
  buildEditorMembershipSql,
  buildSeedSql,
  buildStaffMembershipSql,
  planRoutes,
  seoulDate,
  validateDefinitions,
} from "./sample-routes.mjs";

const CONTAINER = /^supabase_db_[a-z0-9_-]+$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Parses and validates arguments; throws with a usage message on anything unexpected. */
export function parseArgs(argv) {
  const options = { container: null, catalogue: null, today: null, list: false };
  const queue = [...argv];
  while (queue.length > 0) {
    const flag = queue.shift();
    if (flag === "--list") {
      options.list = true;
      continue;
    }
    const value = queue.shift();
    if (value === undefined || value.startsWith("--")) throw new Error(`missing value for ${flag}`);
    if (flag === "--container") options.container = value;
    else if (flag === "--catalogue") options.catalogue = value;
    else if (flag === "--today") options.today = value;
    else throw new Error(`unknown argument ${flag}`);
  }
  if (!CONTAINER.test(options.container ?? "")) throw new Error("--container must name a local supabase_db_<project> container");
  if (options.today !== null && !ISO_DATE.test(options.today)) throw new Error("--today must be YYYY-MM-DD");
  return options;
}

/** True only for a running container of this Docker engine that runs the Supabase Postgres image. */
export function isLocalSupabaseDatabase(info) {
  return info?.State?.Running === true && /(^|\/)supabase\/postgres:/.test(info?.Config?.Image ?? "");
}

function docker(args, input) {
  const result = spawnSync("docker", args, { input, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  if (result.status !== 0) {
    const lines = (result.stderr || "").trim().split("\n");
    const reason = lines.find((line) => /\bERROR:/.test(line)) ?? lines.pop();
    throw new Error(`docker ${args[0]} failed: ${reason}`);
  }
  return result.stdout;
}

function psql(container, sql) {
  return docker(["exec", "-i", container, "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "postgres"], sql);
}

function assertLocalContainer(container) {
  const [info] = JSON.parse(docker(["inspect", container]));
  if (!isLocalSupabaseDatabase(info)) throw new Error(`${container} is not a running local Supabase database`);
}

const literal = (value) => `'${String(value).replace(/'/g, "''")}'`;

function loadCatalogue(container, file) {
  const parsed = JSON.parse(readFileSync(file, "utf8"));
  const rows = Array.isArray(parsed) ? parsed : parsed.rows;
  if (!Array.isArray(rows) || rows.length === 0) throw new Error("catalogue file has no rows");
  // Relations to galleries, editors and events are not part of the snapshot; the editor's-pick flag follows editor_id.
  const cleaned = rows.map((row) => ({
    ...row,
    gallery_id: null,
    editor_id: null,
    guest_editor_id: null,
    event_id: null,
    is_editors_pick: false,
  }));
  psql(
    container,
    `insert into public.exhibition_catalog_v2
     select * from jsonb_populate_recordset(null::public.exhibition_catalog_v2, ${literal(JSON.stringify(cleaned))}::jsonb)
     on conflict (id) do nothing;\n`,
  );
  return cleaned.length;
}

function ensureAccount(container, account) {
  psql(
    container,
    `insert into auth.users (
       instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at,
       raw_app_meta_data, raw_user_meta_data, is_anonymous, confirmation_token, recovery_token,
       email_change_token_new, email_change, email_change_token_current, phone_change, phone_change_token,
       reauthentication_token
     ) values (
       '00000000-0000-0000-0000-000000000000', ${literal(account.id)}, 'authenticated', 'authenticated',
       ${literal(account.email)}, extensions.crypt(${literal(account.localPassword)}, extensions.gen_salt('bf')), now(), now(), now(),
       '{"provider":"email","providers":["email"]}', jsonb_build_object('full_name', ${literal(account.displayName)}),
       false, '', '', '', '', '', '', '', ''
     ) on conflict (id) do nothing;
     -- The app shows the signed-in name from sign-up metadata, as a real sign-up provides it.
     update auth.users set raw_user_meta_data = raw_user_meta_data || jsonb_build_object('full_name', ${literal(account.displayName)})
     where id = ${literal(account.id)};
     insert into auth.identities (provider_id, user_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
     values (${literal(account.id)}, ${literal(account.id)},
       jsonb_build_object('sub', ${literal(account.id)}, 'email', ${literal(account.email)}, 'email_verified', true),
       'email', now(), now(), now())
     on conflict do nothing;
     update public.profiles set display_name = ${literal(account.displayName)} where id = ${literal(account.id)};\n`,
  );
}

function readCatalogue(container) {
  const json = psql(
    container,
    `select coalesce(json_agg(json_build_object(
       'id', id, 'venue_name_ko', venue_name_ko, 'opening_date', opening_date, 'closing_date', closing_date,
       'latitude', latitude, 'longitude', longitude, 'city_en', city_en)), '[]')
     from public.exhibition_catalog_v2;\n`,
  );
  return JSON.parse(json.trim());
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const definitions = validateDefinitions(JSON.parse(readFileSync(new URL("./routes.json", import.meta.url), "utf8")));
  assertLocalContainer(options.container);
  if (options.catalogue) console.log(`catalogue: ${loadCatalogue(options.container, options.catalogue)} rows offered`);
  ensureAccount(options.container, definitions.author);
  if (definitions.reader) ensureAccount(options.container, definitions.reader);
  if (definitions.staff) {
    ensureAccount(options.container, definitions.staff);
    psql(options.container, buildStaffMembershipSql(definitions.staff));
  }
  if (options.list) psql(options.container, buildEditorMembershipSql(definitions.author));
  const today = options.today ?? seoulDate();
  const planned = planRoutes(definitions, readCatalogue(options.container), today);
  psql(options.container, buildSeedSql(definitions.author, planned, { list: options.list }));
  console.log(`sample routes for ${today} (author ${definitions.author.email}):`);
  for (const route of planned) {
    const visibility = route.publish ? (options.list ? "published, listed" : "published") : "private";
    const state = route.skipped ? `skipped: ${route.skipped}` : `${route.exhibitionIds.length} stops, ${visibility}`;
    console.log(`  ${route.name}  ${route.routeId}  ${state}`);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(`create-local-sample-routes: ${error.message}`);
    process.exit(1);
  }
}
