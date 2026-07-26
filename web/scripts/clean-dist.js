const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const DIST = path.join(ROOT, "dist");

function cleanDist(outputDir = DIST, rootDir = ROOT) {
  const resolvedRoot = path.resolve(rootDir);
  const resolvedOutput = path.resolve(outputDir);
  const expectedOutput = path.join(resolvedRoot, "dist");

  if (resolvedOutput !== expectedOutput) {
    throw new Error(`refusing to clean unexpected output directory: ${resolvedOutput}`);
  }

  if (fs.existsSync(resolvedOutput) && fs.lstatSync(resolvedOutput).isSymbolicLink()) {
    throw new Error(`refusing to clean symlinked output directory: ${resolvedOutput}`);
  }

  fs.rmSync(resolvedOutput, { recursive: true, force: true });
}

if (require.main === module) {
  cleanDist();
}

module.exports = { cleanDist };
