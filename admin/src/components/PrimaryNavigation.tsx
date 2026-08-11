import { SignOutIcon } from "./Icons";
import type { AdminSection } from "../domain";
import type { AdminStaffRole } from "./AuthGate";

const navigation = [
  "Exhibitions",
  "Submissions",
  "Gallery claims",
  "Promotions",
  "Venues",
  "Events",
  "Editors",
  "Audit",
] as const;

export function PrimaryNavigation({
  activeItem,
  staffRole,
  onNavigate,
  onSignOut,
  signOutDisabled = false,
}: {
  activeItem: AdminSection;
  staffRole: AdminStaffRole;
  onNavigate: (item: AdminSection) => void;
  onSignOut?: () => void;
  signOutDisabled?: boolean;
}) {
  return (
    <aside className="primary-navigation" aria-label="Primary navigation">
      <div className="wordmark">gallr admin</div>
      <nav>
        {navigation.map((item) => {
          const enabled = item === "Exhibitions" || item === "Submissions" ||
            item === "Gallery claims" || item === "Promotions" ||
            (item === "Editors" && staffRole === "admin");
          return (
          <button
            className={`navigation-item${item === activeItem ? " is-active" : ""}`}
            type="button"
            key={item}
            aria-current={item === activeItem ? "page" : undefined}
            disabled={!enabled}
            onClick={() => {
              if (enabled) {
                onNavigate(item);
              }
            }}
          >
            {item}
          </button>
          );
        })}
      </nav>
      <button
        className="sign-out-button"
        type="button"
        aria-label="Sign out"
        onClick={onSignOut}
        disabled={!onSignOut || signOutDisabled}
        title={
          signOutDisabled
            ? "Resolve or discard the current exhibition changes before signing out."
            : undefined
        }
      >
        <SignOutIcon />
      </button>
    </aside>
  );
}
