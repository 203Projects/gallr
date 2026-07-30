import "@supabase/functions-js/edge-runtime.d.ts";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";

import { validateWorkerToken } from "./auth.ts";
import {
  extensionForMime,
  type ImageInspection,
  ImageValidationError,
  inspectImageBytes,
  MAX_IMAGE_BYTES,
} from "./image.ts";
import {
  MediaSourcePathError,
  validateSourcePath as validateCanonicalSourcePath,
} from "./path.ts";

type JsonObject = Record<string, unknown>;

interface OutboxEvent {
  id: string;
  aggregate_type: string;
  aggregate_id: string;
  event_type: string;
  payload: JsonObject;
  deduplication_key: string | null;
  attempts: number;
  max_attempts: number;
  lease_token: string;
  lease_owner: string;
  locked_until: string;
  created_at: string;
}

interface CleanupPreparation {
  asset_id: string;
  already_purged: boolean;
  purge_token?: string;
  source_bucket_id: string;
  source_object_path: string;
  delivery_bucket_id?: string | null;
  delivery_object_path?: string | null;
}

interface WorkerConfig {
  leaseSeconds: number;
  staleUploadHours: number;
  sweepBatchSize: number;
  deliveryUrl?: string;
  deliveryToken?: string;
  deliveryTimeoutMs: number;
}

class WorkerError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "WorkerError";
  }
}

class RpcError extends WorkerError {
  constructor(
    readonly rpcCode: string | undefined,
    message: string,
    readonly details?: string,
  ) {
    super("database_rpc_failed", message);
    this.name = "RpcError";
  }
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) {
    throw new WorkerError(
      "worker_configuration_missing",
      `${name} is required.`,
    );
  }
  return value;
}

function boundedIntegerEnv(
  name: string,
  fallback: number,
  minimum: number,
  maximum: number,
): number {
  const raw = Deno.env.get(name)?.trim();
  if (!raw) return fallback;
  if (!/^\d+$/.test(raw)) {
    throw new WorkerError(
      "worker_configuration_invalid",
      `${name} must be an integer.`,
    );
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new WorkerError(
      "worker_configuration_invalid",
      `${name} must be between ${minimum} and ${maximum}.`,
    );
  }
  return value;
}

function serviceCredential(): string {
  const direct = Deno.env.get("SUPABASE_SECRET_KEY")?.trim() ||
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  if (direct) return direct;

  const encodedKeys = Deno.env.get("SUPABASE_SECRET_KEYS")?.trim();
  if (encodedKeys) {
    try {
      const parsed: unknown = JSON.parse(encodedKeys);
      if (typeof parsed === "string" && parsed.trim()) return parsed.trim();
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        const keys = parsed as Record<string, unknown>;
        for (const name of ["outbox-worker", "service_role", "default"]) {
          if (typeof keys[name] === "string" && keys[name].trim()) {
            return keys[name].trim();
          }
        }
        const first = Object.values(keys).find((value) =>
          typeof value === "string" && value.trim()
        );
        if (typeof first === "string") return first.trim();
      }
    } catch {
      throw new WorkerError(
        "worker_configuration_invalid",
        "SUPABASE_SECRET_KEYS must be a JSON string or object.",
      );
    }
  }

  throw new WorkerError(
    "worker_configuration_missing",
    "A Supabase server secret or legacy service-role key is required.",
  );
}

function constantTimeEquals(left: string, right: string): boolean {
  const maxLength = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < maxLength; index += 1) {
    difference |= (left.charCodeAt(index) || 0) ^
      (right.charCodeAt(index) || 0);
  }
  return difference === 0;
}

function authenticate(request: Request): boolean {
  const configuredToken = requiredEnv("OUTBOX_WORKER_TOKEN");
  const tokenValidation = validateWorkerToken(configuredToken);
  if (!tokenValidation.valid) {
    throw new WorkerError(
      "worker_configuration_invalid",
      `OUTBOX_WORKER_TOKEN ${tokenValidation.reason}.`,
    );
  }
  const authorization = request.headers.get("authorization") ?? "";
  const match = authorization.match(/^Bearer ([^\s]+)$/);
  return match !== null && constantTimeEquals(match[1], configuredToken);
}

function workerConfig(): WorkerConfig {
  return {
    leaseSeconds: boundedIntegerEnv("OUTBOX_LEASE_SECONDS", 300, 10, 900),
    staleUploadHours: boundedIntegerEnv(
      "OUTBOX_STALE_UPLOAD_HOURS",
      24,
      1,
      720,
    ),
    sweepBatchSize: boundedIntegerEnv("OUTBOX_SWEEP_BATCH_SIZE", 50, 1, 100),
    deliveryUrl: Deno.env.get("OUTBOX_DELIVERY_URL")?.trim() || undefined,
    deliveryToken: Deno.env.get("OUTBOX_DELIVERY_TOKEN")?.trim() || undefined,
    deliveryTimeoutMs: boundedIntegerEnv(
      "OUTBOX_DELIVERY_TIMEOUT_MS",
      10_000,
      1_000,
      60_000,
    ),
  };
}

function serviceClient(): SupabaseClient {
  return createClient(requiredEnv("SUPABASE_URL"), serviceCredential(), {
    auth: {
      autoRefreshToken: false,
      detectSessionInUrl: false,
      persistSession: false,
    },
    global: { headers: { "X-Client-Info": "gallr-outbox-worker" } },
  });
}

async function rpc<T>(
  client: SupabaseClient,
  functionName: string,
  parameters: JsonObject,
): Promise<T> {
  const { data, error } = await client.rpc(functionName, parameters);
  if (error) throw new RpcError(error.code, error.message, error.details);
  return data as T;
}

function jsonObject(value: unknown, label: string): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new WorkerError(
      "outbox_payload_invalid",
      `${label} must be a JSON object.`,
    );
  }
  return value as JsonObject;
}

function stringField(value: JsonObject, field: string): string {
  const item = value[field];
  if (typeof item !== "string" || !item.trim()) {
    throw new WorkerError(
      "outbox_payload_invalid",
      `${field} must be a non-empty string.`,
    );
  }
  return item;
}

function optionalStringField(value: JsonObject, field: string): string | null {
  const item = value[field];
  if (item === undefined || item === null) return null;
  if (typeof item !== "string" || !item.trim()) {
    throw new WorkerError(
      "outbox_payload_invalid",
      `${field} must be null or a non-empty string.`,
    );
  }
  return item;
}

function validateMediaIdentity(event: OutboxEvent, assetId: string): void {
  if (
    event.aggregate_type !== "media_asset" || event.aggregate_id !== assetId
  ) {
    throw new WorkerError(
      "outbox_payload_invalid",
      "Media aggregate identity does not match the event payload.",
    );
  }
}

function validateSourcePath(assetId: string, objectPath: string): string {
  try {
    return validateCanonicalSourcePath(assetId, objectPath);
  } catch (error) {
    if (!(error instanceof MediaSourcePathError)) throw error;
    throw new WorkerError(
      "media_source_path_invalid",
      error.message,
    );
  }
}

function sameImage(left: ImageInspection, right: ImageInspection): boolean {
  return left.byteSize === right.byteSize &&
    left.checksumSha256 === right.checksumSha256 &&
    left.mimeType === right.mimeType &&
    left.width === right.width &&
    left.height === right.height;
}

async function downloadImage(
  client: SupabaseClient,
  bucketId: string,
  objectPath: string,
): Promise<Uint8Array> {
  const { data, error } = await client.storage.from(bucketId).download(
    objectPath,
  );
  if (error || !data) {
    throw new WorkerError(
      "storage_download_failed",
      `Could not download ${bucketId}/${objectPath}: ${
        error?.message ?? "empty response"
      }`,
    );
  }
  if (data.size > MAX_IMAGE_BYTES) {
    throw new ImageValidationError(
      "image_too_large",
      "Image exceeds the 10 MiB limit.",
    );
  }
  return new Uint8Array(await data.arrayBuffer());
}

async function removeObject(
  client: SupabaseClient,
  bucketId: string,
  objectPath: string,
): Promise<void> {
  const { error } = await client.storage.from(bucketId).remove([objectPath]);
  if (error) {
    throw new WorkerError(
      "storage_delete_failed",
      `Could not delete ${bucketId}/${objectPath}: ${error.message}`,
    );
  }
}

async function rejectMedia(
  client: SupabaseClient,
  assetId: string,
  sourceBucketId: string,
  sourceObjectPath: string,
  reasonCode: string,
  diagnostic: string,
): Promise<void> {
  await rpc(client, "outbox_reject_media", {
    p_asset_id: assetId,
    p_source_bucket_id: sourceBucketId,
    p_source_object_path: sourceObjectPath,
    p_reason_code: reasonCode,
    p_diagnostic: diagnostic.slice(0, 1_000),
  });
}

async function publishMedia(
  client: SupabaseClient,
  event: OutboxEvent,
): Promise<void> {
  const payload = jsonObject(event.payload, "payload");
  const assetId = stringField(payload, "asset_id");
  const sourceBucketId = stringField(payload, "source_bucket_id");
  const sourceObjectPath = stringField(payload, "source_object_path");
  const deliveryBucketId = stringField(payload, "delivery_bucket_id");
  const deliveryObjectPath = stringField(payload, "delivery_object_path");

  validateMediaIdentity(event, assetId);
  if (
    sourceBucketId !== "exhibition-media" ||
    deliveryBucketId !== "exhibition-images"
  ) {
    throw new WorkerError(
      "media_bucket_invalid",
      "Media event names a non-canonical bucket.",
    );
  }
  const sourceExtension = validateSourcePath(assetId, sourceObjectPath);

  let sourceBytes: Uint8Array;
  let sourceInspection: ImageInspection;
  try {
    sourceBytes = await downloadImage(client, sourceBucketId, sourceObjectPath);
    sourceInspection = await inspectImageBytes(sourceBytes);
  } catch (error) {
    if (error instanceof ImageValidationError) {
      await rejectMedia(
        client,
        assetId,
        sourceBucketId,
        sourceObjectPath,
        error.code,
        error.message,
      );
      return;
    }
    throw error;
  }

  const detectedExtension = extensionForMime(sourceInspection.mimeType);
  const expectedDeliveryPath = `cms/${assetId}/original.${detectedExtension}`;
  if (
    sourceExtension !== detectedExtension ||
    deliveryObjectPath !== expectedDeliveryPath
  ) {
    await rejectMedia(
      client,
      assetId,
      sourceBucketId,
      sourceObjectPath,
      "media_detected_type_mismatch",
      "Detected bytes do not match the reserved source and delivery extensions.",
    );
    return;
  }

  const { error: uploadError } = await client.storage
    .from(deliveryBucketId)
    .upload(deliveryObjectPath, sourceBytes, {
      cacheControl: "31536000, immutable",
      contentType: sourceInspection.mimeType,
      upsert: false,
    });

  let deliveryInspection: ImageInspection;
  try {
    const deliveryBytes = await downloadImage(
      client,
      deliveryBucketId,
      deliveryObjectPath,
    );
    deliveryInspection = await inspectImageBytes(deliveryBytes);
  } catch (error) {
    if (uploadError) {
      throw new WorkerError(
        "storage_upload_failed",
        `Upload failed (${uploadError.message}) and the destination could not be verified (${
          formatError(error)
        }).`,
      );
    }
    throw error;
  }

  if (!sameImage(sourceInspection, deliveryInspection)) {
    throw new WorkerError(
      "media_destination_conflict",
      "The immutable delivery path already contains different bytes.",
    );
  }

  const { data: publicUrlResult } = client.storage
    .from(deliveryBucketId)
    .getPublicUrl(deliveryObjectPath);

  try {
    await rpc(client, "outbox_mark_media_published", {
      p_asset_id: assetId,
      p_source_bucket_id: sourceBucketId,
      p_source_object_path: sourceObjectPath,
      p_delivery_bucket_id: deliveryBucketId,
      p_delivery_object_path: deliveryObjectPath,
      p_public_url: publicUrlResult.publicUrl,
      p_detected_mime_type: sourceInspection.mimeType,
      p_detected_byte_size: sourceInspection.byteSize,
      p_detected_width: sourceInspection.width,
      p_detected_height: sourceInspection.height,
      p_detected_checksum_sha256: sourceInspection.checksumSha256,
      p_delivery_checksum_sha256: deliveryInspection.checksumSha256,
    });
  } catch (error) {
    const deterministicReservationFailure = error instanceof RpcError &&
      error.message === "media_reservation_does_not_match_detected_bytes";
    const supersededPublication = error instanceof RpcError &&
      [
        "media_asset_is_not_ready_for_publication",
        "media_asset_is_unreferenced",
      ].includes(
        error.message,
      );

    if (deterministicReservationFailure || supersededPublication) {
      await removeObject(client, deliveryBucketId, deliveryObjectPath);
    }
    if (deterministicReservationFailure) {
      await rejectMedia(
        client,
        assetId,
        sourceBucketId,
        sourceObjectPath,
        "media_reservation_mismatch",
        error.details || error.message,
      );
      return;
    }
    if (supersededPublication) return;
    throw error;
  }
}

async function cleanupMedia(
  client: SupabaseClient,
  event: OutboxEvent,
): Promise<void> {
  const payload = jsonObject(event.payload, "payload");
  const assetId = stringField(payload, "asset_id");
  const sourceBucketId = stringField(payload, "source_bucket_id");
  const sourceObjectPath = stringField(payload, "source_object_path");
  const deliveryBucketId = optionalStringField(payload, "delivery_bucket_id");
  const deliveryObjectPath = optionalStringField(
    payload,
    "delivery_object_path",
  );

  validateMediaIdentity(event, assetId);
  if (sourceBucketId !== "exhibition-media") {
    throw new WorkerError(
      "media_bucket_invalid",
      "Cleanup names a non-canonical source bucket.",
    );
  }
  validateSourcePath(assetId, sourceObjectPath);
  if ((deliveryBucketId === null) !== (deliveryObjectPath === null)) {
    throw new WorkerError(
      "outbox_payload_invalid",
      "Delivery bucket and path must be paired.",
    );
  }
  if (
    deliveryBucketId !== null &&
    (deliveryBucketId !== "exhibition-images" ||
      !new RegExp(`^cms/${assetId}/original\\.(jpg|png|webp)$`).test(
        deliveryObjectPath!,
      ))
  ) {
    throw new WorkerError(
      "media_delivery_path_invalid",
      "Cleanup names a non-canonical delivery path.",
    );
  }

  const preparation = await rpc<CleanupPreparation>(
    client,
    "outbox_prepare_media_cleanup",
    {
      p_asset_id: assetId,
      p_source_bucket_id: sourceBucketId,
      p_source_object_path: sourceObjectPath,
      p_delivery_bucket_id: deliveryBucketId,
      p_delivery_object_path: deliveryObjectPath,
    },
  );
  if (preparation.already_purged) return;
  if (!preparation.purge_token) {
    throw new WorkerError(
      "cleanup_preparation_invalid",
      "Cleanup RPC omitted its purge token.",
    );
  }

  await removeObject(
    client,
    preparation.source_bucket_id,
    preparation.source_object_path,
  );
  if (preparation.delivery_bucket_id && preparation.delivery_object_path) {
    await removeObject(
      client,
      preparation.delivery_bucket_id,
      preparation.delivery_object_path,
    );
  }

  await rpc(client, "outbox_finalize_media_cleanup", {
    p_asset_id: preparation.asset_id,
    p_purge_token: preparation.purge_token,
    p_source_bucket_id: preparation.source_bucket_id,
    p_source_object_path: preparation.source_object_path,
    p_delivery_bucket_id: preparation.delivery_bucket_id ?? null,
    p_delivery_object_path: preparation.delivery_object_path ?? null,
  });
}

function deliveryEndpoint(rawUrl: string): URL {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new WorkerError(
      "delivery_url_invalid",
      "OUTBOX_DELIVERY_URL is not a valid URL.",
    );
  }
  const local = url.hostname === "localhost" || url.hostname === "127.0.0.1" ||
    url.hostname === "::1";
  if (url.protocol !== "https:" && !(local && url.protocol === "http:")) {
    throw new WorkerError(
      "delivery_url_invalid",
      "OUTBOX_DELIVERY_URL must use HTTPS (HTTP is allowed only for loopback development).",
    );
  }
  return url;
}

async function deliverExternalEvent(
  event: OutboxEvent,
  config: WorkerConfig,
): Promise<void> {
  if (!config.deliveryUrl) {
    throw new WorkerError(
      "delivery_url_missing",
      `OUTBOX_DELIVERY_URL is required for event type ${event.event_type}.`,
    );
  }
  const headers = new Headers({
    "Content-Type": "application/json",
    "Idempotency-Key": event.deduplication_key || event.id,
    "X-Outbox-Event-Id": event.id,
    "X-Outbox-Event-Type": event.event_type,
  });
  if (config.deliveryToken) {
    headers.set("Authorization", `Bearer ${config.deliveryToken}`);
  }

  const controller = new AbortController();
  const timeout = setTimeout(
    () => controller.abort(),
    config.deliveryTimeoutMs,
  );
  try {
    const response = await fetch(deliveryEndpoint(config.deliveryUrl), {
      method: "POST",
      headers,
      body: JSON.stringify(event),
      signal: controller.signal,
    });
    if (!response.ok) {
      const responseText = (await response.text()).slice(0, 500);
      throw new WorkerError(
        "delivery_rejected",
        `Delivery returned HTTP ${response.status}: ${responseText}`,
      );
    }
  } finally {
    clearTimeout(timeout);
  }
}

async function processEvent(
  client: SupabaseClient,
  event: OutboxEvent,
  config: WorkerConfig,
): Promise<void> {
  if (event.event_type === "media.publish_requested") {
    await publishMedia(client, event);
    return;
  }
  if (event.event_type === "media.cleanup_requested") {
    await cleanupMedia(client, event);
    return;
  }
  await deliverExternalEvent(event, config);
}

function formatError(error: unknown): string {
  if (error instanceof WorkerError) return `${error.code}: ${error.message}`;
  if (error instanceof Error) return `${error.name}: ${error.message}`;
  return `unknown_error: ${String(error)}`;
}

function response(status: number, body: JsonObject): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
    },
  });
}

Deno.serve(async (request) => {
  const invocationId = crypto.randomUUID();
  try {
    if (request.method !== "POST") {
      return response(405, {
        error: "method_not_allowed",
        invocation_id: invocationId,
      });
    }
    if (!authenticate(request)) {
      return response(401, {
        error: "unauthorized",
        invocation_id: invocationId,
      });
    }

    const config = workerConfig();
    const client = serviceClient();
    const leaseOwner = `outbox-worker:${invocationId}`;
    const cutoff = new Date(
      Date.now() - config.staleUploadHours * 60 * 60 * 1_000,
    ).toISOString();

    const swept = await rpc<unknown[]>(client, "outbox_sweep_stale_media", {
      p_cutoff: cutoff,
      p_batch_size: config.sweepBatchSize,
    });
    const claimFunction = config.deliveryUrl
      ? "outbox_claim_events"
      : "outbox_claim_media_events";
    const claimed = await rpc<unknown[]>(client, claimFunction, {
      p_lease_owner: leaseOwner,
      p_batch_size: 1,
      p_lease_seconds: config.leaseSeconds,
    });

    let completed = 0;
    let failed = 0;
    let leaseLost = 0;
    for (const rawEvent of claimed ?? []) {
      const event = jsonObject(
        rawEvent,
        "claimed event",
      ) as unknown as OutboxEvent;
      try {
        await processEvent(client, event, config);
        await rpc(client, "outbox_complete_event", {
          p_event_id: event.id,
          p_lease_token: event.lease_token,
        });
        completed += 1;
      } catch (processingError) {
        try {
          await rpc(client, "outbox_fail_event", {
            p_event_id: event.id,
            p_lease_token: event.lease_token,
            p_error: formatError(processingError).slice(0, 4_000),
          });
          failed += 1;
        } catch (leaseError) {
          leaseLost += 1;
          console.error(
            JSON.stringify({
              invocation_id: invocationId,
              event_id: event.id,
              error: formatError(processingError),
              lease_error: formatError(leaseError),
            }),
          );
        }
      }
    }

    return response(200, {
      invocation_id: invocationId,
      swept: Array.isArray(swept) ? swept.length : 0,
      claimed: Array.isArray(claimed) ? claimed.length : 0,
      completed,
      failed,
      lease_lost: leaseLost,
    });
  } catch (error) {
    console.error(
      JSON.stringify({
        invocation_id: invocationId,
        error: formatError(error),
      }),
    );
    return response(500, {
      error: "worker_invocation_failed",
      invocation_id: invocationId,
    });
  }
});
