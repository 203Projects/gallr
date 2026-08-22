import { LocaleToggle, useLocale } from "../i18n";

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
  const { messages } = useLocale();
  return (
    <div className="owner-layout">
      <aside className="owner-rail">
        <strong>{messages.common.brand}</strong>
        <nav aria-label={messages.common.galleryWorkspace}>
          {active === "setup" ? (
            <span className="rail-item is-active">{messages.navigation.setup}</span>
          ) : (
            <>
              <button className={`rail-item ${active === "exhibitions" ? "is-active" : ""}`} type="button" aria-current={active === "exhibitions" ? "page" : undefined} onClick={() => onNavigate?.("exhibitions")}>{messages.navigation.exhibitions}</button>
              {galleryInfoEnabled && (
                <button className={`rail-item ${active === "gallery-info" ? "is-active" : ""}`} type="button" aria-current={active === "gallery-info" ? "page" : undefined} onClick={() => onNavigate?.("gallery-info")}>{messages.navigation.galleryInfo}</button>
              )}
              {launchKitEnabled && (
                <button className={`rail-item ${active === "launch" ? "is-active" : ""}`} type="button" aria-current={active === "launch" ? "page" : undefined} onClick={() => onNavigate?.("launch")}>{messages.navigation.launchKit}</button>
              )}
            </>
          )}
        </nav>
        <LocaleToggle className="rail-locale-toggle" />
        <button className="rail-sign-out" type="button" onClick={onSignOut}>
          {messages.common.signOut}
        </button>
      </aside>
      <header className="mobile-header">
        <strong>{messages.common.brand}</strong>
        <div className="mobile-header-actions">
          <LocaleToggle />
          <button type="button" onClick={onSignOut}>{messages.common.signOut}</button>
        </div>
        {active !== "setup" && (
          <nav className="mobile-workspace-nav" aria-label={messages.common.galleryWorkspace}>
            <button type="button" aria-current={active === "exhibitions" ? "page" : undefined} onClick={() => onNavigate?.("exhibitions")}>{messages.navigation.exhibitions}</button>
            {galleryInfoEnabled && <button type="button" aria-current={active === "gallery-info" ? "page" : undefined} onClick={() => onNavigate?.("gallery-info")}>{messages.navigation.galleryInfo}</button>}
            {launchKitEnabled && <button type="button" aria-current={active === "launch" ? "page" : undefined} onClick={() => onNavigate?.("launch")}>{messages.navigation.launchKit}</button>}
          </nav>
        )}
      </header>
      {children}
    </div>
  );
}
