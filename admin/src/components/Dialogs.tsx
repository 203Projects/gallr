import { useEffect, useId, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { AdminExhibition, PublishReadiness } from "../domain";
import { CloseIcon } from "./Icons";

interface DialogFrameProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}

function DialogFrame({ title, onClose, children, footer }: DialogFrameProps) {
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
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="dialog-header">
          <h2 id={titleId}>{title}</h2>
          <button className="icon-button" type="button" onClick={onClose} aria-label="Close">
            <CloseIcon />
          </button>
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
    event_id: exhibition.eventId || null,
    editor_id: exhibition.editorId || null,
    ticket_url: exhibition.ticketUrl.trim() || null,
    is_featured: exhibition.isFeatured,
    is_homepage_featured: exhibition.isHomepageFeatured,
    cover_image_url: exhibition.coverImageUrl,
  };

  return (
    <DialogFrame title="Preview" onClose={onClose}>
      <article className="preview-card">
        <p>{exhibition.venueNameKo || "Venue not set"}</p>
        <h3>{exhibition.nameKo || "Untitled exhibition"}</h3>
        <p>{exhibition.nameEn}</p>
        <dl>
          <div>
            <dt>Dates</dt>
            <dd>
              {exhibition.openingDate || "—"} – {exhibition.closingDate || "—"}
            </dd>
          </div>
          <div>
            <dt>Location</dt>
            <dd>
              {[exhibition.cityKo, exhibition.regionKo].filter(Boolean).join(" ") || "—"}
            </dd>
          </div>
        </dl>
        <p>{exhibition.descriptionKo}</p>
        {exhibition.creditsKo && <p>{exhibition.creditsKo}</p>}
      </article>
      <details className="contract-preview">
        <summary>API contract</summary>
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
  const checks: Array<[string, boolean]> = [
    ["Identity is complete", readiness.identityComplete],
    ["Venue is complete", readiness.venueComplete],
    ["Map address and coordinates are complete", readiness.locationComplete],
    ["Dates are valid", readiness.datesValid],
    ["Attached images are processed", readiness.mediaReady],
  ];

  return (
    <DialogFrame
      title="Publish exhibition"
      onClose={onClose}
      footer={
        <>
          <button className="outlined-button" type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            className="accent-button"
            type="button"
            disabled={publishing}
            onClick={onConfirm}
          >
            {publishing ? "Publishing…" : "Publish"}
          </button>
        </>
      }
    >
      <p>
        <strong>{exhibition.nameKo}</strong>
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
      <p>The current public version will be superseded and a website rebuild will be queued.</p>
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
  const isArchive = action === "archive";
  const actionLabel = isArchive ? "Archive" : "Restore";

  return (
    <DialogFrame
      title={`${actionLabel} exhibition`}
      onClose={onClose}
      footer={
        <>
          <button className="outlined-button" type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            className={isArchive ? "black-button" : "accent-button"}
            type="button"
            disabled={busy}
            onClick={onConfirm}
          >
            {busy ? "Working…" : actionLabel}
          </button>
        </>
      }
    >
      <p>
        <strong>{exhibition.nameKo || "Untitled exhibition"}</strong>
      </p>
      <p className="muted contract-id">{exhibition.id}</p>
      <p>
        {isArchive
          ? "This removes the exhibition from public views. Versions, bookmarks, thoughts, and media references are preserved."
          : exhibition.publishedVersionId
            ? "This restores the identity and its last published version. Curated placements stay disabled until the next publish."
            : "This restores the identity as a draft. It stays private until a publisher publishes it."}
      </p>
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
  const [confirmation, setConfirmation] = useState("");
  const confirmed = confirmation === "DELETE";

  return (
    <DialogFrame
      title="Delete draft permanently"
      onClose={onClose}
      footer={
        <>
          <button className="outlined-button" type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            className="black-button"
            type="button"
            disabled={busy || hasAttachedMedia || !confirmed}
            onClick={onConfirm}
          >
            {busy ? "Deleting…" : "Delete permanently"}
          </button>
        </>
      }
    >
      <p>
        <strong>{exhibition.nameKo || "Untitled exhibition"}</strong>
      </p>
      <p className="muted contract-id">{exhibition.id}</p>
      <p>
        This permanently deletes this never-published draft. It cannot be
        restored. Published and archived exhibitions cannot be deleted here.
      </p>
      {hasAttachedMedia && (
        <p className="field-error" role="alert">
          Remove every attached image before deleting this draft.
        </p>
      )}
      <label className="field">
        <span>Type DELETE to confirm</span>
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
