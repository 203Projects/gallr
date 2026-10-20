import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useI18n, type MessageKey } from "../i18n";
import {
  APP_PLATFORMS,
  AppVersionPolicyNotStaffError,
  AppVersionPolicyRejectedError,
  compareAppVersions,
  isAppVersion,
  type AppPlatform,
  type AppVersionPolicy,
  type AppVersionPolicyInput,
  type AppVersionPolicyRepository,
} from "../repositories/AppVersionPolicyRepository";

/** Same spelling in both languages; the store names are not translated. */
const platformLabels: Record<AppPlatform, string> = { android: "Android", ios: "iOS" };

interface Draft {
  minimumVersion: string;
  recommendedVersion: string;
  latestReleasedVersion: string;
}

type DraftField = keyof Draft;

type Notice = { kind: "error" | "success"; key: MessageKey };

function draftOf(policy: AppVersionPolicy): Draft {
  return {
    minimumVersion: policy.minimumVersion,
    recommendedVersion: policy.recommendedVersion,
    latestReleasedVersion: policy.latestReleasedVersion ?? "",
  };
}

function inputOf(draft: Draft): AppVersionPolicyInput {
  const latest = draft.latestReleasedVersion.trim();
  return {
    minimumVersion: draft.minimumVersion.trim(),
    recommendedVersion: draft.recommendedVersion.trim(),
    latestReleasedVersion: latest === "" ? null : latest,
  };
}

/** Client-side mirror of the server's checks, keyed by the field that carries the message. */
function draftIssues(draft: Draft): Partial<Record<DraftField, MessageKey>> {
  const input = inputOf(draft);
  const issues: Partial<Record<DraftField, MessageKey>> = {};
  if (!isAppVersion(input.minimumVersion)) issues.minimumVersion = "appVersions.validation.format";
  if (!isAppVersion(input.recommendedVersion)) issues.recommendedVersion = "appVersions.validation.format";
  if (input.latestReleasedVersion !== null && !isAppVersion(input.latestReleasedVersion)) {
    issues.latestReleasedVersion = "appVersions.validation.format";
  }
  if (
    !issues.minimumVersion
    && !issues.recommendedVersion
    && compareAppVersions(input.recommendedVersion, input.minimumVersion) < 0
  ) {
    issues.recommendedVersion = "appVersions.validation.recommendedBelowMinimum";
  }
  return issues;
}

function minimumAboveReleased(draft: Draft): boolean {
  const input = inputOf(draft);
  return (
    input.latestReleasedVersion !== null
    && isAppVersion(input.minimumVersion)
    && isAppVersion(input.latestReleasedVersion)
    && compareAppVersions(input.minimumVersion, input.latestReleasedVersion) > 0
  );
}

function sameAsStored(draft: Draft, policy: AppVersionPolicy): boolean {
  const input = inputOf(draft);
  return (
    input.minimumVersion === policy.minimumVersion
    && input.recommendedVersion === policy.recommendedVersion
    && input.latestReleasedVersion === policy.latestReleasedVersion
  );
}

function saveErrorKey(error: unknown): MessageKey {
  if (error instanceof AppVersionPolicyNotStaffError) return "appVersions.error.notStaff";
  if (error instanceof AppVersionPolicyRejectedError) return "appVersions.error.rejected";
  return "appVersions.error.save";
}

export function AppVersionsWorkspace({ repository }: { repository: AppVersionPolicyRepository }) {
  const { t, formatDateTime } = useI18n();
  const [policies, setPolicies] = useState<AppVersionPolicy[] | null>(null);
  const [loadError, setLoadError] = useState<MessageKey | null>(null);
  const [drafts, setDrafts] = useState<Partial<Record<AppPlatform, Draft>>>({});
  const [notices, setNotices] = useState<Partial<Record<AppPlatform, Notice>>>({});
  const [saving, setSaving] = useState<AppPlatform | null>(null);

  useEffect(() => {
    let cancelled = false;
    repository
      .list()
      .then((rows) => {
        if (cancelled) return;
        setPolicies(rows);
        setDrafts(Object.fromEntries(rows.map((row) => [row.platform, draftOf(row)])));
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setLoadError(
          error instanceof AppVersionPolicyNotStaffError ? "appVersions.error.notStaff" : "appVersions.error.load",
        );
      });
    return () => {
      cancelled = true;
    };
  }, [repository]);

  const updateDraft = useCallback((platform: AppPlatform, field: DraftField, value: string) => {
    setDrafts((current) => {
      const draft = current[platform];
      return draft ? { ...current, [platform]: { ...draft, [field]: value } } : current;
    });
    setNotices((current) => ({ ...current, [platform]: undefined }));
  }, []);

  const save = useCallback(
    async (platform: AppPlatform, draft: Draft) => {
      setSaving(platform);
      try {
        const saved = await repository.save(platform, inputOf(draft));
        setPolicies((current) => (current ?? []).map((row) => (row.platform === platform ? saved : row)));
        setDrafts((current) => ({ ...current, [platform]: draftOf(saved) }));
        setNotices((current) => ({ ...current, [platform]: { kind: "success", key: "appVersions.notice.saved" } }));
      } catch (error) {
        setNotices((current) => ({ ...current, [platform]: { kind: "error", key: saveErrorKey(error) } }));
      } finally {
        setSaving(null);
      }
    },
    [repository],
  );

  return (
    <main className="workspace app-versions-workspace">
      <header className="workspace-header">
        <div className="workspace-title-row">
          <div>
            <h1>{t("appVersions.title")}</h1>
            <p className="workspace-subtitle">{t("appVersions.subtitle")}</p>
          </div>
        </div>
      </header>
      {loadError ? (
        <div className="inline-notice" role="alert">! {t(loadError)}</div>
      ) : policies === null ? (
        <div className="table-state"><p>{t("appVersions.loading")}</p></div>
      ) : (
        <div className="app-version-list">
          {APP_PLATFORMS.map((platform) => {
            const policy = policies.find((row) => row.platform === platform);
            const draft = drafts[platform];
            if (!policy || !draft) return null;
            return (
              <AppVersionCard
                key={platform}
                platform={platform}
                policy={policy}
                draft={draft}
                notice={notices[platform]}
                saving={saving === platform}
                busy={saving !== null}
                onChange={(field, value) => updateDraft(platform, field, value)}
                onSave={() => save(platform, draft)}
                formatDateTime={formatDateTime}
              />
            );
          })}
        </div>
      )}
    </main>
  );
}

function AppVersionCard({
  platform,
  policy,
  draft,
  notice,
  saving,
  busy,
  onChange,
  onSave,
  formatDateTime,
}: {
  platform: AppPlatform;
  policy: AppVersionPolicy;
  draft: Draft;
  notice: Notice | undefined;
  saving: boolean;
  busy: boolean;
  onChange: (field: DraftField, value: string) => void;
  onSave: () => void;
  formatDateTime: (value: string) => string;
}) {
  const { t } = useI18n();
  const issues = draftIssues(draft);
  const invalid = Object.keys(issues).length > 0;
  const canSave = !invalid && !busy && !sameAsStored(draft, policy);
  const label = platformLabels[platform];
  const changed = policy.updatedByEmail !== null && policy.updatedAt !== null
    ? t("appVersions.updatedBy", { time: formatDateTime(policy.updatedAt), email: policy.updatedByEmail })
    : t("appVersions.seeded");

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (canSave) onSave();
  };

  // Help and errors sit beside the label, not inside it, so the label's text stays the field name.
  const field = (name: DraftField, labelKey: MessageKey, help?: MessageKey) => (
    <div className="app-version-field">
      <label className="field">
        <span>{t(labelKey)}</span>
        <input
          value={draft[name]}
          inputMode="decimal"
          aria-invalid={issues[name] ? "true" : undefined}
          onChange={(event) => onChange(name, event.target.value)}
        />
      </label>
      {help && !issues[name] && <small className="field-help">{t(help)}</small>}
      {issues[name] && <p className="field-error" role="alert">{t(issues[name])}</p>}
    </div>
  );

  return (
    <form className="app-version-card" aria-label={label} onSubmit={submit} noValidate>
      <div className="app-version-card-heading">
        <span>{t("appVersions.title")}</span>
        <h2>{label}</h2>
        {changed && <p className="app-version-changed">{changed}</p>}
      </div>
      {field("minimumVersion", "appVersions.fields.minimum")}
      {field("recommendedVersion", "appVersions.fields.recommended")}
      {field("latestReleasedVersion", "appVersions.fields.latestReleased", "appVersions.help.latestReleased")}
      {minimumAboveReleased(draft) && (
        <div className="inline-notice" role="note">! {t("appVersions.warning.minimumAboveReleased")}</div>
      )}
      {notice && (
        <div
          className={`inline-notice${notice.kind === "success" ? " editor-success" : ""}`}
          role={notice.kind === "error" ? "alert" : "status"}
        >
          {notice.kind === "error" ? "! " : ""}{t(notice.key)}
        </div>
      )}
      <footer>
        <button type="submit" className="accent-button" disabled={!canSave}>
          {saving ? t("appVersions.actions.saving") : t("appVersions.actions.save")}
        </button>
      </footer>
    </form>
  );
}
