export type GalleryAlertProviderName = "apns" | "fcm";
export type GalleryAlertProviderEnvironment = "sandbox" | "production";

export interface GalleryPublicationEvent {
  id: string;
  event_type: string;
  aggregate_type: string;
  aggregate_id: string;
  deduplication_key: string | null;
  payload: Record<string, unknown>;
}

export interface GalleryAlertJob {
  job_id: number;
  lease_token: string;
  provider: GalleryAlertProviderName;
  provider_token: string;
  provider_environment: GalleryAlertProviderEnvironment;
  locale: string;
  gallery_name_ko: string;
  gallery_name_en: string;
  exhibition_name_ko: string;
  exhibition_name_en: string;
  exhibition_id: string;
  deduplication_key: string;
}

export interface GalleryAlertClaim {
  jobs: GalleryAlertJob[];
  hasMore: boolean;
}

export interface GalleryAlertFailure {
  code: string;
  retryable: boolean;
  invalidToken: boolean;
}

export interface GalleryAlertBackend {
  claim(eventId: string): Promise<GalleryAlertClaim>;
  markDelivered(jobId: number, leaseToken: string): Promise<void>;
  markFailed(
    jobId: number,
    leaseToken: string,
    failure: GalleryAlertFailure,
  ): Promise<void>;
}

export type GalleryAlertProviderResult =
  | { outcome: "delivered" }
  | {
    outcome: "retryable" | "invalid_token" | "permanent";
    code: string;
  };

export interface GalleryAlertProvider {
  send(job: GalleryAlertJob): Promise<GalleryAlertProviderResult>;
}

export type GalleryAlertDeliveryResult =
  | { ok: true }
  | {
    ok: false;
    code:
      | "gallery_alert_event_invalid"
      | "gallery_alert_provider_retryable"
      | "gallery_alert_batch_incomplete"
      | "gallery_alert_database_unavailable";
  };

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function isPublicationEvent(event: GalleryPublicationEvent): boolean {
  const exhibitionId = event.payload.exhibition_id;
  const versionId = event.payload.version_id;
  const galleryId = event.payload.gallery_id;
  return event.event_type === "exhibition.published" &&
    event.aggregate_type === "exhibition" &&
    typeof exhibitionId === "string" &&
    exhibitionId === event.aggregate_id &&
    typeof versionId === "string" &&
    UUID_PATTERN.test(versionId) &&
    typeof galleryId === "string" &&
    UUID_PATTERN.test(galleryId) &&
    UUID_PATTERN.test(event.id);
}

function failureFor(
  result: Exclude<GalleryAlertProviderResult, { outcome: "delivered" }>,
): GalleryAlertFailure {
  return {
    code: result.code,
    retryable: result.outcome === "retryable",
    invalidToken: result.outcome === "invalid_token",
  };
}

export async function deliverGalleryPublicationAlerts(
  backend: GalleryAlertBackend,
  provider: GalleryAlertProvider,
  event: GalleryPublicationEvent,
): Promise<GalleryAlertDeliveryResult> {
  if (!isPublicationEvent(event)) {
    return { ok: false, code: "gallery_alert_event_invalid" };
  }

  let claim: GalleryAlertClaim;
  try {
    claim = await backend.claim(event.id);
  } catch {
    return { ok: false, code: "gallery_alert_database_unavailable" };
  }

  let retryableFailure = false;
  for (const job of claim.jobs) {
    let result: GalleryAlertProviderResult;
    try {
      result = await provider.send(job);
    } catch {
      result = { outcome: "retryable", code: "provider_network_error" };
    }

    try {
      if (result.outcome === "delivered") {
        await backend.markDelivered(job.job_id, job.lease_token);
      } else {
        await backend.markFailed(
          job.job_id,
          job.lease_token,
          failureFor(result),
        );
        retryableFailure ||= result.outcome === "retryable";
      }
    } catch {
      return { ok: false, code: "gallery_alert_database_unavailable" };
    }
  }

  if (retryableFailure) {
    return { ok: false, code: "gallery_alert_provider_retryable" };
  }
  if (claim.hasMore) {
    return { ok: false, code: "gallery_alert_batch_incomplete" };
  }
  return { ok: true };
}
