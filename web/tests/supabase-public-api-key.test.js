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

test("uses the publishable-key variable even when a legacy variable is present", () => {
  assert.equal(
    resolveSupabasePublicApiKey({
      SUPABASE_PUBLISHABLE_KEY: "sb_publishable_current",
      SUPABASE_ANON_KEY: "legacy-anon",
    }),
    "sb_publishable_current",
  );
});

test("does not accept the deprecated legacy variable", () => {
  assert.equal(
    resolveSupabasePublicApiKey({ SUPABASE_ANON_KEY: " legacy-anon " }),
    "",
  );
});

test("does not fall back when the publishable-key variable is blank", () => {
  assert.equal(
    resolveSupabasePublicApiKey({
      SUPABASE_PUBLISHABLE_KEY: "  ",
      SUPABASE_ANON_KEY: "legacy-anon",
    }),
    "",
  );
});

test("returns blank when neither variable is configured", () => {
  assert.equal(resolveSupabasePublicApiKey({}), "");
});
