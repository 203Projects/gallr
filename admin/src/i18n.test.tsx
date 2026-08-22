import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import {
  ADMIN_LOCALE_STORAGE_KEY,
  LanguageSwitch,
  LocaleProvider,
  alternateLocalizedText,
  localizedText,
  formatDisplayDate,
  formatDisplayDateTime,
  resolveLocale,
  translateMessage,
  uiErrorMessage,
  uiMessageText,
  useI18n,
  type LocaleStorage,
} from "./i18n";

function memoryStorage(): LocaleStorage & { values: Map<string, string> } {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, value); },
  };
}

function LocaleProbe() {
  const { locale, t } = useI18n();
  const [draft, setDraft] = useState("");
  return (
    <>
      <LanguageSwitch />
      <p>{locale}</p>
      <p>{t("navigation.exhibitions")}</p>
      <label>
        <span>{t("fields.unsavedDraft")}</span>
        <input value={draft} onChange={(event) => setDraft(event.target.value)} />
      </label>
    </>
  );
}

describe("Admin locale", () => {
  beforeEach(() => {
    document.documentElement.lang = "en";
  });

  it("resolves a saved locale before Korean browser language and otherwise keeps English", () => {
    expect(resolveLocale("en", ["ko-KR"])).toBe("en");
    expect(resolveLocale(null, ["ko-KR", "en-US"])).toBe("ko");
    expect(resolveLocale(null, ["en-US", "ko-KR"])).toBe("en");
    expect(resolveLocale("unsupported", ["fr-FR"])).toBe("en");
    expect(resolveLocale(null, [])).toBe("en");
  });

  it("switches live, persists the locale, updates document language, and preserves form state", async () => {
    const user = userEvent.setup();
    const storage = memoryStorage();
    render(
      <LocaleProvider initialLocale="en" storage={storage}>
        <LocaleProbe />
      </LocaleProvider>,
    );

    await user.type(screen.getByRole("textbox"), "unsaved text");
    await user.click(screen.getByRole("button", { name: "Korean" }));

    expect(screen.getByText("ko")).toBeInTheDocument();
    expect(screen.getByText("전시")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveValue("unsaved text");
    expect(document.documentElement.lang).toBe("ko");
    expect(storage.getItem(ADMIN_LOCALE_STORAGE_KEY)).toBe("ko");
  });

  it("uses a saved preference ahead of Korean browser language", () => {
    const storage = memoryStorage();
    storage.setItem(ADMIN_LOCALE_STORAGE_KEY, "en");
    render(
      <LocaleProvider browserLanguages={["ko-KR"]} storage={storage}>
        <LocaleProbe />
      </LocaleProvider>,
    );

    expect(screen.getByText("en")).toBeInTheDocument();
    expect(document.documentElement.lang).toBe("en");
  });

  it("keeps rendering when browser storage is unavailable", () => {
    const storage: LocaleStorage = {
      getItem: () => { throw new DOMException("blocked"); },
      setItem: () => { throw new DOMException("blocked"); },
    };

    expect(() => render(
      <LocaleProvider browserLanguages={["ko-KR"]} storage={storage}>
        <LocaleProbe />
      </LocaleProvider>,
    )).not.toThrow();
    expect(screen.getByText("ko")).toBeInTheDocument();
  });

  it("prefers the active entity language and falls back to the available counterpart", () => {
    expect(localizedText("ko", "한국어 이름", "English name")).toBe("한국어 이름");
    expect(localizedText("en", "한국어 이름", "English name")).toBe("English name");
    expect(localizedText("ko", "", "English fallback")).toBe("English fallback");
    expect(localizedText("en", "한국어 대체", "")).toBe("한국어 대체");
    expect(alternateLocalizedText("en", "한국어 대체", "")).toBe("");
    expect(alternateLocalizedText("ko", "한국어 이름", "English name")).toBe("English name");
  });

  it("formats calendar dates without shifting them and displays instants in Seoul", () => {
    expect(formatDisplayDate("2026-08-11", "ko")).toContain("2026");
    expect(formatDisplayDate("2026-08-11", "ko")).toContain("11");
    expect(formatDisplayDateTime("2026-08-11T15:30:00Z", "en"))
      .toContain("Aug 12, 2026");
  });

  it("uses a localized interface fallback instead of leaking an English client error", () => {
    const message = uiErrorMessage(
      new Error("Choose a JPEG, PNG, or WebP image."),
      "notice.mediaUpdateFailed",
    );

    expect(uiMessageText(message, (key, parameters) =>
      translateMessage("ko", key, parameters)))
      .toBe("이미지를 변경하지 못했습니다.");
  });
});
