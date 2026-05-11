// Restores the curated seed that global-setup.ts swapped out.
// Always runs, even if tests fail — Playwright invokes globalTeardown
// after the worker pool exits.

import * as fs from "fs";
import * as path from "path";

const ROOT = path.resolve(__dirname, "..");
const SEED = path.join(ROOT, "scripts", "exhibitions-seed.json");
const BACKUP = path.join(ROOT, "scripts", ".exhibitions-seed.backup.json");

export default async function globalTeardown() {
  if (fs.existsSync(BACKUP)) {
    fs.copyFileSync(BACKUP, SEED);
    fs.unlinkSync(BACKUP);
    console.log("[playwright globalTeardown] restored original exhibitions-seed.json");
  }
}
