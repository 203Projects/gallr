#!/usr/bin/env node

import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, "../..");
const sourceDir = resolve(scriptDir, "source");
const resourceDir = resolve(repoRoot, "composeApp/src/commonMain/composeResources/files/map_geometry");
const kotlinOutput = resolve(
  repoRoot,
  "shared/src/commonMain/kotlin/com/gallr/shared/map/GeneratedDotMapGeometries.kt",
);

export const NATURAL_EARTH_REVISION = "ca96624a56bd078437bca8184e78163e5039ad19";
const RAW_ROOT = `https://raw.githubusercontent.com/nvkelso/natural-earth-vector/${NATURAL_EARTH_REVISION}/geojson`;

const definitions = [
  {
    key: "korea",
    sourceFile: "korea-admin0.geojson",
    remoteFile: "ne_10m_admin_0_countries.geojson",
    select: (feature) => feature.properties?.ADM0_A3 === "KOR",
    // Country scope is a mobile visual overview. A deliberately sparse grid
    // keeps the Korea silhouette readable while leaving semantic markers large.
    columns: 31,
    rows: 43,
    river: false,
  },
  {
    key: "seoul",
    sourceFile: "seoul-admin1.geojson",
    remoteFile: "ne_10m_admin_1_states_provinces.geojson",
    select: (feature) => feature.properties?.adm0_a3 === "KOR" && feature.properties?.name_en === "Seoul",
    columns: 49,
    rows: 37,
    river: true,
  },
];

async function fetchFeature(definition) {
  const response = await fetch(`${RAW_ROOT}/${definition.remoteFile}`);
  if (!response.ok) throw new Error(`Natural Earth fetch failed: ${response.status}`);
  const collection = await response.json();
  const feature = collection.features.find(definition.select);
  if (!feature) throw new Error(`Natural Earth feature not found: ${definition.key}`);
  return {
    type: "Feature",
    properties: {
      source: "Natural Earth 1:10m",
      source_revision: NATURAL_EARTH_REVISION,
      source_file: definition.remoteFile,
      name: feature.properties?.name_en ?? feature.properties?.ADMIN ?? definition.key,
    },
    geometry: feature.geometry,
  };
}

async function loadFeature(definition, refreshSource) {
  const sourcePath = resolve(sourceDir, definition.sourceFile);
  if (refreshSource) {
    const feature = await fetchFeature(definition);
    await mkdir(sourceDir, { recursive: true });
    await writeFile(sourcePath, `${JSON.stringify(feature)}\n`);
    return feature;
  }
  return JSON.parse(await readFile(sourcePath, "utf8"));
}

function coordinateRings(geometry) {
  if (geometry.type === "Polygon") return [geometry.coordinates];
  if (geometry.type === "MultiPolygon") return geometry.coordinates;
  throw new Error(`Unsupported geometry type: ${geometry.type}`);
}

function boundsOf(geometry) {
  let west = Infinity;
  let east = -Infinity;
  let south = Infinity;
  let north = -Infinity;
  for (const polygon of coordinateRings(geometry)) {
    for (const ring of polygon) {
      for (const [longitude, latitude] of ring) {
        west = Math.min(west, longitude);
        east = Math.max(east, longitude);
        south = Math.min(south, latitude);
        north = Math.max(north, latitude);
      }
    }
  }
  return { north, east, south, west };
}

function pointInRing(longitude, latitude, ring) {
  let inside = false;
  for (let i = 0, previous = ring.length - 1; i < ring.length; previous = i++) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[previous];
    const intersects = yi > latitude !== yj > latitude &&
      longitude < ((xj - xi) * (latitude - yi)) / (yj - yi) + xi;
    if (intersects) inside = !inside;
  }
  return inside;
}

function pointInGeometry(longitude, latitude, geometry) {
  return coordinateRings(geometry).some((polygon) => {
    if (!pointInRing(longitude, latitude, polygon[0])) return false;
    return polygon.slice(1).every((hole) => !pointInRing(longitude, latitude, hole));
  });
}

function isSeoulRiverGap(x, y) {
  const center = 0.57 + 0.055 * Math.sin((x * 2.2 - 0.25) * Math.PI);
  return Math.abs(y - center) < 0.035;
}

export function sampleFeature(definition, feature) {
  const bounds = boundsOf(feature.geometry);
  const cells = [];
  for (let row = 0; row < definition.rows; row += 1) {
    const y = row / (definition.rows - 1);
    for (let column = 0; column < definition.columns; column += 1) {
      const x = column / (definition.columns - 1);
      const longitude = bounds.west + x * (bounds.east - bounds.west);
      const latitude = bounds.north - y * (bounds.north - bounds.south);
      if (!pointInGeometry(longitude, latitude, feature.geometry)) continue;
      if (definition.river && isSeoulRiverGap(x, y)) continue;
      cells.push({
        id: `${definition.key}-${String(cells.length).padStart(4, "0")}`,
        x: Number(x.toFixed(6)),
        y: Number(y.toFixed(6)),
      });
    }
  }
  if (cells.length < 40) throw new Error(`Generated ${definition.key} geometry is unexpectedly sparse`);
  return { key: definition.key, version: 1, bounds, cells };
}

function geometryJson(geometry, sourceFeature) {
  return `${JSON.stringify({
    source: sourceFeature.properties,
    source_sha256: createHash("sha256").update(JSON.stringify(sourceFeature)).digest("hex"),
    ...geometry,
  }, null, 2)}\n`;
}

function kotlinNumber(value) {
  const formatted = Number(value).toFixed(6).replace(/0+$/, "").replace(/\.$/, ".0");
  return formatted.includes(".") ? formatted : `${formatted}.0`;
}

const KOTLIN_CELL_CHUNK_SIZE = 200;

function kotlinSource(geometries) {
  const bodies = geometries.map((geometry) => {
    const cellChunks = [];
    for (let start = 0; start < geometry.cells.length; start += KOTLIN_CELL_CHUNK_SIZE) {
      cellChunks.push(geometry.cells.slice(start, start + KOTLIN_CELL_CHUNK_SIZE));
    }
    const chunkFunctions = cellChunks.map((chunk, index) => {
      const cells = chunk.map((cell) =>
        `        DotCell("${cell.id}", NormalizedPoint(${kotlinNumber(cell.x)}, ${kotlinNumber(cell.y)})),`
      ).join("\n");
      return `    private fun ${geometry.key}Cells${index}(): List<DotCell> = listOf(\n` +
        cells + "\n    )";
    }).join("\n\n");
    const chunkCalls = cellChunks.map((_, index) =>
      `            addAll(${geometry.key}Cells${index}())`
    ).join("\n");
    return `    val ${geometry.key}: DotMapGeometry by lazy { ${geometry.key}Geometry() }\n\n` +
      `    private fun ${geometry.key}Geometry(): DotMapGeometry = DotMapGeometry(\n` +
      `        key = "${geometry.key}",\n` +
      "        version = 1,\n" +
      "        bounds = GeoBounds(\n" +
      `            north = ${kotlinNumber(geometry.bounds.north)},\n` +
      `            east = ${kotlinNumber(geometry.bounds.east)},\n` +
      `            south = ${kotlinNumber(geometry.bounds.south)},\n` +
      `            west = ${kotlinNumber(geometry.bounds.west)},\n` +
      "        ),\n" +
      "        cells = buildList {\n" + chunkCalls + "\n        },\n    )\n\n" +
      chunkFunctions;
  }).join("\n\n");

  return `// Generated by scripts/map-geometry/generate-dot-map.mjs. Do not edit by hand.\n` +
    `// Natural Earth revision: ${NATURAL_EARTH_REVISION} (public domain).\n` +
    "package com.gallr.shared.map\n\n" +
    "import com.gallr.shared.data.model.map.DotCell\n" +
    "import com.gallr.shared.data.model.map.DotMapGeometry\n" +
    "import com.gallr.shared.data.model.map.GeoBounds\n" +
    "import com.gallr.shared.data.model.map.NormalizedPoint\n\n" +
    "object GeneratedDotMapGeometries {\n" + bodies + "\n\n" +
    "    fun geometry(key: String): DotMapGeometry? = when (key) {\n" +
    "        korea.key -> korea\n" +
    "        seoul.key -> seoul\n" +
    "        else -> null\n" +
    "    }\n" +
    "}\n";
}

export async function generate({ refreshSource = false } = {}) {
  const outputs = [];
  await mkdir(resourceDir, { recursive: true });
  for (const definition of definitions) {
    const feature = await loadFeature(definition, refreshSource);
    const geometry = sampleFeature(definition, feature);
    outputs.push(geometry);
    await writeFile(resolve(resourceDir, `${definition.key}.json`), geometryJson(geometry, feature));
  }
  await mkdir(dirname(kotlinOutput), { recursive: true });
  await writeFile(kotlinOutput, kotlinSource(outputs));
  return outputs;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const outputs = await generate({ refreshSource: process.argv.includes("--refresh-source") });
  for (const output of outputs) {
    process.stdout.write(`${output.key}: ${output.cells.length} cells\n`);
  }
}
