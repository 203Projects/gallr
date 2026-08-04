import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GalleryClaimsWorkspace } from "./GalleryClaimsWorkspace";
import type { AdminGalleryClaim } from "../domain";

const pendingClaim: AdminGalleryClaim = {
  galleryId: "gallery-one",
  galleryNameKo: "갤러리 알파",
  galleryNameEn: "Gallery Alpha",
  galleryStatus: "active",
  userId: "owner-one",
  ownerEmail: "owner@alpha.example",
  membershipStatus: "pending",
  websiteUrl: "https://alpha.example",
  socialUrl: "",
  claimNote: "I manage gallery programming.",
  reviewNotes: "",
  createdAt: "2026-07-31T08:00:00Z",
  reviewedAt: null,
};

function repositoryWith(records: AdminGalleryClaim[] = [pendingClaim]) {
  return {
    listGalleryClaims: vi.fn().mockResolvedValue(records),
    approveGalleryClaim: vi.fn().mockResolvedValue({
      ...pendingClaim,
      membershipStatus: "active" as const,
      reviewedAt: "2026-07-31T09:00:00Z",
    }),
    rejectGalleryClaim: vi.fn().mockResolvedValue({
      ...pendingClaim,
      membershipStatus: "rejected" as const,
      reviewNotes: "Use an official gallery email.",
      reviewedAt: "2026-07-31T09:00:00Z",
    }),
  };
}

describe("gallery claims workspace", () => {
  it("shows evidence and approves a pending owner without exposing it to visitors", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    render(<GalleryClaimsWorkspace repository={repository} />);

    expect((await screen.findAllByText("갤러리 알파")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("owner@alpha.example").length).toBeGreaterThan(0);
    expect(screen.getByRole("link", { name: "Official website" }))
      .toHaveAttribute("href", "https://alpha.example");
    await user.click(screen.getByRole("button", { name: "Approve claim" }));

    await waitFor(() => expect(repository.approveGalleryClaim).toHaveBeenCalledWith(
      "gallery-one",
      "owner-one",
      expect.any(String),
    ));
    expect((await screen.findAllByText("Active")).length).toBeGreaterThan(1);
  });

  it("requires a review note and returns a rejected claim", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    render(<GalleryClaimsWorkspace repository={repository} />);

    await screen.findAllByText("갤러리 알파");
    expect(screen.getByRole("button", { name: "Reject claim" })).toBeDisabled();
    await user.type(screen.getByLabelText("Reason if rejected"), "Use an official gallery email.");
    await user.click(screen.getByRole("button", { name: "Reject claim" }));

    await waitFor(() => expect(repository.rejectGalleryClaim).toHaveBeenCalledWith(
      "gallery-one",
      "owner-one",
      "Use an official gallery email.",
      expect.any(String),
    ));
    expect((await screen.findAllByText("Rejected")).length).toBeGreaterThan(1);
  });
});
