import type { SupabaseClient } from "@supabase/supabase-js";
import {
  MalformedGeocodingPayloadError,
  SupabaseAdminGeocodingService,
} from "./SupabaseAdminGeocodingService";

describe("SupabaseAdminGeocodingService", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("invokes the authenticated geocoding function and validates every candidate", async () => {
    const invoke = vi.fn(async () => ({
      data: {
        candidates: [
          {
            road_address: " 서울 용산구 한남대로 28 ",
            jibun_address: " 서울 용산구 한남동 1-1 ",
            english_address: " 28 Hannam-daero, Yongsan-gu, Seoul ",
            city_ko: " 서울 ",
            city_en: " Seoul ",
            region_ko: " 용산구 ",
            region_en: " Yongsan-gu ",
            latitude: "37.5344",
            longitude: "127.0005",
          },
        ],
      },
      error: null,
    }));
    const client = { functions: { invoke } } as unknown as SupabaseClient;

    await expect(
      new SupabaseAdminGeocodingService(client).searchAddress(
        " 서울 용산구 한남대로 28 ",
      ),
    ).resolves.toEqual([
      {
        roadAddress: "서울 용산구 한남대로 28",
        jibunAddress: "서울 용산구 한남동 1-1",
        englishAddress: "28 Hannam-daero, Yongsan-gu, Seoul",
        cityKo: "서울",
        cityEn: "Seoul",
        regionKo: "용산구",
        regionEn: "Yongsan-gu",
        latitude: "37.5344",
        longitude: "127.0005",
      },
    ]);
    expect(invoke).toHaveBeenCalledWith("geocode-address", {
      body: { address: "서울 용산구 한남대로 28" },
      signal: expect.any(AbortSignal),
    });
  });

  it("aborts a stalled Edge Function invocation after a bounded timeout", async () => {
    vi.useFakeTimers();
    let invocationSignal: AbortSignal | undefined;
    const invoke = vi.fn(
      (
        _functionName: string,
        options: { signal?: AbortSignal },
      ): Promise<{ data: unknown; error: null }> => {
        invocationSignal = options.signal;
        return new Promise(() => undefined);
      },
    );
    const client = { functions: { invoke } } as unknown as SupabaseClient;

    const lookup = new SupabaseAdminGeocodingService(client).searchAddress(
      "서울 용산구 한남대로 28",
    );
    void lookup.catch(() => undefined);

    expect(invocationSignal).toBeInstanceOf(AbortSignal);
    expect(invocationSignal?.aborted).toBe(false);
    await vi.runAllTimersAsync();

    await expect(lookup).rejects.toThrow(
      "The geocoding request timed out. Check the network and try again.",
    );
    expect(invocationSignal?.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("ignores an Edge Function result that settles after the client timeout", async () => {
    vi.useFakeTimers();
    let settleInvocation:
      | ((value: { data: unknown; error: null }) => void)
      | undefined;
    const invoke = vi.fn(
      (): Promise<{ data: unknown; error: null }> =>
        new Promise((resolve) => {
          settleInvocation = resolve;
        }),
    );
    const client = { functions: { invoke } } as unknown as SupabaseClient;
    const lateCandidatesRead = vi.fn(() => []);

    const lookup = new SupabaseAdminGeocodingService(client).searchAddress(
      "서울 용산구 한남대로 28",
    );
    void lookup.catch(() => undefined);
    await vi.runAllTimersAsync();
    await expect(lookup).rejects.toThrow(
      "The geocoding request timed out. Check the network and try again.",
    );

    const lateData = Object.defineProperty({}, "candidates", {
      get: lateCandidatesRead,
    });
    settleInvocation?.({ data: lateData, error: null });
    await Promise.resolve();

    expect(lateCandidatesRead).not.toHaveBeenCalled();
    await expect(lookup).rejects.toThrow("The geocoding request timed out");
  });

  it("normalizes an abort-aware invocation to the actionable timeout error", async () => {
    vi.useFakeTimers();
    const invoke = vi.fn(
      (
        _functionName: string,
        options: { signal?: AbortSignal },
      ): Promise<{ data: unknown; error: null }> =>
        new Promise((_resolve, reject) => {
          options.signal?.addEventListener(
            "abort",
            () => reject(new DOMException("internal detail", "AbortError")),
            { once: true },
          );
        }),
    );
    const client = { functions: { invoke } } as unknown as SupabaseClient;

    const lookup = new SupabaseAdminGeocodingService(client).searchAddress(
      "서울 용산구 한남대로 28",
    );
    void lookup.catch(() => undefined);
    await vi.runAllTimersAsync();

    await expect(lookup).rejects.toThrow(
      "The geocoding request timed out. Check the network and try again.",
    );
    await expect(lookup).rejects.not.toThrow("internal detail");
  });

  it("bounds reading a stalled structured error response", async () => {
    vi.useFakeTimers();
    const client = {
      functions: {
        invoke: vi.fn(async () => ({
          data: null,
          error: Object.assign(new Error("relay failed"), {
            context: { json: () => new Promise(() => undefined) },
          }),
        })),
      },
    } as unknown as SupabaseClient;

    const lookup = new SupabaseAdminGeocodingService(client).searchAddress(
      "서울 용산구 한남대로 28",
    );
    void lookup.catch(() => undefined);
    await vi.runAllTimersAsync();

    await expect(lookup).rejects.toThrow(
      "The geocoding request timed out. Check the network and try again.",
    );
  });

  it("rejects malformed coordinates instead of swapping or guessing axes", async () => {
    const client = {
      functions: {
        invoke: vi.fn(async () => ({
          data: {
            candidates: [
              {
                road_address: "서울",
                jibun_address: "",
                english_address: "Seoul",
                latitude: "127.0005",
                longitude: "37.5344",
              },
            ],
          },
          error: null,
        })),
      },
    } as unknown as SupabaseClient;

    await expect(
      new SupabaseAdminGeocodingService(client).searchAddress("서울"),
    ).rejects.toBeInstanceOf(MalformedGeocodingPayloadError);
  });

  it.each([
    ["an oversized address", "가".repeat(501)],
    ["an address with ASCII control characters", "서울\u0000용산구"],
  ])("rejects %s returned by the Edge Function", async (_label, roadAddress) => {
    const client = {
      functions: {
        invoke: vi.fn(async () => ({
          data: {
            candidates: [
              {
                road_address: roadAddress,
                jibun_address: "",
                english_address: "Seoul",
                latitude: "37.5344",
                longitude: "127.0005",
              },
            ],
          },
          error: null,
        })),
      },
    } as unknown as SupabaseClient;

    await expect(
      new SupabaseAdminGeocodingService(client).searchAddress("서울"),
    ).rejects.toMatchObject({
      name: "MalformedGeocodingPayloadError",
      path: "$.candidates[0].road_address",
    });
  });

  it("surfaces the structured Edge Function message from a FunctionsHttpError-like context", async () => {
    const context = {
      json: vi.fn(async () => ({
        error: {
          code: "active_staff_membership_required",
          message: "An active staff membership is required.",
        },
      })),
    };
    const functionError = Object.assign(
      new Error("Edge Function returned a non-2xx status code"),
      { context },
    );
    const client = {
      functions: {
        invoke: vi.fn(async () => ({ data: null, error: functionError })),
      },
    } as unknown as SupabaseClient;

    await expect(
      new SupabaseAdminGeocodingService(client).searchAddress("서울"),
    ).rejects.toThrow("An active staff membership is required.");
    expect(context.json).toHaveBeenCalledOnce();
  });

  it("falls back to the SDK error without exposing malformed relay content", async () => {
    const functionError = Object.assign(new Error("Geocoding request failed."), {
      context: {
        json: vi.fn(async () => ({
          error: {
            code: "provider_error",
            message: "unsafe\u0000message",
          },
        })),
      },
    });
    const client = {
      functions: {
        invoke: vi.fn(async () => ({ data: null, error: functionError })),
      },
    } as unknown as SupabaseClient;

    await expect(
      new SupabaseAdminGeocodingService(client).searchAddress("서울"),
    ).rejects.toThrow("Geocoding request failed.");
  });
});
