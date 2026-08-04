import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ExhibitionWorkspace } from "./ExhibitionWorkspace";
import type { OwnerExhibition } from "../domain";

const draft: OwnerExhibition = {
  id: "exhibition-one",
  workingVersionId: "version-one",
  versionNumber: 1,
  revision: 3,
  ownerStatus: "draft" as const,
  reviewNotes: "",
  nameKo: "작은 방의 기록",
  nameEn: "Notes from a Small Room",
  venueNameKo: "갤러리 알파",
  venueNameEn: "Gallery Alpha",
  cityKo: "서울",
  cityEn: "Seoul",
  regionKo: "종로구",
  regionEn: "Jongno-gu",
  addressKo: "서울특별시 종로구 삼청로 12",
  addressEn: "",
  openingDate: "2026-09-02",
  closingDate: "2026-11-08",
  descriptionKo: "작은 방에서 시작된 기록입니다.",
  descriptionEn: "",
  hours: "Tue-Sun 11:00-18:00",
  contact: "",
  receptionDate: "",
  receptionStartTime: "",
  ticketUrl: "",
  updatedAt: "2026-07-31T10:00:00Z",
  pageLoads30d: 0,
  pageLoadsAllTime: 0,
  cover: null,
};

function repositoryWith(records: OwnerExhibition[] = [draft]) {
  return {
    listExhibitions: vi.fn().mockResolvedValue(records),
    createExhibitionDraft: vi.fn().mockResolvedValue(draft),
    saveExhibitionDraft: vi.fn().mockImplementation(async (_id, _version, _revision, patch) => ({
      ...draft,
      ...patch,
      revision: 4,
    })),
    uploadCover: vi.fn().mockResolvedValue({
      ...draft,
      revision: 4,
      cover: {
        assetId: "asset-one",
        status: "ready",
        bucketId: "exhibition-media",
        objectPath: "owner-drafts/user/asset/original.jpg",
        publicUrl: null,
        mimeType: "image/jpeg",
        byteSize: 2048,
        originalFilename: "cover.jpg",
        previewUrl: "blob:cover",
      },
    }),
    submitExhibition: vi.fn().mockResolvedValue({
      ...draft,
      ownerStatus: "submitted",
    }),
    startLaunchCheckout: vi.fn().mockResolvedValue({
      active: true,
      launchKitId: "launch-one",
    }),
  };
}

describe("gallery exhibition workspace", () => {
  it("renders editorial list rows, review notes, and public links without status pills", async () => {
    const records = [
      { ...draft, ownerStatus: "needs_changes" as const, reviewNotes: "Confirm opening hours." },
      {
        ...draft,
        id: "published-one",
        ownerStatus: "published" as const,
        nameKo: "기억의 정원",
        pageLoads30d: 12,
        pageLoadsAllTime: 41,
      },
    ];
    render(
      <ExhibitionWorkspace
        membershipStatus="active"
        repository={repositoryWith(records)}
        onSignOut={vi.fn()}
      />,
    );

    expect(await screen.findByText("Confirm opening hours.")).toBeInTheDocument();
    expect(screen.getByText("Needs changes")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View public page" }))
      .toHaveAttribute(
        "href",
        "https://gallrmap.com/exhibitions/notes-from-a-small-room-publ/",
      );
    expect(screen.getByText("Last 30 days")).toBeInTheDocument();
    expect(screen.getByText("All time")).toBeInTheDocument();
    expect(screen.queryByText(/featured/i)).not.toBeInTheDocument();
  });

  it("shows literal impact and its privacy caveat only in a published editor", async () => {
    const user = userEvent.setup();
    const published = {
      ...draft,
      ownerStatus: "published" as const,
      pageLoads30d: 1_234,
      pageLoadsAllTime: 5_678,
    };
    render(
      <ExhibitionWorkspace
        membershipStatus="active"
        repository={repositoryWith([published])}
        onSignOut={vi.fn()}
      />,
    );

    await user.click(await screen.findByText("작은 방의 기록"));
    expect(screen.getByRole("heading", { name: "Public impact" })).toBeInTheDocument();
    expect(screen.getByText("1,234")).toBeInTheDocument();
    expect(screen.getByText("5,678")).toBeInTheDocument();
    expect(screen.getByText("Public page loads, not unique visitors.")).toBeInTheDocument();
  });

  it("keeps the deferred Launch Kit CTA informative without starting checkout", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith([{ ...draft, ownerStatus: "published" as const }]);
    render(
      <ExhibitionWorkspace
        membershipStatus="active"
        repository={repository}
        onSignOut={vi.fn()}
      />,
    );

    await user.click(await screen.findByText("작은 방의 기록"));
    await user.click(screen.getByRole("button", { name: "Launch this exhibition" }));

    expect(repository.startLaunchCheckout).not.toHaveBeenCalled();
    expect(screen.getByText(
      "Launch Kit is coming soon. Your published listing is already live.",
    )).toBeInTheDocument();
  });

  it("starts checkout only when Launch Kit is enabled", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith([{ ...draft, ownerStatus: "published" as const }]);
    const onNavigateLaunch = vi.fn();
    render(
      <ExhibitionWorkspace
        membershipStatus="active"
        repository={repository}
        onSignOut={vi.fn()}
        onNavigateLaunch={onNavigateLaunch}
        launchKitEnabled
      />,
    );

    await user.click(await screen.findByText("작은 방의 기록"));
    await user.click(screen.getByRole("button", { name: "Launch this exhibition" }));

    await waitFor(() => expect(repository.startLaunchCheckout).toHaveBeenCalledWith("exhibition-one"));
    expect(onNavigateLaunch).toHaveBeenCalledTimes(1);
  });

  it("creates a real canonical draft and opens the focused editor", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith([]);
    render(
      <ExhibitionWorkspace
        membershipStatus="active"
        repository={repository}
        onSignOut={vi.fn()}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "Create exhibition" }));
    expect(repository.createExhibitionDraft).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole("heading", { name: "Edit exhibition" }))
      .toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Venue name (Korean)" }))
      .toHaveValue("갤러리 알파");
  });

  it("saves owner fields with the current version and optimistic revision", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    render(
      <ExhibitionWorkspace
        membershipStatus="active"
        repository={repository}
        onSignOut={vi.fn()}
      />,
    );
    await user.click(await screen.findByText("작은 방의 기록"));
    const name = screen.getByRole("textbox", { name: "Name (English)" });
    await user.clear(name);
    await user.type(name, "Notes, Revised");
    await user.click(screen.getByRole("button", { name: "Save draft" }));

    await waitFor(() => expect(repository.saveExhibitionDraft).toHaveBeenCalledWith(
      "exhibition-one",
      "version-one",
      3,
      expect.objectContaining({ nameEn: "Notes, Revised" }),
    ));
    expect(await screen.findByText("Draft · Saved")).toBeInTheDocument();
  });

  it("uploads the selected cover before submitting an active owner draft", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    render(
      <ExhibitionWorkspace
        membershipStatus="active"
        repository={repository}
        onSignOut={vi.fn()}
      />,
    );
    await user.click(await screen.findByText("작은 방의 기록"));
    const input = screen.getByLabelText("Choose cover image");
    const file = new File(["cover"], "cover.jpg", { type: "image/jpeg" });
    await user.upload(input, file);
    await waitFor(() => expect(repository.uploadCover).toHaveBeenCalledWith(
      "exhibition-one", "version-one", 3, file,
    ));
    await user.click(screen.getByRole("button", { name: "Submit for review" }));
    await waitFor(() => expect(repository.submitExhibition).toHaveBeenCalledWith(
      "exhibition-one", "version-one", 4, expect.any(String),
    ));
    expect(await screen.findByText("Submitted")).toBeInTheDocument();
  });

  it("lets a pending claim prepare drafts but not submit them", async () => {
    const user = userEvent.setup();
    render(
      <ExhibitionWorkspace
        membershipStatus="pending"
        repository={repositoryWith()}
        onSignOut={vi.fn()}
      />,
    );
    await user.click(await screen.findByText("작은 방의 기록"));
    expect(screen.getByRole("button", { name: "Submit for review" }))
      .toBeDisabled();
    expect(screen.getByText("Gallery verification is required before submission."))
      .toBeInTheDocument();
  });

  it.each([
    [
      "owner_submit_exhibition failed [23514]: owner_submission_incomplete",
      "Complete the required title, venue, address, dates, and hours before submitting.",
    ],
    [
      "owner_submit_exhibition failed [23514]: owner_submission_cover_required",
      "Add a cover image before submitting.",
    ],
  ])("explains owner submission requirements instead of exposing %s", async (failure, explanation) => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    repository.submitExhibition.mockRejectedValueOnce(new Error(failure));

    render(
      <ExhibitionWorkspace
        membershipStatus="active"
        repository={repository}
        onSignOut={vi.fn()}
      />,
    );

    await user.click(await screen.findByText("작은 방의 기록"));
    await user.click(screen.getByRole("button", { name: "Submit for review" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(`! ${explanation}`);
    expect(screen.queryByText(failure)).not.toBeInTheDocument();
  });
});
