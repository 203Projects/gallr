import { useCallback, useEffect, useState } from "react";
import type {
  ExistingGalleryClaimInput,
  GallerySearchResult,
  NewGalleryClaimInput,
  OwnerAccess,
  OwnerAuth,
  OwnerRepository,
  OwnerSession,
} from "../domain";
import { ExhibitionWorkspace } from "./ExhibitionWorkspace";
import { OwnerShell } from "./OwnerShell";
import { LaunchKitWorkspace } from "./LaunchKitWorkspace";

type WorkspaceState =
  | { kind: "checking" }
  | { kind: "signed-out" }
  | { kind: "ready"; session: OwnerSession; access: OwnerAccess | null }
  | { kind: "error"; message: string };

type OwnerWorkspace = "exhibitions" | "launch";

function checkoutReturn(search: string): "success" | "cancelled" | null {
  const value = new URLSearchParams(search).get("launch");
  return value === "success" || value === "cancelled" ? value : null;
}

function initialOwnerWorkspace(search: string, launchKitEnabled: boolean): OwnerWorkspace {
  return launchKitEnabled && checkoutReturn(search) === "success" ? "launch" : "exhibitions";
}

function cleanedCheckoutReturnUrl(currentUrl: string): string | null {
  const url = new URL(currentUrl);
  if (!checkoutReturn(url.search)) return null;
  url.searchParams.delete("launch");
  url.searchParams.delete("session_id");
  return `${url.pathname}${url.search}${url.hash}`;
}

function message(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function SignIn({ auth }: { auth: OwnerAuth }) {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!email.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await auth.sendOtp(email.trim());
      setSent(true);
    } catch (cause) {
      setError(message(cause, "Sign-in email could not be sent."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="auth-layout">
      <div className="auth-wordmark">gallr gallery</div>
      <section className="auth-panel">
        <h1>Publish with gallr</h1>
        {sent ? (
          <div className="auth-confirmation" role="status">
            <h2>Check your email</h2>
            <p>Use the secure sign-in message sent to {email.trim()}.</p>
            <button className="text-button" type="button" onClick={() => setSent(false)}>
              Use a different email
            </button>
          </div>
        ) : (
          <form onSubmit={(event) => void submit(event)}>
            <p>Manage free exhibition listings for your gallery.</p>
            <label className="field">
              <span>Email</span>
              <input
                type="email"
                autoComplete="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                required
              />
            </label>
            {error && <p className="field-error" role="alert">! {error}</p>}
            <button className="standard-button auth-submit" type="submit" disabled={busy}>
              {busy ? "Sending…" : "Send sign-in code"}
            </button>
          </form>
        )}
      </section>
    </main>
  );
}

interface EvidenceFieldsProps {
  value: { websiteUrl: string; socialUrl: string; claimNote: string };
  onChange: (value: EvidenceFieldsProps["value"]) => void;
}

function EvidenceFields({ value, onChange }: EvidenceFieldsProps) {
  return (
    <div className="evidence-grid">
      <label className="field">
        <span>Official website</span>
        <input
          type="url"
          value={value.websiteUrl}
          onChange={(event) => onChange({ ...value, websiteUrl: event.target.value })}
          placeholder="https://"
        />
      </label>
      <label className="field">
        <span>Official social profile</span>
        <input
          type="url"
          value={value.socialUrl}
          onChange={(event) => onChange({ ...value, socialUrl: event.target.value })}
          placeholder="https://"
        />
      </label>
      <label className="field field-wide">
        <span>Claim note</span>
        <textarea
          value={value.claimNote}
          onChange={(event) => onChange({ ...value, claimNote: event.target.value })}
          rows={4}
        />
      </label>
    </div>
  );
}

function GalleryOnboarding({
  repository,
  onAccess,
  onSignOut,
}: {
  repository: OwnerRepository;
  onAccess: (access: OwnerAccess) => void;
  onSignOut: () => void;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<GallerySearchResult[] | null>(null);
  const [selected, setSelected] = useState<GallerySearchResult | null>(null);
  const [creating, setCreating] = useState(false);
  const [nameKo, setNameKo] = useState("");
  const [nameEn, setNameEn] = useState("");
  const [evidence, setEvidence] = useState({ websiteUrl: "", socialUrl: "", claimNote: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const search = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (query.trim().length < 2 || busy) return;
    setBusy(true);
    setError(null);
    try {
      setResults(await repository.searchGalleries(query.trim()));
    } catch (cause) {
      setError(message(cause, "Gallery search failed."));
    } finally {
      setBusy(false);
    }
  };

  const evidencePresent = Boolean(
    evidence.websiteUrl.trim() || evidence.socialUrl.trim() || evidence.claimNote.trim(),
  );

  const claimExisting = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selected || busy) return;
    if (!evidencePresent) {
      setError("Add an official website, social profile, or claim note.");
      return;
    }
    const input: ExistingGalleryClaimInput = { galleryId: selected.galleryId, ...evidence };
    setBusy(true);
    setError(null);
    try {
      onAccess(await repository.claimExistingGallery(input));
    } catch (cause) {
      setError(message(cause, "Gallery claim could not be submitted."));
    } finally {
      setBusy(false);
    }
  };

  const createGallery = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!nameKo.trim() || busy) return;
    if (!evidencePresent) {
      setError("Add an official website, social profile, or claim note.");
      return;
    }
    const input: NewGalleryClaimInput = { nameKo, nameEn, ...evidence };
    setBusy(true);
    setError(null);
    try {
      onAccess(await repository.createGalleryClaim(input));
    } catch (cause) {
      setError(message(cause, "Gallery workspace could not be created."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <OwnerShell active="setup" onSignOut={onSignOut}>
      <main className="workspace onboarding-workspace">
        <h1>Set up your gallery</h1>
        <p className="workspace-intro">Search before creating a new gallery workspace.</p>
        {!creating && !selected && (
          <>
            <form className="search-form" onSubmit={(event) => void search(event)}>
              <label className="field search-input">
                <span>Gallery name</span>
                <input
                  type="search"
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="Search Korean or English name"
                />
              </label>
              <button className="standard-button" type="submit" disabled={busy || query.trim().length < 2}>
                Search
              </button>
            </form>
            <p className="search-help">Search for your gallery by name to see if it already exists.</p>
            {results && (
              <div className="search-results" aria-live="polite">
                {results.length === 0 ? (
                  <p>No matching gallery found.</p>
                ) : results.map((gallery) => (
                  <article className="gallery-result" key={gallery.galleryId}>
                    <div>
                      <h2>{gallery.nameKo}</h2>
                      {gallery.nameEn && <p>{gallery.nameEn}</p>}
                      {gallery.addressKo && <p>{gallery.addressKo}</p>}
                    </div>
                    <button
                      className="outlined-button"
                      type="button"
                      disabled={gallery.isClaimed}
                      onClick={() => setSelected(gallery)}
                    >
                      {gallery.isClaimed ? "Already claimed" : "Request access"}
                    </button>
                  </article>
                ))}
              </div>
            )}
            <div className="create-divider">
              <span>Can’t find your gallery?</span>
              <button className="text-button" type="button" onClick={() => setCreating(true)}>
                Create a new gallery
              </button>
            </div>
          </>
        )}

        {selected && (
          <form className="claim-form" onSubmit={(event) => void claimExisting(event)}>
            <button className="text-button back-action" type="button" onClick={() => setSelected(null)}>
              Back to search
            </button>
            <h2>Request access to {selected.nameKo}</h2>
            <p>Share one official reference so staff can verify the claim.</p>
            <EvidenceFields value={evidence} onChange={setEvidence} />
            {error && <p className="field-error" role="alert">! {error}</p>}
            <button className="standard-button" type="submit" disabled={busy}>Submit claim</button>
          </form>
        )}

        {creating && (
          <form className="claim-form" onSubmit={(event) => void createGallery(event)}>
            <button className="text-button back-action" type="button" onClick={() => setCreating(false)}>
              Back to search
            </button>
            <h2>Create a new gallery</h2>
            <div className="evidence-grid">
              <label className="field">
                <span>Gallery name (Korean)</span>
                <input value={nameKo} onChange={(event) => setNameKo(event.target.value)} required />
              </label>
              <label className="field">
                <span>Gallery name (English)</span>
                <input value={nameEn} onChange={(event) => setNameEn(event.target.value)} />
              </label>
            </div>
            <EvidenceFields value={evidence} onChange={setEvidence} />
            {error && <p className="field-error" role="alert">! {error}</p>}
            <button className="standard-button" type="submit" disabled={busy}>Create gallery</button>
          </form>
        )}
        {!selected && error && <p className="field-error" role="alert">! {error}</p>}
      </main>
    </OwnerShell>
  );
}

function SuspendedAccess({ onSignOut }: { onSignOut: () => void }) {
  return (
    <main className="blocked-layout">
      <strong>gallr gallery</strong>
      <section>
        <h1>Gallery access suspended</h1>
        <p>This workspace is unavailable. Contact Gallr support to review access.</p>
        <button className="outlined-button" type="button" onClick={onSignOut}>Sign out</button>
      </section>
    </main>
  );
}

export function OwnerApp({
  auth,
  repository,
  launchKitEnabled = false,
  promotionEnabled = false,
  publicSiteUrl = "https://gallrmap.com",
}: {
  auth: OwnerAuth;
  repository: OwnerRepository;
  launchKitEnabled?: boolean;
  promotionEnabled?: boolean;
  publicSiteUrl?: string;
}) {
  const [state, setState] = useState<WorkspaceState>({ kind: "checking" });
  const [activeWorkspace, setActiveWorkspace] = useState<OwnerWorkspace>(() => (
    initialOwnerWorkspace(window.location.search, launchKitEnabled)
  ));

  const synchronize = useCallback(async (session: OwnerSession | null) => {
    if (!session) {
      setState({ kind: "signed-out" });
      return;
    }
    setState({ kind: "checking" });
    try {
      const access = await repository.currentAccess();
      setState({ kind: "ready", session, access });
    } catch (cause) {
      setState({ kind: "error", message: message(cause, "Gallery access could not be verified.") });
    }
  }, [repository]);

  useEffect(() => {
    const cleanedUrl = cleanedCheckoutReturnUrl(window.location.href);
    if (cleanedUrl) {
      window.history.replaceState(window.history.state, "", cleanedUrl);
    }
  }, []);

  useEffect(() => {
    let current = true;
    void auth.getSession()
      .then((session) => {
        if (current) return synchronize(session);
      })
      .catch((cause) => {
        if (current) {
          setState({
            kind: "error",
            message: message(cause, "Session could not be verified."),
          });
        }
      });
    const unsubscribe = auth.subscribe((session) => {
      if (current) void synchronize(session);
    });
    return () => {
      current = false;
      unsubscribe();
    };
  }, [auth, synchronize]);

  const signOut = async () => {
    try {
      await auth.signOut();
      setState({ kind: "signed-out" });
    } catch (cause) {
      setState({ kind: "error", message: message(cause, "Sign out failed.") });
    }
  };

  if (state.kind === "checking") return <main className="loading-state">Loading gallery workspace…</main>;
  if (state.kind === "signed-out") return <SignIn auth={auth} />;
  if (state.kind === "error") {
    return (
      <main className="blocked-layout">
        <strong>gallr gallery</strong>
        <section><h1>Workspace unavailable</h1><p>! {state.message}</p></section>
      </main>
    );
  }
  if (!state.access || state.access.membership.status === "rejected" || state.access.membership.status === "revoked") {
    return (
      <GalleryOnboarding
        repository={repository}
        onAccess={(access) => setState({ ...state, access })}
        onSignOut={() => void signOut()}
      />
    );
  }
  if (state.access.membership.status === "suspended") {
    return <SuspendedAccess onSignOut={() => void signOut()} />;
  }
  return launchKitEnabled && activeWorkspace === "launch" ? (
    <LaunchKitWorkspace
      repository={repository}
      onNavigate={setActiveWorkspace}
      onSignOut={() => void signOut()}
      promotionEnabled={promotionEnabled}
    />
  ) : (
    <ExhibitionWorkspace
      membershipStatus={state.access.membership.status}
      repository={repository}
      onSignOut={() => void signOut()}
      onNavigateLaunch={() => setActiveWorkspace("launch")}
      launchKitEnabled={launchKitEnabled}
      publicSiteUrl={publicSiteUrl}
    />
  );
}
