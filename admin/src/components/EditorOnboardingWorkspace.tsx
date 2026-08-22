import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import type {
  AdminEditorUpdateInput,
  AdminManagedEditor,
  AdminEditorRepository,
  AdminEditorRequest,
  EditorOnboardingInput,
} from "../repositories/AdminEditorRepository";
import { EditorRevisionConflictError } from "../repositories/AdminEditorRepository";
import { useI18n, type MessageKey } from "../i18n";
import { DialogFrame } from "./Dialogs";

type Translate = ReturnType<typeof useI18n>["t"];
type LocalizedText = ReturnType<typeof useI18n>["localized"];
type MessageParameters = Record<string, string | number>;

type UiNotice = {
  kind: "interface";
  key: MessageKey;
  parameters?: MessageParameters;
};

function interfaceNotice(
  key: MessageKey,
  parameters?: MessageParameters,
): UiNotice {
  return { kind: "interface", key, parameters };
}

function noticeText(notice: UiNotice, t: Translate): string {
  return t(notice.key, notice.parameters);
}

const emptyForm: EditorOnboardingInput = {
  email: "",
};

type EditorValidationField = "email";

interface EditorValidationIssue {
  field: EditorValidationField;
  message: UiNotice;
}

function validationIssue(input: EditorOnboardingInput): EditorValidationIssue | null {
  if (!input.email.trim()) {
    return {
      field: "email",
      message: interfaceNotice("editorAdmin.validation.emailRequired"),
    };
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(input.email.trim())) {
    return {
      field: "email",
      message: interfaceNotice("editorAdmin.validation.emailInvalid"),
    };
  }
  return null;
}

function editorUpdateValidationMessage(
  input: AdminEditorUpdateInput,
): UiNotice | null {
  if (!input.nameKo.trim() || !input.titleKo.trim() || !input.bioKo.trim()) {
    return interfaceNotice("editorAdmin.validation.requiredKoreanProfile");
  }
  if (!input.curationDescriptionKo.trim()) {
    return interfaceNotice("editorAdmin.validation.requiredKoreanStatement");
  }
  if (!input.activeFrom) {
    return interfaceNotice("editorAdmin.validation.activeFromRequired");
  }
  if (input.activeTo && input.activeTo < input.activeFrom) {
    return interfaceNotice("editorAdmin.validation.activeToBeforeFrom");
  }
  return null;
}

function editorDisplayName(
  editor: AdminManagedEditor,
  localized: LocalizedText,
): string {
  return localized(editor.nameKo, editor.nameEn, editor.editorId);
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
  venueNameEn: string;
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
      venueNameEn: typeof row.venue_name_en === "string" ? row.venue_name_en : "",
      selected: row.selected,
    }];
  });
}

export function EditorOnboardingWorkspace({
  repository,
}: {
  repository: AdminEditorRepository;
}) {
  const { locale, t, formatDate, formatNumber, localized } = useI18n();
  const [form, setForm] = useState<EditorOnboardingInput>(emptyForm);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<UiNotice | null>(null);
  const [validationField, setValidationField] =
    useState<EditorValidationField | null>(null);
  const [success, setSuccess] = useState<UiNotice | null>(null);
  const [requests, setRequests] = useState<AdminEditorRequest[]>([]);
  const [requestsLoading, setRequestsLoading] = useState(true);
  const [requestBusy, setRequestBusy] = useState<string | null>(null);
  const [reviewNotes, setReviewNotes] = useState<Record<string, string>>({});
  const [editors, setEditors] = useState<AdminManagedEditor[]>([]);
  const [editorsLoading, setEditorsLoading] = useState(true);
  const [editorBusy, setEditorBusy] = useState<string | null>(null);
  const [managementError, setManagementError] = useState<UiNotice | null>(null);
  const [managementSuccess, setManagementSuccess] = useState<UiNotice | null>(
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
    } catch {
      setManagementError(interfaceNotice("editorAdmin.error.loadEditors"));
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
      if (current) setError(interfaceNotice("editorAdmin.error.loadRequests"));
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
        interfaceNotice("editorAdmin.success.invited", { email: created.email }),
      );
      setForm(emptyForm);
      await loadEditors();
    } catch {
      setValidationField(null);
      setError(interfaceNotice("editorAdmin.error.invite"));
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
        ! {noticeText(error, t)}
      </small>
    ) : null;

  const requestEditorName = (request: AdminEditorRequest) => {
    const editor = editors.find((candidate) => candidate.editorId === request.editorId);
    return editor
      ? localized(editor.nameKo, editor.nameEn, request.editorName)
      : request.editorName;
  };

  const review = async (request: AdminEditorRequest, approve: boolean) => {
    const notes = reviewNotes[request.id]?.trim() ?? "";
    if (!approve && !notes) {
      setError(interfaceNotice("editorAdmin.error.rejectReason"));
      return;
    }
    setRequestBusy(request.id);
    setError(null);
    try {
      await repository.reviewRequest(request.id, approve, notes);
      setRequests((current) => current.filter((item) => item.id !== request.id));
      setSuccess(interfaceNotice(
        request.kind === "profile"
          ? approve
            ? "editorAdmin.success.profileApproved"
            : "editorAdmin.success.profileRejected"
          : approve
            ? "editorAdmin.success.curationApproved"
            : "editorAdmin.success.curationRejected",
        { name: requestEditorName(request) },
      ));
    } catch {
      setError(interfaceNotice("editorAdmin.error.review"));
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
      setManagementSuccess(interfaceNotice("editorAdmin.success.updated", {
        name: editorDisplayName(updated, localized),
      }));
    } catch (caught) {
      if (caught instanceof EditorRevisionConflictError) {
        setEditingEditor(null);
        setEditForm(null);
        await loadEditors();
        setManagementError(interfaceNotice("editorAdmin.conflict.edit", {
          revision: caught.serverRevision,
        }));
      } else {
        setManagementError(interfaceNotice("editorAdmin.error.update"));
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
          ? interfaceNotice("editorAdmin.success.accessRestored", {
            name: editorDisplayName(updated, localized),
          })
          : interfaceNotice("editorAdmin.success.deactivated", {
            name: editorDisplayName(updated, localized),
          }),
      );
    } catch (caught) {
      if (caught instanceof EditorRevisionConflictError) {
        setConfirmDeactivate(null);
        await loadEditors();
        setManagementError(interfaceNotice("editorAdmin.conflict.access", {
          revision: caught.serverRevision,
        }));
      } else {
        setManagementError(active
          ? interfaceNotice("editorAdmin.error.restore")
          : interfaceNotice("editorAdmin.error.deactivate"));
      }
    } finally {
      setEditorBusy(null);
    }
  };

  return (
    <main className="editor-onboarding-workspace">
      <header className="editor-onboarding-header">
        <p className="workspace-kicker">{t("editorAdmin.kicker.access")}</p>
        <h1>{t("editorAdmin.title")}</h1>
        <p>{t("editorAdmin.introduction")}</p>
      </header>

      <section className="managed-editors" aria-labelledby="managed-editors-title">
        <header>
          <div>
            <p className="workspace-kicker">{t("editorAdmin.kicker.directory")}</p>
            <h2 id="managed-editors-title">{t("editorAdmin.manageTitle")}</h2>
          </div>
          <span>{t(editors.length === 1
            ? "editorAdmin.count.one"
            : "editorAdmin.count.other", { count: formatNumber(editors.length) })}</span>
        </header>

        {managementError && <div className="inline-notice managed-editor-notice" role="alert">! {noticeText(managementError, t)}</div>}
        {managementSuccess && <div className="inline-notice editor-success managed-editor-notice" role="status">{noticeText(managementSuccess, t)}</div>}

        {editingEditor && editForm ? (
          <form className="managed-editor-form" onSubmit={saveEditor} noValidate>
            <header>
              <div>
                <p className="workspace-kicker">{t("editorAdmin.kicker.editProfile")}</p>
                <h3>{editorDisplayName(editingEditor, localized)}</h3>
              </div>
              <button className="text-button" type="button" onClick={() => {
                setEditingEditor(null);
                setEditForm(null);
                setManagementError(null);
              }}>{t("editorAdmin.actions.cancel")}</button>
            </header>
            <div className="managed-editor-identity">
              <span><strong>{t("editorAdmin.identity.slug")}</strong>{editingEditor.editorId}</span>
              <span><strong>{t("editorAdmin.identity.account")}</strong>{editingEditor.email ?? t("editorAdmin.identity.noLinkedAccount")}</span>
            </div>
            <div className="editor-form-grid">
              <label className="field"><span>{t("editorAdmin.fields.nameKo")}</span><input aria-label={t("editorAdmin.aria.editNameKo")} value={editForm.nameKo} onChange={(event) => updateEditField("nameKo", event.target.value)} /></label>
              <label className="field"><span>{t("editorAdmin.fields.nameEn")}</span><input aria-label={t("editorAdmin.aria.editNameEn")} value={editForm.nameEn} onChange={(event) => updateEditField("nameEn", event.target.value)} /></label>
              <label className="field"><span>{t("editorAdmin.fields.titleKo")}</span><input aria-label={t("editorAdmin.aria.editTitleKo")} value={editForm.titleKo} onChange={(event) => updateEditField("titleKo", event.target.value)} /></label>
              <label className="field"><span>{t("editorAdmin.fields.titleEn")}</span><input aria-label={t("editorAdmin.aria.editTitleEn")} value={editForm.titleEn} onChange={(event) => updateEditField("titleEn", event.target.value)} /></label>
              <label className="field editor-form-wide"><span>{t("editorAdmin.fields.bioKo")}</span><textarea aria-label={t("editorAdmin.aria.editBioKo")} value={editForm.bioKo} onChange={(event) => updateEditField("bioKo", event.target.value)} /></label>
              <label className="field editor-form-wide"><span>{t("editorAdmin.fields.bioEn")}</span><textarea aria-label={t("editorAdmin.aria.editBioEn")} value={editForm.bioEn} onChange={(event) => updateEditField("bioEn", event.target.value)} /></label>
              <label className="field editor-form-wide"><span>{t("editorAdmin.fields.statementKo")}</span><textarea aria-label={t("editorAdmin.aria.editStatementKo")} value={editForm.curationDescriptionKo} onChange={(event) => updateEditField("curationDescriptionKo", event.target.value)} /></label>
              <label className="field editor-form-wide"><span>{t("editorAdmin.fields.statementEn")}</span><textarea aria-label={t("editorAdmin.aria.editStatementEn")} value={editForm.curationDescriptionEn} onChange={(event) => updateEditField("curationDescriptionEn", event.target.value)} /></label>
              <label className="field"><span>{t("editorAdmin.fields.activeFrom")}</span><input aria-label={t("editorAdmin.aria.editActiveFrom")} type="date" value={editForm.activeFrom} onChange={(event) => updateEditField("activeFrom", event.target.value)} /></label>
              <label className="field"><span>{t("editorAdmin.fields.activeTo")}</span><input aria-label={t("editorAdmin.aria.editActiveTo")} type="date" value={editForm.activeTo ?? ""} onChange={(event) => updateEditField("activeTo", event.target.value || null)} /></label>
              <label className="editor-active-toggle editor-form-wide">
                <input aria-label={t("editorAdmin.aria.publishProfile")} type="checkbox" checked={editForm.isActive} onChange={(event) => updateEditField("isActive", event.target.checked)} />
                <span><strong>{t("editorAdmin.profile.published")}</strong><small>{t("editorAdmin.profile.visibilityHelp")}</small></span>
              </label>
            </div>
            <footer>
              <p>{t("editorAdmin.identity.fixedHelp")}</p>
              <button className="black-button" type="submit" disabled={editorBusy !== null}>{editorBusy ? t("editorAdmin.actions.saving") : t("editorAdmin.actions.save")}</button>
            </footer>
          </form>
        ) : null}

        {editingEditor ? null : editorsLoading ? (
          <div className="table-state"><p>{t("editorAdmin.loading.editors")}</p></div>
        ) : editors.length === 0 ? (
          <div className="table-state"><p>{t("editorAdmin.empty.editors")}</p></div>
        ) : (
          <div className="managed-editor-list">
            {editors.map((editor) => {
              const displayName = editorDisplayName(editor, localized);
              const alternateName = (locale === "ko" ? editor.nameEn : editor.nameKo).trim();
              return (
                <article className="managed-editor-card" key={editor.editorId}>
                  <div className="managed-editor-card-heading">
                    <div>
                      <span>{editor.editorId}</span>
                      <h3>{displayName}</h3>
                      {alternateName && alternateName !== displayName ? <p>{alternateName}</p> : null}
                    </div>
                    <span>{t("editorAdmin.revision", { revision: formatNumber(editor.revision) })}</span>
                  </div>
                  <p className="managed-editor-title">{localized(editor.titleKo, editor.titleEn)}</p>
                  {editor.email ? <p className="managed-editor-email">{editor.email}</p> : null}
                  <div className="managed-editor-states">
                    <span>{editor.isActive ? t("editorAdmin.profile.published") : t("editorAdmin.profile.unpublished")}</span>
                    <span>{editor.hasAccess
                      ? (editor.accessActive ? t("editorAdmin.access.active") : t("editorAdmin.access.removed"))
                      : t("editorAdmin.access.none")}</span>
                    <span>{formatDate(editor.activeFrom)} — {editor.activeTo
                      ? formatDate(editor.activeTo)
                      : t("editorAdmin.schedule.openEnded")}</span>
                  </div>
                  <div className="managed-editor-actions">
                    <button className="outlined-button" type="button" disabled={editorBusy !== null} aria-label={t("editorAdmin.aria.edit", { name: displayName })} onClick={() => startEditing(editor)}>{t("editorAdmin.actions.edit")}</button>
                    {editor.hasAccess ? editor.accessActive ? (
                      <button className="text-button" type="button" disabled={editorBusy !== null} aria-label={t("editorAdmin.aria.deactivate", { name: displayName })} onClick={() => setConfirmDeactivate(editor)}>{t("editorAdmin.actions.deactivate")}</button>
                    ) : (
                      <button className="black-button" type="button" disabled={editorBusy !== null} aria-label={t("editorAdmin.aria.restore", { name: displayName })} onClick={() => void changeAccess(editor, true)}>{editorBusy === editor.editorId ? t("editorAdmin.actions.restoring") : t("editorAdmin.actions.restore")}</button>
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
          title={t("editorAdmin.dialog.deactivateTitle", {
            name: editorDisplayName(confirmDeactivate, localized),
          })}
          role="alertdialog"
          onClose={() => setConfirmDeactivate(null)}
          footer={
            <>
              <button className="outlined-button" type="button" disabled={editorBusy !== null} onClick={() => setConfirmDeactivate(null)}>{t("editorAdmin.actions.cancel")}</button>
              <button className="black-button" type="button" disabled={editorBusy !== null} aria-label={t("editorAdmin.aria.confirmDeactivate", { name: editorDisplayName(confirmDeactivate, localized) })} onClick={() => void changeAccess(confirmDeactivate, false)}>{editorBusy ? t("editorAdmin.actions.deactivating") : t("editorAdmin.actions.deactivateEditor")}</button>
            </>
          }
        >
          <p>{t("editorAdmin.dialog.deactivateBody")}</p>
        </DialogFrame>
      ) : null}

      <form className="editor-onboarding-form" onSubmit={submit} noValidate>
        <section aria-labelledby="account-section-title">
          <div className="editor-form-section-heading">
            <span>01</span>
            <h2 id="account-section-title">{t("editorAdmin.invite.title")}</h2>
          </div>
          <div className="editor-form-grid">
            <label className="field editor-form-wide">
              <span>{t("editorAdmin.invite.email")}</span>
              <input
                ref={(element) => { validationFieldRefs.current.email = element ?? undefined; }}
                aria-label={t("editorAdmin.invite.emailAria")}
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
              {t("editorAdmin.invite.explanation")}
            </p>
          </div>
        </section>

        {error && !validationField && <div className="inline-notice" role="alert">! {noticeText(error, t)}</div>}
        {success && <div className="inline-notice editor-success" role="status">{noticeText(success, t)}</div>}
        <footer className="editor-form-footer">
          <p>{t("editorAdmin.invite.accessHelp")}</p>
          <button className="accent-button" type="submit" disabled={busy}>
            {busy ? t("editorAdmin.actions.inviting") : t("editorAdmin.invite.title")}
          </button>
        </footer>
      </form>

      <section className="editor-request-queue" aria-labelledby="editor-requests-title">
        <header>
          <div>
            <p className="workspace-kicker">{t("editorAdmin.kicker.reviewQueue")}</p>
            <h2 id="editor-requests-title">{t("editorAdmin.requests.title")}</h2>
          </div>
          <span>{t("editorAdmin.requests.pendingCount", {
            count: formatNumber(requests.length),
          })}</span>
        </header>
        {requestsLoading ? <div className="table-state"><p>{t("editorAdmin.loading.requests")}</p></div> : requests.length === 0 ? (
          <div className="table-state"><p>{t("editorAdmin.empty.requests")}</p></div>
        ) : (
          <div className="editor-request-list">
            {requests.map((request) => {
              const bioKo = typeof request.payload.bio_ko === "string" ? request.payload.bio_ko : "";
              const bioEn = typeof request.payload.bio_en === "string" ? request.payload.bio_en : "";
              const curationDescriptionKo = typeof request.payload.curation_description_ko === "string" ? request.payload.curation_description_ko : "";
              const curationDescriptionEn = typeof request.payload.curation_description_en === "string" ? request.payload.curation_description_en : "";
              const changes = curationRequestChanges(request.payload);
              const displayEditorName = requestEditorName(request);
              const bio = localized(bioKo, bioEn);
              const alternateBio = (locale === "ko" ? bioEn : bioKo).trim();
              const curationDescription = localized(
                curationDescriptionKo,
                curationDescriptionEn,
              );
              const alternateCurationDescription = (
                locale === "ko" ? curationDescriptionEn : curationDescriptionKo
              ).trim();
              return (
                <article className="editor-request-card" key={request.id}>
                  <div className="editor-request-meta">
                    <span>{request.kind === "profile"
                      ? t("editorAdmin.request.type.profile")
                      : t("editorAdmin.request.type.curation")}</span>
                    <time dateTime={request.createdAt}>{formatDate(request.createdAt)}</time>
                  </div>
                  <h3>{displayEditorName}</h3>
                  {request.kind === "profile" ? (
                    <div className="editor-request-bio">
                      <span>{t("editorAdmin.request.proposedBio")}</span>
                      <p>{bio}</p>
                      {alternateBio && alternateBio !== bio ? <p className="muted">{alternateBio}</p> : null}
                    </div>
                  ) : (
                    <>
                      {curationDescription ? (
                        <div className="editor-request-bio editor-request-statement">
                          <span>{t("editorAdmin.request.statement")}</span>
                          <p>{curationDescription}</p>
                          {alternateCurationDescription && alternateCurationDescription !== curationDescription ? <p className="muted">{alternateCurationDescription}</p> : null}
                        </div>
                      ) : null}
                      <p>{t(changes.length === 1
                        ? "editorAdmin.request.changeCount.one"
                        : "editorAdmin.request.changeCount.other", {
                        count: formatNumber(changes.length),
                      })}</p>
                      <ul className="editor-request-changes">
                        {changes.map((change) => {
                          const changeName = localized(change.nameKo, change.nameEn, change.id);
                          const alternateChangeName = (
                            locale === "ko" ? change.nameEn : change.nameKo
                          ).trim();
                          return (
                            <li key={change.id}>
                              <span className="editor-request-decision">{change.selected
                                ? t("editorAdmin.request.add")
                                : t("editorAdmin.request.remove")}</span>
                              <strong>{changeName}</strong>
                              {alternateChangeName && alternateChangeName !== changeName ? <small>{alternateChangeName}</small> : null}
                              {(change.venueNameKo || change.venueNameEn) ? (
                                <small>{localized(change.venueNameKo, change.venueNameEn)}</small>
                              ) : null}
                            </li>
                          );
                        })}
                      </ul>
                    </>
                  )}
                  <label className="field"><span>{t("editorAdmin.request.rejectionReason")}</span><textarea value={reviewNotes[request.id] ?? ""} onChange={(event) => setReviewNotes((current) => ({ ...current, [request.id]: event.target.value }))} /></label>
                  <div className="editor-request-actions">
                    <button className="outlined-button" type="button" disabled={requestBusy !== null || !(reviewNotes[request.id]?.trim())} onClick={() => void review(request, false)}>{t("editorAdmin.actions.reject")}</button>
                    <button className="black-button" type="button" aria-label={t("editorAdmin.aria.approve", {
                      name: displayEditorName,
                      kind: t(request.kind === "profile"
                        ? "editorAdmin.requestKind.profile"
                        : "editorAdmin.requestKind.curation"),
                    })} disabled={requestBusy !== null} onClick={() => void review(request, true)}>{requestBusy === request.id ? t("editorAdmin.actions.reviewing") : t("editorAdmin.actions.approve")}</button>
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
