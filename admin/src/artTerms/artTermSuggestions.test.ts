// Spec 101 FR-019a: Admin suggests the terms the app itself would detect, each with the description's sentence.
// The word table must equal the app's, read from the parity file the Kotlin host test ArtTermWordsParityHostTest
// keeps equal to ArtTermVocabulary.kt; the detection cases mirror the app's ArtTermDetectorTest.
import { MAX_GROUNDING_LENGTH } from "../domain";
import { suggestArtTerms } from "./artTermSuggestions";
import { ART_TERM_WORDS } from "./artTermWords";
// A `?raw` import is typed by vite/client, so `tsc -b` in the Admin-only Vercel build never resolves a file outside
// admin/; vite.config.ts allows only the contracts folders.
import wordsText from "../../../specs/101-taste-listening/contracts/art-term-words.json?raw";

const ids = (descriptionKo: string, descriptionEn = "") =>
  suggestArtTerms({ descriptionKo, descriptionEn }).map(({ termId }) => termId);

describe("the word table", () => {
  it("equals the app's words, term by term and in order", () => {
    const appWords = (JSON.parse(wordsText) as { terms: { id: string; korean: string[]; english: string[] }[] }).terms;
    expect(ART_TERM_WORDS.map((words) => ({ ...words }))).toEqual(appWords);
    expect(appWords).toHaveLength(50);
  });
});

describe("suggestArtTerms", () => {
  it("maps korean and english words to the vocabulary in vocabulary order", () => {
    expect(ids("대형 캔버스에 그린 회화 연작과 드로잉을 선보인다. 기억과 풍경을 다룬다.")).toEqual([
      "medium:painting",
      "medium:drawing",
      "theme:memory",
      "theme:nature",
      "theme:landscape",
      "mood:monumental",
    ]);
    // The app also reads the title ("Quiet Rooms" gives quiet-meditative there); a suggestion needs a sentence, so
    // Admin reads the description only.
    expect(ids("", "Site-specific installations and single-channel video explore identity.")).toEqual([
      "medium:installation",
      "medium:video",
      "theme:identity",
    ]);
  });

  it("matches english as whole words regardless of case", () => {
    expect(ids("", "The display replays a replayed scene.")).toEqual([]);
    expect(ids("", "PAINTINGS AND SCULPTURES.")).toEqual(["medium:painting", "medium:sculpture"]);
  });

  it("gives each term the first sentence that implies it, as written", () => {
    const descriptionKo = "작가의 첫 개인전이다. 백자와 도예 작업을 소개한다.\n기억을 다룬 회화도 함께 걸린다.";
    const suggestions = suggestArtTerms({ descriptionKo, descriptionEn: "" });
    expect(suggestions).toEqual([
      { termId: "medium:painting", sentence: "기억을 다룬 회화도 함께 걸린다." },
      { termId: "medium:craft", sentence: "백자와 도예 작업을 소개한다." },
      { termId: "medium:ceramics", sentence: "백자와 도예 작업을 소개한다." },
      { termId: "theme:memory", sentence: "기억을 다룬 회화도 함께 걸린다." },
    ]);
    for (const { sentence } of suggestions) expect(descriptionKo).toContain(sentence);
  });

  it("prefers the korean sentence and falls back to the english one", () => {
    expect(
      suggestArtTerms({ descriptionKo: "조각 신작을 선보인다.", descriptionEn: "New sculptures." }),
    ).toEqual([{ termId: "medium:sculpture", sentence: "조각 신작을 선보인다." }]);
    expect(suggestArtTerms({ descriptionKo: "신작을 선보인다.", descriptionEn: "New sculptures. Opening 10am." })).toEqual([
      { termId: "medium:sculpture", sentence: "New sculptures." },
    ]);
  });

  it("detects every expansion term from korean substrings and english whole words", () => {
    const cases: Record<string, [string, string]> = {
      "medium:ceramics": ["백자와 도예 작업", "porcelain vessels"],
      "medium:textile": ["섬유와 직물로 짠 화면", "woven textile works"],
      "medium:sound": ["사운드 설치", "a sound installation"],
      "medium:media-art": ["미디어아트 신작", "new media art"],
      "medium:architecture-design": ["건축가의 드로잉", "architecture and its models"],
      "style:dansaekhwa": ["단색화의 계보", "a history of dansaekhwa"],
      "style:minhwa": ["조선 민화", "joseon minhwa"],
      "style:korean-painting": ["수묵 한국화", "contemporary ink painting"],
      "style:pop": ["팝아트의 색", "pop art colours"],
      "style:surreal": ["초현실적 장면", "surrealist scenes"],
      "theme:body": ["신체의 감각", "bodily sensation"],
      "theme:time": ["시간성에 대한 탐구", "on temporality"],
      "theme:ecology": ["기후 위기", "the climate crisis"],
      "theme:labor": ["노동의 풍경", "domestic labour"],
      "theme:gender": ["젠더와 페미니즘", "feminist histories"],
      "theme:archive": ["아카이브 자료", "archival documents"],
      "theme:landscape": ["산수화", "mountain landscapes"],
      "mood:humorous": ["해학적인 장면", "a humorous turn"],
    };
    for (const [id, [korean, english]] of Object.entries(cases)) {
      expect(ids(korean), `${id} from ${korean}`).toContain(id);
      expect(ids("", english), `${id} from ${english}`).toContain(id);
    }
  });

  it("never suggests staff-only terms or words that describe the visit or the credits", () => {
    const suggested = ids(
      "어두운 방, 따뜻한 빛, 쓸쓸한 오후. 우리 집 이야기. 관람 시간은 오전 10시부터입니다. 전시 디자인과 그래픽 디자인, 기록 사진, 여성 작가 12인.",
      "A dark room, warm light and a melancholy afternoon at home. A body of work shown for the first time. Exhibition design by the studio. Opening time 10am.",
    );
    for (const id of [
      "theme:home",
      "mood:dark",
      "mood:warm",
      "mood:melancholy",
      "theme:time",
      "medium:architecture-design",
      "theme:body",
      "theme:archive",
      "theme:gender",
    ]) {
      expect(suggested, id).not.toContain(id);
    }
  });

  it("keeps a long sentence verbatim and within the grounding limit around the word", () => {
    const long = `${"가".repeat(500)} 회화 ${"나".repeat(500)}.`;
    const [suggestion] = suggestArtTerms({ descriptionKo: long, descriptionEn: "" });
    expect(suggestion.termId).toBe("medium:painting");
    expect(Array.from(suggestion.sentence).length).toBeLessThanOrEqual(MAX_GROUNDING_LENGTH);
    expect(suggestion.sentence).toContain("회화");
    expect(long).toContain(suggestion.sentence);
  });

  it("suggests nothing for a blank description", () => {
    expect(ids("", "")).toEqual([]);
  });
});
