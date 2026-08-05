import type { SupabaseClient } from "@supabase/supabase-js";
import type { AdminGeocodeCandidate } from "../domain";
import type { AdminGeocodingService } from "./AdminGeocodingService";

type JsonRecord = Record<string, unknown>;

const MAX_ADDRESS_LENGTH = 500;
const MAX_LOCATION_LABEL_LENGTH = 100;
const MAX_ERROR_CODE_LENGTH = 100;
const MAX_ERROR_MESSAGE_LENGTH = 500;
const DEFAULT_ERROR_MESSAGE = "The geocoding service did not respond.";
const GEOCODING_REQUEST_TIMEOUT_MS = 20_000;
const GEOCODING_TIMEOUT_MESSAGE =
  "The geocoding request timed out. Check the network and try again.";

class GeocodingRequestTimeoutError extends Error {
  constructor() {
    super(GEOCODING_TIMEOUT_MESSAGE);
    this.name = "GeocodingRequestTimeoutError";
  }
}

export class MalformedGeocodingPayloadError extends Error {
  constructor(readonly path: string, expected: string) {
    super(`geocode-address returned malformed data at ${path}: expected ${expected}.`);
    this.name = "MalformedGeocodingPayloadError";
  }
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function containsAsciiControlCharacters(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const codePoint = value.charCodeAt(index);
    if (codePoint <= 31 || codePoint === 127) return true;
  }
  return false;
}

function readString(record: JsonRecord, key: string, path: string): string {
  const value = record[key];
  if (typeof value !== "string") {
    throw new MalformedGeocodingPayloadError(`${path}.${key}`, "a string");
  }
  return value;
}

function readAddressString(
  record: JsonRecord,
  key: "road_address" | "jibun_address" | "english_address",
  path: string,
): string {
  const value = readString(record, key, path);
  if (
    value.length > MAX_ADDRESS_LENGTH ||
    containsAsciiControlCharacters(value)
  ) {
    throw new MalformedGeocodingPayloadError(
      `${path}.${key}`,
      `a string of at most ${MAX_ADDRESS_LENGTH} characters without ASCII control characters`,
    );
  }
  return value.trim();
}

function readLocationString(
  record: JsonRecord,
  key: "city_ko" | "city_en" | "region_ko" | "region_en",
  path: string,
): string {
  const value = readString(record, key, path).trim();
  if (
    value.length === 0 ||
    value.length > MAX_LOCATION_LABEL_LENGTH ||
    containsAsciiControlCharacters(value)
  ) {
    throw new MalformedGeocodingPayloadError(
      `${path}.${key}`,
      `a non-empty string of at most ${MAX_LOCATION_LABEL_LENGTH} characters without ASCII control characters`,
    );
  }
  return value;
}

function readCoordinate(
  record: JsonRecord,
  key: "latitude" | "longitude",
  path: string,
): string {
  const value = readString(record, key, path).trim();
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(value)) {
    throw new MalformedGeocodingPayloadError(
      `${path}.${key}`,
      "a decimal coordinate string",
    );
  }
  const coordinate = Number(value);
  const inRange =
    Number.isFinite(coordinate) &&
    (key === "latitude"
      ? coordinate >= -90 && coordinate <= 90
      : coordinate >= -180 && coordinate <= 180);
  if (!inRange) {
    throw new MalformedGeocodingPayloadError(
      `${path}.${key}`,
      key === "latitude" ? "-90 through 90" : "-180 through 180",
    );
  }
  return value;
}

function mapCandidate(value: unknown, index: number): AdminGeocodeCandidate {
  const path = `$.candidates[${index}]`;
  if (!isRecord(value)) {
    throw new MalformedGeocodingPayloadError(path, "an object");
  }
  const candidate = {
    roadAddress: readAddressString(value, "road_address", path),
    jibunAddress: readAddressString(value, "jibun_address", path),
    englishAddress: readAddressString(value, "english_address", path),
    cityKo: readLocationString(value, "city_ko", path),
    cityEn: readLocationString(value, "city_en", path),
    regionKo: readLocationString(value, "region_ko", path),
    regionEn: readLocationString(value, "region_en", path),
    latitude: readCoordinate(value, "latitude", path),
    longitude: readCoordinate(value, "longitude", path),
  };
  if (
    candidate.roadAddress.trim().length === 0 &&
    candidate.jibunAddress.trim().length === 0
  ) {
    throw new MalformedGeocodingPayloadError(
      path,
      "a road or lot-number address",
    );
  }
  return candidate;
}

function safeErrorText(value: unknown, maximumLength: number): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim();
  if (
    normalized.length === 0 ||
    normalized.length > maximumLength ||
    containsAsciiControlCharacters(normalized)
  ) {
    return null;
  }
  return normalized;
}

function errorMessageFromPayload(payload: unknown): string | null {
  if (!isRecord(payload)) return null;

  if (isRecord(payload.error)) {
    const code = safeErrorText(payload.error.code, MAX_ERROR_CODE_LENGTH);
    const message = safeErrorText(
      payload.error.message,
      MAX_ERROR_MESSAGE_LENGTH,
    );
    if (code !== null && message !== null) return message;
  }

  // Retain compatibility with earlier relay responses while bounding the
  // untrusted string before it reaches the editor UI.
  return safeErrorText(payload.message, MAX_ERROR_MESSAGE_LENGTH);
}

async function readFunctionErrorContext(context: unknown): Promise<unknown> {
  if (!isRecord(context)) return null;

  let bodySource: JsonRecord = context;
  if (typeof context.clone === "function") {
    try {
      const cloned: unknown = context.clone.call(context);
      if (isRecord(cloned)) bodySource = cloned;
    } catch {
      // Some Response-like test doubles do not implement clone correctly.
      // Their json() method remains a valid fallback below.
    }
  }

  if (typeof bodySource.json !== "function") return null;
  return await bodySource.json.call(bodySource);
}

async function functionErrorMessage(error: unknown): Promise<string> {
  if (isRecord(error)) {
    try {
      const body = await readFunctionErrorContext(error.context);
      const message = errorMessageFromPayload(body);
      if (message !== null) return message;
    } catch {
      // Fall back to the SDK message when a relay response is not JSON.
    }
  }
  return error instanceof Error
    ? safeErrorText(error.message, MAX_ERROR_MESSAGE_LENGTH) ??
        DEFAULT_ERROR_MESSAGE
    : DEFAULT_ERROR_MESSAGE;
}

export class SupabaseAdminGeocodingService implements AdminGeocodingService {
  readonly mode = "naver-server" as const;

  constructor(private readonly client: SupabaseClient) {}

  async searchAddress(address: string): Promise<AdminGeocodeCandidate[]> {
    const query = address.trim();
    const controller = new AbortController();
    let timedOut = false;
    let timeoutId: ReturnType<typeof globalThis.setTimeout> | null = null;
    const timeout = new Promise<never>((_resolve, reject) => {
      timeoutId = globalThis.setTimeout(() => {
        timedOut = true;
        controller.abort();
        reject(new GeocodingRequestTimeoutError());
      }, GEOCODING_REQUEST_TIMEOUT_MS);
    });

    const operation = async (): Promise<unknown> => {
      const result = await this.client.functions.invoke("geocode-address", {
        body: { address: query },
        signal: controller.signal,
      });
      if (result.error !== null) {
        throw new Error(await functionErrorMessage(result.error));
      }
      return result.data;
    };

    let data: unknown;
    try {
      data = await Promise.race([operation(), timeout]);
    } catch (error) {
      // An abort-aware fetch may reject before the timeout promise wins its
      // race. Preserve one actionable editor-facing result in either order.
      if (timedOut) throw new GeocodingRequestTimeoutError();
      throw error;
    } finally {
      if (timeoutId !== null) globalThis.clearTimeout(timeoutId);
    }
    if (!isRecord(data) || !Array.isArray(data.candidates)) {
      throw new MalformedGeocodingPayloadError("$.candidates", "an array");
    }
    if (data.candidates.length > 3) {
      throw new MalformedGeocodingPayloadError(
        "$.candidates",
        "at most three candidates",
      );
    }
    return data.candidates.map(mapCandidate);
  }
}
