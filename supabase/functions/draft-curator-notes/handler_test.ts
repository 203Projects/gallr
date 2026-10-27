import { assertEquals } from "@std/assert";
import type {
  Candidate,
  DraftAnswer,
  DraftingBackend,
  StoredVersion,
} from "./backend.ts";
import { createDraftHandler } from "./handler.ts";
import type { StorePayload } from "./drafting.ts";
import type { NoteDraft } from "./schema.ts";

const packet = JSON.parse(
  await Deno.readTextFile(new URL("./fixtures/packet.json", import.meta.url)),
) as Record<string, unknown>;
const TOKEN = "schedule-token-0123456789abcdef0123456789abcdef";
const ORIGIN = "https://admin.gallrmap.com";

function candidate(id: string, featured = false): Candidate {
  return {
    exhibitionId: id,
    isFeatured: featured,
    packet: { ...packet, id },
    packetHash: "b".repeat(64),
    sourceVersionId: null,
  };
}

function draft(): NoteDraft {
  return {
    ko: {
      hook: "곰 한 마리가 동화 속으로 들어간 이유",
      insights: [
        { text: "첫 번째 통찰입니다.", grounding: ["description_ko"] },
        { text: "두 번째 통찰입니다.", grounding: ["artists"] },
      ],
      tip: "복도 끝의 방부터 보면 좋습니다.",
    },
    en: null,
  };
}

interface FakeOptions {
  candidates?: Candidate[];
  publisher?: boolean;
  requestPacket?: Candidate | null;
  answer?: Partial<DraftAnswer> | Error;
  storeError?: Error;
  draftDelayMs?: number;
}

function fakeBackend(options: FakeOptions = {}) {
  const stored: StorePayload[] = [];
  const backend: DraftingBackend = {
    listCandidates: (_limit: number) =>
      Promise.resolve(options.candidates ?? []),
    requestPacket: (_exhibitionId: string, _jwt: string) =>
      Promise.resolve(options.requestPacket ?? null),
    isPublisher: (_jwt: string) => Promise.resolve(options.publisher ?? false),
    storeDraft: (payload: StorePayload) => {
      if (options.storeError) return Promise.reject(options.storeError);
      stored.push(payload);
      const version: StoredVersion = {
        id: `v-${stored.length}`,
        versionNumber: stored.length,
        status: String(payload.status),
      };
      return Promise.resolve(version);
    },
    draft: async (_messages) => {
      if (options.draftDelayMs) {
        await new Promise((resolve) =>
          setTimeout(resolve, options.draftDelayMs)
        );
      }
      if (options.answer instanceof Error) throw options.answer;
      return {
        parsed: draft(),
        model: "claude-fake",
        stopReason: "end_turn",
        usage: { inputTokens: 10, cacheReadInputTokens: 5, outputTokens: 20 },
        ...options.answer,
      };
    },
  };
  return { backend, stored };
}

function handlerWith(
  options: FakeOptions = {},
  extra: Record<string, string> = {},
) {
  const { backend, stored } = fakeBackend(options);
  const logs: unknown[] = [];
  let tick = 0;
  const handler = createDraftHandler({
    env: (name) =>
      ({
        CURATOR_NOTES_SCHEDULE_TOKEN: TOKEN,
        CURATOR_NOTES_ALLOWED_ORIGINS: ORIGIN,
        ...extra,
      })[name],
    createBackend: () => backend,
    log: (value) => logs.push(value),
    now: () => {
      tick += extra.TICK_MS ? Number(extra.TICK_MS) : 0;
      return tick;
    },
  });
  return { handler, stored, logs };
}

function post(body: unknown, headers: Record<string, string> = {}) {
  return new Request(
    "https://example.supabase.co/functions/v1/draft-curator-notes",
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        origin: ORIGIN,
        ...headers,
      },
      body: typeof body === "string" ? body : JSON.stringify(body),
    },
  );
}

Deno.test("rejects non-POST, non-JSON and oversized bodies", async () => {
  const { handler } = handlerWith();
  assertEquals(
    (await handler(new Request("https://x.test/", { method: "GET" }))).status,
    405,
  );
  assertEquals(
    (await handler(
      post("{}", {
        "content-type": "text/plain",
        authorization: `Bearer ${TOKEN}`,
      }),
    )).status,
    400,
  );
  assertEquals(
    (await handler(
      post({ mode: "scheduled", pad: "x".repeat(17_000) }, {
        authorization: `Bearer ${TOKEN}`,
      }),
    )).status,
    400,
  );
  assertEquals(
    (await handler(
      post({ mode: "unknown" }, { authorization: `Bearer ${TOKEN}` }),
    )).status,
    400,
  );
});

Deno.test("scheduled mode needs the schedule token", async () => {
  const { handler } = handlerWith();
  assertEquals((await handler(post({ mode: "scheduled" }))).status, 401);
  assertEquals(
    (await handler(
      post({ mode: "scheduled" }, { authorization: "Bearer wrong" }),
    )).status,
    401,
  );
  const ok = await handler(
    post({ mode: "scheduled" }, { authorization: `Bearer ${TOKEN}` }),
  );
  assertEquals(ok.status, 200);
  assertEquals(await ok.json(), {
    drafted: 0,
    unusable: 0,
    skipped: 0,
    failed: 0,
    remaining: 0,
    results: [],
  });
});

Deno.test("scheduled mode drafts every candidate and stores provenance", async () => {
  const { handler, stored, logs } = handlerWith({
    candidates: [candidate("a", true), candidate("b")],
  });
  const response = await handler(
    post({ mode: "scheduled", limit: 20 }, {
      authorization: `Bearer ${TOKEN}`,
    }),
  );
  const body = await response.json();
  assertEquals(body.drafted, 2);
  assertEquals(
    body.results.map((r: { exhibition_id: string }) => r.exhibition_id),
    ["a", "b"],
  );
  assertEquals(stored.length, 2);
  assertEquals(stored[0].status, "draft");
  assertEquals(stored[0].model, "claude-fake");
  assertEquals(stored[0].source_hash, "b".repeat(64));
  assertEquals(stored[0].request_kind, "scheduled");
  const line = logs.find((entry) =>
    (entry as { operation?: string }).operation === "curator_note_drafted"
  ) as
    | Record<string, unknown>
    | undefined;
  assertEquals(line?.exhibition_id, "a");
  assertEquals(line?.model, "claude-fake");
  assertEquals(line?.output_tokens, 20);
  assertEquals(JSON.stringify(logs).includes("곰 한 마리"), false);
});

Deno.test("a refusal or a null parse is stored as unusable", async () => {
  const refused = handlerWith({
    candidates: [candidate("a")],
    answer: { parsed: null, stopReason: "refusal" },
  });
  const body = await (await refused.handler(
    post({ mode: "scheduled" }, { authorization: `Bearer ${TOKEN}` }),
  )).json();
  assertEquals(body.unusable, 1);
  assertEquals(refused.stored[0].status, "unusable");
  assertEquals(refused.stored[0].rejection_code, "provider_refused");

  const unparsed = handlerWith({
    candidates: [candidate("a")],
    answer: { parsed: null, stopReason: "end_turn" },
  });
  await unparsed.handler(
    post({ mode: "scheduled" }, { authorization: `Bearer ${TOKEN}` }),
  );
  assertEquals(unparsed.stored[0].rejection_code, "parse_failed");
});

Deno.test("a draft that fails validation is stored as unusable with its code", async () => {
  const bad = draft();
  bad.ko.insights[0].grounding = ["wikipedia"];
  const { handler, stored } = handlerWith({
    candidates: [candidate("a")],
    answer: { parsed: bad },
  });
  const body = await (await handler(
    post({ mode: "scheduled" }, { authorization: `Bearer ${TOKEN}` }),
  )).json();
  assertEquals(body.unusable, 1);
  assertEquals(stored[0].rejection_code, "grounding_unknown_key");
});

Deno.test("a provider failure is reported per exhibition and the run continues", async () => {
  const { handler, stored, logs } = handlerWith({
    candidates: [candidate("a"), candidate("b")],
    answer: new Error("upstream down"),
  });
  const body = await (await handler(
    post({ mode: "scheduled" }, { authorization: `Bearer ${TOKEN}` }),
  )).json();
  assertEquals(body.failed, 2);
  assertEquals(stored.length, 0);
  assertEquals(
    logs.filter((entry) =>
      (entry as { operation?: string }).operation ===
        "curator_note_provider_failed"
    ).length,
    2,
  );
  assertEquals(JSON.stringify(logs).includes("upstream down"), false);
});

Deno.test("the time budget leaves the rest for the next run", async () => {
  const { handler, stored } = handlerWith(
    { candidates: [candidate("a"), candidate("b"), candidate("c")] },
    { TICK_MS: "30000" },
  );
  const body = await (await handler(
    post({ mode: "scheduled" }, { authorization: `Bearer ${TOKEN}` }),
  )).json();
  assertEquals(body.drafted, 1);
  assertEquals(body.remaining, 2);
  assertEquals(stored.length, 1);
});

Deno.test("single mode requires a publisher session and a qualifying exhibition", async () => {
  const denied = handlerWith({
    publisher: false,
    requestPacket: candidate("a"),
  });
  assertEquals(
    (await denied.handler(
      post({ mode: "single", exhibition_id: "a" }, {
        authorization: "Bearer jwt",
      }),
    )).status,
    401,
  );
  const missing = handlerWith({ publisher: true, requestPacket: null });
  assertEquals(
    (await missing.handler(
      post({ mode: "single", exhibition_id: "a" }, {
        authorization: "Bearer jwt",
      }),
    )).status,
    404,
  );
  const ok = handlerWith({ publisher: true, requestPacket: candidate("a") });
  const response = await ok.handler(
    post({ mode: "single", exhibition_id: "a", request_kind: "regenerate" }, {
      authorization: "Bearer jwt",
    }),
  );
  assertEquals(response.status, 200);
  assertEquals(response.headers.get("access-control-allow-origin"), ORIGIN);
  assertEquals((await response.json()).drafted, 1);
  assertEquals(ok.stored[0].request_kind, "regenerate");
});

Deno.test("single mode answers the preflight for an allowed origin only", async () => {
  const { handler } = handlerWith();
  const allowed = await handler(
    new Request("https://x.test/", {
      method: "OPTIONS",
      headers: { origin: ORIGIN },
    }),
  );
  assertEquals(allowed.status, 204);
  assertEquals(allowed.headers.get("access-control-allow-origin"), ORIGIN);
  const other = await handler(
    new Request("https://x.test/", {
      method: "OPTIONS",
      headers: { origin: "https://evil.example" },
    }),
  );
  assertEquals(other.status, 403);
});

Deno.test("single mode reports a provider failure as 503", async () => {
  const { handler } = handlerWith({
    publisher: true,
    requestPacket: candidate("a"),
    answer: new Error("down"),
  });
  const response = await handler(
    post({ mode: "single", exhibition_id: "a" }, {
      authorization: "Bearer jwt",
    }),
  );
  assertEquals(response.status, 503);
  assertEquals(await response.json(), { error: "provider_unavailable" });
});

Deno.test("a changed packet between select and store is reported as source_changed", async () => {
  const { handler } = handlerWith({
    candidates: [candidate("a")],
    storeError: Object.assign(new Error("curator_note_source_changed"), {
      code: "PT409",
    }),
  });
  const body = await (await handler(
    post({ mode: "scheduled" }, { authorization: `Bearer ${TOKEN}` }),
  )).json();
  assertEquals(body.failed, 1);
  assertEquals(body.results[0].code, "source_changed");
});
