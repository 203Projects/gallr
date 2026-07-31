import { publicExhibitionSlug, publicExhibitionUrl } from "./publicExhibitionUrl";

describe("public exhibition URL", () => {
  it("matches the public web slug contract", () => {
    expect(publicExhibitionSlug({
      id: "abcd1234-5678",
      nameEn: "Void — Forms",
      nameKo: "보이드 폼",
    })).toBe("void-forms-abcd");
    expect(publicExhibitionSlug({
      id: "1234abcd-5678",
      nameEn: "",
      nameKo: "한국 단색화의 계보",
    })).toBe("한국-단색화의-계보-1234");
  });

  it("targets the visitor origin instead of the gallery workspace origin", () => {
    expect(publicExhibitionUrl({
      id: "abcd1234-5678",
      nameEn: "Void Forms",
      nameKo: "보이드 폼",
    })).toBe("https://gallrmap.com/exhibitions/void-forms-abcd/");
  });
});
