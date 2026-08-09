"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const {
  resolveSupabasePublicApiKey,
} = require("../scripts/supabase-public-api-key");

test("uses the publishable-key variable", () => {
  assert.equal(
    resolveSupabasePublicApiKey({
      SUPABASE_PUBLISHABLE_KEY: " sb_publishable_current ",
    }),
    "sb_publishable_current",
  );
});

test("prefers the publishable-key variable during migration", () => {
  assert.equal(
    resolveSupabasePublicApiKey({
      SUPABASE_PUBLISHABLE_KEY: "sb_publishable_current",
      SUPABASE_ANON_KEY: "legacy-anon",
    }),
    "sb_publishable_current",
  );
});

test("temporarily accepts the legacy variable", () => {
  assert.equal(
    resolveSupabasePublicApiKey({ SUPABASE_ANON_KEY: " legacy-anon " }),
    "legacy-anon",
  );
});

test("ignores a blank publishable-key variable during migration", () => {
  assert.equal(
    resolveSupabasePublicApiKey({
      SUPABASE_PUBLISHABLE_KEY: "  ",
      SUPABASE_ANON_KEY: "legacy-anon",
    }),
    "legacy-anon",
  );
});

test("returns blank when neither variable is configured", () => {
  assert.equal(resolveSupabasePublicApiKey({}), "");
});
