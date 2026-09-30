const test = require("node:test");
const assert = require("node:assert/strict");
const { registrationConfig } = require("../scripts/lib/registration-config.js");
test("artist intake stays off unless explicitly activated with matching public configuration", () => {
  assert.deepEqual(registrationConfig({}), { enabled: false });
  assert.deepEqual(
    registrationConfig({
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_PUBLISHABLE_KEY: "sb_publishable_example",
    }),
    { enabled: false },
  );
  assert.throws(() =>
    registrationConfig({ GALLR_ENABLE_ARTIST_SUBMISSIONS: "true" }),
  );
  assert.throws(() =>
    registrationConfig({
      GALLR_ENABLE_ARTIST_SUBMISSIONS: "true",
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_PUBLISHABLE_KEY: "sb_secret_bad",
    }),
  );
  assert.equal(
    registrationConfig({
      GALLR_ENABLE_ARTIST_SUBMISSIONS: "true",
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_PUBLISHABLE_KEY: "sb_publishable_example",
    }).enabled,
    true,
  );
});
