import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PrimaryNavigation } from "./PrimaryNavigation";

describe("PrimaryNavigation promotion capability", () => {
  it("keeps Promotions absent by default", () => {
    render(<PrimaryNavigation activeItem="Exhibitions" onNavigate={vi.fn()} />);

    expect(screen.queryByRole("button", { name: "Promotions" })).not.toBeInTheDocument();
  });

  it("exposes Promotions only when its independent capability is enabled", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    render(
      <PrimaryNavigation
        activeItem="Exhibitions"
        onNavigate={onNavigate}
        promotionsEnabled
      />,
    );

    await user.click(screen.getByRole("button", { name: "Promotions" }));
    expect(onNavigate).toHaveBeenCalledWith("Promotions");
  });
});
