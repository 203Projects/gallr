import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type {
  AuthChangeEvent,
  Session,
  SupabaseClient,
} from "@supabase/supabase-js";
import { useState } from "react";
import { vi } from "vitest";
import { AuthGate } from "./AuthGate";

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

function createClient({
  session = null,
  staff = null,
}: {
  session?: Session | null;
  staff?: unknown;
}) {
  const signInWithPassword = vi.fn().mockResolvedValue({ error: null });
  const signInWithOAuth = vi.fn().mockResolvedValue({ data: { url: null }, error: null });
  const resetPasswordForEmail = vi.fn().mockResolvedValue({ error: null });
  const updateUser = vi.fn().mockResolvedValue({ data: { user: {} }, error: null });
  const signOut = vi.fn().mockResolvedValue({ error: null });
  const rpc = vi.fn().mockResolvedValue({ data: staff, error: null });
  const getSession = vi.fn().mockResolvedValue({ data: { session }, error: null });
  let authStateChange:
    | ((event: AuthChangeEvent, session: Session | null) => void)
    | null = null;
  const client = {
    auth: {
      getSession,
      onAuthStateChange: vi.fn().mockImplementation((callback) => {
        authStateChange = callback;
        return { data: { subscription: { unsubscribe: vi.fn() } } };
      }),
      signInWithPassword,
      signInWithOAuth,
      resetPasswordForEmail,
      updateUser,
      signOut,
    },
    rpc,
  } as unknown as SupabaseClient;

  return {
    client,
    rpc,
    getSession,
    signInWithPassword,
    signInWithOAuth,
    resetPasswordForEmail,
    updateUser,
    emitAuthStateChange(event: AuthChangeEvent, nextSession: Session | null) {
      if (!authStateChange) throw new Error("Auth state listener is not registered");
      authStateChange(event, nextSession);
    },
  };
}

function StatefulWorkspace() {
  const [section, setSection] = useState("Basics");
  const [draftValue, setDraftValue] = useState("");
  return (
    <>
      <button
        type="button"
        aria-pressed={section === "Venue"}
        onClick={() => setSection("Venue")}
      >
        Venue
      </button>
      <input
        aria-label="Unsaved draft field"
        value={draftValue}
        onChange={(event) => setDraftValue(event.target.value)}
      />
    </>
  );
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

  it("offers the enabled Google provider without bypassing staff authorization", async () => {
    const user = userEvent.setup();
    const { client, signInWithOAuth } = createClient({});
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    await user.click(screen.getByRole("button", { name: "Continue with Google" }));

    expect(signInWithOAuth).toHaveBeenCalledWith({
      provider: "google",
      options: { redirectTo: window.location.origin },
    });
    expect(screen.queryByRole("button", { name: /apple/i })).not.toBeInTheDocument();
  });

  it("explains when password recovery is temporarily rate limited", async () => {
    const user = userEvent.setup();
    const { client, resetPasswordForEmail } = createClient({});
    resetPasswordForEmail.mockResolvedValueOnce({
      data: {},
      error: {
        code: "over_email_send_rate_limit",
        message: "email rate limit exceeded",
        status: 429,
      },
    });
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    await user.type(screen.getByLabelText("Email"), "editor@example.com");
    await user.click(screen.getByRole("button", { name: "Forgot password?" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Too many reset emails were requested. Wait a few minutes and try again.",
    );
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

  it.each(["SIGNED_IN", "TOKEN_REFRESHED"] as const)(
    "keeps the mounted workspace and unsaved form state during %s for the same user",
    async (event) => {
      const user = userEvent.setup();
      const session = { user: { id: "staff-user" } } as Session;
      const staff = {
        user_id: "staff-user",
        role: "publisher",
        active: true,
      };
      const refreshAccessResult = createDeferred<{
        data: typeof staff;
        error: null;
      }>();
      const { client, emitAuthStateChange, rpc } = createClient({
        session,
        staff,
      });
      render(
        <AuthGate client={client}>
          {() => <StatefulWorkspace />}
        </AuthGate>,
      );

      const venueTab = await screen.findByRole("button", { name: "Venue" });
      const draftField = await screen.findByLabelText("Unsaved draft field");
      await user.click(venueTab);
      await user.type(draftField, "In-progress venue notes");
      rpc.mockReturnValueOnce(refreshAccessResult.promise);

      act(() => emitAuthStateChange(event, session));

      expect(screen.getByRole("button", { name: "Venue" })).toHaveAttribute(
        "aria-pressed",
        "true",
      );
      expect(screen.getByLabelText("Unsaved draft field")).toBe(draftField);
      expect(draftField).toHaveValue("In-progress venue notes");

      await act(async () => {
        refreshAccessResult.resolve({ data: staff, error: null });
        await refreshAccessResult.promise;
      });
      expect(screen.getByRole("button", { name: "Venue" })).toHaveAttribute(
        "aria-pressed",
        "true",
      );
      expect(screen.getByLabelText("Unsaved draft field")).toBe(draftField);
      expect(draftField).toHaveValue("In-progress venue notes");
    },
  );

  it("blocks authenticated users without staff membership", async () => {
    const { client } = createClient({
      session: { user: { id: "ordinary-user" } } as Session,
      staff: null,
    });
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    expect(await screen.findByRole("heading", { name: "Access unavailable" })).toBeInTheDocument();
    expect(screen.queryByText("Admin workspace")).not.toBeInTheDocument();
  });

  it("completes PASSWORD_RECOVERY before returning to the authorized session", async () => {
    const user = userEvent.setup();
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const { client, emitAuthStateChange, rpc, updateUser } = createClient({
      staff: { user_id: "staff-user", role: "publisher", active: true },
    });
    render(
      <AuthGate client={client}>
        {(access) => <div>Workspace for {access.role}</div>}
      </AuthGate>,
    );

    await screen.findByRole("heading", { name: "gallr" });
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));

    expect(
      await screen.findByRole("heading", { name: "Set a new password" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Password requirements")).toHaveTextContent(
      "At least 8 characters",
    );
    expect(screen.getByLabelText("Password requirements")).toHaveTextContent(
      "Not found in known password breaches",
    );
    expect(screen.getByLabelText("Password requirements")).toHaveTextContent(
      "Uppercase letters, numbers, and symbols are optional",
    );
    expect(screen.queryByText("Workspace for publisher")).not.toBeInTheDocument();
    expect(rpc).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("New password"), "gallery-wall-2026");
    await user.type(screen.getByLabelText("Confirm password"), "gallery-wall-2026");
    await user.click(screen.getByRole("button", { name: "Update password" }));

    expect(updateUser).toHaveBeenCalledWith({ password: "gallery-wall-2026" });
    expect(await screen.findByText("Workspace for publisher")).toBeInTheDocument();
    expect(rpc).toHaveBeenCalledWith("admin_current_staff");
  });

  it("validates recovery password length and confirmation before updating", async () => {
    const user = userEvent.setup();
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const { client, emitAuthStateChange, updateUser } = createClient({});
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));
    await screen.findByRole("heading", { name: "Set a new password" });

    await user.type(screen.getByLabelText("New password"), "short");
    await user.type(screen.getByLabelText("Confirm password"), "short");
    await user.click(screen.getByRole("button", { name: "Update password" }));

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Password must be at least 8 characters.",
    );
    expect(updateUser).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText("New password"));
    await user.clear(screen.getByLabelText("Confirm password"));
    await user.type(screen.getByLabelText("New password"), "gallery-one");
    await user.type(screen.getByLabelText("Confirm password"), "gallery-two");
    await user.click(screen.getByRole("button", { name: "Update password" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Passwords do not match.");
    expect(updateUser).not.toHaveBeenCalled();
  });

  it("explains when Supabase rejects a known breached password", async () => {
    const user = userEvent.setup();
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const { client, emitAuthStateChange, updateUser } = createClient({});
    updateUser.mockResolvedValueOnce({
      data: { user: null },
      error: {
        code: "weak_password",
        message: "provider diagnostic",
        reasons: ["pwned"],
        status: 422,
      },
    });
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));
    await screen.findByRole("heading", { name: "Set a new password" });
    await user.type(screen.getByLabelText("New password"), "familiar-password");
    await user.type(screen.getByLabelText("Confirm password"), "familiar-password");
    await user.click(screen.getByRole("button", { name: "Update password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Choose a unique password that has not appeared in a known data breach.",
    );
    expect(screen.queryByText("provider diagnostic")).not.toBeInTheDocument();
  });

  it.each([
    [
      { code: "weak_password", reasons: ["length"] },
      "This password is too short. Use at least 8 characters.",
    ],
    [
      { code: "weak_password", reasons: ["characters"] },
      "This password does not meet the configured character requirements.",
    ],
    [
      { code: "same_password" },
      "Choose a password different from your current password.",
    ],
    [
      { code: "session_expired" },
      "This reset session has expired. Return to sign-in and request a new link.",
    ],
  ])("explains password update rejection %#", async (error, expectedMessage) => {
    const user = userEvent.setup();
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const { client, emitAuthStateChange, updateUser } = createClient({});
    updateUser.mockResolvedValueOnce({ data: { user: null }, error });
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));
    await user.type(screen.getByLabelText("New password"), "gallery-wall-2026");
    await user.type(screen.getByLabelText("Confirm password"), "gallery-wall-2026");
    await user.click(screen.getByRole("button", { name: "Update password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(expectedMessage);
  });

  it("keeps provider recovery errors generic and leaves the form available", async () => {
    const user = userEvent.setup();
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const { client, emitAuthStateChange, updateUser } = createClient({});
    updateUser.mockResolvedValueOnce({
      data: { user: null },
      error: { message: "sensitive provider diagnostic" },
    });
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));
    await screen.findByRole("heading", { name: "Set a new password" });
    await user.type(screen.getByLabelText("New password"), "gallery-wall-2026");
    await user.type(screen.getByLabelText("Confirm password"), "gallery-wall-2026");
    await user.click(screen.getByRole("button", { name: "Update password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Password could not be updated. Try again.",
    );
    expect(screen.queryByText("sensitive provider diagnostic")).not.toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Set a new password" }),
    ).toBeInTheDocument();
  });

  it("contains unexpected recovery failures without exposing exception details", async () => {
    const user = userEvent.setup();
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const { client, emitAuthStateChange, updateUser } = createClient({});
    updateUser.mockRejectedValueOnce(new Error("private network diagnostic"));
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));
    await screen.findByRole("heading", { name: "Set a new password" });
    await user.type(screen.getByLabelText("New password"), "gallery-wall-2026");
    await user.type(screen.getByLabelText("Confirm password"), "gallery-wall-2026");
    await user.click(screen.getByRole("button", { name: "Update password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Password could not be updated. Try again.",
    );
    expect(screen.queryByText("private network diagnostic")).not.toBeInTheDocument();
  });

  it("does not let an access check started before PASSWORD_RECOVERY overwrite the reset form", async () => {
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const accessResult = createDeferred<{
      data: { user_id: string; role: string; active: boolean };
      error: null;
    }>();
    const { client, emitAuthStateChange, rpc } = createClient({
      session: recoverySession,
    });
    rpc.mockReturnValueOnce(accessResult.promise);
    render(
      <AuthGate client={client}>
        {(access) => <div>Workspace for {access.role}</div>}
      </AuthGate>,
    );

    await waitFor(() => expect(rpc).toHaveBeenCalledWith("admin_current_staff"));
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));
    expect(
      await screen.findByRole("heading", { name: "Set a new password" }),
    ).toBeInTheDocument();

    await act(async () => {
      accessResult.resolve({
        data: { user_id: "staff-user", role: "publisher", active: true },
        error: null,
      });
      await accessResult.promise;
    });

    expect(
      screen.getByRole("heading", { name: "Set a new password" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Workspace for publisher")).not.toBeInTheDocument();
  });

  it("fails closed generically when loading the initial session rejects", async () => {
    const { client, getSession } = createClient({});
    getSession.mockRejectedValueOnce(new Error("private session storage diagnostic"));
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    expect(
      await screen.findByRole("heading", { name: "Access unavailable" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Staff access could not be verified. Sign out and try again."),
    ).toBeInTheDocument();
    expect(screen.queryByText("private session storage diagnostic")).not.toBeInTheDocument();
  });

  it("fails closed generically when an access verification rejects", async () => {
    const { client, rpc } = createClient({
      session: { user: { id: "staff-user" } } as Session,
    });
    rpc.mockRejectedValueOnce(new Error("private access diagnostic"));
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    expect(
      await screen.findByRole("heading", { name: "Access unavailable" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Staff access could not be verified. Sign out and try again."),
    ).toBeInTheDocument();
    expect(screen.queryByText("private access diagnostic")).not.toBeInTheDocument();
  });

  it("leaves checking safely when post-update access verification rejects", async () => {
    const user = userEvent.setup();
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const { client, emitAuthStateChange, rpc } = createClient({});
    rpc.mockRejectedValueOnce(new Error("private post-update diagnostic"));
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));
    await screen.findByRole("heading", { name: "Set a new password" });
    await user.type(screen.getByLabelText("New password"), "gallery-wall-2026");
    await user.type(screen.getByLabelText("Confirm password"), "gallery-wall-2026");
    await user.click(screen.getByRole("button", { name: "Update password" }));

    expect(
      await screen.findByRole("heading", { name: "Access unavailable" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Staff access could not be verified. Sign out and try again."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Checking session…")).not.toBeInTheDocument();
    expect(screen.queryByText("private post-update diagnostic")).not.toBeInTheDocument();
  });

  it("clears recovery submission state when a newer auth event supersedes verification", async () => {
    const user = userEvent.setup();
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const accessResult = createDeferred<{
      data: { user_id: string; role: string; active: boolean };
      error: null;
    }>();
    const { client, emitAuthStateChange, rpc } = createClient({});
    rpc.mockReturnValueOnce(accessResult.promise);
    render(<AuthGate client={client}>{() => <div>Admin workspace</div>}</AuthGate>);

    await screen.findByRole("heading", { name: "gallr" });
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));
    await user.type(screen.getByLabelText("New password"), "gallery-wall-2026");
    await user.type(screen.getByLabelText("Confirm password"), "gallery-wall-2026");
    await user.click(screen.getByRole("button", { name: "Update password" }));
    await waitFor(() => expect(rpc).toHaveBeenCalledWith("admin_current_staff"));

    act(() => emitAuthStateChange("SIGNED_OUT", null));
    await screen.findByRole("heading", { name: "gallr" });
    await act(async () => {
      accessResult.resolve({
        data: { user_id: "staff-user", role: "publisher", active: true },
        error: null,
      });
      await accessResult.promise;
    });

    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
    expect(screen.queryByText("Workspace for publisher")).not.toBeInTheDocument();
  });

  it("does not resume recovery completion after signing out during the password update", async () => {
    const user = userEvent.setup();
    const recoverySession = { user: { id: "staff-user" } } as Session;
    const updateResult = createDeferred<{ data: { user: object }; error: null }>();
    const { client, emitAuthStateChange, rpc, updateUser } = createClient({
      staff: { user_id: "staff-user", role: "publisher", active: true },
    });
    updateUser.mockReturnValueOnce(updateResult.promise);
    render(
      <AuthGate client={client}>
        {(access) => <div>Workspace for {access.role}</div>}
      </AuthGate>,
    );

    await screen.findByRole("heading", { name: "gallr" });
    act(() => emitAuthStateChange("PASSWORD_RECOVERY", recoverySession));
    await user.type(screen.getByLabelText("New password"), "gallery-wall-2026");
    await user.type(screen.getByLabelText("Confirm password"), "gallery-wall-2026");
    await user.click(screen.getByRole("button", { name: "Update password" }));
    await waitFor(() => expect(updateUser).toHaveBeenCalledOnce());

    act(() => emitAuthStateChange("SIGNED_OUT", null));
    await act(async () => {
      updateResult.resolve({ data: { user: {} }, error: null });
      await updateResult.promise;
    });

    expect(await screen.findByRole("heading", { name: "gallr" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
    expect(screen.queryByText("Workspace for publisher")).not.toBeInTheDocument();
    expect(rpc).not.toHaveBeenCalled();
  });
});
