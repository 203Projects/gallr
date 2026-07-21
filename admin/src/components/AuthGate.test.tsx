import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { Session, SupabaseClient } from "@supabase/supabase-js";
import { vi } from "vitest";
import { AuthGate } from "./AuthGate";

function createClient({
  session = null,
  staff = null,
}: {
  session?: Session | null;
  staff?: unknown;
}) {
  const signInWithPassword = vi.fn().mockResolvedValue({ error: null });
  const resetPasswordForEmail = vi.fn().mockResolvedValue({ error: null });
  const signOut = vi.fn().mockResolvedValue({ error: null });
  const rpc = vi.fn().mockResolvedValue({ data: staff, error: null });
  const client = {
    auth: {
      getSession: vi.fn().mockResolvedValue({ data: { session }, error: null }),
      onAuthStateChange: vi.fn().mockReturnValue({
        data: { subscription: { unsubscribe: vi.fn() } },
      }),
      signInWithPassword,
      resetPasswordForEmail,
      signOut,
    },
    rpc,
  } as unknown as SupabaseClient;

  return { client, rpc, signInWithPassword };
}

describe("AuthGate", () => {
  it("renders the invite-only login and submits credentials", async () => {
    const user = userEvent.setup();
    const { client, signInWithPassword } = createClient({});
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    await user.type(screen.getByLabelText("Email"), "editor@example.com");
    await user.type(screen.getByLabelText("Password"), "correct-horse");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(signInWithPassword).toHaveBeenCalledWith({
      email: "editor@example.com",
      password: "correct-horse",
    });
    expect(screen.queryByText("Sign up")).not.toBeInTheDocument();
  });

  it("renders the workspace only for an active staff member", async () => {
    const { client, rpc } = createClient({
      session: { user: { id: "staff-user" } } as Session,
      staff: { user_id: "staff-user", role: "publisher", active: true },
    });
    render(
      <AuthGate client={client}>
        {(access) => <div>Workspace for {access.role}</div>}
      </AuthGate>,
    );

    expect(await screen.findByText("Workspace for publisher")).toBeInTheDocument();
    expect(rpc).toHaveBeenCalledWith("admin_current_staff");
    expect(screen.queryByRole("heading", { name: "gallr" })).not.toBeInTheDocument();
  });

  it("blocks authenticated users without staff membership", async () => {
    const { client } = createClient({
      session: { user: { id: "ordinary-user" } } as Session,
      staff: null,
    });
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    expect(await screen.findByRole("heading", { name: "Access unavailable" })).toBeInTheDocument();
    expect(screen.queryByText("Admin workspace")).not.toBeInTheDocument();
  });
});
