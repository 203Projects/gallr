import type { Mock } from "vitest";

type ModuleUnderTest = typeof import("./NaverMapsJsAdminGeocodingService");
type GeocodeCallback = (status: unknown, response: unknown) => void;

interface NaverTestWindow extends Window {
  naver?: unknown;
  navermap_authFailure?: () => void;
}

const testWindow = window as NaverTestWindow;
const READY_CALLBACK_PREFIX = "__gallrNaverMapsReady_";

let moduleUnderTest: ModuleUnderTest;

function deleteReadyCallbacks(): void {
  const globals = testWindow as unknown as Record<string, unknown>;
  Object.getOwnPropertyNames(testWindow)
    .filter((name) => name.startsWith(READY_CALLBACK_PREFIX))
    .forEach((name) => delete globals[name]);
}

function readyCallbackFor(script: HTMLScriptElement | null): {
  name: string;
  invoke: () => void;
} {
  expect(script).not.toBeNull();
  const name = new URL(script?.src ?? "").searchParams.get("callback");
  expect(name).toMatch(/^__gallrNaverMapsReady_[a-z0-9_]+$/);
  expect(name?.length).toBeLessThanOrEqual(64);
  const callback = (testWindow as unknown as Record<string, unknown>)[
    name ?? ""
  ];
  expect(callback).toBeTypeOf("function");
  return { name: name ?? "", invoke: callback as () => void };
}

function address(index = 1) {
  return {
    roadAddress: `서울 용산구 한남대로 ${index}`,
    jibunAddress: `서울 용산구 한남동 ${index}`,
    englishAddress: `${index} Hannam-daero, Yongsan-gu, Seoul`,
    addressElements: [
      { types: ["SIDO"], longName: "서울특별시", shortName: "서울특별시", code: "" },
      { types: ["SIGUGUN"], longName: "용산구", shortName: "용산구", code: "" },
    ],
    x: `127.000${index}`,
    y: `37.534${index}`,
  };
}

function installNaverSdk(
  response: unknown = { v2: { addresses: [address()] } },
  status: unknown = 200,
): Mock {
  const geocode = vi.fn(
    (
      _options: { query: string; count: number },
      callback: GeocodeCallback,
    ) => callback(status, response),
  );
  testWindow.naver = {
    maps: {
      Service: {
        Status: { OK: 200, ERROR: 500 },
        geocode,
      },
    },
  };
  return geocode;
}

function installDeferredNaverSdk(): {
  geocode: Mock;
  invokeCallback: (status: unknown, response: unknown) => void;
} {
  let callback: GeocodeCallback | null = null;
  const geocode = vi.fn(
    (
      _options: { query: string; count: number },
      nextCallback: GeocodeCallback,
    ) => {
      callback = nextCallback;
    },
  );
  testWindow.naver = {
    maps: {
      Service: {
        Status: { OK: 200, ERROR: 500 },
        geocode,
      },
    },
  };
  return {
    geocode,
    invokeCallback: (status, response) => {
      if (callback === null) {
        throw new Error("NAVER geocode callback was not registered.");
      }
      callback(status, response);
    },
  };
}

beforeEach(async () => {
  vi.resetModules();
  document.head.querySelectorAll("script").forEach((script) => script.remove());
  delete testWindow.naver;
  delete testWindow.navermap_authFailure;
  deleteReadyCallbacks();
  moduleUnderTest = await import("./NaverMapsJsAdminGeocodingService");
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  delete testWindow.naver;
  delete testWindow.navermap_authFailure;
  deleteReadyCallbacks();
});

describe("NaverMapsJsAdminGeocodingService", () => {
  it("defer-loads the official geocoder script once and deduplicates concurrent lookups", async () => {
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );

    expect(document.head.querySelectorAll("script")).toHaveLength(0);

    const firstLookup = service.searchAddress(" 서울 용산구 한남대로 28 ");
    const secondLookup = service.searchAddress("서울 종로구 세종대로 1");
    const scripts = document.head.querySelectorAll("script");

    expect(scripts).toHaveLength(1);
    const scriptUrl = new URL(scripts[0]?.src ?? "");
    expect(scriptUrl.origin + scriptUrl.pathname).toBe(
      "https://oapi.map.naver.com/openapi/v3/maps.js",
    );
    expect(scriptUrl.searchParams.get("ncpKeyId")).toBe("public-client-id");
    expect(scriptUrl.searchParams.get("submodules")).toBe("geocoder");
    const ready = readyCallbackFor(scripts[0] ?? null);

    let settled = false;
    void firstLookup.then(
      () => {
        settled = true;
      },
      () => {
        settled = true;
      },
    );
    scripts[0]?.dispatchEvent(new Event("load"));
    await Promise.resolve();
    expect(settled).toBe(false);

    const geocode = installNaverSdk();
    ready.invoke();

    await expect(firstLookup).resolves.toHaveLength(1);
    await expect(secondLookup).resolves.toHaveLength(1);
    expect(
      (testWindow as unknown as Record<string, unknown>)[ready.name],
    ).toBeUndefined();
    expect(geocode).toHaveBeenNthCalledWith(
      1,
      { query: "서울 용산구 한남대로 28", count: 3 },
      expect.any(Function),
    );
    expect(geocode).toHaveBeenNthCalledWith(
      2,
      { query: "서울 종로구 세종대로 1", count: 3 },
      expect.any(Function),
    );
  });

  it("maps x to longitude and y to latitude while returning at most three candidates", async () => {
    const geocode = installNaverSdk({
      v2: { addresses: [address(1), address(2), address(3), address(4)] },
    });
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );

    await expect(service.searchAddress("서울")).resolves.toEqual([
      {
        roadAddress: "서울 용산구 한남대로 1",
        jibunAddress: "서울 용산구 한남동 1",
        englishAddress: "1 Hannam-daero, Yongsan-gu, Seoul",
        cityKo: "서울",
        cityEn: "Seoul",
        regionKo: "용산구",
        regionEn: "Yongsan-gu",
        latitude: "37.5341",
        longitude: "127.0001",
      },
      {
        roadAddress: "서울 용산구 한남대로 2",
        jibunAddress: "서울 용산구 한남동 2",
        englishAddress: "2 Hannam-daero, Yongsan-gu, Seoul",
        cityKo: "서울",
        cityEn: "Seoul",
        regionKo: "용산구",
        regionEn: "Yongsan-gu",
        latitude: "37.5342",
        longitude: "127.0002",
      },
      {
        roadAddress: "서울 용산구 한남대로 3",
        jibunAddress: "서울 용산구 한남동 3",
        englishAddress: "3 Hannam-daero, Yongsan-gu, Seoul",
        cityKo: "서울",
        cityEn: "Seoul",
        regionKo: "용산구",
        regionEn: "Yongsan-gu",
        latitude: "37.5343",
        longitude: "127.0003",
      },
    ]);
    expect(geocode).toHaveBeenCalledWith(
      { query: "서울", count: 3 },
      expect.any(Function),
    );
  });

  it("chains and restores an existing authentication failure handler", async () => {
    const existingHandler = vi.fn();
    testWindow.navermap_authFailure = existingHandler;
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );

    const lookup = service.searchAddress("서울 용산구 한남대로 28");
    const ready = readyCallbackFor(document.head.querySelector("script"));
    expect(testWindow.navermap_authFailure).not.toBe(existingHandler);

    testWindow.navermap_authFailure?.();

    await expect(lookup).rejects.toMatchObject({
      code: "sdk_auth_failed",
      message: expect.stringContaining("registered Web service URL"),
    });
    expect(existingHandler).toHaveBeenCalledOnce();
    expect(testWindow.navermap_authFailure).toBe(existingHandler);
    expect(
      (testWindow as unknown as Record<string, unknown>)[ready.name],
    ).toBeUndefined();
  });

  it("reports an actionable script load error and permits a retry", async () => {
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );
    const firstLookup = service.searchAddress("서울");
    const firstScript = document.head.querySelector("script");
    const firstReady = readyCallbackFor(firstScript);

    firstScript?.dispatchEvent(new Event("error"));

    await expect(firstLookup).rejects.toMatchObject({
      code: "sdk_load_failed",
      message: expect.stringContaining("network connection"),
    });

    const secondLookup = service.searchAddress("서울");
    const secondScript = document.head.querySelector("script");
    expect(secondScript).not.toBe(firstScript);
    const secondReady = readyCallbackFor(secondScript);
    expect(secondReady.name).not.toBe(firstReady.name);
    installNaverSdk();
    secondReady.invoke();
    await expect(secondLookup).resolves.toHaveLength(1);
  });

  it("reports authentication/configuration when the loaded SDK lacks the geocoder", async () => {
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );
    const lookup = service.searchAddress("서울");
    const script = document.head.querySelector("script");
    testWindow.naver = { maps: {} };

    script?.dispatchEvent(new Event("load"));
    const pendingAfterLoad = Promise.race([
      lookup.then(
        () => "settled",
        () => "settled",
      ),
      Promise.resolve("pending"),
    ]);
    await expect(pendingAfterLoad).resolves.toBe("pending");
    readyCallbackFor(script).invoke();

    await expect(lookup).rejects.toMatchObject({
      code: "geocoder_unavailable",
      message: expect.stringContaining("Dynamic Map and Geocoding"),
    });
  });

  it("times out when NAVER never signals that its submodules are ready", async () => {
    vi.useFakeTimers();
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );
    const lookup = service.searchAddress("서울");
    const script = document.head.querySelector("script");
    const ready = readyCallbackFor(script);
    const rejection = expect(lookup).rejects.toMatchObject({
      code: "sdk_load_timeout",
      message: expect.stringContaining("did not finish loading"),
    });

    script?.dispatchEvent(new Event("load"));
    await vi.advanceTimersByTimeAsync(10_000);

    await rejection;
    expect(script?.isConnected).toBe(false);
    expect(
      (testWindow as unknown as Record<string, unknown>)[ready.name],
    ).toBeUndefined();
  });

  it("times out when NAVER accepts a geocoding request but never responds", async () => {
    vi.useFakeTimers();
    const { geocode } = installDeferredNaverSdk();
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );

    const lookup = service.searchAddress("서울 용산구 한남대로 28");
    const rejection = expect(lookup).rejects.toMatchObject({
      code: "geocode_timeout",
      message: expect.stringContaining("did not respond"),
    });

    await vi.advanceTimersByTimeAsync(10_000);

    await rejection;
    expect(geocode).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("ignores a NAVER geocoding callback that arrives after the request timeout", async () => {
    vi.useFakeTimers();
    const { invokeCallback } = installDeferredNaverSdk();
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );
    const lateResponseRead = vi.fn(() => ({ addresses: [address()] }));
    const lateResponse = Object.defineProperty({}, "v2", {
      get: lateResponseRead,
    });

    const lookup = service.searchAddress("서울 용산구 한남대로 28");
    const rejection = expect(lookup).rejects.toMatchObject({
      code: "geocode_timeout",
    });
    await vi.advanceTimersByTimeAsync(10_000);
    await rejection;

    invokeCallback(200, lateResponse);

    expect(lateResponseRead).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("turns a non-OK NAVER callback status into an actionable provider error", async () => {
    installNaverSdk({ v2: { addresses: [] } }, 500);
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );

    await expect(service.searchAddress("서울")).rejects.toMatchObject({
      code: "provider_error",
      message: expect.stringContaining("quota"),
    });
  });

  it.each([
    [
      "a malformed response container",
      { v2: { addresses: "not-an-array" } },
      "$.v2.addresses",
    ],
    [
      "an oversized address",
      {
        v2: {
          addresses: [{ ...address(), roadAddress: "가".repeat(501) }],
        },
      },
      "$.v2.addresses[0].roadAddress",
    ],
    [
      "an address with ASCII control characters",
      {
        v2: {
          addresses: [{ ...address(), roadAddress: "서울\u0000용산구" }],
        },
      },
      "$.v2.addresses[0].roadAddress",
    ],
    [
      "swapped or out-of-range axes",
      {
        v2: {
          addresses: [{ ...address(), x: "37.5344", y: "127.0005" }],
        },
      },
      "$.v2.addresses[0].y",
    ],
  ])("rejects %s", async (_label, response, path) => {
    installNaverSdk(response);
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );

    await expect(service.searchAddress("서울")).rejects.toMatchObject({
      code: "malformed_provider_response",
      path,
    });
  });

  it("rejects unsafe input before adding the NAVER script", async () => {
    const service = new moduleUnderTest.NaverMapsJsAdminGeocodingService(
      "public-client-id",
    );

    await expect(service.searchAddress("서울\u0000용산구")).rejects.toMatchObject({
      code: "invalid_address",
    });
    expect(document.head.querySelector("script")).toBeNull();
  });

  it("requires a bounded public client ID", () => {
    expect(
      () => new moduleUnderTest.NaverMapsJsAdminGeocodingService("  "),
    ).toThrowError(expect.objectContaining({ code: "invalid_client_id" }));
    expect(
      () =>
        new moduleUnderTest.NaverMapsJsAdminGeocodingService("a".repeat(201)),
    ).toThrowError(expect.objectContaining({ code: "invalid_client_id" }));
  });
});
