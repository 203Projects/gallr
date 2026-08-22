import { SignOutIcon } from "./Icons";
import type { AdminSection } from "../domain";
import type { AdminStaffRole } from "./AuthGate";
import { LanguageSwitch, useI18n, type MessageKey } from "../i18n";

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

const navigationMessageKeys: Record<AdminSection, MessageKey> = {
  Exhibitions: "navigation.exhibitions",
  Submissions: "navigation.submissions",
  "Gallery claims": "navigation.galleryClaims",
  Promotions: "navigation.promotions",
  Editors: "navigation.editors",
};

export function PrimaryNavigation({
  activeItem,
  staffRole,
  onNavigate,
  onSignOut,
  signOutDisabled = false,
  promotionsEnabled = false,
}: {
  activeItem: AdminSection;
  staffRole: AdminStaffRole;
  onNavigate: (item: AdminSection) => void;
  onSignOut?: () => void;
  signOutDisabled?: boolean;
  promotionsEnabled?: boolean;
}) {
  const { t } = useI18n();
  const roleNavigation = staffRole === "admin"
    ? adminNavigation
    : staffNavigation;
  const navigation = roleNavigation.filter((item) => (
    item !== "Promotions" || promotionsEnabled
  ));

  return (
    <aside className="primary-navigation" aria-label={t("navigation.primary")}>
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
            {t(navigationMessageKeys[item])}
          </button>
        ))}
      </nav>
      <div className="navigation-footer">
        <LanguageSwitch />
        <button
          className="sign-out-button"
          type="button"
          aria-label={t("actions.signOut")}
          onClick={onSignOut}
          disabled={!onSignOut || signOutDisabled}
          title={signOutDisabled ? t("actions.resolveBeforeSignOut") : undefined}
        >
          <SignOutIcon />
        </button>
      </div>
    </aside>
  );
}
