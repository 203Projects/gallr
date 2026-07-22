import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App, { AdminWorkspace } from "./App";
import type { AdminMediaAsset } from "./domain";
import { InMemoryAdminExhibitionRepository } from "./repositories/InMemoryAdminExhibitionRepository";

describe("gallr admin", () => {
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
    await waitFor(
      () => expect(screen.getByText("All changes saved")).toBeInTheDocument(),
      { timeout: 2500 },
    );

    await user.click(screen.getByRole("button", { name: "Preview" }));
    await user.click(screen.getByText("API contract"));
    const contract = screen.getByText(/"latitude": 37\.5665/);
    expect(contract).toHaveTextContent(`"event_id": "${lookups.events[0].id}"`);
    expect(contract).toHaveTextContent(`"editor_id": "${lookups.editors[0].id}"`);
    expect(contract).toHaveTextContent(
      '"ticket_url": "https://tickets.example.test/show"',
    );
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
});
