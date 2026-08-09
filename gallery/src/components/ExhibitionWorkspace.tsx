import { useEffect, useId, useState } from "react";
import type {
  MembershipStatus,
  OwnerExhibition,
  OwnerExhibitionPatch,
  OwnerRepository,
} from "../domain";
import { OwnerShell } from "./OwnerShell";
import { publicExhibitionUrl } from "../publicExhibitionUrl";

type ExhibitionRepository = Pick<
  OwnerRepository,
  | "listExhibitions"
  | "createExhibitionDraft"
  | "saveExhibitionDraft"
  | "uploadCover"
  | "submitExhibition"
  | "activateLaunchKit"
>;

const ownerErrorExplanations: ReadonlyArray<readonly [string, string]> = [
  [
    "owner_submission_incomplete",
    "Complete the required Korean and English fields, dates, and hours before submitting.",
  ],
  [
    "owner_submission_bilingual_incomplete",
    "Complete the required Korean and English fields before submitting.",
  ],
  ["owner_submission_cover_required", "Add a cover image before submitting."],
  ["active_gallery_membership_required", "Gallery verification is required before submission."],
  ["revision_conflict", "This draft changed elsewhere. Reload it and try again."],
  ["owner_cover_mime_invalid", "Choose a JPEG, PNG, or WebP image."],
  ["owner_cover_size_invalid", "Choose an image smaller than 10 MB."],
  ["owner_cover_filename_invalid", "Choose an image with a valid filename."],
  ["owner_cover_object_not_found", "The cover upload did not finish. Try again."],
  ["owner_cover_mime_mismatch", "The uploaded image type did not match the selected file."],
  ["owner_cover_size_mismatch", "The uploaded image size could not be verified."],
  ["owner_patch_ticket_url_invalid", "Ticket URL must start with http:// or https://."],
  ["owner_patch_date_invalid", "Use a valid calendar date in YYYY-MM-DD format."],
  ["owner_patch_time_invalid", "Use a valid time in 24-hour HH:MM format."],
  ["owner_patch_field_too_long", "One or more fields is too long. Shorten the highlighted content and try again."],
  ["owner_patch_field_invalid", "One or more fields has an unsupported format."],
  ["owner_patch_field_not_allowed", "The form included an unsupported field. Reload and try again."],
  ["patch_must_be_an_object", "The draft format was invalid. Reload and try again."],
];

function errorMessage(error: unknown, fallback: string): string {
  const raw = error instanceof Error && error.message ? error.message : "";
  for (const [code, explanation] of ownerErrorExplanations) {
    if (raw.includes(code)) return explanation;
  }
  return raw || fallback;
}

function requestId(): string {
  return crypto.randomUUID();
}

function statusLabel(status: OwnerExhibition["ownerStatus"]): string {
  switch (status) {
    case "needs_changes": return "Needs changes";
    case "submitted": return "Submitted";
    case "published": return "Published";
    case "archived": return "Archived";
    default: return "Draft";
  }
}

function ImpactSummary({ exhibition }: { exhibition: OwnerExhibition }) {
  if (exhibition.ownerStatus !== "published") return null;
  return (
    <div className="impact-summary">
      <dl>
        <div><dt>Last 30 days</dt><dd>{exhibition.pageLoads30d.toLocaleString("en-US")}</dd></div>
        <div><dt>All time</dt><dd>{exhibition.pageLoadsAllTime.toLocaleString("en-US")}</dd></div>
      </dl>
      <p>Public page loads, not unique visitors.</p>
    </div>
  );
}

function editablePatch(exhibition: OwnerExhibition): OwnerExhibitionPatch {
  return {
    nameKo: exhibition.nameKo,
    nameEn: exhibition.nameEn,
    venueNameKo: exhibition.venueNameKo,
    venueNameEn: exhibition.venueNameEn,
    cityKo: exhibition.cityKo,
    cityEn: exhibition.cityEn,
    regionKo: exhibition.regionKo,
    regionEn: exhibition.regionEn,
    addressKo: exhibition.addressKo,
    addressEn: exhibition.addressEn,
    openingDate: exhibition.openingDate,
    closingDate: exhibition.closingDate,
    descriptionKo: exhibition.descriptionKo,
    descriptionEn: exhibition.descriptionEn,
    hours: exhibition.hours,
    contact: exhibition.contact,
    receptionDate: exhibition.receptionDate,
    receptionStartTime: exhibition.receptionStartTime,
    ticketUrl: exhibition.ticketUrl,
  };
}

function validHttpUrl(value: string): boolean {
  if (!value.trim()) return true;
  try {
    const url = new URL(value.trim());
    return (url.protocol === "http:" || url.protocol === "https:") && Boolean(url.hostname);
  } catch {
    return false;
  }
}

type EditableField = keyof OwnerExhibitionPatch;
type FieldErrors = Partial<Record<EditableField, string>>;

const fieldLabels: Record<EditableField, string> = {
  nameKo: "Name (Korean)",
  nameEn: "Name (English)",
  venueNameKo: "Venue name (Korean)",
  venueNameEn: "Venue name (English)",
  cityKo: "City (Korean)",
  cityEn: "City (English)",
  regionKo: "Region (Korean)",
  regionEn: "Region (English)",
  addressKo: "Address (Korean)",
  addressEn: "Address (English)",
  openingDate: "Opening date",
  closingDate: "Closing date",
  descriptionKo: "Description (Korean)",
  descriptionEn: "Description (English)",
  hours: "Hours",
  contact: "Contact",
  receptionDate: "Opening reception date",
  receptionStartTime: "Opening reception time",
  ticketUrl: "Ticket URL",
};

const requiredSubmissionFields: EditableField[] = [
  "nameKo",
  "nameEn",
  "venueNameKo",
  "venueNameEn",
  "cityKo",
  "cityEn",
  "regionKo",
  "regionEn",
  "addressKo",
  "addressEn",
  "openingDate",
  "closingDate",
  "hours",
];

function fieldLimit(field: EditableField): number {
  if (field === "descriptionKo" || field === "descriptionEn") return 20_000;
  if (field === "hours" || field === "contact") return 1_000;
  if (field === "addressKo" || field === "addressEn") return 500;
  return 300;
}

function validIsoDate(value: string): boolean {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day;
}

function draftValidationErrors(exhibition: OwnerExhibition): FieldErrors {
  const patch = editablePatch(exhibition);
  const errors: FieldErrors = {};
  for (const field of Object.keys(fieldLabels) as EditableField[]) {
    const limit = fieldLimit(field);
    if (patch[field].length > limit) {
      errors[field] = `${fieldLabels[field]} must be ${limit.toLocaleString("en-US")} characters or fewer.`;
    }
  }
  for (const field of ["openingDate", "closingDate", "receptionDate"] as const) {
    if (patch[field] && !validIsoDate(patch[field])) {
      errors[field] = `${fieldLabels[field]} must be a valid calendar date.`;
    }
  }
  if (patch.receptionStartTime && !/^([01]\d|2[0-3]):[0-5]\d$/.test(patch.receptionStartTime)) {
    errors.receptionStartTime = "Opening reception time must use 24-hour HH:MM format.";
  }
  if (!validHttpUrl(patch.ticketUrl)) {
    errors.ticketUrl = "Ticket URL must start with http:// or https://.";
  }
  return errors;
}

function submissionValidationErrors(exhibition: OwnerExhibition): FieldErrors {
  const errors = draftValidationErrors(exhibition);
  for (const field of requiredSubmissionFields) {
    if (!exhibition[field].trim()) errors[field] = "Required for submission.";
  }
  if (
    !errors.openingDate &&
    !errors.closingDate &&
    exhibition.openingDate &&
    exhibition.closingDate &&
    exhibition.closingDate < exhibition.openingDate
  ) {
    errors.closingDate = "Closing date must be on or after the opening date.";
  }
  return errors;
}

function validationSummary(errors: FieldErrors, multiple: string): string | null {
  const entries = Object.entries(errors) as Array<[EditableField, string]>;
  if (entries.length === 0) return null;
  if (entries.length === 1) {
    const [field, message] = entries[0];
    return message === "Required for submission."
      ? `${fieldLabels[field]} is required for submission.`
      : message;
  }
  return multiple;
}

function Field({
  label,
  value,
  onChange,
  type = "text",
  disabled = false,
  required = false,
  error,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  disabled?: boolean;
  required?: boolean;
  error?: string;
}) {
  const inputId = useId();
  const errorId = `${inputId}-error`;
  return (
    <div className={`field${required ? " is-required" : ""}${error ? " has-error" : ""}`}>
      <label htmlFor={inputId}>{label}</label>
      <input
        id={inputId}
        type={type}
        value={value}
        disabled={disabled}
        required={required}
        aria-invalid={error ? "true" : undefined}
        aria-describedby={error ? errorId : undefined}
        onChange={(event) => onChange(event.target.value)}
      />
      {error && <span id={errorId} className="field-inline-error">! {error}</span>}
    </div>
  );
}

function TextAreaField({
  label,
  value,
  onChange,
  disabled = false,
  error,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  error?: string;
}) {
  const inputId = useId();
  const errorId = `${inputId}-error`;
  return (
    <div className={`field${error ? " has-error" : ""}`}>
      <label htmlFor={inputId}>{label}</label>
      <textarea
        id={inputId}
        rows={6}
        value={value}
        disabled={disabled}
        aria-invalid={error ? "true" : undefined}
        aria-describedby={error ? errorId : undefined}
        onChange={(event) => onChange(event.target.value)}
      />
      {error && <span id={errorId} className="field-inline-error">! {error}</span>}
    </div>
  );
}

function Editor({
  exhibition,
  membershipStatus,
  repository,
  onChange,
  onBack,
  onLaunchReady,
  launchKitEnabled,
  publicSiteUrl,
}: {
  exhibition: OwnerExhibition;
  membershipStatus: MembershipStatus;
  repository: ExhibitionRepository;
  onChange: (record: OwnerExhibition) => void;
  onBack: () => void;
  onLaunchReady: () => void;
  launchKitEnabled: boolean;
  publicSiteUrl: string;
}) {
  const [record, setRecord] = useState(exhibition);
  const [busy, setBusy] = useState<"save" | "cover" | "submit" | "launch" | null>(null);
  const [saved, setSaved] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [coverError, setCoverError] = useState<string | null>(null);
  const [launchNotice, setLaunchNotice] = useState(false);
  const canEdit = record.ownerStatus === "draft" || record.ownerStatus === "needs_changes";

  const update = <Key extends keyof OwnerExhibition>(key: Key, value: OwnerExhibition[Key]) => {
    setRecord((current) => ({ ...current, [key]: value }));
    setSaved(false);
    setDirty(true);
    if (key in fieldLabels) {
      setFieldErrors((current) => {
        if (!current[key as EditableField]) return current;
        const updated = { ...current };
        delete updated[key as EditableField];
        return updated;
      });
    }
    setError(null);
  };

  const showValidation = (errors: FieldErrors, summary: string): boolean => {
    const message = validationSummary(errors, summary);
    setFieldErrors(errors);
    setError(message);
    return message !== null;
  };

  const persistDraft = async (current: OwnerExhibition) => {
    const updated = await repository.saveExhibitionDraft(
      current.id,
      current.workingVersionId,
      current.revision,
      editablePatch(current),
    );
    setRecord(updated);
    onChange(updated);
    setDirty(false);
    return updated;
  };

  const save = async () => {
    if (!canEdit || busy) return;
    if (showValidation(
      draftValidationErrors(record),
      "Fix the highlighted fields before saving.",
    )) return;
    setBusy("save");
    setError(null);
    try {
      await persistDraft(record);
      setFieldErrors({});
      setSaved(true);
    } catch (cause) {
      setError(errorMessage(cause, "Draft could not be saved."));
    } finally {
      setBusy(null);
    }
  };

  const upload = async (file: File | undefined) => {
    if (!file || !canEdit || busy) return;
    if (dirty && showValidation(
      draftValidationErrors(record),
      "Fix the highlighted fields before uploading the cover.",
    )) return;
    setBusy("cover");
    setError(null);
    setCoverError(null);
    try {
      const current = dirty ? await persistDraft(record) : record;
      const updated = await repository.uploadCover(
        current.id,
        current.workingVersionId,
        current.revision,
        file,
      );
      setRecord(updated);
      onChange(updated);
      setDirty(false);
      setFieldErrors({});
      setCoverError(null);
      setSaved(true);
    } catch (cause) {
      setError(errorMessage(cause, "Cover could not be uploaded."));
    } finally {
      setBusy(null);
    }
  };

  const submit = async () => {
    if (!canEdit || membershipStatus !== "active" || busy) return;
    const validationErrors = submissionValidationErrors(record);
    const hasReadyCover = record.cover?.status === "ready" || record.cover?.status === "published";
    const hasFieldErrors = showValidation(
      validationErrors,
      "Complete the highlighted required fields before submitting.",
    );
    if (!hasReadyCover) {
      setCoverError("A cover image is required for submission.");
      if (!hasFieldErrors) setError("Add a cover image before submitting.");
    } else {
      setCoverError(null);
    }
    if (hasFieldErrors || !hasReadyCover) {
      return;
    }
    setBusy("submit");
    setError(null);
    try {
      const current = dirty ? await persistDraft(record) : record;
      const updated = await repository.submitExhibition(
        current.id,
        current.workingVersionId,
        current.revision,
        requestId(),
      );
      setRecord(updated);
      onChange(updated);
      setDirty(false);
      setFieldErrors({});
      setCoverError(null);
      setSaved(false);
    } catch (cause) {
      setError(errorMessage(cause, "Exhibition could not be submitted."));
    } finally {
      setBusy(null);
    }
  };

  const launch = async () => {
    if (busy || record.ownerStatus !== "published") return;
    if (!launchKitEnabled) {
      setError(null);
      setLaunchNotice(true);
      return;
    }
    setBusy("launch");
    setError(null);
    setLaunchNotice(false);
    try {
      await repository.activateLaunchKit(record.id);
      onLaunchReady();
    } catch (cause) {
      setError(errorMessage(cause, "Launch Kit could not be activated."));
    } finally { setBusy(null); }
  };

  return (
    <main className="workspace exhibition-editor-workspace">
      <div className="editor-heading">
        <div>
          <button className="text-button back-action" type="button" onClick={onBack}>
            Back to exhibitions
          </button>
          <h1>Edit exhibition</h1>
          <p className="editor-status" role="status">
            {statusLabel(record.ownerStatus)}{saved ? " · Saved" : ""}
          </p>
          {canEdit && <p className="required-note">* Required for submission</p>}
        </div>
        {canEdit && (
          <button className="standard-button editor-save" type="button" onClick={() => void save()} disabled={Boolean(busy)}>
            {busy === "save" ? "Saving…" : "Save draft"}
          </button>
        )}
      </div>

      {record.reviewNotes && (
        <section className="review-note">
          <strong>Changes requested</strong>
          <p>{record.reviewNotes}</p>
        </section>
      )}

      <div className="editor-columns">
        <div className="editor-fields">
          <section className="editor-section">
            <h2>Exhibition</h2>
            <div className="field-pair">
              <Field label="Name (Korean)" value={record.nameKo} required error={fieldErrors.nameKo} disabled={!canEdit} onChange={(value) => update("nameKo", value)} />
              <Field label="Name (English)" value={record.nameEn} required error={fieldErrors.nameEn} disabled={!canEdit} onChange={(value) => update("nameEn", value)} />
            </div>
            <div className="date-pair">
              <Field label="Opening date" type="date" value={record.openingDate} required error={fieldErrors.openingDate} disabled={!canEdit} onChange={(value) => update("openingDate", value)} />
              <Field label="Closing date" type="date" value={record.closingDate} required error={fieldErrors.closingDate} disabled={!canEdit} onChange={(value) => update("closingDate", value)} />
            </div>
            <TextAreaField label="Description (Korean)" value={record.descriptionKo} error={fieldErrors.descriptionKo} disabled={!canEdit} onChange={(value) => update("descriptionKo", value)} />
            <TextAreaField label="Description (English)" value={record.descriptionEn} error={fieldErrors.descriptionEn} disabled={!canEdit} onChange={(value) => update("descriptionEn", value)} />
          </section>

          <section className="editor-section">
            <h2>Visit details</h2>
            <div className="field-pair">
              <Field label="Venue name (Korean)" value={record.venueNameKo} required error={fieldErrors.venueNameKo} disabled={!canEdit} onChange={(value) => update("venueNameKo", value)} />
              <Field label="Venue name (English)" value={record.venueNameEn} required error={fieldErrors.venueNameEn} disabled={!canEdit} onChange={(value) => update("venueNameEn", value)} />
            </div>
            <div className="field-pair">
              <Field label="City (Korean)" value={record.cityKo} required error={fieldErrors.cityKo} disabled={!canEdit} onChange={(value) => update("cityKo", value)} />
              <Field label="City (English)" value={record.cityEn} required error={fieldErrors.cityEn} disabled={!canEdit} onChange={(value) => update("cityEn", value)} />
            </div>
            <div className="field-pair">
              <Field label="Region (Korean)" value={record.regionKo} required error={fieldErrors.regionKo} disabled={!canEdit} onChange={(value) => update("regionKo", value)} />
              <Field label="Region (English)" value={record.regionEn} required error={fieldErrors.regionEn} disabled={!canEdit} onChange={(value) => update("regionEn", value)} />
            </div>
            <Field label="Address (Korean)" value={record.addressKo} required error={fieldErrors.addressKo} disabled={!canEdit} onChange={(value) => update("addressKo", value)} />
            <Field label="Address (English)" value={record.addressEn} required error={fieldErrors.addressEn} disabled={!canEdit} onChange={(value) => update("addressEn", value)} />
            <Field label="Hours" value={record.hours} required error={fieldErrors.hours} disabled={!canEdit} onChange={(value) => update("hours", value)} />
            <Field label="Contact" value={record.contact} error={fieldErrors.contact} disabled={!canEdit} onChange={(value) => update("contact", value)} />
            <div className="date-pair">
              <Field label="Opening reception date" type="date" value={record.receptionDate} error={fieldErrors.receptionDate} disabled={!canEdit} onChange={(value) => update("receptionDate", value)} />
              <Field label="Opening reception time" type="time" value={record.receptionStartTime} error={fieldErrors.receptionStartTime} disabled={!canEdit} onChange={(value) => update("receptionStartTime", value)} />
            </div>
            <Field label="Ticket URL" type="url" value={record.ticketUrl} error={fieldErrors.ticketUrl} disabled={!canEdit} onChange={(value) => update("ticketUrl", value)} />
          </section>
        </div>

        <aside className="editor-media">
          <h2 className="is-required-heading">Cover image</h2>
          {record.cover?.previewUrl ? (
            <img src={record.cover.previewUrl} alt="Exhibition cover preview" />
          ) : (
            <div className="cover-placeholder">No cover selected</div>
          )}
          {canEdit && (
            <label className="outlined-button file-button">
              <span>{busy === "cover" ? "Uploading…" : "Choose cover image"}</span>
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                aria-label="Choose cover image"
                aria-required="true"
                aria-invalid={coverError ? "true" : undefined}
                disabled={Boolean(busy)}
                onChange={(event) => void upload(event.target.files?.[0])}
              />
            </label>
          )}
          <p className="media-help">JPEG, PNG, or WebP. Maximum 10 MB.</p>
          {coverError && <p className="field-inline-error cover-error">! {coverError}</p>}

          <div className="submission-panel">
            <h2>Review</h2>
            {record.ownerStatus === "submitted" ? (
              <p>Your listing is in staff review.</p>
            ) : record.ownerStatus === "published" ? (
              <>
                <p>Published</p>
                <a href={publicExhibitionUrl(record, publicSiteUrl)}>View public page</a>
                <h2 className="impact-heading">Public impact</h2>
                <ImpactSummary exhibition={record} />
                <button className="primary-button launch-button" type="button" disabled={Boolean(busy)} onClick={() => void launch()}>
                  {busy === "launch" ? "Activating…" : "Activate free Launch Kit"}
                </button>
                {launchNotice && (
                  <p className="submission-help" role="status">
                    Launch Kit is coming soon. Your published listing is already live.
                  </p>
                )}
              </>
            ) : canEdit ? (
              <>
                <button
                  className="primary-button submit-button"
                  type="button"
                  disabled={membershipStatus !== "active" || Boolean(busy)}
                  onClick={() => void submit()}
                >
                  {busy === "submit" ? "Submitting…" : "Submit for review"}
                </button>
                {membershipStatus !== "active" && (
                  <p className="submission-help">Gallery verification is required before submission.</p>
                )}
              </>
            ) : (
              <p>{statusLabel(record.ownerStatus)}</p>
            )}
          </div>
          {error && <p className="field-error editor-error" role="alert">! {error}</p>}
        </aside>
      </div>
    </main>
  );
}

export function ExhibitionWorkspace({
  membershipStatus,
  repository,
  onSignOut,
  onNavigateLaunch = () => undefined,
  launchKitEnabled = false,
  publicSiteUrl = "https://gallrmap.com",
}: {
  membershipStatus: MembershipStatus;
  repository: ExhibitionRepository;
  onSignOut: () => void;
  onNavigateLaunch?: () => void;
  launchKitEnabled?: boolean;
  publicSiteUrl?: string;
}) {
  const [records, setRecords] = useState<OwnerExhibition[]>([]);
  const [selected, setSelected] = useState<OwnerExhibition | null>(null);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void repository.listExhibitions()
      .then((result) => {
        if (current) setRecords(result);
      })
      .catch((cause) => {
        if (current) setError(errorMessage(cause, "Exhibitions could not be loaded."));
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => { current = false; };
  }, [repository]);

  const updateRecord = (updated: OwnerExhibition) => {
    setRecords((current) => current.map((item) => item.id === updated.id ? updated : item));
  };

  const create = async () => {
    if (creating) return;
    setCreating(true);
    setError(null);
    try {
      const created = await repository.createExhibitionDraft(requestId());
      setRecords((current) => [created, ...current]);
      setSelected(created);
    } catch (cause) {
      setError(errorMessage(cause, "Draft could not be created."));
    } finally {
      setCreating(false);
    }
  };

  if (selected) {
    return (
      <OwnerShell active="exhibitions" launchKitEnabled={launchKitEnabled} onNavigate={(target) => target === "launch" && onNavigateLaunch()} onSignOut={onSignOut}>
        <Editor
          exhibition={selected}
          membershipStatus={membershipStatus}
          repository={repository}
          onChange={(updated) => {
            setSelected(updated);
            updateRecord(updated);
          }}
          onBack={() => setSelected(null)}
          onLaunchReady={onNavigateLaunch}
          launchKitEnabled={launchKitEnabled}
          publicSiteUrl={publicSiteUrl}
        />
      </OwnerShell>
    );
  }

  return (
    <OwnerShell active="exhibitions" launchKitEnabled={launchKitEnabled} onNavigate={(target) => target === "launch" && onNavigateLaunch()} onSignOut={onSignOut}>
      <main className="workspace dashboard-workspace">
        <div className="dashboard-heading">
          <div>
            <h1>My exhibitions</h1>
            <p>Prepare and publish free exhibition listings for your gallery.</p>
          </div>
          <button className="primary-button dashboard-create" type="button" disabled={creating} onClick={() => void create()}>
            {creating ? "Creating…" : "Create exhibition"}
          </button>
        </div>
        {membershipStatus === "pending" && (
          <section className="claim-notice">
            <h2>Gallery claim pending</h2>
            <p>You can create drafts while we verify your gallery.</p>
          </section>
        )}
        {error && <p className="field-error dashboard-error" role="alert">! {error}</p>}
        {loading ? (
          <p className="workspace-loading">Loading exhibitions…</p>
        ) : records.length === 0 ? (
          <section className="dashboard-empty">
            <h2>Your exhibitions will appear here.</h2>
            <p>Create a draft when your next exhibition is ready.</p>
          </section>
        ) : (
          <div className="exhibition-list">
            <div className="exhibition-list-head" aria-hidden="true">
              <span>Exhibition</span><span>Dates</span><span>Status</span><span>Impact</span><span>Updated</span>
            </div>
            {records.map((record) => (
              <article className="exhibition-row" key={record.id}>
                <div className="exhibition-identity">
                  <button type="button" onClick={() => setSelected(record)}>{record.nameKo || "Untitled exhibition"}</button>
                  {record.nameEn && <p>{record.nameEn}</p>}
                  {record.reviewNotes && <p className="row-review-note">{record.reviewNotes}</p>}
                  {record.ownerStatus === "published" && <a href={publicExhibitionUrl(record, publicSiteUrl)}>View public page</a>}
                </div>
                <span className="row-dates">{record.openingDate || "—"}<br />{record.closingDate || "—"}</span>
                <span className="row-status">{statusLabel(record.ownerStatus)}</span>
                <span className="row-impact"><ImpactSummary exhibition={record} /></span>
                <span className="row-updated">{record.updatedAt ? new Date(record.updatedAt).toLocaleDateString("en-CA") : "—"}</span>
              </article>
            ))}
          </div>
        )}
      </main>
    </OwnerShell>
  );
}
