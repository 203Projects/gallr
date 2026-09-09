import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App, { AdminWorkspace } from "./App";
import type { AdminExhibition, AdminMediaAsset } from "./domain";
import { LocaleProvider } from "./i18n";
import {
  DraftDeleteBlockedError,
  RevisionConflictError,
} from "./repositories/AdminExhibitionRepository";
import { InMemoryAdminExhibitionRepository } from "./repositories/InMemoryAdminExhibitionRepository";

describe("gallr admin", () => {
  it("renders a representative exhibition workflow in Korean", async () => {
    render(
      <LocaleProvider initialLocale="ko">
        <AdminWorkspace
          repository={new InMemoryAdminExhibitionRepository()}
          staffRole="admin"
        />
      </LocaleProvider>,
    );

    expect(await screen.findByRole("heading", { name: "전시" }))
      .toBeInTheDocument();
    expect(screen.getByLabelText("전시 검색")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "새 전시" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "게시됨" })).toBeInTheDocument();
    expect(screen.getByLabelText("커버 이미지 없는 전시만")).not.toBeChecked();
  });

  it("reviews a gallery submission and accepts it as an unpublished draft", async () => {
    const user = userEvent.setup();
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        staffRole="admin"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "Submissions" }));

    expect(await screen.findByRole("heading", { name: "Submissions" }))
      .toBeInTheDocument();
    expect(screen.getAllByText("기억의 층위").length).toBeGreaterThan(0);
    expect(screen.getAllByText("gallery@example.com").length).toBeGreaterThan(0);
    await user.click(screen.getByRole("button", { name: "Start review" }));
    expect(await screen.findAllByText("In review")).not.toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Accept as draft" }));
    expect(await screen.findByRole("heading", { name: "Exhibitions" }))
      .toBeInTheDocument();
    expect(screen.getByLabelText("Exhibition name (Korean) *")).toHaveValue("기억의 층위");
    expect(
      screen.getByRole("status").textContent,
    ).toMatch(/accepted as an unpublished draft/i);
  });

  it("requires a reason before rejecting a gallery submission", async () => {
    const user = userEvent.setup();
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        staffRole="admin"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "Submissions" }));
    await screen.findAllByText("기억의 층위");
    expect(screen.getByRole("button", { name: "Reject" })).toBeDisabled();
    await user.type(
      screen.getByLabelText("Reason if rejected"),
      "Please confirm the venue dates.",
    );
    await user.click(screen.getByRole("button", { name: "Reject" }));
    expect(await screen.findAllByText("Rejected")).not.toHaveLength(0);
    expect(screen.getByText("Please confirm the venue dates."))
      .toBeInTheDocument();
  });

  it("distinguishes owner submissions and opens the existing canonical draft", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const createDraft = vi.spyOn(repository, "createDraft");
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "Submissions" }));
    await screen.findByRole("heading", { name: "Submissions" });
    await user.click(screen.getByRole("row", { name: /Owner workspace/ }));

    expect(screen.getByLabelText("Changes requested")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Request changes" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Accept owner draft" }));

    expect(await screen.findByRole("heading", { name: "Exhibitions" })).toBeInTheDocument();
    expect(screen.getByLabelText("Exhibition name (Korean) *")).toHaveValue("서로 다른 시간");
    expect(createDraft).not.toHaveBeenCalled();
  });

  it("filters exhibitions by search, publish state, date state, placement, and cover", async () => {
    const user = userEvent.setup();
    render(<App />);

    expect((await screen.findAllByText("서로 다른 시간")).length).toBeGreaterThan(0);
    await user.type(screen.getByLabelText("Search exhibitions"), "빛의 문법");
    expect((await screen.findAllByText("빛의 문법")).length).toBeGreaterThan(0);
    expect(screen.queryByText("기억의 표면")).not.toBeInTheDocument();

    await user.clear(screen.getByLabelText("Search exhibitions"));
    await user.click(screen.getByRole("button", { name: "Archived" }));
    expect(await screen.findByText("낯선 정원")).toBeInTheDocument();
    expect(screen.queryByText("빛의 문법")).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Exhibition date status"), "ended");
    await user.click(screen.getByLabelText("Featured on homepage only"));
    await user.selectOptions(screen.getByLabelText("Sort exhibitions"), "opening_asc");

    expect(screen.getByLabelText("Exhibition date status")).toHaveValue("ended");
    expect(screen.getByLabelText("Featured on homepage only")).toBeChecked();
    expect(screen.getByLabelText("Sort exhibitions")).toHaveValue("opening_asc");

    await user.click(screen.getByRole("button", { name: "All" }));
    await user.selectOptions(screen.getByLabelText("Exhibition date status"), "all");
    await user.click(screen.getByLabelText("Featured on homepage only"));
    expect((await screen.findAllByText("빛의 문법")).length).toBeGreaterThan(0);

    await user.click(screen.getByLabelText("Missing cover image only"));
    expect(screen.getByLabelText("Missing cover image only")).toBeChecked();
    await waitFor(() =>
      expect(screen.queryByText("빛의 문법")).not.toBeInTheDocument(),
    );
    expect(screen.getAllByText("서로 다른 시간").length).toBeGreaterThan(0);

    await user.click(screen.getByLabelText("Missing cover image only"));
    expect(screen.getByLabelText("Missing cover image only")).not.toBeChecked();
    expect((await screen.findAllByText("빛의 문법")).length).toBeGreaterThan(0);
  });

  it("ignores a stale exhibition response after the filters change", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const initial = await repository.list({ search: "", status: "All" });
    let resolvePublished: (records: AdminExhibition[]) => void = () => undefined;
    let resolveArchived: (records: AdminExhibition[]) => void = () => undefined;
    const published = new Promise<AdminExhibition[]>((resolve) => {
      resolvePublished = resolve;
    });
    const archived = new Promise<AdminExhibition[]>((resolve) => {
      resolveArchived = resolve;
    });
    const list = vi.spyOn(repository, "list").mockImplementation((filters) => {
      if (filters.status === "Published") return published;
      if (filters.status === "Archived") return archived;
      return Promise.resolve(initial);
    });
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findByRole("table", { name: "Exhibitions" });
    await user.click(screen.getByRole("button", { name: "Published" }));
    await waitFor(() =>
      expect(list).toHaveBeenCalledWith(expect.objectContaining({ status: "Published" })),
    );
    await user.click(screen.getByRole("button", { name: "Archived" }));
    await waitFor(() =>
      expect(list).toHaveBeenCalledWith(expect.objectContaining({ status: "Archived" })),
    );

    await act(async () => {
      resolveArchived(initial.filter((record) => record.status === "Archived"));
    });
    const table = await screen.findByRole("table", { name: "Exhibitions" });
    expect(within(table).getByText("낯선 정원")).toBeInTheDocument();

    await act(async () => {
      resolvePublished(initial.filter((record) => record.status === "Published"));
    });
    expect(within(table).getByText("낯선 정원")).toBeInTheDocument();
    expect(within(table).queryByText("빛의 문법")).not.toBeInTheDocument();
  });

  it("keeps a saved revision when an older list snapshot resolves afterward", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const initial = await repository.list({ search: "", status: "All" });
    const first = initial[0];
    let resolveSave: (record: AdminExhibition) => void = () => undefined;
    let resolveList: (records: AdminExhibition[]) => void = () => undefined;
    const staleList = new Promise<AdminExhibition[]>((resolve) => {
      resolveList = resolve;
    });
    const list = vi.spyOn(repository, "list").mockImplementation((filters) =>
      filters.status === first.status ? staleList : Promise.resolve(initial)
    );
    const saveDraft = vi.spyOn(repository, "saveDraft").mockImplementation(
      () => new Promise<AdminExhibition>((resolve) => {
        resolveSave = resolve;
      }),
    );
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findByRole("table", { name: "Exhibitions" });
    await user.click(screen.getByRole("button", { name: first.status }));
    await waitFor(() =>
      expect(list).toHaveBeenCalledWith(expect.objectContaining({ status: first.status }))
    );
    await user.type(screen.getByLabelText("Exhibition name (Korean) *"), "!");
    await waitFor(() => expect(saveDraft).toHaveBeenCalled(), { timeout: 2500 });

    const saved = { ...first, nameKo: `${first.nameKo}!`, revision: first.revision + 1 };
    await act(async () => resolveSave(saved));

    await act(async () => resolveList([first]));
    await waitFor(() => {
      const currentTable = screen.getByRole("table", { name: "Exhibitions" });
      expect(within(currentTable).getByText(saved.nameKo)).toBeInTheDocument();
      expect(within(currentTable).queryByText(first.nameKo)).not.toBeInTheDocument();
    });
  });

  it("clears previous rows when the current filter request fails", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const initial = await repository.list({ search: "", status: "All" });
    vi.spyOn(repository, "list").mockImplementation((filters) =>
      filters.status === "Archived"
        ? Promise.reject(new Error("list unavailable"))
        : Promise.resolve(initial)
    );
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    const table = await screen.findByRole("table", { name: "Exhibitions" });
    expect(within(table).getByText(initial[0].nameKo)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Archived" }));

    expect(
      (await screen.findAllByText("Exhibitions could not be loaded.")).length,
    ).toBeGreaterThan(0);
    expect(screen.queryByRole("table", { name: "Exhibitions" })).not.toBeInTheDocument();
    expect(screen.getByText("0 exhibitions")).toBeInTheDocument();
  });

  it("merges an in-flight save against the filters current when it resolves", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const [first] = await repository.list({ search: "", status: "All" });
    let resolveSave: (record: AdminExhibition) => void = () => undefined;
    const saveDraft = vi.spyOn(repository, "saveDraft").mockImplementation(
      () =>
        new Promise<AdminExhibition>((resolve) => {
          resolveSave = resolve;
        }),
    );
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    const table = await screen.findByRole("table", { name: "Exhibitions" });
    expect(within(table).getByText(first.nameKo)).toBeInTheDocument();
    await user.type(screen.getByLabelText("Exhibition name (Korean) *"), "!");
    await waitFor(() => expect(saveDraft).toHaveBeenCalled(), { timeout: 2500 });

    await user.click(screen.getByLabelText("Missing cover image only"));
    await waitFor(() =>
      expect(screen.getByLabelText("Missing cover image only")).toBeChecked(),
    );

    await act(async () => {
      resolveSave({
        ...first,
        nameKo: `${first.nameKo}!`,
        revision: first.revision + 1,
        coverImageUrl: "https://images.example.test/just-uploaded.webp",
      });
    });

    await waitFor(() =>
      expect(
        within(screen.getByRole("table", { name: "Exhibitions" }))
          .queryByText(`${first.nameKo}!`),
      ).not.toBeInTheDocument(),
    );
  });

  it("creates and autosaves a new exhibition draft", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "New exhibition" }));

    const title = screen.getByLabelText("Exhibition name (Korean) *");
    expect(title).toHaveValue("");
    await user.type(title, "새로운 전시");

    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(screen.getAllByText("새로운 전시").length).toBeGreaterThan(0);
  });

  it("omits unchanged art metadata from unrelated autosaves and includes explicit metadata edits", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const saveDraft = vi.spyOn(repository, "saveDraft");
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findAllByText("서로 다른 시간");
    await user.type(screen.getByLabelText("Exhibition name (Korean) *"), " 수정");
    await waitFor(() => expect(saveDraft).toHaveBeenCalled(), { timeout: 2500 });
    expect(saveDraft.mock.calls[0][3]).not.toHaveProperty("artMetadata");

    saveDraft.mockClear();
    await user.click(screen.getByRole("tab", { name: "Art" }));
    await user.click(screen.getByRole("checkbox", { name: /Photography/ }));
    await waitFor(() => expect(saveDraft).toHaveBeenCalled(), { timeout: 2500 });
    expect(saveDraft.mock.calls[0][3]).toHaveProperty("artMetadata.terms.0.id", "photography");
  });

  it("defaults new exhibitions to homepage placement", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.click(screen.getByRole("tab", { name: "Curation" }));

    expect(screen.getByLabelText("Featured on homepage")).toBeChecked();
  });

  it("reuses a past venue without replacing exhibition-specific details", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.type(screen.getByLabelText("Exhibition name (Korean) *"), "새 전시 제목");
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );

    await user.click(screen.getByRole("tab", { name: "Venue" }));
    await user.type(screen.getByRole("searchbox", { name: "Search past venues" }), "오오");
    await user.click(
      screen.getByRole("button", {
        name: "Use venue Artspace OOO, Seoul Yongsan-gu, 55 Itaewon-ro, Yongsan-gu, Seoul",
      }),
    );

    expect(screen.getByLabelText("Venue name (Korean) *")).toHaveValue(
      "아트스페이스 오오",
    );
    expect(screen.getByLabelText("Address (Korean) *")).toHaveValue(
      "서울 용산구 이태원로 55",
    );
    expect(screen.getByLabelText("Latitude *")).toHaveValue("37.5348");
    expect(screen.getByLabelText("Longitude *")).toHaveValue("127.0010");

    await user.click(screen.getByRole("tab", { name: "Basics" }));
    expect(screen.getByLabelText("Exhibition name (Korean) *")).toHaveValue("새 전시 제목");
  });

  it("serializes edits made while an autosave is in flight and rebases them onto the saved revision", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const originalSave = repository.saveDraft.bind(repository);
    let releaseFirstSave: (() => void) | null = null;
    let markFirstSaveStarted: (() => void) | null = null;
    const firstSaveStarted = new Promise<void>((resolve) => {
      markFirstSaveStarted = resolve;
    });
    let attempt = 0;
    let activeSaves = 0;
    let maxActiveSaves = 0;
    repository.saveDraft = vi.fn(
      async (...args: Parameters<typeof originalSave>) => {
        attempt += 1;
        activeSaves += 1;
        maxActiveSaves = Math.max(maxActiveSaves, activeSaves);
        try {
          if (attempt === 1) {
            markFirstSaveStarted?.();
            await new Promise<void>((resolve) => {
              releaseFirstSave = resolve;
            });
          }
          return await originalSave(...args);
        } finally {
          activeSaves -= 1;
        }
      },
    );

    render(<AdminWorkspace repository={repository} staffRole="admin" />);
    await screen.findAllByText("서로 다른 시간");

    await user.type(screen.getByLabelText("Exhibition name (Korean) *"), " — 1차");
    await firstSaveStarted;
    await user.type(screen.getByLabelText("Exhibition name (English)"), " queued");
    await new Promise((resolve) => window.setTimeout(resolve, 700));

    expect(repository.saveDraft).toHaveBeenCalledTimes(1);

    await act(async () => {
      releaseFirstSave?.();
    });

    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(repository.saveDraft).toHaveBeenCalledTimes(2);
    const saveDraft = vi.mocked(repository.saveDraft);
    expect(saveDraft.mock.calls[1][2]).toBe(saveDraft.mock.calls[0][2] + 1);
    expect(maxActiveSaves).toBe(1);
    const attemptedRevisions =
      new Set(
        saveDraft.mock.calls.map((call) =>
          `${call[0]}:${call[1]}:${call[2]}`
        ),
      );
    expect(attemptedRevisions.size).toBe(2);
    expect(screen.getByLabelText("Exhibition name (Korean) *")).toHaveValue(
      "서로 다른 시간 — 1차",
    );
    expect(screen.getByLabelText("Exhibition name (English)")).toHaveValue(
      "Different Times queued",
    );
    expect(screen.queryByText("! A newer revision exists")).not.toBeInTheDocument();
  });

  it("blocks navigation after an ambiguous save error until the editor reloads", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const originalSave = repository.saveDraft.bind(repository);
    const onSignOut = vi.fn();
    let attempt = 0;
    repository.saveDraft = vi.fn(
      async (...args: Parameters<typeof originalSave>) => {
        attempt += 1;
        if (attempt === 1) throw new Error("Temporary save outage.");
        return originalSave(...args);
      },
    );

    render(
      <AdminWorkspace
        repository={repository}
        staffRole="admin"
        onSignOut={onSignOut}
      />,
    );
    await screen.findAllByText("서로 다른 시간");
    const title = screen.getByLabelText("Exhibition name (Korean) *");
    await user.type(title, " — 보존");

    expect(await screen.findByText("! Save failed", {}, { timeout: 2500 }))
      .toBeInTheDocument();
    expect(screen.getByText("The draft could not be saved.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New exhibition" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Close editor" }));
    expect(screen.getByLabelText("Exhibition name (Korean) *")).toHaveValue(
      "서로 다른 시간 — 보존",
    );
    await user.click(screen.getByRole("row", { name: /빛의 문법/ }));
    expect(screen.getByLabelText("Exhibition name (Korean) *")).toHaveValue(
      "서로 다른 시간 — 보존",
    );

    expect(screen.queryByRole("button", { name: "Retry save" })).not.toBeInTheDocument();
    await user.click(
      screen.getByRole("button", { name: "Discard changes and reload" }),
    );
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(repository.saveDraft).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("blocks invalid-draft navigation and offers a server discard recovery", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const createDraft = vi.spyOn(repository, "createDraft");
    const onSignOut = vi.fn();
    render(
      <AdminWorkspace
        repository={repository}
        staffRole="admin"
        onSignOut={onSignOut}
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("tab", { name: "Schedule" }));
    const ticketUrl = screen.getByLabelText("Exhibition ticket URL");
    await user.clear(ticketUrl);
    await user.type(ticketUrl, "not-a-url");

    expect(screen.getByText("Fix highlighted fields to save")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New exhibition" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Discard changes and reload" }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("row", { name: /빛의 문법/ }));
    await user.click(screen.getByRole("button", { name: "Close editor" }));
    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(ticketUrl).toHaveValue("not-a-url");
    expect(createDraft).not.toHaveBeenCalled();
    expect(onSignOut).not.toHaveBeenCalled();

    await user.click(
      screen.getByRole("button", { name: "Discard changes and reload" }),
    );
    await waitFor(() =>
      expect(ticketUrl).toHaveValue(
        "https://tickets.example.test/exhibitions",
      ),
    );
    expect(screen.getByText("All changes saved")).toBeInTheDocument();
  });

  it("reloads the server version to recover from a revision conflict", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const original = (await repository.list({ search: "", status: "All" }))[0];
    const originalSave = repository.saveDraft.bind(repository);
    let attempt = 0;
    repository.saveDraft = vi.fn(
      async (...args: Parameters<typeof originalSave>) => {
        attempt += 1;
        if (attempt === 1) {
          await originalSave(
            original.id,
            original.workingVersionId,
            original.revision,
            { nameEn: "Server-side edit" },
          );
          throw new RevisionConflictError(original.revision + 1);
        }
        return originalSave(...args);
      },
    );

    render(<AdminWorkspace repository={repository} staffRole="admin" />);
    await screen.findAllByText("서로 다른 시간");
    await user.type(screen.getByLabelText("Exhibition name (Korean) *"), " — 충돌");

    expect(
      await screen.findByText("! A newer revision exists", {}, { timeout: 2500 }),
    ).toBeInTheDocument();
    expect(screen.getByText(/server is at revision 7/i)).toBeInTheDocument();
    const conflictedTitle = screen.getByLabelText("Exhibition name (Korean) *");
    expect(conflictedTitle).toBeDisabled();
    await user.type(conflictedTitle, " — 재시도 안 함");
    await new Promise((resolve) => window.setTimeout(resolve, 1_300));
    expect(repository.saveDraft).toHaveBeenCalledTimes(1);

    await user.click(
      screen.getByRole("button", { name: "Discard changes and reload" }),
    );
    await waitFor(() =>
      expect(screen.getByLabelText("Exhibition name (Korean) *")).toHaveValue(original.nameKo),
    );
    expect(screen.getByText("All changes saved")).toBeInTheDocument();
    expect(screen.getByLabelText("Exhibition name (Korean) *")).toBeEnabled();

    await user.type(
      screen.getByLabelText("Exhibition name (Korean) *"),
      " — 새 리비전",
    );
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(repository.saveDraft).toHaveBeenCalledTimes(2);
    expect(vi.mocked(repository.saveDraft).mock.calls[1][2]).toBe(
      original.revision + 1,
    );
  });

  it("reloads same-version media before clearing a media conflict", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const initialRecords = await repository.list({ search: "", status: "All" });
    const initial = initialRecords[0];
    const server = {
      ...initial,
      revision: initial.revision + 1,
      updatedAt: "2026-07-22T12:00:00.000Z",
      updatedBy: "Another editor",
    };
    const staleMedia: AdminMediaAsset = {
      assetId: "stale-cover",
      versionId: initial.workingVersionId,
      role: "cover",
      sortOrder: 0,
      status: "published",
      bucketId: "exhibition-media",
      objectPath: "stale/cover.jpg",
      mimeType: "image/jpeg",
      byteSize: 1024,
      width: 1600,
      height: 1067,
      checksumSha256: null,
      publicUrl: "https://images.example.test/stale-cover.jpg",
      altKo: "",
      altEn: "Stale cover",
      credit: "",
      rightsUrl: "",
      originalFilename: "stale-cover.jpg",
      createdAt: "2026-07-21T12:00:00.000Z",
      updatedAt: "2026-07-21T12:00:00.000Z",
      previewUrl: "https://images.example.test/stale-cover.jpg",
    };
    const serverMedia: AdminMediaAsset = {
      ...staleMedia,
      assetId: "server-cover",
      objectPath: "server/cover.jpg",
      originalFilename: "server-cover.jpg",
      publicUrl: "https://images.example.test/server-cover.jpg",
      previewUrl: "https://images.example.test/server-cover.jpg",
      updatedAt: "2026-07-22T12:00:00.000Z",
    };
    let recordReads = 0;
    repository.list = vi.fn(async () => {
      recordReads += 1;
      return recordReads === 1
        ? initialRecords
        : [server, ...initialRecords.slice(1)];
    });
    let mediaReads = 0;
    let resolveMediaReload: ((media: AdminMediaAsset[]) => void) | null = null;
    const pendingMediaReload = new Promise<AdminMediaAsset[]>((resolve) => {
      resolveMediaReload = resolve;
    });
    repository.listMedia = vi.fn(async () => {
      mediaReads += 1;
      return mediaReads === 1 ? [staleMedia] : pendingMediaReload;
    });
    repository.uploadAndAttachMedia = vi.fn(async () => {
      throw new RevisionConflictError(server.revision);
    });

    render(<AdminWorkspace repository={repository} staffRole="admin" />);
    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("tab", { name: "Media" }));
    expect(await screen.findByText("stale-cover.jpg")).toBeInTheDocument();

    const replaceCover = screen.getByLabelText("Replace cover");
    expect(replaceCover).toBeEnabled();
    await user.upload(
      replaceCover,
      new File(["image"], "conflicting-cover.jpg", { type: "image/jpeg" }),
    );
    await waitFor(() =>
      expect(repository.uploadAndAttachMedia).toHaveBeenCalledTimes(1),
    );
    const mediaConflict = `A newer revision (${server.revision}) exists. Reload before changing media.`;
    expect(await screen.findByText(`! ${mediaConflict}`)).toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "Discard changes and reload" }),
    );
    await waitFor(() =>
      expect(repository.list).toHaveBeenLastCalledWith({
        search: "",
        status: "All",
      }),
    );
    await waitFor(() => expect(repository.listMedia).toHaveBeenCalledTimes(2));
    expect(screen.getByText(`! ${mediaConflict}`)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reloading…" })).toBeDisabled();
    expect(
      screen.queryByText("Server version reloaded. Local changes were discarded."),
    ).not.toBeInTheDocument();

    await act(async () => {
      resolveMediaReload?.([serverMedia]);
    });
    expect(await screen.findByText("server-cover.jpg")).toBeInTheDocument();
    expect(screen.queryByText("stale-cover.jpg")).not.toBeInTheDocument();
    expect(screen.queryByText(`! ${mediaConflict}`)).not.toBeInTheDocument();
    expect(screen.getByText("All changes saved")).toBeInTheDocument();
  });

  it("clearly identifies fixture persistence as temporary", async () => {
    render(<App />);

    expect(await screen.findByText("Fixture admin")).toBeInTheDocument();
    expect(
      screen.getByText(/changes are temporary and are never saved to Supabase/i),
    ).toBeInTheDocument();
  });

  it("validates and autosaves coordinates, ticket URL, and editorial associations", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const lookups = await repository.getExhibitionLookups();
    const originalSave = repository.saveDraft.bind(repository);
    repository.saveDraft = vi.fn(originalSave);
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.click(screen.getByRole("tab", { name: "Venue" }));

    await user.type(screen.getByLabelText("Latitude *"), "37.5665");
    expect(screen.getByText("Fix highlighted fields to save")).toBeInTheDocument();
    expect(
      screen.getByText(/Add both latitude and longitude/i),
    ).toBeInTheDocument();
    expect(repository.saveDraft).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("Longitude *"), "126.9780");
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(repository.saveDraft).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("tab", { name: "Schedule" }));
    const ticketUrl = screen.getByLabelText("Exhibition ticket URL");
    await user.type(ticketUrl, "ftp://tickets.example.test/show");
    expect(screen.getByText("Fix highlighted fields to save")).toBeInTheDocument();
    expect(ticketUrl).toHaveAttribute("aria-invalid", "true");
    await new Promise((resolve) => window.setTimeout(resolve, 700));
    expect(repository.saveDraft).toHaveBeenCalledTimes(1);

    await user.clear(ticketUrl);
    await user.type(ticketUrl, "https://tickets.example.test/show");
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(repository.saveDraft).toHaveBeenCalledTimes(2);

    await user.click(screen.getByRole("tab", { name: "Curation" }));
    await user.selectOptions(
      await screen.findByLabelText("Linked event"),
      lookups.events[0].id,
    );
    await user.selectOptions(
      screen.getByLabelText("Editorial attribution"),
      lookups.editors[0].id,
    );
    await user.selectOptions(screen.getByLabelText("Featured status"), "featured");
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );

    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(screen.getByText("API contract"));
    const contract = screen.getByText(/"latitude": 37\.5665/);
    expect(contract).toHaveTextContent(`"event_id": "${lookups.events[0].id}"`);
    expect(contract).toHaveTextContent(`"editor_id": "${lookups.editors[0].id}"`);
    expect(contract).toHaveTextContent('"is_featured": true');
    expect(contract).toHaveTextContent(
      '"ticket_url": "https://tickets.example.test/show"',
    );
  });

  it("edits multiline hours and bilingual credits in the exhibition form", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");

    expect(
      screen
        .getByLabelText("Description (Korean)")
        .compareDocumentPosition(screen.getByLabelText("Credits (Korean)")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      screen
        .getByLabelText("Credits (Korean)")
        .compareDocumentPosition(screen.getByLabelText("Description (English)")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      screen
        .getByLabelText("Description (English)")
        .compareDocumentPosition(screen.getByLabelText("Credits (English)")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    await user.type(screen.getByLabelText("Credits (Korean)"), "자료 제공: 작가");
    await user.type(
      screen.getByLabelText("Credits (English)"),
      "Courtesy of the artist",
    );

    await user.click(screen.getByRole("tab", { name: "Schedule" }));
    const hours = screen.getByLabelText("Hours");
    expect(hours.tagName).toBe("TEXTAREA");
    await user.clear(hours);
    await user.type(hours, "화–금 11:00–18:00{enter}토 12:00–17:00");

    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(hours).toHaveValue("화–금 11:00–18:00\n토 12:00–17:00");
  });

  it("preserves coordinates for unit details and clears them for a different street address", async () => {
    const user = userEvent.setup();
    const geocodingService = {
      mode: "naver-server" as const,
      searchAddress: vi.fn(async () => [
        {
          roadAddress: "서울 용산구 한남대로 28",
          jibunAddress: "서울 용산구 한남동 1-1",
          englishAddress: "28 Hannam-daero, Yongsan-gu, Seoul",
          cityKo: "서울",
          cityEn: "Seoul",
          regionKo: "용산구",
          regionEn: "Yongsan-gu",
          latitude: "37.5344",
          longitude: "127.0005",
        },
      ]),
    };

    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        geocodingService={geocodingService}
        staffRole="admin"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.click(screen.getByRole("tab", { name: "Venue" }));

    const address = screen.getByLabelText("Address (Korean) *");
    const englishAddress = screen.getByLabelText("Address (English)");
    const latitude = screen.getByLabelText("Latitude *");
    const longitude = screen.getByLabelText("Longitude *");
    expect(screen.getByRole("button", { name: "Find coordinates" })).toBeDisabled();

    await user.type(englishAddress, "Stale English address");
    await user.type(address, "서울 용산구 한남대로 28");
    expect(englishAddress).toHaveValue("");
    await user.click(screen.getByRole("button", { name: "Find coordinates" }));
    expect(geocodingService.searchAddress).toHaveBeenCalledWith(
      "서울 용산구 한남대로 28",
    );
    expect(latitude).toHaveValue("");
    expect(longitude).toHaveValue("");

    await user.click(
      await screen.findByRole("button", {
        name: "Use location: 서울 용산구 한남대로 28",
      }),
    );
    expect(englishAddress).toHaveValue(
      "28 Hannam-daero, Yongsan-gu, Seoul",
    );
    expect(latitude).toHaveValue("37.5344");
    expect(longitude).toHaveValue("127.0005");
    expect(screen.getByLabelText("City / province")).toHaveValue("서울");
    expect(screen.getByLabelText("Region")).toHaveValue("용산구");
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );

    await user.type(address, " 2층");
    expect(englishAddress).toHaveValue("");
    expect(latitude).toHaveValue("37.5344");
    expect(longitude).toHaveValue("127.0005");

    await user.clear(address);
    await user.type(address, "서울 용산구 이태원로 55");
    expect(latitude).toHaveValue("");
    expect(longitude).toHaveValue("");
    expect(
      screen.getByText(/Changing the searchable street address clears its coordinates/i),
    ).toBeInTheDocument();
  });

  it("edits an optional reception end time", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.click(screen.getByRole("tab", { name: "Schedule" }));

    const endTime = screen.getByLabelText("Reception end time");
    expect(endTime).toHaveValue("");
    await user.type(endTime, "20:00");

    expect(await screen.findByText("v1 · revision 2", {}, { timeout: 2500 })).toBeInTheDocument();
    expect(screen.getByText("All changes saved")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Basics" }));
    await user.click(screen.getByRole("tab", { name: "Schedule" }));
    expect(screen.getByLabelText("Reception end time")).toHaveValue("20:00");
  });

  it("waits for an in-flight autosave before offering a geocode candidate", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const originalSave = repository.saveDraft.bind(repository);
    let saveAttempt = 0;
    let releaseFirstSave: (() => void) | null = null;
    repository.saveDraft = vi.fn(
      async (...args: Parameters<typeof originalSave>) => {
        saveAttempt += 1;
        if (saveAttempt === 1) {
          await new Promise<void>((resolve) => {
            releaseFirstSave = resolve;
          });
        }
        return originalSave(...args);
      },
    );
    const geocodingService = {
      mode: "naver-server" as const,
      searchAddress: vi.fn(async () => [
        {
          roadAddress: "서울 용산구 한남대로 28",
          jibunAddress: "서울 용산구 한남동 1-1",
          englishAddress: "28 Hannam-daero, Yongsan-gu, Seoul",
          cityKo: "서울",
          cityEn: "Seoul",
          regionKo: "용산구",
          regionEn: "Yongsan-gu",
          latitude: "37.5344",
          longitude: "127.0005",
        },
      ]),
    };

    render(
      <AdminWorkspace
        repository={repository}
        geocodingService={geocodingService}
        staffRole="admin"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.click(screen.getByRole("tab", { name: "Venue" }));
    await user.type(
      screen.getByLabelText("Address (Korean) *"),
      "서울 용산구 한남대로 28",
    );

    await screen.findByText("Saving…", {}, { timeout: 2500 });
    await user.click(screen.getByRole("button", { name: "Find coordinates" }));
    await waitFor(() =>
      expect(geocodingService.searchAddress).toHaveBeenCalledTimes(1),
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /Searching/ }),
      ).toBeDisabled(),
    );
    expect(
      screen.queryByRole("button", {
        name: "Use location: 서울 용산구 한남대로 28",
      }),
    ).not.toBeInTheDocument();

    await act(async () => {
      releaseFirstSave?.();
    });

    await user.click(
      await screen.findByRole("button", {
        name: "Use location: 서울 용산구 한남대로 28",
      }),
    );
    expect(screen.getByLabelText("Latitude *")).toHaveValue("37.5344");
    expect(screen.getByLabelText("Longitude *")).toHaveValue("127.0005");
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );

    const saveDraft = vi.mocked(repository.saveDraft);
    expect(saveDraft).toHaveBeenCalledTimes(2);
    expect(saveDraft.mock.calls[1][3]).toMatchObject({
      addressKo: "서울 용산구 한남대로 28",
      addressEn: "28 Hannam-daero, Yongsan-gu, Seoul",
      latitude: "37.5344",
      longitude: "127.0005",
    });
  });

  it("labels fixture mode and explains its supported address without claiming a NAVER search", async () => {
    const user = userEvent.setup();
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        staffRole="admin"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    expect(screen.getByText("Fixture mode")).toBeInTheDocument();
    expect(
      screen.getByText(/Address lookup uses local sample data only/i),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.click(screen.getByRole("tab", { name: "Venue" }));
    const address = screen.getByLabelText("Address (Korean) *");
    await user.type(address, "서울 용산구 한남대로 28");
    await user.click(screen.getByRole("button", { name: "Find coordinates" }));
    expect(
      await screen.findByRole("button", {
        name: "Use location: 서울 용산구 한남대로 28",
      }),
    ).toBeInTheDocument();

    await user.clear(address);
    await user.type(address, "서울 종로구 세종대로 175");
    await user.click(screen.getByRole("button", { name: "Find coordinates" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Fixture lookup has no match",
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "서울 용산구 한남대로 28",
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "No NAVER request was sent",
    );
  });

  it("attributes an empty live geocoding result to NAVER Maps", async () => {
    const user = userEvent.setup();
    const geocodingService = {
      mode: "naver-server" as const,
      searchAddress: vi.fn(async () => []),
    };
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        geocodingService={geocodingService}
        staffRole="admin"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    expect(screen.queryByText("Fixture mode")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.click(screen.getByRole("tab", { name: "Venue" }));
    await user.type(
      screen.getByLabelText("Address (Korean) *"),
      "서울 종로구 세종대로 175",
    );
    await user.click(screen.getByRole("button", { name: "Find coordinates" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "NAVER Maps found no matching address",
    );
  });

  it("previews the current draft and exposes its compatibility projection", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "Preview" }));

    expect(screen.getByRole("dialog", { name: "Preview" })).toBeInTheDocument();
    await user.click(screen.getByText("API contract"));
    expect(screen.getByText(/"name_ko": "서로 다른 시간"/)).toBeInTheDocument();
  });

  it("switches an open preview dialog to Korean without closing it", async () => {
    const user = userEvent.setup();
    render(
      <LocaleProvider initialLocale="en">
        <App />
      </LocaleProvider>,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    const dialog = screen.getByRole("dialog", { name: "Preview" });
    expect(within(dialog).getByRole("group", { name: "Interface language" }))
      .toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Korean" }));

    expect(screen.getByRole("dialog", { name: "미리보기" })).toBeInTheDocument();
  });

  it("closes dialogs with Escape and returns focus to the invoker", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    const previewButton = screen.getByRole("button", { name: "Preview" });
    await user.click(previewButton);

    expect(screen.getByRole("dialog", { name: "Preview" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Close" })).toHaveFocus();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Preview" })).not.toBeInTheDocument();
    expect(previewButton).toHaveFocus();
  });

  it("clones a published version into a new working draft before autosaving", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("row", { name: /빛의 문법/ }));
    const title = screen.getByLabelText("Exhibition name (Korean) *");
    await user.clear(title);
    await user.type(title, "빛의 문법 — 개정");

    await waitFor(
      () => expect(screen.getByText("v4 · revision 4")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(screen.getAllByText("Draft").length).toBeGreaterThan(0);
  });

  it("creates an unchanged working draft before opening media for a published exhibition", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const originalSave = repository.saveDraft.bind(repository);
    repository.saveDraft = vi.fn(
      async (...args: Parameters<typeof originalSave>) => originalSave(...args),
    );

    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("row", { name: /빛의 문법/ }));
    await user.click(screen.getByRole("button", { name: "Manage images" }));

    await waitFor(() =>
      expect(screen.getByRole("tab", { name: "Media" })).toHaveAttribute(
        "aria-selected",
        "true",
      ),
    );
    expect(await screen.findByLabelText("Choose cover image")).toBeEnabled();
    expect(repository.saveDraft).toHaveBeenCalledWith(
      "cf108aa92ae8efc4",
      "10000000-0000-0000-0000-000000000002",
      3,
      {},
    );
    expect(screen.getAllByText("Draft").length).toBeGreaterThan(0);
  });

  it("deduplicates repeated Manage images saves for one version revision", async () => {
    const repository = new InMemoryAdminExhibitionRepository();
    const originalSave = repository.saveDraft.bind(repository);
    let releaseSave: (() => void) | null = null;
    repository.saveDraft = vi.fn(
      async (...args: Parameters<typeof originalSave>) => {
        await new Promise<void>((resolve) => {
          releaseSave = resolve;
        });
        return originalSave(...args);
      },
    );

    render(<AdminWorkspace repository={repository} staffRole="admin" />);
    await screen.findAllByText("서로 다른 시간");
    await userEvent.click(screen.getByRole("row", { name: /빛의 문법/ }));
    const manageImages = screen.getByRole("button", { name: "Manage images" });

    act(() => {
      manageImages.click();
      manageImages.click();
    });

    expect(repository.saveDraft).toHaveBeenCalledTimes(1);
    await act(async () => releaseSave?.());
    await waitFor(() =>
      expect(screen.getByRole("tab", { name: "Media" })).toHaveAttribute(
        "aria-selected",
        "true",
      ),
    );
  });

  it("archives and restores records without deleting their history", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "Archive" }));
    const archiveDialog = screen.getByRole("dialog", {
      name: "Archive exhibition",
    });
    await user.click(within(archiveDialog).getByRole("button", { name: "Archive" }));

    expect(await screen.findByRole("button", { name: "Restore" })).toBeEnabled();
    expect(
      screen.getAllByText(/history and media references are preserved/).length,
    ).toBeGreaterThan(0);

    await user.click(screen.getByRole("button", { name: "Restore" }));
    const restoreDialog = screen.getByRole("dialog", {
      name: "Restore exhibition",
    });
    await user.click(within(restoreDialog).getByRole("button", { name: "Restore" }));
    expect(await screen.findByRole("button", { name: "Archive" })).toBeEnabled();
    expect(
      screen.getAllByText(/restored as a draft/).length,
    ).toBeGreaterThan(0);
  });

  it("requires confirmation before discarding a working draft and returns to the published version", async () => {
    const user = userEvent.setup();
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        staffRole="publisher"
      />,
    );

    await user.click(await screen.findByRole("row", { name: /기억의 표면/ }));
    await user.click(screen.getByRole("button", { name: "Discard draft" }));

    const dialog = screen.getByRole("dialog", {
      name: "Discard draft changes",
    });
    expect(within(dialog).getByText(/last published version/)).toBeVisible();
    await user.click(
      within(dialog).getByRole("button", { name: "Discard changes" }),
    );

    expect(
      await screen.findAllByText(
        "Draft changes discarded. The last published version was restored.",
      ),
    ).not.toHaveLength(0);
    expect(screen.getByRole("button", { name: "Archive" })).toBeEnabled();
    expect(
      screen.queryByRole("button", { name: "Discard draft" }),
    ).not.toBeInTheDocument();
  });

  it("requires explicit confirmation before permanently deleting a never-published draft", async () => {
    const user = userEvent.setup();
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        staffRole="admin"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(
      screen.getByRole("button", { name: "Delete permanently" }),
    );

    const dialog = screen.getByRole("dialog", {
      name: "Delete draft permanently",
    });
    const confirm = within(dialog).getByRole("button", {
      name: "Delete permanently",
    });
    expect(confirm).toBeDisabled();

    await user.type(within(dialog).getByLabelText("Type DELETE to confirm"), "DELETE");
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() =>
      expect(
        screen.queryByRole("row", { name: /서로 다른 시간/ }),
      ).not.toBeInTheDocument(),
    );
    expect(
      (await screen.findAllByText("Draft permanently deleted.")).length,
    ).toBeGreaterThan(0);
  });

  it("warns that deleting withdraws a submission still awaiting review", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const listed = await repository.list({
      search: "",
      status: "All",
      temporalStatus: "all",
      featuredOnly: false,
      sort: "updated_desc",
    });
    const target = listed.find((record) => record.status === "Draft");
    repository.list = async () =>
      listed.map((record) =>
        record.id === target?.id
          ? { ...record, hasOpenOwnerSubmission: true }
          : record,
      );
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(
      screen.getByRole("button", { name: "Delete permanently" }),
    );

    const dialog = screen.getByRole("dialog", {
      name: "Delete draft permanently",
    });
    expect(
      within(dialog).getByText(
        "This draft has a submission awaiting review. Deleting it withdraws that submission from the review queue.",
      ),
    ).toBeVisible();
  });

  it("omits the withdrawal warning when no review round is open", async () => {
    const user = userEvent.setup();
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        staffRole="admin"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(
      screen.getByRole("button", { name: "Delete permanently" }),
    );

    const dialog = screen.getByRole("dialog", {
      name: "Delete draft permanently",
    });
    expect(
      within(dialog).queryByText(/withdraws that submission/),
    ).not.toBeInTheDocument();
  });

  it("names the retained relationship when permanent deletion is refused", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    repository.deleteDraft = async () => {
      throw new DraftDeleteBlockedError("draft_delete_has_launch_kit_reference");
    };
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(
      screen.getByRole("button", { name: "Delete permanently" }),
    );

    const dialog = screen.getByRole("dialog", {
      name: "Delete draft permanently",
    });
    await user.type(
      within(dialog).getByLabelText("Type DELETE to confirm"),
      "DELETE",
    );
    await user.click(
      within(dialog).getByRole("button", { name: "Delete permanently" }),
    );

    expect(
      await screen.findAllByText(
        "This draft has a Launch Kit. Cancel the Launch Kit before deleting it.",
      ),
    ).not.toHaveLength(0);
    expect(screen.getByRole("row", { name: /서로 다른 시간/ })).toBeVisible();
  });

  it("does not offer permanent deletion to publishers", async () => {
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        staffRole="publisher"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    expect(
      screen.queryByRole("button", { name: "Delete permanently" }),
    ).not.toBeInTheDocument();
  });

  it("keeps publish and lifecycle commands unavailable to contributors", async () => {
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        staffRole="contributor"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Archive" })).toBeDisabled();
  });

  it("lets contributors manage draft media while archived media stays read-only", async () => {
    const user = userEvent.setup();
    render(
      <AdminWorkspace
        repository={new InMemoryAdminExhibitionRepository()}
        staffRole="contributor"
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("tab", { name: "Media" }));
    expect(await screen.findByLabelText("Choose cover image")).toBeEnabled();

    await user.click(screen.getByRole("row", { name: /낯선 정원/ }));
    await user.click(screen.getByRole("tab", { name: "Media" }));
    expect(
      await screen.findByText(/Archived exhibitions are read-only/),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Choose cover image")).toBeDisabled();
  });

  it("uploads draft media, clears the chooser, and blocks publish while processing", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("tab", { name: "Media" }));
    const chooser = await screen.findByLabelText("Choose cover image");
    const file = new File(["image"], "draft-cover.png", {
      type: "image/png",
    });
    await user.upload(chooser, file);

    expect(await screen.findByText("Processing for publication")).toBeInTheDocument();
    expect(screen.getAllByText(/Cover image attached/).length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    expect(chooser).toHaveValue("");
  });

  it("refreshes processing media until the worker publishes it", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    let mediaReads = 0;
    repository.listMedia = vi.fn(async (
      _exhibitionId: string,
      versionId: string,
    ): Promise<AdminMediaAsset[]> => {
      mediaReads += 1;
      const status: AdminMediaAsset["status"] =
        mediaReads === 1 ? "ready" : "published";
      return [
        {
          assetId: "worker-cover",
          versionId,
          role: "cover",
          sortOrder: 0,
          status,
          bucketId: "exhibition-media",
          objectPath: "worker/cover.jpg",
          mimeType: "image/jpeg",
          byteSize: 1024,
          width: 1600,
          height: 1067,
          checksumSha256: null,
          publicUrl:
            status === "published"
              ? "https://images.example.test/worker-cover.jpg"
              : null,
          altKo: "",
          altEn: "Worker cover",
          credit: "",
          rightsUrl: "",
          originalFilename: "worker-cover.jpg",
          createdAt: "2026-07-21T12:00:00.000Z",
          updatedAt: "2026-07-21T12:00:00.000Z",
          previewUrl: "https://images.example.test/worker-cover.jpg",
        },
      ];
    });
    render(
      <AdminWorkspace
        repository={repository}
        staffRole="admin"
        mediaStatusPollIntervalMs={40}
      />,
    );

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("tab", { name: "Media" }));
    const filename = await screen.findByText("worker-cover.jpg");
    const asset = filename.closest("article");
    expect(asset).not.toBeNull();
    expect(within(asset as HTMLElement).getByText("Processing for publication"))
      .toBeInTheDocument();

    await waitFor(() =>
      expect(within(asset as HTMLElement).getByText("Published"))
        .toBeInTheDocument(),
    );
    expect(repository.listMedia).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: "Publish" })).toBeEnabled();
  });

  it("drops a row from the missing-cover list once its cover is published", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    let mediaReads = 0;
    repository.listMedia = vi.fn(async (
      _exhibitionId: string,
      versionId: string,
    ): Promise<AdminMediaAsset[]> => {
      mediaReads += 1;
      const status: AdminMediaAsset["status"] =
        mediaReads === 1 ? "ready" : "published";
      return [
        {
          assetId: "worker-cover",
          versionId,
          role: "cover",
          sortOrder: 0,
          status,
          bucketId: "exhibition-media",
          objectPath: "worker/cover.jpg",
          mimeType: "image/jpeg",
          byteSize: 1024,
          width: 1600,
          height: 1067,
          checksumSha256: null,
          publicUrl:
            status === "published"
              ? "https://images.example.test/worker-cover.jpg"
              : null,
          altKo: "",
          altEn: "Worker cover",
          credit: "",
          rightsUrl: "",
          originalFilename: "worker-cover.jpg",
          createdAt: "2026-07-21T12:00:00.000Z",
          updatedAt: "2026-07-21T12:00:00.000Z",
          previewUrl: "https://images.example.test/worker-cover.jpg",
        },
      ];
    });
    render(
      <AdminWorkspace
        repository={repository}
        staffRole="admin"
        mediaStatusPollIntervalMs={40}
      />,
    );

    const table = await screen.findByRole("table", { name: "Exhibitions" });
    expect(within(table).getByText("서로 다른 시간")).toBeInTheDocument();
    await user.click(screen.getByLabelText("Missing cover image only"));
    await waitFor(() =>
      expect(within(table).queryByText("빛의 문법")).not.toBeInTheDocument(),
    );
    expect(within(table).getByText("서로 다른 시간")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Media" }));
    const filename = await screen.findByText("worker-cover.jpg");
    const asset = filename.closest("article");
    await waitFor(() =>
      expect(within(asset as HTMLElement).getByText("Published"))
        .toBeInTheDocument(),
    );

    await waitFor(() =>
      expect(
        within(screen.getByRole("table", { name: "Exhibitions" }))
          .queryByText("서로 다른 시간"),
      ).not.toBeInTheDocument(),
    );
  });

  it("locks exhibition fields while a media mutation is in flight", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const originalUpload = repository.uploadAndAttachMedia.bind(repository);
    let resume: (() => void) | null = null;
    repository.uploadAndAttachMedia = vi.fn(
      async (...args: Parameters<typeof originalUpload>) => {
        await new Promise<void>((resolve) => {
          resume = resolve;
        });
        return originalUpload(...args);
      },
    );
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("tab", { name: "Media" }));
    await user.upload(
      await screen.findByLabelText("Choose cover image"),
      new File(["image"], "slow-cover.webp", { type: "image/webp" }),
    );
    expect(await screen.findByText("Updating media…")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Basics" }));
    expect(screen.getByLabelText("Exhibition name (Korean) *")).toBeDisabled();
    expect(screen.getByRole("button", { name: "New exhibition" })).toBeDisabled();

    await act(async () => {
      resume?.();
    });
    await waitFor(() =>
      expect(screen.getByLabelText("Exhibition name (Korean) *")).toBeEnabled(),
    );
  });

  it("reuses a lifecycle request ID when an unchanged publish is retried", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAdminExhibitionRepository();
    const originalPublish = repository.publish.bind(repository);
    let attempts = 0;
    const publish = vi.fn(
      async (...args: Parameters<typeof originalPublish>) => {
        attempts += 1;
        if (attempts === 1) throw new Error("Temporary connection failure.");
        return originalPublish(...args);
      },
    );
    repository.publish = publish;
    render(<AdminWorkspace repository={repository} staffRole="admin" />);

    await screen.findAllByText("서로 다른 시간");
    const publishButton = screen.getByRole("button", { name: "Publish" });
    await waitFor(() => expect(publishButton).toBeEnabled());
    await user.click(publishButton);
    const dialog = screen.getByRole("dialog", { name: "Publish exhibition" });
    const confirm = within(dialog).getByRole("button", { name: "Publish" });

    await user.click(confirm);
    expect(
      (await screen.findAllByText("Publish failed.")).length,
    ).toBeGreaterThan(0);
    await user.click(confirm);

    await waitFor(() => expect(publish).toHaveBeenCalledTimes(2));
    const firstRequestId = publish.mock.calls[0][3];
    expect(firstRequestId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(publish.mock.calls[1][3]).toBe(firstRequestId);
    expect(
      (await screen.findAllByText(/Exhibition published/)).length,
    ).toBeGreaterThan(0);
  });

  it("fails closed when a production build has no Supabase configuration", async () => {
    vi.resetModules();
    vi.stubEnv("MODE", "production");
    vi.stubEnv("PROD", true);
    vi.stubEnv("DEV", false);
    vi.stubEnv("VITE_SUPABASE_URL", undefined);
    vi.stubEnv("VITE_SUPABASE_PUBLISHABLE_KEY", undefined);
    vi.stubEnv("VITE_ADMIN_FIXTURE_MODE", "true");

    try {
      const { default: ProductionApp } = await import("./App");
      render(<ProductionApp />);

      expect(
        await screen.findByRole("heading", { name: "Admin configuration required" }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "New exhibition" }))
        .not.toBeInTheDocument();
    } finally {
      vi.unstubAllEnvs();
      vi.resetModules();
    }
  });

  it("fails closed for production bundles even when MODE is test and fixtures are requested", async () => {
    vi.resetModules();
    vi.stubEnv("MODE", "test");
    vi.stubEnv("PROD", true);
    vi.stubEnv("DEV", false);
    vi.stubEnv("VITE_SUPABASE_URL", undefined);
    vi.stubEnv("VITE_SUPABASE_PUBLISHABLE_KEY", undefined);
    vi.stubEnv("VITE_ADMIN_FIXTURE_MODE", "true");

    try {
      const { default: ProductionTestModeApp } = await import("./App");
      render(<ProductionTestModeApp />);

      expect(
        await screen.findByRole("heading", { name: "Admin configuration required" }),
      ).toBeInTheDocument();
      expect(screen.queryByText("Fixture admin")).not.toBeInTheDocument();
    } finally {
      vi.unstubAllEnvs();
      vi.resetModules();
    }
  });
});
