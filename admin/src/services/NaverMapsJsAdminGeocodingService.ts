import type { AdminGeocodeCandidate } from "../domain";
import type { AdminGeocodingService } from "./AdminGeocodingService";

type JsonRecord = Record<string, unknown>;
type NaverAuthFailureHandler = () => void;
type GeocodeCallback = (status: unknown, response: unknown) => void;

interface NaverMapsService {
  Status: JsonRecord;
  geocode(
    options: { query: string; count: number },
    callback: GeocodeCallback,
  ): void;
}

interface NaverMapsSdk {
  maps: {
    Service: NaverMapsService;
  };
}

interface NaverBrowserWindow extends Window {
  naver?: unknown;
  navermap_authFailure?: NaverAuthFailureHandler;
}

export type NaverMapsJsGeocodingErrorCode =
  | "invalid_client_id"
  | "invalid_address"
  | "browser_unavailable"
  | "sdk_client_id_conflict"
  | "sdk_load_failed"
  | "sdk_load_timeout"
  | "sdk_auth_failed"
  | "geocoder_unavailable"
  | "geocode_timeout"
  | "provider_error"
  | "malformed_provider_response";

const SDK_BASE_URL = "https://oapi.map.naver.com/openapi/v3/maps.js";
const MAX_CLIENT_ID_LENGTH = 200;
const MAX_ADDRESS_LENGTH = 500;
const MAX_CANDIDATES = 3;
const SDK_LOAD_TIMEOUT_MS = 10_000;
const GEOCODE_TIMEOUT_MS = 10_000;
const READY_CALLBACK_PREFIX = "__gallrNaverMapsReady_";
const MAX_READY_CALLBACK_LENGTH = 64;
const MAX_READY_CALLBACK_ATTEMPTS = 1_000;

interface CanonicalCity {
  ko: string;
  en: string;
  englishAddressNames: readonly string[];
}

const CANONICAL_CITIES: Readonly<Record<string, CanonicalCity>> = {
  "서울특별시": { ko: "서울", en: "Seoul", englishAddressNames: ["Seoul"] },
  "부산광역시": { ko: "부산", en: "Busan", englishAddressNames: ["Busan"] },
  "대구광역시": { ko: "대구", en: "Daegu", englishAddressNames: ["Daegu"] },
  "인천광역시": { ko: "인천", en: "Incheon", englishAddressNames: ["Incheon"] },
  "광주광역시": { ko: "광주", en: "Gwangju", englishAddressNames: ["Gwangju"] },
  "대전광역시": { ko: "대전", en: "Daejeon", englishAddressNames: ["Daejeon"] },
  "울산광역시": { ko: "울산", en: "Ulsan", englishAddressNames: ["Ulsan"] },
  "세종특별자치시": { ko: "세종", en: "Sejong", englishAddressNames: ["Sejong-si", "Sejong"] },
  "경기도": { ko: "경기", en: "Gyeonggi", englishAddressNames: ["Gyeonggi-do", "Gyeonggi"] },
  "강원특별자치도": { ko: "강원", en: "Gangwon", englishAddressNames: ["Gangwon-do", "Gangwon State", "Gangwon"] },
  "강원도": { ko: "강원", en: "Gangwon", englishAddressNames: ["Gangwon-do", "Gangwon"] },
  "충청북도": { ko: "충북", en: "Chungbuk", englishAddressNames: ["Chungcheongbuk-do", "Chungbuk"] },
  "충청남도": { ko: "충남", en: "Chungnam", englishAddressNames: ["Chungcheongnam-do", "Chungnam"] },
  "전북특별자치도": { ko: "전북", en: "Jeonbuk", englishAddressNames: ["Jeonbuk State", "Jeollabuk-do", "Jeonbuk"] },
  "전라북도": { ko: "전북", en: "Jeonbuk", englishAddressNames: ["Jeollabuk-do", "Jeonbuk"] },
  "전라남도": { ko: "전남", en: "Jeonnam", englishAddressNames: ["Jeollanam-do", "Jeonnam"] },
  "경상북도": { ko: "경북", en: "Gyeongbuk", englishAddressNames: ["Gyeongsangbuk-do", "Gyeongbuk"] },
  "경상남도": { ko: "경남", en: "Gyeongnam", englishAddressNames: ["Gyeongsangnam-do", "Gyeongnam"] },
  "제주특별자치도": { ko: "제주", en: "Jeju", englishAddressNames: ["Jeju-do", "Jeju"] },
  "제주도": { ko: "제주", en: "Jeju", englishAddressNames: ["Jeju-do", "Jeju"] },
};

let sdkLoadPromise: Promise<NaverMapsSdk> | null = null;
let sdkLoadClientId: string | null = null;
let readyCallbackSequence = 0;

export class NaverMapsJsGeocodingError extends Error {
  constructor(
    readonly code: NaverMapsJsGeocodingErrorCode,
    message: string,
    readonly path?: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "NaverMapsJsGeocodingError";
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

function malformed(path: string, expected: string): never {
  throw new NaverMapsJsGeocodingError(
    "malformed_provider_response",
    `NAVER Maps geocoding returned malformed data at ${path}: expected ${expected}.`,
    path,
  );
}

function readAddressString(
  record: JsonRecord,
  key: "roadAddress" | "jibunAddress" | "englishAddress",
  path: string,
): string {
  const value = record[key];
  if (typeof value !== "string") {
    return malformed(`${path}.${key}`, "a string");
  }
  if (
    value.length > MAX_ADDRESS_LENGTH ||
    containsAsciiControlCharacters(value)
  ) {
    return malformed(
      `${path}.${key}`,
      `a string of at most ${MAX_ADDRESS_LENGTH} characters without ASCII control characters`,
    );
  }
  return value.trim();
}

function readCoordinate(
  record: JsonRecord,
  key: "x" | "y",
  path: string,
): string {
  const value = record[key];
  if (typeof value !== "string") {
    return malformed(`${path}.${key}`, "a decimal coordinate string");
  }
  const normalized = value.trim();
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(normalized)) {
    return malformed(`${path}.${key}`, "a decimal coordinate string");
  }
  const coordinate = Number(normalized);
  const inRange =
    Number.isFinite(coordinate) &&
    (key === "y"
      ? coordinate >= -90 && coordinate <= 90
      : coordinate >= -180 && coordinate <= 180);
  if (!inRange) {
    return malformed(
      `${path}.${key}`,
      key === "y" ? "-90 through 90" : "-180 through 180",
    );
  }
  return normalized;
}

function readAddressElement(
  candidate: JsonRecord,
  expectedType: "SIDO" | "SIGUGUN" | "DONGMYUN",
  path: string,
): string | null {
  const elements = candidate.addressElements;
  if (!Array.isArray(elements) || elements.length > 20) {
    return malformed(`${path}.addressElements`, "an array of at most 20 elements");
  }
  for (let index = 0; index < elements.length; index += 1) {
    const element = elements[index];
    const elementPath = `${path}.addressElements[${index}]`;
    if (!isRecord(element) || !Array.isArray(element.types) || element.types.length > 10) {
      return malformed(elementPath, "an address element");
    }
    if (!element.types.every((type) => typeof type === "string" && type.length <= 50)) {
      return malformed(`${elementPath}.types`, "short string values");
    }
    if (element.types.includes(expectedType)) {
      if (
        typeof element.longName !== "string" ||
        element.longName.length > 100 ||
        containsAsciiControlCharacters(element.longName)
      ) {
        return malformed(`${elementPath}.longName`, "a location label");
      }
      return element.longName.trim() || null;
    }
  }
  return null;
}

function readCanonicalLocation(
  candidate: JsonRecord,
  englishAddress: string,
  path: string,
): Pick<AdminGeocodeCandidate, "cityKo" | "cityEn" | "regionKo" | "regionEn"> {
  const sido = readAddressElement(candidate, "SIDO", path);
  const city = sido === null ? undefined : CANONICAL_CITIES[sido];
  if (city === undefined) {
    return malformed(`${path}.addressElements`, "a supported NAVER SIDO");
  }
  const regionSource =
    readAddressElement(candidate, "SIGUGUN", path) ??
    readAddressElement(candidate, "DONGMYUN", path);
  const regionKo = regionSource?.split(/\s+/u)[0] ?? "";
  if (!regionKo) {
    return malformed(`${path}.addressElements`, "a NAVER SIGUGUN");
  }
  const englishParts = englishAddress.split(",").map((part) => part.trim()).filter(Boolean);
  const cityIndex = englishParts.findIndex((part) => city.englishAddressNames.includes(part));
  const regionEn = cityIndex > 0 ? englishParts[cityIndex - 1] : "";
  if (!regionEn || regionEn.length > 100 || containsAsciiControlCharacters(regionEn)) {
    return malformed(`${path}.englishAddress`, "an English city and region");
  }
  return { cityKo: city.ko, cityEn: city.en, regionKo, regionEn };
}

function mapCandidate(value: unknown, index: number): AdminGeocodeCandidate {
  const path = `$.v2.addresses[${index}]`;
  if (!isRecord(value)) return malformed(path, "an object");

  const roadAddress = readAddressString(value, "roadAddress", path);
  const jibunAddress = readAddressString(value, "jibunAddress", path);
  const englishAddress = readAddressString(value, "englishAddress", path);
  if (roadAddress.length === 0 && jibunAddress.length === 0) {
    return malformed(path, "a road or lot-number address");
  }

  return {
    roadAddress,
    jibunAddress,
    englishAddress,
    ...readCanonicalLocation(value, englishAddress, path),
    longitude: readCoordinate(value, "x", path),
    latitude: readCoordinate(value, "y", path),
  };
}

function parseCandidates(response: unknown): AdminGeocodeCandidate[] {
  if (!isRecord(response)) return malformed("$", "an object");
  if (!isRecord(response.v2)) return malformed("$.v2", "an object");
  if (!Array.isArray(response.v2.addresses)) {
    return malformed("$.v2.addresses", "an array");
  }
  return response.v2.addresses.slice(0, MAX_CANDIDATES).map(mapCandidate);
}

function readSdk(browserWindow: NaverBrowserWindow): NaverMapsSdk | null {
  if (!isRecord(browserWindow.naver)) return null;
  const maps = browserWindow.naver.maps;
  if (!isRecord(maps) || !isRecord(maps.Service)) return null;
  const service = maps.Service;
  if (!isRecord(service.Status) || typeof service.geocode !== "function") {
    return null;
  }
  return { maps: { Service: service as unknown as NaverMapsService } };
}

function restoreAuthFailureHandler(
  browserWindow: NaverBrowserWindow,
  wrapper: NaverAuthFailureHandler,
  previous: NaverAuthFailureHandler | undefined,
): void {
  if (browserWindow.navermap_authFailure !== wrapper) return;
  if (previous === undefined) {
    delete browserWindow.navermap_authFailure;
  } else {
    browserWindow.navermap_authFailure = previous;
  }
}

function createReadyCallbackName(
  browserWindow: NaverBrowserWindow,
): string {
  const globals = browserWindow as unknown as Record<string, unknown>;
  const timestamp = Date.now().toString(36);
  for (let attempt = 0; attempt < MAX_READY_CALLBACK_ATTEMPTS; attempt += 1) {
    readyCallbackSequence = (readyCallbackSequence + 1) % 0x1000000;
    const name = `${READY_CALLBACK_PREFIX}${timestamp}_${readyCallbackSequence.toString(36)}`;
    if (name.length <= MAX_READY_CALLBACK_LENGTH && !(name in globals)) {
      return name;
    }
  }
  throw new NaverMapsJsGeocodingError(
    "sdk_load_failed",
    "Could not reserve a safe NAVER Maps readiness callback. Reload the page and try again.",
  );
}

function createSdkLoadPromise(
  clientId: string,
  browserWindow: NaverBrowserWindow,
  browserDocument: Document,
): Promise<NaverMapsSdk> {
  const existingSdk = readSdk(browserWindow);
  if (existingSdk !== null) return Promise.resolve(existingSdk);

  let script: HTMLScriptElement | null = null;
  let settled = false;
  let timeoutId: number | null = null;
  const previousAuthFailure = browserWindow.navermap_authFailure;
  const globals = browserWindow as unknown as Record<string, unknown>;
  const readyCallbackName = createReadyCallbackName(browserWindow);

  return new Promise<NaverMapsSdk>((resolve, reject) => {
    const cleanup = () => {
      if (timeoutId !== null) {
        browserWindow.clearTimeout(timeoutId);
        timeoutId = null;
      }
      if (script !== null) {
        script.onload = null;
        script.onerror = null;
      }
      if (globals[readyCallbackName] === readyCallback) {
        delete globals[readyCallbackName];
      }
      restoreAuthFailureHandler(
        browserWindow,
        authFailureWrapper,
        previousAuthFailure,
      );
    };
    const fail = (error: NaverMapsJsGeocodingError) => {
      if (settled) return;
      settled = true;
      cleanup();
      script?.remove();
      reject(error);
    };
    const authFailureWrapper = () => {
      try {
        previousAuthFailure?.();
      } catch {
        // The existing application handler remains isolated from this lookup.
      }
      fail(
        new NaverMapsJsGeocodingError(
          "sdk_auth_failed",
          "NAVER Maps authentication failed. Verify the public client ID and the registered Web service URL in NAVER Cloud.",
        ),
      );
    };
    const readyCallback = () => {
      if (settled) return;
      const sdk = readSdk(browserWindow);
      if (sdk === null) {
        fail(
          new NaverMapsJsGeocodingError(
            "geocoder_unavailable",
            "The NAVER Maps geocoder did not initialize. Ensure Dynamic Map and Geocoding are enabled for this client ID.",
          ),
        );
        return;
      }
      settled = true;
      cleanup();
      resolve(sdk);
    };

    browserWindow.navermap_authFailure = authFailureWrapper;
    globals[readyCallbackName] = readyCallback;
    script = browserDocument.createElement("script");
    script.async = true;
    script.src = `${SDK_BASE_URL}?ncpKeyId=${encodeURIComponent(clientId)}&submodules=geocoder&callback=${encodeURIComponent(readyCallbackName)}`;
    script.onerror = () => {
      fail(
        new NaverMapsJsGeocodingError(
          "sdk_load_failed",
          "Could not load the NAVER Maps JavaScript SDK. Check your network connection and the registered Web service URL.",
        ),
      );
    };
    timeoutId = browserWindow.setTimeout(() => {
      fail(
        new NaverMapsJsGeocodingError(
          "sdk_load_timeout",
          "The NAVER Maps JavaScript SDK did not finish loading. Check the network, client ID, and registered Web service URL, then try again.",
        ),
      );
    }, SDK_LOAD_TIMEOUT_MS);

    try {
      (browserDocument.head ?? browserDocument.documentElement).append(script);
    } catch (cause) {
      fail(
        new NaverMapsJsGeocodingError(
          "sdk_load_failed",
          "Could not add the NAVER Maps JavaScript SDK to this page.",
          undefined,
          { cause },
        ),
      );
    }
  });
}

function loadSdk(clientId: string): Promise<NaverMapsSdk> {
  if (typeof window === "undefined" || typeof document === "undefined") {
    return Promise.reject(
      new NaverMapsJsGeocodingError(
        "browser_unavailable",
        "NAVER Maps browser geocoding is available only in a web browser.",
      ),
    );
  }

  if (sdkLoadPromise !== null) {
    if (sdkLoadClientId !== clientId) {
      return Promise.reject(
        new NaverMapsJsGeocodingError(
          "sdk_client_id_conflict",
          "NAVER Maps is already initialized with a different public client ID. Reload the page after correcting the configuration.",
        ),
      );
    }
    return sdkLoadPromise;
  }

  const browserWindow = window as NaverBrowserWindow;
  sdkLoadClientId = clientId;
  const promise = createSdkLoadPromise(clientId, browserWindow, document);
  sdkLoadPromise = promise;
  void promise.catch(() => {
    if (sdkLoadPromise === promise) {
      sdkLoadPromise = null;
      sdkLoadClientId = null;
    }
  });
  return promise;
}

function normalizeAddress(address: string): string {
  if (typeof address !== "string") {
    throw new NaverMapsJsGeocodingError(
      "invalid_address",
      "Enter a Korean address before searching NAVER Maps.",
    );
  }
  const query = address.trim();
  if (
    query.length === 0 ||
    address.length > MAX_ADDRESS_LENGTH ||
    containsAsciiControlCharacters(address)
  ) {
    throw new NaverMapsJsGeocodingError(
      "invalid_address",
      `Enter a Korean address of at most ${MAX_ADDRESS_LENGTH} characters without control characters.`,
    );
  }
  return query;
}

function normalizeClientId(clientId: string): string {
  if (typeof clientId !== "string") {
    throw new NaverMapsJsGeocodingError(
      "invalid_client_id",
      "A NAVER Maps public client ID is required for browser geocoding.",
    );
  }
  const normalized = clientId.trim();
  if (
    normalized.length === 0 ||
    clientId.length > MAX_CLIENT_ID_LENGTH ||
    containsAsciiControlCharacters(clientId)
  ) {
    throw new NaverMapsJsGeocodingError(
      "invalid_client_id",
      `Provide a NAVER Maps public client ID of at most ${MAX_CLIENT_ID_LENGTH} characters without control characters.`,
    );
  }
  return normalized;
}

export class NaverMapsJsAdminGeocodingService
  implements AdminGeocodingService
{
  readonly mode = "naver-browser" as const;
  private readonly clientId: string;

  constructor(clientId: string) {
    this.clientId = normalizeClientId(clientId);
  }

  async searchAddress(address: string): Promise<AdminGeocodeCandidate[]> {
    const query = normalizeAddress(address);
    const sdk = await loadSdk(this.clientId);
    const browserWindow = window as NaverBrowserWindow;

    return await new Promise<AdminGeocodeCandidate[]>((resolve, reject) => {
      let settled = false;
      let timeoutId: number | null = null;
      const cleanup = () => {
        if (timeoutId !== null) {
          browserWindow.clearTimeout(timeoutId);
          timeoutId = null;
        }
      };
      const fail = (error: unknown) => {
        if (settled) return;
        settled = true;
        cleanup();
        reject(error);
      };
      const succeed = (candidates: AdminGeocodeCandidate[]) => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve(candidates);
      };
      const callback: GeocodeCallback = (status, response) => {
        if (settled) return;
        if (status !== sdk.maps.Service.Status.OK) {
          fail(
            new NaverMapsJsGeocodingError(
              "provider_error",
              "NAVER Maps rejected the geocoding request. Enable Web Dynamic Map and Geocoding, register this page's origin as a Web service URL, and check that quota is available.",
            ),
          );
          return;
        }
        try {
          succeed(parseCandidates(response));
        } catch (error) {
          fail(error);
        }
      };

      timeoutId = browserWindow.setTimeout(() => {
        fail(
          new NaverMapsJsGeocodingError(
            "geocode_timeout",
            "NAVER Maps did not respond to the geocoding request within 10 seconds. Check the network and NAVER Maps quota, then try again.",
          ),
        );
      }, GEOCODE_TIMEOUT_MS);

      try {
        sdk.maps.Service.geocode(
          { query, count: MAX_CANDIDATES },
          callback,
        );
      } catch (cause) {
        fail(
          new NaverMapsJsGeocodingError(
            "provider_error",
            "NAVER Maps could not start the geocoding request. Verify the browser SDK configuration.",
            undefined,
            { cause },
          ),
        );
      }
    });
  }
}
