export type OwnerWorkspaceTarget = "exhibitions" | "gallery-info" | "launch";

export function OwnerShell({
  active,
  launchKitEnabled = false,
  galleryInfoEnabled = true,
  onSignOut,
  onNavigate,
  children,
}: {
  active: "setup" | OwnerWorkspaceTarget;
  launchKitEnabled?: boolean;
  galleryInfoEnabled?: boolean;
  onSignOut: () => void;
  onNavigate?: (target: OwnerWorkspaceTarget) => void;
  children: React.ReactNode;
}) {
  return (
    <div className="owner-layout">
      <aside className="owner-rail">
        <strong>gallr gallery</strong>
        <nav aria-label="Gallery workspace">
          {active === "setup" ? (
            <span className="rail-item is-active">Set up gallery</span>
          ) : (
            <>
              <button className={`rail-item ${active === "exhibitions" ? "is-active" : ""}`} type="button" aria-current={active === "exhibitions" ? "page" : undefined} onClick={() => onNavigate?.("exhibitions")}>Exhibitions</button>
              {galleryInfoEnabled && (
                <button className={`rail-item ${active === "gallery-info" ? "is-active" : ""}`} type="button" aria-current={active === "gallery-info" ? "page" : undefined} onClick={() => onNavigate?.("gallery-info")}>Gallery Info</button>
              )}
              {launchKitEnabled && (
                <button className={`rail-item ${active === "launch" ? "is-active" : ""}`} type="button" aria-current={active === "launch" ? "page" : undefined} onClick={() => onNavigate?.("launch")}>Launch Kit</button>
              )}
            </>
          )}
        </nav>
        <button className="rail-sign-out" type="button" onClick={onSignOut}>
          Sign out
        </button>
      </aside>
      <header className="mobile-header">
        <strong>gallr gallery</strong>
        <div className="mobile-header-actions">
          <button type="button" onClick={onSignOut}>Sign out</button>
        </div>
        {active !== "setup" && (
          <nav className="mobile-workspace-nav" aria-label="Gallery workspace">
            <button type="button" aria-current={active === "exhibitions" ? "page" : undefined} onClick={() => onNavigate?.("exhibitions")}>Exhibitions</button>
            {galleryInfoEnabled && <button type="button" aria-current={active === "gallery-info" ? "page" : undefined} onClick={() => onNavigate?.("gallery-info")}>Gallery Info</button>}
            {launchKitEnabled && <button type="button" aria-current={active === "launch" ? "page" : undefined} onClick={() => onNavigate?.("launch")}>Launch Kit</button>}
          </nav>
        )}
      </header>
      {children}
    </div>
  );
}
