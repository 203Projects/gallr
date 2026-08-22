#!/usr/bin/env node
// Read-only mirror parity check for the Seoul -> Singapore compatibility bridge.
//
// Compares the public mobile reader contracts on both projects and exits
// non-zero when the compatibility catalogue has fallen behind. Designed for a
// scheduled watchdog: it prints a single-line verdict and never writes.
//
// The August 2026 outage stayed invisible for five days because a guarded apply
// failed silently every five minutes. This is the check that would have caught
// it on the first pass.
//
// Usage (credentials come from the matching 1Password items):
//
//   env \
//     GALLR_SEOUL_SUPABASE_URL='op://DEV/gallr-korea-server/hostname' \
//     GALLR_SEOUL_SECRET_KEY='op://DEV/gallr-korea-server/credential' \
//     GALLR_LEGACY_SUPABASE_URL='op://DEV/gallr-production-server/hostname' \
//     GALLR_LEGACY_SECRET_KEY='op://DEV/gallr-production-server/credential' \
//     op run -- node scripts/legacy-mobile-mirror/check-mirror-parity.mjs
//
// Exit codes: 0 = in sync, 1 = drift detected, 2 = check could not run.

import { fileURLToPath } from "node:url";

export const SEOUL_PROJECT_REF = "oqrvbstopuppznxqoonp";
export const LEGACY_PROJECT_REF = "yhuhjxswjbrtmbpbrciq";

/** Maximum acceptable lag before the mirror is considered stale. */
export const MAX_LAG_MINUTES = 30;

const RESOURCES = Object.freeze([
  "exhibitions",
  "exhibition_catalog_v2",
  "events",
  "editors",
]);

function required(env, name) {
  const value = env[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

/** Rejects any URL that is not the exact reviewed project origin. */
export function reviewedProjectUrl(value, expectedRef) {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error("A project URL is required.");
  }
  // Vault items store the host with or without a scheme. Normalize a bare
  // hostname to https rather than rejecting it, but never downgrade an
  // explicit scheme — an http:// value must still fail closed below.
  const raw = value.trim();
  const candidate = /^[a-z][a-z0-9+.-]*:\/\//i.test(raw)
    ? raw
    : `https://${raw}`;

  let parsed;
  try {
    parsed = new URL(candidate);
  } catch {
    throw new Error("Refusing to contact an unreviewed project host.");
  }
  const expected = `${expectedRef}.supabase.co`;
  if (parsed.protocol !== "https:" || parsed.hostname !== expected) {
    throw new Error(`Refusing to contact an unreviewed project host.`);
  }
  return `${parsed.protocol}//${parsed.hostname}`;
}

async function countRows(fetchImpl, baseUrl, key, resource) {
  const url = new URL(`/rest/v1/${resource}`, baseUrl);
  url.searchParams.set("select", "id");
  const response = await fetchImpl(url, {
    headers: {
      apikey: key,
      authorization: `Bearer ${key}`,
      prefer: "count=exact",
      range: "0-0",
    },
  });
  if (!response.ok && response.status !== 206) {
    throw new Error(`${resource} count failed with HTTP ${response.status}.`);
  }
  const range = response.headers.get("content-range") ?? "";
  const total = Number(range.split("/").at(-1));
  if (!Number.isFinite(total)) {
    throw new Error(`${resource} count response was malformed.`);
  }
  return total;
}

async function newestUpdatedAt(fetchImpl, baseUrl, key) {
  const url = new URL("/rest/v1/exhibitions", baseUrl);
  url.searchParams.set("select", "updated_at");
  url.searchParams.set("order", "updated_at.desc");
  url.searchParams.set("limit", "1");
  const response = await fetchImpl(url, {
    headers: { apikey: key, authorization: `Bearer ${key}` },
  });
  if (!response.ok) {
    throw new Error(`freshness probe failed with HTTP ${response.status}.`);
  }
  const rows = await response.json();
  if (!Array.isArray(rows) || rows.length === 0) return null;
  const value = Date.parse(rows[0]?.updated_at ?? "");
  return Number.isFinite(value) ? value : null;
}

/** Pure comparison so the verdict logic is unit-testable without network. */
export function buildVerdict({ counts, sourceNewest, targetNewest, now }) {
  const drifted = RESOURCES.filter(
    (resource) => counts[resource].source !== counts[resource].target,
  );
  const lagMinutes = sourceNewest && targetNewest
    ? Math.max(0, Math.round((sourceNewest - targetNewest) / 60000))
    : null;
  const stale = lagMinutes !== null && lagMinutes > MAX_LAG_MINUTES;
  const ageMinutes = targetNewest !== null && now
    ? Math.max(0, Math.round((now - targetNewest) / 60000))
    : null;

  return {
    inSync: drifted.length === 0 && !stale,
    drifted,
    lagMinutes,
    targetAgeMinutes: ageMinutes,
    counts,
  };
}

export async function checkParity(env = process.env, fetchImpl = fetch) {
  const sourceUrl = reviewedProjectUrl(
    required(env, "GALLR_SEOUL_SUPABASE_URL"),
    SEOUL_PROJECT_REF,
  );
  const targetUrl = reviewedProjectUrl(
    required(env, "GALLR_LEGACY_SUPABASE_URL"),
    LEGACY_PROJECT_REF,
  );
  const sourceKey = required(env, "GALLR_SEOUL_SECRET_KEY");
  const targetKey = required(env, "GALLR_LEGACY_SECRET_KEY");

  const counts = {};
  for (const resource of RESOURCES) {
    const [source, target] = await Promise.all([
      countRows(fetchImpl, sourceUrl, sourceKey, resource),
      countRows(fetchImpl, targetUrl, targetKey, resource),
    ]);
    counts[resource] = { source, target };
  }

  const [sourceNewest, targetNewest] = await Promise.all([
    newestUpdatedAt(fetchImpl, sourceUrl, sourceKey),
    newestUpdatedAt(fetchImpl, targetUrl, targetKey),
  ]);

  return buildVerdict({
    counts,
    sourceNewest,
    targetNewest,
    now: Date.now(),
  });
}

async function main() {
  let verdict;
  try {
    verdict = await checkParity();
  } catch (error) {
    console.error(`MIRROR CHECK FAILED: ${error.message}`);
    process.exitCode = 2;
    return;
  }

  if (verdict.inSync) {
    // Silent-success shape: a watchdog should stay quiet when healthy.
    console.log(
      `MIRROR OK: Singapore matches gallr-korea ` +
        `(lag ${verdict.lagMinutes ?? "n/a"}m)`,
    );
    return;
  }

  const detail = verdict.drifted
    .map((resource) =>
      `${resource} korea=${verdict.counts[resource].source} ` +
      `singapore=${verdict.counts[resource].target}`
    )
    .join("; ");

  console.error(
    `MIRROR DRIFT: installed mobile clients are being served stale data. ` +
      `${detail || "row counts match"}. ` +
      `lag=${verdict.lagMinutes ?? "unknown"}m ` +
      `target_last_change=${verdict.targetAgeMinutes ?? "unknown"}m ago. ` +
      `Check dead-lettered legacy_catalog.sync_requested outbox rows.`,
  );
  process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  await main();
}
