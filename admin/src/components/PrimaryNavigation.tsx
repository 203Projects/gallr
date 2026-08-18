import { SignOutIcon } from "./Icons";
import type { AdminSection } from "../domain";
import type { AdminStaffRole } from "./AuthGate";

const staffNavigation = [
  "Exhibitions",
  "Submissions",
  "Gallery claims",
  "Promotions",
] as const satisfies readonly AdminSection[];

const adminNavigation = [
  ...staffNavigation,
  "Editors",
] as const satisfies readonly AdminSection[];

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
  const navigation = staffRole === "admin"
    ? adminNavigation
    : staffNavigation;

  return (
    <aside className="primary-navigation" aria-label="Primary navigation">
      <div className="wordmark">gallr admin</div>
      <nav>
        {navigation.map((item) => (
          <button
            className={`navigation-item${item === activeItem ? " is-active" : ""}`}
            type="button"
            key={item}
            aria-current={item === activeItem ? "page" : undefined}
            onClick={() => onNavigate(item)}
          >
            {item}
          </button>
        ))}
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
