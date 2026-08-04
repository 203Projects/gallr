import { render, screen } from "@testing-library/react";
import { GalleryRoot } from "./App";

describe("gallery configuration", () => {
  it("fails closed when Supabase browser configuration is missing", () => {
    render(<GalleryRoot client={null} />);

    expect(screen.getByRole("heading", { name: "Configuration required" }))
      .toBeInTheDocument();
    expect(screen.queryByText(/fixture|sample workspace/i)).not.toBeInTheDocument();
  });
});
