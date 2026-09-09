import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import type {
  AdminExhibition,
  AdminExhibitionLookups,
  AdminGeocodeCandidate,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaMutationResult,
  AdminMediaRole,
  AdminSection,
  AdminVenueLookup,
  ExhibitionFilters,
  ExhibitionArtMetadata,
  ExhibitionPatch,
  InspectorSection,
} from "./domain";
import {
  getAdminExhibitionValidation,
  getPublishReadiness,
  matchesExhibitionFilters,
  seoulCalendarDate,
  shouldPreserveCoordinatesForAddressChange,
  sortAdminExhibitions,
} from "./domain";
import { PrimaryNavigation } from "./components/PrimaryNavigation";
import { ExhibitionTable } from "./components/ExhibitionTable";
import { ExhibitionInspector } from "./components/ExhibitionInspector";
import { SubmissionWorkspace } from "./components/SubmissionWorkspace";
import { GalleryClaimsWorkspace } from "./components/GalleryClaimsWorkspace";
import { PromotionWorkspace } from "./components/PromotionWorkspace";
import { EditorOnboardingWorkspace } from "./components/EditorOnboardingWorkspace";
import {
  DeleteDraftDialog,
  DiscardDraftDialog,
  LifecycleDialog,
  PreviewDialog,
  PublishDialog,
} from "./components/Dialogs";
import { SearchIcon } from "./components/Icons";
import { AuthGate } from "./components/AuthGate";
import type { AdminStaffRole } from "./components/AuthGate";
import { EditorPicksWorkspace } from "./components/EditorPicksWorkspace";
import { EditorSelfOnboardingWorkspace } from "./components/EditorSelfOnboardingWorkspace";
import { InMemoryAdminExhibitionRepository } from "./repositories/InMemoryAdminExhibitionRepository";
import { SupabaseAdminExhibitionRepository } from "./repositories/SupabaseAdminExhibitionRepository";
import { SupabaseEditorPickRepository } from "./repositories/SupabaseEditorPickRepository";
import type { EditorPickRepository } from "./repositories/EditorPickRepository";
import { SupabaseEditorSelfOnboardingRepository } from "./repositories/EditorSelfOnboardingRepository";
import type { AdminEditorRepository } from "./repositories/AdminEditorRepository";
import { InMemoryAdminEditorRepository } from "./repositories/InMemoryAdminEditorRepository";
import { SupabaseAdminEditorRepository } from "./repositories/SupabaseAdminEditorRepository";
import {
  type AdminExhibitionRepository,
  DraftDeleteBlockedError,
  type DraftDeleteBlockedReason,
  RevisionConflictError,
} from "./repositories/AdminExhibitionRepository";
import { supabase } from "./lib/supabase";
import type { AdminGeocodingService } from "./services/AdminGeocodingService";
import { InMemoryAdminGeocodingService } from "./services/InMemoryAdminGeocodingService";
import { NaverMapsJsAdminGeocodingService } from "./services/NaverMapsJsAdminGeocodingService";
import { SupabaseAdminGeocodingService } from "./services/SupabaseAdminGeocodingService";
import {
  LanguageSwitch,
  interfaceMessage,
  uiErrorMessage,
  uiMessageText,
  useI18n,
  type MessageKey,
  type UiMessage,
} from "./i18n";

type SaveState =
  | "saved"
  | "dirty"
  | "invalid"
  | "saving"
  | "error"
  | "conflict";
type LifecycleAction = "publish" | "archive" | "restore" | "discard" | "delete";

interface RetainedLifecycleRequest {
  action: LifecycleAction;
  context: string;
  requestId: string;
}

const statuses: ExhibitionFilters["status"][] = [
  "All",
  "Draft",
  "Published",
  "Archived",
];

const statusKeys: Record<ExhibitionFilters["status"], MessageKey> = {
  All: "common.all",
  Draft: "status.draft",
  Published: "status.published",
  Archived: "status.archived",
};

// Permanent deletion is refused for eight distinct retained relationships. Name
// the blocking one so an operator knows what to clear instead of retrying a
// command that cannot succeed.
const draftDeleteBlockedMessageKey: Record<DraftDeleteBlockedReason, MessageKey> = {
  only_never_published_drafts_can_be_deleted: "notice.deleteBlocked.published",
  draft_delete_requires_media_detach: "notice.deleteBlocked.media",
  imported_exhibitions_cannot_be_deleted: "notice.deleteBlocked.imported",
  draft_delete_has_submission_reference: "notice.deleteBlocked.submission",
  draft_delete_has_curation_reference: "notice.deleteBlocked.curation",
  draft_delete_has_launch_kit_reference: "notice.deleteBlocked.launchKit",
  draft_delete_has_promotion_reference: "notice.deleteBlocked.promotion",
  draft_delete_has_pending_outbox_event: "notice.deleteBlocked.pendingOutbox",
};

const defaultExhibitionFilters: ExhibitionFilters = {
  search: "",
  status: "All",
  temporalStatus: "all",
  featuredOnly: false,
  missingCoverOnly: false,
  sort: "updated_desc",
};

function toPatch(
  exhibition: AdminExhibition,
  includeArtMetadata = false,
): ExhibitionPatch {
  const {
    id: _id,
    workingVersionId: _workingVersionId,
    versionNumber: _versionNumber,
    publishedVersionId: _publishedVersionId,
    hasUnpublishedChanges: _hasUnpublishedChanges,
    coverImageUrl: _coverImageUrl,
    coverAltKo: _coverAltKo,
    coverAltEn: _coverAltEn,
    imageCredit: _imageCredit,
    hasOpenOwnerSubmission: _hasOpenOwnerSubmission,
    status: _status,
    revision: _revision,
    createdAt: _createdAt,
    publishedAt: _publishedAt,
    updatedAt: _updatedAt,
    updatedBy: _updatedBy,
    artMetadata,
    ...patch
  } = exhibition;
  return includeArtMetadata && artMetadata !== null
    ? { ...patch, artMetadata }
    : patch;
}

interface AdminWorkspaceProps {
  repository: AdminExhibitionRepository;
  geocodingService?: AdminGeocodingService;
  staffRole: AdminStaffRole;
  editorRepository?: AdminEditorRepository;
  onSignOut?: () => void;
  mediaStatusPollIntervalMs?: number;
  fixturePersistence?: boolean;
  promotionsEnabled?: boolean;
}

const fixtureGeocodingService = new InMemoryAdminGeocodingService();
const fixtureEditorRepository = new InMemoryAdminEditorRepository();
const browserNaverClientId = import.meta.env.DEV
  ? import.meta.env.VITE_NAVER_MAPS_CLIENT_ID?.trim()
  : undefined;
const fixtureAdminRequested =
  import.meta.env.VITE_ADMIN_FIXTURE_MODE?.trim().toLocaleLowerCase() === "true";
const configuredAdminPromotionsEnabled =
  import.meta.env.VITE_ADMIN_PROMOTIONS_ENABLED?.trim().toLocaleLowerCase() ===
  "true";
const fixtureAdminAllowed =
  !supabase &&
  !import.meta.env.PROD &&
  (import.meta.env.MODE === "test" ||
    (import.meta.env.DEV && fixtureAdminRequested));

export function AdminWorkspace({
  repository,
  geocodingService = fixtureGeocodingService,
  staffRole,
  editorRepository = fixtureEditorRepository,
  onSignOut,
  mediaStatusPollIntervalMs = 5_000,
  fixturePersistence = false,
  promotionsEnabled = false,
}: AdminWorkspaceProps) {
  const { t, formatNumber } = useI18n();
  const [activeSection, setActiveSection] =
    useState<AdminSection>("Exhibitions");
  const [filters, setFilters] =
    useState<ExhibitionFilters>(defaultExhibitionFilters);
  // Optimistic list merges resolve after async work; they must apply the
  // filters current at completion, not the ones captured when the work began.
  // A layout effect keeps the ref fresh before any promise continuation runs.
  const filtersRef = useRef(filters);
  useLayoutEffect(() => {
    filtersRef.current = filters;
  }, [filters]);
  const mergeVisibleRecord = useCallback(
    (current: AdminExhibition[], record: AdminExhibition) => {
      const activeFilters = filtersRef.current;
      const withoutRecord = current.filter((item) => item.id !== record.id);
      if (!matchesExhibitionFilters(record, activeFilters, seoulCalendarDate())) {
        return withoutRecord;
      }
      return sortAdminExhibitions([...withoutRecord, record], activeFilters.sort);
    },
    [],
  );
  const [records, setRecords] = useState<AdminExhibition[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<AdminExhibition | null>(null);
  const [draft, setDraft] = useState<AdminExhibition | null>(null);
  const [section, setSection] = useState<InspectorSection>("Basics");
  const [saveState, setSaveState] = useState<SaveState>("saved");
  const [previewOpen, setPreviewOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [lifecycleAction, setLifecycleAction] = useState<
    "archive" | "restore" | null
  >(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [discardOpen, setDiscardOpen] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [lifecycleBusy, setLifecycleBusy] = useState(false);
  const [notice, setNotice] = useState<UiMessage | null>(null);
  const [media, setMedia] = useState<AdminMediaAsset[]>([]);
  const [mediaContext, setMediaContext] = useState<string | null>(null);
  const [mediaLoading, setMediaLoading] = useState(false);
  const [mediaBusy, setMediaBusy] = useState(false);
  const [mediaError, setMediaError] = useState<UiMessage | null>(null);
  const [mediaRecoveryEpoch, setMediaRecoveryEpoch] = useState(0);
  const [lookups, setLookups] = useState<AdminExhibitionLookups | null>(null);
  const [lookupsLoading, setLookupsLoading] = useState(true);
  const [lookupsError, setLookupsError] = useState<UiMessage | null>(null);
  const [geocodeCandidates, setGeocodeCandidates] = useState<
    AdminGeocodeCandidate[]
  >([]);
  const [geocodeLoading, setGeocodeLoading] = useState(false);
  const [geocodeError, setGeocodeError] = useState<UiMessage | null>(null);
  const [saveInFlight, setSaveInFlight] = useState(false);
  const [saveError, setSaveError] = useState<UiMessage | null>(null);
  const [saveRecoveryBusy, setSaveRecoveryBusy] = useState(false);
  const saveGeneration = useRef(0);
  const activeSaveCount = useRef(0);
  const saveLoopRunning = useRef(false);
  const saveRequestInFlight = useRef(false);
  // A revision remains consumed after any outcome until an explicit server reload.
  const attemptedSaveRevisions = useRef(new Set<string>());
  const revisionConflict = useRef(false);
  const latestDraftRef = useRef<AdminExhibition | null>(null);
  const didInitializeSelection = useRef(false);
  const recordLoadGeneration = useRef(0);
  const confirmedSavedRecords = useRef(new Map<string, AdminExhibition>());
  const mediaLoadGeneration = useRef(0);
  const preloadedMediaContext = useRef<string | null>(null);
  const mediaBusyRef = useRef(false);
  const lifecycleRequest = useRef<RetainedLifecycleRequest | null>(null);
  const geocodeGeneration = useRef(0);
  const artMetadataChangeGeneration = useRef(0);
  const savedArtMetadataGeneration = useRef(0);

  const resetGeocoding = () => {
    geocodeGeneration.current += 1;
    setGeocodeCandidates([]);
    setGeocodeLoading(false);
    setGeocodeError(null);
  };

  const resetSaveAttemptGuard = () => {
    attemptedSaveRevisions.current.clear();
    revisionConflict.current = false;
  };

  const resetArtMetadataDirty = useCallback(() => {
    artMetadataChangeGeneration.current = 0;
    savedArtMetadataGeneration.current = 0;
  }, []);

  const saveDraftOnce = useCallback(
    async (
      snapshot: AdminExhibition,
      patch: Partial<ExhibitionPatch>,
    ): Promise<AdminExhibition | null> => {
      const key = JSON.stringify([
        snapshot.id,
        snapshot.workingVersionId,
        snapshot.revision,
      ]);
      if (
        saveRequestInFlight.current ||
        attemptedSaveRevisions.current.has(key)
      ) {
        return null;
      }

      attemptedSaveRevisions.current.add(key);
      saveRequestInFlight.current = true;
      try {
        return await repository.saveDraft(
          snapshot.id,
          snapshot.workingVersionId,
          snapshot.revision,
          patch,
        );
      } finally {
        saveRequestInFlight.current = false;
      }
    },
    [repository],
  );

  const searchArtists = useCallback(
    (query: string) => repository.searchArtists(query),
    [repository],
  );

  const createArtist = useCallback(
    (nameKo: string, nameEn: string, requestId: string) =>
      repository.createArtist(nameKo, nameEn, requestId),
    [repository],
  );

  const enterRevisionConflict = (error: RevisionConflictError) => {
    revisionConflict.current = true;
    setSaveState("conflict");
    setSaveError(
      interfaceMessage("notice.serverRevision", {
        revision: error.serverRevision,
      }),
    );
  };

  const loadRecords = useCallback(async () => {
    const generation = ++recordLoadGeneration.current;
    setLoading(true);
    try {
      const next = await repository.list(filters);
      if (generation !== recordLoadGeneration.current) return;
      let reconciled = next;
      for (const saved of confirmedSavedRecords.current.values()) {
        const serverRecord = next.find((record) => record.id === saved.id);
        if (serverRecord && serverRecord.revision >= saved.revision) {
          confirmedSavedRecords.current.delete(saved.id);
        } else {
          reconciled = mergeVisibleRecord(reconciled, saved);
        }
      }
      setRecords(reconciled);
      if (!didInitializeSelection.current && reconciled.length > 0) {
        didInitializeSelection.current = true;
        latestDraftRef.current = reconciled[0];
        resetArtMetadataDirty();
        setSelected(reconciled[0]);
        setDraft(reconciled[0]);
      }
    } catch (error) {
      if (generation !== recordLoadGeneration.current) return;
      setRecords([]);
      setNotice(uiErrorMessage(error, "notice.exhibitionsLoadFailed"));
    } finally {
      if (generation === recordLoadGeneration.current) setLoading(false);
    }
  }, [filters, mergeVisibleRecord, repository, resetArtMetadataDirty]);

  useEffect(() => {
    void loadRecords();
  }, [loadRecords]);

  useEffect(() => {
    let cancelled = false;
    setLookupsLoading(true);
    setLookupsError(null);
    void repository
      .getExhibitionLookups()
      .then((next) => {
        if (!cancelled) setLookups(next);
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setLookupsError(uiErrorMessage(error, "notice.lookupsLoadFailed"));
      })
      .finally(() => {
        if (!cancelled) setLookupsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [repository]);

  const draftMediaContext = draft
    ? `${draft.id}:${draft.workingVersionId}`
    : null;
  const lifecycleContext = draft
    ? `${draft.id}:${draft.workingVersionId}:${draft.revision}`
    : null;

  useEffect(() => {
    if (
      lifecycleRequest.current &&
      lifecycleRequest.current.context !== lifecycleContext
    ) {
      lifecycleRequest.current = null;
    }
  }, [lifecycleContext]);

  useEffect(() => {
    if (preloadedMediaContext.current !== null) {
      if (preloadedMediaContext.current === draftMediaContext) {
        preloadedMediaContext.current = null;
        return;
      }
      preloadedMediaContext.current = null;
    }

    const generation = ++mediaLoadGeneration.current;
    setMedia([]);
    setMediaContext(draftMediaContext);
    setMediaError(null);
    if (!draft || !draftMediaContext) {
      setMediaLoading(false);
      return;
    }

    setMediaLoading(true);
    void repository
      .listMedia(draft.id, draft.workingVersionId)
      .then((next) => {
        if (mediaLoadGeneration.current !== generation) return;
        setMedia(next);
      })
      .catch((error: unknown) => {
        if (mediaLoadGeneration.current !== generation) return;
        setMediaError(uiErrorMessage(error, "notice.mediaLoadFailed"));
      })
      .finally(() => {
        if (mediaLoadGeneration.current === generation) setMediaLoading(false);
      });
  }, [draft?.id, draft?.workingVersionId, draftMediaContext, repository]);

  useEffect(() => {
    if (
      !draft ||
      saveState !== "dirty" ||
      mediaBusy ||
      saveLoopRunning.current
    ) {
      return;
    }

    const timer = window.setTimeout(async () => {
      if (saveLoopRunning.current) return;
      saveLoopRunning.current = true;
      activeSaveCount.current += 1;
      setSaveInFlight(true);
      try {
        let snapshot = latestDraftRef.current ?? draft;

        while (snapshot) {
          const generation = saveGeneration.current;
          const artMetadataGeneration = artMetadataChangeGeneration.current;
          const includeArtMetadata =
            artMetadataGeneration > savedArtMetadataGeneration.current;
          setSaveState("saving");
          setSaveError(null);

          let saved: AdminExhibition;
          try {
            const result = await saveDraftOnce(
              snapshot,
              toPatch(snapshot, includeArtMetadata),
            );
            if (result === null) {
              setSaveState("error");
              setSaveError(interfaceMessage("notice.draftSaveFailed"));
              return;
            }
            saved = result;
            savedArtMetadataGeneration.current = Math.max(
              savedArtMetadataGeneration.current,
              artMetadataGeneration,
            );
          } catch (error) {
            if (error instanceof RevisionConflictError) {
              enterRevisionConflict(error);
            } else {
              setSaveState("error");
              setSaveError(uiErrorMessage(error, "notice.draftSaveFailed"));
            }
            return;
          }

          lifecycleRequest.current = null;
          if (saveGeneration.current === generation) {
            latestDraftRef.current = saved;
            confirmedSavedRecords.current.set(saved.id, saved);
            setSelected(saved);
            setDraft(saved);
            setSaveState("saved");
            setSaveError(null);
            setRecords((current) => mergeVisibleRecord(current, saved));
            return;
          }

          const latest = latestDraftRef.current;
          if (!latest || latest.id !== saved.id) return;

          const rebased: AdminExhibition = {
            ...saved,
            ...toPatch(latest, true),
          };
          latestDraftRef.current = rebased;
          setSelected(rebased);
          setDraft(rebased);

          if (!getAdminExhibitionValidation(rebased).isValid) {
            setSaveState("invalid");
            return;
          }
          snapshot = rebased;
        }
      } finally {
        saveLoopRunning.current = false;
        activeSaveCount.current -= 1;
        if (activeSaveCount.current === 0) setSaveInFlight(false);
      }
    }, 600);
    return () => window.clearTimeout(timer);
  }, [draft, mediaBusy, mergeVisibleRecord, saveDraftOnce, saveState]);

  const handleSelect = (exhibition: AdminExhibition) => {
    if (saveState !== "saved" || mediaBusyRef.current) {
      setNotice(interfaceMessage("notice.resolveBeforeExhibition"));
      return;
    }
    lifecycleRequest.current = null;
    resetGeocoding();
    saveGeneration.current += 1;
    latestDraftRef.current = exhibition;
    resetArtMetadataDirty();
    setSelected(exhibition);
    setDraft(exhibition);
    setSection("Basics");
    setSaveState("saved");
    setSaveError(null);
    setNotice(null);
  };

  const handleChange = (
    field: keyof AdminExhibition,
    value: string | boolean | ExhibitionArtMetadata | null,
  ) => {
    if (
      draft?.status === "Archived" ||
      mediaBusyRef.current ||
      revisionConflict.current
    ) return;
    if (!draft) return;
    const addressChanged = field === "addressKo" && value !== draft.addressKo;
    const preserveCoordinates =
      addressChanged &&
      shouldPreserveCoordinatesForAddressChange(
        draft.addressKo,
        String(value ?? ""),
      );
    const next: AdminExhibition = addressChanged
      ? {
          ...draft,
          addressKo: String(value ?? ""),
          addressEn: "",
          latitude: preserveCoordinates ? draft.latitude : "",
          longitude: preserveCoordinates ? draft.longitude : "",
        }
      : { ...draft, [field]: value };
    if (field === "artMetadata") artMetadataChangeGeneration.current += 1;
    if (addressChanged) resetGeocoding();
    saveGeneration.current += 1;
    latestDraftRef.current = next;
    setDraft(next);
    setSaveState(
      getAdminExhibitionValidation(next).isValid ? "dirty" : "invalid",
    );
    setSaveError(null);
    setNotice(null);
  };

  const handleCreate = async () => {
    if (saveState !== "saved" || mediaBusyRef.current) {
      setNotice(interfaceMessage("notice.resolveBeforeCreate"));
      return;
    }
    try {
      const created = await repository.createDraft();
      confirmedSavedRecords.current.set(created.id, created);
      setFilters(defaultExhibitionFilters);
      setRecords((current) => [created, ...current.filter((item) => item.id !== created.id)]);
      saveGeneration.current += 1;
      latestDraftRef.current = created;
      resetArtMetadataDirty();
      setSelected(created);
      setDraft(created);
      setSection("Basics");
      setSaveState("saved");
      setSaveError(null);
      lifecycleRequest.current = null;
      resetGeocoding();
      setNotice(interfaceMessage("notice.newDraft"));
    } catch (error) {
      setNotice(uiErrorMessage(error, "notice.draftCreateFailed"));
    }
  };

  const replaceVisibleRecord = useCallback(
    (record: AdminExhibition) => {
      confirmedSavedRecords.current.set(record.id, record);
      setRecords((current) => mergeVisibleRecord(current, record));
      saveGeneration.current += 1;
      latestDraftRef.current = record;
      resetArtMetadataDirty();
      setSelected(record);
      setDraft(record);
      setSaveError(null);
      lifecycleRequest.current = null;
    },
    [mergeVisibleRecord, resetArtMetadataDirty],
  );

  const handleManageMedia = async () => {
    if (!draft || mediaBusyRef.current || saveState !== "saved") return;

    if (draft.status !== "Published") {
      setSection("Media");
      return;
    }

    const snapshot = draft;
    setSaveState("saving");
    setSaveError(null);
    setNotice(null);
    try {
      const workingDraft = await saveDraftOnce(snapshot, {});
      if (workingDraft === null) return;
      replaceVisibleRecord(workingDraft);
      setSaveState("saved");
      setSection("Media");
      setNotice(interfaceMessage("notice.workingDraft"));
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        enterRevisionConflict(error);
      } else {
        setSaveState("error");
        setSaveError(uiErrorMessage(error, "notice.workingDraftFailed"));
      }
    }
  };

  const handleDiscardAndReload = async () => {
    if (!draft || saveLoopRunning.current || saveRecoveryBusy) return;
    const recoveryDraftId = draft.id;
    setSaveRecoveryBusy(true);
    setSaveError(null);
    setNotice(null);
    try {
      const matches = await repository.list({
        search: "",
        status: "All",
      });
      const reloaded = matches.find((record) => record.id === recoveryDraftId);
      if (!reloaded) {
        setSaveError(interfaceMessage("notice.serverVersionMissing"));
        return;
      }
      const reloadedMedia = await repository.listMedia(
        reloaded.id,
        reloaded.workingVersionId,
      );

      const reloadedMediaContext =
        `${reloaded.id}:${reloaded.workingVersionId}`;
      mediaLoadGeneration.current += 1;
      preloadedMediaContext.current = reloadedMediaContext;
      setMedia(reloadedMedia);
      setMediaContext(reloadedMediaContext);
      setMediaLoading(false);
      setMediaError(null);
      setMediaRecoveryEpoch((current) => current + 1);
      resetSaveAttemptGuard();
      replaceVisibleRecord(reloaded);
      setSaveState("saved");
      resetGeocoding();
      setNotice(interfaceMessage("notice.serverReloaded"));
    } catch (error) {
      setSaveError(
        error instanceof Error
          ? interfaceMessage("notice.reloadFailed", { detail: error.message })
          : interfaceMessage("notice.serverReloadFailed"),
      );
    } finally {
      setSaveRecoveryBusy(false);
    }
  };

  const handleFindCoordinates = async () => {
    if (!draft || draft.status === "Archived") return;
    const address = draft.addressKo.trim();
    if (address.length === 0) return;

    const generation = ++geocodeGeneration.current;
    setGeocodeLoading(true);
    setGeocodeCandidates([]);
    setGeocodeError(null);
    try {
      const candidates = await geocodingService.searchAddress(address);
      if (geocodeGeneration.current !== generation) return;
      setGeocodeCandidates(candidates);
      if (candidates.length === 0) {
        setGeocodeError(interfaceMessage(
          geocodingService.mode === "fixture"
            ? "notice.fixtureGeocodeEmpty"
            : "notice.naverGeocodeEmpty",
        ));
      }
    } catch (error) {
      if (geocodeGeneration.current !== generation) return;
      setGeocodeError(uiErrorMessage(error, "notice.geocodeFailed"));
    } finally {
      if (geocodeGeneration.current === generation) setGeocodeLoading(false);
    }
  };

  const handleApplyGeocodeCandidate = (candidate: AdminGeocodeCandidate) => {
    if (
      !draft ||
      draft.status === "Archived" ||
      mediaBusyRef.current ||
      activeSaveCount.current > 0 ||
      revisionConflict.current
    ) {
      return;
    }
    const next: AdminExhibition = {
      ...draft,
      addressKo: candidate.roadAddress || candidate.jibunAddress,
      addressEn: candidate.englishAddress,
      cityKo: candidate.cityKo,
      cityEn: candidate.cityEn,
      regionKo: candidate.regionKo,
      regionEn: candidate.regionEn,
      latitude: candidate.latitude,
      longitude: candidate.longitude,
    };
    geocodeGeneration.current += 1;
    saveGeneration.current += 1;
    latestDraftRef.current = next;
    setDraft(next);
    setGeocodeCandidates([]);
    setGeocodeLoading(false);
    setGeocodeError(null);
    setSaveState(
      getAdminExhibitionValidation(next).isValid ? "dirty" : "invalid",
    );
    setSaveError(null);
    setNotice(interfaceMessage("notice.locationSelected"));
  };

  const handleApplyVenue = (venue: AdminVenueLookup) => {
    if (
      !draft ||
      draft.status === "Archived" ||
      mediaBusyRef.current ||
      revisionConflict.current
    ) return;
    const next: AdminExhibition = {
      ...draft,
      venueNameKo: venue.nameKo,
      venueNameEn: venue.nameEn,
      cityKo: venue.cityKo,
      cityEn: venue.cityEn,
      regionKo: venue.regionKo,
      regionEn: venue.regionEn,
      addressKo: venue.addressKo,
      addressEn: venue.addressEn,
      latitude: venue.latitude,
      longitude: venue.longitude,
    };
    resetGeocoding();
    saveGeneration.current += 1;
    latestDraftRef.current = next;
    setDraft(next);
    setSaveState(
      getAdminExhibitionValidation(next).isValid ? "dirty" : "invalid",
    );
    setSaveError(null);
    setNotice(null);
  };

  const handleLocationChange = (
    location: Pick<AdminExhibition, "cityKo" | "cityEn" | "regionKo" | "regionEn">,
  ) => {
    if (
      !draft ||
      draft.status === "Archived" ||
      mediaBusyRef.current ||
      revisionConflict.current
    ) return;
    const next: AdminExhibition = { ...draft, ...location };
    saveGeneration.current += 1;
    latestDraftRef.current = next;
    setDraft(next);
    setSaveState(
      getAdminExhibitionValidation(next).isValid ? "dirty" : "invalid",
    );
    setSaveError(null);
    setNotice(null);
  };

  const lifecycleRequestId = (
    action: LifecycleAction,
    exhibition: AdminExhibition,
  ): string => {
    const context = `${exhibition.id}:${exhibition.workingVersionId}:${exhibition.revision}`;
    const retained = lifecycleRequest.current;
    if (retained?.action === action && retained.context === context) {
      return retained.requestId;
    }
    const requestId = crypto.randomUUID();
    lifecycleRequest.current = { action, context, requestId };
    return requestId;
  };

  const handlePublish = async () => {
    if (
      !draft ||
      mediaBusyRef.current ||
      mediaLoading ||
      saveState !== "saved" ||
      draft.artMetadata?.artists.some(({ id }) => id === null)
    ) {
      return;
    }
    setPublishing(true);
    try {
      const published = await repository.publish(
        draft.id,
        draft.workingVersionId,
        draft.revision,
        lifecycleRequestId("publish", draft),
      );
      replaceVisibleRecord(published);
      setPublishOpen(false);
      setNotice(interfaceMessage("notice.published"));
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        lifecycleRequest.current = null;
        enterRevisionConflict(error);
        setNotice(interfaceMessage("notice.newerRevision", { revision: error.serverRevision }));
      } else {
        setNotice(uiErrorMessage(error, "notice.publishFailed"));
      }
    } finally {
      setPublishing(false);
    }
  };

  const handleLifecycleAction = async (action: "archive" | "restore") => {
    if (
      !draft ||
      staffRole === "contributor" ||
      mediaBusyRef.current ||
      saveState !== "saved"
    ) {
      return;
    }
    setLifecycleBusy(true);
    setNotice(null);
    try {
      const changed = await repository[action](
        draft.id,
        draft.workingVersionId,
        draft.revision,
        lifecycleRequestId(action, draft),
      );
      replaceVisibleRecord(changed);
      setSaveState("saved");
      setLifecycleAction(null);
      setNotice(interfaceMessage(
        action === "archive"
          ? "notice.archived"
          : changed.publishedVersionId
            ? "notice.restoredPublished"
            : "notice.restoredDraft",
      ));
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        lifecycleRequest.current = null;
        enterRevisionConflict(error);
        setNotice(interfaceMessage("notice.newerRevision", { revision: error.serverRevision }));
      } else {
        setNotice(uiErrorMessage(error, "notice.lifecycleFailed"));
      }
    } finally {
      setLifecycleBusy(false);
    }
  };

  const handleDeleteDraft = async () => {
    if (
      !draft ||
      staffRole !== "admin" ||
      draft.status !== "Draft" ||
      draft.publishedVersionId !== null ||
      mediaBusyRef.current ||
      mediaLoading ||
      visibleMedia.length > 0 ||
      saveState !== "saved"
    ) {
      return;
    }

    setLifecycleBusy(true);
    setNotice(null);
    try {
      await repository.deleteDraft(
        draft.id,
        draft.workingVersionId,
        draft.revision,
        lifecycleRequestId("delete", draft),
      );
      const deletedId = draft.id;
      confirmedSavedRecords.current.delete(deletedId);
      saveGeneration.current += 1;
      latestDraftRef.current = null;
      lifecycleRequest.current = null;
      setRecords((current) =>
        current.filter((record) => record.id !== deletedId),
      );
      setSelected(null);
      setDraft(null);
      setMedia([]);
      setMediaContext(null);
      setDeleteOpen(false);
      resetGeocoding();
      setNotice(interfaceMessage("notice.deleted"));
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        lifecycleRequest.current = null;
        enterRevisionConflict(error);
        setNotice(interfaceMessage("notice.newerRevision", { revision: error.serverRevision }));
      } else if (error instanceof DraftDeleteBlockedError) {
        setNotice(interfaceMessage(draftDeleteBlockedMessageKey[error.reason]));
      } else {
        setNotice(uiErrorMessage(error, "notice.deleteFailed"));
      }
    } finally {
      setLifecycleBusy(false);
    }
  };

  const handleDiscardDraft = async () => {
    if (
      !draft ||
      staffRole === "contributor" ||
      draft.status !== "Draft" ||
      draft.publishedVersionId === null ||
      draft.workingVersionId === draft.publishedVersionId ||
      !draft.hasUnpublishedChanges ||
      mediaBusyRef.current ||
      mediaLoading ||
      saveState !== "saved"
    ) {
      return;
    }

    setLifecycleBusy(true);
    setNotice(null);
    try {
      const restored = await repository.discardDraft(
        draft.id,
        draft.workingVersionId,
        draft.revision,
        lifecycleRequestId("discard", draft),
      );
      replaceVisibleRecord(restored);
      setSaveState("saved");
      setDiscardOpen(false);
      resetGeocoding();
      setNotice(interfaceMessage("notice.discarded"));
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        lifecycleRequest.current = null;
        enterRevisionConflict(error);
        setNotice(interfaceMessage("notice.newerRevision", { revision: error.serverRevision }));
      } else {
        setNotice(uiErrorMessage(error, "notice.discardFailed"));
      }
    } finally {
      setLifecycleBusy(false);
    }
  };

  const visibleMedia =
    draftMediaContext !== null && mediaContext === draftMediaContext ? media : [];
  const mediaIsLoading =
    draft !== null &&
    (mediaLoading || mediaContext !== draftMediaContext);
  const processingMediaKey = visibleMedia
    .filter(
      (asset) =>
        asset.status === "pending_upload" || asset.status === "ready",
    )
    .map((asset) => `${asset.assetId}:${asset.status}`)
    .sort()
    .join("|");

  useEffect(() => {
    if (
      !draft ||
      !draftMediaContext ||
      mediaContext !== draftMediaContext ||
      mediaIsLoading ||
      processingMediaKey.length === 0
    ) {
      return;
    }

    let cancelled = false;
    let refreshing = false;
    const generation = mediaLoadGeneration.current;
    const exhibitionId = draft.id;
    const versionId = draft.workingVersionId;

    const refreshProcessingMedia = async () => {
      if (cancelled || refreshing || mediaBusyRef.current) return;
      refreshing = true;
      try {
        const next = await repository.listMedia(exhibitionId, versionId);
        if (
          cancelled ||
          mediaBusyRef.current ||
          mediaLoadGeneration.current !== generation
        ) {
          return;
        }
        setMedia(next);
        const publishedCoverUrl =
          next.find((asset) => asset.role === "cover")?.publicUrl ?? null;
        if (publishedCoverUrl !== null) {
          // The list row must follow the server's cover_image_url once the
          // worker publishes the cover, so cover-based filters stay accurate
          // without a reload. Only the list changes; the draft is left alone.
          setRecords((current) => {
            const record = current.find((item) => item.id === exhibitionId);
            if (
              !record ||
              record.workingVersionId !== versionId ||
              record.coverImageUrl === publishedCoverUrl
            ) {
              return current;
            }
            return mergeVisibleRecord(current, {
              ...record,
              coverImageUrl: publishedCoverUrl,
            });
          });
        }
        setMediaError((current) =>
          current?.kind === "interface" &&
          (current.key === "notice.mediaRefreshFailed" ||
            current.key === "notice.mediaRefreshNoResponse")
            ? null
            : current,
        );
      } catch (error) {
        if (
          cancelled ||
          mediaBusyRef.current ||
          mediaLoadGeneration.current !== generation
        ) {
          return;
        }
        setMediaError(
          (current) =>
            current ??
            (error instanceof Error
              ? interfaceMessage("notice.mediaRefreshFailed", { detail: error.message })
              : interfaceMessage("notice.mediaRefreshNoResponse")),
        );
      } finally {
        refreshing = false;
      }
    };

    const timer = window.setInterval(
      () => void refreshProcessingMedia(),
      mediaStatusPollIntervalMs,
    );
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [
    draft?.id,
    draft?.workingVersionId,
    draftMediaContext,
    mediaContext,
    mediaIsLoading,
    mediaRecoveryEpoch,
    mediaStatusPollIntervalMs,
    mergeVisibleRecord,
    processingMediaKey,
    repository,
  ]);

  const runMediaMutation = async (
    operation: (snapshot: AdminExhibition) => Promise<AdminMediaMutationResult>,
    successMessage: MessageKey,
  ) => {
    if (!draft || draft.status !== "Draft") {
      setMediaError(interfaceMessage("notice.mediaDraftOnly"));
      return;
    }
    if (saveState !== "saved" || mediaIsLoading) {
      setMediaError(interfaceMessage("notice.waitForDetails"));
      return;
    }
    if (mediaBusyRef.current) return;

    const snapshot = draft;
    mediaBusyRef.current = true;
    mediaLoadGeneration.current += 1;
    setMediaBusy(true);
    setMediaError(null);
    setNotice(null);
    try {
      const result = await operation(snapshot);
      setMedia(result.media);
      setMediaContext(
        `${result.exhibition.id}:${result.exhibition.workingVersionId}`,
      );
      replaceVisibleRecord(result.exhibition);
      setSaveState("saved");
      setNotice(interfaceMessage(successMessage));
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        lifecycleRequest.current = null;
        enterRevisionConflict(error);
        setMediaError(interfaceMessage("notice.newerMediaRevision", { revision: error.serverRevision }));
      } else {
        setMediaError(uiErrorMessage(error, "notice.mediaUpdateFailed"));
      }
    } finally {
      mediaBusyRef.current = false;
      setMediaBusy(false);
    }
  };

  const handleMediaUpload = (file: File, role: AdminMediaRole) => {
    void runMediaMutation(
      (snapshot) =>
        repository.uploadAndAttachMedia(
          snapshot.id,
          snapshot.workingVersionId,
          snapshot.revision,
          file,
          role,
        ),
      role === "cover" ? "notice.coverUploaded" : "notice.galleryUploaded",
    );
  };

  const handleMediaMetadataSave = (
    assetId: string,
    patch: AdminMediaMetadataPatch,
  ) => {
    void runMediaMutation(
      (snapshot) =>
        repository.updateMediaMetadata(
          snapshot.id,
          snapshot.workingVersionId,
          snapshot.revision,
          assetId,
          patch,
        ),
      "notice.metadataSaved",
    );
  };

  const handleMediaReorder = (orderedAssetIds: string[]) => {
    void runMediaMutation(
      (snapshot) =>
        repository.reorderMedia(
          snapshot.id,
          snapshot.workingVersionId,
          snapshot.revision,
          orderedAssetIds,
        ),
      "notice.galleryOrderSaved",
    );
  };

  const handleMediaDetach = (assetId: string) => {
    void runMediaMutation(
      (snapshot) =>
        repository.detachMedia(
          snapshot.id,
          snapshot.workingVersionId,
          snapshot.revision,
          assetId,
        ),
      "notice.imageRemoved",
    );
  };

  const readiness = draft
    ? getPublishReadiness(draft, visibleMedia, !mediaIsLoading)
    : null;
  const validation = draft ? getAdminExhibitionValidation(draft) : null;
  const mediaEditable = Boolean(
    draft &&
      draft.status === "Draft" &&
      saveState === "saved" &&
      !mediaIsLoading,
  );
  const mediaReadOnlyReason = !draft
    ? null
    : draft.status === "Archived"
      ? t("notice.archivedMediaReadonly")
      : draft.status !== "Draft"
        ? t("notice.createDraftForMedia")
        : saveState !== "saved"
          ? t("notice.waitForSaveMedia")
          : null;
  const geocodeResultWaitingForSave =
    saveInFlight && geocodeCandidates.length > 0;
  const editorTransitionBlocked = saveState !== "saved" || mediaBusy;

  const handleNavigation = (next: AdminSection) => {
    if (next === activeSection) return;
    if (next === "Editors" && staffRole !== "admin") return;
    if (next === "Promotions" && !promotionsEnabled) return;
    if (editorTransitionBlocked) {
      setNotice(interfaceMessage("notice.resolveBeforeSection"));
      return;
    }
    setActiveSection(next);
    setNotice(null);
  };

  const handleAcceptedSubmission = (exhibition: AdminExhibition) => {
    setFilters(defaultExhibitionFilters);
    setRecords((current) => [
      exhibition,
      ...current.filter((record) => record.id !== exhibition.id),
    ]);
    saveGeneration.current += 1;
    latestDraftRef.current = exhibition;
    setSelected(exhibition);
    setDraft(exhibition);
    setSection("Basics");
    setSaveState("saved");
    setSaveError(null);
    setNotice(interfaceMessage("notice.submissionAccepted"));
    setActiveSection("Exhibitions");
  };

  return (
    <div className="admin-shell">
      <PrimaryNavigation
        activeItem={activeSection}
        staffRole={staffRole}
        onNavigate={handleNavigation}
        onSignOut={onSignOut}
        signOutDisabled={editorTransitionBlocked}
        promotionsEnabled={promotionsEnabled}
      />
      {activeSection === "Submissions" ? (
        <SubmissionWorkspace
          repository={repository}
          onAccepted={handleAcceptedSubmission}
        />
      ) : activeSection === "Gallery claims" ? (
        <GalleryClaimsWorkspace repository={repository} />
      ) : activeSection === "Promotions" && promotionsEnabled ? (
        <PromotionWorkspace repository={repository} />
      ) : activeSection === "Editors" && staffRole === "admin" ? (
        <EditorOnboardingWorkspace repository={editorRepository} />
      ) : (
        <>
      <main className="workspace">
        <header className="workspace-header">
          <div className="workspace-title-row">
            <h1>{t("workspace.exhibitions")}</h1>
            {(fixturePersistence || geocodingService.mode === "fixture") && (
              <div className="fixture-mode-indicator" role="note">
                <strong>{t(fixturePersistence ? "workspace.fixtureAdmin" : "workspace.fixtureMode")}</strong>
                <span>
                  {fixturePersistence
                    ? t("workspace.fixtureTemporary")
                    : t("workspace.fixtureAddress")}
                </span>
              </div>
            )}
          </div>
          <div className="workspace-toolbar">
            <label className="search-field">
              <span className="visually-hidden">{t("workspace.searchExhibitions")}</span>
              <SearchIcon />
              <input
                type="search"
                value={filters.search}
                placeholder={t("workspace.searchExhibitionsPlaceholder")}
                onChange={(event) =>
                  setFilters((current) => ({
                    ...current,
                    search: event.target.value,
                  }))
                }
              />
            </label>
            <div className="status-filter" aria-label={t("workspace.statusFilter")}>
              {statuses.map((status) => (
                <button
                  type="button"
                  className={filters.status === status ? "is-active" : ""}
                  aria-pressed={filters.status === status}
                  onClick={() =>
                    setFilters((current) => ({ ...current, status }))
                  }
                  key={status}
                >
                  {t(statusKeys[status])}
                </button>
              ))}
            </div>
            <button
              className="accent-button"
              type="button"
              disabled={editorTransitionBlocked}
              onClick={handleCreate}
            >
              {t("workspace.newExhibition")}
            </button>
          </div>
          <div
            className="exhibition-list-options"
            aria-label={t("workspace.listOptions")}
          >
            <label>
              <span>{t("workspace.dateStatus")}</span>
              <select
                aria-label={t("workspace.dateStatus")}
                value={filters.temporalStatus ?? "all"}
                onChange={(event) =>
                  setFilters((current) => ({
                    ...current,
                    temporalStatus: event.target.value as NonNullable<
                      ExhibitionFilters["temporalStatus"]
                    >,
                  }))
                }
              >
                <option value="all">{t("workspace.allDates")}</option>
                <option value="running">{t("workspace.currentlyRunning")}</option>
                <option value="upcoming">{t("workspace.upcoming")}</option>
                <option value="ended">{t("workspace.ended")}</option>
              </select>
            </label>
            <label>
              <span>{t("workspace.sort")}</span>
              <select
                aria-label={t("workspace.sortExhibitions")}
                value={filters.sort ?? "updated_desc"}
                onChange={(event) =>
                  setFilters((current) => ({
                    ...current,
                    sort: event.target.value as NonNullable<
                      ExhibitionFilters["sort"]
                    >,
                  }))
                }
              >
                <option value="updated_desc">{t("workspace.recentlyUpdated")}</option>
                <option value="published_desc">{t("workspace.datePublished")}</option>
                <option value="opening_asc">{t("workspace.openingDate")}</option>
                <option value="closing_asc">{t("workspace.closingDate")}</option>
                <option value="created_desc">{t("workspace.dateCreated")}</option>
              </select>
            </label>
            <label className="list-toggle-filter">
              <input
                type="checkbox"
                checked={filters.featuredOnly ?? false}
                onChange={(event) =>
                  setFilters((current) => ({
                    ...current,
                    featuredOnly: event.target.checked,
                  }))
                }
              />
              {t("workspace.homepageOnly")}
            </label>
            <label className="list-toggle-filter">
              <input
                type="checkbox"
                checked={filters.missingCoverOnly ?? false}
                onChange={(event) =>
                  setFilters((current) => ({
                    ...current,
                    missingCoverOnly: event.target.checked,
                  }))
                }
              />
              {t("workspace.missingCoverOnly")}
            </label>
          </div>
          {notice && (
            <div className="inline-notice" role="status">
              {uiMessageText(notice, t)}
            </div>
          )}
          {(saveState === "invalid" ||
            saveState === "error" ||
            saveState === "conflict") &&
            draft && (
            <div className="inline-notice" role="alert">
              <span aria-hidden="true">! </span>
              <span>
                {saveError
                  ? uiMessageText(saveError, t)
                  : saveState === "invalid"
                    ? t("workspace.invalidDraft")
                    : t("workspace.serverChanged")}
              </span>{" "}
              <button
                className="outlined-compact"
                type="button"
                disabled={saveRecoveryBusy}
                onClick={() => void handleDiscardAndReload()}
              >
                {t(saveRecoveryBusy ? "workspace.reloading" : "workspace.discardReload")}
              </button>
            </div>
          )}
        </header>

        <ExhibitionTable
          exhibitions={records}
          selectedId={selected?.id ?? null}
          onSelect={handleSelect}
          loading={loading}
        />
        <footer className="table-footer">
          <span>{t("workspace.exhibitionCount", { count: formatNumber(records.length) })}</span>
          <span>{t("workspace.pageOne")}</span>
        </footer>
      </main>

      {draft && readiness && validation && (
        <ExhibitionInspector
          exhibition={draft}
          section={section}
          saveState={saveState}
          readiness={readiness}
          validation={validation}
          lookups={lookups}
          lookupsLoading={lookupsLoading}
          lookupsError={lookupsError ? uiMessageText(lookupsError, t) : null}
          publishAllowed={staffRole !== "contributor"}
          deleteAllowed={staffRole === "admin"}
          lifecycleBusy={lifecycleBusy}
          media={visibleMedia}
          mediaLoading={mediaIsLoading}
          mediaBusy={mediaBusy}
          mediaError={mediaError ? uiMessageText(mediaError, t) : null}
          mediaEditable={mediaEditable}
          mediaReadOnlyReason={mediaReadOnlyReason}
          geocodeCandidates={
            geocodeResultWaitingForSave ? [] : geocodeCandidates
          }
          geocodeLoading={geocodeLoading || geocodeResultWaitingForSave}
          geocodeError={geocodeError ? uiMessageText(geocodeError, t) : null}
          geocodingMode={geocodingService.mode}
          onSectionChange={setSection}
          onClose={() => {
            if (saveState !== "saved" || mediaBusyRef.current) {
              setNotice(interfaceMessage("notice.resolveBeforeClose"));
              return;
            }
            saveGeneration.current += 1;
            latestDraftRef.current = null;
            setSelected(null);
            setDraft(null);
            setSaveError(null);
            resetGeocoding();
          }}
          onChange={handleChange}
          onSearchArtists={searchArtists}
          onCreateArtist={createArtist}
          onPreview={() => setPreviewOpen(true)}
          onPublish={() => setPublishOpen(true)}
          onArchive={() => setLifecycleAction("archive")}
          onRestore={() => setLifecycleAction("restore")}
          onDiscard={() => setDiscardOpen(true)}
          onDelete={() => setDeleteOpen(true)}
          onManageMedia={() => void handleManageMedia()}
          onMediaUpload={handleMediaUpload}
          onMediaMetadataSave={handleMediaMetadataSave}
          onMediaReorder={handleMediaReorder}
          onMediaDetach={handleMediaDetach}
          onMediaErrorClear={() => setMediaError(null)}
          onFindCoordinates={() => void handleFindCoordinates()}
          onApplyGeocodeCandidate={handleApplyGeocodeCandidate}
          onApplyVenue={handleApplyVenue}
          onLocationChange={handleLocationChange}
        />
      )}

      {previewOpen && draft && (
        <PreviewDialog exhibition={draft} onClose={() => setPreviewOpen(false)} />
      )}
      {publishOpen && draft && readiness && (
        <PublishDialog
          exhibition={draft}
          readiness={readiness}
          publishing={publishing}
          onClose={() => setPublishOpen(false)}
          onConfirm={handlePublish}
        />
      )}
      {lifecycleAction && draft && (
        <LifecycleDialog
          exhibition={draft}
          action={lifecycleAction}
          busy={lifecycleBusy}
          onClose={() => setLifecycleAction(null)}
          onConfirm={() => void handleLifecycleAction(lifecycleAction)}
        />
      )}
      {deleteOpen && draft && (
        <DeleteDraftDialog
          exhibition={draft}
          busy={lifecycleBusy}
          hasAttachedMedia={visibleMedia.length > 0}
          onClose={() => setDeleteOpen(false)}
          onConfirm={() => void handleDeleteDraft()}
        />
      )}
      {discardOpen && draft && (
        <DiscardDraftDialog
          exhibition={draft}
          busy={lifecycleBusy}
          onClose={() => setDiscardOpen(false)}
          onConfirm={() => void handleDiscardDraft()}
        />
      )}
        </>
      )}
      <div className="visually-hidden" aria-live="polite">
        {notice ? uiMessageText(notice, t) : null}
      </div>
    </div>
  );
}

export default function App() {
  const { t } = useI18n();
  const repository = useMemo<AdminExhibitionRepository | null>(
    () => {
      if (supabase) return new SupabaseAdminExhibitionRepository(supabase);
      if (fixtureAdminAllowed) {
        return new InMemoryAdminExhibitionRepository();
      }
      return null;
    },
    [],
  );
  const geocodingService = useMemo<AdminGeocodingService | null>(
    () => {
      if (supabase) return new SupabaseAdminGeocodingService(supabase);
      if (!fixtureAdminAllowed) return null;
      return browserNaverClientId
        ? new NaverMapsJsAdminGeocodingService(browserNaverClientId)
        : fixtureGeocodingService;
    },
    [],
  );
  const editorPickRepository = useMemo<EditorPickRepository | null>(
    () => (supabase ? new SupabaseEditorPickRepository(supabase) : null),
    [],
  );
  const editorOnboardingRepository = useMemo<AdminEditorRepository | null>(
    () => (supabase ? new SupabaseAdminEditorRepository(supabase) : null),
    [],
  );
  const editorSelfOnboardingRepository = useMemo(
    () => supabase
      ? new SupabaseEditorSelfOnboardingRepository(supabase)
      : null,
    [],
  );

  if (!repository || !geocodingService) {
    return (
      <div className="login-shell">
        <aside className="login-rail" aria-label={t("config.rail")}>
          <strong>{t("config.rail")}</strong>
          <LanguageSwitch />
          <span className="login-rail-mark" aria-hidden="true" />
        </aside>
        <main className="login-stage">
          <section
            className="access-denied"
            aria-labelledby="admin-configuration-title"
          >
            <h1 id="admin-configuration-title">
              {t("config.title")}
            </h1>
            <p>
              {t("config.body")}
            </p>
          </section>
        </main>
      </div>
    );
  }

  if (!supabase) {
    return (
      <AdminWorkspace
        repository={repository}
        geocodingService={geocodingService}
        staffRole="admin"
        fixturePersistence
        promotionsEnabled={configuredAdminPromotionsEnabled}
      />
    );
  }

  return (
    <AuthGate client={supabase}>
      {(access, signOut, refreshAccess) =>
        access.role === "editor" && editorPickRepository ? (
          <EditorPicksWorkspace
            repository={editorPickRepository}
            editorName={access.editorName}
            onSignOut={() => void signOut()}
          />
        ) : access.role === "editor_onboarding" &&
          editorSelfOnboardingRepository ? (
          <EditorSelfOnboardingWorkspace
            repository={editorSelfOnboardingRepository}
            onCompleted={refreshAccess}
            onSignOut={() => void signOut()}
          />
        ) : access.role !== "editor" && access.role !== "editor_onboarding" ? (
          <AdminWorkspace
            repository={repository}
            geocodingService={geocodingService}
            staffRole={access.role}
            editorRepository={editorOnboardingRepository ?? undefined}
            onSignOut={() => void signOut()}
            promotionsEnabled={configuredAdminPromotionsEnabled}
          />
        ) : null
      }
    </AuthGate>
  );
}
