import type {
  AdminExhibition,
  AdminExhibitionLookups,
  AdminExhibitionValidation,
  AdminGeocodeCandidate,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaRole,
  InspectorSection,
  PublishReadiness,
} from "../domain";
import type { AdminGeocodingMode } from "../services/AdminGeocodingService";
import { CloseIcon, HistoryIcon, ImageIcon, MoreIcon } from "./Icons";
import { MediaEditor } from "./MediaEditor";

interface ExhibitionInspectorProps {
  exhibition: AdminExhibition;
  section: InspectorSection;
  saveState:
    | "saved"
    | "dirty"
    | "invalid"
    | "saving"
    | "error"
    | "conflict";
  readiness: PublishReadiness;
  validation: AdminExhibitionValidation;
  lookups: AdminExhibitionLookups | null;
  lookupsLoading: boolean;
  lookupsError: string | null;
  publishAllowed: boolean;
  lifecycleBusy: boolean;
  media: AdminMediaAsset[];
  mediaLoading: boolean;
  mediaBusy: boolean;
  mediaError: string | null;
  mediaEditable: boolean;
  mediaReadOnlyReason: string | null;
  geocodeCandidates: AdminGeocodeCandidate[];
  geocodeLoading: boolean;
  geocodeError: string | null;
  geocodingMode: AdminGeocodingMode;
  onSectionChange: (section: InspectorSection) => void;
  onClose: () => void;
  onChange: (
    field: keyof AdminExhibition,
    value: string | boolean | null,
  ) => void;
  onPreview: () => void;
  onPublish: () => void;
  onArchive: () => void;
  onRestore: () => void;
  onMediaUpload: (file: File, role: AdminMediaRole) => void;
  onMediaMetadataSave: (
    assetId: string,
    patch: AdminMediaMetadataPatch,
  ) => void;
  onMediaReorder: (orderedAssetIds: string[]) => void;
  onMediaDetach: (assetId: string) => void;
  onMediaErrorClear: () => void;
  onFindCoordinates: () => void;
  onApplyGeocodeCandidate: (candidate: AdminGeocodeCandidate) => void;
}

const sections: InspectorSection[] = [
  "Basics",
  "Venue",
  "Schedule",
  "Media",
  "Curation",
];

function Field({
  label,
  value,
  onChange,
  type = "text",
  inputMode,
  placeholder,
  required = false,
  disabled = false,
  invalid = false,
  describedBy,
  error = null,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  inputMode?: "decimal" | "email" | "numeric" | "search" | "tel" | "text" | "url";
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
  invalid?: boolean;
  describedBy?: string;
  error?: string | null;
}) {
  return (
    <label className="field">
      <span>
        {label}
        {required ? " *" : ""}
      </span>
      <input
        type={type}
        value={value}
        required={required}
        disabled={disabled}
        inputMode={inputMode}
        placeholder={placeholder}
        aria-invalid={invalid || Boolean(error)}
        aria-describedby={describedBy}
        onChange={(event) => onChange(event.target.value)}
      />
      {error && (
        <small className="field-error" role="alert">
          {error}
        </small>
      )}
    </label>
  );
}

function SelectField({
  label,
  value,
  placeholder,
  options,
  disabled,
  onChange,
}: {
  label: string;
  value: string;
  placeholder: string;
  options: Array<{ value: string; label: string }>;
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  const hasCurrentOption = value === "" || options.some((option) => option.value === value);
  return (
    <label className="field">
      <span>{label}</span>
      <select
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="">{placeholder}</option>
        {!hasCurrentOption && <option value={value}>{value} (unavailable)</option>}
        {options.map((option) => (
          <option value={option.value} key={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

function TextAreaField({
  label,
  value,
  onChange,
  disabled = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <textarea
        rows={3}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

function SaveState({ state }: { state: ExhibitionInspectorProps["saveState"] }) {
  const labels = {
    saved: "All changes saved",
    dirty: "Unsaved changes",
    invalid: "Fix highlighted fields to save",
    saving: "Saving…",
    error: "! Save failed",
    conflict: "! A newer revision exists",
  };
  return (
    <span
      className={
        state === "error" || state === "conflict" || state === "invalid"
          ? "save-error"
          : "muted"
      }
    >
      {labels[state]}
    </span>
  );
}

export function ExhibitionInspector({
  exhibition,
  section,
  saveState,
  readiness,
  validation,
  lookups,
  lookupsLoading,
  lookupsError,
  publishAllowed,
  lifecycleBusy,
  media,
  mediaLoading,
  mediaBusy,
  mediaError,
  mediaEditable,
  mediaReadOnlyReason,
  geocodeCandidates,
  geocodeLoading,
  geocodeError,
  geocodingMode,
  onSectionChange,
  onClose,
  onChange,
  onPreview,
  onPublish,
  onArchive,
  onRestore,
  onMediaUpload,
  onMediaMetadataSave,
  onMediaReorder,
  onMediaDetach,
  onMediaErrorClear,
  onFindCoordinates,
  onApplyGeocodeCandidate,
}: ExhibitionInspectorProps) {
  const contentReadOnly = exhibition.status === "Archived" || mediaBusy;
  const publishDisabled =
    !publishAllowed ||
    mediaBusy ||
    mediaLoading ||
    saveState !== "saved" ||
    !Object.values(readiness).every(Boolean);
  const lifecycleDisabled =
    !publishAllowed || lifecycleBusy || mediaBusy || saveState !== "saved";
  const eventOptions = (lookups?.events ?? []).map((event) => ({
    value: event.id,
    label: `${event.shortLabel || event.nameKo || event.nameEn || event.id} · ${event.startDate}${event.isActive ? "" : " · inactive"}`,
  }));
  const editorOptions = (lookups?.editors ?? []).map((editor) => ({
    value: editor.id,
    label: `${editor.nameKo || editor.nameEn || editor.id}${editor.titleKo ? ` · ${editor.titleKo}` : ""}${editor.isActive ? "" : " · inactive"}`,
  }));
  const lookupsDisabled = contentReadOnly || lookupsLoading || lookupsError !== null;
  const geocodeStatus = geocodeLoading
    ? "Searching for address matches…"
    : geocodeCandidates.length > 0
      ? `${geocodeCandidates.length} address ${geocodeCandidates.length === 1 ? "match" : "matches"} found.`
      : "";

  return (
    <aside className="exhibition-inspector" aria-label="Exhibition editor">
      <header className="inspector-header">
        <div className="inspector-title-row">
          <div>
            <h2>{exhibition.nameKo || "Untitled exhibition"}</h2>
            <p>{exhibition.status}</p>
          </div>
          <button
            className="outlined-compact inspector-lifecycle-button"
            type="button"
            disabled={lifecycleDisabled}
            onClick={exhibition.status === "Archived" ? onRestore : onArchive}
            title={
              !publishAllowed
                ? "Publisher access is required."
                : saveState !== "saved"
                  ? "Save the current changes first."
                  : undefined
            }
          >
            <MoreIcon />
            {lifecycleBusy
              ? "Working…"
              : exhibition.status === "Archived"
                ? "Restore"
                : "Archive"}
          </button>
          <button
            className="icon-button inspector-mobile-close"
            type="button"
            aria-label="Close editor"
            onClick={onClose}
          >
            <CloseIcon />
          </button>
        </div>
        <div className="revision-row">
          <span>Version</span>
          <strong>
            v{exhibition.versionNumber} · revision {exhibition.revision}
          </strong>
          <button className="outlined-compact" type="button" disabled>
            <HistoryIcon />
            View history
          </button>
        </div>
        <SaveState state={saveState} />
      </header>

      <div className="inspector-tabs" role="tablist" aria-label="Editor sections">
        {sections.map((item) => (
          <button
            type="button"
            role="tab"
            aria-selected={item === section}
            className={item === section ? "is-active" : ""}
            onClick={() => onSectionChange(item)}
            key={item}
          >
            {item}
          </button>
        ))}
      </div>

      <div className="inspector-content" key={section}>
        {section === "Basics" && (
          <>
            <Field
              label="전시명 (Korean)"
              required
              disabled={contentReadOnly}
              value={exhibition.nameKo}
              onChange={(value) => onChange("nameKo", value)}
            />
            <Field
              label="전시명 (English)"
              value={exhibition.nameEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("nameEn", value)}
            />
            <TextAreaField
              label="소개 (Korean)"
              value={exhibition.descriptionKo}
              disabled={contentReadOnly}
              onChange={(value) => onChange("descriptionKo", value)}
            />
            <TextAreaField
              label="소개 (English)"
              value={exhibition.descriptionEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("descriptionEn", value)}
            />
            <div className="media-label">Cover image</div>
            <div className="basics-cover-row">
              <div className="media-field basics-cover-field">
                {exhibition.coverImageUrl ? (
                  <img src={exhibition.coverImageUrl} alt={exhibition.coverAltEn} />
                ) : (
                  <ImageIcon className="media-placeholder-icon" />
                )}
              </div>
              <div className="basics-cover-actions">
                <button className="outlined-compact" type="button" disabled>
                  Replace
                </button>
                <button className="outlined-compact" type="button" disabled>
                  Remove
                </button>
              </div>
            </div>
            <p className="field-help">Recommended: 1600 × 1067px · JPEG, PNG</p>
          </>
        )}

        {section === "Venue" && (
          <>
            <Field
              label="Venue name (Korean)"
              required
              disabled={contentReadOnly}
              value={exhibition.venueNameKo}
              onChange={(value) => onChange("venueNameKo", value)}
            />
            <Field
              label="Venue name (English)"
              value={exhibition.venueNameEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("venueNameEn", value)}
            />
            <div className="field-pair">
              <Field
                label="City (Korean)"
                required
                disabled={contentReadOnly}
                value={exhibition.cityKo}
                onChange={(value) => onChange("cityKo", value)}
              />
              <Field
                label="Region (Korean)"
                required
                disabled={contentReadOnly}
                value={exhibition.regionKo}
                onChange={(value) => onChange("regionKo", value)}
              />
            </div>
            <Field
              label="Address (Korean)"
              required
              value={exhibition.addressKo}
              disabled={contentReadOnly}
              onChange={(value) => onChange("addressKo", value)}
            />
            <div className="geocode-actions">
              <button
                className="outlined-compact"
                type="button"
                disabled={
                  contentReadOnly ||
                  geocodeLoading ||
                  exhibition.addressKo.trim().length === 0
                }
                onClick={onFindCoordinates}
              >
                {geocodeLoading ? "Searching…" : "Find coordinates"}
              </button>
              <span className="muted">
                {geocodingMode === "fixture"
                  ? "Fixture-only lookup. Sample: 서울 용산구 한남대로 28. No NAVER request is made."
                  : "Searches NAVER Maps; no draft changes until you choose a result."}
              </span>
            </div>
            <div
              className="visually-hidden"
              role="status"
              aria-live="polite"
              aria-atomic="true"
            >
              {geocodeStatus}
            </div>
            {geocodeError && (
              <p className="field-error geocode-error" role="alert">
                {geocodeError}
              </p>
            )}
            {geocodeCandidates.length > 0 && (
              <ul className="geocode-results" aria-label="Address matches">
                {geocodeCandidates.map((candidate) => {
                  const displayAddress =
                    candidate.roadAddress || candidate.jibunAddress;
                  const mapUrl = `https://map.naver.com/v5/search/${encodeURIComponent(displayAddress)}`;
                  return (
                    <li
                      key={`${candidate.latitude}:${candidate.longitude}:${displayAddress}`}
                    >
                      <div>
                        <strong>{displayAddress}</strong>
                        {candidate.jibunAddress &&
                          candidate.jibunAddress !== displayAddress && (
                            <small>{candidate.jibunAddress}</small>
                          )}
                        <small>
                          {candidate.latitude}, {candidate.longitude}
                        </small>
                      </div>
                      <div className="geocode-result-actions">
                        <a
                          className="text-button"
                          href={mapUrl}
                          target="_blank"
                          rel="noreferrer"
                          aria-label={`Review ${displayAddress} on NAVER Maps`}
                        >
                          Review map
                        </a>
                        <button
                          className="outlined-compact"
                          type="button"
                          aria-label={`Use location: ${displayAddress}`}
                          onClick={() => onApplyGeocodeCandidate(candidate)}
                        >
                          Use location
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
            <Field
              label="Address (English)"
              value={exhibition.addressEn}
              disabled={contentReadOnly}
              onChange={(value) => onChange("addressEn", value)}
            />
            <div className="field-pair coordinate-fields">
              <Field
                label="Latitude"
                required
                inputMode="decimal"
                placeholder="37.5665"
                value={exhibition.latitude}
                disabled={contentReadOnly}
                invalid={validation.coordinateError !== null}
                describedBy="coordinate-error"
                onChange={(value) => onChange("latitude", value)}
              />
              <Field
                label="Longitude"
                required
                inputMode="decimal"
                placeholder="126.9780"
                value={exhibition.longitude}
                disabled={contentReadOnly}
                invalid={validation.coordinateError !== null}
                describedBy="coordinate-error"
                onChange={(value) => onChange("longitude", value)}
              />
            </div>
            {validation.coordinateError && (
              <p className="field-error coordinate-error" id="coordinate-error" role="alert">
                {validation.coordinateError}
              </p>
            )}
            <p className="field-help coordinate-help">
              Required to publish. Changing the Korean address clears its coordinates so an old pin cannot be reused accidentally.
            </p>
          </>
        )}

        {section === "Schedule" && (
          <>
            <div className="field-pair">
              <Field
                label="Opening date"
                type="date"
                required
                disabled={contentReadOnly}
                value={exhibition.openingDate}
                onChange={(value) => onChange("openingDate", value)}
              />
              <Field
                label="Closing date"
                type="date"
                required
                disabled={contentReadOnly}
                value={exhibition.closingDate}
                onChange={(value) => onChange("closingDate", value)}
              />
            </div>
            <Field
              label="Hours"
              value={exhibition.hours}
              disabled={contentReadOnly}
              onChange={(value) => onChange("hours", value)}
            />
            <Field
              label="Public contact"
              value={exhibition.contact}
              disabled={contentReadOnly}
              onChange={(value) => onChange("contact", value)}
            />
            <Field
              label="Exhibition ticket URL"
              type="url"
              inputMode="url"
              placeholder="https://tickets.example.com/exhibition"
              value={exhibition.ticketUrl}
              disabled={contentReadOnly}
              error={validation.ticketUrlError}
              onChange={(value) => onChange("ticketUrl", value)}
            />
            <div className="field-pair">
              <Field
                label="Reception date"
                type="date"
                value={exhibition.receptionDate}
                disabled={contentReadOnly}
                onChange={(value) => onChange("receptionDate", value)}
              />
              <Field
                label="Reception time"
                type="time"
                value={exhibition.receptionStartTime}
                disabled={contentReadOnly}
                onChange={(value) => onChange("receptionStartTime", value)}
              />
            </div>
          </>
        )}

        {section === "Media" && (
          <MediaEditor
            media={media}
            loading={mediaLoading}
            busy={mediaBusy}
            error={mediaError}
            editable={mediaEditable}
            readOnlyReason={mediaReadOnlyReason}
            onUpload={onMediaUpload}
            onUpdateMetadata={onMediaMetadataSave}
            onReorder={onMediaReorder}
            onDetach={onMediaDetach}
            onClearError={onMediaErrorClear}
          />
        )}

        {section === "Curation" && (
          <>
            <SelectField
              label="Linked event"
              value={exhibition.eventId}
              placeholder="No linked event"
              options={eventOptions}
              disabled={lookupsDisabled}
              onChange={(value) => onChange("eventId", value)}
            />
            <SelectField
              label="Editorial attribution"
              value={exhibition.editorId}
              placeholder="No editor attribution"
              options={editorOptions}
              disabled={lookupsDisabled}
              onChange={(value) => onChange("editorId", value)}
            />
            {lookupsLoading && <p className="field-help">Loading event and editor choices…</p>}
            {lookupsError && (
              <p className="field-error" role="alert">
                Event and editor choices are unavailable. Existing assignments are preserved.
              </p>
            )}
            <fieldset className="curation-fields">
              <legend>Placement</legend>
              <label>
                <input
                  type="checkbox"
                  disabled={contentReadOnly}
                  checked={exhibition.isFeatured}
                  onChange={(event) => onChange("isFeatured", event.target.checked)}
                />
                Featured in app
              </label>
              <label>
                <input
                  type="checkbox"
                  disabled={contentReadOnly}
                  checked={exhibition.isHomepageFeatured}
                  onChange={(event) =>
                    onChange("isHomepageFeatured", event.target.checked)
                  }
                />
                Featured on homepage
              </label>
            </fieldset>
          </>
        )}
      </div>

      <footer className="inspector-footer">
        <button className="outlined-button" type="button" onClick={onPreview}>
          Preview
        </button>
        <button
          className="accent-button"
          type="button"
          disabled={publishDisabled}
          onClick={onPublish}
          title={
            publishDisabled
              ? !publishAllowed
                ? "Publisher access is required."
                : "Save the draft and complete required fields first."
              : undefined
          }
        >
          Publish
        </button>
      </footer>
    </aside>
  );
}
