import { assertEquals, assertMatch } from "@std/assert";
import {
  buildMessages,
  englishSourceOf,
  inFormalPoliteRegister,
  loadPromptAssets,
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
            "서울 첫 개인전이라는 사실보다 중요한 것은 네 번째 전시라는 숫자입니다.",
          grounding: ["description_ko"],
        },
        {
          text: "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역합니다.",
          grounding: ["description_ko", "art_terms"],
        },
      ],
      tip: "복도 끝의 가장 어두운 방부터 보면 좋습니다.",
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
  draft.ko.insights[1].text = "1998년 베를린에서 시작된 연작입니다.";
  assertEquals(validateDraft(draft, packet), {
    ok: false,
    code: "number_not_in_sources",
  });
  const okDraft = goodDraft();
  okDraft.ko.insights[1].text = "2026년 가을, 네 번째 전시입니다.";
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

Deno.test("Korean sentences must be in 합니다체, not the plain -다 or 해요체 register", () => {
  const plain = goodDraft();
  plain.ko.insights[0].text =
    "서울 첫 개인전이라는 사실보다 중요한 것은 네 번째 전시라는 숫자다.";
  assertEquals(validateDraft(plain, packet), {
    ok: false,
    code: "language_mismatch",
  });
  const haeyo = goodDraft();
  haeyo.ko.tip = "복도 끝의 가장 어두운 방부터 보세요.";
  assertEquals(validateDraft(haeyo, packet), {
    ok: false,
    code: "language_mismatch",
  });
  const lastSentencePlain = goodDraft();
  lastSentencePlain.ko.insights[1].text =
    "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역합니다. 변신은 어디에서 멈추는가?";
  assertEquals(validateDraft(lastSentencePlain, packet), {
    ok: false,
    code: "language_mismatch",
  });
});

Deno.test("합니다체 questions, closing quotes and dotted dates pass; the hook and English are not checked", () => {
  const draft = goodDraft();
  draft.ko.hook = "곰은 왜 다시 변신했나";
  draft.ko.insights[1].text =
    "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역합니다. 변신은 어디에서 멈춥니까? 전시 제목은 “곰의 시간”입니다. 전시는 10.31까지 이어집니다.";
  assertEquals(validateDraft(draft, packet), { ok: true });
  const english = goodDraft();
  english.en!.tip = "Start with the darkest room.";
  assertEquals(validateDraft(english, packet), { ok: true });
});

Deno.test("initials in a name do not end a 합니다체 sentence", () => {
  const draft = goodDraft();
  draft.ko.insights[1].text =
    "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역합니다. J. M. W. 터너의 풍경을 다시 봅니다.";
  assertEquals(validateDraft(draft, packet), { ok: true });
});

Deno.test("abbreviations such as Vol. and No. do not end a 합니다체 sentence", () => {
  const seriesPacket = {
    ...packet,
    description_en: `${packet.description_en} Vol. 2 and No. 5 hang together.`,
  };
  const draft = goodDraft();
  draft.ko.tip = "Vol. 2와 No. 5를 함께 보면 좋습니다.";
  assertEquals(validateDraft(draft, seriesPacket), { ok: true });
});

Deno.test("a foreign title ending in ? does not end a 합니다체 sentence, quoted or not", () => {
  const unquoted = goodDraft();
  unquoted.ko.insights[1].text = "Why Not? 전은 질문으로 시작합니다.";
  assertEquals(validateDraft(unquoted, packet), { ok: true });
  const quoted = goodDraft();
  quoted.ko.insights[1].text = "“Why Not?” 전은 질문으로 시작합니다.";
  assertEquals(validateDraft(quoted, packet), { ok: true });
});

Deno.test("a plain -다 sentence after initials is still rejected", () => {
  const last = goodDraft();
  last.ko.insights[1].text =
    "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역합니다. J. M. W. 터너를 다시 본다.";
  assertEquals(validateDraft(last, packet), {
    ok: false,
    code: "language_mismatch",
  });
  const middle = goodDraft();
  middle.ko.insights[1].text =
    "J. M. W. 터너를 다시 본다. 방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역합니다.";
  assertEquals(validateDraft(middle, packet), {
    ok: false,
    code: "language_mismatch",
  });
});

Deno.test("a bracketed aside before the full stop does not hide a plain -다 sentence", () => {
  // Checked on the register alone: the years here are not in the fixture packet.
  const plain = goodDraft().ko;
  plain.insights[1].text =
    "그는 이 연작을 처음 그렸다(1990). 방마다 색을 바꿔 번역합니다.";
  assertEquals(inFormalPoliteRegister(plain), false);
  const polite = goodDraft().ko;
  polite.insights[1].text =
    "그는 이 연작을 처음 그렸습니다(1990). 작가(1970년생)는 방마다 색을 바꿉니다.";
  assertEquals(inFormalPoliteRegister(polite), true);
});

Deno.test("a whole sentence in brackets is still checked, and nested or full-width asides are removed", () => {
  const bracketedSentence = goodDraft().ko;
  bracketedSentence.insights[1].text =
    "작품을 소개합니다. (입장료는 무료이다.)";
  assertEquals(inFormalPoliteRegister(bracketedSentence), false);
  const nested = goodDraft().ko;
  nested.insights[1].text =
    "그는 이 연작을 처음 그렸다(1990 (재제작)). 방마다 색을 바꿔 번역합니다.";
  assertEquals(inFormalPoliteRegister(nested), false);
  const fullWidth = goodDraft().ko;
  fullWidth.insights[1].text =
    "그는 이 연작을 처음 그렸다（1990）. 방마다 색을 바꿔 번역합니다.";
  assertEquals(inFormalPoliteRegister(fullWidth), false);
  const square = goodDraft().ko;
  square.insights[1].text =
    "그는 이 연작을 처음 그렸다[1990]. 방마다 색을 바꿔 번역합니다.";
  assertEquals(inFormalPoliteRegister(square), false);
  const politeNested = goodDraft().ko;
  politeNested.insights[1].text =
    "그는 이 연작을 처음 그렸습니다(1990 (재제작)). 방마다 색을 바꿔 번역합니다.";
  assertEquals(inFormalPoliteRegister(politeNested), true);
});

Deno.test("a final aside with stops in it, such as initials or Vol. 2, does not fail a 합니다체 sentence", () => {
  const initials = goodDraft().ko;
  initials.insights[1].text =
    "작가가 오래 따라온 화가입니다 (J. M. W. Turner). 방마다 색을 바꿔 번역합니다.";
  assertEquals(inFormalPoliteRegister(initials), true);
  const volume = goodDraft().ko;
  volume.tip = "이 책은 연작의 일부입니다 (Vol. 2).";
  assertEquals(inFormalPoliteRegister(volume), true);
});

Deno.test("a quoted Korean title ending in ? or . does not end a 합니다체 sentence", () => {
  const corner = goodDraft().ko;
  corner.insights[1].text =
    "「누가 미술관을 두려워하랴?」 전은 질문으로 시작합니다.";
  assertEquals(inFormalPoliteRegister(corner), true);
  const double = goodDraft().ko;
  double.insights[1].text = '"끝나지 않는 여름." 전은 빛으로 시작합니다.';
  assertEquals(inFormalPoliteRegister(double), true);
});

Deno.test("a plain -다 sentence ended by a line break or by a stop with no space is rejected", () => {
  const lineBreak = goodDraft().ko;
  lineBreak.insights[1].text =
    "방마다 색을 바꿨다\n그래서 복도 끝이 가장 어둡습니다.";
  assertEquals(inFormalPoliteRegister(lineBreak), false);
  const noSpace = goodDraft().ko;
  noSpace.insights[1].text = "작가는 이 연작을 그렸다.그리고 다시 봅니다.";
  assertEquals(inFormalPoliteRegister(noSpace), false);
});

Deno.test("an ending in 니다 or 니까 without the ㅂ before it is not 합니다체", () => {
  const copula = goodDraft().ko;
  copula.insights[1].text = "이 연작의 주제는 어머니다.";
  assertEquals(inFormalPoliteRegister(copula), false);
  const connective = goodDraft().ko;
  connective.tip = "오후에는 빛이 들어오니까.";
  assertEquals(inFormalPoliteRegister(connective), false);
  const polite = goodDraft().ko;
  polite.tip = "오후에 가면 좋습니다. 빛이 들어옵니까?";
  assertEquals(inFormalPoliteRegister(polite), true);
});

Deno.test("a sentence ending on a Latin word joins the next one, a trade-off the editor's review covers", () => {
  // Splitting after any Latin word would also split "J. M. W. 터너"; such a sentence is checked with the next.
  const draft = goodDraft().ko;
  draft.insights[1].text =
    "이번 전시의 제목은 Untitled. 방마다 색을 바꿔 번역합니다.";
  assertEquals(inFormalPoliteRegister(draft), true);
});

/** The register verdict for each Korean insight text, with the fixture's other fields unchanged. */
function registerOf(...texts: string[]): boolean[] {
  return texts.map((text) => {
    const korean = goodDraft().ko;
    korean.insights[1].text = text;
    return inFormalPoliteRegister(korean);
  });
}

Deno.test("a quote mark pairs only with its own closer, so a mismatched pair cannot hide a plain -다 sentence", () => {
  assertEquals(
    registerOf(
      "“곰을 다시 그렸다. 방마다 색을 바꾸고」 전은 빛으로 시작합니다.",
      "“곰을 다시 그렸다. 그리고 '빛' 전은 방마다 색을 바꿉니다.",
      "「곰을 다시 그렸다. 그리고 “빛” 전은 방마다 색을 바꿉니다.",
    ),
    [false, false, false],
  );
  assertEquals(
    registerOf(
      "「『곰』의 시간」 전은 방마다 색을 바꿉니다.",
      "“그는 ‘곰’이라 부릅니다”라는 문장으로 시작합니다.",
      "‘곰의 시간’과 “빛의 방”을 함께 봅니다.",
    ),
    [true, true, true],
  );
});

Deno.test("an apostrophe inside or after a Latin word is not a quote mark", () => {
  assertEquals(
    registerOf(
      "작가는 Children's Room을 다시 그렸다. 그 옆에 Artists' Room이 있습니다.",
      "'Children's Room'에서 그는 방을 다시 지었다.",
    ),
    [false, false],
  );
  assertEquals(
    registerOf(
      "'Children's Room' 전은 아이의 방을 다시 짓습니다.",
      "Children's Room과 Artists' Room을 함께 봅니다.",
      "‘Children’s Room’ 전은 아이의 방을 다시 짓습니다.",
    ),
    [true, true, true],
  );
});

Deno.test("a Hangul sentence wholly in quotes is still checked", () => {
  assertEquals(
    registerOf(
      "작품을 소개합니다. “입장료는 무료이다.”",
      '작품을 소개합니다. "입장료는 무료이다."',
      "작품을 소개합니다. '입장료는 무료이다.'",
      "작품을 소개합니다. 「입장료는 무료이다.」",
      "“입장료는 무료이다”. 방마다 색을 바꿉니다.",
      "“입장료는 무료이다.”\n방마다 색을 바꿉니다.",
      "작품을 소개합니다. “‘입장료는 무료이다.’”",
    ),
    [false, false, false, false, false, false, false],
  );
  assertEquals(
    registerOf(
      "작품을 소개합니다. “입장료는 무료입니다.”",
      "“입장료는 무료입니다”. 방마다 색을 바꿉니다.",
      '작품을 소개합니다. "Why not?"',
    ),
    [true, true, true],
  );
});

Deno.test("a sentence cannot end on a quoted title", () => {
  assertEquals(
    registerOf("전시 제목은 “곰의 시간”. 방마다 색을 바꿉니다."),
    [false],
  );
  assertEquals(
    registerOf("전시 제목은 “곰의 시간”입니다. 방마다 색을 바꿉니다."),
    [true],
  );
});

Deno.test("a quoted sentence followed by more words reads as a title, a trade-off the editor's review covers", () => {
  // Same shape as "끝나지 않는 여름." 전은 …: nothing but grammar tells a title from a quoted sentence here.
  assertEquals(
    registerOf("“입장료는 무료이다.” 방마다 색을 바꿉니다."),
    [true],
  );
});

Deno.test("an ellipsis, ?!, !? or .. after Hangul ends a sentence", () => {
  assertEquals(
    registerOf(
      "작가는 곰을 다시 그렸다… 방마다 색을 바꿉니다.",
      "작가는 곰을 다시 그렸다... 방마다 색을 바꿉니다.",
      "작가는 곰을 다시 그렸다.. 방마다 색을 바꿉니다.",
      "변신은 어디에서 멈추는가?! 방마다 색을 바꿉니다.",
      "변신은 어디에서 멈추는가!? 방마다 색을 바꿉니다.",
      "작가는 곰을 다시 그렸다…그리고 방마다 색을 바꿉니다.",
    ),
    [false, false, false, false, false, false],
  );
  assertEquals(
    registerOf(
      "곰은 다시 변신합니다… 방마다 색을 바꿉니다.",
      "곰은 다시 변신합니다... 방마다 색을 바꿉니다...",
      "변신은 어디에서 멈춥니까?! 방마다 색을 바꿉니다.",
    ),
    [true, true, true],
  );
});

Deno.test("an unmatched closing quote after a stop ends a sentence", () => {
  assertEquals(
    registerOf(
      "작가는 곰을 다시 그렸다.” 방마다 색을 바꿉니다.",
      "작가는 곰을 다시 그렸다.' 방마다 색을 바꿉니다.",
      "작가는 곰을 다시 그렸다.」 방마다 색을 바꿉니다.",
      "작가는 곰을 다시 그렸다.”방마다 색을 바꿉니다.",
    ),
    [false, false, false, false],
  );
  assertEquals(
    registerOf("곰은 다시 변신합니다.” 방마다 색을 바꿉니다."),
    [true],
  );
});

Deno.test("a stop straight before a quoting or case particle stays inside the sentence", () => {
  assertEquals(
    registerOf(
      "작가는 왜?라고 묻습니다.",
      "작가는 왜?하고 묻습니다.",
      "전시는 왜?라는 질문으로 시작합니다.",
      "작가는 정말?이라며 되묻습니다.",
      "왜?는 이 전시의 첫 질문입니다.",
      "작가는 그래서...라며 말을 줄입니다.",
    ),
    [true, true, true, true, true, true],
  );
  assertEquals(
    registerOf(
      "작가는 곰을 그렸다.며칠 뒤 다시 봅니다.",
      "작가는 곰을 그렸다?라고 묻는다.",
    ),
    [false, false],
  );
});

Deno.test("a stop and a closing bracket straight before Hangul end a sentence", () => {
  assertEquals(
    registerOf("작품을 봅니다(정말 좋다.)그리고 방을 나섭니다."),
    [false],
  );
  assertEquals(
    registerOf("작품을 봅니다(정말 좋습니다.)그리고 방을 나섭니다."),
    [true],
  );
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

Deno.test("the prompt's reference notes are themselves written in 합니다체", async () => {
  const { examples } = await loadPromptAssets();
  for (const example of examples as Array<{ ko: NoteDraft["ko"] }>) {
    assertEquals(inFormalPoliteRegister(example.ko), true);
  }
});
