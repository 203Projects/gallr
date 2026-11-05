// The tracked list of galleries and museums gallr follows, with each venue's official website and social
// channels, so staff can sweep them for new exhibitions. Dependency-free Node 22; the only file it writes is
// venues.json (`mark-checked`). The catalogue query is run separately (see README.md).
import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const FORMAT_VERSION = 1;
const REGISTRY_URL = new URL("./venues.json", import.meta.url);
const KINDS = Object.freeze(["museum", "gallery", "art_space", "foundation", "other"]);
const STATUSES = Object.freeze(["active", "unknown", "moved", "closed"]);
const LINK_KEYS = Object.freeze(["website", "exhibitions_page", "instagram", "facebook", "youtube", "naver_blog", "x"]);
const VENUE_KEYS = Object.freeze([
  "name_ko",
  "name_en",
  "kind",
  "city_ko",
  "region_ko",
  ...LINK_KEYS,
  "other_links",
  "status",
  "last_checked",
  "notes",
]);
const LINK_LABELS = Object.freeze({
  website: "site",
  exhibitions_page: "exhibitions",
  instagram: "Instagram",
  facebook: "Facebook",
  youtube: "YouTube",
  naver_blog: "blog",
  x: "X",
});
const INSTAGRAM_PATTERN = /^https:\/\/www\.instagram\.com\/[A-Za-z0-9._]+\/$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

const USAGE = [
  "Usage:",
  "  node venue-registry.mjs check",
  "  supabase db query ... -f catalogue-venues.sql < /dev/null | node venue-registry.mjs diff",
  "  node venue-registry.mjs sweep [--limit <n>] [--city <city_ko>] [--kind <kind>]",
  "  node venue-registry.mjs mark-checked --date <YYYY-MM-DD> <name_ko>...",
].join("\n");

class InputError extends Error {}

function isWebUrl(value) {
  if (typeof value !== "string") return false;
  try {
    return ["https:", "http:"].includes(new URL(value).protocol);
  } catch {
    return false;
  }
}

function isCalendarDate(value) {
  if (typeof value !== "string" || !DATE_PATTERN.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value);
}

function venueProblems(venue, index) {
  const label = typeof venue?.name_ko === "string" && venue.name_ko ? venue.name_ko : `venue #${index + 1}`;
  if (venue === null || typeof venue !== "object" || Array.isArray(venue)) return [`${label}: not an object`];

  const problems = [];
  const keys = Object.keys(venue);
  const missing = VENUE_KEYS.filter((key) => !keys.includes(key));
  const unknown = keys.filter((key) => !VENUE_KEYS.includes(key));
  if (missing.length) problems.push(`${label}: missing ${missing.join(", ")}`);
  if (unknown.length) problems.push(`${label}: unknown ${unknown.join(", ")}`);

  if (typeof venue.name_ko !== "string" || venue.name_ko.trim() !== venue.name_ko || !venue.name_ko) {
    problems.push(`${label}: name_ko must be a non-blank string without outer spaces`);
  }
  for (const key of ["name_en", "city_ko", "region_ko", "notes"]) {
    if (typeof venue[key] !== "string") problems.push(`${label}: ${key} must be a string`);
  }
  if (!KINDS.includes(venue.kind)) problems.push(`${label}: kind must be one of ${KINDS.join(", ")}`);
  if (!STATUSES.includes(venue.status)) problems.push(`${label}: status must be one of ${STATUSES.join(", ")}`);
  for (const key of LINK_KEYS) {
    if (venue[key] !== null && !isWebUrl(venue[key])) problems.push(`${label}: ${key} must be null or an http(s) URL`);
  }
  if (typeof venue.instagram === "string" && !INSTAGRAM_PATTERN.test(venue.instagram)) {
    problems.push(`${label}: instagram must look like https://www.instagram.com/<handle>/`);
  }
  if (!Array.isArray(venue.other_links) || !venue.other_links.every(isWebUrl)) {
    problems.push(`${label}: other_links must be a list of http(s) URLs`);
  }
  if (venue.last_checked !== null && !isCalendarDate(venue.last_checked)) {
    problems.push(`${label}: last_checked must be null or YYYY-MM-DD`);
  }
  return problems;
}

/** Every problem in a parsed registry, in file order; an empty list means it is valid. */
export function registryProblems(registry) {
  if (registry === null || typeof registry !== "object" || Array.isArray(registry)) return ["registry is not an object"];
  if (registry.format_version !== FORMAT_VERSION) return [`format_version must be ${FORMAT_VERSION}`];
  if (!Array.isArray(registry.venues)) return ["venues must be a list"];

  const problems = registry.venues.flatMap(venueProblems);
  const seen = new Set();
  registry.venues.forEach((venue, index) => {
    const name = venue?.name_ko;
    if (typeof name !== "string") return;
    if (seen.has(name)) problems.push(`${name}: listed twice`);
    seen.add(name);
    const previous = registry.venues[index - 1]?.name_ko;
    if (typeof previous === "string" && previous > name) problems.push(`${name}: out of order (sort by name_ko)`);
  });
  return problems;
}

/** Parses venues.json text and throws InputError listing every problem. */
export function readRegistry(text) {
  let registry;
  try {
    registry = JSON.parse(text);
  } catch {
    throw new InputError("venues.json is not valid JSON");
  }
  const problems = registryProblems(registry);
  if (problems.length) throw new InputError(`venues.json has ${problems.length} problem(s):\n${problems.join("\n")}`);
  return registry;
}

function readCatalogue(text) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new InputError("catalogue input is not valid JSON (pipe the output of catalogue-venues.sql)");
  }
  const rows = parsed?.rows;
  if (!Array.isArray(rows) || !rows.every((row) => typeof row?.venue_name_ko === "string")) {
    throw new InputError("catalogue input needs rows[].venue_name_ko");
  }
  return rows;
}

/** Catalogue venues with no registry entry, and tracked venues with nothing in the catalogue. */
export function diffCatalogue(registry, rows) {
  const tracked = new Set(registry.venues.map((venue) => venue.name_ko));
  const listed = new Set(rows.map((row) => row.venue_name_ko.trim()));
  return {
    missing: rows.filter((row) => !tracked.has(row.venue_name_ko.trim())),
    notInCatalogue: registry.venues.filter((venue) => venue.status !== "closed" && !listed.has(venue.name_ko)),
  };
}

function renderDiff({ missing, notInCatalogue }) {
  const lines = [`Catalogue venues missing from venues.json: ${missing.length}`];
  for (const row of missing) lines.push(`- ${row.venue_name_ko.trim()} (${row.city_ko ?? ""} ${row.region_ko ?? ""})`.trimEnd());
  lines.push("", `Tracked venues with no catalogue exhibition yet: ${notInCatalogue.length}`);
  for (const venue of notInCatalogue) lines.push(`- ${venue.name_ko}`);
  return `${lines.join("\n")}\n`;
}

function linkList(venue) {
  const links = LINK_KEYS.filter((key) => venue[key]).map((key) => `[${LINK_LABELS[key]}](${venue[key]})`);
  links.push(...venue.other_links.map((url, index) => `[link ${index + 1}](${url})`));
  return links.length ? links.join(" · ") : "no official channel found";
}

/** Markdown checklist of venues to sweep, least recently checked first; closed venues are left out. */
export function renderSweep(registry, { limit = Infinity, city = null, kind = null } = {}) {
  const venues = registry.venues
    .filter((venue) => venue.status !== "closed")
    .filter((venue) => city === null || venue.city_ko === city)
    .filter((venue) => kind === null || venue.kind === kind)
    .sort((a, b) => (a.last_checked ?? "").localeCompare(b.last_checked ?? "") || (a.name_ko < b.name_ko ? -1 : 1))
    .slice(0, limit);
  const lines = venues.map((venue) => {
    const place = [venue.city_ko, venue.region_ko].filter(Boolean).join(" ");
    const checked = venue.last_checked ?? "never";
    return `- [ ] **${venue.name_ko}** ${venue.name_en ? `(${venue.name_en}) ` : ""}· ${venue.kind} · ${place} · checked ${checked} — ${linkList(venue)}`;
  });
  return `${lines.join("\n")}\n`;
}

/** Returns a copy of the registry with last_checked set on the named venues; throws on unknown names. */
export function markChecked(registry, names, date) {
  if (!isCalendarDate(date)) throw new InputError("--date must be YYYY-MM-DD");
  if (!names.length) throw new InputError("name at least one venue (name_ko)");
  const known = new Set(registry.venues.map((venue) => venue.name_ko));
  const unknown = names.filter((name) => !known.has(name));
  if (unknown.length) throw new InputError(`not in venues.json: ${unknown.join(", ")}`);
  const wanted = new Set(names);
  return {
    ...registry,
    venues: registry.venues.map((venue) => (wanted.has(venue.name_ko) ? { ...venue, last_checked: date } : venue)),
  };
}

function parseFlags(argv, allowed) {
  const flags = {};
  const rest = [];
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (!arg.startsWith("--")) {
      rest.push(arg);
      continue;
    }
    const name = arg.slice(2);
    if (!allowed.includes(name) || argv[index + 1] === undefined) throw new InputError(`unexpected ${arg}`);
    flags[name] = argv[index + 1];
    index += 1;
  }
  return { flags, rest };
}

function sweepOptions(flags) {
  const options = { city: flags.city ?? null, kind: flags.kind ?? null };
  if (flags.kind !== undefined && !KINDS.includes(flags.kind)) throw new InputError(`--kind must be one of ${KINDS.join(", ")}`);
  if (flags.limit !== undefined) {
    const limit = Number(flags.limit);
    if (!Number.isInteger(limit) || limit < 1) throw new InputError("--limit must be a positive whole number");
    options.limit = limit;
  }
  return options;
}

/** Pure entry point: returns { exitCode, stdout, stderr, write } where write is new venues.json text or null. */
export function run(argv, { registryText, stdinText = "" }) {
  const [command, ...args] = argv;
  try {
    const registry = readRegistry(registryText);
    switch (command) {
      case "check": {
        parseFlags(args, []);
        return { exitCode: 0, stdout: `venues.json is valid: ${registry.venues.length} venues\n`, stderr: "", write: null };
      }
      case "diff": {
        parseFlags(args, []);
        const diff = diffCatalogue(registry, readCatalogue(stdinText));
        return { exitCode: diff.missing.length ? 1 : 0, stdout: renderDiff(diff), stderr: "", write: null };
      }
      case "sweep": {
        const { flags, rest } = parseFlags(args, ["limit", "city", "kind"]);
        if (rest.length) throw new InputError(`unexpected ${rest[0]}`);
        return { exitCode: 0, stdout: renderSweep(registry, sweepOptions(flags)), stderr: "", write: null };
      }
      case "mark-checked": {
        const { flags, rest } = parseFlags(args, ["date"]);
        const updated = markChecked(registry, rest, flags.date);
        return {
          exitCode: 0,
          stdout: `last_checked set to ${flags.date} on ${rest.length} venue(s)\n`,
          stderr: "",
          write: `${JSON.stringify(updated, null, 2)}\n`,
        };
      }
      default:
        throw new InputError(command ? `unknown command ${command}` : "missing command");
    }
  } catch (error) {
    if (!(error instanceof InputError)) throw error;
    return { exitCode: 2, stdout: "", stderr: `${error.message}\n${USAGE}\n`, write: null };
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const argv = process.argv.slice(2);
  const stdinText = argv[0] === "diff" ? readFileSync(0, "utf8") : "";
  const result = run(argv, { registryText: readFileSync(REGISTRY_URL, "utf8"), stdinText });
  if (result.write !== null) writeFileSync(REGISTRY_URL, result.write);
  process.stdout.write(result.stdout);
  process.stderr.write(result.stderr);
  process.exitCode = result.exitCode;
}
