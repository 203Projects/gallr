import { resolveSupabaseSecretKey } from "../_shared/supabase_keys.ts";
import { validateOpaqueToken } from "../_shared/opaque_token.ts";
import type { MirrorSource } from "./handler.ts";

type Fetcher = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

export interface LegacyCatalogMirrorBackend {
  mirror(source: MirrorSource): Promise<void>;
}

const SOURCE_REF = "oqrvbstopuppznxqoonp";
const TARGET_REF = "yhuhjxswjbrtmbpbrciq";
const SOURCE_URL = `https://${SOURCE_REF}.supabase.co`;
const TARGET_URL = `https://${TARGET_REF}.supabase.co`;
const RECEIVER_URL =
  `${TARGET_URL}/functions/v1/legacy-catalog-mirror-receiver`;
const EVENT_IMAGE_PATH_PREFIX = "/storage/v1/object/public/event-images/";

const RESOURCE_COLUMNS = Object.freeze({
  events: [
    "id",
    "name_ko",
    "name_en",
    "description_ko",
    "description_en",
    "location_label_ko",
    "location_label_en",
    "start_date",
    "end_date",
    "brand_color",
    "accent_color",
    "ticket_url",
    "is_active",
    "updated_at",
    "cover_image_url",
    "short_label",
  ],
  editors: [
    "id",
    "name_ko",
    "name_en",
    "title_ko",
    "title_en",
    "bio_ko",
    "bio_en",
    "is_active",
    "active_from",
    "active_to",
    "created_at",
    "updated_at",
  ],
  exhibitions: [
    "id",
    "name_ko",
    "venue_name_ko",
    "country_code",
    "city_ko",
    "region_ko",
    "opening_date",
    "closing_date",
    "is_featured",
    "latitude",
    "longitude",
    "description_ko",
    "cover_image_url",
    "updated_at",
    "name_en",
    "venue_name_en",
    "city_en",
    "region_en",
    "description_en",
    "address_ko",
    "address_en",
    "hours",
    "contact",
    "reception_date",
    "opening_time",
    "ticket_url",
    "is_homepage_featured",
    "event_id",
    "editor_id",
    "credits_ko",
    "credits_en",
  ],
  exhibition_catalog_v2: [
    "id",
    "name_ko",
    "name_en",
    "venue_name_ko",
    "venue_name_en",
    "country_code",
    "city_ko",
    "city_en",
    "region_ko",
    "region_en",
    "opening_date",
    "closing_date",
    "is_featured",
    "latitude",
    "longitude",
    "description_ko",
    "description_en",
    "address_ko",
    "address_en",
    "cover_image_url",
    "hours",
    "contact",
    "reception_date",
    "opening_time",
    "event_id",
    "editor_id",
    "is_homepage_featured",
    "ticket_url",
    "updated_at",
    "is_editors_pick",
    "guest_editor_id",
    // The canonical checksum hashes the whole row, so the carried gallery
    // identity must travel with the snapshot or every row fails comparison.
    "gallery_id",
    "content_checksum_sha256",
    "credits_ko",
    "credits_en",
    "artists",
    "art_terms",
  ],
});

type Resource = keyof typeof RESOURCE_COLUMNS;
type CatalogRow = Record<string, unknown> & { id: string };
type Snapshot = Record<Resource, CatalogRow[]>;

function required(
  environment: Record<string, string | undefined>,
  name: string,
): string {
  const value = environment[name]?.trim();
  if (value) return value;
  throw new Error("Mirror configuration is incomplete.");
}

function exactProjectUrl(value: string, expected: string): boolean {
  try {
    const url = new URL(value);
    return url.origin === expected && url.href === `${expected}/`;
  } catch {
    return false;
  }
}

function headers(key: string): HeadersInit {
  return { apikey: key, authorization: `Bearer ${key}` };
}

async function responseJson(response: Response): Promise<unknown> {
  if (!response.ok) {
    throw new Error(`Mirror request failed with HTTP ${response.status}.`);
  }
  try {
    return await response.json();
  } catch {
    throw new Error("Mirror request returned invalid JSON.");
  }
}

function sortedRows(value: unknown, resource: Resource): CatalogRow[] {
  if (!Array.isArray(value)) {
    throw new Error(`Source ${resource} response is invalid.`);
  }
  const ids = new Set<string>();
  const rows = value.map((candidate) => {
    if (
      !candidate || typeof candidate !== "object" || Array.isArray(candidate)
    ) {
      throw new Error(`Source ${resource} response is invalid.`);
    }
    const row = candidate as Record<string, unknown>;
    if (typeof row.id !== "string" || !row.id || ids.has(row.id)) {
      throw new Error(`Source ${resource} identifiers are invalid.`);
    }
    ids.add(row.id);
    return { ...row, id: row.id };
  });
  return rows.sort((left, right) => left.id.localeCompare(right.id, "en"));
}

function localizeEventMedia(rows: CatalogRow[]): CatalogRow[] {
  return rows.map((event) => {
    if (typeof event.cover_image_url !== "string") return event;
    try {
      const sourceImage = new URL(event.cover_image_url);
      if (
        sourceImage.origin !== SOURCE_URL ||
        !sourceImage.pathname.startsWith(EVENT_IMAGE_PATH_PREFIX)
      ) return event;
      const targetImage = new URL(
        `${sourceImage.pathname}${sourceImage.search}${sourceImage.hash}`,
        `${TARGET_URL}/`,
      );
      return { ...event, cover_image_url: targetImage.href };
    } catch {
      return event;
    }
  });
}

async function fetchResource(
  fetcher: Fetcher,
  key: string,
  resource: Resource,
): Promise<CatalogRow[]> {
  const rows: CatalogRow[] = [];
  const pageSize = 500;
  for (let offset = 0;; offset += pageSize) {
    const url = new URL(`/rest/v1/${resource}`, SOURCE_URL);
    url.searchParams.set("select", RESOURCE_COLUMNS[resource].join(","));
    url.searchParams.set("order", "id.asc");
    url.searchParams.set("limit", String(pageSize));
    url.searchParams.set("offset", String(offset));
    const page = sortedRows(
      await responseJson(await fetcher(url, { headers: headers(key) })),
      resource,
    );
    rows.push(...page);
    if (page.length < pageSize) return rows;
  }
}

class SupabaseLegacyCatalogMirrorBackend implements LegacyCatalogMirrorBackend {
  constructor(
    private readonly fetcher: Fetcher,
    private readonly sourceKey: string,
    private readonly receiverToken: string,
    private readonly reason: string,
  ) {}

  async mirror(source: MirrorSource): Promise<void> {
    const [events, editors, exhibitions, canonicalExhibitions] = await Promise
      .all([
        fetchResource(this.fetcher, this.sourceKey, "events"),
        fetchResource(this.fetcher, this.sourceKey, "editors"),
        fetchResource(this.fetcher, this.sourceKey, "exhibitions"),
        fetchResource(
          this.fetcher,
          this.sourceKey,
          "exhibition_catalog_v2",
        ),
      ]);
    if (exhibitions.length === 0) throw new Error("Source catalogue is empty.");
    if (canonicalExhibitions.length === 0) {
      throw new Error("Source canonical-v2 catalogue is empty.");
    }
    const snapshot: Snapshot = {
      events: localizeEventMedia(events),
      editors,
      exhibitions,
      exhibition_catalog_v2: canonicalExhibitions,
    };
    const result = await responseJson(
      await this.fetcher(RECEIVER_URL, {
        method: "POST",
        headers: {
          "authorization": `Bearer ${this.receiverToken}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({
          p_snapshot: snapshot,
          p_source_project_ref: SOURCE_REF,
          p_reason: `${this.reason}; ${source}`,
        }),
      }),
    );
    if (
      !result || typeof result !== "object" || Array.isArray(result) ||
      !["applied", "unchanged"].includes(
        String((result as Record<string, unknown>).status),
      )
    ) throw new Error("Mirror apply receipt is invalid.");
  }
}

export function createLegacyCatalogMirrorBackend(
  environment: Record<string, string | undefined>,
  fetcher: Fetcher = fetch,
): LegacyCatalogMirrorBackend {
  const sourceUrl = required(environment, "SUPABASE_URL");
  const receiverUrl = required(environment, "LEGACY_CATALOG_RECEIVER_URL");
  if (!exactProjectUrl(sourceUrl, SOURCE_URL) || receiverUrl !== RECEIVER_URL) {
    throw new Error("Mirror project configuration is invalid.");
  }
  const reason = required(environment, "LEGACY_CATALOG_MIRROR_REASON");
  if (reason.length > 450) throw new Error("Mirror reason is too long.");
  const receiverToken = required(environment, "LEGACY_CATALOG_RECEIVER_TOKEN");
  if (!validateOpaqueToken(receiverToken).valid) {
    throw new Error("Mirror receiver token is invalid.");
  }
  return new SupabaseLegacyCatalogMirrorBackend(
    fetcher,
    resolveSupabaseSecretKey(environment, "legacy-catalog-mirror"),
    receiverToken,
    reason,
  );
}
