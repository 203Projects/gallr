import assert from "node:assert/strict";
import test from "node:test";

import {
  buildVerdict,
  LEGACY_PROJECT_REF,
  MAX_LAG_MINUTES,
  reviewedProjectUrl,
  SEOUL_PROJECT_REF,
} from "./check-mirror-parity.mjs";

const NOW = Date.parse("2026-08-19T12:00:00Z");

function counts(overrides = {}) {
  const base = {
    exhibitions: { source: 336, target: 336 },
    exhibition_catalog_v2: { source: 336, target: 336 },
    events: { source: 3, target: 3 },
    editors: { source: 5, target: 5 },
  };
  return { ...base, ...overrides };
}

test("matching catalogues within the freshness window are in sync", () => {
  const verdict = buildVerdict({
    counts: counts(),
    sourceNewest: NOW - 60_000,
    targetNewest: NOW - 120_000,
    now: NOW,
  });
  assert.equal(verdict.inSync, true);
  assert.deepEqual(verdict.drifted, []);
  assert.equal(verdict.lagMinutes, 1);
});

test("a row-count shortfall is reported as drift", () => {
  // The exact August 2026 shape: Singapore eight exhibitions behind.
  const verdict = buildVerdict({
    counts: counts({
      exhibitions: { source: 336, target: 328 },
      exhibition_catalog_v2: { source: 336, target: 328 },
    }),
    sourceNewest: NOW,
    targetNewest: NOW,
    now: NOW,
  });
  assert.equal(verdict.inSync, false);
  assert.deepEqual(verdict.drifted, ["exhibitions", "exhibition_catalog_v2"]);
});

test("a stale target is reported even when row counts match", () => {
  // A frozen mirror can still have equal counts while updates stop flowing.
  const verdict = buildVerdict({
    counts: counts(),
    sourceNewest: NOW,
    targetNewest: NOW - (MAX_LAG_MINUTES + 5) * 60_000,
    now: NOW,
  });
  assert.equal(verdict.inSync, false);
  assert.ok(verdict.lagMinutes > MAX_LAG_MINUTES);
});

test("lag inside the tolerance does not raise a false alarm", () => {
  const verdict = buildVerdict({
    counts: counts(),
    sourceNewest: NOW,
    targetNewest: NOW - (MAX_LAG_MINUTES - 1) * 60_000,
    now: NOW,
  });
  assert.equal(verdict.inSync, true);
});

test("only the exact reviewed project hosts are accepted", () => {
  assert.equal(
    reviewedProjectUrl(
      `https://${SEOUL_PROJECT_REF}.supabase.co/`,
      SEOUL_PROJECT_REF,
    ),
    `https://${SEOUL_PROJECT_REF}.supabase.co`,
  );

  assert.throws(
    () => reviewedProjectUrl("https://evil.example.com", SEOUL_PROJECT_REF),
    /unreviewed project host/,
  );

  // Guards against swapping the pair: the target ref is not the source ref.
  assert.throws(
    () =>
      reviewedProjectUrl(
        `https://${LEGACY_PROJECT_REF}.supabase.co`,
        SEOUL_PROJECT_REF,
      ),
    /unreviewed project host/,
  );

  assert.throws(
    () => reviewedProjectUrl(`http://${SEOUL_PROJECT_REF}.supabase.co`, SEOUL_PROJECT_REF),
    /unreviewed project host/,
  );

  // Vault items store the legacy host without a scheme; accept it as https.
  assert.equal(
    reviewedProjectUrl(`${SEOUL_PROJECT_REF}.supabase.co`, SEOUL_PROJECT_REF),
    `https://${SEOUL_PROJECT_REF}.supabase.co`,
  );

  assert.throws(
    () => reviewedProjectUrl("evil.example.com", SEOUL_PROJECT_REF),
    /unreviewed project host/,
  );
});
