export function OwnerShell({
  active,
  onSignOut,
  onNavigate,
  children,
}: {
  active: "setup" | "exhibitions" | "launch";
  onSignOut: () => void;
  onNavigate?: (target: "exhibitions" | "launch") => void;
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
              <button className={`rail-item ${active === "exhibitions" ? "is-active" : ""}`} type="button" onClick={() => onNavigate?.("exhibitions")}>Exhibitions</button>
              <button className={`rail-item ${active === "launch" ? "is-active" : ""}`} type="button" onClick={() => onNavigate?.("launch")}>Launch Kit</button>
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
          {active !== "setup" && (
            <button type="button" onClick={() => onNavigate?.(active === "launch" ? "exhibitions" : "launch")}>
              {active === "launch" ? "Exhibitions" : "Launch Kit"}
            </button>
          )}
          <button type="button" onClick={onSignOut}>Sign out</button>
        </div>
      </header>
      {children}
    </div>
  );
}
