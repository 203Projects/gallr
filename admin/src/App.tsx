import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  AdminExhibition,
  AdminExhibitionLookups,
  AdminGeocodeCandidate,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaMutationResult,
  AdminMediaRole,
  ExhibitionFilters,
  ExhibitionPatch,
  InspectorSection,
} from "./domain";
import {
  getAdminExhibitionValidation,
  getPublishReadiness,
} from "./domain";
import { PrimaryNavigation } from "./components/PrimaryNavigation";
import { ExhibitionTable } from "./components/ExhibitionTable";
import { ExhibitionInspector } from "./components/ExhibitionInspector";
import {
  LifecycleDialog,
  PreviewDialog,
  PublishDialog,
} from "./components/Dialogs";
import { SearchIcon } from "./components/Icons";
import { AuthGate } from "./components/AuthGate";
import type { StaffRole } from "./components/AuthGate";
import { InMemoryAdminExhibitionRepository } from "./repositories/InMemoryAdminExhibitionRepository";
import { SupabaseAdminExhibitionRepository } from "./repositories/SupabaseAdminExhibitionRepository";
import {
  type AdminExhibitionRepository,
  RevisionConflictError,
} from "./repositories/AdminExhibitionRepository";
import { supabase } from "./lib/supabase";
import type { AdminGeocodingService } from "./services/AdminGeocodingService";
import { InMemoryAdminGeocodingService } from "./services/InMemoryAdminGeocodingService";
import { NaverMapsJsAdminGeocodingService } from "./services/NaverMapsJsAdminGeocodingService";
import { SupabaseAdminGeocodingService } from "./services/SupabaseAdminGeocodingService";

type SaveState =
  | "saved"
  | "dirty"
  | "invalid"
  | "saving"
  | "error"
  | "conflict";
type LifecycleAction = "publish" | "archive" | "restore";

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

function toPatch(exhibition: AdminExhibition): ExhibitionPatch {
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
    status: _status,
    revision: _revision,
    updatedAt: _updatedAt,
    updatedBy: _updatedBy,
    ...patch
  } = exhibition;
  return patch;
}

interface AdminWorkspaceProps {
  repository: AdminExhibitionRepository;
  geocodingService?: AdminGeocodingService;
  staffRole: StaffRole;
  onSignOut?: () => void;
  mediaStatusPollIntervalMs?: number;
  fixturePersistence?: boolean;
}

const fixtureGeocodingService = new InMemoryAdminGeocodingService();
const browserNaverClientId = import.meta.env.DEV
  ? import.meta.env.VITE_NAVER_MAPS_CLIENT_ID?.trim()
  : undefined;
const fixtureAdminRequested =
  import.meta.env.VITE_ADMIN_FIXTURE_MODE?.trim().toLocaleLowerCase() === "true";
const fixtureAdminAllowed =
  !supabase &&
  !import.meta.env.PROD &&
  (import.meta.env.MODE === "test" ||
    (import.meta.env.DEV && fixtureAdminRequested));

function matchesFilters(
  exhibition: AdminExhibition,
  filters: ExhibitionFilters,
): boolean {
  const query = filters.search.trim().toLocaleLowerCase();
  const matchesStatus =
    filters.status === "All" || exhibition.status === filters.status;
  const matchesSearch =
    query.length === 0 ||
    [
      exhibition.id,
      exhibition.nameKo,
      exhibition.nameEn,
      exhibition.venueNameKo,
      exhibition.venueNameEn,
    ].some((value) => value.toLocaleLowerCase().includes(query));
  return matchesStatus && matchesSearch;
}

export function AdminWorkspace({
  repository,
  geocodingService = fixtureGeocodingService,
  staffRole,
  onSignOut,
  mediaStatusPollIntervalMs = 5_000,
  fixturePersistence = false,
}: AdminWorkspaceProps) {
  const [filters, setFilters] = useState<ExhibitionFilters>({
    search: "",
    status: "All",
  });
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
  const [publishing, setPublishing] = useState(false);
  const [lifecycleBusy, setLifecycleBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [media, setMedia] = useState<AdminMediaAsset[]>([]);
  const [mediaContext, setMediaContext] = useState<string | null>(null);
  const [mediaLoading, setMediaLoading] = useState(false);
  const [mediaBusy, setMediaBusy] = useState(false);
  const [mediaError, setMediaError] = useState<string | null>(null);
  const [mediaRecoveryEpoch, setMediaRecoveryEpoch] = useState(0);
  const [lookups, setLookups] = useState<AdminExhibitionLookups | null>(null);
  const [lookupsLoading, setLookupsLoading] = useState(true);
  const [lookupsError, setLookupsError] = useState<string | null>(null);
  const [geocodeCandidates, setGeocodeCandidates] = useState<
    AdminGeocodeCandidate[]
  >([]);
  const [geocodeLoading, setGeocodeLoading] = useState(false);
  const [geocodeError, setGeocodeError] = useState<string | null>(null);
  const [saveInFlight, setSaveInFlight] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveRecoveryBusy, setSaveRecoveryBusy] = useState(false);
  const saveGeneration = useRef(0);
  const activeSaveCount = useRef(0);
  const saveLoopRunning = useRef(false);
  const latestDraftRef = useRef<AdminExhibition | null>(null);
  const didInitializeSelection = useRef(false);
  const mediaLoadGeneration = useRef(0);
  const preloadedMediaContext = useRef<string | null>(null);
  const mediaBusyRef = useRef(false);
  const lifecycleRequest = useRef<RetainedLifecycleRequest | null>(null);
  const geocodeGeneration = useRef(0);

  const resetGeocoding = () => {
    geocodeGeneration.current += 1;
    setGeocodeCandidates([]);
    setGeocodeLoading(false);
    setGeocodeError(null);
  };

  const loadRecords = useCallback(async () => {
    setLoading(true);
    try {
      const next = await repository.list(filters);
      setRecords(next);
      if (!didInitializeSelection.current && next.length > 0) {
        didInitializeSelection.current = true;
        latestDraftRef.current = next[0];
        setSelected(next[0]);
        setDraft(next[0]);
      }
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Exhibitions could not be loaded.");
    } finally {
      setLoading(false);
    }
  }, [filters, repository]);

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
        setLookupsError(
          error instanceof Error
            ? error.message
            : "Event and editor choices could not be loaded.",
        );
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
        setMediaError(
          error instanceof Error ? error.message : "Media could not be loaded.",
        );
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
          setSaveState("saving");
          setSaveError(null);

          let saved: AdminExhibition;
          try {
            saved = await repository.saveDraft(
              snapshot.id,
              snapshot.workingVersionId,
              snapshot.revision,
              toPatch(snapshot),
            );
          } catch (error) {
            if (error instanceof RevisionConflictError) {
              setSaveState("conflict");
              setSaveError(`The server is at revision ${error.serverRevision}.`);
            } else {
              setSaveState("error");
              setSaveError(
                error instanceof Error
                  ? error.message
                  : "The draft could not be saved.",
              );
            }
            return;
          }

          lifecycleRequest.current = null;
          if (saveGeneration.current === generation) {
            latestDraftRef.current = saved;
            setSelected(saved);
            setDraft(saved);
            setSaveState("saved");
            setSaveError(null);
            setRecords((current) => {
              const existingIndex = current.findIndex(
                (record) => record.id === saved.id,
              );
              const withoutRecord = current.filter(
                (record) => record.id !== saved.id,
              );
              if (!matchesFilters(saved, filters)) return withoutRecord;
              if (existingIndex < 0) return [saved, ...withoutRecord];
              const next = [...withoutRecord];
              next.splice(existingIndex, 0, saved);
              return next;
            });
            return;
          }

          const latest = latestDraftRef.current;
          if (!latest || latest.id !== saved.id) return;

          const rebased: AdminExhibition = {
            ...saved,
            ...toPatch(latest),
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
  }, [draft, filters, mediaBusy, repository, saveState]);

  const handleSelect = (exhibition: AdminExhibition) => {
    if (saveState !== "saved" || mediaBusyRef.current) {
      setNotice(
        "Retry or discard the current draft changes before changing exhibitions.",
      );
      return;
    }
    lifecycleRequest.current = null;
    resetGeocoding();
    saveGeneration.current += 1;
    latestDraftRef.current = exhibition;
    setSelected(exhibition);
    setDraft(exhibition);
    setSection("Basics");
    setSaveState("saved");
    setSaveError(null);
    setNotice(null);
  };

  const handleChange = (
    field: keyof AdminExhibition,
    value: string | boolean | null,
  ) => {
    if (draft?.status === "Archived" || mediaBusyRef.current) return;
    if (!draft) return;
    const addressChanged = field === "addressKo" && value !== draft.addressKo;
    const next: AdminExhibition = addressChanged
      ? {
          ...draft,
          addressKo: String(value ?? ""),
          addressEn: "",
          latitude: "",
          longitude: "",
        }
      : { ...draft, [field]: value };
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
      setNotice(
        "Retry or discard the current draft changes before creating an exhibition.",
      );
      return;
    }
    try {
      const created = await repository.createDraft();
      setFilters({ search: "", status: "All" });
      setRecords((current) => [created, ...current.filter((item) => item.id !== created.id)]);
      saveGeneration.current += 1;
      latestDraftRef.current = created;
      setSelected(created);
      setDraft(created);
      setSection("Basics");
      setSaveState("saved");
      setSaveError(null);
      lifecycleRequest.current = null;
      resetGeocoding();
      setNotice("New draft created. Add its required details before publishing.");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Draft could not be created.");
    }
  };

  const replaceVisibleRecord = useCallback(
    (record: AdminExhibition) => {
      setRecords((current) => {
        const withoutRecord = current.filter((item) => item.id !== record.id);
        return matchesFilters(record, filters)
          ? [record, ...withoutRecord]
          : withoutRecord;
      });
      saveGeneration.current += 1;
      latestDraftRef.current = record;
      setSelected(record);
      setDraft(record);
      setSaveError(null);
      lifecycleRequest.current = null;
    },
    [filters],
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
      const workingDraft = await repository.saveDraft(
        snapshot.id,
        snapshot.workingVersionId,
        snapshot.revision,
        {},
      );
      replaceVisibleRecord(workingDraft);
      setSaveState("saved");
      setSection("Media");
      setNotice("Working draft created. You can now change images.");
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        setSaveState("conflict");
        setSaveError(`The server is at revision ${error.serverRevision}.`);
      } else {
        setSaveState("error");
        setSaveError(
          error instanceof Error
            ? error.message
            : "A working draft could not be created.",
        );
      }
    }
  };

  const handleRetrySave = () => {
    if (!draft || saveLoopRunning.current) return;
    if (!getAdminExhibitionValidation(draft).isValid) {
      setSaveState("invalid");
      setSaveError(null);
      return;
    }
    saveGeneration.current += 1;
    latestDraftRef.current = draft;
    setSaveError(null);
    setNotice(null);
    setSaveState("dirty");
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
        throw new Error("The server version could not be found.");
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
      replaceVisibleRecord(reloaded);
      setSaveState("saved");
      resetGeocoding();
      setNotice("Server version reloaded. Local changes were discarded.");
    } catch (error) {
      setSaveError(
        error instanceof Error
          ? `Reload failed: ${error.message}`
          : "The server version could not be reloaded.",
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
        setGeocodeError(
          geocodingService.mode === "fixture"
            ? "Fixture lookup has no match. Use the sample 서울 용산구 한남대로 28 or enter both coordinates manually. No NAVER request was sent."
            : "NAVER Maps found no matching address. Refine the Korean address or enter both coordinates manually.",
        );
      }
    } catch (error) {
      if (geocodeGeneration.current !== generation) return;
      setGeocodeError(
        error instanceof Error
          ? error.message
          : "Coordinates could not be found. Try again later.",
      );
    } finally {
      if (geocodeGeneration.current === generation) setGeocodeLoading(false);
    }
  };

  const handleApplyGeocodeCandidate = (candidate: AdminGeocodeCandidate) => {
    if (
      !draft ||
      draft.status === "Archived" ||
      mediaBusyRef.current ||
      activeSaveCount.current > 0
    ) {
      return;
    }
    const next: AdminExhibition = {
      ...draft,
      addressKo: candidate.roadAddress || candidate.jibunAddress,
      addressEn: candidate.englishAddress,
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
    setNotice(
      "Map location selected. Saving the confirmed address and coordinates.",
    );
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
    if (!draft || mediaBusyRef.current || mediaLoading || saveState !== "saved") {
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
      setNotice("Exhibition published. Website rebuild queued.");
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        lifecycleRequest.current = null;
        setSaveState("conflict");
        setSaveError(`The server is at revision ${error.serverRevision}.`);
        setNotice(`A newer revision (${error.serverRevision}) exists.`);
      } else {
        setNotice(error instanceof Error ? error.message : "Publish failed.");
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
      setNotice(
        action === "archive"
          ? "Exhibition archived. Its history and media references are preserved."
          : changed.publishedVersionId
            ? "Exhibition restored. Its last published version is available; curation remains disabled."
            : "Exhibition restored as a draft. Publish it when it is ready.",
      );
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        lifecycleRequest.current = null;
        setSaveState("conflict");
        setSaveError(`The server is at revision ${error.serverRevision}.`);
        setNotice(`A newer revision (${error.serverRevision}) exists.`);
      } else {
        setNotice(
          error instanceof Error ? error.message : `Exhibition could not be ${action}d.`,
        );
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
        setMediaError((current) =>
          current?.startsWith("Media processing status could not be refreshed.")
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
        const detail =
          error instanceof Error ? error.message : "The server did not respond.";
        setMediaError(
          (current) =>
            current ??
            `Media processing status could not be refreshed. ${detail}`,
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
    processingMediaKey,
    repository,
  ]);

  const runMediaMutation = async (
    operation: (snapshot: AdminExhibition) => Promise<AdminMediaMutationResult>,
    successMessage: string,
  ) => {
    if (!draft || draft.status !== "Draft") {
      setMediaError("Media can only be changed on a draft exhibition.");
      return;
    }
    if (saveState !== "saved" || mediaIsLoading) {
      setMediaError("Wait for exhibition details and media to finish loading.");
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
      setNotice(successMessage);
    } catch (error) {
      if (error instanceof RevisionConflictError) {
        lifecycleRequest.current = null;
        setSaveState("conflict");
        setSaveError(`The server is at revision ${error.serverRevision}.`);
        setMediaError(
          `A newer revision (${error.serverRevision}) exists. Reload before changing media.`,
        );
      } else {
        setMediaError(
          error instanceof Error ? error.message : "Media could not be updated.",
        );
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
      role === "cover"
        ? "Cover image attached. Processing for publication."
        : "Gallery image attached. Processing for publication.",
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
      "Image metadata saved.",
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
      "Gallery order saved.",
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
      "Image removed from this draft.",
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
      ? "Archived exhibitions are read-only. Restore this exhibition before changing media."
      : draft.status !== "Draft"
        ? "Create a working draft by editing exhibition details before changing media."
        : saveState !== "saved"
          ? "Wait for exhibition details to finish saving before changing media."
          : null;
  const geocodeResultWaitingForSave =
    saveInFlight && geocodeCandidates.length > 0;
  const editorTransitionBlocked = saveState !== "saved" || mediaBusy;

  return (
    <div className="admin-shell">
      <PrimaryNavigation
        onSignOut={onSignOut}
        signOutDisabled={editorTransitionBlocked}
      />
      <main className="workspace">
        <header className="workspace-header">
          <div className="workspace-title-row">
            <h1>Exhibitions</h1>
            {(fixturePersistence || geocodingService.mode === "fixture") && (
              <div className="fixture-mode-indicator" role="note">
                <strong>{fixturePersistence ? "Fixture admin" : "Fixture mode"}</strong>
                <span>
                  {fixturePersistence
                    ? "Changes are temporary and are never saved to Supabase."
                    : "Address lookup uses local sample data only."}
                </span>
              </div>
            )}
          </div>
          <div className="workspace-toolbar">
            <label className="search-field">
              <span className="visually-hidden">Search exhibitions</span>
              <SearchIcon />
              <input
                type="search"
                value={filters.search}
                placeholder="Search exhibitions..."
                onChange={(event) =>
                  setFilters((current) => ({
                    ...current,
                    search: event.target.value,
                  }))
                }
              />
            </label>
            <div className="status-filter" aria-label="Status filter">
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
                  {status}
                </button>
              ))}
            </div>
            <button
              className="accent-button"
              type="button"
              disabled={editorTransitionBlocked}
              onClick={handleCreate}
            >
              New exhibition
            </button>
          </div>
          {notice && (
            <div className="inline-notice" role="status">
              {notice}
            </div>
          )}
          {(saveState === "invalid" ||
            saveState === "error" ||
            saveState === "conflict") &&
            draft && (
            <div className="inline-notice" role="alert">
              <span aria-hidden="true">! </span>
              <span>
                {saveError ??
                  (saveState === "invalid"
                    ? "This draft has invalid fields and was not saved."
                    : "The server version changed while this draft was open.")}
              </span>{" "}
              {saveState === "error" && (
                <button
                  className="outlined-compact"
                  type="button"
                  disabled={saveRecoveryBusy}
                  onClick={handleRetrySave}
                >
                  Retry save
                </button>
              )}{" "}
              <button
                className="outlined-compact"
                type="button"
                disabled={saveRecoveryBusy}
                onClick={() => void handleDiscardAndReload()}
              >
                {saveRecoveryBusy ? "Reloading…" : "Discard changes and reload"}
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
          <span>{records.length} exhibitions</span>
          <span>Page 1</span>
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
          lookupsError={lookupsError}
          publishAllowed={staffRole !== "contributor"}
          lifecycleBusy={lifecycleBusy}
          media={visibleMedia}
          mediaLoading={mediaIsLoading}
          mediaBusy={mediaBusy}
          mediaError={mediaError}
          mediaEditable={mediaEditable}
          mediaReadOnlyReason={mediaReadOnlyReason}
          geocodeCandidates={
            geocodeResultWaitingForSave ? [] : geocodeCandidates
          }
          geocodeLoading={geocodeLoading || geocodeResultWaitingForSave}
          geocodeError={geocodeError}
          geocodingMode={geocodingService.mode}
          onSectionChange={setSection}
          onClose={() => {
            if (saveState !== "saved" || mediaBusyRef.current) {
              setNotice(
                "Retry or discard the current draft changes before closing the editor.",
              );
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
          onPreview={() => setPreviewOpen(true)}
          onPublish={() => setPublishOpen(true)}
          onArchive={() => setLifecycleAction("archive")}
          onRestore={() => setLifecycleAction("restore")}
          onManageMedia={() => void handleManageMedia()}
          onMediaUpload={handleMediaUpload}
          onMediaMetadataSave={handleMediaMetadataSave}
          onMediaReorder={handleMediaReorder}
          onMediaDetach={handleMediaDetach}
          onMediaErrorClear={() => setMediaError(null)}
          onFindCoordinates={() => void handleFindCoordinates()}
          onApplyGeocodeCandidate={handleApplyGeocodeCandidate}
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
      <div className="visually-hidden" aria-live="polite">
        {notice}
      </div>
    </div>
  );
}

export default function App() {
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

  if (!repository || !geocodingService) {
    return (
      <div className="login-shell">
        <aside className="login-rail" aria-label="gallr admin">
          <strong>gallr admin</strong>
          <span className="login-rail-mark" aria-hidden="true" />
        </aside>
        <main className="login-stage">
          <section
            className="access-denied"
            aria-labelledby="admin-configuration-title"
          >
            <h1 id="admin-configuration-title">
              Admin configuration required
            </h1>
            <p>
              Set both public Supabase environment variables before starting
              the admin. Temporary fixture data is available only when
              explicitly enabled in development or under the test harness.
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
      />
    );
  }

  return (
    <AuthGate client={supabase}>
      {(access, signOut) => (
        <AdminWorkspace
          repository={repository}
          geocodingService={geocodingService}
          staffRole={access.role}
          onSignOut={() => void signOut()}
        />
      )}
    </AuthGate>
  );
}
