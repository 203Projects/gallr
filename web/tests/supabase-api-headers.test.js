"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const { supabaseApiHeaders } = require("../scripts/supabase-api-headers");

function legacyJwt(role) {
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" }))
    .toString("base64url");
  const payload = Buffer.from(JSON.stringify({ role }))
    .toString("base64url");
  return `${header}.${payload}.signature`;
}

test("legacy JWT API key keeps bearer compatibility", () => {
  const key = legacyJwt("anon");

  assert.deepEqual(supabaseApiHeaders(key), {
    apikey: key,
    Authorization: `Bearer ${key}`,
  });
});

test("publishable API key is not sent as a bearer token", () => {
  const key = "sb_publishable_public-test-key";

  assert.deepEqual(supabaseApiHeaders(key), {
    apikey: key,
  });
});

test("secret API key is rejected from public build clients", () => {
  assert.throws(
    () => supabaseApiHeaders("sb_secret_must-not-ship"),
    /secret API keys are not allowed/i,
  );
});

test("legacy service role JWT is rejected from public build clients", () => {
  assert.throws(
    () => supabaseApiHeaders(legacyJwt("service_role")),
    /service role API keys are not allowed/i,
  );
});

test("blank API key is rejected", () => {
  assert.throws(() => supabaseApiHeaders("   "), /API key is required/i);
});
