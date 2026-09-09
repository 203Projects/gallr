import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ExhibitionArtMetadataEditor } from "./ExhibitionArtMetadataEditor";

const terms = [
  { id: "photography", category: "medium" as const, nameKo: "사진", nameEn: "Photography" },
  { id: "quiet-meditative", category: "mood" as const, nameKo: "고요함", nameEn: "Quiet / meditative" },
];

function deferred<Value>() {
  let resolve!: (value: Value) => void;
  const promise = new Promise<Value>((complete) => { resolve = complete; });
  return { promise, resolve };
}

describe("ExhibitionArtMetadataEditor", () => {
  it("renders ordered unresolved credits, grouped terms, and accessible controls", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <ExhibitionArtMetadataEditor
        metadata={{
          artists: [
            { id: null, nameKo: "새 작가", nameEn: "New Artist" },
            { id: "artist-two", nameKo: "김민정", nameEn: "Minjung Kim" },
          ],
          terms: [],
        }}
        terms={terms}
        disabled={false}
        onChange={onChange}
        onSearchArtists={vi.fn().mockResolvedValue([])}
        onCreateArtist={vi.fn()}
      />,
    );

    const list = screen.getByRole("list", { name: "Ordered artist credits" });
    expect(within(list).getByText("UNRESOLVED")).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Medium" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Mood / tone" })).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: /Photography/ }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      terms: [expect.objectContaining({ id: "photography" })],
    }));

    await user.click(screen.getByRole("button", { name: "Move Minjung Kim up" }));
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      artists: [
        expect.objectContaining({ id: "artist-two" }),
        expect.objectContaining({ id: null }),
      ],
    }));
  });

  it("searches and replaces an unresolved suggestion with a canonical artist", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <ExhibitionArtMetadataEditor
        metadata={{ artists: [{ id: null, nameKo: "김민정", nameEn: "Minjung Kim" }], terms: [] }}
        terms={terms}
        disabled={false}
        onChange={onChange}
        onSearchArtists={vi.fn().mockResolvedValue([
          { id: "artist-one", nameKo: "김민정", nameEn: "Minjung Kim" },
        ])}
        onCreateArtist={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Resolve Minjung Kim" }));
    await user.type(screen.getByRole("searchbox", { name: "Search canonical artists" }), "Kim");
    await user.click(await screen.findByRole("button", { name: "Use Minjung Kim" }));
    expect(onChange).toHaveBeenCalledWith({
      artists: [{ id: "artist-one", nameKo: "김민정", nameEn: "Minjung Kim" }],
      terms: [],
    });
  });

  it("merges a created artist into the latest metadata and locks edits while creating", async () => {
    const user = userEvent.setup();
    const creation = deferred<{ id: string; nameKo: string; nameEn: string }>();
    const onChange = vi.fn();
    const props = {
      terms,
      disabled: false,
      onChange,
      onSearchArtists: vi.fn().mockResolvedValue([]),
      onCreateArtist: vi.fn().mockReturnValue(creation.promise),
    };
    const { rerender } = render(
      <ExhibitionArtMetadataEditor
        {...props}
        metadata={{ artists: [], terms: [] }}
      />,
    );

    await user.type(screen.getByRole("textbox", { name: "Artist name (Korean)" }), "김민정");
    await user.type(screen.getByRole("textbox", { name: "Artist name (English)" }), "Minjung Kim");
    await user.click(screen.getByRole("button", { name: "Create artist" }));
    expect(screen.getByRole("checkbox", { name: /Photography/ })).toBeDisabled();

    const latestMetadata = { artists: [], terms: [terms[0]] };
    rerender(<ExhibitionArtMetadataEditor {...props} metadata={latestMetadata} />);
    await act(async () => creation.resolve({
      id: "artist-created",
      nameKo: "김민정",
      nameEn: "Minjung Kim",
    }));

    expect(onChange).toHaveBeenLastCalledWith({
      artists: [{ id: "artist-created", nameKo: "김민정", nameEn: "Minjung Kim" }],
      terms: [terms[0]],
    });
  });

  it("ignores artist creation completion after unmount", async () => {
    const user = userEvent.setup();
    const creation = deferred<{ id: string; nameKo: string; nameEn: string }>();
    const onChange = vi.fn();
    const { unmount } = render(
      <ExhibitionArtMetadataEditor
        metadata={{ artists: [], terms: [] }}
        terms={terms}
        disabled={false}
        onChange={onChange}
        onSearchArtists={vi.fn().mockResolvedValue([])}
        onCreateArtist={vi.fn().mockReturnValue(creation.promise)}
      />,
    );
    await user.type(screen.getByRole("textbox", { name: "Artist name (Korean)" }), "김민정");
    await user.type(screen.getByRole("textbox", { name: "Artist name (English)" }), "Minjung Kim");
    await user.click(screen.getByRole("button", { name: "Create artist" }));
    unmount();
    await act(async () => creation.resolve({
      id: "artist-created",
      nameKo: "김민정",
      nameEn: "Minjung Kim",
    }));
    expect(onChange).not.toHaveBeenCalled();
  });
});
