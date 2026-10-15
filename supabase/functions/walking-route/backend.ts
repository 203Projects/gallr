import { createClient } from "@supabase/supabase-js";
import { resolveSupabaseSecretKey } from "../_shared/supabase_keys.ts";
import type { ProviderName } from "./providers/provider.ts";

/** Two position keys, the bytewise-smaller first, as the cache stores them. */
export interface PositionPair {
  fromKey: string;
  toKey: string;
}

export interface CachedLeg extends PositionPair {
  distanceM: number;
  /** Drawn from fromKey to toKey. */
  polyline: string;
  routedAt: Date;
}

export interface LegToStore extends PositionPair {
  fromExhibitionId: string;
  toExhibitionId: string;
  distanceM: number;
  polyline: string;
  provider: ProviderName;
}

export interface QuotaDecision {
  allowed: boolean;
  retryAfterSeconds: number;
  providerCallsGranted: number;
}

/** The three service-role RPCs of migration 20261014130000_walking_route_cache.sql. */
export interface WalkingRouteBackend {
  consumeQuota(
    source: string | null,
    legs: number,
    providerCalls: number,
  ): Promise<QuotaDecision>;
  lookup(pairs: PositionPair[]): Promise<CachedLeg[]>;
  store(legs: LegToStore[]): Promise<number>;
}

export type RpcCall = (
  name: string,
  args: Record<string, unknown>,
) => Promise<{ data: unknown; error: unknown }>;

const MAX_RETRY_AFTER_SECONDS = 3600;
const MAX_PROVIDER_CALLS = 5;

class BackendError extends Error {
  constructor(operation: string) {
    super(`Walking route ${operation} failed.`);
    this.name = "BackendError";
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function boundedInteger(value: unknown, min: number, max: number): boolean {
  return Number.isSafeInteger(value) && (value as number) >= min &&
    (value as number) <= max;
}

function parseDecision(data: unknown): QuotaDecision {
  if (
    !isRecord(data) || typeof data.allowed !== "boolean" ||
    !boundedInteger(data.provider_calls_granted, 0, MAX_PROVIDER_CALLS) ||
    !boundedInteger(data.retry_after_seconds, 0, MAX_RETRY_AFTER_SECONDS)
  ) throw new BackendError("quota");
  const retryAfterSeconds = data.retry_after_seconds as number;
  if (data.allowed === (retryAfterSeconds > 0)) {
    throw new BackendError("quota");
  }
  return {
    allowed: data.allowed,
    retryAfterSeconds,
    providerCallsGranted: data.provider_calls_granted as number,
  };
}

function parseCachedLeg(row: unknown): CachedLeg {
  if (
    !isRecord(row) || typeof row.from_key !== "string" ||
    typeof row.to_key !== "string" || typeof row.polyline !== "string" ||
    row.polyline.length === 0 ||
    !boundedInteger(row.distance_m, 0, 1_000_000) ||
    typeof row.routed_at !== "string"
  ) throw new BackendError("lookup");
  const routedAt = new Date(row.routed_at);
  if (Number.isNaN(routedAt.getTime())) throw new BackendError("lookup");
  return {
    fromKey: row.from_key,
    toKey: row.to_key,
    distanceM: row.distance_m as number,
    polyline: row.polyline,
    routedAt,
  };
}

export function createRpcBackend(rpc: RpcCall): WalkingRouteBackend {
  async function call(
    operation: string,
    name: string,
    args: Record<string, unknown>,
  ): Promise<unknown> {
    const { data, error } = await rpc(name, args);
    if (error) throw new BackendError(operation);
    return data;
  }

  return {
    async consumeQuota(source, legs, providerCalls) {
      return parseDecision(
        await call("quota", "service_walking_route_consume", {
          p_source: source,
          p_legs: legs,
          p_provider_calls: providerCalls,
        }),
      );
    },
    async lookup(pairs) {
      const data = await call("lookup", "service_walking_legs_lookup", {
        p_pairs: pairs.map((pair) => ({
          from_key: pair.fromKey,
          to_key: pair.toKey,
        })),
      });
      if (!Array.isArray(data)) throw new BackendError("lookup");
      return data.map(parseCachedLeg);
    },
    async store(legs) {
      if (legs.length === 0) return 0;
      const data = await call("store", "service_walking_legs_store", {
        p_legs: legs.map((leg) => ({
          from_key: leg.fromKey,
          from_exhibition_id: leg.fromExhibitionId,
          to_key: leg.toKey,
          to_exhibition_id: leg.toExhibitionId,
          distance_m: leg.distanceM,
          polyline: leg.polyline,
          provider: leg.provider,
        })),
      });
      if (!boundedInteger(data, 0, legs.length)) {
        throw new BackendError("store");
      }
      return data as number;
    },
  };
}

function required(environment: Record<string, string>, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

/** Service-role RPCs with the `walking-route` component key (or the project default) from SUPABASE_SECRET_KEYS. */
export function createWalkingRouteBackend(
  environment: Record<string, string>,
): WalkingRouteBackend {
  const client = createClient(
    required(environment, "SUPABASE_URL"),
    resolveSupabaseSecretKey(environment, "walking-route"),
    { auth: { autoRefreshToken: false, persistSession: false } },
  );
  return createRpcBackend(async (name, args) => {
    const { data, error } = await client.rpc(name, args);
    return { data, error };
  });
}
