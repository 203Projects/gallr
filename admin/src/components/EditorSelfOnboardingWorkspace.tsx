import { useState, type FormEvent } from "react";
import type {
  EditorSelfOnboardingInput,
  EditorSelfOnboardingRepository,
} from "../repositories/EditorSelfOnboardingRepository";

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

function validationMessage(input: EditorSelfOnboardingInput): string | null {
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(input.editorId.trim())) {
    return "Editor slug must use lowercase letters, numbers, and single hyphens.";
  }
  if (input.editorId.trim().length < 3 || input.editorId.trim().length > 64) {
    return "Editor slug must be between 3 and 64 characters.";
  }
  if (!input.nameKo.trim() || !input.titleKo.trim() || !input.bioKo.trim()) {
    return "Complete the required Korean profile fields.";
  }
  if (!input.curationDescriptionKo.trim()) {
    return "Add the Korean curatorial statement shown with your collection.";
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
  const [form, setForm] = useState(emptyProfile);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
    } catch (caught) {
      setError(
        caught instanceof Error
          ? caught.message
          : "The editor profile could not be created.",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="editor-onboarding-workspace editor-self-onboarding">
      <header className="editor-onboarding-header">
        <p className="workspace-kicker">GALLR EDITOR / ONBOARDING</p>
        <h1>Create your editor profile</h1>
        <p>
          Add the identity and statement for your curation workspace. Your
          profile starts unpublished and becomes public only after Admin review.
        </p>
        {onSignOut ? (
          <button className="text-button" type="button" onClick={onSignOut}>
            Sign out
          </button>
        ) : null}
      </header>

      <form className="editor-onboarding-form" onSubmit={submit} noValidate>
        <section aria-labelledby="identity-title">
          <div className="editor-form-section-heading">
            <span>01</span>
            <h2 id="identity-title">Identity</h2>
          </div>
          <div className="editor-form-grid">
            <label className="field">
              <span>Editor slug *</span>
              <input aria-label="Editor slug" value={form.editorId} onChange={(event) => update("editorId", event.target.value)} placeholder="mina-kim" spellCheck={false} />
              <small className="field-help">Permanent lowercase URL identifier</small>
            </label>
            <label className="field"><span>Name (Korean) *</span><input aria-label="Name (Korean)" value={form.nameKo} onChange={(event) => update("nameKo", event.target.value)} /></label>
            <label className="field"><span>Name (English)</span><input aria-label="Name (English)" value={form.nameEn} onChange={(event) => update("nameEn", event.target.value)} /></label>
            <label className="field"><span>Title (Korean) *</span><input aria-label="Title (Korean)" value={form.titleKo} onChange={(event) => update("titleKo", event.target.value)} /></label>
            <label className="field"><span>Title (English)</span><input aria-label="Title (English)" value={form.titleEn} onChange={(event) => update("titleEn", event.target.value)} /></label>
          </div>
        </section>

        <section aria-labelledby="profile-copy-title">
          <div className="editor-form-section-heading">
            <span>02</span>
            <h2 id="profile-copy-title">Profile</h2>
          </div>
          <div className="editor-form-grid">
            <label className="field editor-form-wide"><span>Bio (Korean) *</span><textarea aria-label="Bio (Korean)" value={form.bioKo} onChange={(event) => update("bioKo", event.target.value)} /></label>
            <label className="field editor-form-wide"><span>Bio (English)</span><textarea aria-label="Bio (English)" value={form.bioEn} onChange={(event) => update("bioEn", event.target.value)} /></label>
            <p className="editor-form-explanation editor-form-wide">The curatorial statement introduces your exhibition collection and remains separate from your personal biography.</p>
            <label className="field editor-form-wide"><span>Curatorial statement (Korean) *</span><textarea aria-label="Curatorial statement (Korean)" value={form.curationDescriptionKo} onChange={(event) => update("curationDescriptionKo", event.target.value)} /></label>
            <label className="field editor-form-wide"><span>Curatorial statement (English)</span><textarea aria-label="Curatorial statement (English)" value={form.curationDescriptionEn} onChange={(event) => update("curationDescriptionEn", event.target.value)} /></label>
          </div>
        </section>

        {error ? <div className="inline-notice" role="alert">! {error}</div> : null}
        <footer className="editor-form-footer">
          <p>Your workspace opens immediately. Admin controls public visibility and scheduling.</p>
          <button className="accent-button" type="submit" disabled={busy}>
            {busy ? "Creating…" : "Create editor profile"}
          </button>
        </footer>
      </form>
    </main>
  );
}
