const assert = require("node:assert/strict");
const { validatePayload, validateImage, consumeCallback, createClient } = require("../client/individual-submission.js");
assert.equal(validateImage(null), "required");
assert.equal(validateImage({type:"image/svg+xml",size:20,name:"x.svg"}), "image_type");
assert.equal(validateImage({type:"image/png",size:5242881,name:"x.png"}), "image_size");
assert.equal(validateImage({type:"image/jpeg",size:200,name:"poster.jpg"}), "");
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
    if (url.endsWith("reserve_individual_exhibition_image")) return {ok:true,json:async()=>({asset_id:"00000000-0000-4000-8000-000000000087",bucket_id:"exhibition-media",object_path:"submissions/00000000-0000-4000-8000-000000000088/00000000-0000-4000-8000-000000000087/original.png",mime_type:"image/png",byte_size:68})};
    return { ok: true, json: async () => ({ submission_id: "00000000-0000-4000-8000-000000000001", status: "submitted" }) };
  });
  await client.sendLink("person@example.com", "https://gallrmap.com/submit/individual/");
  assert.equal(new URL(calls[0].url).searchParams.get("redirect_to"), "https://gallrmap.com/submit/individual/");
  assert.equal(JSON.parse(calls[0].options.body).create_user, true);
  await client.submit("verified-token", payload, "request-id");
  assert.equal(calls[1].options.headers.Authorization, "Bearer verified-token");
  assert.deepEqual(JSON.parse(calls[1].options.body), { p_payload: payload, p_request_id: "request-id" });
  await assert.rejects(client.submit("", payload, "request-id"));
  const file = {type:"image/png",size:68,name:"poster.png"};
  const reservation = await client.reserveImage("verified-token",file,"image-request");
  await client.uploadImage("verified-token",file,reservation);
  assert.equal(calls.at(-1).options.body,file);
  assert.equal(calls.at(-1).options.headers["Content-Type"],"image/png");
  assert.equal(calls.at(-1).options.headers["x-upsert"],"false");
  await assert.rejects(client.uploadImage("verified-token",file,{...reservation,object_path:"../escape"}));
  const duplicateClient=createClient({url:"https://example.supabase.co",key:"sb_publishable_test"},async()=>({ok:false,status:409,json:async()=>({error:"Duplicate"})}));
  await duplicateClient.uploadImage("verified-token",file,reservation);
  for (const code of ["ResourceAlreadyExists", "KeyAlreadyExists", "already_exists"]) {
    const retryClient=createClient({url:"https://example.supabase.co",key:"sb_publishable_test"},async()=>({ok:false,status:409,json:async()=>({code})}));
    await retryClient.uploadImage("verified-token",file,reservation);
  }
  const expiredClient=createClient({url:"https://example.supabase.co",key:"sb_publishable_test"},async()=>({ok:false,status:400,json:async()=>({message:"individual_image_reservation_expired"})}));
  await assert.rejects(expiredClient.submit("verified-token",payload,"request-id"),{message:"image_unavailable"});
  console.log("[individual-submission.test] passed");
})().catch(error => { console.error(error); process.exitCode = 1; });

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
