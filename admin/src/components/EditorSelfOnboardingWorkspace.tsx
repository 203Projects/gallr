import { useState, type FormEvent } from "react";
import type {
  EditorSelfOnboardingInput,
  EditorSelfOnboardingRepository,
} from "../repositories/EditorSelfOnboardingRepository";
import { LanguageSwitch, useI18n, type MessageKey } from "../i18n";

type Translate = ReturnType<typeof useI18n>["t"];

type UiNotice = { kind: "interface"; key: MessageKey };

const interfaceNotice = (key: MessageKey): UiNotice => ({ kind: "interface", key });

function noticeText(notice: UiNotice, t: Translate): string {
  return t(notice.key);
}

const emptyProfile: EditorSelfOnboardingInput = {
  editorId: "",
  nameKo: "",
  nameEn: "",
  titleKo: "",
  titleEn: "",
  bioKo: "",
  bioEn: "",
  curationDescriptionKo: "",
  curationDescriptionEn: "",
};

function validationMessage(
  input: EditorSelfOnboardingInput,
): UiNotice | null {
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(input.editorId.trim())) {
    return interfaceNotice("editorSelf.validation.slugFormat");
  }
  if (input.editorId.trim().length < 3 || input.editorId.trim().length > 64) {
    return interfaceNotice("editorSelf.validation.slugLength");
  }
  if (!input.nameKo.trim() || !input.titleKo.trim() || !input.bioKo.trim()) {
    return interfaceNotice("editorSelf.validation.requiredKoreanProfile");
  }
  if (!input.curationDescriptionKo.trim()) {
    return interfaceNotice("editorSelf.validation.requiredKoreanStatement");
  }
  return null;
}

export function EditorSelfOnboardingWorkspace({
  repository,
  onCompleted,
  onSignOut,
}: {
  repository: EditorSelfOnboardingRepository;
  onCompleted: (editorName: string) => void;
  onSignOut?: () => void;
}) {
  const { t } = useI18n();
  const [form, setForm] = useState(emptyProfile);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<UiNotice | null>(null);

  const update = <Key extends keyof EditorSelfOnboardingInput>(
    key: Key,
    value: EditorSelfOnboardingInput[Key],
  ) => setForm((current) => ({ ...current, [key]: value }));

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const validation = validationMessage(form);
    if (validation) {
      setError(validation);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const profile = await repository.complete(form);
      onCompleted(profile.nameEn || profile.nameKo);
    } catch {
      setError(interfaceNotice("editorSelf.error.create"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="editor-onboarding-workspace editor-self-onboarding">
      <header className="editor-onboarding-header">
        <LanguageSwitch />
        <p className="workspace-kicker">{t("editorSelf.kicker")}</p>
        <h1>{t("editorSelf.title")}</h1>
        <p>{t("editorSelf.introduction")}</p>
        {onSignOut ? (
          <button className="text-button" type="button" onClick={onSignOut}>
            {t("actions.signOut")}
          </button>
        ) : null}
      </header>

      <form className="editor-onboarding-form" onSubmit={submit} noValidate>
        <section aria-labelledby="identity-title">
          <div className="editor-form-section-heading">
            <span>01</span>
            <h2 id="identity-title">{t("editorSelf.identity.title")}</h2>
          </div>
          <div className="editor-form-grid">
            <label className="field">
              <span>{t("editorSelf.fields.slug")}</span>
              <input aria-label={t("editorSelf.aria.slug")} value={form.editorId} onChange={(event) => update("editorId", event.target.value)} placeholder={t("editorSelf.placeholder.slug")} spellCheck={false} />
              <small className="field-help">{t("editorSelf.help.slug")}</small>
            </label>
            <label className="field"><span>{t("editorSelf.fields.nameKo")}</span><input aria-label={t("editorSelf.aria.nameKo")} value={form.nameKo} onChange={(event) => update("nameKo", event.target.value)} /></label>
            <label className="field"><span>{t("editorSelf.fields.nameEn")}</span><input aria-label={t("editorSelf.aria.nameEn")} value={form.nameEn} onChange={(event) => update("nameEn", event.target.value)} /></label>
            <label className="field"><span>{t("editorSelf.fields.titleKo")}</span><input aria-label={t("editorSelf.aria.titleKo")} value={form.titleKo} onChange={(event) => update("titleKo", event.target.value)} /></label>
            <label className="field"><span>{t("editorSelf.fields.titleEn")}</span><input aria-label={t("editorSelf.aria.titleEn")} value={form.titleEn} onChange={(event) => update("titleEn", event.target.value)} /></label>
          </div>
        </section>

        <section aria-labelledby="profile-copy-title">
          <div className="editor-form-section-heading">
            <span>02</span>
            <h2 id="profile-copy-title">{t("editorSelf.profile.title")}</h2>
          </div>
          <div className="editor-form-grid">
            <label className="field editor-form-wide"><span>{t("editorSelf.fields.bioKo")}</span><textarea aria-label={t("editorSelf.aria.bioKo")} value={form.bioKo} onChange={(event) => update("bioKo", event.target.value)} /></label>
            <label className="field editor-form-wide"><span>{t("editorSelf.fields.bioEn")}</span><textarea aria-label={t("editorSelf.aria.bioEn")} value={form.bioEn} onChange={(event) => update("bioEn", event.target.value)} /></label>
            <p className="editor-form-explanation editor-form-wide">{t("editorSelf.statementExplanation")}</p>
            <label className="field editor-form-wide"><span>{t("editorSelf.fields.statementKo")}</span><textarea aria-label={t("editorSelf.aria.statementKo")} value={form.curationDescriptionKo} onChange={(event) => update("curationDescriptionKo", event.target.value)} /></label>
            <label className="field editor-form-wide"><span>{t("editorSelf.fields.statementEn")}</span><textarea aria-label={t("editorSelf.aria.statementEn")} value={form.curationDescriptionEn} onChange={(event) => update("curationDescriptionEn", event.target.value)} /></label>
          </div>
        </section>

        {error ? <div className="inline-notice" role="alert">! {noticeText(error, t)}</div> : null}
        <footer className="editor-form-footer">
          <p>{t("editorSelf.footer")}</p>
          <button className="accent-button" type="submit" disabled={busy}>
            {busy ? t("editorSelf.actions.creating") : t("editorSelf.actions.create")}
          </button>
        </footer>
      </form>
    </main>
  );
}
