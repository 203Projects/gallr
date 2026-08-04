import { useEffect, useState } from "react";
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
  | "startLaunchCheckout"
>;

const ownerErrorExplanations: ReadonlyArray<readonly [string, string]> = [
  [
    "owner_submission_incomplete",
    "Complete the required title, venue, address, dates, and hours before submitting.",
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

function Field({
  label,
  value,
  onChange,
  type = "text",
  disabled = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  disabled?: boolean;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <input
        type={type}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
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
  const [error, setError] = useState<string | null>(null);
  const [launchNotice, setLaunchNotice] = useState(false);
  const canEdit = record.ownerStatus === "draft" || record.ownerStatus === "needs_changes";

  const update = <Key extends keyof OwnerExhibition>(key: Key, value: OwnerExhibition[Key]) => {
    setRecord((current) => ({ ...current, [key]: value }));
    setSaved(false);
  };

  const save = async () => {
    if (!canEdit || busy) return;
    setBusy("save");
    setError(null);
    try {
      const updated = await repository.saveExhibitionDraft(
        record.id,
        record.workingVersionId,
        record.revision,
        editablePatch(record),
      );
      setRecord(updated);
      onChange(updated);
      setSaved(true);
    } catch (cause) {
      setError(errorMessage(cause, "Draft could not be saved."));
    } finally {
      setBusy(null);
    }
  };

  const upload = async (file: File | undefined) => {
    if (!file || !canEdit || busy) return;
    setBusy("cover");
    setError(null);
    try {
      const updated = await repository.uploadCover(
        record.id,
        record.workingVersionId,
        record.revision,
        file,
      );
      setRecord(updated);
      onChange(updated);
      setSaved(false);
    } catch (cause) {
      setError(errorMessage(cause, "Cover could not be uploaded."));
    } finally {
      setBusy(null);
    }
  };

  const submit = async () => {
    if (!canEdit || membershipStatus !== "active" || busy) return;
    setBusy("submit");
    setError(null);
    try {
      const updated = await repository.submitExhibition(
        record.id,
        record.workingVersionId,
        record.revision,
        requestId(),
      );
      setRecord(updated);
      onChange(updated);
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
      const result = await repository.startLaunchCheckout(record.id);
      if (result.active) onLaunchReady();
      else if (result.url) window.location.assign(result.url);
    } catch (cause) {
      setError(errorMessage(cause, "Launch Kit checkout could not be started."));
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
              <Field label="Name (Korean)" value={record.nameKo} disabled={!canEdit} onChange={(value) => update("nameKo", value)} />
              <Field label="Name (English)" value={record.nameEn} disabled={!canEdit} onChange={(value) => update("nameEn", value)} />
            </div>
            <div className="date-pair">
              <Field label="Opening date" type="date" value={record.openingDate} disabled={!canEdit} onChange={(value) => update("openingDate", value)} />
              <Field label="Closing date" type="date" value={record.closingDate} disabled={!canEdit} onChange={(value) => update("closingDate", value)} />
            </div>
            <label className="field">
              <span>Description (Korean)</span>
              <textarea rows={6} value={record.descriptionKo} disabled={!canEdit} onChange={(event) => update("descriptionKo", event.target.value)} />
            </label>
            <label className="field">
              <span>Description (English)</span>
              <textarea rows={6} value={record.descriptionEn} disabled={!canEdit} onChange={(event) => update("descriptionEn", event.target.value)} />
            </label>
          </section>

          <section className="editor-section">
            <h2>Visit details</h2>
            <div className="field-pair">
              <Field label="Venue name (Korean)" value={record.venueNameKo} disabled={!canEdit} onChange={(value) => update("venueNameKo", value)} />
              <Field label="Venue name (English)" value={record.venueNameEn} disabled={!canEdit} onChange={(value) => update("venueNameEn", value)} />
            </div>
            <div className="field-pair">
              <Field label="City (Korean)" value={record.cityKo} disabled={!canEdit} onChange={(value) => update("cityKo", value)} />
              <Field label="City (English)" value={record.cityEn} disabled={!canEdit} onChange={(value) => update("cityEn", value)} />
            </div>
            <div className="field-pair">
              <Field label="Region (Korean)" value={record.regionKo} disabled={!canEdit} onChange={(value) => update("regionKo", value)} />
              <Field label="Region (English)" value={record.regionEn} disabled={!canEdit} onChange={(value) => update("regionEn", value)} />
            </div>
            <Field label="Address (Korean)" value={record.addressKo} disabled={!canEdit} onChange={(value) => update("addressKo", value)} />
            <Field label="Address (English)" value={record.addressEn} disabled={!canEdit} onChange={(value) => update("addressEn", value)} />
            <Field label="Hours" value={record.hours} disabled={!canEdit} onChange={(value) => update("hours", value)} />
            <Field label="Contact" value={record.contact} disabled={!canEdit} onChange={(value) => update("contact", value)} />
            <div className="date-pair">
              <Field label="Opening reception date" type="date" value={record.receptionDate} disabled={!canEdit} onChange={(value) => update("receptionDate", value)} />
              <Field label="Opening reception time" type="time" value={record.receptionStartTime} disabled={!canEdit} onChange={(value) => update("receptionStartTime", value)} />
            </div>
            <Field label="Ticket URL" type="url" value={record.ticketUrl} disabled={!canEdit} onChange={(value) => update("ticketUrl", value)} />
          </section>
        </div>

        <aside className="editor-media">
          <h2>Cover image</h2>
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
                disabled={Boolean(busy)}
                onChange={(event) => void upload(event.target.files?.[0])}
              />
            </label>
          )}
          <p className="media-help">JPEG, PNG, or WebP. Maximum 10 MB.</p>

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
                  {busy === "launch" ? "Opening checkout…" : "Launch this exhibition"}
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
