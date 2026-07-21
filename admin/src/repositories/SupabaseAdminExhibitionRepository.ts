import type { SupabaseClient } from "@supabase/supabase-js";
import type {
  AdminExhibition,
  AdminExhibitionLookups,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaMutationResult,
  AdminMediaRole,
  AdminMediaStatus,
  AdminMediaUploadTarget,
  ExhibitionFilters,
  ExhibitionPatch,
  ExhibitionStatus,
} from "../domain";
import {
  type AdminExhibitionRepository,
  RevisionConflictError,
} from "./AdminExhibitionRepository";
import {
  ADMIN_MEDIA_MIME_TYPES,
  assertValidAdminMediaFile,
  readImageDimensions,
  sha256File,
} from "./MediaFile";

type JsonRecord = Record<string, unknown>;

interface RpcErrorLike {
  code?: unknown;
  details?: unknown;
  hint?: unknown;
  message?: unknown;
}

export class MalformedAdminExhibitionPayloadError extends Error {
  constructor(
    readonly rpcName: string,
    readonly path: string,
    expected: string,
    actual: unknown,
  ) {
    super(
      `${rpcName} returned malformed data at ${path}: expected ${expected}, received ${describeType(actual)}.`,
    );
    this.name = "MalformedAdminExhibitionPayloadError";
  }
}

function describeType(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  return typeof value;
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readRecord(value: unknown, rpcName: string, path: string): JsonRecord {
  if (!isRecord(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      path,
      "an object",
      value,
    );
  }
  return value;
}

function readString(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string {
  const value = record[key];
  if (typeof value !== "string") {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a string",
      value,
    );
  }
  return value;
}

function readNonEmptyString(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string {
  const value = readString(record, key, rpcName, path);
  if (value.trim().length === 0) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a non-empty string",
      value,
    );
  }
  return value;
}

function readBoolean(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): boolean {
  const value = record[key];
  if (typeof value !== "boolean") {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a boolean",
      value,
    );
  }
  return value;
}

function readPositiveInteger(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): number {
  const value = record[key];
  if (!Number.isSafeInteger(value) || (value as number) < 1) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a positive integer",
      value,
    );
  }
  return value as number;
}

function readNonNegativeInteger(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): number {
  const value = record[key];
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a non-negative integer",
      value,
    );
  }
  return value as number;
}

function readNullablePositiveInteger(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): number | null {
  const value = record[key];
  if (value === null) return null;
  if (!Number.isSafeInteger(value) || (value as number) < 1) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a positive integer or null",
      value,
    );
  }
  return value as number;
}

function readNullableString(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string | null {
  const value = record[key];
  if (value !== null && typeof value !== "string") {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a string or null",
      value,
    );
  }
  return value;
}

function readNullableNonEmptyString(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): string | null {
  const value = readNullableString(record, key, rpcName, path);
  if (value !== null && value.trim().length === 0) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "a non-empty string or null",
      value,
    );
  }
  return value;
}

function readStatus(
  record: JsonRecord,
  rpcName: string,
  path: string,
): ExhibitionStatus {
  const value = readString(record, "status", rpcName, path).toLowerCase();
  switch (value) {
    case "draft":
      return "Draft";
    case "published":
      return "Published";
    case "archived":
      return "Archived";
    default:
      throw new MalformedAdminExhibitionPayloadError(
        rpcName,
        `${path}.status`,
        'one of "draft", "published", or "archived"',
        record.status,
      );
  }
}

function mapExhibition(
  value: unknown,
  rpcName: string,
  path: string,
): AdminExhibition {
  const record = readRecord(value, rpcName, path);
  return {
    id: readNonEmptyString(record, "id", rpcName, path),
    workingVersionId: readNonEmptyString(
      record,
      "working_version_id",
      rpcName,
      path,
    ),
    versionNumber: readPositiveInteger(
      record,
      "version_number",
      rpcName,
      path,
    ),
    publishedVersionId: readNullableNonEmptyString(
      record,
      "published_version_id",
      rpcName,
      path,
    ),
    hasUnpublishedChanges: readBoolean(
      record,
      "has_unpublished_changes",
      rpcName,
      path,
    ),
    nameKo: readString(record, "name_ko", rpcName, path),
    nameEn: readString(record, "name_en", rpcName, path),
    venueNameKo: readString(record, "venue_name_ko", rpcName, path),
    venueNameEn: readString(record, "venue_name_en", rpcName, path),
    cityKo: readString(record, "city_ko", rpcName, path),
    cityEn: readString(record, "city_en", rpcName, path),
    regionKo: readString(record, "region_ko", rpcName, path),
    regionEn: readString(record, "region_en", rpcName, path),
    addressKo: readString(record, "address_ko", rpcName, path),
    addressEn: readString(record, "address_en", rpcName, path),
    latitude: readString(record, "latitude", rpcName, path),
    longitude: readString(record, "longitude", rpcName, path),
    openingDate: readString(record, "opening_date", rpcName, path),
    closingDate: readString(record, "closing_date", rpcName, path),
    descriptionKo: readString(record, "description_ko", rpcName, path),
    descriptionEn: readString(record, "description_en", rpcName, path),
    hours: readString(record, "hours", rpcName, path),
    contact: readString(record, "contact", rpcName, path),
    receptionDate: readString(record, "reception_date", rpcName, path),
    receptionStartTime: readString(
      record,
      "reception_start_time",
      rpcName,
      path,
    ),
    eventId: readString(record, "event_id", rpcName, path),
    editorId: readString(record, "editor_id", rpcName, path),
    ticketUrl: readString(record, "ticket_url", rpcName, path),
    coverImageUrl: readNullableString(
      record,
      "cover_image_url",
      rpcName,
      path,
    ),
    coverAltKo: readString(record, "cover_alt_ko", rpcName, path),
    coverAltEn: readString(record, "cover_alt_en", rpcName, path),
    imageCredit: readString(record, "image_credit", rpcName, path),
    isFeatured: readBoolean(record, "is_featured", rpcName, path),
    isHomepageFeatured: readBoolean(
      record,
      "is_homepage_featured",
      rpcName,
      path,
    ),
    status: readStatus(record, rpcName, path),
    revision: readPositiveInteger(record, "revision", rpcName, path),
    updatedAt: readNonEmptyString(record, "updated_at", rpcName, path),
    updatedBy: readNonEmptyString(record, "updated_by", rpcName, path),
  };
}

function mapList(value: unknown, rpcName: string): AdminExhibition[] {
  if (!Array.isArray(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      "$",
      "an array",
      value,
    );
  }
  return value.map((record, index) =>
    mapExhibition(record, rpcName, `$[${index}]`),
  );
}

function readArray(
  record: JsonRecord,
  key: string,
  rpcName: string,
  path: string,
): unknown[] {
  const value = record[key];
  if (!Array.isArray(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.${key}`,
      "an array",
      value,
    );
  }
  return value;
}

function mapExhibitionLookups(
  value: unknown,
  rpcName: string,
): AdminExhibitionLookups {
  const record = readRecord(value, rpcName, "$");
  const events = readArray(record, "events", rpcName, "$").map(
    (item, index) => {
      const path = `$.events[${index}]`;
      const event = readRecord(item, rpcName, path);
      return {
        id: readNonEmptyString(event, "id", rpcName, path),
        nameKo: readString(event, "name_ko", rpcName, path),
        nameEn: readString(event, "name_en", rpcName, path),
        locationLabelKo: readString(
          event,
          "location_label_ko",
          rpcName,
          path,
        ),
        locationLabelEn: readString(
          event,
          "location_label_en",
          rpcName,
          path,
        ),
        startDate: readString(event, "start_date", rpcName, path),
        endDate: readString(event, "end_date", rpcName, path),
        shortLabel: readNullableString(event, "short_label", rpcName, path),
        isActive: readBoolean(event, "is_active", rpcName, path),
      };
    },
  );
  const editors = readArray(record, "editors", rpcName, "$").map(
    (item, index) => {
      const path = `$.editors[${index}]`;
      const editor = readRecord(item, rpcName, path);
      return {
        id: readNonEmptyString(editor, "id", rpcName, path),
        nameKo: readString(editor, "name_ko", rpcName, path),
        nameEn: readString(editor, "name_en", rpcName, path),
        titleKo: readString(editor, "title_ko", rpcName, path),
        titleEn: readString(editor, "title_en", rpcName, path),
        isActive: readBoolean(editor, "is_active", rpcName, path),
        activeFrom: readNullableString(
          editor,
          "active_from",
          rpcName,
          path,
        ),
        activeTo: readNullableString(editor, "active_to", rpcName, path),
      };
    },
  );
  return { events, editors };
}

function readMediaRole(
  record: JsonRecord,
  rpcName: string,
  path: string,
): AdminMediaRole {
  const value = readString(record, "role", rpcName, path);
  if (value === "cover" || value === "gallery") return value;
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    `${path}.role`,
    '"cover" or "gallery"',
    value,
  );
}

function readMediaStatus(
  record: JsonRecord,
  rpcName: string,
  path: string,
): AdminMediaStatus {
  const value = readString(record, "status", rpcName, path);
  if (
    value === "pending_upload" ||
    value === "ready" ||
    value === "published" ||
    value === "orphaned" ||
    value === "rejected"
  ) {
    return value;
  }
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    `${path}.status`,
    "a supported media status",
    value,
  );
}

function readMediaMimeType(
  record: JsonRecord,
  rpcName: string,
  path: string,
): string {
  const value = readNonEmptyString(record, "mime_type", rpcName, path);
  if ((ADMIN_MEDIA_MIME_TYPES as readonly string[]).includes(value)) return value;
  throw new MalformedAdminExhibitionPayloadError(
    rpcName,
    `${path}.mime_type`,
    "a supported image MIME type",
    value,
  );
}

function readNullableChecksum(
  record: JsonRecord,
  rpcName: string,
  path: string,
): string | null {
  const value = readNullableString(
    record,
    "checksum_sha256",
    rpcName,
    path,
  );
  if (value !== null && !/^[0-9a-f]{64}$/.test(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      `${path}.checksum_sha256`,
      "a lowercase SHA-256 digest or null",
      value,
    );
  }
  return value;
}

function mapMediaAsset(
  value: unknown,
  rpcName: string,
  path: string,
): AdminMediaAsset {
  const record = readRecord(value, rpcName, path);
  const role = readMediaRole(record, rpcName, path);
  const sortOrder = readNonNegativeInteger(record, "sort_order", rpcName, path);
  const status = readMediaStatus(record, rpcName, path);
  const width = readNullablePositiveInteger(record, "width", rpcName, path);
  const height = readNullablePositiveInteger(record, "height", rpcName, path);
  const publicUrl = readNullableString(record, "public_url", rpcName, path);
  if ((width === null) !== (height === null)) {
    throw new Error(`${rpcName} returned incomplete dimensions at ${path}.`);
  }
  if ((role === "cover" && sortOrder !== 0) || (role === "gallery" && sortOrder < 1)) {
    throw new Error(`${rpcName} returned an invalid media order at ${path}.`);
  }
  if (status === "published" && publicUrl === null) {
    throw new Error(`${rpcName} returned published media without a public URL at ${path}.`);
  }
  return {
    assetId: readNonEmptyString(record, "asset_id", rpcName, path),
    versionId: readNonEmptyString(record, "version_id", rpcName, path),
    role,
    sortOrder,
    status,
    bucketId: readNonEmptyString(record, "bucket_id", rpcName, path),
    objectPath: readNonEmptyString(record, "object_path", rpcName, path),
    mimeType: readMediaMimeType(record, rpcName, path),
    byteSize: readPositiveInteger(record, "byte_size", rpcName, path),
    width,
    height,
    checksumSha256: readNullableChecksum(record, rpcName, path),
    publicUrl,
    altKo: readString(record, "alt_ko", rpcName, path),
    altEn: readString(record, "alt_en", rpcName, path),
    credit: readString(record, "credit", rpcName, path),
    rightsUrl: readString(record, "rights_url", rpcName, path),
    originalFilename: readString(
      record,
      "original_filename",
      rpcName,
      path,
    ),
    createdAt: readNonEmptyString(record, "created_at", rpcName, path),
    updatedAt: readNonEmptyString(record, "updated_at", rpcName, path),
    previewUrl: null,
  };
}

function mapMediaList(
  value: unknown,
  rpcName: string,
  path = "$",
): AdminMediaAsset[] {
  if (!Array.isArray(value)) {
    throw new MalformedAdminExhibitionPayloadError(
      rpcName,
      path,
      "an array",
      value,
    );
  }
  return value.map((record, index) =>
    mapMediaAsset(record, rpcName, `${path}[${index}]`),
  );
}

function mapUploadTarget(
  value: unknown,
  rpcName: string,
): AdminMediaUploadTarget {
  const record = readRecord(value, rpcName, "$");
  return {
    assetId: readNonEmptyString(record, "asset_id", rpcName, "$"),
    bucketId: readNonEmptyString(record, "bucket_id", rpcName, "$"),
    objectPath: readNonEmptyString(record, "object_path", rpcName, "$"),
    mimeType: readMediaMimeType(record, rpcName, "$"),
    byteSize: readPositiveInteger(record, "byte_size", rpcName, "$"),
    originalFilename: readString(record, "original_filename", rpcName, "$"),
    status: readMediaStatus(record, rpcName, "$"),
  };
}

function validateFinalizedAsset(
  value: unknown,
  rpcName: string,
  target: AdminMediaUploadTarget,
): void {
  const record = readRecord(value, rpcName, "$");
  const assetId = readNonEmptyString(record, "asset_id", rpcName, "$");
  const bucketId = readNonEmptyString(record, "bucket_id", rpcName, "$");
  const objectPath = readNonEmptyString(record, "object_path", rpcName, "$");
  readMediaStatus(record, rpcName, "$");
  readMediaMimeType(record, rpcName, "$");
  readPositiveInteger(record, "byte_size", rpcName, "$");
  readNullablePositiveInteger(record, "width", rpcName, "$");
  readNullablePositiveInteger(record, "height", rpcName, "$");
  readNullableChecksum(record, rpcName, "$");
  if (
    assetId !== target.assetId ||
    bucketId !== target.bucketId ||
    objectPath !== target.objectPath
  ) {
    throw new Error(`${rpcName} returned an asset that does not match the upload reservation.`);
  }
}

function mapMediaMutation(
  value: unknown,
  rpcName: string,
): AdminMediaMutationResult {
  const record = readRecord(value, rpcName, "$");
  return {
    exhibition: mapExhibition(record.exhibition, rpcName, "$.exhibition"),
    media: mapMediaList(record.media, rpcName, "$.media"),
  };
}

function throwStorageError(operation: string, error: unknown): never {
  const record = isRecord(error) ? error : {};
  const message =
    readErrorText(record.message) ?? "Unknown Supabase Storage error.";
  const statusCode =
    typeof record.statusCode === "string" || typeof record.statusCode === "number"
      ? ` [${String(record.statusCode)}]`
      : "";
  throw new Error(`${operation} failed${statusCode}: ${message}`);
}

function parseServerRevision(value: unknown): number | null {
  if (typeof value === "number") {
    return Number.isInteger(value) && value >= 1 ? value : null;
  }
  if (typeof value !== "string" || !/^\d+$/.test(value.trim())) return null;
  const revision = Number(value.trim());
  return Number.isSafeInteger(revision) && revision >= 1 ? revision : null;
}

function readErrorText(value: unknown): string | null {
  return typeof value === "string" && value.trim().length > 0
    ? value.trim()
    : null;
}

function throwRpcError(rpcName: string, error: RpcErrorLike): never {
  const code = readErrorText(error.code);
  const message = readErrorText(error.message);

  if (code === "40001" && message?.toLowerCase() === "revision_conflict") {
    const serverRevision = parseServerRevision(error.details);
    if (serverRevision !== null) {
      throw new RevisionConflictError(serverRevision);
    }
    throw new Error(
      `${rpcName} reported revision_conflict without a valid positive integer server revision in error.details.`,
    );
  }

  const details = readErrorText(error.details);
  const hint = readErrorText(error.hint);
  const codeSuffix = code === null ? "" : ` [${code}]`;
  const detailSuffix = details === null ? "" : ` Details: ${details}`;
  const hintSuffix = hint === null ? "" : ` Hint: ${hint}`;
  throw new Error(
    `${rpcName} failed${codeSuffix}: ${message ?? "Unknown Supabase error."}${detailSuffix}${hintSuffix}`,
  );
}

function serializePatch(patch: ExhibitionPatch): JsonRecord {
  return {
    name_ko: patch.nameKo,
    name_en: patch.nameEn,
    venue_name_ko: patch.venueNameKo,
    venue_name_en: patch.venueNameEn,
    city_ko: patch.cityKo,
    city_en: patch.cityEn,
    region_ko: patch.regionKo,
    region_en: patch.regionEn,
    address_ko: patch.addressKo,
    address_en: patch.addressEn,
    latitude: patch.latitude,
    longitude: patch.longitude,
    opening_date: patch.openingDate,
    closing_date: patch.closingDate,
    description_ko: patch.descriptionKo,
    description_en: patch.descriptionEn,
    hours: patch.hours,
    contact: patch.contact,
    reception_date: patch.receptionDate,
    reception_start_time: patch.receptionStartTime,
    event_id: patch.eventId,
    editor_id: patch.editorId,
    ticket_url: patch.ticketUrl,
    is_featured: patch.isFeatured,
    is_homepage_featured: patch.isHomepageFeatured,
  };
}

export class SupabaseAdminExhibitionRepository
  implements AdminExhibitionRepository
{
  constructor(private readonly client: SupabaseClient) {}

  async list(filters: ExhibitionFilters): Promise<AdminExhibition[]> {
    const rpcName = "admin_list_exhibitions";
    const { data, error } = await this.client.rpc(rpcName, {
      p_search: filters.search.trim(),
      p_status:
        filters.status === "All" ? null : filters.status.toLowerCase(),
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapList(data, rpcName);
  }

  async getExhibitionLookups(): Promise<AdminExhibitionLookups> {
    const rpcName = "admin_get_exhibition_lookups";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibitionLookups(data, rpcName);
  }

  async createDraft(): Promise<AdminExhibition> {
    const rpcName = "admin_create_exhibition_draft";
    const { data, error } = await this.client.rpc(rpcName);
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async saveDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    patch: ExhibitionPatch,
  ): Promise<AdminExhibition> {
    const rpcName = "admin_save_exhibition_draft";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: id,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_patch: serializePatch(patch),
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async publish(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const rpcName = "admin_publish_exhibition";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: id,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async archive(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    return this.runVersionCommand(
      "admin_archive_exhibition",
      id,
      expectedVersionId,
      expectedRevision,
      requestId,
    );
  }

  async restore(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    return this.runVersionCommand(
      "admin_restore_exhibition",
      id,
      expectedVersionId,
      expectedRevision,
      requestId,
    );
  }

  private async runVersionCommand(
    rpcName: "admin_archive_exhibition" | "admin_restore_exhibition",
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: id,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_request_id: requestId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return mapExhibition(data, rpcName, "$");
  }

  async listMedia(
    exhibitionId: string,
    versionId: string,
  ): Promise<AdminMediaAsset[]> {
    const rpcName = "admin_list_exhibition_media";
    const { data, error } = await this.client.rpc(rpcName, {
      p_exhibition_id: exhibitionId,
      p_version_id: versionId,
    });
    if (error !== null) throwRpcError(rpcName, error);
    return this.hydratePreviewUrls(mapMediaList(data, rpcName));
  }

  async uploadAndAttachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    file: File,
    role: AdminMediaRole,
  ): Promise<AdminMediaMutationResult> {
    assertValidAdminMediaFile(file);

    let dimensions: { width: number; height: number } | null;
    try {
      dimensions = await readImageDimensions(file);
    } catch {
      throw new Error("The selected image could not be decoded.");
    }
    const checksum = await sha256File(file);

    const requestRpc = "admin_request_media_upload";
    const requestResponse = await this.client.rpc(requestRpc, {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_filename: file.name,
      p_mime_type: file.type,
      p_byte_size: file.size,
    });
    if (requestResponse.error !== null) {
      throwRpcError(requestRpc, requestResponse.error);
    }
    const target = mapUploadTarget(requestResponse.data, requestRpc);
    if (
      target.mimeType !== file.type ||
      target.byteSize !== file.size ||
      target.originalFilename !== file.name ||
      target.status !== "pending_upload"
    ) {
      throw new Error(`${requestRpc} returned a reservation that does not match the selected file.`);
    }

    const storage = this.client.storage.from(target.bucketId);
    const signedUpload = await storage.createSignedUploadUrl(target.objectPath, {
      upsert: false,
    });
    if (signedUpload.error !== null) {
      throwStorageError("Creating the signed image upload", signedUpload.error);
    }
    const uploaded = await storage.uploadToSignedUrl(
      target.objectPath,
      signedUpload.data.token,
      file,
      { contentType: file.type, upsert: false },
    );
    if (uploaded.error !== null) {
      throwStorageError("Uploading the image", uploaded.error);
    }

    const finalizeRpc = "admin_finalize_media_upload";
    const finalizeResponse = await this.client.rpc(finalizeRpc, {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_asset_id: target.assetId,
      p_width: dimensions?.width ?? null,
      p_height: dimensions?.height ?? null,
      p_checksum_sha256: checksum,
    });
    if (finalizeResponse.error !== null) {
      throwRpcError(finalizeRpc, finalizeResponse.error);
    }
    validateFinalizedAsset(finalizeResponse.data, finalizeRpc, target);

    return this.runMediaMutation("admin_attach_exhibition_media", {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_asset_id: target.assetId,
      p_role: role,
    });
  }

  async updateMediaMetadata(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
    patch: AdminMediaMetadataPatch,
  ): Promise<AdminMediaMutationResult> {
    return this.runMediaMutation("admin_update_exhibition_media_metadata", {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_asset_id: assetId,
      p_alt_ko: patch.altKo,
      p_alt_en: patch.altEn,
      p_credit: patch.credit,
      p_rights_url: patch.rightsUrl,
    });
  }

  async reorderMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    orderedAssetIds: string[],
  ): Promise<AdminMediaMutationResult> {
    if (new Set(orderedAssetIds).size !== orderedAssetIds.length) {
      throw new Error("Gallery order contains a duplicate image.");
    }
    return this.runMediaMutation("admin_reorder_exhibition_media", {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_ordered_asset_ids: orderedAssetIds,
    });
  }

  async detachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
  ): Promise<AdminMediaMutationResult> {
    return this.runMediaMutation("admin_detach_exhibition_media", {
      p_exhibition_id: exhibitionId,
      p_expected_version_id: expectedVersionId,
      p_expected_revision: expectedRevision,
      p_asset_id: assetId,
    });
  }

  private async runMediaMutation(
    rpcName:
      | "admin_attach_exhibition_media"
      | "admin_update_exhibition_media_metadata"
      | "admin_reorder_exhibition_media"
      | "admin_detach_exhibition_media",
    args: JsonRecord,
  ): Promise<AdminMediaMutationResult> {
    const { data, error } = await this.client.rpc(rpcName, args);
    if (error !== null) throwRpcError(rpcName, error);
    const result = mapMediaMutation(data, rpcName);
    return {
      exhibition: result.exhibition,
      media: await this.hydratePreviewUrls(result.media),
    };
  }

  private async hydratePreviewUrls(
    media: AdminMediaAsset[],
  ): Promise<AdminMediaAsset[]> {
    return Promise.all(
      media.map(async (asset) => {
        if (asset.publicUrl !== null) {
          return { ...asset, previewUrl: asset.publicUrl };
        }
        if (
          asset.status === "pending_upload" ||
          asset.status === "orphaned" ||
          asset.status === "rejected"
        ) {
          return asset;
        }
        const preview = await this.client.storage
          .from(asset.bucketId)
          .createSignedUrl(asset.objectPath, 15 * 60);
        if (preview.error !== null) {
          throwStorageError("Creating the image preview", preview.error);
        }
        return { ...asset, previewUrl: preview.data.signedUrl };
      }),
    );
  }
}
