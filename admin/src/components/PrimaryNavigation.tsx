import { SignOutIcon } from "./Icons";

const navigation = [
  "Exhibitions",
  "Submissions",
  "Venues",
  "Events",
  "Editors",
  "Audit",
] as const;

export function PrimaryNavigation({
  onSignOut,
  signOutDisabled = false,
}: {
  onSignOut?: () => void;
  signOutDisabled?: boolean;
}) {
  return (
    <aside className="primary-navigation" aria-label="Primary navigation">
      <div className="wordmark">gallr admin</div>
      <nav>
        {navigation.map((item) => (
          <button
            className={`navigation-item${item === "Exhibitions" ? " is-active" : ""}`}
            type="button"
            key={item}
            aria-current={item === "Exhibitions" ? "page" : undefined}
            disabled={item !== "Exhibitions"}
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
