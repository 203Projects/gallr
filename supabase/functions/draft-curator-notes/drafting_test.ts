import { assertEquals, assertMatch } from "@std/assert";
import {
  buildMessages,
  englishSourceOf,
  PROMPT_VERSION,
  sourceFieldsOf,
  toStorePayload,
  validateDraft,
} from "./drafting.ts";
import { type NoteDraft, NoteDraftSchema } from "./schema.ts";

const packet = JSON.parse(
  await Deno.readTextFile(new URL("./fixtures/packet.json", import.meta.url)),
) as Record<string, unknown>;

function goodDraft(overrides: Partial<NoteDraft> = {}): NoteDraft {
  return {
    ko: {
      hook: "곰 한 마리가 동화 속으로 들어간 이유",
      insights: [
        {
          text:
            "서울 첫 개인전이라는 사실보다 중요한 것은 네 번째 전시라는 숫자다.",
          grounding: ["description_ko"],
        },
        {
          text: "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역한다.",
          grounding: ["description_ko", "art_terms"],
        },
      ],
      tip: "복도 끝의 가장 어두운 방부터 보라.",
    },
    en: {
      hook: "Why a bear walked into a fairy tale",
      insights: [
        {
          text: "A first solo show in Seoul matters less than the number four.",
          grounding: ["description_en"],
        },
        {
          text:
            "Each room changes colour to stage the character's next disguise.",
          grounding: ["description_en"],
        },
      ],
      tip: "Start with the darkest room at the end of the corridor.",
    },
    ...overrides,
  };
}

Deno.test("the schema accepts a well-formed draft and rejects extra keys", () => {
  assertEquals(NoteDraftSchema.safeParse(goodDraft()).success, true);
  assertEquals(
    NoteDraftSchema.safeParse({ ...goodDraft(), extra: true }).success,
    false,
  );
  assertEquals(
    NoteDraftSchema.safeParse(goodDraft({ en: null })).success,
    true,
  );
});

Deno.test("source fields are the packet keys, sorted", () => {
  const fields = sourceFieldsOf(packet);
  assertEquals(fields[0], "art_terms");
  assertEquals(fields.includes("description_ko"), true);
  assertEquals(fields.includes("cover_image_url"), false);
  assertEquals([...fields].sort(), fields);
});

Deno.test("messages carry the prompt version, the examples and the packet", () => {
  const messages = buildMessages(packet, {
    system: "You are the curator.",
    examples: [{ note: "example one" }],
  });
  assertMatch(messages.system, /You are the curator\./);
  assertMatch(messages.system, /example one/);
  assertMatch(messages.user, /"description_ko"/);
  assertMatch(messages.user, /Korean/);
  assertMatch(messages.user, /English/);
  assertMatch(PROMPT_VERSION, /^\d{4}-\d{2}-\d{2}\.\d+$/);
});

Deno.test("English is written from the English description, from the Korean one under English names, or not at all", () => {
  const { description_en: _dropped, ...koOnly } = packet;
  const { name_en: _name, ...noEnglishName } = koOnly;
  assertEquals(englishSourceOf(packet), "description_en");
  assertEquals(englishSourceOf(koOnly), "korean_description");
  assertEquals(englishSourceOf(noEnglishName), null);
  assertEquals(
    englishSourceOf({ ...koOnly, description_en: "  " }),
    "korean_description",
  );

  assertMatch(
    buildMessages(packet, { system: "s", examples: [] }).user,
    /English \(`en`\) is required because the packet has `description_en`/,
  );
  const fromKorean = buildMessages(koOnly, { system: "s", examples: [] }).user;
  assertMatch(fromKorean, /English \(`en`\) is required/);
  assertMatch(fromKorean, /from the Korean description/);
  assertMatch(fromKorean, /never transliterate/);
  assertMatch(
    buildMessages(noEnglishName, { system: "s", examples: [] }).user,
    /"en": null/,
  );
});

Deno.test("a grounded draft passes validation", () => {
  assertEquals(validateDraft(goodDraft(), packet), { ok: true });
});

Deno.test("a grounding key outside the packet is rejected", () => {
  const draft = goodDraft();
  draft.ko.insights[0].grounding = ["wikipedia"];
  assertEquals(validateDraft(draft, packet), {
    ok: false,
    code: "grounding_unknown_key",
  });
});

Deno.test("a year or number absent from the packet is rejected", () => {
  const draft = goodDraft();
  draft.ko.insights[1].text = "1998년 베를린에서 시작된 연작이다.";
  assertEquals(validateDraft(draft, packet), {
    ok: false,
    code: "number_not_in_sources",
  });
  const okDraft = goodDraft();
  okDraft.ko.insights[1].text = "2026년 가을, 네 번째 전시다.";
  assertEquals(validateDraft(okDraft, packet), { ok: true });
});

Deno.test("language mismatches are rejected", () => {
  const noHangul = goodDraft();
  noHangul.ko.hook = "A hook without Korean";
  assertEquals(validateDraft(noHangul, packet), {
    ok: false,
    code: "language_mismatch",
  });
  const hangulInEnglish = goodDraft();
  hangulInEnglish.en!.tip = "복도 끝부터";
  assertEquals(validateDraft(hangulInEnglish, packet), {
    ok: false,
    code: "language_mismatch",
  });
});

Deno.test("over-long fields and wrong insight counts are rejected", () => {
  const long = goodDraft();
  long.ko.hook = "가".repeat(121);
  assertEquals(validateDraft(long, packet), {
    ok: false,
    code: "length_exceeded",
  });
  const few = goodDraft();
  few.ko.insights = [few.ko.insights[0]];
  assertEquals(validateDraft(few, packet), {
    ok: false,
    code: "insight_count",
  });
  const many = goodDraft();
  many.en!.insights = [
    ...many.en!.insights,
    ...many.en!.insights,
    many.en!.insights[0],
  ];
  assertEquals(validateDraft(many, packet), {
    ok: false,
    code: "insight_count",
  });
});

Deno.test("English grounded in the Korean description passes when the packet gives an English name", () => {
  const { description_en: _dropped, ...koOnly } = packet;
  const english = goodDraft().en!;
  const fromKorean = goodDraft({
    en: {
      ...english,
      insights: english.insights.map((insight) => ({
        ...insight,
        grounding: ["description_ko"],
      })),
    },
  });
  assertEquals(validateDraft(fromKorean, koOnly), { ok: true });
  assertEquals(validateDraft(goodDraft(), koOnly), {
    ok: false,
    code: "grounding_unknown_key",
  });
  assertEquals(validateDraft(goodDraft({ en: null }), koOnly), { ok: true });
});

Deno.test("an English section is refused when the packet has neither an English description nor an English name", () => {
  const { description_en: _dropped, name_en: _name, ...noEnglish } = packet;
  const english = goodDraft().en!;
  const fromKorean = goodDraft({
    en: {
      ...english,
      insights: english.insights.map((insight) => ({
        ...insight,
        grounding: ["description_ko"],
      })),
    },
  });
  assertEquals(validateDraft(fromKorean, noEnglish), {
    ok: false,
    code: "language_mismatch",
  });
  assertEquals(validateDraft(goodDraft({ en: null }), noEnglish), { ok: true });
});

Deno.test("the store payload carries provenance and the model that answered", () => {
  const payload = toStorePayload({
    exhibitionId: "who-lived-happily",
    packetHash: "a".repeat(64),
    sourceFields: sourceFieldsOf(packet),
    sourceVersionId: null,
    draft: goodDraft(),
    model: "claude-opus-5-5-answered",
    requestKind: "scheduled",
    requestedBy: null,
    generatedAt: "2026-10-11T18:00:00.000Z",
  });
  assertEquals(payload.status, "draft");
  assertEquals(payload.model, "claude-opus-5-5-answered");
  assertEquals(payload.prompt_version, PROMPT_VERSION);
  assertEquals(payload.source_hash, "a".repeat(64));
  assertEquals(payload.hook_ko, "곰 한 마리가 동화 속으로 들어간 이유");
  assertEquals(payload.insights_en.length, 2);
  assertEquals(payload.rejection_code, null);

  const unusable = toStorePayload({
    exhibitionId: "who-lived-happily",
    packetHash: "a".repeat(64),
    sourceFields: sourceFieldsOf(packet),
    sourceVersionId: null,
    draft: null,
    rejectionCode: "parse_failed",
    model: "claude-opus-5-5-answered",
    requestKind: "single",
    requestedBy: "00000000-0000-4000-8000-00000000d901",
    generatedAt: "2026-10-11T18:00:00.000Z",
  });
  assertEquals(unusable.status, "unusable");
  assertEquals(unusable.rejection_code, "parse_failed");
  assertEquals(unusable.insights_ko, []);
  assertEquals(unusable.requested_by, "00000000-0000-4000-8000-00000000d901");
});

Deno.test("the store payload names the API producer and no override unless asked", () => {
  const payload = toStorePayload({
    exhibitionId: "who-lived-happily",
    packetHash: "a".repeat(64),
    sourceFields: sourceFieldsOf(packet),
    sourceVersionId: null,
    draft: goodDraft(),
    model: "claude-test",
    requestKind: "scheduled",
    requestedBy: null,
    generatedAt: "2026-10-13T03:00:00.000Z",
  });
  assertEquals(payload.producer, "claude");
  assertEquals(payload.override, false);
});
