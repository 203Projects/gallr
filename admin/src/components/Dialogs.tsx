import { useEffect, useId, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { AdminExhibition, PublishReadiness } from "../domain";
import { CloseIcon } from "./Icons";
import { LanguageSwitch, useI18n } from "../i18n";

interface DialogFrameProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  role?: "dialog" | "alertdialog";
}

export function DialogFrame({
  title,
  onClose,
  children,
  footer,
  role = "dialog",
}: DialogFrameProps) {
  const { t } = useI18n();
  const titleId = useId();
  const dialogRef = useRef<HTMLElement>(null);
  const onCloseRef = useRef(onClose);

  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const dialog = dialogRef.current;
    const previousFocus = document.activeElement;
    if (!dialog) return;

    const focusableSelector =
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
    const getFocusable = () =>
      Array.from(dialog.querySelectorAll<HTMLElement>(focusableSelector));

    (getFocusable()[0] ?? dialog).focus();

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== "Tab") return;

      const focusable = getFocusable();
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    dialog.addEventListener("keydown", handleKeyDown);
    return () => {
      dialog.removeEventListener("keydown", handleKeyDown);
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) {
        previousFocus.focus();
      }
    };
  }, []);

  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={onClose}>
      <section
        ref={dialogRef}
        className="dialog"
        role={role}
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="dialog-header">
          <h2 id={titleId}>{title}</h2>
          <div className="dialog-header-actions">
            <button className="icon-button" type="button" onClick={onClose} aria-label={t("common.close")}>
              <CloseIcon />
            </button>
            <LanguageSwitch />
          </div>
        </header>
        <div className="dialog-content">{children}</div>
        {footer && <footer className="dialog-footer">{footer}</footer>}
      </section>
    </div>
  );
}

export function PreviewDialog({
  exhibition,
  onClose,
}: {
  exhibition: AdminExhibition;
  onClose: () => void;
}) {
  const { locale, t, formatDate, localized } = useI18n();
  const displayName = localized(
    exhibition.nameKo,
    exhibition.nameEn,
    t("common.untitledExhibition"),
  );
  const alternateName = locale === "ko" ? exhibition.nameEn : exhibition.nameKo;
  const publicProjection = {
    id: exhibition.id,
    name_ko: exhibition.nameKo,
    name_en: exhibition.nameEn,
    venue_name_ko: exhibition.venueNameKo,
    venue_name_en: exhibition.venueNameEn,
    city_ko: exhibition.cityKo,
    city_en: exhibition.cityEn,
    region_ko: exhibition.regionKo,
    region_en: exhibition.regionEn,
    address_ko: exhibition.addressKo,
    address_en: exhibition.addressEn,
    latitude:
      exhibition.latitude.trim() === "" ? null : Number(exhibition.latitude),
    longitude:
      exhibition.longitude.trim() === "" ? null : Number(exhibition.longitude),
    opening_date: exhibition.openingDate,
    closing_date: exhibition.closingDate,
    description_ko: exhibition.descriptionKo,
    description_en: exhibition.descriptionEn,
    credits_ko: exhibition.creditsKo,
    credits_en: exhibition.creditsEn,
    hours: exhibition.hours || null,
    contact: exhibition.contact || null,
    reception_date: exhibition.receptionDate || null,
    opening_time: exhibition.receptionStartTime || null,
    reception_end_time: exhibition.receptionEndTime || null,
    event_id: exhibition.eventId || null,
    editor_id: exhibition.editorId || null,
    ticket_url: exhibition.ticketUrl.trim() || null,
    is_featured: exhibition.isFeatured,
    is_homepage_featured: exhibition.isHomepageFeatured,
    cover_image_url: exhibition.coverImageUrl,
  };

  return (
    <DialogFrame title={t("common.preview")} onClose={onClose}>
      <article className="preview-card">
        <p>{localized(exhibition.venueNameKo, exhibition.venueNameEn, t("dialog.venueNotSet"))}</p>
        <h3>{displayName}</h3>
        {alternateName && alternateName !== displayName ? <p>{alternateName}</p> : null}
        <dl>
          <div>
            <dt>{t("dialog.dates")}</dt>
            <dd>
              {formatDate(exhibition.openingDate)} – {formatDate(exhibition.closingDate)}
            </dd>
          </div>
          <div>
            <dt>{t("dialog.location")}</dt>
            <dd>
              {[
                localized(exhibition.cityKo, exhibition.cityEn),
                localized(exhibition.regionKo, exhibition.regionEn),
              ].filter(Boolean).join(" ") || "—"}
            </dd>
          </div>
        </dl>
        <p>{localized(exhibition.descriptionKo, exhibition.descriptionEn)}</p>
        {localized(exhibition.creditsKo, exhibition.creditsEn) && (
          <p>{localized(exhibition.creditsKo, exhibition.creditsEn)}</p>
        )}
      </article>
      <details className="contract-preview">
        <summary>{t("dialog.apiContract")}</summary>
        <pre>{JSON.stringify(publicProjection, null, 2)}</pre>
      </details>
    </DialogFrame>
  );
}

export function PublishDialog({
  exhibition,
  readiness,
  publishing,
  onClose,
  onConfirm,
}: {
  exhibition: AdminExhibition;
  readiness: PublishReadiness;
  publishing: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const { t, localized } = useI18n();
  const checks: Array<[string, boolean]> = [
    [t("dialog.identityComplete"), readiness.identityComplete],
    [t("dialog.venueComplete"), readiness.venueComplete],
    [t("dialog.locationComplete"), readiness.locationComplete],
    [t("dialog.datesValid"), readiness.datesValid],
    [t("dialog.imagesProcessed"), readiness.mediaReady],
  ];

  return (
    <DialogFrame
      title={t("dialog.publishTitle")}
      onClose={onClose}
      footer={
        <>
          <button className="outlined-button" type="button" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            className="accent-button"
            type="button"
            disabled={publishing}
            onClick={onConfirm}
          >
            {t(publishing ? "common.publishing" : "common.publish")}
          </button>
        </>
      }
    >
      <p>
        <strong>{localized(exhibition.nameKo, exhibition.nameEn, t("common.untitledExhibition"))}</strong>
      </p>
      <p className="muted contract-id">{exhibition.id}</p>
      <ul className="publish-checklist">
        {checks.map(([label, complete]) => (
          <li key={label}>
            <span aria-hidden="true">{complete ? "✓" : "!"}</span>
            {label}
          </li>
        ))}
      </ul>
      <p>{t("dialog.publishWarning")}</p>
    </DialogFrame>
  );
}

export function LifecycleDialog({
  exhibition,
  action,
  busy,
  onClose,
  onConfirm,
}: {
  exhibition: AdminExhibition;
  action: "archive" | "restore";
  busy: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const { t, localized } = useI18n();
  const isArchive = action === "archive";
  const actionLabel = t(isArchive ? "common.archive" : "common.restore");

  return (
    <DialogFrame
      title={t(isArchive ? "dialog.archiveTitle" : "dialog.restoreTitle")}
      onClose={onClose}
      footer={
        <>
          <button className="outlined-button" type="button" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            className={isArchive ? "black-button" : "accent-button"}
            type="button"
            disabled={busy}
            onClick={onConfirm}
          >
            {busy ? t("common.working") : actionLabel}
          </button>
        </>
      }
    >
      <p>
        <strong>{localized(exhibition.nameKo, exhibition.nameEn, t("common.untitledExhibition"))}</strong>
      </p>
      <p className="muted contract-id">{exhibition.id}</p>
      <p>
        {isArchive
          ? t("dialog.archiveBody")
          : exhibition.publishedVersionId
            ? t("dialog.restorePublishedBody")
            : t("dialog.restoreDraftBody")}
      </p>
    </DialogFrame>
  );
}

export function DiscardDraftDialog({
  exhibition,
  busy,
  onClose,
  onConfirm,
}: {
  exhibition: AdminExhibition;
  busy: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const { t, formatNumber, localized } = useI18n();
  return (
    <DialogFrame
      title={t("dialog.discardTitle")}
      onClose={onClose}
      footer={
        <>
          <button className="outlined-button" type="button" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            className="black-button"
            type="button"
            disabled={busy}
            onClick={onConfirm}
          >
            {t(busy ? "dialog.discarding" : "dialog.discardAction")}
          </button>
        </>
      }
    >
      <p>
        <strong>{localized(exhibition.nameKo, exhibition.nameEn, t("common.untitledExhibition"))}</strong>
      </p>
      <p className="muted contract-id">{exhibition.id}</p>
      <p>{t("dialog.discardBody", { version: formatNumber(exhibition.versionNumber) })}</p>
    </DialogFrame>
  );
}

export function DeleteDraftDialog({
  exhibition,
  busy,
  hasAttachedMedia,
  onClose,
  onConfirm,
}: {
  exhibition: AdminExhibition;
  busy: boolean;
  hasAttachedMedia: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const { t, localized } = useI18n();
  const [confirmation, setConfirmation] = useState("");
  const confirmed = confirmation === "DELETE";

  return (
    <DialogFrame
      title={t("dialog.deleteTitle")}
      onClose={onClose}
      footer={
        <>
          <button className="outlined-button" type="button" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button
            className="black-button"
            type="button"
            disabled={busy || hasAttachedMedia || !confirmed}
            onClick={onConfirm}
          >
            {t(busy ? "dialog.deleting" : "dialog.deleteAction")}
          </button>
        </>
      }
    >
      <p>
        <strong>{localized(exhibition.nameKo, exhibition.nameEn, t("common.untitledExhibition"))}</strong>
      </p>
      <p className="muted contract-id">{exhibition.id}</p>
      <p>{t("dialog.deleteBody")}</p>
      {hasAttachedMedia && (
        <p className="field-error" role="alert">
          {t("dialog.removeImages")}
        </p>
      )}
      <label className="field">
        <span>{t("dialog.typeDelete")}</span>
        <input
          type="text"
          autoComplete="off"
          value={confirmation}
          disabled={busy || hasAttachedMedia}
          onChange={(event) => setConfirmation(event.target.value)}
        />
      </label>
    </DialogFrame>
  );
}
