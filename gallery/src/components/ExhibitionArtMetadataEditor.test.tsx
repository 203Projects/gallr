import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LocaleProvider } from "../i18n";
import { ExhibitionArtMetadataEditor } from "./ExhibitionArtMetadataEditor";

const terms = [
  { id: "photography", category: "medium" as const, nameKo: "사진", nameEn: "Photography" },
  { id: "quiet-meditative", category: "mood" as const, nameKo: "고요함", nameEn: "Quiet / meditative" },
];

describe("gallery ExhibitionArtMetadataEditor", () => {
  it("adds unresolved suggestions, preserves order, and selects controlled terms", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <LocaleProvider initialLocale="en">
        <ExhibitionArtMetadataEditor
          metadata={{
            artists: [{ id: "artist-one", nameKo: "김민정", nameEn: "Minjung Kim" }],
            terms: [],
          }}
          terms={terms}
          disabled={false}
          onChange={onChange}
          onSearchArtists={vi.fn().mockResolvedValue([])}
        />
      </LocaleProvider>,
    );

    await user.type(screen.getByRole("textbox", { name: "Suggested artist name (Korean)" }), "새 작가");
    await user.type(screen.getByRole("textbox", { name: "Suggested artist name (English)" }), "New Artist");
    await user.click(screen.getByRole("button", { name: "Add artist suggestion" }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      artists: [
        expect.objectContaining({ id: "artist-one" }),
        { id: null, nameKo: "새 작가", nameEn: "New Artist" },
      ],
    }));

    await user.click(screen.getByRole("checkbox", { name: /Photography/ }));
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      terms: [expect.objectContaining({ id: "photography" })],
    }));
    expect(within(screen.getByRole("group", { name: "Medium" }))
      .getByRole("checkbox", { name: /Photography/ })).toBeInTheDocument();
  });

  it("makes submitted metadata read-only", () => {
    render(
      <LocaleProvider initialLocale="en">
        <ExhibitionArtMetadataEditor
          metadata={{ artists: [], terms: [] }}
          terms={terms}
          disabled
          onChange={vi.fn()}
          onSearchArtists={vi.fn()}
        />
      </LocaleProvider>,
    );

    expect(screen.getByRole("searchbox", { name: "Search canonical artists" })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: /Photography/ })).toBeDisabled();
  });
});
