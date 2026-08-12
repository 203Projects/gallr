import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";

export const SEOUL_PROJECT_REF = "oqrvbstopuppznxqoonp";
export const LEGACY_PROJECT_REF = "yhuhjxswjbrtmbpbrciq";
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
    "content_checksum_sha256",
    "credits_ko",
    "credits_en",
  ],
});

export const MOBILE_READER_SHARED_COLUMNS = Object.freeze(
  RESOURCE_COLUMNS.exhibitions.filter((column) =>
    RESOURCE_COLUMNS.exhibition_catalog_v2.includes(column)
  ),
);

function required(env, name, description) {
  const value = env[name]?.trim();
  if (!value) throw new Error(`${description} is required`);
  return value;
}

function reviewedProjectUrl(value, expectedRef, description) {
  const expectedHostname = `${expectedRef}.supabase.co`;
  const normalized = value === expectedHostname ? `https://${value}` : value;
  let parsed;
  try {
    parsed = new URL(normalized);
  } catch {
    throw new Error(`${description} is not a valid URL`);
  }
  if (
    parsed.protocol !== "https:" ||
    parsed.username ||
    parsed.password ||
    parsed.port ||
    parsed.pathname !== "/" ||
    parsed.search ||
    parsed.hash ||
    parsed.hostname !== expectedHostname
  ) {
    throw new Error(
      `${description} does not match the reviewed ${
        expectedRef === SEOUL_PROJECT_REF ? "Seoul source" : "Singapore project"
      }`,
    );
  }
  return parsed.origin;
}

export function readConfig(env = process.env) {
  const sourceUrl = reviewedProjectUrl(
    required(env, "GALLR_SEOUL_SUPABASE_URL", "Seoul source URL"),
    SEOUL_PROJECT_REF,
    "Seoul source",
  );
  const targetUrl = reviewedProjectUrl(
    required(env, "GALLR_LEGACY_SUPABASE_URL", "legacy target URL"),
    LEGACY_PROJECT_REF,
    "legacy target",
  );
  const reason = required(env, "GALLR_LEGACY_MIRROR_REASON", "change reason");
  if (reason.length > 500) {
    throw new Error("change reason must be 500 characters or fewer");
  }

  return {
    sourceRef: SEOUL_PROJECT_REF,
    sourceUrl,
    sourceSecretKey: required(
      env,
      "GALLR_SEOUL_SECRET_KEY",
      "Seoul server credential",
    ),
    targetRef: LEGACY_PROJECT_REF,
    targetUrl,
    targetSecretKey: required(
      env,
      "GALLR_LEGACY_SECRET_KEY",
      "legacy server credential",
    ),
    reason,
  };
}

function sortedRows(rows, resource) {
  if (!Array.isArray(rows)) {
    throw new Error(`${resource} response must be an array`);
  }
  const ids = new Set();
  return rows.map((row) => {
    if (!row || typeof row !== "object" || Array.isArray(row)) {
      throw new Error(`${resource} contains a non-object row`);
    }
    if (typeof row.id !== "string" || !row.id || ids.has(row.id)) {
      throw new Error(`${resource} contains a missing or duplicate id`);
    }
    ids.add(row.id);
    return { ...row };
  }).sort((left, right) => left.id.localeCompare(right.id, "en"));
}

export function buildSnapshot(resources) {
  const snapshot = {
    events: sortedRows(resources.events, "events"),
    editors: sortedRows(resources.editors, "editors"),
    exhibitions: sortedRows(resources.exhibitions, "exhibitions"),
    exhibition_catalog_v2: sortedRows(
      resources.exhibition_catalog_v2,
      "exhibition_catalog_v2",
    ),
  };
  if (snapshot.exhibitions.length === 0) {
    throw new Error("Seoul exhibition snapshot is empty; refusing to mirror");
  }
  if (snapshot.exhibition_catalog_v2.length === 0) {
    throw new Error("Seoul canonical-v2 snapshot is empty; refusing to mirror");
  }
  return snapshot;
}

function localizeEventMedia(snapshot, sourceUrl, targetUrl) {
  return {
    ...snapshot,
    events: snapshot.events.map((event) => {
      if (typeof event.cover_image_url !== "string") return event;
      try {
        const sourceImage = new URL(event.cover_image_url);
        if (
          sourceImage.origin !== sourceUrl ||
          !sourceImage.pathname.startsWith(EVENT_IMAGE_PATH_PREFIX)
        ) return event;
        const targetImage = new URL(
          `${sourceImage.pathname}${sourceImage.search}${sourceImage.hash}`,
          `${targetUrl}/`,
        );
        return { ...event, cover_image_url: targetImage.href };
      } catch {
        return event;
      }
    }),
  };
}

export function diffResource(sourceRows, targetRows) {
  const source = new Map(sourceRows.map((row) => [row.id, row]));
  const target = new Map(targetRows.map((row) => [row.id, row]));
  let insert = 0;
  let update = 0;
  let deleted = 0;
  const changedFields = new Map();
  for (const [id, value] of source) {
    if (!target.has(id)) insert += 1;
    else {
      const targetValue = target.get(id);
      if (JSON.stringify(targetValue) === JSON.stringify(value)) continue;
      update += 1;
      const fields = new Set([
        ...Object.keys(value),
        ...Object.keys(targetValue),
      ]);
      for (const field of fields) {
        if (
          JSON.stringify(value[field]) !== JSON.stringify(targetValue[field])
        ) {
          changedFields.set(field, (changedFields.get(field) ?? 0) + 1);
        }
      }
    }
  }
  for (const id of target.keys()) if (!source.has(id)) deleted += 1;
  return {
    source: source.size,
    target: target.size,
    insert,
    update,
    delete: deleted,
    changed_fields: Object.fromEntries(
      [...changedFields.entries()].sort(([left], [right]) =>
        left.localeCompare(right, "en")
      ),
    ),
  };
}

export function mobileReaderContractParity(snapshot) {
  const project = (rows) =>
    rows.map((row) =>
      Object.fromEntries(
        MOBILE_READER_SHARED_COLUMNS.map((column) => [column, row[column]]),
      )
    );
  const diff = diffResource(
    project(snapshot.exhibitions),
    project(snapshot.exhibition_catalog_v2),
  );
  return {
    matches: diff.insert === 0 && diff.delete === 0 && diff.update === 0,
    legacy: diff.source,
    canonical_v2: diff.target,
    legacy_only: diff.insert,
    canonical_v2_only: diff.delete,
    mismatched: diff.update,
    changed_fields: diff.changed_fields,
  };
}

export function assertMobileReaderContractParity(snapshot, location) {
  const parity = mobileReaderContractParity(snapshot);
  if (parity.matches) return parity;
  const changedFields = Object.keys(parity.changed_fields).join(",") || "none";
  throw new Error(
    `${location} mobile reader contracts diverge: ` +
      `legacy=${parity.legacy}, canonical_v2=${parity.canonical_v2}, ` +
      `legacy_only=${parity.legacy_only}, ` +
      `canonical_v2_only=${parity.canonical_v2_only}, ` +
      `mismatched=${parity.mismatched}, changed_fields=${changedFields}`,
  );
}

function authHeaders(key) {
  return {
    apikey: key,
    authorization: `Bearer ${key}`,
  };
}

async function responseJson(response, operation) {
  if (response.ok) return response.json();
  let error = {};
  try {
    error = await response.json();
  } catch {
    // Do not include raw response bodies in operational logs.
  }
  const code = typeof error.code === "string" ? ` (${error.code})` : "";
  const message = typeof error.message === "string" ? `: ${error.message}` : "";
  throw new Error(
    `${operation} failed with HTTP ${response.status}${code}${message}`,
  );
}

async function fetchResource({ baseUrl, key, resource, fetchImpl }) {
  const columns = RESOURCE_COLUMNS[resource];
  const rows = [];
  const pageSize = 500;
  for (let offset = 0;; offset += pageSize) {
    const url = new URL(`/rest/v1/${resource}`, baseUrl);
    url.searchParams.set("select", columns.join(","));
    url.searchParams.set("order", "id.asc");
    url.searchParams.set("limit", String(pageSize));
    url.searchParams.set("offset", String(offset));
    const response = await fetchImpl(url, { headers: authHeaders(key) });
    const page = await responseJson(response, `read ${resource}`);
    if (!Array.isArray(page)) {
      throw new Error(`read ${resource} returned a non-array payload`);
    }
    rows.push(...page);
    if (page.length < pageSize) return rows;
  }
}

async function fetchSnapshot(baseUrl, key, fetchImpl) {
  const [events, editors, exhibitions, exhibitionCatalogV2] = await Promise.all(
    ["events", "editors", "exhibitions", "exhibition_catalog_v2"].map((
      resource,
    ) => fetchResource({ baseUrl, key, resource, fetchImpl })),
  );
  return buildSnapshot({
    events,
    editors,
    exhibitions,
    exhibition_catalog_v2: exhibitionCatalogV2,
  });
}

function snapshotSha256(snapshot) {
  return createHash("sha256").update(JSON.stringify(snapshot)).digest("hex");
}

export async function runMirror({
  env = process.env,
  apply = false,
  fetchImpl = globalThis.fetch,
} = {}) {
  if (typeof fetchImpl !== "function") {
    throw new Error("fetch implementation is required");
  }
  const config = readConfig(env);
  const source = localizeEventMedia(
    await fetchSnapshot(config.sourceUrl, config.sourceSecretKey, fetchImpl),
    config.sourceUrl,
    config.targetUrl,
  );
  const sourceReaderContractParity = assertMobileReaderContractParity(
    source,
    "Seoul",
  );
  const sourceSummary = {
    exhibitions: source.exhibitions.length,
    exhibition_catalog_v2: source.exhibition_catalog_v2.length,
    events: source.events.length,
    editors: source.editors.length,
    sha256: snapshotSha256(source),
    reader_contract_parity: sourceReaderContractParity,
  };

  if (!apply) {
    const target = await fetchSnapshot(
      config.targetUrl,
      config.targetSecretKey,
      fetchImpl,
    );
    const targetReaderContractParity = mobileReaderContractParity(target);
    return {
      mode: "dry-run",
      sourceRef: config.sourceRef,
      targetRef: config.targetRef,
      source: sourceSummary,
      target_reader_contract_parity: targetReaderContractParity,
      diff: {
        exhibitions: diffResource(source.exhibitions, target.exhibitions),
        exhibition_catalog_v2: diffResource(
          source.exhibition_catalog_v2,
          target.exhibition_catalog_v2,
        ),
        events: diffResource(source.events, target.events),
        editors: diffResource(source.editors, target.editors),
      },
    };
  }

  const endpoint = new URL(
    "/rest/v1/rpc/service_replace_legacy_mobile_catalog",
    config.targetUrl,
  );
  const response = await fetchImpl(endpoint, {
    method: "POST",
    headers: {
      ...authHeaders(config.targetSecretKey),
      "content-type": "application/json",
    },
    body: JSON.stringify({
      p_snapshot: source,
      p_source_project_ref: config.sourceRef,
      p_reason: config.reason,
    }),
  });
  const receipt = await responseJson(response, "apply legacy mobile mirror");
  return {
    mode: "apply",
    sourceRef: config.sourceRef,
    targetRef: config.targetRef,
    source: sourceSummary,
    receipt,
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const unknown = process.argv.slice(2).filter((argument) =>
    argument !== "--apply"
  );
  if (unknown.length > 0) {
    throw new Error(`unknown argument: ${unknown[0]}`);
  }
  const result = await runMirror({ apply: process.argv.includes("--apply") });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (
    result.mode === "dry-run" &&
    !result.target_reader_contract_parity.matches
  ) {
    process.exitCode = 1;
  }
}
