// Network-free tests for the release coverage gate. Run: node --test scripts/catalogue-coverage/
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { run } from "./coverage.mjs";

const fixture = (name) => readFileSync(new URL(`./fixtures/${name}`, import.meta.url), "utf8");
const passing = fixture("passing.json");
const belowFloor = fixture("below-floor.json");
const passingCoverage = JSON.parse(passing).rows[0].coverage;

function withCoverage(overrides) {
  return JSON.stringify({ rows: [{ coverage: { ...passingCoverage, ...overrides } }] });
}

function tableRow(stdout, label) {
  return stdout.split("\n").find((line) => line.startsWith(`| ${label} |`));
}

test("prints the release Markdown table from the query result", () => {
  const result = run(["--release", "1.15.0"], passing);

  assert.equal(result.exitCode, 0, result.stderr);
  assert.equal(result.stderr, "");
  assert.match(result.stdout, /^### Catalogue coverage · 1\.15\.0$/m);
  assert.match(result.stdout, /^Running and upcoming exhibitions on 2026-10-28: \*\*120\*\*$/m);
  assert.match(result.stdout, /^\| Measure \| Count \| Share \| Floor \| Result \|$/m);
  assert.match(result.stdout, /^\|---\|---:\|---:\|---:\|---\|$/m);
  assert.equal(
    tableRow(result.stdout, "≥3 reviewed terms, published more than 7 days ago"),
    "| ≥3 reviewed terms, published more than 7 days ago | 80 / 80 | 100.0% | ≥ 100% | pass |",
  );
  assert.equal(
    tableRow(result.stdout, "≥3 reviewed terms, all"),
    "| ≥3 reviewed terms, all | 110 / 120 | 91.6% | ≥ 90% | pass |",
  );
  assert.match(result.stdout, /^\*\*Floor: pass\*\*$/m);
});

test("reports credits, hours, coordinates, dates, English, stale and duplicate counts", () => {
  const { stdout } = run(["--release", "1.15.0"], passing);

  assert.equal(
    tableRow(stdout, "Artist linked where the credits name one"),
    "| Artist linked where the credits name one | 50 / 60 | 83.3% | — | reported |",
  );
  assert.equal(tableRow(stdout, "Confirmed weekly hours"), "| Confirmed weekly hours | 30 / 120 | 25.0% | — | reported |");
  assert.equal(tableRow(stdout, "Coordinates"), "| Coordinates | 118 / 120 | 98.3% | — | reported |");
  assert.equal(tableRow(stdout, "English description"), "| English description | 96 / 120 | 80.0% | — | reported |");
  assert.equal(
    tableRow(stdout, "Listed exhibitions with current dates"),
    "| Listed exhibitions with current dates | 120 / 125 | 96.0% | — | reported |",
  );
  assert.equal(tableRow(stdout, "Stale terms or credits"), "| Stale terms or credits | 3 | — | — | needs review |");
  assert.equal(tableRow(stdout, "Stale confirmed hours"), "| Stale confirmed hours | 1 | — | — | needs review |");
  assert.equal(tableRow(stdout, "Duplicate artist groups"), "| Duplicate artist groups | 2 | — | — | needs merge |");
});

test("exits 1 below --floor-week", () => {
  const result = run(
    ["--release", "1.15.0", "--floor-week", "1.0", "--floor-all", "0.9"],
    withCoverage({ published_week_ago_terms_3plus: 79 }),
  );

  assert.equal(result.exitCode, 1);
  assert.equal(
    tableRow(result.stdout, "≥3 reviewed terms, published more than 7 days ago"),
    "| ≥3 reviewed terms, published more than 7 days ago | 79 / 80 | 98.7% | ≥ 100% | **below floor** |",
  );
  assert.match(result.stdout, /^\*\*Floor: below floor\*\*$/m);
  assert.match(result.stderr, /below the coverage floor/);
});

test("exits 1 below --floor-all", () => {
  const result = run(["--release", "1.15.0", "--floor-all", "0.95"], passing);

  assert.equal(result.exitCode, 1);
  assert.equal(
    tableRow(result.stdout, "≥3 reviewed terms, all"),
    "| ≥3 reviewed terms, all | 110 / 120 | 91.6% | ≥ 95% | **below floor** |",
  );
});

test("a share exactly at the floor passes and a share just under it fails", () => {
  assert.equal(run(["--release", "1.15.0", "--floor-all", "0.9"], withCoverage({ terms_3plus: 108 })).exitCode, 0);
  assert.equal(run(["--release", "1.15.0", "--floor-all", "0.9"], withCoverage({ terms_3plus: 107 })).exitCode, 1);
});

test("defaults to the spec floors of 100% for week-old listings and 90% overall", () => {
  const result = run(["--release", "1.15.0"], belowFloor);

  assert.equal(result.exitCode, 1);
  assert.match(tableRow(result.stdout, "≥3 reviewed terms, published more than 7 days ago"), /≥ 100% \| \*\*below floor\*\* \|$/);
  assert.match(tableRow(result.stdout, "≥3 reviewed terms, all"), /100 \/ 120 \| 83\.3% \| ≥ 90% \| \*\*below floor\*\* \|$/);
});

test("accepts a bare row array and a coverage column returned as JSON text", () => {
  const result = run(["--release", "1.15.0", "--floor-week", "0", "--floor-all", "0"], belowFloor);

  assert.equal(result.exitCode, 0, result.stderr);
  assert.match(result.stdout, /\| Stale terms or credits \| 12 \|/);
});

test("no listing older than a week passes the week floor without dividing by zero", () => {
  const result = run(
    ["--release", "1.15.0"],
    withCoverage({ published_week_ago: 0, published_week_ago_terms_3plus: 0 }),
  );

  assert.equal(result.exitCode, 0, result.stderr);
  assert.equal(
    tableRow(result.stdout, "≥3 reviewed terms, published more than 7 days ago"),
    "| ≥3 reviewed terms, published more than 7 days ago | 0 / 0 | — | ≥ 100% | pass |",
  );
});

test("malformed or inconsistent results stop with exit 2", () => {
  const cases = [
    "not json",
    "{}",
    JSON.stringify({ rows: [] }),
    JSON.stringify({ rows: [{ coverage: null }] }),
    JSON.stringify({ rows: [{ coverage: passingCoverage }, { coverage: passingCoverage }] }),
    withCoverage({ format_version: 2 }),
    withCoverage({ terms_3plus: -1 }),
    withCoverage({ coordinates: 1.5 }),
    withCoverage({ hours_confirmed: "30" }),
    withCoverage({ english_description: undefined }),
    withCoverage({ terms_3plus: 121 }),
    withCoverage({ published_week_ago: 121 }),
    withCoverage({ published_week_ago_terms_3plus: 81 }),
    withCoverage({ credits_named_linked: 61 }),
    withCoverage({ listed_current_dates: 126 }),
    withCoverage({ running_upcoming: 0, terms_3plus: 0, published_week_ago: 0, published_week_ago_terms_3plus: 0 }),
    withCoverage({ today: "28/10/2026" }),
  ];

  for (const input of cases) {
    const result = run(["--release", "1.15.0"], input);
    assert.equal(result.exitCode, 2, input);
    assert.equal(result.stdout, "", input);
    assert.notEqual(result.stderr, "", input);
  }
});

test("invalid arguments stop with exit 2", () => {
  const cases = [
    [],
    ["--release"],
    ["--release", ""],
    ["--release", "1.15.0", "--floor-week", "1.5"],
    ["--release", "1.15.0", "--floor-all", "-0.1"],
    ["--release", "1.15.0", "--floor-all", "ninety"],
    ["--release", "1.15.0", "--floor-all"],
    ["--release", "1.15.0", "--unknown", "1"],
  ];

  for (const argv of cases) {
    const result = run(argv, passing);
    assert.equal(result.exitCode, 2, argv.join(" "));
    assert.match(result.stderr, /Usage:/, argv.join(" "));
  }
});

test("the command reads stdin and sets its exit status", () => {
  const script = fileURLToPath(new URL("./coverage.mjs", import.meta.url));
  const pass = spawnSync(process.execPath, [script, "--release", "1.15.0"], { input: passing, encoding: "utf8" });
  const fail = spawnSync(process.execPath, [script, "--release", "1.15.0"], { input: belowFloor, encoding: "utf8" });

  assert.equal(pass.status, 0, pass.stderr);
  assert.match(pass.stdout, /\*\*Floor: pass\*\*/);
  assert.equal(fail.status, 1);
  assert.match(fail.stdout, /\*\*Floor: below floor\*\*/);
});
