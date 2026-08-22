import { render, screen } from "@testing-library/react";
import type { SupabaseClient } from "@supabase/supabase-js";
import { GalleryRoot } from "./App";

describe("gallery configuration", () => {
  it("fails closed when Supabase browser configuration is missing", () => {
    render(<GalleryRoot client={null} />);

    expect(screen.getByRole("heading", { name: "Configuration required" }))
      .toBeInTheDocument();
    expect(screen.queryByText(/fixture|sample workspace/i)).not.toBeInTheDocument();
  });

  it("offers Korean in the configuration-blocked state", () => {
    window.localStorage.setItem("gallr.gallery.locale.v1", "ko");
    render(<GalleryRoot client={null} />);

    expect(screen.getByRole("heading", { name: "설정이 필요합니다" }))
      .toBeInTheDocument();
    expect(screen.getByRole("group", { name: "언어" })).toBeInTheDocument();
    window.localStorage.clear();
  });

  it("fails closed when the matching public-site origin is invalid", () => {
    render(
      <GalleryRoot
        client={{} as SupabaseClient}
        publicSiteUrl=" not-a-public-origin "
      />,
    );

    expect(screen.getByRole("heading", { name: "Configuration required" }))
      .toBeInTheDocument();
  });
});
