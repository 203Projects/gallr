import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App, { AdminWorkspace } from "./App";
import type { AdminMediaAsset } from "./domain";
import { RevisionConflictError } from "./repositories/AdminExhibitionRepository";
import { InMemoryAdminExhibitionRepository } from "./repositories/InMemoryAdminExhibitionRepository";

describe("gallr admin", () => {
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
    expect(screen.getByLabelText("전시명 (Korean) *")).toHaveValue("기억의 층위");
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
    expect(screen.getByLabelText("전시명 (Korean) *")).toHaveValue("서로 다른 시간");
    expect(createDraft).not.toHaveBeenCalled();
  });

  it("filters exhibitions by search and status", async () => {
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
  });

  it("creates and autosaves a new exhibition draft", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "New exhibition" }));

    const title = screen.getByLabelText("전시명 (Korean) *");
    expect(title).toHaveValue("");
    await user.type(title, "새로운 전시");

    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(screen.getAllByText("새로운 전시").length).toBeGreaterThan(0);
  });

  it("reuses a past venue without replacing exhibition-specific details", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findAllByText("서로 다른 시간");
    await user.click(screen.getByRole("button", { name: "New exhibition" }));
    await user.type(screen.getByLabelText("전시명 (Korean) *"), "새 전시 제목");
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );

    await user.click(screen.getByRole("tab", { name: "Venue" }));
    await user.type(screen.getByRole("searchbox", { name: "Search past venues" }), "오오");
    await user.click(
      screen.getByRole("button", {
        name: "Use venue 아트스페이스 오오, 서울 용산구, 서울 용산구 이태원로 55",
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
    expect(screen.getByLabelText("전시명 (Korean) *")).toHaveValue("새 전시 제목");
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
    repository.saveDraft = vi.fn(
      async (...args: Parameters<typeof originalSave>) => {
        attempt += 1;
        if (attempt === 1) {
          markFirstSaveStarted?.();
          await new Promise<void>((resolve) => {
            releaseFirstSave = resolve;
          });
        }
        return originalSave(...args);
      },
    );

    render(<AdminWorkspace repository={repository} staffRole="admin" />);
    await screen.findAllByText("서로 다른 시간");

    await user.type(screen.getByLabelText("전시명 (Korean) *"), " — 1차");
    await firstSaveStarted;
    await user.type(screen.getByLabelText("전시명 (English)"), " queued");
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
    expect(screen.getByLabelText("전시명 (Korean) *")).toHaveValue(
      "서로 다른 시간 — 1차",
    );
    expect(screen.getByLabelText("전시명 (English)")).toHaveValue(
      "Different Times queued",
    );
    expect(screen.queryByText("! A newer revision exists")).not.toBeInTheDocument();
  });

  it("blocks navigation after a save error and lets the editor retry without losing the draft", async () => {
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
    const title = screen.getByLabelText("전시명 (Korean) *");
    await user.type(title, " — 보존");

    expect(await screen.findByText("! Save failed", {}, { timeout: 2500 }))
      .toBeInTheDocument();
    expect(screen.getByText("Temporary save outage.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New exhibition" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Close editor" }));
    expect(screen.getByLabelText("전시명 (Korean) *")).toHaveValue(
      "서로 다른 시간 — 보존",
    );
    await user.click(screen.getByRole("row", { name: /빛의 문법/ }));
    expect(screen.getByLabelText("전시명 (Korean) *")).toHaveValue(
      "서로 다른 시간 — 보존",
    );

    await user.click(screen.getByRole("button", { name: "Retry save" }));
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );
    expect(repository.saveDraft).toHaveBeenCalledTimes(2);
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
    repository.saveDraft = vi.fn(async () => {
      throw new RevisionConflictError(original.revision + 1);
    });

    render(<AdminWorkspace repository={repository} staffRole="admin" />);
    await screen.findAllByText("서로 다른 시간");
    await user.type(screen.getByLabelText("전시명 (Korean) *"), " — 충돌");

    expect(
      await screen.findByText("! A newer revision exists", {}, { timeout: 2500 }),
    ).toBeInTheDocument();
    expect(screen.getByText(/server is at revision 7/i)).toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: "Discard changes and reload" }),
    );
    await waitFor(() =>
      expect(screen.getByLabelText("전시명 (Korean) *")).toHaveValue(original.nameKo),
    );
    expect(screen.getByText("All changes saved")).toBeInTheDocument();
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
        .getByLabelText("소개 (Korean)")
        .compareDocumentPosition(screen.getByLabelText("크레딧 (Korean)")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      screen
        .getByLabelText("크레딧 (Korean)")
        .compareDocumentPosition(screen.getByLabelText("소개 (English)")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      screen
        .getByLabelText("소개 (English)")
        .compareDocumentPosition(screen.getByLabelText("Credits (English)")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    await user.type(screen.getByLabelText("크레딧 (Korean)"), "자료 제공: 작가");
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

  it("requires a confirmed map location and clears stale coordinates when the address changes", async () => {
    const user = userEvent.setup();
    const geocodingService = {
      mode: "naver-server" as const,
      searchAddress: vi.fn(async () => [
        {
          roadAddress: "서울 용산구 한남대로 28",
          jibunAddress: "서울 용산구 한남동 1-1",
          englishAddress: "28 Hannam-daero, Yongsan-gu, Seoul",
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
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );

    await user.type(address, " 2층");
    expect(englishAddress).toHaveValue("");
    expect(latitude).toHaveValue("");
    expect(longitude).toHaveValue("");
    expect(
      screen.getByText(/Changing the Korean address clears its coordinates/i),
    ).toBeInTheDocument();
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
    const title = screen.getByLabelText("전시명 (Korean) *");
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
    expect(screen.getByLabelText("전시명 (Korean) *")).toBeDisabled();
    expect(screen.getByRole("button", { name: "New exhibition" })).toBeDisabled();

    await act(async () => {
      resume?.();
    });
    await waitFor(() =>
      expect(screen.getByLabelText("전시명 (Korean) *")).toBeEnabled(),
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
      (await screen.findAllByText("Temporary connection failure.")).length,
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
