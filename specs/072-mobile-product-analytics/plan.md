# Plan: Mobile product analytics

## Architecture

```text
Typed mobile action
    → AnalyticsRecorder (shared)
    → bounded purgeable DataStore queue
    → batch client without Auth/user identity
    → mobile-analytics Edge validation + source quota
    → service-only transactional RPC
         ├─ private event-ID receipt (7-day TTL)
         └─ daily aggregate counter
```

### Shared mobile contracts

- Closed `MobileAnalyticsEvent` hierarchy and enum dimensions.
- `AnalyticsRecorder.record()` / `flush()` plus `NoopAnalyticsRecorder`.
- `DataStoreAnalyticsQueue`, capped at 200 and seven days in a dedicated
  OS-purgeable cache DataStore.
- `MobileAnalyticsApiClient` using the existing Ktor engine through a lazy,
  header-free client with no API key, Authorization, cookie, or user/session
  identity.
- Inject at Android/iOS composition roots; capture semantics remain in common
  application orchestration.

### Server contracts

- New chronological migration for private receipts, aggregate counters,
  source quotas, recorder/prune functions, RLS, grants, and indexes.
- `mobile-analytics` Edge Function following `index.ts → handler.ts → backend.ts`.
- Strict batch/body/schema validation and server-derived pseudonymous source key
  used only by the short-lived quota table, never joined to event facts.

### Dashboard

Start with SQL views/queries for daily counts, recommendation open rate,
high-intent action rate, route creation/start rate, and platform/app-major
health. No external paid analytics SDK is introduced.

## Constitution check

- **Spec-First:** privacy and measurement semantics are explicit before code.
- **Test-First:** shared, Edge, and pgTAP failures precede implementation.
- **Shared-First:** event rules, queue behavior, and network contracts live in
  shared Kotlin; platform roots only provide coarse platform/version values.
- **Simplicity:** aggregate-only v1 intentionally excludes identity-based
  retention, arbitrary event properties, and a third-party analytics SDK.
- **Observability:** pipeline errors use existing redacted `AppLog`; analytics
  facts never enter diagnostic logs.

## Supabase compatibility notes

- New tables are private and are not exposed through the Data API.
- Privileged functions pin an empty `search_path`, schema-qualify objects, and
  revoke `PUBLIC`, `anon`, and `authenticated` execution.
- The Edge backend alone receives the component-scoped secret.
- Run the canonical migration-lineage guard before database work and the full
  repository database workflow before handoff.

## Delivery slices

1. Privacy fixes and typed/no-op shared contract.
2. Database/Edge aggregate ingestion.
3. Bounded offline queue and Ktor delivery.
4. Mobile integration points and disabled-by-default preference/release gates.
5. Privacy/store disclosure, preference UI, staging evidence, then separately
   approved production enablement.
