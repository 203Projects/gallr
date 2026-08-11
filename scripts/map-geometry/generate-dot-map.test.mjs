import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { resolve } from "node:path";
import { generate, NATURAL_EARTH_REVISION } from "./generate-dot-map.mjs";

const repoRoot = resolve(import.meta.dirname, "../..");

test("checked-in geometry is deterministic and retains provenance", async () => {
  const first = await generate();
  const firstKotlin = await readFile(
    resolve(repoRoot, "shared/src/commonMain/kotlin/com/gallr/shared/map/GeneratedDotMapGeometries.kt"),
    "utf8",
  );
  const second = await generate();
  const secondKotlin = await readFile(
    resolve(repoRoot, "shared/src/commonMain/kotlin/com/gallr/shared/map/GeneratedDotMapGeometries.kt"),
    "utf8",
  );

  assert.deepEqual(second, first);
  assert.equal(secondKotlin, firstKotlin);
  assert.match(firstKotlin, new RegExp(NATURAL_EARTH_REVISION));
  assert.ok(first.find((geometry) => geometry.key === "korea").cells.length >= 100);
  assert.ok(first.find((geometry) => geometry.key === "seoul").cells.length >= 100);

  const cellChunks = [...firstKotlin.matchAll(
    /private fun \w+Cells\d+\(\): List<DotCell> = listOf\(([\s\S]*?)\n    \)/g,
  )];
  const expectedChunkCount = first.reduce(
    (count, geometry) => count + Math.ceil(geometry.cells.length / 200),
    0,
  );
  assert.equal(cellChunks.length, expectedChunkCount);
  assert.ok(cellChunks.every(([, body]) => (body.match(/DotCell\(/g) ?? []).length <= 200));
  assert.match(firstKotlin, /val korea: DotMapGeometry by lazy/);
});
