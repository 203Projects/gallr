const assert = require("node:assert/strict");
const { validatePayload, consumeCallback, createClient } = require("../client/individual-submission.js");
const payload = { name_ko: "전시", venue_name_ko: "공간", address_ko: "서울", opening_date: "2026-09-30", closing_date: "2026-10-31", hours: "10:00–18:00" };
assert.deepEqual(validatePayload(payload), {});
assert.equal(validatePayload({ ...payload, closing_date: "2026-09-01" }).closing_date, "date_order");
assert.equal(validatePayload({ ...payload, opening_date: "2026-02-30" }).opening_date, "date_invalid");
assert.equal(validatePayload({ ...payload, name_ko: " " }).name_ko, "required");
assert.deepEqual(consumeCallback("https://gallrmap.com/submit/individual/#access_token=secret&refresh_token=hidden&type=magiclink"), { token: "secret", cleanUrl: "/submit/individual/", error: false });
assert.equal(consumeCallback("https://gallrmap.com/submit/individual/#details").cleanUrl, "/submit/individual/#details");
(async () => {
  const calls = [];
  const client = createClient({ url: "https://example.supabase.co", key: "sb_publishable_test" }, async (url, options) => {
    calls.push({ url, options });
    return { ok: true, json: async () => ({ submission_id: "00000000-0000-4000-8000-000000000001", status: "submitted" }) };
  });
  await client.sendLink("person@example.com", "https://gallrmap.com/submit/individual/");
  assert.equal(new URL(calls[0].url).searchParams.get("redirect_to"), "https://gallrmap.com/submit/individual/");
  assert.equal(JSON.parse(calls[0].options.body).create_user, true);
  await client.submit("verified-token", payload, "request-id");
  assert.equal(calls[1].options.headers.Authorization, "Bearer verified-token");
  assert.deepEqual(JSON.parse(calls[1].options.body), { p_payload: payload, p_request_id: "request-id" });
  await assert.rejects(client.submit("", payload, "request-id"));
  console.log("[individual-submission.test] passed");
})().catch(() => { process.exitCode = 1; });

const { individualSubmissionConfig } = require("../scripts/individual-submission-config.js");
assert.deepEqual(individualSubmissionConfig({}), { url: "", key: "" });
assert.deepEqual(individualSubmissionConfig({ GALLR_ENABLE_INDIVIDUAL_SUBMISSION: "1", SUPABASE_URL: "https://example.supabase.co/", SUPABASE_PUBLISHABLE_KEY: "sb_publishable_test" }), { url: "https://example.supabase.co", key: "sb_publishable_test" });
for (const url of ["http://example.com", "https://example.com/?key=hidden", "https://example.com/path"]) {
  assert.throws(() => individualSubmissionConfig({ GALLR_ENABLE_INDIVIDUAL_SUBMISSION: "true", SUPABASE_URL: url, SUPABASE_PUBLISHABLE_KEY: "sb_publishable_test" }));
}
assert.throws(() => individualSubmissionConfig({ GALLR_ENABLE_INDIVIDUAL_SUBMISSION: "1", SUPABASE_URL: "https://example.supabase.co", SUPABASE_PUBLISHABLE_KEY: "sb_secret_hidden" }));


const credentialUrl = new URL("https://example.com");
credentialUrl.username = "fixture-user";
credentialUrl.password = "fixture-password";
assert.throws(() => individualSubmissionConfig({ GALLR_ENABLE_INDIVIDUAL_SUBMISSION: "1", SUPABASE_URL: credentialUrl.href, SUPABASE_PUBLISHABLE_KEY: "sb_publishable_test" }));
