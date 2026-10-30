// Release coverage gate (spec 101 FR-024). Reads the JSON that
// `supabase db query --output-format json -f coverage.sql` prints on stdin, writes a Markdown table for the
// release pull request, and exits 1 when reviewed-term coverage is below a floor (2 on bad input).
// Network-free and dependency-free: the query is run separately by the owner.
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const FORMAT_VERSION = 1;
const DEFAULT_FLOORS = Object.freeze({ week: 1.0, all: 0.9 });
const COUNT_KEYS = Object.freeze([
  "running_upcoming",
  "terms_3plus",
  "published_week_ago",
  "published_week_ago_terms_3plus",
  "credits_named",
  "credits_named_linked",
  "hours_confirmed",
  "coordinates",
  "english_description",
  "metadata_stale",
  "hours_stale",
  "listed_total",
  "listed_current_dates",
  "duplicate_artist_groups",
]);
/** [part, whole]: each part is a subset of its whole. */
const SUBSETS = Object.freeze([
  ["terms_3plus", "running_upcoming"],
  ["published_week_ago", "running_upcoming"],
  ["published_week_ago_terms_3plus", "published_week_ago"],
  ["published_week_ago_terms_3plus", "terms_3plus"],
  ["credits_named", "running_upcoming"],
  ["credits_named_linked", "credits_named"],
  ["hours_confirmed", "running_upcoming"],
  ["coordinates", "running_upcoming"],
  ["english_description", "running_upcoming"],
  ["metadata_stale", "running_upcoming"],
  ["hours_stale", "running_upcoming"],
  ["running_upcoming", "listed_total"],
  ["listed_current_dates", "listed_total"],
]);
// A floor is compared as a ratio; this absorbs binary rounding of values such as 0.9 × 120.
const FLOOR_TOLERANCE = 1e-9;

const USAGE =
  "Usage: supabase db query --linked --project-ref <ref> --output-format json -f coverage.sql < /dev/null \\\n" +
  "  | node coverage.mjs --release <version> [--floor-week <0..1>] [--floor-all <0..1>]";

class InputError extends Error {}

function requireInput(condition, message) {
  if (!condition) throw new InputError(message);
}

/** Parses `--release`, `--floor-week` and `--floor-all`; throws InputError on anything else. */
export function parseArgs(argv) {
  const options = { release: null, floors: { ...DEFAULT_FLOORS } };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    requireInput(value !== undefined, `Missing a value for ${flag}.`);
    switch (flag) {
      case "--release":
        requireInput(/^\S{1,40}$/u.test(value), "The release must be a non-empty version without spaces.");
        options.release = value;
        break;
      case "--floor-week":
        options.floors.week = floorValue(flag, value);
        break;
      case "--floor-all":
        options.floors.all = floorValue(flag, value);
        break;
      default:
        throw new InputError(`Unknown option ${flag}.`);
    }
  }
  requireInput(options.release !== null, "--release is required.");
  return options;
}

function floorValue(flag, value) {
  const floor = /^\d+(\.\d+)?$/u.test(value) ? Number(value) : Number.NaN;
  requireInput(floor >= 0 && floor <= 1, `${flag} must be a share between 0 and 1.`);
  return floor;
}

/** Extracts and validates the single `coverage` object from the query output. */
export function readCoverage(text) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new InputError("The query output is not JSON.");
  }
  const rows = Array.isArray(parsed) ? parsed : parsed?.rows;
  requireInput(Array.isArray(rows) && rows.length === 1, "Expected exactly one result row.");
  let coverage = rows[0]?.coverage;
  if (typeof coverage === "string") {
    try {
      coverage = JSON.parse(coverage);
    } catch {
      throw new InputError("The coverage column is not JSON.");
    }
  }
  requireInput(coverage !== null && typeof coverage === "object" && !Array.isArray(coverage), "Missing coverage object.");
  requireInput(coverage.format_version === FORMAT_VERSION, `Expected coverage format_version ${FORMAT_VERSION}.`);
  requireInput(
    typeof coverage.today === "string" && /^\d{4}-\d{2}-\d{2}$/u.test(coverage.today),
    "Missing the catalogue date.",
  );
  for (const key of COUNT_KEYS) {
    requireInput(Number.isSafeInteger(coverage[key]) && coverage[key] >= 0, `Invalid count ${key}.`);
  }
  for (const [part, whole] of SUBSETS) {
    requireInput(coverage[part] <= coverage[whole], `${part} exceeds ${whole}.`);
  }
  requireInput(coverage.running_upcoming > 0, "No running or upcoming exhibitions; check the target project.");
  return coverage;
}

function meetsFloor(part, whole, floor) {
  return whole === 0 || part / whole >= floor - FLOOR_TOLERANCE;
}

/** Rounds down so a share just under a floor never prints as the floor itself. */
function share(part, whole) {
  return whole === 0 ? "—" : `${(Math.floor((part * 1000) / whole) / 10).toFixed(1)}%`;
}

function percent(floor) {
  return `${Number((floor * 100).toFixed(1))}%`;
}

function measuredRow(label, part, whole, floor) {
  if (floor === undefined) return [label, `${part} / ${whole}`, share(part, whole), "—", "reported"];
  const result = meetsFloor(part, whole, floor) ? "pass" : "**below floor**";
  return [label, `${part} / ${whole}`, share(part, whole), `≥ ${percent(floor)}`, result];
}

function countRow(label, count, action) {
  return [label, String(count), "—", "—", count === 0 ? "none" : action];
}

/** Applies the floors; returns the table rows and whether the release may proceed. */
export function evaluate(coverage, floors) {
  const c = coverage;
  const passes =
    meetsFloor(c.published_week_ago_terms_3plus, c.published_week_ago, floors.week) &&
    meetsFloor(c.terms_3plus, c.running_upcoming, floors.all);
  const rows = [
    measuredRow(
      "≥3 reviewed terms, published more than 7 days ago",
      c.published_week_ago_terms_3plus,
      c.published_week_ago,
      floors.week,
    ),
    measuredRow("≥3 reviewed terms, all", c.terms_3plus, c.running_upcoming, floors.all),
    measuredRow("Artist linked where the credits name one", c.credits_named_linked, c.credits_named),
    measuredRow("Confirmed weekly hours", c.hours_confirmed, c.running_upcoming),
    measuredRow("Coordinates", c.coordinates, c.running_upcoming),
    measuredRow("English description", c.english_description, c.running_upcoming),
    measuredRow("Listed exhibitions with current dates", c.listed_current_dates, c.listed_total),
    countRow("Stale terms or credits", c.metadata_stale, "needs review"),
    countRow("Stale confirmed hours", c.hours_stale, "needs review"),
    countRow("Duplicate artist groups", c.duplicate_artist_groups, "needs merge"),
  ];
  return { passes, rows };
}

export function renderMarkdown(release, coverage, evaluation) {
  const lines = [
    `### Catalogue coverage · ${release}`,
    "",
    `Running and upcoming exhibitions on ${coverage.today}: **${coverage.running_upcoming}**`,
    "",
    "| Measure | Count | Share | Floor | Result |",
    "|---|---:|---:|---:|---|",
    ...evaluation.rows.map((cells) => `| ${cells.join(" | ")} |`),
    "",
    `**Floor: ${evaluation.passes ? "pass" : "below floor"}**`,
    "",
  ];
  return lines.join("\n");
}

/** Pure entry point: returns what the command prints and its exit code. */
export function run(argv, stdinText) {
  try {
    const options = parseArgs(argv);
    const coverage = readCoverage(stdinText);
    const evaluation = evaluate(coverage, options.floors);
    return {
      exitCode: evaluation.passes ? 0 : 1,
      stdout: renderMarkdown(options.release, coverage, evaluation),
      stderr: evaluation.passes ? "" : "Reviewed-term coverage is below the coverage floor; the release must not proceed.\n",
    };
  } catch (error) {
    if (!(error instanceof InputError)) throw error;
    return { exitCode: 2, stdout: "", stderr: `${error.message}\n${USAGE}\n` };
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const result = run(process.argv.slice(2), readFileSync(0, "utf8"));
  process.stdout.write(result.stdout);
  process.stderr.write(result.stderr);
  process.exitCode = result.exitCode;
}
