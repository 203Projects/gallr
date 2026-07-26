const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");

const { cleanDist } = require("../scripts/clean-dist.js");

const root = fs.mkdtempSync(path.join(os.tmpdir(), "gallr-clean-dist-"));
const dist = path.join(root, "dist");
const retained = path.join(root, "retained.txt");

try {
  fs.mkdirSync(path.join(dist, "exhibitions", "stale-page"), { recursive: true });
  fs.writeFileSync(path.join(dist, "exhibitions", "stale-page", "index.html"), "stale");
  fs.writeFileSync(retained, "keep");

  cleanDist(dist, root);

  assert.equal(fs.existsSync(dist), false, "generated output is removed");
  assert.equal(fs.readFileSync(retained, "utf8"), "keep", "sibling source files are preserved");

  const outside = path.join(root, "outside");
  fs.mkdirSync(outside);
  fs.symlinkSync(outside, dist, "dir");
  assert.throws(
    () => cleanDist(dist, root),
    /refusing to clean symlinked output directory/,
    "the cleaner refuses a symlinked dist directory"
  );
  assert.equal(fs.existsSync(outside), true, "the symlink target is preserved");
  fs.unlinkSync(dist);

  assert.throws(
    () => cleanDist(path.join(root, "other"), root),
    /refusing to clean unexpected output directory/,
    "the cleaner refuses any target other than the exact dist directory"
  );

  console.log("[clean-dist.test] all tests passed");
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}
