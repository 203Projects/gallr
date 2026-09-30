const test = require("node:test");
const assert = require("node:assert/strict");
const { createRegistrationRepository } = require("../registration/adapter.js");
const request = "87000000-0000-4000-8000-000000000001";
const file = { type: "image/png", size: 100, name: "poster.png" };
const path =
  "submissions/87000000-0000-4000-8000-000000000002/87000000-0000-4000-8000-000000000003/original.png";
const draft = {
  name_ko: "전시",
  artists: "작가",
  opening_date: "2026-10-01",
  closing_date: "2026-10-31",
  description_ko: "소개",
  venue_name_ko: "장소",
  address_ko: "서울",
  hours: "10–18",
  submitter_name: "Artist",
  relationship: "artist",
  contact_email: "artist@example.com",
};
test("retains request identity on an ambiguous submission retry and never overwrites the cover", async () => {
  const calls = [];
  let attempts = 0;
  const client = {
    rpc: async (name, args) => {
      calls.push([name, args]);
      if (name === "artist_reserve_registration")
        return {
          data: {
            request_id: request,
            bucket_id: "exhibition-media",
            object_path: path,
            finalized: attempts > 0,
          },
          error: null,
        };
      if (++attempts === 1) return { data: null, error: { code: "network" } };
      return {
        data: {
          submission_id: "87000000-0000-4000-8000-000000000002",
          status: "submitted",
          submitted_at: "2026-09-30T00:00:00Z",
        },
        error: null,
      };
    },
    storage: {
      from: () => ({
        upload: async (_path, _file, options) => {
          assert.equal(options.upsert, false);
          return { error: attempts ? { statusCode: "409" } : null };
        },
      }),
    },
  };
  const repository = createRegistrationRepository(client);
  await assert.rejects(repository.submit(request, draft, file));
  assert.equal(
    (await repository.submit(request, draft, file)).status,
    "submitted",
  );
  assert.ok(calls.every(([, args]) => args.p_request_id === request));
});

test('a completed ambiguous request retries finalization without writing the immutable cover again', async () => {
  let uploads=0;
  const client={rpc:async(name)=>({error:null,data:name==='artist_reserve_registration'
    ? {request_id:request,bucket_id:'exhibition-media',object_path:path,finalized:true}
    : {submission_id:'87000000-0000-4000-8000-000000000002',status:'submitted',submitted_at:'2026-09-30T00:00:00Z'}}),
    storage:{from:()=>({upload:async()=>{uploads++;return {error:{statusCode:'403'}};}})}};
  const result=await createRegistrationRepository(client).submit(request,draft,file);
  assert.equal(result.status,'submitted');assert.equal(uploads,0);
});
test("rejects a malformed upload target before storage I/O", async () => {
  const client = {
    rpc: async () => ({
      data: {
        request_id: request,
        bucket_id: "public",
        object_path: "../other",
      },
      error: null,
    }),
    storage: {
      from: () => {
        throw new Error("must not upload");
      },
    },
  };
  await assert.rejects(
    createRegistrationRepository(client).submit(request, draft, file),
    /response/,
  );
});
test("fails closed on malformed status records", async () => {
  await assert.rejects(
    createRegistrationRepository({
      rpc: async () => ({
        data: [{ id: "foreign", status: "published" }],
        error: null,
      }),
    }).list(),
  );
});
