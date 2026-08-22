import { afterEach, describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  GALLERY_LOCALE_STORAGE_KEY,
  LocaleProvider,
  LocaleToggle,
  alternateBilingual,
  formatDateOnly,
  formatNumber,
  formatTime,
  localizeBilingual,
  resolvePortalLocale,
  useLocale,
} from "./i18n";

afterEach(() => {
  window.localStorage.clear();
  document.documentElement.lang = "en";
});

function LocaleProbe() {
  const { messages } = useLocale();
  return (
    <div>
      <LocaleToggle />
      <label>
        {messages.auth.email}
        <input defaultValue="dirty value" />
      </label>
      <span>{messages.navigation.exhibitions}</span>
    </div>
  );
}

describe("Gallery locale", () => {
  it("resolves a saved locale before the browser and otherwise preserves the English default", () => {
    expect(resolvePortalLocale("en", "ko-KR")).toBe("en");
    expect(resolvePortalLocale("ko", "en-US")).toBe("ko");
    expect(resolvePortalLocale(null, "ko-KR")).toBe("ko");
    expect(resolvePortalLocale(null, "en-US")).toBe("en");
    expect(resolvePortalLocale("unsupported", "fr-FR")).toBe("en");
  });

  it("persists live switching, synchronizes document language, and preserves dirty state", async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(GALLERY_LOCALE_STORAGE_KEY, "ko");

    render(
      <LocaleProvider>
        <LocaleProbe />
      </LocaleProvider>,
    );

    expect(document.documentElement.lang).toBe("ko");
    expect(screen.getByText("전시")).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "언어" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "한국어" })).toHaveAttribute("aria-pressed", "true");

    const input = screen.getByRole("textbox", { name: "이메일" });
    await user.clear(input);
    await user.type(input, "owner@example.test");
    await user.click(screen.getByRole("button", { name: "EN" }));

    expect(document.documentElement.lang).toBe("en");
    expect(window.localStorage.getItem(GALLERY_LOCALE_STORAGE_KEY)).toBe("en");
    expect(screen.getByText("Exhibitions")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Email" })).toHaveValue("owner@example.test");
  });

  it("localizes bilingual read labels and display-only values without changing canonical input", () => {
    expect(localizeBilingual("작은 방의 기록", "Notes from a Small Room", "ko"))
      .toBe("작은 방의 기록");
    expect(localizeBilingual("작은 방의 기록", "Notes from a Small Room", "en"))
      .toBe("Notes from a Small Room");
    expect(localizeBilingual("대체 제목", "  ", "en")).toBe("대체 제목");
    expect(localizeBilingual("", "English fallback", "ko")).toBe("English fallback");
    expect(alternateBilingual("대체 제목", "  ", "en")).toBe("");
    expect(alternateBilingual("한국어 이름", "English name", "ko")).toBe("English name");

    expect(formatDateOnly("2026-08-22", "ko")).toBe("2026년 8월 22일");
    expect(formatDateOnly("2026-08-22", "en")).toBe("Aug 22, 2026");
    expect(formatTime("2026-08-22T09:05:00Z", "ko")).toBe("오후 6:05");
    expect(formatTime("2026-08-21T21:05:00Z", "ko")).toBe("오전 6:05");
    expect(formatTime("2026-08-22T09:05:00Z", "en")).toBe("6:05 PM");
    expect(formatNumber(1_234_567, "ko")).toBe("1,234,567");
  });
});
