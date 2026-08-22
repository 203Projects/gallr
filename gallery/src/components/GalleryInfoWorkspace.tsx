import { useEffect, useId, useState } from "react";
import type {
  GalleryGeocodeCandidate,
  GalleryInfo,
  GalleryInfoPatch,
  OwnerRepository,
} from "../domain";
import { useLocale, type PortalMessages } from "../i18n";
import { OwnerShell, type OwnerWorkspaceTarget } from "./OwnerShell";

const TEXT_LIMITS = {
  nameKo: 300,
  nameEn: 300,
  venueNameKo: 300,
  venueNameEn: 300,
  hours: 1_000,
  contact: 1_000,
} as const;

type EditableTextField = keyof typeof TEXT_LIMITS;

type GalleryInfoErrorKey = keyof PortalMessages["galleryInfo"]["errors"];

const galleryInfoErrorExplanations: ReadonlyArray<readonly [string, GalleryInfoErrorKey]> = [
  ["revision_conflict", "revision"],
  ["gallery_info_access_denied", "access"],
  ["geocode_access_required", "geocodeAccess"],
  ["authentication_required", "authentication"],
  ["gallery_info_required", "required"],
  ["gallery_info_location_invalid", "location"],
  ["gallery_info_field_not_allowed", "unsupportedField"],
  ["gallery_info_field_invalid", "unsupportedFormat"],
  ["gallery_info_patch_invalid", "invalidForm"],
  ["geocoding_rate_limited", "rateLimited"],
];

function errorMessage(cause: unknown, fallback: GalleryInfoErrorKey): GalleryInfoErrorKey {
  const raw = cause instanceof Error && cause.message ? cause.message : "";
  for (const [code, explanation] of galleryInfoErrorExplanations) {
    if (raw.includes(code)) return explanation;
  }
  return fallback;
}

function patchFrom(info: GalleryInfo): GalleryInfoPatch {
  return {
    nameKo: info.nameKo,
    nameEn: info.nameEn,
    venueNameKo: info.venueNameKo,
    venueNameEn: info.venueNameEn,
    cityKo: info.cityKo,
    cityEn: info.cityEn,
    regionKo: info.regionKo,
    regionEn: info.regionEn,
    addressKo: info.addressKo,
    addressEn: info.addressEn,
    latitude: info.latitude,
    longitude: info.longitude,
    hours: info.hours,
    contact: info.contact,
  };
}

function withCandidate(info: GalleryInfo, candidate: GalleryGeocodeCandidate): GalleryInfo {
  return {
    ...info,
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

function restoreSavedAddress(current: GalleryInfo, saved: GalleryInfo): GalleryInfo {
  return {
    ...current,
    cityKo: saved.cityKo,
    cityEn: saved.cityEn,
    regionKo: saved.regionKo,
    regionEn: saved.regionEn,
    addressKo: saved.addressKo,
    addressEn: saved.addressEn,
    latitude: saved.latitude,
    longitude: saved.longitude,
  };
}

function TextField({
  label,
  value,
  maxLength,
  required = false,
  readOnly = false,
  onChange,
}: {
  label: string;
  value: string;
  maxLength?: number;
  required?: boolean;
  readOnly?: boolean;
  onChange?: (value: string) => void;
}) {
  const id = useId();
  return (
    <div className={`field${required ? " is-required" : ""}`}>
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        value={value}
        maxLength={maxLength}
        required={required}
        readOnly={readOnly}
        onChange={(event) => onChange?.(event.target.value)}
      />
    </div>
  );
}

function validate(info: GalleryInfo): GalleryInfoErrorKey | null {
  for (const field of Object.keys(TEXT_LIMITS) as EditableTextField[]) {
    if (info[field].length > TEXT_LIMITS[field]) {
      return "characterLimit";
    }
  }
  if (
    !info.nameKo.trim() || !info.nameEn.trim() ||
    !info.venueNameKo.trim() || !info.venueNameEn.trim() ||
    !info.cityKo.trim() || !info.cityEn.trim() ||
    !info.regionKo.trim() || !info.regionEn.trim() ||
    !info.addressKo.trim() || !info.addressEn.trim() ||
    info.latitude === null || info.longitude === null
  ) {
    return "required";
  }
  if (
    !Number.isFinite(info.latitude) || !Number.isFinite(info.longitude) ||
    info.latitude < -90 || info.latitude > 90 ||
    info.longitude < -180 || info.longitude > 180
  ) {
    return "location";
  }
  return null;
}

export function GalleryInfoWorkspace({
  repository,
  onNavigate,
  onSignOut,
  launchKitEnabled = false,
}: {
  repository: Pick<OwnerRepository, "getGalleryInfo" | "saveGalleryInfo" | "searchGalleryAddress">;
  onNavigate: (target: OwnerWorkspaceTarget) => void;
  onSignOut: () => void;
  launchKitEnabled?: boolean;
}) {
  const { messages } = useLocale();
  const [savedInfo, setSavedInfo] = useState<GalleryInfo | null>(null);
  const [info, setInfo] = useState<GalleryInfo | null>(null);
  const [query, setQuery] = useState("");
  const [candidates, setCandidates] = useState<GalleryGeocodeCandidate[]>([]);
  const [searchCompleted, setSearchCompleted] = useState(false);
  const [selectedCandidate, setSelectedCandidate] = useState<GalleryGeocodeCandidate | null>(null);
  const [busy, setBusy] = useState<"search" | "save" | null>(null);
  const [error, setError] = useState<GalleryInfoErrorKey | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let current = true;
    void repository.getGalleryInfo()
      .then((record) => {
        if (!current) return;
        setSavedInfo(record);
        setInfo(record);
      })
      .catch((cause) => {
        if (current) setError(errorMessage(cause, "load"));
      });
    return () => { current = false; };
  }, [repository]);

  const updateText = (field: EditableTextField, value: string) => {
    setInfo((current) => current ? { ...current, [field]: value } : current);
    setSaved(false);
    setError(null);
  };

  const changeQuery = (value: string) => {
    setQuery(value);
    setCandidates([]);
    setSearchCompleted(false);
    setError(null);
    if (selectedCandidate && savedInfo) {
      setInfo((current) => current ? restoreSavedAddress(current, savedInfo) : current);
      setSelectedCandidate(null);
      setSaved(false);
    }
  };

  const search = async (event?: React.FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    const address = query.trim();
    if (address.length < 2 || busy) return;
    setBusy("search");
    setError(null);
    setCandidates([]);
    setSearchCompleted(false);
    try {
      setCandidates((await repository.searchGalleryAddress(address)).slice(0, 3));
      setSearchCompleted(true);
    } catch (cause) {
      setError(errorMessage(cause, "addressSearch"));
    } finally {
      setBusy(null);
    }
  };

  const selectCandidate = (candidate: GalleryGeocodeCandidate) => {
    setInfo((current) => current ? withCandidate(current, candidate) : current);
    setSelectedCandidate(candidate);
    setSaved(false);
    setError(null);
  };

  const save = async () => {
    if (!info || busy) return;
    const validationError = validate(info);
    if (validationError) {
      setError(validationError);
      return;
    }
    setBusy("save");
    setError(null);
    try {
      const updated = await repository.saveGalleryInfo(info.revision, patchFrom(info));
      setSavedInfo(updated);
      setInfo(updated);
      setSelectedCandidate(null);
      setCandidates([]);
      setSearchCompleted(false);
      setSaved(true);
    } catch (cause) {
      setError(errorMessage(cause, "save"));
    } finally {
      setBusy(null);
    }
  };

  return (
    <OwnerShell
      active="gallery-info"
      launchKitEnabled={launchKitEnabled}
      onNavigate={onNavigate}
      onSignOut={onSignOut}
    >
      <main className="workspace gallery-info-workspace">
        <div className="gallery-info-heading">
          <div>
            <h1>{messages.galleryInfo.title}</h1>
            <p>{messages.galleryInfo.intro}</p>
            {saved && <p className="editor-status" role="status">{messages.galleryInfo.saved}</p>}
          </div>
        </div>

        {info ? (
          <div className="gallery-info-form">
            <section className="editor-section">
              <h2>{messages.galleryInfo.galleryVenue}</h2>
              <div className="field-pair">
                <TextField label={messages.galleryInfo.nameKo} value={info.nameKo} maxLength={300} required onChange={(value) => updateText("nameKo", value)} />
                <TextField label={messages.galleryInfo.nameEn} value={info.nameEn} maxLength={300} required onChange={(value) => updateText("nameEn", value)} />
              </div>
              <div className="field-pair">
                <TextField label={messages.galleryInfo.venueKo} value={info.venueNameKo} maxLength={300} required onChange={(value) => updateText("venueNameKo", value)} />
                <TextField label={messages.galleryInfo.venueEn} value={info.venueNameEn} maxLength={300} required onChange={(value) => updateText("venueNameEn", value)} />
              </div>
            </section>

            <section className="editor-section gallery-location-section">
              <h2>{messages.galleryInfo.addressMap}</h2>
              <p>{messages.galleryInfo.addressHelp}</p>
              <form className="gallery-address-search" onSubmit={(event) => void search(event)}>
                <div className="field">
                  <label htmlFor="gallery-address-query">{messages.galleryInfo.findAddress}</label>
                  <input
                    id="gallery-address-query"
                    type="search"
                    value={query}
                    disabled={busy !== null}
                    onChange={(event) => changeQuery(event.target.value)}
                  />
                </div>
                <button className="standard-button" type="submit" disabled={busy !== null || query.trim().length < 2}>
                  {busy === "search" ? messages.galleryInfo.searching : messages.galleryInfo.searchAddress}
                </button>
              </form>
              {candidates.length > 0 && (
                <ul className="address-candidates" aria-label={messages.galleryInfo.addressMatches} aria-live="polite">
                  {candidates.map((candidate) => (
                    <li key={`${candidate.latitude}:${candidate.longitude}:${candidate.roadAddress}`}>
                      <div>
                        <strong>{candidate.roadAddress || candidate.jibunAddress}</strong>
                        <span>{candidate.englishAddress}</span>
                      </div>
                      <button className="outlined-button" type="button" onClick={() => selectCandidate(candidate)}>
                        {messages.galleryInfo.useAddress(candidate.roadAddress || candidate.jibunAddress)}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              {searchCompleted && candidates.length === 0 && (
                <p className="address-search-status" role="status">
                  {messages.galleryInfo.noMatches}
                </p>
              )}
              <div className="field-pair">
                <TextField label={messages.galleryInfo.cityKo} value={info.cityKo} readOnly />
                <TextField label={messages.galleryInfo.cityEn} value={info.cityEn} readOnly />
              </div>
              <div className="field-pair">
                <TextField label={messages.galleryInfo.regionKo} value={info.regionKo} readOnly />
                <TextField label={messages.galleryInfo.regionEn} value={info.regionEn} readOnly />
              </div>
              <TextField label={messages.galleryInfo.addressKo} value={info.addressKo} readOnly />
              <TextField label={messages.galleryInfo.addressEn} value={info.addressEn} readOnly />
              <div className="field-pair">
                <TextField label={messages.galleryInfo.latitude} value={info.latitude?.toString() ?? ""} readOnly />
                <TextField label={messages.galleryInfo.longitude} value={info.longitude?.toString() ?? ""} readOnly />
              </div>
            </section>

            <section className="editor-section">
              <h2>{messages.galleryInfo.visitDetails}</h2>
              <TextField label={messages.galleryInfo.hours} value={info.hours} maxLength={1_000} onChange={(value) => updateText("hours", value)} />
              <TextField label={messages.galleryInfo.contact} value={info.contact} maxLength={1_000} onChange={(value) => updateText("contact", value)} />
            </section>

            {error && <p className="field-error gallery-info-error" role="alert">! {messages.galleryInfo.errors[error]}</p>}
            <button className="standard-button gallery-info-save" type="button" disabled={busy !== null} onClick={() => void save()}>
              {busy === "save" ? messages.galleryInfo.saving : messages.galleryInfo.save}
            </button>
          </div>
        ) : error ? (
          <p className="field-error" role="alert">! {messages.galleryInfo.errors[error]}</p>
        ) : (
          <p className="workspace-loading">{messages.galleryInfo.loading}</p>
        )}
      </main>
    </OwnerShell>
  );
}
