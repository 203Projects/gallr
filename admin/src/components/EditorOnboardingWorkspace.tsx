import { useEffect, useState, type FormEvent } from "react";
import type {
  AdminEditorRepository,
  AdminEditorRequest,
  EditorOnboardingInput,
} from "../repositories/AdminEditorRepository";

const emptyForm: EditorOnboardingInput = {
  email: "",
  editorId: "",
  nameKo: "",
  nameEn: "",
  titleKo: "",
  titleEn: "",
  bioKo: "",
  bioEn: "",
  curationDescriptionKo: "",
  curationDescriptionEn: "",
  isActive: false,
  activeFrom: "",
  activeTo: null,
};

function validationMessage(input: EditorOnboardingInput): string | null {
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(input.editorId.trim())) {
    return "Editor slug must use lowercase letters, numbers, and single hyphens.";
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(input.email.trim())) {
    return "Enter a valid invitation email.";
  }
  if (!input.nameKo.trim() || !input.titleKo.trim() || !input.bioKo.trim()) {
    return "Complete the required Korean profile fields.";
  }
  if (!input.curationDescriptionKo.trim()) {
    return "Add the Korean curatorial statement shown with this collection.";
  }
  if (!input.activeFrom) return "Choose the editor's active-from date.";
  if (input.activeTo && input.activeTo < input.activeFrom) {
    return "Active-to date cannot be earlier than active-from date.";
  }
  return null;
}

interface CurationRequestChange {
  id: string;
  nameKo: string;
  nameEn: string;
  venueNameKo: string;
  selected: boolean;
}

function curationRequestChanges(payload: Record<string, unknown>): CurationRequestChange[] {
  if (!Array.isArray(payload.changes)) return [];
  return payload.changes.flatMap((value) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return [];
    const row = value as Record<string, unknown>;
    if (typeof row.id !== "string" || typeof row.selected !== "boolean") return [];
    return [{
      id: row.id,
      nameKo: typeof row.name_ko === "string" ? row.name_ko : "",
      nameEn: typeof row.name_en === "string" ? row.name_en : "",
      venueNameKo: typeof row.venue_name_ko === "string" ? row.venue_name_ko : "",
      selected: row.selected,
    }];
  });
}

export function EditorOnboardingWorkspace({
  repository,
}: {
  repository: AdminEditorRepository;
}) {
  const [form, setForm] = useState<EditorOnboardingInput>(emptyForm);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [requests, setRequests] = useState<AdminEditorRequest[]>([]);
  const [requestsLoading, setRequestsLoading] = useState(true);
  const [requestBusy, setRequestBusy] = useState<string | null>(null);
  const [reviewNotes, setReviewNotes] = useState<Record<string, string>>({});

  useEffect(() => {
    let current = true;
    setRequestsLoading(true);
    void repository.listRequests().then((next) => {
      if (current) setRequests(next);
    }).catch(() => {
      if (current) setError("Editor requests could not be loaded.");
    }).finally(() => {
      if (current) setRequestsLoading(false);
    });
    return () => { current = false; };
  }, [repository]);

  const update = <Key extends keyof EditorOnboardingInput>(
    key: Key,
    value: EditorOnboardingInput[Key],
  ) => setForm((current) => ({ ...current, [key]: value }));

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const validation = validationMessage(form);
    if (validation) {
      setError(validation);
      setSuccess(null);
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const created = await repository.invite({
        ...form,
        activeTo: form.activeTo || null,
      });
      setSuccess(
        `Invitation sent to ${created.email}. ${created.nameEn || created.nameKo} can open My curation after setting a password.`,
      );
      setForm(emptyForm);
    } catch (caught) {
      setError(
        caught instanceof Error
          ? caught.message
          : "The editor could not be invited.",
      );
    } finally {
      setBusy(false);
    }
  };

  const review = async (request: AdminEditorRequest, approve: boolean) => {
    const notes = reviewNotes[request.id]?.trim() ?? "";
    if (!approve && !notes) {
      setError("Add a reason before rejecting an editor request.");
      return;
    }
    setRequestBusy(request.id);
    setError(null);
    try {
      await repository.reviewRequest(request.id, approve, notes);
      setRequests((current) => current.filter((item) => item.id !== request.id));
      setSuccess(`${request.editorName}'s ${request.kind} request was ${approve ? "approved" : "rejected"}.`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The editor request could not be reviewed.");
    } finally {
      setRequestBusy(null);
    }
  };

  return (
    <main className="editor-onboarding-workspace">
      <header className="editor-onboarding-header">
        <p className="workspace-kicker">ACCESS / EDITOR</p>
        <h1>Editors</h1>
        <p>
          Invite an editor and create the profile linked to their restricted
          My curation workspace. Only administrators can perform this action.
        </p>
      </header>

      <form className="editor-onboarding-form" onSubmit={submit} noValidate>
        <section aria-labelledby="account-section-title">
          <div className="editor-form-section-heading">
            <span>01</span>
            <h2 id="account-section-title">Account</h2>
          </div>
          <div className="editor-form-grid">
            <label className="field">
              <span>Invitation email *</span>
              <input
                aria-label="Invitation email"
                type="email"
                value={form.email}
                onChange={(event) => update("email", event.target.value)}
                autoComplete="email"
              />
            </label>
            <label className="field">
              <span>Editor slug *</span>
              <input
                aria-label="Editor slug"
                value={form.editorId}
                onChange={(event) => update("editorId", event.target.value)}
                placeholder="mina-kim"
                spellCheck={false}
              />
              <small className="field-help">Lowercase URL identifier</small>
            </label>
          </div>
        </section>

        <section aria-labelledby="profile-section-title">
          <div className="editor-form-section-heading">
            <span>02</span>
            <h2 id="profile-section-title">Editor profile</h2>
          </div>
          <div className="editor-form-grid">
            <label className="field"><span>Name (Korean) *</span><input aria-label="Name (Korean)" value={form.nameKo} onChange={(event) => update("nameKo", event.target.value)} /></label>
            <label className="field"><span>Name (English)</span><input value={form.nameEn} onChange={(event) => update("nameEn", event.target.value)} /></label>
            <label className="field"><span>Title (Korean) *</span><input aria-label="Title (Korean)" value={form.titleKo} onChange={(event) => update("titleKo", event.target.value)} /></label>
            <label className="field"><span>Title (English)</span><input value={form.titleEn} onChange={(event) => update("titleEn", event.target.value)} /></label>
            <label className="field editor-form-wide"><span>Bio (Korean) *</span><textarea aria-label="Bio (Korean)" value={form.bioKo} onChange={(event) => update("bioKo", event.target.value)} /></label>
            <label className="field editor-form-wide"><span>Bio (English)</span><textarea value={form.bioEn} onChange={(event) => update("bioEn", event.target.value)} /></label>
          </div>
        </section>

        <section aria-labelledby="curation-section-title">
          <div className="editor-form-section-heading">
            <span>03</span>
            <h2 id="curation-section-title">Curation</h2>
          </div>
          <div className="editor-form-grid">
            <p className="editor-form-explanation editor-form-wide">
              This statement introduces the editor's exhibition collection. It is separate from their personal biography.
            </p>
            <label className="field editor-form-wide"><span>Curatorial statement (Korean) *</span><textarea aria-label="Curatorial statement (Korean)" value={form.curationDescriptionKo} onChange={(event) => update("curationDescriptionKo", event.target.value)} /></label>
            <label className="field editor-form-wide"><span>Curatorial statement (English)</span><textarea aria-label="Curatorial statement (English)" value={form.curationDescriptionEn} onChange={(event) => update("curationDescriptionEn", event.target.value)} /></label>
          </div>
        </section>

        <section aria-labelledby="schedule-section-title">
          <div className="editor-form-section-heading">
            <span>04</span>
            <h2 id="schedule-section-title">Schedule</h2>
          </div>
          <div className="editor-form-grid">
            <label className="field"><span>Active from *</span><input aria-label="Active from" type="date" value={form.activeFrom} onChange={(event) => update("activeFrom", event.target.value)} /></label>
            <label className="field"><span>Active to</span><input type="date" value={form.activeTo ?? ""} onChange={(event) => update("activeTo", event.target.value || null)} /></label>
            <label className="editor-active-toggle editor-form-wide">
              <input type="checkbox" checked={form.isActive} onChange={(event) => update("isActive", event.target.checked)} />
              <span><strong>Publish profile immediately</strong><small>Leave off to prepare the editor privately before launch.</small></span>
            </label>
          </div>
        </section>

        {error && <div className="inline-notice" role="alert">{error}</div>}
        {success && <div className="inline-notice editor-success" role="status">{success}</div>}
        <footer className="editor-form-footer">
          <p>The invitation grants My curation and own-bio proposals only. It does not grant staff administration access.</p>
          <button className="accent-button" type="submit" disabled={busy}>
            {busy ? "Inviting…" : "Invite editor"}
          </button>
        </footer>
      </form>

      <section className="editor-request-queue" aria-labelledby="editor-requests-title">
        <header>
          <div>
            <p className="workspace-kicker">REVIEW QUEUE</p>
            <h2 id="editor-requests-title">Editor requests</h2>
          </div>
          <span>{requests.length} pending</span>
        </header>
        {requestsLoading ? <div className="table-state"><p>Loading editor requests…</p></div> : requests.length === 0 ? (
          <div className="table-state"><p>No editor profile or curation requests need review.</p></div>
        ) : (
          <div className="editor-request-list">
            {requests.map((request) => {
              const bioKo = typeof request.payload.bio_ko === "string" ? request.payload.bio_ko : "";
              const bioEn = typeof request.payload.bio_en === "string" ? request.payload.bio_en : "";
              const curationDescriptionKo = typeof request.payload.curation_description_ko === "string" ? request.payload.curation_description_ko : "";
              const curationDescriptionEn = typeof request.payload.curation_description_en === "string" ? request.payload.curation_description_en : "";
              const changes = curationRequestChanges(request.payload);
              return (
                <article className="editor-request-card" key={request.id}>
                  <div className="editor-request-meta">
                    <span>{request.kind === "profile" ? "BIO UPDATE" : "CURATION"}</span>
                    <time dateTime={request.createdAt}>{new Date(request.createdAt).toLocaleDateString()}</time>
                  </div>
                  <h3>{request.editorName}</h3>
                  {request.kind === "profile" ? (
                    <div className="editor-request-bio">
                      <span>PROPOSED BIO</span>
                      <p>{bioKo}</p>
                      {bioEn ? <p className="muted">{bioEn}</p> : null}
                    </div>
                  ) : (
                    <>
                      {curationDescriptionKo ? (
                        <div className="editor-request-bio editor-request-statement">
                          <span>Curatorial statement</span>
                          <p>{curationDescriptionKo}</p>
                          {curationDescriptionEn ? <p className="muted">{curationDescriptionEn}</p> : null}
                        </div>
                      ) : null}
                      <p>{changes.length} exhibition change{changes.length === 1 ? "" : "s"} ready to publish.</p>
                      <ul className="editor-request-changes">
                        {changes.map((change) => (
                          <li key={change.id}>
                            <span className="editor-request-decision">{change.selected ? "Add to curation" : "Remove from curation"}</span>
                            <strong>{change.nameKo || change.nameEn || change.id}</strong>
                            {change.nameEn && change.nameEn !== change.nameKo ? <small>{change.nameEn}</small> : null}
                            {change.venueNameKo ? <small>{change.venueNameKo}</small> : null}
                          </li>
                        ))}
                      </ul>
                    </>
                  )}
                  <label className="field"><span>Reason if rejected</span><textarea value={reviewNotes[request.id] ?? ""} onChange={(event) => setReviewNotes((current) => ({ ...current, [request.id]: event.target.value }))} /></label>
                  <div className="editor-request-actions">
                    <button className="outlined-button" type="button" disabled={requestBusy !== null || !(reviewNotes[request.id]?.trim())} onClick={() => void review(request, false)}>Reject</button>
                    <button className="black-button" type="button" aria-label={`Approve ${request.editorName} ${request.kind} request`} disabled={requestBusy !== null} onClick={() => void review(request, true)}>{requestBusy === request.id ? "Reviewing…" : "Approve"}</button>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>
    </main>
  );
}
