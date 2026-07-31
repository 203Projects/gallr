import type {
  AdminExhibition,
  AdminExhibitionLookups,
  AdminExhibitionSubmission,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaMutationResult,
  AdminMediaRole,
  AdminSubmissionAcceptance,
  ExhibitionFilters,
  ExhibitionPatch,
  SubmissionFilters,
} from "../domain";
import {
  exhibitionFixtures,
  exhibitionLookupFixtures,
  submissionFixtures,
} from "../data/fixtures";
import { isPublishReady } from "../domain";
import {
  type AdminExhibitionRepository,
  RevisionConflictError,
} from "./AdminExhibitionRepository";
import {
  assertValidAdminMediaFile,
  readFileDataUrl,
  readImageDimensions,
  sha256File,
} from "./MediaFile";

type LifecycleAction = "publish" | "archive" | "restore";

interface LifecycleResult {
  action: LifecycleAction;
  exhibitionId: string;
  versionId: string;
  revision: number;
  result: AdminExhibition;
}

function copy<T>(value: T): T {
  return structuredClone(value);
}

export class InMemoryAdminExhibitionRepository
  implements AdminExhibitionRepository
{
  private records = copy(exhibitionFixtures);
  private submissions = copy(submissionFixtures);
  private mediaByVersion = new Map<string, AdminMediaAsset[]>();
  private lifecycleResults = new Map<string, LifecycleResult>();
  private deletedDraftRequests = new Map<
    string,
    {
      exhibitionId: string;
      versionId: string;
      revision: number;
    }
  >();
  private submissionResults = new Map<
    string,
    AdminSubmissionAcceptance | AdminExhibitionSubmission
  >();

  async list(filters: ExhibitionFilters): Promise<AdminExhibition[]> {
    const query = filters.search.trim().toLocaleLowerCase();
    return copy(
      this.records.filter((record) => {
        const matchesStatus =
          filters.status === "All" || record.status === filters.status;
        const matchesSearch =
          query.length === 0 ||
          [
            record.id,
            record.nameKo,
            record.nameEn,
            record.venueNameKo,
            record.venueNameEn,
          ].some((value) => value.toLocaleLowerCase().includes(query));
        return matchesStatus && matchesSearch;
      }),
    );
  }

  async getExhibitionLookups(): Promise<AdminExhibitionLookups> {
    return copy(exhibitionLookupFixtures);
  }

  async createDraft(): Promise<AdminExhibition> {
    const now = new Date().toISOString();
    const record: AdminExhibition = {
      id: crypto.randomUUID(),
      workingVersionId: crypto.randomUUID(),
      versionNumber: 1,
      publishedVersionId: null,
      hasUnpublishedChanges: true,
      nameKo: "",
      nameEn: "",
      venueNameKo: "",
      venueNameEn: "",
      cityKo: "서울",
      cityEn: "Seoul",
      regionKo: "",
      regionEn: "",
      addressKo: "",
      addressEn: "",
      latitude: "",
      longitude: "",
      openingDate: "",
      closingDate: "",
      descriptionKo: "",
      descriptionEn: "",
      creditsKo: "",
      creditsEn: "",
      hours: "",
      contact: "",
      receptionDate: "",
      receptionStartTime: "",
      eventId: "",
      editorId: "",
      ticketUrl: "",
      coverImageUrl: null,
      coverAltKo: "",
      coverAltEn: "",
      imageCredit: "",
      isFeatured: false,
      isHomepageFeatured: false,
      status: "Draft",
      revision: 1,
      updatedAt: now,
      updatedBy: "Current editor",
    };
    this.records.unshift(record);
    return copy(record);
  }

  async saveDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    patch: Partial<ExhibitionPatch>,
  ): Promise<AdminExhibition> {
    const index = this.records.findIndex((record) => record.id === id);
    if (index < 0) throw new Error("Exhibition not found.");
    const current = this.records[index];
    if (
      current.workingVersionId !== expectedVersionId ||
      current.revision !== expectedRevision
    ) {
      throw new RevisionConflictError(current.revision);
    }

    const workingDraft: AdminExhibition =
      current.status === "Published" && !current.hasUnpublishedChanges
        ? {
            ...current,
            workingVersionId: crypto.randomUUID(),
            versionNumber: current.versionNumber + 1,
            hasUnpublishedChanges: true,
            status: "Draft",
            revision: current.revision,
          }
        : current;

    const saved: AdminExhibition = {
      ...workingDraft,
      ...copy(patch),
      hasUnpublishedChanges: true,
      status: "Draft",
      revision: workingDraft.revision + 1,
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[index] = saved;
    return copy(saved);
  }

  async publish(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const repeated = this.readLifecycleResult(
      "publish",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (repeated) return repeated;
    const index = this.records.findIndex((record) => record.id === id);
    if (index < 0) throw new Error("Exhibition not found.");
    const current = this.records[index];
    if (
      current.workingVersionId !== expectedVersionId ||
      current.revision !== expectedRevision
    ) {
      throw new RevisionConflictError(current.revision);
    }
    if (!isPublishReady(current)) {
      throw new Error("Complete every required field before publishing.");
    }
    if (
      (this.mediaByVersion.get(current.workingVersionId) ?? []).some(
        (asset) => asset.status !== "published",
      )
    ) {
      throw new Error("Wait for every attached image to finish processing before publishing.");
    }

    const published: AdminExhibition = {
      ...current,
      publishedVersionId: current.workingVersionId,
      hasUnpublishedChanges: false,
      status: "Published",
      revision: current.revision + 1,
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[index] = published;
    return this.storeLifecycleResult(
      "publish",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
      published,
    );
  }

  async archive(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const repeated = this.readLifecycleResult(
      "archive",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (repeated) return repeated;
    const current = this.requireCurrent(
      id,
      expectedVersionId,
      expectedRevision,
    );
    const archived: AdminExhibition = {
      ...current.record,
      status: "Archived",
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[current.index] = archived;
    return this.storeLifecycleResult(
      "archive",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
      archived,
    );
  }

  async restore(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const repeated = this.readLifecycleResult(
      "restore",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (repeated) return repeated;
    const current = this.requireCurrent(
      id,
      expectedVersionId,
      expectedRevision,
    );
    const restored: AdminExhibition = {
      ...current.record,
      status:
        current.record.publishedVersionId !== null &&
        !current.record.hasUnpublishedChanges
          ? "Published"
          : "Draft",
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[current.index] = restored;
    return this.storeLifecycleResult(
      "restore",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
      restored,
    );
  }

  async deleteDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<void> {
    const repeated = this.deletedDraftRequests.get(requestId);
    if (repeated) {
      if (
        repeated.exhibitionId !== id ||
        repeated.versionId !== expectedVersionId ||
        repeated.revision !== expectedRevision
      ) {
        throw new Error("The deletion request ID was reused with different details.");
      }
      return;
    }

    const current = this.requireCurrent(
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (
      current.record.status !== "Draft" ||
      current.record.publishedVersionId !== null
    ) {
      throw new Error("Only never-published drafts can be deleted permanently.");
    }
    if ((this.mediaByVersion.get(expectedVersionId) ?? []).length > 0) {
      throw new Error("Remove every attached image before deleting this draft.");
    }

    this.records.splice(current.index, 1);
    this.mediaByVersion.delete(expectedVersionId);
    this.deletedDraftRequests.set(requestId, {
      exhibitionId: id,
      versionId: expectedVersionId,
      revision: expectedRevision,
    });
  }

  async listSubmissions(
    filters: SubmissionFilters,
  ): Promise<AdminExhibitionSubmission[]> {
    const query = filters.search.trim().toLocaleLowerCase();
    return copy(
      this.submissions.filter((submission) => {
        const matchesStatus =
          filters.status === "all" || submission.status === filters.status;
        const matchesSearch =
          query.length === 0 ||
          [
            submission.nameKo,
            submission.nameEn,
            submission.venueNameKo,
            submission.venueNameEn,
            submission.submitterEmail,
          ].some((value) => value.toLocaleLowerCase().includes(query));
        return matchesStatus && matchesSearch;
      }),
    );
  }

  async startSubmissionReview(id: string): Promise<AdminExhibitionSubmission> {
    const current = this.requireSubmission(id);
    if (current.submission.status === "submitted") {
      current.submission.status = "in_review";
    } else if (current.submission.status !== "in_review") {
      throw new Error("This submission can no longer be reviewed.");
    }
    return copy(current.submission);
  }

  async acceptSubmission(
    id: string,
    requestId: string,
  ): Promise<AdminSubmissionAcceptance> {
    const replay = this.submissionResults.get(requestId);
    if (replay) return copy(replay as AdminSubmissionAcceptance);
    const current = this.requireSubmission(id);
    if (
      current.submission.status !== "submitted" &&
      current.submission.status !== "in_review"
    ) {
      throw new Error("This submission can no longer be accepted.");
    }
    const exhibition = await this.createDraft();
    const acceptedDraft = await this.saveDraft(
      exhibition.id,
      exhibition.workingVersionId,
      exhibition.revision,
      {
        nameKo: current.submission.nameKo,
        nameEn: current.submission.nameEn,
        venueNameKo: current.submission.venueNameKo,
        venueNameEn: current.submission.venueNameEn,
        addressKo: current.submission.addressKo,
        addressEn: current.submission.addressEn,
        openingDate: current.submission.openingDate,
        closingDate: current.submission.closingDate,
        descriptionKo: current.submission.descriptionKo,
        descriptionEn: current.submission.descriptionEn,
        hours: current.submission.hours,
        receptionDate: current.submission.receptionDate.slice(0, 10),
        receptionStartTime: current.submission.receptionDate.slice(11, 16),
      },
    );
    current.submission.status = "accepted";
    current.submission.acceptedExhibitionId = acceptedDraft.id;
    current.submission.reviewedAt = new Date().toISOString();
    const result = {
      submission: copy(current.submission),
      exhibition: acceptedDraft,
    };
    this.submissionResults.set(requestId, copy(result));
    return result;
  }

  async rejectSubmission(
    id: string,
    reviewNotes: string,
    requestId: string,
  ): Promise<AdminExhibitionSubmission> {
    const replay = this.submissionResults.get(requestId);
    if (replay) return copy(replay as AdminExhibitionSubmission);
    if (!reviewNotes.trim()) throw new Error("Add a reason before rejecting.");
    const current = this.requireSubmission(id);
    if (
      current.submission.status !== "submitted" &&
      current.submission.status !== "in_review"
    ) {
      throw new Error("This submission can no longer be rejected.");
    }
    current.submission.status = "rejected";
    current.submission.reviewNotes = reviewNotes.trim();
    current.submission.reviewedAt = new Date().toISOString();
    this.submissionResults.set(requestId, copy(current.submission));
    return copy(current.submission);
  }

  private requireSubmission(id: string): {
    index: number;
    submission: AdminExhibitionSubmission;
  } {
    const index = this.submissions.findIndex((submission) => submission.id === id);
    if (index < 0) throw new Error("Submission not found.");
    return { index, submission: this.submissions[index] };
  }

  async listMedia(
    exhibitionId: string,
    versionId: string,
  ): Promise<AdminMediaAsset[]> {
    const exhibition = this.records.find((record) => record.id === exhibitionId);
    if (!exhibition) throw new Error("Exhibition not found.");
    if (
      exhibition.workingVersionId !== versionId &&
      exhibition.publishedVersionId !== versionId
    ) {
      throw new Error("Exhibition version not found.");
    }
    return copy(this.mediaByVersion.get(versionId) ?? []);
  }

  async uploadAndAttachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    file: File,
    role: AdminMediaRole,
  ): Promise<AdminMediaMutationResult> {
    assertValidAdminMediaFile(file);
    const current = this.requireDraftCurrent(
      exhibitionId,
      expectedVersionId,
      expectedRevision,
    );
    const [dimensions, previewUrl, checksumSha256] = await Promise.all([
      readImageDimensions(file).catch(() => null),
      readFileDataUrl(file),
      globalThis.crypto?.subtle ? sha256File(file) : Promise.resolve(null),
    ]);
    const now = new Date().toISOString();
    const existing = this.mediaByVersion.get(expectedVersionId) ?? [];
    const asset: AdminMediaAsset = {
      assetId: crypto.randomUUID(),
      versionId: current.record.workingVersionId,
      role,
      sortOrder:
        role === "cover"
          ? 0
          : existing.filter((item) => item.role === "gallery").length + 1,
      status: "ready",
      bucketId: "exhibition-media",
      objectPath: `${exhibitionId}/${crypto.randomUUID()}-${file.name}`,
      mimeType: file.type,
      byteSize: file.size,
      width: dimensions?.width ?? null,
      height: dimensions?.height ?? null,
      checksumSha256,
      publicUrl: null,
      altKo: "",
      altEn: "",
      credit: "",
      rightsUrl: "",
      originalFilename: file.name,
      createdAt: now,
      updatedAt: now,
      previewUrl,
    };

    const next =
      role === "cover"
        ? [
            ...existing.map((item) =>
              item.role === "cover" ? { ...item, role: "gallery" as const } : item,
            ),
            asset,
          ]
        : [...existing, asset];
    return this.commitMediaMutation(current.index, this.normalizeMedia(next));
  }

  async updateMediaMetadata(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
    patch: AdminMediaMetadataPatch,
  ): Promise<AdminMediaMutationResult> {
    const current = this.requireDraftCurrent(
      exhibitionId,
      expectedVersionId,
      expectedRevision,
    );
    const existing = this.mediaByVersion.get(expectedVersionId) ?? [];
    if (!existing.some((asset) => asset.assetId === assetId)) {
      throw new Error("Image attachment not found.");
    }
    const now = new Date().toISOString();
    const next = existing.map((asset) =>
      asset.assetId === assetId
        ? {
            ...asset,
            altKo: patch.altKo,
            altEn: patch.altEn,
            credit: patch.credit,
            rightsUrl: patch.rightsUrl,
            updatedAt: now,
          }
        : asset,
    );
    return this.commitMediaMutation(current.index, this.normalizeMedia(next));
  }

  async reorderMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    orderedAssetIds: string[],
  ): Promise<AdminMediaMutationResult> {
    const current = this.requireDraftCurrent(
      exhibitionId,
      expectedVersionId,
      expectedRevision,
    );
    const existing = this.mediaByVersion.get(expectedVersionId) ?? [];
    const gallery = existing.filter((asset) => asset.role === "gallery");
    const expectedIds = new Set(gallery.map((asset) => asset.assetId));
    const suppliedIds = new Set(orderedAssetIds);
    if (
      suppliedIds.size !== orderedAssetIds.length ||
      suppliedIds.size !== expectedIds.size ||
      orderedAssetIds.some((assetId) => !expectedIds.has(assetId))
    ) {
      throw new Error("Gallery order must include every gallery image exactly once.");
    }
    const byId = new Map(existing.map((asset) => [asset.assetId, asset]));
    const cover = existing.filter((asset) => asset.role === "cover");
    const reordered = [
      ...cover,
      ...orderedAssetIds.map((assetId) => byId.get(assetId) as AdminMediaAsset),
    ];
    return this.commitMediaMutation(
      current.index,
      this.normalizeMedia(reordered),
    );
  }

  async detachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
  ): Promise<AdminMediaMutationResult> {
    const current = this.requireDraftCurrent(
      exhibitionId,
      expectedVersionId,
      expectedRevision,
    );
    const existing = this.mediaByVersion.get(expectedVersionId) ?? [];
    if (!existing.some((asset) => asset.assetId === assetId)) {
      throw new Error("Image attachment not found.");
    }
    return this.commitMediaMutation(
      current.index,
      this.normalizeMedia(
        existing.filter((asset) => asset.assetId !== assetId),
      ),
    );
  }

  private normalizeMedia(media: AdminMediaAsset[]): AdminMediaAsset[] {
    const cover = media.find((asset) => asset.role === "cover");
    const gallery = media
      .filter((asset) => asset.role === "gallery")
      .sort((left, right) => left.sortOrder - right.sortOrder);
    return [
      ...(cover ? [{ ...cover, sortOrder: 0 }] : []),
      ...gallery.map((asset, index) => ({ ...asset, sortOrder: index + 1 })),
    ];
  }

  private commitMediaMutation(
    recordIndex: number,
    media: AdminMediaAsset[],
  ): AdminMediaMutationResult {
    const current = this.records[recordIndex];
    const cover = media.find((asset) => asset.role === "cover");
    const exhibition: AdminExhibition = {
      ...current,
      coverImageUrl: cover?.publicUrl ?? cover?.previewUrl ?? null,
      coverAltKo: cover?.altKo ?? "",
      coverAltEn: cover?.altEn ?? "",
      imageCredit: cover?.credit ?? "",
      hasUnpublishedChanges: true,
      status: "Draft",
      revision: current.revision + 1,
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[recordIndex] = exhibition;
    this.mediaByVersion.set(current.workingVersionId, copy(media));
    return { exhibition: copy(exhibition), media: copy(media) };
  }

  private requireDraftCurrent(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
  ): { index: number; record: AdminExhibition } {
    const current = this.requireCurrent(id, expectedVersionId, expectedRevision);
    if (current.record.status !== "Draft") {
      throw new Error("Media can only be changed on a draft exhibition.");
    }
    return current;
  }

  private readLifecycleResult(
    action: LifecycleAction,
    requestId: string,
    exhibitionId: string,
    versionId: string,
    revision: number,
  ): AdminExhibition | null {
    const stored = this.lifecycleResults.get(requestId);
    if (!stored) return null;
    if (
      stored.action !== action ||
      stored.exhibitionId !== exhibitionId ||
      stored.versionId !== versionId ||
      stored.revision !== revision
    ) {
      throw new Error("The lifecycle request ID was already used for another command.");
    }
    return copy(stored.result);
  }

  private storeLifecycleResult(
    action: LifecycleAction,
    requestId: string,
    exhibitionId: string,
    versionId: string,
    revision: number,
    result: AdminExhibition,
  ): AdminExhibition {
    this.lifecycleResults.set(requestId, {
      action,
      exhibitionId,
      versionId,
      revision,
      result: copy(result),
    });
    return copy(result);
  }

  private requireCurrent(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
  ): { index: number; record: AdminExhibition } {
    const index = this.records.findIndex((record) => record.id === id);
    if (index < 0) throw new Error("Exhibition not found.");
    const record = this.records[index];
    if (
      record.workingVersionId !== expectedVersionId ||
      record.revision !== expectedRevision
    ) {
      throw new RevisionConflictError(record.revision);
    }
    return { index, record };
  }
}
