import { SignOutIcon } from "./Icons";
import type { AdminSection } from "../domain";

const navigation = [
  "Exhibitions",
  "Submissions",
  "Venues",
  "Events",
  "Editors",
  "Audit",
] as const;

export function PrimaryNavigation({
  activeItem,
  onNavigate,
  onSignOut,
  signOutDisabled = false,
}: {
  activeItem: AdminSection;
  onNavigate: (item: AdminSection) => void;
  onSignOut?: () => void;
  signOutDisabled?: boolean;
}) {
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
            disabled={item !== "Exhibitions" && item !== "Submissions"}
            onClick={() => {
              if (item === "Exhibitions" || item === "Submissions") {
                onNavigate(item);
              }
            }}
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
