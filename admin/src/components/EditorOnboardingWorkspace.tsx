import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import type {
  AdminEditorUpdateInput,
  AdminManagedEditor,
  AdminEditorRepository,
  AdminEditorRequest,
  EditorOnboardingInput,
} from "../repositories/AdminEditorRepository";
import { EditorRevisionConflictError } from "../repositories/AdminEditorRepository";
import { DialogFrame } from "./Dialogs";

const emptyForm: EditorOnboardingInput = {
  email: "",
};

type EditorValidationField = "email";

interface EditorValidationIssue {
  field: EditorValidationField;
  message: string;
}

function validationIssue(input: EditorOnboardingInput): EditorValidationIssue | null {
  if (!input.email.trim()) {
    return { field: "email", message: "Invitation email is required." };
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(input.email.trim())) {
    return { field: "email", message: "Enter a valid invitation email." };
  }
  return null;
}

function editorUpdateValidationMessage(
  input: AdminEditorUpdateInput,
): string | null {
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

function editorDisplayName(editor: AdminManagedEditor): string {
  return editor.nameEn || editor.nameKo;
}

function editorUpdateInput(editor: AdminManagedEditor): AdminEditorUpdateInput {
  return {
    nameKo: editor.nameKo,
    nameEn: editor.nameEn,
    titleKo: editor.titleKo,
    titleEn: editor.titleEn,
    bioKo: editor.bioKo,
    bioEn: editor.bioEn,
    curationDescriptionKo: editor.curationDescriptionKo,
    curationDescriptionEn: editor.curationDescriptionEn,
    isActive: editor.isActive,
    activeFrom: editor.activeFrom,
    activeTo: editor.activeTo,
  };
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
  const [validationField, setValidationField] =
    useState<EditorValidationField | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [requests, setRequests] = useState<AdminEditorRequest[]>([]);
  const [requestsLoading, setRequestsLoading] = useState(true);
  const [requestBusy, setRequestBusy] = useState<string | null>(null);
  const [reviewNotes, setReviewNotes] = useState<Record<string, string>>({});
  const [editors, setEditors] = useState<AdminManagedEditor[]>([]);
  const [editorsLoading, setEditorsLoading] = useState(true);
  const [editorBusy, setEditorBusy] = useState<string | null>(null);
  const [managementError, setManagementError] = useState<string | null>(null);
  const [managementSuccess, setManagementSuccess] = useState<string | null>(
    null,
  );
  const [editingEditor, setEditingEditor] = useState<AdminManagedEditor | null>(
    null,
  );
  const [editForm, setEditForm] = useState<AdminEditorUpdateInput | null>(null);
  const [confirmDeactivate, setConfirmDeactivate] =
    useState<AdminManagedEditor | null>(null);
  const validationFieldRefs = useRef<
    Partial<Record<EditorValidationField, HTMLInputElement | HTMLTextAreaElement>>
  >({});

  const loadEditors = useCallback(async () => {
    setEditorsLoading(true);
    try {
      const next = await repository.listEditors();
      setEditors(next);
      setManagementError(null);
    } catch (caught) {
      setManagementError(
        caught instanceof Error ? caught.message : "Editors could not be loaded.",
      );
    } finally {
      setEditorsLoading(false);
    }
  }, [repository]);

  useEffect(() => {
    void loadEditors();
  }, [loadEditors]);

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
  ) => {
    setForm((current) => ({ ...current, [key]: value }));
    if (validationField === key) {
      setValidationField(null);
      setError(null);
    }
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const issue = validationIssue(form);
    if (issue) {
      setError(issue.message);
      setValidationField(issue.field);
      setSuccess(null);
      validationFieldRefs.current[issue.field]?.focus();
      return;
    }
    setBusy(true);
    setError(null);
    setValidationField(null);
    setSuccess(null);
    try {
      const created = await repository.invite({
        email: form.email.trim(),
      });
      setSuccess(
        `Invitation sent to ${created.email}. They can set a password and complete their profile in gallr editor.`,
      );
      setForm(emptyForm);
      await loadEditors();
    } catch (caught) {
      setValidationField(null);
      setError(
        caught instanceof Error
          ? caught.message
          : "The editor could not be invited.",
      );
    } finally {
      setBusy(false);
    }
  };

  const fieldError = (field: EditorValidationField) =>
    validationField === field && error ? (
      <small
        className="field-error editor-validation-error"
        id={`editor-${field}-error`}
        role="alert"
      >
        ! {error}
      </small>
    ) : null;

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

  const replaceEditor = (updated: AdminManagedEditor) => {
    setEditors((current) => current.map((editor) =>
      editor.editorId === updated.editorId ? updated : editor
    ));
  };

  const startEditing = (editor: AdminManagedEditor) => {
    setEditingEditor(editor);
    setEditForm(editorUpdateInput(editor));
    setConfirmDeactivate(null);
    setManagementError(null);
    setManagementSuccess(null);
  };

  const updateEditField = <Key extends keyof AdminEditorUpdateInput>(
    key: Key,
    value: AdminEditorUpdateInput[Key],
  ) => setEditForm((current) => current ? { ...current, [key]: value } : current);

  const saveEditor = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editingEditor || !editForm) return;
    const validation = editorUpdateValidationMessage(editForm);
    if (validation) {
      setManagementError(validation);
      setManagementSuccess(null);
      return;
    }
    setEditorBusy(editingEditor.editorId);
    setManagementError(null);
    setManagementSuccess(null);
    try {
      const updated = await repository.updateEditor(
        editingEditor.editorId,
        editingEditor.revision,
        { ...editForm, activeTo: editForm.activeTo || null },
      );
      replaceEditor(updated);
      setEditingEditor(null);
      setEditForm(null);
      setManagementSuccess(`${editorDisplayName(updated)} was updated.`);
    } catch (caught) {
      if (caught instanceof EditorRevisionConflictError) {
        setEditingEditor(null);
        setEditForm(null);
        await loadEditors();
        setManagementError(
          `A newer editor revision (${caught.serverRevision}) exists. The directory was reloaded; review it before editing again.`,
        );
      } else {
        setManagementError(
          caught instanceof Error ? caught.message : "The editor could not be updated.",
        );
      }
    } finally {
      setEditorBusy(null);
    }
  };

  const changeAccess = async (
    editor: AdminManagedEditor,
    active: boolean,
  ) => {
    setEditorBusy(editor.editorId);
    setManagementError(null);
    setManagementSuccess(null);
    try {
      const updated = await repository.setAccess(
        editor.editorId,
        editor.revision,
        active,
      );
      replaceEditor(updated);
      setConfirmDeactivate(null);
      setManagementSuccess(
        active
          ? `${editorDisplayName(updated)} access was restored. The public profile remains unpublished until you enable it.`
          : `${editorDisplayName(updated)} was deactivated. Their account and history were preserved.`,
      );
    } catch (caught) {
      if (caught instanceof EditorRevisionConflictError) {
        setConfirmDeactivate(null);
        await loadEditors();
        setManagementError(
          `A newer editor revision (${caught.serverRevision}) exists. The directory was reloaded.`,
        );
      } else {
        setManagementError(
          caught instanceof Error
            ? caught.message
            : active
              ? "Editor access could not be restored."
              : "Editor access could not be deactivated.",
        );
      }
    } finally {
      setEditorBusy(null);
    }
  };

  return (
    <main className="editor-onboarding-workspace">
      <header className="editor-onboarding-header">
        <p className="workspace-kicker">ACCESS / EDITOR</p>
        <h1>Editors</h1>
        <p>
          Send an editor invitation. The editor creates their own profile after
          setting a password; Admin controls publication separately.
        </p>
      </header>

      <section className="managed-editors" aria-labelledby="managed-editors-title">
        <header>
          <div>
            <p className="workspace-kicker">DIRECTORY</p>
            <h2 id="managed-editors-title">Manage editors</h2>
          </div>
          <span>{editors.length} editor{editors.length === 1 ? "" : "s"}</span>
        </header>

        {managementError && <div className="inline-notice managed-editor-notice" role="alert">! {managementError}</div>}
        {managementSuccess && <div className="inline-notice editor-success managed-editor-notice" role="status">{managementSuccess}</div>}

        {editingEditor && editForm ? (
          <form className="managed-editor-form" onSubmit={saveEditor} noValidate>
            <header>
              <div>
                <p className="workspace-kicker">EDIT PROFILE</p>
                <h3>{editorDisplayName(editingEditor)}</h3>
              </div>
              <button className="text-button" type="button" onClick={() => {
                setEditingEditor(null);
                setEditForm(null);
                setManagementError(null);
              }}>Cancel</button>
            </header>
            <div className="managed-editor-identity">
              <span><strong>Slug</strong>{editingEditor.editorId}</span>
              <span><strong>Account</strong>{editingEditor.email ?? "No linked account"}</span>
            </div>
            <div className="editor-form-grid">
              <label className="field"><span>Name (Korean) *</span><input aria-label="Edit name (Korean)" value={editForm.nameKo} onChange={(event) => updateEditField("nameKo", event.target.value)} /></label>
              <label className="field"><span>Name (English)</span><input aria-label="Edit name (English)" value={editForm.nameEn} onChange={(event) => updateEditField("nameEn", event.target.value)} /></label>
              <label className="field"><span>Title (Korean) *</span><input aria-label="Edit title (Korean)" value={editForm.titleKo} onChange={(event) => updateEditField("titleKo", event.target.value)} /></label>
              <label className="field"><span>Title (English)</span><input aria-label="Edit title (English)" value={editForm.titleEn} onChange={(event) => updateEditField("titleEn", event.target.value)} /></label>
              <label className="field editor-form-wide"><span>Bio (Korean) *</span><textarea aria-label="Edit bio (Korean)" value={editForm.bioKo} onChange={(event) => updateEditField("bioKo", event.target.value)} /></label>
              <label className="field editor-form-wide"><span>Bio (English)</span><textarea aria-label="Edit bio (English)" value={editForm.bioEn} onChange={(event) => updateEditField("bioEn", event.target.value)} /></label>
              <label className="field editor-form-wide"><span>Curatorial statement (Korean) *</span><textarea aria-label="Edit curatorial statement (Korean)" value={editForm.curationDescriptionKo} onChange={(event) => updateEditField("curationDescriptionKo", event.target.value)} /></label>
              <label className="field editor-form-wide"><span>Curatorial statement (English)</span><textarea aria-label="Edit curatorial statement (English)" value={editForm.curationDescriptionEn} onChange={(event) => updateEditField("curationDescriptionEn", event.target.value)} /></label>
              <label className="field"><span>Active from *</span><input aria-label="Edit active from" type="date" value={editForm.activeFrom} onChange={(event) => updateEditField("activeFrom", event.target.value)} /></label>
              <label className="field"><span>Active to</span><input aria-label="Edit active to" type="date" value={editForm.activeTo ?? ""} onChange={(event) => updateEditField("activeTo", event.target.value || null)} /></label>
              <label className="editor-active-toggle editor-form-wide">
                <input aria-label="Publish editor profile" type="checkbox" checked={editForm.isActive} onChange={(event) => updateEditField("isActive", event.target.checked)} />
                <span><strong>Published profile</strong><small>Controls public visibility. Workspace access is managed separately.</small></span>
              </label>
            </div>
            <footer>
              <p>Slug and account email are fixed identity fields.</p>
              <button className="black-button" type="submit" disabled={editorBusy !== null}>{editorBusy ? "Saving…" : "Save editor"}</button>
            </footer>
          </form>
        ) : null}

        {editingEditor ? null : editorsLoading ? (
          <div className="table-state"><p>Loading editors…</p></div>
        ) : editors.length === 0 ? (
          <div className="table-state"><p>No editors have been created yet. Invite the first editor below.</p></div>
        ) : (
          <div className="managed-editor-list">
            {editors.map((editor) => {
              const displayName = editorDisplayName(editor);
              return (
                <article className="managed-editor-card" key={editor.editorId}>
                  <div className="managed-editor-card-heading">
                    <div>
                      <span>{editor.editorId}</span>
                      <h3>{displayName}</h3>
                      {editor.nameEn && editor.nameKo !== editor.nameEn ? <p>{editor.nameKo}</p> : null}
                    </div>
                    <span>REV {editor.revision}</span>
                  </div>
                  <p className="managed-editor-title">{editor.titleEn || editor.titleKo}</p>
                  {editor.email ? <p className="managed-editor-email">{editor.email}</p> : null}
                  <div className="managed-editor-states">
                    <span>{editor.isActive ? "Published profile" : "Unpublished profile"}</span>
                    <span>{editor.hasAccess ? (editor.accessActive ? "Workspace active" : "Access removed") : "No linked workspace account"}</span>
                    <span>{editor.activeFrom}{editor.activeTo ? ` — ${editor.activeTo}` : " — open ended"}</span>
                  </div>
                  <div className="managed-editor-actions">
                    <button className="outlined-button" type="button" disabled={editorBusy !== null} aria-label={`Edit ${displayName}`} onClick={() => startEditing(editor)}>Edit</button>
                    {editor.hasAccess ? editor.accessActive ? (
                      <button className="text-button" type="button" disabled={editorBusy !== null} aria-label={`Deactivate ${displayName}`} onClick={() => setConfirmDeactivate(editor)}>Deactivate</button>
                    ) : (
                      <button className="black-button" type="button" disabled={editorBusy !== null} aria-label={`Restore ${displayName} access`} onClick={() => void changeAccess(editor, true)}>{editorBusy === editor.editorId ? "Restoring…" : "Restore access"}</button>
                    ) : null}
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>

      {confirmDeactivate ? (
        <DialogFrame
          title={`Deactivate ${editorDisplayName(confirmDeactivate)}?`}
          role="alertdialog"
          onClose={() => setConfirmDeactivate(null)}
          footer={
            <>
              <button className="outlined-button" type="button" disabled={editorBusy !== null} onClick={() => setConfirmDeactivate(null)}>Cancel</button>
              <button className="black-button" type="button" disabled={editorBusy !== null} aria-label={`Confirm deactivate ${editorDisplayName(confirmDeactivate)}`} onClick={() => void changeAccess(confirmDeactivate, false)}>{editorBusy ? "Deactivating…" : "Deactivate editor"}</button>
            </>
          }
        >
          <p>The editor will lose workspace access and their public profile will be hidden. Their account, exhibition attribution, requests, and audit history are preserved.</p>
        </DialogFrame>
      ) : null}

      <form className="editor-onboarding-form" onSubmit={submit} noValidate>
        <section aria-labelledby="account-section-title">
          <div className="editor-form-section-heading">
            <span>01</span>
            <h2 id="account-section-title">Invite editor</h2>
          </div>
          <div className="editor-form-grid">
            <label className="field editor-form-wide">
              <span>Invitation email *</span>
              <input
                ref={(element) => { validationFieldRefs.current.email = element ?? undefined; }}
                aria-label="Invitation email"
                aria-invalid={validationField === "email"}
                aria-describedby={validationField === "email" ? "editor-email-error" : undefined}
                type="email"
                value={form.email}
                onChange={(event) => update("email", event.target.value)}
                autoComplete="email"
              />
              {fieldError("email")}
            </label>
            <p className="editor-form-explanation editor-form-wide">
              They will receive a secure link to set a password and create
              their own profile in gallr editor.
            </p>
          </div>
        </section>

        {error && !validationField && <div className="inline-notice" role="alert">! {error}</div>}
        {success && <div className="inline-notice editor-success" role="status">{success}</div>}
        <footer className="editor-form-footer">
          <p>The invitation grants onboarding access only. It never grants staff administration access.</p>
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
