import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import type { ExhibitionArtMetadata } from "../domain";
import { LocaleProvider } from "../i18n";
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
    expect(screen.getByRole("searchbox")).toHaveValue("Minjung Kim");
    expect(screen.getByRole("searchbox")).toHaveFocus();
    expect(screen.getByText('Resolving "Minjung Kim" — choose a matching artist below, or create a new one.')).toBeInTheDocument();
    expect(screen.getByText("RESOLVING")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Artist name (Korean)" })).toHaveValue("김민정");
    expect(screen.getByRole("textbox", { name: "Artist name (English)" })).toHaveValue("Minjung Kim");
    await user.click(await screen.findByRole("button", { name: "Link to Minjung Kim" }));
    expect(onChange).toHaveBeenCalledWith({
      artists: [{ id: "artist-one", nameKo: "김민정", nameEn: "Minjung Kim" }],
      terms: [],
    });
    expect(screen.queryByRole("button", { name: "Cancel resolution" })).not.toBeInTheDocument();
    expect(screen.getByRole("searchbox")).toHaveValue("");
  });

  const suggestion = { id: null, nameKo: "윤홍일", nameEn: "Hongil Yoon" };
  const canonical = { ...suggestion, id: "artist-created" };

  function ResolutionHarness({
    artists = [suggestion] as ExhibitionArtMetadata["artists"],
    search = vi.fn().mockResolvedValue([]),
    create = vi.fn().mockResolvedValue(canonical),
  }) {
    const [metadata, setMetadata] = useState<ExhibitionArtMetadata>({ artists, terms: [terms[0]] });
    return <ExhibitionArtMetadataEditor metadata={metadata} terms={terms} disabled={false} onChange={setMetadata} onSearchArtists={search} onCreateArtist={create} />;
  }

  it("creates and links the prefilled suggestion in one click and ends resolution", async () => {
    const user = userEvent.setup();
    const search = vi.fn().mockResolvedValue([]);
    const create = vi.fn().mockResolvedValue(canonical);
    render(<ResolutionHarness search={search} create={create} />);
    await user.click(screen.getByRole("button", { name: "Resolve Hongil Yoon" }));
    expect(await screen.findByText('No match — create "Hongil Yoon" as a new canonical artist below.')).toBeInTheDocument();
    expect(search).toHaveBeenCalledWith("Hongil Yoon");
    await user.click(screen.getByRole("button", { name: "Create Hongil Yoon" }));
    expect(create).toHaveBeenCalledWith("윤홍일", "Hongil Yoon", expect.any(String));
    await waitFor(() => expect(screen.queryByText("RESOLVING")).not.toBeInTheDocument());
    const list = screen.getByRole("list", { name: "Ordered artist credits" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(1);
    expect(screen.queryByText("UNRESOLVED")).not.toBeInTheDocument();
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.getByRole("checkbox", { name: /Photography/ })).toBeChecked();
  });

  it("cancels without changing credits, restores the creation draft, and ignores a stale search", async () => {
    const user = userEvent.setup();
    const pending = deferred<typeof canonical[]>();
    const search = vi.fn().mockReturnValue(pending.promise);
    render(<ResolutionHarness search={search} />);
    await user.type(screen.getByRole("textbox", { name: "Artist name (Korean)" }), "이전 초안");
    await user.type(screen.getByRole("textbox", { name: "Artist name (English)" }), "Previous draft");
    await user.click(screen.getByRole("button", { name: "Resolve Hongil Yoon" }));
    await waitFor(() => expect(search).toHaveBeenCalled());
    await user.click(screen.getByRole("button", { name: "Cancel resolution" }));
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.getByText("UNRESOLVED")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Artist name (Korean)" })).toHaveValue("이전 초안");
    expect(screen.getByRole("textbox", { name: "Artist name (English)" })).toHaveValue("Previous draft");
    await act(async () => pending.resolve([canonical]));
    expect(screen.queryByRole("button", { name: /Link to|Use Hongil/ })).not.toBeInTheDocument();
  });

  it("keeps resolution on the same artist when credits are reordered or removed", async () => {
    const user = userEvent.setup();
    const other = { id: "other", nameKo: "다른 작가", nameEn: "Other Artist" };
    render(<ResolutionHarness artists={[other, suggestion]} search={vi.fn().mockResolvedValue([canonical])} />);
    await user.click(screen.getByRole("button", { name: "Resolve Hongil Yoon" }));
    await user.click(screen.getByRole("button", { name: "Move Hongil Yoon up" }));
    await user.click(screen.getByRole("button", { name: "Remove Other Artist" }));
    await user.click(await screen.findByRole("button", { name: "Link to Hongil Yoon" }));
    expect(screen.queryByText("UNRESOLVED")).not.toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Ordered artist credits" })).toHaveTextContent("Hongil Yoon");
  });

  it("deduplicates an existing canonical credit when linking a suggestion", async () => {
    const user = userEvent.setup();
    render(<ResolutionHarness artists={[canonical, suggestion]} search={vi.fn().mockResolvedValue([canonical])} />);
    await user.click(screen.getByRole("button", { name: "Resolve Hongil Yoon" }));
    await user.click(await screen.findByRole("button", { name: "Link to Hongil Yoon" }));
    expect(within(screen.getByRole("list", { name: "Ordered artist credits" })).getAllByRole("listitem")).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "Cancel resolution" })).not.toBeInTheDocument();
  });

  it("shows Korean resolution copy and prefills the Korean search name", async () => {
    const user = userEvent.setup();
    render(<LocaleProvider initialLocale="ko"><ResolutionHarness /></LocaleProvider>);
    await user.click(screen.getByRole("button", { name: "윤홍일 해결" }));
    expect(screen.getByRole("searchbox")).toHaveValue("윤홍일");
    expect(screen.getByRole("searchbox")).toHaveFocus();
    expect(screen.getByText('"윤홍일" 연결 중 — 아래에서 일치하는 작가를 선택하거나 새 작가를 만드세요.')).toBeInTheDocument();
    expect(await screen.findByText('일치하는 작가가 없습니다 — 아래에서 "윤홍일"을 새 등록 작가로 만드세요.')).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "윤홍일 만들기" })).toBeEnabled();
  });

  it("does not invent a missing translation or enable one-click creation without both names", async () => {
    const user = userEvent.setup();
    render(<ResolutionHarness artists={[{ ...suggestion, nameKo: "" }]} />);
    await user.click(screen.getByRole("button", { name: "Resolve Hongil Yoon" }));
    expect(screen.getByRole("textbox", { name: "Artist name (Korean)" })).toHaveValue("");
    expect(screen.getByRole("button", { name: "Create Hongil Yoon" })).toBeDisabled();
  });

  it("keeps existing results when Resolve is clicked again on the active row", async () => {
    const user = userEvent.setup();
    render(<ResolutionHarness search={vi.fn().mockResolvedValue([canonical])} />);
    await user.click(screen.getByRole("button", { name: "Resolve Hongil Yoon" }));
    await screen.findByRole("button", { name: "Link to Hongil Yoon" });
    await user.click(screen.getByRole("button", { name: "Resolve Hongil Yoon" }));
    expect(screen.getByRole("button", { name: "Link to Hongil Yoon" })).toBeInTheDocument();
    expect(screen.getByRole("searchbox")).toHaveFocus();
  });

  it("searches a one-character suggestion immediately in resolve mode", async () => {
    const user = userEvent.setup();
    const search = vi.fn().mockResolvedValue([]);
    render(<ResolutionHarness artists={[{ id: null, nameKo: "김", nameEn: "K" }]} search={search} />);
    await user.click(screen.getByRole("button", { name: "Resolve K" }));
    await waitFor(() => expect(search).toHaveBeenCalledWith("K"));
  });

  it("retains resolve mode and request identity across an ambiguous create retry", async () => {
    const user = userEvent.setup();
    const create = vi.fn().mockRejectedValueOnce(new Error("network")).mockResolvedValueOnce(canonical);
    render(<ResolutionHarness create={create} />);
    await user.click(screen.getByRole("button", { name: "Resolve Hongil Yoon" }));
    await user.click(screen.getByRole("button", { name: "Create Hongil Yoon" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Artist could not be created");
    expect(screen.getByText("RESOLVING")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create Hongil Yoon" }));
    expect(create.mock.calls[0][2]).toBe(create.mock.calls[1][2]);
    await waitFor(() => expect(screen.queryByText("RESOLVING")).not.toBeInTheDocument());
  });

  it("searches long valid names within the canonical lookup query limit", async () => {
    const user = userEvent.setup();
    const nameEn = "A".repeat(200);
    const search = vi.fn().mockResolvedValue([]);
    render(<ResolutionHarness artists={[{ id: null, nameKo: "긴 이름", nameEn }]} search={search} />);
    await user.click(screen.getByRole("button", { name: `Resolve ${nameEn}` }));
    expect(screen.getByRole("searchbox")).toHaveValue(nameEn);
    await waitFor(() => expect(search).toHaveBeenCalledWith("A".repeat(100)));
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
