import { useCallback, useEffect, useId, useRef, useState } from "react";
import type {
  ArtTerm,
  GalleryGeocodeCandidate,
  MembershipStatus,
  OwnerExhibition,
  OwnerExhibitionPatch,
  OwnerRepository,
} from "../domain";
import {
  LocaleToggle,
  alternateBilingual,
  formatDateOnly,
  formatNumber,
  formatTimestampDate,
  localizeBilingual,
  useLocale,
  type PortalLocale,
  type PortalMessages,
} from "../i18n";
import { OwnerShell } from "./OwnerShell";
import { publicExhibitionUrl } from "../publicExhibitionUrl";
import { ExhibitionQrCard } from "./ExhibitionQrCard";
import { ExhibitionArtMetadataEditor } from "./ExhibitionArtMetadataEditor";

type ExhibitionRepository = Pick<
  OwnerRepository,
  | "listExhibitions"
  | "hideExhibition"
  | "createExhibitionDraft"
  | "saveExhibitionDraft"
  | "uploadCover"
  | "submitExhibition"
  | "searchGalleryAddress"
  | "listArtTerms"
  | "searchArtists"
  | "activateLaunchKit"
>;

// Address fields (city/region/address) and coordinates are always derived
// together from a single bounded geocode selection, never hand-typed, so a
// gallery owner never has to know decimal latitude/longitude.
type LocationFields = Pick<
  OwnerExhibition,
  | "cityKo"
  | "cityEn"
  | "regionKo"
  | "regionEn"
  | "addressKo"
  | "addressEn"
  | "latitude"
  | "longitude"
>;

function withCandidate(
  exhibition: OwnerExhibition,
  candidate: GalleryGeocodeCandidate,
): OwnerExhibition {
  return {
    ...exhibition,
    cityKo: candidate.cityKo,
    cityEn: candidate.cityEn,
    regionKo: candidate.regionKo,
    regionEn: candidate.regionEn,
    addressKo: candidate.roadAddress || candidate.jibunAddress,
    addressEn: candidate.englishAddress,
    latitude: candidate.latitude,
    longitude: candidate.longitude,
  };
}

function withLocation(
  exhibition: OwnerExhibition,
  location: LocationFields,
): OwnerExhibition {
  return { ...exhibition, ...location };
}

// A geocode selection always fills the address text and the coordinates
// together, and the server independently requires city_ko / region_ko /
// address_ko on submit. Legacy drafts predating address search can carry
// coordinates with blank address text, so presence of coordinates alone is not
// a sufficient location check: treat that state as an incomplete location so
// the owner is told to re-search instead of being rejected server-side by an
// unexplained owner_submission_incomplete.
function hasCompleteLocation(exhibition: OwnerExhibition): boolean {
  return (
    exhibition.latitude !== null &&
    exhibition.longitude !== null &&
    exhibition.cityKo.trim() !== "" &&
    exhibition.regionKo.trim() !== "" &&
    exhibition.addressKo.trim() !== ""
  );
}

type ExhibitionErrorKey = keyof PortalMessages["exhibitions"]["errors"];

const ownerErrorExplanations: ReadonlyArray<readonly [string, ExhibitionErrorKey]> = [
  [
    "owner_submission_incomplete",
    "submissionIncomplete",
  ],
  [
    "owner_submission_bilingual_incomplete",
    "bilingualIncomplete",
  ],
  ["owner_submission_cover_required", "addCover"],
  ["active_gallery_membership_required", "verification"],
  ["published_owner_exhibition_required", "launchEligibility"],
  ["launch_kit_payment_state_present", "launchPaymentState"],
  ["launch_kit_not_activatable", "launchNotActivatable"],
  ["revision_conflict", "revision"],
  ["owner_cover_mime_invalid", "coverMime"],
  ["owner_cover_size_invalid", "coverSize"],
  ["owner_cover_filename_invalid", "coverFilename"],
  ["owner_cover_object_not_found", "coverMissing"],
  ["owner_cover_mime_mismatch", "coverMimeMismatch"],
  ["owner_cover_size_mismatch", "coverSizeMismatch"],
  ["owner_patch_ticket_url_invalid", "invalidTicket"],
  ["owner_patch_date_invalid", "invalidDate"],
  ["owner_patch_time_invalid", "invalidTime"],
  ["owner_patch_field_too_long", "tooLong"],
  ["owner_patch_field_invalid", "unsupportedFormat"],
  ["owner_patch_field_not_allowed", "unsupportedField"],
  ["patch_must_be_an_object", "invalidPatch"],
  ["geocode_access_required", "geocodeAccess"],
  ["geocoding_rate_limited", "geocodeRate"],
];

function errorMessage(error: unknown, fallback: ExhibitionErrorKey): ExhibitionErrorKey {
  const raw = error instanceof Error && error.message ? error.message : "";
  for (const [code, explanation] of ownerErrorExplanations) {
    if (raw.includes(code)) return explanation;
  }
  return fallback;
}

function removalErrorMessage(error: unknown): ExhibitionErrorKey {
  const raw = error instanceof Error && error.message ? error.message : "";
  if (raw.includes("revision_conflict")) {
    return "removalRevision";
  }
  if (raw.includes("owner_exhibition_access_denied") || raw.includes("gallery_info_access_denied")) {
    return "removalAccess";
  }
  return "removal";
}

function requestId(): string {
  return crypto.randomUUID();
}

function statusLabel(status: OwnerExhibition["ownerStatus"], messages: PortalMessages): string {
  switch (status) {
    case "needs_changes": return messages.exhibitions.statuses.needsChanges;
    case "submitted": return messages.exhibitions.statuses.submitted;
    case "published": return messages.exhibitions.statuses.published;
    case "archived": return messages.exhibitions.statuses.archived;
    default: return messages.exhibitions.statuses.draft;
  }
}

function ImpactSummary({ exhibition }: { exhibition: OwnerExhibition }) {
  const { locale, messages } = useLocale();
  if (exhibition.ownerStatus !== "published") return null;
  return (
    <div className="impact-summary">
      <dl>
        <div><dt>{messages.exhibitions.impact.last30Days}</dt><dd>{formatNumber(exhibition.pageLoads30d, locale)}</dd></div>
        <div><dt>{messages.exhibitions.impact.allTime}</dt><dd>{formatNumber(exhibition.pageLoadsAllTime, locale)}</dd></div>
      </dl>
      <p>{messages.exhibitions.impact.caveat}</p>
    </div>
  );
}

function editablePatch(
  exhibition: OwnerExhibition,
  includeArtMetadata = false,
): OwnerExhibitionPatch {
  const patch: OwnerExhibitionPatch = {
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
    latitude: exhibition.latitude,
    longitude: exhibition.longitude,
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
  if (includeArtMetadata && exhibition.artMetadata !== null) {
    patch.artMetadata = exhibition.artMetadata;
  }
  return patch;
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

type EditableField = Exclude<keyof OwnerExhibitionPatch, "artMetadata">;
type FieldError =
  | { kind: "tooLong"; limit: number }
  | { kind: "coordinatePair" }
  | { kind: "coordinateRange" }
  | { kind: "invalidDate" }
  | { kind: "invalidTime" }
  | { kind: "invalidTicket" }
  | { kind: "required" }
  | { kind: "closingBeforeOpening" };
type FieldErrors = Partial<Record<EditableField, FieldError>>;

const editableFields: readonly EditableField[] = [
  "nameKo", "nameEn", "venueNameKo", "venueNameEn", "cityKo", "cityEn",
  "regionKo", "regionEn", "addressKo", "addressEn", "latitude", "longitude",
  "openingDate", "closingDate", "descriptionKo", "descriptionEn", "hours",
  "contact", "receptionDate", "receptionStartTime", "ticketUrl",
];

const requiredSubmissionFields = [
  "nameKo",
  "nameEn",
  "venueNameKo",
  "venueNameEn",
  "openingDate",
  "closingDate",
  "hours",
] as const;

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
  for (const field of editableFields) {
    const limit = fieldLimit(field);
    const value = patch[field];
    if (typeof value === "string" && value.length > limit) {
      errors[field] = { kind: "tooLong", limit };
    }
  }
  if ((patch.latitude === null) !== (patch.longitude === null)) {
    errors.latitude = { kind: "coordinatePair" };
    errors.longitude = { kind: "coordinatePair" };
  } else if (
    patch.latitude !== null && patch.longitude !== null &&
    (!Number.isFinite(patch.latitude) || !Number.isFinite(patch.longitude) ||
      patch.latitude < -90 || patch.latitude > 90 ||
      patch.longitude < -180 || patch.longitude > 180)
  ) {
    errors.latitude = { kind: "coordinateRange" };
    errors.longitude = { kind: "coordinateRange" };
  }
  for (const field of ["openingDate", "closingDate", "receptionDate"] as const) {
    if (patch[field] && !validIsoDate(patch[field])) {
      errors[field] = { kind: "invalidDate" };
    }
  }
  if (patch.receptionStartTime && !/^([01]\d|2[0-3]):[0-5]\d$/.test(patch.receptionStartTime)) {
    errors.receptionStartTime = { kind: "invalidTime" };
  }
  if (!validHttpUrl(patch.ticketUrl)) {
    errors.ticketUrl = { kind: "invalidTicket" };
  }
  return errors;
}

function submissionValidationErrors(exhibition: OwnerExhibition): FieldErrors {
  const errors = draftValidationErrors(exhibition);
  for (const field of requiredSubmissionFields) {
    if (!exhibition[field].trim()) errors[field] = { kind: "required" };
  }
  if (!hasCompleteLocation(exhibition)) {
    errors.latitude = { kind: "required" };
    errors.longitude = { kind: "required" };
  }
  if (
    !errors.openingDate &&
    !errors.closingDate &&
    exhibition.openingDate &&
    exhibition.closingDate &&
    exhibition.closingDate < exhibition.openingDate
  ) {
    errors.closingDate = { kind: "closingBeforeOpening" };
  }
  return errors;
}

type ValidationSummaryKey = "fixBeforeSave" | "fixBeforeCover" | "completeBeforeSubmit";
type EditorError =
  | { kind: "known"; key: ExhibitionErrorKey }
  | { kind: "summary"; key: ValidationSummaryKey }
  | { kind: "field"; field: EditableField; error: FieldError };

function validationSummary(errors: FieldErrors, multiple: ValidationSummaryKey): EditorError | null {
  const entries = Object.entries(errors) as Array<[EditableField, FieldError]>;
  if (entries.length === 0) return null;
  if (entries.length === 1) {
    const [field, error] = entries[0];
    return { kind: "field", field, error };
  }
  return { kind: "summary", key: multiple };
}

// The exact, ordered list of still-missing required items, so an owner is told
// precisely what to supplement rather than a single generic message.
type MissingRequirement = EditableField | "location" | "cover";

function missingRequirements(errors: FieldErrors, coverMissing: boolean): MissingRequirement[] {
  const labels: MissingRequirement[] = [];
  for (const field of editableFields) {
    if (field === "latitude" || field === "longitude") continue;
    if (errors[field]?.kind === "required") labels.push(field);
  }
  if (errors.latitude?.kind === "required" || errors.longitude?.kind === "required") {
    labels.push("location");
  }
  if (coverMissing) labels.push("cover");
  return labels;
}

function fieldErrorMessage(
  field: EditableField,
  error: FieldError | undefined,
  locale: PortalLocale,
  messages: PortalMessages,
): string | undefined {
  if (!error) return undefined;
  const label = messages.exhibitions.fields[field];
  switch (error.kind) {
    case "tooLong": return messages.exhibitions.validation.tooLong(label, formatNumber(error.limit, locale));
    case "coordinatePair": return messages.exhibitions.validation.coordinatePair;
    case "coordinateRange": return messages.exhibitions.validation.coordinateRange;
    case "invalidDate": return messages.exhibitions.validation.invalidDate(label);
    case "invalidTime": return messages.exhibitions.validation.invalidTime;
    case "invalidTicket": return messages.exhibitions.validation.invalidTicket;
    case "required": return messages.exhibitions.validation.required;
    case "closingBeforeOpening": return messages.exhibitions.validation.closingBeforeOpening;
  }
}

function editorErrorMessage(
  error: EditorError,
  locale: PortalLocale,
  messages: PortalMessages,
): string {
  if (error.kind === "known") return messages.exhibitions.errors[error.key];
  if (error.kind === "summary") return messages.exhibitions.validation[error.key];
  const fieldMessage = fieldErrorMessage(error.field, error.error, locale, messages);
  return error.error.kind === "required"
    ? messages.exhibitions.validation.requiredSummary(messages.exhibitions.fields[error.field])
    : fieldMessage ?? messages.exhibitions.validation.completeBeforeSubmit;
}

function ReadOnlyField({ label, value }: { label: string; value: string }) {
  const inputId = useId();
  return (
    <div className="field">
      <label htmlFor={inputId}>{label}</label>
      <input id={inputId} value={value} readOnly />
    </div>
  );
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
  artTerms,
  artTermsError,
}: {
  exhibition: OwnerExhibition;
  membershipStatus: MembershipStatus;
  repository: ExhibitionRepository;
  onChange: (record: OwnerExhibition) => void;
  onBack: () => void;
  onLaunchReady: () => void;
  launchKitEnabled: boolean;
  publicSiteUrl: string;
  artTerms: ArtTerm[] | null;
  artTermsError: boolean;
}) {
  const { locale, messages } = useLocale();
  const [record, setRecord] = useState(exhibition);
  const [busy, setBusy] = useState<"save" | "cover" | "submit" | "launch" | null>(null);
  const [saved, setSaved] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<EditorError | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [coverError, setCoverError] = useState(false);
  const [launchNotice, setLaunchNotice] = useState(false);
  const [missing, setMissing] = useState<MissingRequirement[]>([]);
  const [query, setQuery] = useState("");
  const [candidates, setCandidates] = useState<GalleryGeocodeCandidate[]>([]);
  const [searchCompleted, setSearchCompleted] = useState(false);
  const [searching, setSearching] = useState(false);
  const artMetadataDirty = useRef(false);
  const canEdit = record.ownerStatus === "draft" || record.ownerStatus === "needs_changes";
  // Mirrors the submit-time requirement exactly, so a legacy draft holding
  // coordinates but no address text renders the search prompt (and is listed in
  // the missing-requirements checklist) instead of showing blank read-only rows.
  const hasLocation = hasCompleteLocation(record);
  const localizedFieldError = (field: EditableField) => (
    fieldErrorMessage(field, fieldErrors[field], locale, messages)
  );
  const searchArtists = useCallback(
    (query: string) => repository.searchArtists(query),
    [repository],
  );

  const update = <Key extends keyof OwnerExhibition>(key: Key, value: OwnerExhibition[Key]) => {
    if (key === "artMetadata") artMetadataDirty.current = true;
    setRecord((current) => ({ ...current, [key]: value }));
    setSaved(false);
    setDirty(true);
    if (editableFields.includes(key as EditableField)) {
      setFieldErrors((current) => {
        if (!current[key as EditableField]) return current;
        const updated = { ...current };
        delete updated[key as EditableField];
        return updated;
      });
    }
    setMissing([]);
    setError(null);
  };

  const showValidation = (errors: FieldErrors, summary: ValidationSummaryKey): boolean => {
    const nextError = validationSummary(errors, summary);
    setFieldErrors(errors);
    setError(nextError);
    return nextError !== null;
  };

  const persistDraft = async (current: OwnerExhibition) => {
    const updated = await repository.saveExhibitionDraft(
      current.id,
      current.workingVersionId,
      current.revision,
      editablePatch(current, artMetadataDirty.current),
    );
    setRecord(updated);
    onChange(updated);
    setDirty(false);
    artMetadataDirty.current = false;
    return updated;
  };

  const changeQuery = (value: string) => {
    setQuery(value);
    setCandidates([]);
    setSearchCompleted(false);
    setError(null);
  };

  const searchAddress = async (event?: React.FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    const address = query.trim();
    if (!canEdit || address.length < 2 || busy || searching) return;
    setSearching(true);
    setError(null);
    setCandidates([]);
    setSearchCompleted(false);
    try {
      setCandidates((await repository.searchGalleryAddress(address)).slice(0, 3));
      setSearchCompleted(true);
    } catch (cause) {
      setError({ kind: "known", key: errorMessage(cause, "addressSearch") });
    } finally {
      setSearching(false);
    }
  };

  const selectCandidate = (candidate: GalleryGeocodeCandidate) => {
    setRecord((current) => withCandidate(current, candidate));
    setDirty(true);
    setSaved(false);
    setCandidates([]);
    setSearchCompleted(false);
    setMissing([]);
    setFieldErrors((current) => {
      if (!current.latitude && !current.longitude) return current;
      const updated = { ...current };
      delete updated.latitude;
      delete updated.longitude;
      return updated;
    });
    setError(null);
  };

  const clearLocation = () => {
    setRecord((current) => withLocation(current, {
      cityKo: "", cityEn: "", regionKo: "", regionEn: "",
      addressKo: "", addressEn: "", latitude: null, longitude: null,
    }));
    setDirty(true);
    setSaved(false);
    setError(null);
  };

  const save = async () => {
    if (!canEdit || busy) return;
    if (showValidation(
      draftValidationErrors(record),
      "fixBeforeSave",
    )) return;
    setBusy("save");
    setError(null);
    try {
      await persistDraft(record);
      setFieldErrors({});
      setSaved(true);
    } catch (cause) {
      setError({ kind: "known", key: errorMessage(cause, "save") });
    } finally {
      setBusy(null);
    }
  };

  const upload = async (file: File | undefined) => {
    if (!file || !canEdit || busy) return;
    if (dirty && showValidation(
      draftValidationErrors(record),
      "fixBeforeCover",
    )) return;
    setBusy("cover");
    setError(null);
    setCoverError(false);
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
      setCoverError(false);
      setSaved(true);
    } catch (cause) {
      setError({ kind: "known", key: errorMessage(cause, "coverUpload") });
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
      "completeBeforeSubmit",
    );
    setMissing(missingRequirements(validationErrors, !hasReadyCover));
    if (!hasReadyCover) {
      setCoverError(true);
      if (!hasFieldErrors) setError({ kind: "known", key: "addCover" });
    } else {
      setCoverError(false);
    }
    if (hasFieldErrors || !hasReadyCover) {
      return;
    }
    setBusy("submit");
    setError(null);
    setMissing([]);
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
      setCoverError(false);
      setSaved(false);
    } catch (cause) {
      setError({ kind: "known", key: errorMessage(cause, "submit") });
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
      setError({ kind: "known", key: errorMessage(cause, "launch") });
    } finally { setBusy(null); }
  };

  return (
    <main className="workspace exhibition-editor-workspace">
      <div className="editor-heading">
        <div>
          <button className="text-button back-action" type="button" onClick={onBack}>
            {messages.exhibitions.editor.back}
          </button>
          <h1>{messages.exhibitions.editor.title}</h1>
          <p className="editor-status" role="status">
            {statusLabel(record.ownerStatus, messages)}{saved ? ` · ${messages.exhibitions.editor.savedSuffix}` : ""}
          </p>
          {canEdit && <p className="required-note">{messages.exhibitions.editor.requiredNote}</p>}
        </div>
        {canEdit && (
          <button className="standard-button editor-save" type="button" onClick={() => void save()} disabled={Boolean(busy)}>
            {busy === "save" ? messages.exhibitions.editor.saving : messages.exhibitions.editor.save}
          </button>
        )}
      </div>

      {record.reviewNotes && (
        <section className="review-note">
          <strong>{messages.exhibitions.editor.changesRequested}</strong>
          <p>{record.reviewNotes}</p>
        </section>
      )}

      <div className="editor-columns">
        <div className="editor-fields">
          <section className="editor-section">
            <h2>{messages.exhibitions.editor.exhibition}</h2>
            <div className="field-pair">
              <Field label={messages.exhibitions.fields.nameKo} value={record.nameKo} required error={localizedFieldError("nameKo")} disabled={!canEdit} onChange={(value) => update("nameKo", value)} />
              <Field label={messages.exhibitions.fields.nameEn} value={record.nameEn} required error={localizedFieldError("nameEn")} disabled={!canEdit} onChange={(value) => update("nameEn", value)} />
            </div>
            <div className="date-pair">
              <Field label={messages.exhibitions.fields.openingDate} type="date" value={record.openingDate} required error={localizedFieldError("openingDate")} disabled={!canEdit} onChange={(value) => update("openingDate", value)} />
              <Field label={messages.exhibitions.fields.closingDate} type="date" value={record.closingDate} required error={localizedFieldError("closingDate")} disabled={!canEdit} onChange={(value) => update("closingDate", value)} />
            </div>
            <TextAreaField label={messages.exhibitions.fields.descriptionKo} value={record.descriptionKo} error={localizedFieldError("descriptionKo")} disabled={!canEdit} onChange={(value) => update("descriptionKo", value)} />
            <TextAreaField label={messages.exhibitions.fields.descriptionEn} value={record.descriptionEn} error={localizedFieldError("descriptionEn")} disabled={!canEdit} onChange={(value) => update("descriptionEn", value)} />
          </section>

          <section className="editor-section">
            <h2>{messages.exhibitions.art.heading}</h2>
            <ExhibitionArtMetadataEditor
              metadata={record.artMetadata}
              terms={artTerms}
              termsError={artTermsError}
              disabled={!canEdit || Boolean(busy)}
              onChange={(metadata) => update("artMetadata", metadata)}
              onSearchArtists={searchArtists}
            />
          </section>

          <section className="editor-section">
            <h2>{messages.exhibitions.editor.visitDetails}</h2>
            <div className="field-pair">
              <Field label={messages.exhibitions.fields.venueNameKo} value={record.venueNameKo} required error={localizedFieldError("venueNameKo")} disabled={!canEdit} onChange={(value) => update("venueNameKo", value)} />
              <Field label={messages.exhibitions.fields.venueNameEn} value={record.venueNameEn} required error={localizedFieldError("venueNameEn")} disabled={!canEdit} onChange={(value) => update("venueNameEn", value)} />
            </div>
            <div className="location-block">
              <h3 className="is-required-heading">{messages.exhibitions.editor.location}</h3>
              <p className="location-help">{messages.exhibitions.editor.locationHelp}</p>
              {canEdit && (
                <form className="gallery-address-search" onSubmit={(event) => void searchAddress(event)}>
                  <div className="field">
                    <label htmlFor="exhibition-address-query">{messages.exhibitions.editor.findAddress}</label>
                    <input
                      id="exhibition-address-query"
                      type="search"
                      value={query}
                      disabled={searching}
                      placeholder={messages.exhibitions.editor.addressPlaceholder}
                      onChange={(event) => changeQuery(event.target.value)}
                    />
                  </div>
                  <button className="standard-button" type="submit" disabled={searching || query.trim().length < 2}>
                    {searching ? messages.exhibitions.editor.searching : messages.exhibitions.editor.searchAddress}
                  </button>
                </form>
              )}
              {candidates.length > 0 && (
                <ul className="address-candidates" aria-label={messages.exhibitions.editor.addressMatches} aria-live="polite">
                  {candidates.map((candidate) => (
                    <li key={`${candidate.latitude}:${candidate.longitude}:${candidate.roadAddress}`}>
                      <div>
                        <strong>{candidate.roadAddress || candidate.jibunAddress}</strong>
                        <span>{candidate.englishAddress}</span>
                      </div>
                      <button className="outlined-button" type="button" onClick={() => selectCandidate(candidate)}>
                        {messages.exhibitions.editor.useAddress(candidate.roadAddress || candidate.jibunAddress)}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              {searchCompleted && candidates.length === 0 && (
                <p className="address-search-status" role="status">
                  {messages.exhibitions.editor.noMatches}
                </p>
              )}
              {hasLocation ? (
                <>
                  <div className="field-pair">
                    <ReadOnlyField label={messages.exhibitions.fields.cityKo} value={record.cityKo} />
                    <ReadOnlyField label={messages.exhibitions.fields.cityEn} value={record.cityEn} />
                  </div>
                  <div className="field-pair">
                    <ReadOnlyField label={messages.exhibitions.fields.regionKo} value={record.regionKo} />
                    <ReadOnlyField label={messages.exhibitions.fields.regionEn} value={record.regionEn} />
                  </div>
                  <ReadOnlyField label={messages.exhibitions.fields.addressKo} value={record.addressKo} />
                  <ReadOnlyField label={messages.exhibitions.fields.addressEn} value={record.addressEn} />
                  <div className="field-pair">
                    <ReadOnlyField label={messages.exhibitions.fields.latitude} value={record.latitude?.toString() ?? ""} />
                    <ReadOnlyField label={messages.exhibitions.fields.longitude} value={record.longitude?.toString() ?? ""} />
                  </div>
                  {canEdit && (
                    <button className="text-button clear-location" type="button" onClick={clearLocation}>
                      {messages.exhibitions.editor.clearAddress}
                    </button>
                  )}
                </>
              ) : (
                <p className="location-empty" role={fieldErrors.latitude ? "alert" : undefined}>
                  {fieldErrors.latitude
                    ? messages.exhibitions.editor.addressRequired
                    : messages.exhibitions.editor.noAddress}
                </p>
              )}
            </div>
            <Field label={messages.exhibitions.fields.hours} value={record.hours} required error={localizedFieldError("hours")} disabled={!canEdit} onChange={(value) => update("hours", value)} />
            <Field label={messages.exhibitions.fields.contact} value={record.contact} error={localizedFieldError("contact")} disabled={!canEdit} onChange={(value) => update("contact", value)} />
            <div className="date-pair">
              <Field label={messages.exhibitions.fields.receptionDate} type="date" value={record.receptionDate} error={localizedFieldError("receptionDate")} disabled={!canEdit} onChange={(value) => update("receptionDate", value)} />
              <Field label={messages.exhibitions.fields.receptionStartTime} type="time" value={record.receptionStartTime} error={localizedFieldError("receptionStartTime")} disabled={!canEdit} onChange={(value) => update("receptionStartTime", value)} />
            </div>
            <Field label={messages.exhibitions.fields.ticketUrl} type="url" value={record.ticketUrl} error={localizedFieldError("ticketUrl")} disabled={!canEdit} onChange={(value) => update("ticketUrl", value)} />
          </section>
        </div>

        <aside className="editor-media">
          <h2 className="is-required-heading">{messages.exhibitions.editor.cover}</h2>
          {record.cover?.previewUrl ? (
            <img src={record.cover.previewUrl} alt={messages.exhibitions.editor.coverAlt} />
          ) : (
            <div className="cover-placeholder">{messages.exhibitions.editor.noCover}</div>
          )}
          {canEdit && (
            <label className="outlined-button file-button">
              <span>{busy === "cover" ? messages.exhibitions.editor.uploading : messages.exhibitions.editor.chooseCover}</span>
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                aria-label={messages.exhibitions.editor.chooseCover}
                aria-required="true"
                aria-invalid={coverError ? "true" : undefined}
                disabled={Boolean(busy)}
                onChange={(event) => void upload(event.target.files?.[0])}
              />
            </label>
          )}
          <p className="media-help">{messages.exhibitions.editor.coverHelp}</p>
          {coverError && <p className="field-inline-error cover-error">! {messages.exhibitions.validation.coverRequired}</p>}

          <div className="submission-panel">
            <h2>{messages.exhibitions.editor.review}</h2>
            {record.ownerStatus === "submitted" ? (
              <p>{messages.exhibitions.editor.inReview}</p>
            ) : record.ownerStatus === "published" ? (
              <>
                <p>{messages.exhibitions.statuses.published}</p>
                <a href={publicExhibitionUrl(record, publicSiteUrl)}>{messages.exhibitions.editor.viewPublic}</a>
                <p className="submission-help">{messages.exhibitions.editor.publicPageDelay}</p>
                <ExhibitionQrCard
                  exhibition={{
                    id: record.id,
                    nameKo: record.nameKo,
                    nameEn: record.nameEn,
                  }}
                  posterUrl={record.cover?.status === "published" ? record.cover.publicUrl : null}
                  publicSiteUrl={publicSiteUrl}
                />
                <h2 className="impact-heading">{messages.exhibitions.editor.publicImpact}</h2>
                <ImpactSummary exhibition={record} />
                <button className="primary-button launch-button" type="button" disabled={Boolean(busy)} onClick={() => void launch()}>
                  {busy === "launch" ? messages.exhibitions.editor.activatingLaunch : messages.exhibitions.editor.activateLaunch}
                </button>
                {launchNotice && (
                  <p className="submission-help" role="status">
                    {messages.exhibitions.editor.launchSoon}
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
                  {busy === "submit" ? messages.exhibitions.editor.submitting : messages.exhibitions.editor.submit}
                </button>
                {membershipStatus !== "active" && (
                  <p className="submission-help">{messages.exhibitions.errors.verification}</p>
                )}
              </>
            ) : (
              <p>{statusLabel(record.ownerStatus, messages)}</p>
            )}
          </div>
          {missing.length > 0 && (
            <div className="submission-missing" role="alert">
              <strong>{messages.exhibitions.editor.addBeforeSubmit}</strong>
              <ul>
                {missing.map((item) => (
                  <li key={item}>
                    {item === "location"
                      ? messages.exhibitions.validation.locationRequirement
                      : item === "cover"
                        ? messages.exhibitions.validation.coverRequirement
                        : messages.exhibitions.fields[item]}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {error && <p className="field-error editor-error" role="alert">! {editorErrorMessage(error, locale, messages)}</p>}
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
  onNavigateGalleryInfo = () => undefined,
  galleryInfoEnabled = true,
  launchKitEnabled = false,
  publicSiteUrl = "https://gallrmap.com",
}: {
  membershipStatus: MembershipStatus;
  repository: ExhibitionRepository;
  onSignOut: () => void;
  onNavigateLaunch?: () => void;
  onNavigateGalleryInfo?: () => void;
  galleryInfoEnabled?: boolean;
  launchKitEnabled?: boolean;
  publicSiteUrl?: string;
}) {
  const { locale, messages } = useLocale();
  const [records, setRecords] = useState<OwnerExhibition[]>([]);
  const [selected, setSelected] = useState<OwnerExhibition | null>(null);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [pendingRemoval, setPendingRemoval] = useState<OwnerExhibition | null>(null);
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState<ExhibitionErrorKey | null>(null);
  const [artTerms, setArtTerms] = useState<ArtTerm[] | null>(null);
  const [artTermsError, setArtTermsError] = useState(false);
  const createButtonRef = useRef<HTMLButtonElement | null>(null);
  const removalTriggerRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    let current = true;
    void repository.listExhibitions()
      .then((result) => {
        if (current) setRecords(result);
      })
      .catch((cause) => {
        if (current) setError(errorMessage(cause, "load"));
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => { current = false; };
  }, [repository]);

  useEffect(() => {
    let current = true;
    setArtTermsError(false);
    void repository.listArtTerms()
      .then((terms) => {
        if (current) setArtTerms(terms);
      })
      .catch(() => {
        if (!current) return;
        setArtTerms(null);
        setArtTermsError(true);
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
      setError(errorMessage(cause, "create"));
    } finally {
      setCreating(false);
    }
  };

  const removeFromList = async () => {
    if (!pendingRemoval || removing) return;
    setRemoving(true);
    setError(null);
    try {
      await repository.hideExhibition(
        pendingRemoval.id,
        pendingRemoval.workingVersionId,
        pendingRemoval.revision,
      );
      setRecords((current) => current.filter((item) => item.id !== pendingRemoval.id));
      setPendingRemoval(null);
      queueMicrotask(() => createButtonRef.current?.focus());
    } catch (cause) {
      setError(removalErrorMessage(cause));
      setPendingRemoval(null);
      queueMicrotask(() => removalTriggerRef.current?.focus());
    } finally {
      setRemoving(false);
    }
  };

  const closeRemovalDialog = () => {
    setPendingRemoval(null);
    queueMicrotask(() => removalTriggerRef.current?.focus());
  };

  if (selected) {
    return (
      <OwnerShell active="exhibitions" galleryInfoEnabled={galleryInfoEnabled} launchKitEnabled={launchKitEnabled} onNavigate={(target) => target === "launch" ? onNavigateLaunch() : target === "gallery-info" && onNavigateGalleryInfo()} onSignOut={onSignOut}>
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
          artTerms={artTerms}
          artTermsError={artTermsError}
        />
      </OwnerShell>
    );
  }

  return (
    <OwnerShell active="exhibitions" galleryInfoEnabled={galleryInfoEnabled} launchKitEnabled={launchKitEnabled} onNavigate={(target) => target === "launch" ? onNavigateLaunch() : target === "gallery-info" && onNavigateGalleryInfo()} onSignOut={onSignOut}>
      <main className="workspace dashboard-workspace">
        <div className="dashboard-heading">
          <div>
            <h1>{messages.exhibitions.dashboard.title}</h1>
            <p>{messages.exhibitions.dashboard.intro}</p>
          </div>
          <button ref={createButtonRef} className="primary-button dashboard-create" type="button" disabled={creating} onClick={() => void create()}>
            {creating ? messages.exhibitions.dashboard.creating : messages.exhibitions.dashboard.create}
          </button>
        </div>
        {membershipStatus === "pending" && (
          <section className="claim-notice">
            <h2>{messages.exhibitions.dashboard.claimPending}</h2>
            <p>{messages.exhibitions.dashboard.claimPendingBody}</p>
          </section>
        )}
        {error && <p className="field-error dashboard-error" role="alert">! {messages.exhibitions.errors[error]}</p>}
        {loading ? (
          <p className="workspace-loading">{messages.exhibitions.dashboard.loading}</p>
        ) : records.length === 0 ? (
          <section className="dashboard-empty">
            <h2>{messages.exhibitions.dashboard.emptyTitle}</h2>
            <p>{messages.exhibitions.dashboard.emptyBody}</p>
          </section>
        ) : (
          <div className="exhibition-list">
            <div className="exhibition-list-head" aria-hidden="true">
              <span>{messages.exhibitions.dashboard.columnExhibition}</span><span>{messages.exhibitions.dashboard.columnDates}</span><span>{messages.exhibitions.dashboard.columnStatus}</span><span>{messages.exhibitions.dashboard.columnImpact}</span><span>{messages.exhibitions.dashboard.columnUpdated}</span>
            </div>
            {records.map((record) => (
              <article className="exhibition-row" key={record.id}>
                <div className="exhibition-identity">
                  <button
                    className="exhibition-title-button"
                    type="button"
                    onClick={() => setSelected(record)}
                  >
                    {localizeBilingual(record.nameKo, record.nameEn, locale) || messages.exhibitions.dashboard.untitled}
                  </button>
                  {alternateBilingual(record.nameKo, record.nameEn, locale) && (
                    <p>{alternateBilingual(record.nameKo, record.nameEn, locale)}</p>
                  )}
                  {record.reviewNotes && <p className="row-review-note">{record.reviewNotes}</p>}
                  {record.ownerStatus === "published" && <a href={publicExhibitionUrl(record, publicSiteUrl)}>{messages.exhibitions.editor.viewPublic}</a>}
                  <button
                    className="row-remove"
                    type="button"
                    aria-label={messages.exhibitions.dashboard.removeAria(localizeBilingual(record.nameKo, record.nameEn, locale) || messages.exhibitions.dashboard.untitled)}
                    onClick={(event) => {
                      removalTriggerRef.current = event.currentTarget;
                      setPendingRemoval(record);
                    }}
                  >
                    {messages.exhibitions.dashboard.remove}
                  </button>
                </div>
                <span className="row-dates">{record.openingDate ? formatDateOnly(record.openingDate, locale) : "—"}<br />{record.closingDate ? formatDateOnly(record.closingDate, locale) : "—"}</span>
                <span className="row-status">{statusLabel(record.ownerStatus, messages)}</span>
                <span className="row-impact"><ImpactSummary exhibition={record} /></span>
                <span className="row-updated">{record.updatedAt ? formatTimestampDate(record.updatedAt, locale) : "—"}</span>
              </article>
            ))}
          </div>
        )}
        {pendingRemoval && (
          <div className="owner-confirm-backdrop">
            <section
              className="owner-confirm-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="remove-exhibition-title"
              onKeyDown={(event) => {
                if (event.key === "Escape" && !removing) {
                  event.preventDefault();
                  closeRemovalDialog();
                  return;
                }
                if (event.key !== "Tab") return;
                const focusable = Array.from(
                  event.currentTarget.querySelectorAll<HTMLButtonElement>("button:not(:disabled)"),
                );
                const first = focusable.at(0);
                const last = focusable.at(-1);
                if (event.shiftKey && document.activeElement === first) {
                  event.preventDefault();
                  last?.focus();
                } else if (!event.shiftKey && document.activeElement === last) {
                  event.preventDefault();
                  first?.focus();
                }
              }}
            >
              <LocaleToggle className="dialog-locale-toggle" />
              <h2 id="remove-exhibition-title">{messages.exhibitions.dashboard.removeTitle}</h2>
              <p>{messages.exhibitions.dashboard.removeBody(statusLabel(pendingRemoval.ownerStatus, messages))}</p>
              <div className="owner-confirm-actions">
                <button className="outlined-button" type="button" autoFocus disabled={removing} onClick={closeRemovalDialog}>
                  {messages.exhibitions.dashboard.cancel}
                </button>
                <button className="standard-button" type="button" disabled={removing} onClick={() => void removeFromList()}>
                  {removing ? messages.exhibitions.dashboard.removing : messages.exhibitions.dashboard.remove}
                </button>
              </div>
            </section>
          </div>
        )}
      </main>
    </OwnerShell>
  );
}
