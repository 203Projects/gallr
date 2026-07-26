import { containsAsciiControlCharacters } from "./http.ts";

export const NAVER_GEOCODE_ENDPOINT =
  "https://maps.apigw.ntruss.com/map-geocode/v2/geocode";

export const MAX_GEOCODE_CANDIDATES = 3;

export interface GeocodeCandidate {
  road_address: string;
  jibun_address: string;
  english_address: string;
  latitude: string;
  longitude: string;
}

type ProviderErrorCode =
  | "provider_configuration_invalid"
  | "provider_response_invalid";

export class GeocodingProviderError extends Error {
  constructor(
    readonly code: ProviderErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "GeocodingProviderError";
  }
}

function providerResponseInvalid(message: string): never {
  throw new GeocodingProviderError("provider_response_invalid", message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function boundedString(
  record: Record<string, unknown>,
  field: string,
  maximumLength: number,
): string {
  const value = record[field];
  if (
    typeof value !== "string" || value.length > maximumLength ||
    containsAsciiControlCharacters(value)
  ) {
    providerResponseInvalid(`Provider field ${field} is invalid.`);
  }
  return value.trim();
}

function nonNegativeInteger(
  record: Record<string, unknown>,
  field: string,
): number {
  const value = record[field];
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    providerResponseInvalid(`Provider field ${field} is invalid.`);
  }
  return value as number;
}

function coordinate(
  record: Record<string, unknown>,
  field: "x" | "y",
  minimum: number,
  maximum: number,
): string {
  const value = record[field];
  if (
    typeof value !== "string" || value.length > 32 ||
    !/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/u.test(value.trim())
  ) {
    providerResponseInvalid(`Provider coordinate ${field} is invalid.`);
  }

  const normalized = value.trim();
  const numeric = Number(normalized);
  if (!Number.isFinite(numeric) || numeric < minimum || numeric > maximum) {
    providerResponseInvalid(`Provider coordinate ${field} is out of range.`);
  }
  return normalized;
}

export function buildNaverGeocodeRequest(
  address: string,
  apiKeyId: string,
  apiKey: string,
): Request {
  if (!address.trim() || !apiKeyId.trim() || !apiKey.trim()) {
    throw new GeocodingProviderError(
      "provider_configuration_invalid",
      "Address and NAVER credentials are required.",
    );
  }

  const url = new URL(NAVER_GEOCODE_ENDPOINT);
  url.searchParams.set("query", address.trim());
  url.searchParams.set("count", String(MAX_GEOCODE_CANDIDATES));

  return new Request(url, {
    method: "GET",
    headers: {
      Accept: "application/json",
      "x-ncp-apigw-api-key-id": apiKeyId.trim(),
      "x-ncp-apigw-api-key": apiKey.trim(),
    },
    redirect: "error",
  });
}

export function parseNaverGeocodeResponse(
  payload: unknown,
): GeocodeCandidate[] {
  if (!isRecord(payload) || payload.status !== "OK") {
    providerResponseInvalid("Provider response status is invalid.");
  }

  if (!isRecord(payload.meta)) {
    providerResponseInvalid("Provider response metadata is invalid.");
  }
  const totalCount = nonNegativeInteger(payload.meta, "totalCount");
  const page = nonNegativeInteger(payload.meta, "page");
  const count = nonNegativeInteger(payload.meta, "count");
  if (page < 1 || count > MAX_GEOCODE_CANDIDATES) {
    providerResponseInvalid("Provider response metadata is out of range.");
  }

  if (
    !Array.isArray(payload.addresses) ||
    payload.addresses.length > MAX_GEOCODE_CANDIDATES ||
    payload.addresses.length !== count ||
    totalCount < payload.addresses.length
  ) {
    providerResponseInvalid("Provider candidate count is invalid.");
  }

  return payload.addresses.map((candidate) => {
    if (!isRecord(candidate)) {
      providerResponseInvalid("Provider candidate is invalid.");
    }

    const roadAddress = boundedString(candidate, "roadAddress", 500);
    const jibunAddress = boundedString(candidate, "jibunAddress", 500);
    const englishAddress = boundedString(candidate, "englishAddress", 500);
    if (!roadAddress && !jibunAddress) {
      providerResponseInvalid("Provider candidate has no usable address.");
    }

    return {
      road_address: roadAddress,
      jibun_address: jibunAddress,
      english_address: englishAddress,
      // NAVER's x is longitude and y is latitude. Keep this explicit: a
      // silent axis swap can put a saved exhibition thousands of km away.
      longitude: coordinate(candidate, "x", -180, 180),
      latitude: coordinate(candidate, "y", -90, 90),
    };
  });
}
