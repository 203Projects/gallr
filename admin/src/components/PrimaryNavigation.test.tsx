import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PrimaryNavigation } from "./PrimaryNavigation";

describe("PrimaryNavigation", () => {
  it("enables Editors for admins", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    render(
      <PrimaryNavigation
        activeItem="Exhibitions"
        staffRole="admin"
        onNavigate={onNavigate}
      />,
    );
    const editors = screen.getByRole("button", { name: "Editors" });
    expect(editors).toBeEnabled();
    await user.click(editors);
    expect(onNavigate).toHaveBeenCalledWith("Editors");
  });

  it.each(["contributor", "publisher"] as const)(
    "hides Editors from %s staff",
    (staffRole) => {
      render(
        <PrimaryNavigation
          activeItem="Exhibitions"
          staffRole={staffRole}
          onNavigate={vi.fn()}
        />,
      );
      expect(screen.queryByRole("button", { name: "Editors" }))
        .not.toBeInTheDocument();
    },
  );

  it("does not render placeholder destinations", () => {
    render(
      <PrimaryNavigation
        activeItem="Exhibitions"
        staffRole="admin"
        onNavigate={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: "Venues" }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Events" }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Audit" }))
      .not.toBeInTheDocument();
  });
});
