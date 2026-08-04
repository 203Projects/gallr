import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { AdminLocalPromotion } from "../domain";
import { PromotionWorkspace } from "./PromotionWorkspace";

const submitted: AdminLocalPromotion = {
  id: "promotion-one",
  launchKitId: "launch-one",
  exhibitionId: "between-seasons",
  galleryId: "gallery-one",
  status: "submitted",
  revision: 1,
  cityKo: "서울", cityEn: "Seoul", regionKo: "용산구", regionEn: "Yongsan-gu",
  startsAt: null, endsAt: null, reviewNotes: "",
  requestedAt: "2026-07-31T10:00:00Z", reviewedAt: null,
  nameKo: "계절 사이", nameEn: "Between Seasons",
  venueNameKo: "아틀리에 한남", venueNameEn: "Atelier Hannam",
  closingDate: "2026-09-14", galleryNameKo: "아틀리에 한남", galleryNameEn: "Atelier Hannam",
};

function repository() {
  return {
    listLocalPromotions: vi.fn().mockResolvedValue([submitted]),
    approveLocalPromotion: vi.fn().mockResolvedValue({ ...submitted, status: "active" as const }),
    rejectLocalPromotion: vi.fn().mockResolvedValue({
      ...submitted, status: "rejected" as const, reviewNotes: "Opening date needs confirmation.",
    }),
  };
}

describe("PromotionWorkspace", () => {
  it("keeps paid promotion in a dedicated queue and approves an explicit schedule", async () => {
    const user = userEvent.setup();
    const source = repository();
    render(<PromotionWorkspace repository={source} />);
    expect((await screen.findAllByText("Between Seasons")).length).toBeGreaterThan(1);
    expect(screen.getByText(/separate from editorial Featured/i)).toBeInTheDocument();
    await user.type(screen.getByLabelText("Starts"), "2026-08-08T09:00");
    await user.type(screen.getByLabelText("Ends"), "2026-08-15T09:00");
    await user.click(screen.getByRole("button", { name: "Approve schedule" }));
    await waitFor(() => expect(source.approveLocalPromotion).toHaveBeenCalledWith(
      "promotion-one",
      new Date("2026-08-08T09:00").toISOString(),
      new Date("2026-08-15T09:00").toISOString(),
      expect.any(String),
    ));
  });

  it("requires a reason before rejecting a promotion", async () => {
    const user = userEvent.setup();
    const source = repository();
    render(<PromotionWorkspace repository={source} />);
    await screen.findAllByText("Between Seasons");
    expect(screen.getByRole("button", { name: "Reject request" })).toBeDisabled();
    await user.type(screen.getByLabelText("Reason if rejected"), "Opening date needs confirmation.");
    await user.click(screen.getByRole("button", { name: "Reject request" }));
    await waitFor(() => expect(source.rejectLocalPromotion).toHaveBeenCalledWith(
      "promotion-one", "Opening date needs confirmation.", expect.any(String),
    ));
  });
});
