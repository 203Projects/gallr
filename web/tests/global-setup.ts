// Runs once before any Playwright project starts.
// Swaps the curated seed for the test fixture, then rebuilds dist/ so the
// static file server (webServer) sees deterministic exhibitions data.
//
// Restores the original seed via global-teardown.ts.

import * as fs from "fs";
import * as path from "path";
import { execSync } from "child_process";

const ROOT = path.resolve(__dirname, "..");
const SEED = path.join(ROOT, "scripts", "exhibitions-seed.json");
const BACKUP = path.join(ROOT, "scripts", ".exhibitions-seed.backup.json");
const FIXTURE = path.join(ROOT, "tests", "fixtures", "exhibitions.json");

export default async function globalSetup() {
  if (!fs.existsSync(FIXTURE)) {
    throw new Error(`[playwright globalSetup] missing fixture: ${FIXTURE}`);
  }
  // Snapshot whatever seed currently exists so teardown can restore it.
  if (fs.existsSync(SEED)) {
    fs.copyFileSync(SEED, BACKUP);
  }
  const fx = JSON.parse(fs.readFileSync(FIXTURE, "utf8"));
  const seed = {
    fetchedAt: new Date().toISOString(),
    source: "test-fixture",
    exhibitions: fx.exhibitions,
  };
  fs.writeFileSync(SEED, JSON.stringify(seed, null, 2));
  console.log(
    `[playwright globalSetup] swapped exhibitions-seed.json with ${fx.exhibitions.length} fixture rows`
  );
  // Rebuild dist/ against the fixture seed before webServer starts serving.
  execSync("npm run build", {
    cwd: ROOT,
    stdio: "inherit",
    env: { ...process.env, GALLR_TEST_TODAY: "2026-05-10" },
  });
}
