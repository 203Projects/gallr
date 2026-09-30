# Implementation plan

## Architecture

- Eleventy renders the chooser and accessible form shell. A small bundled client uses the pinned Supabase SDK; domain validation and adapter response validation are tested separately from DOM orchestration.
- Add a private registration relation keyed by request UUID and Auth user UUID. It reserves one private media slot and records the final submission UUID. Existing `content.exhibition_submissions` keep `public_form` source and gain a nullable submitter account association, avoiding changes to existing review enums and historical data.
- Authenticate via shared Supabase Auth email/password and verified email signup; do not change Auth settings or create role memberships. Draft fields and selected poster remain in memory through in-page login. SDK manages refresh; logout removes account-specific rendered records.
- Narrow RPCs reserve intake, finalize intake, and list the caller's recent submissions. Existing `create_exhibition_submission_impl` validates canonical payload and private media; the new wrapper additionally validates the actor, upload slot, object ownership, idempotency and contact role. Staff acceptance stays on the existing public-form branch.
- Rejection is shown accurately as rejection with notes; a new request retains its prior review history. Acceptance is distinct from publication.
- Reuse the existing admin submission notification trigger. Decision email support is deferred unless the current outbox renderer supports a public-web destination; the initial UI promises status in the account, not unimplemented email delivery.

## Constitution check

- I: specification written before implementation; plan and tasks follow it.
- II: write failing domain, adapter, navigation and pgTAP contracts before tested implementation.
- III: reuse canonical intake, media and review; no new role, portal, generic framework or unauthenticated endpoint.
- IV: chooser and authenticated submission are distinct testable stories, released together when backend and configuration are ready.
- V: existing audit/outbox covers intake; client failures use bounded operation codes without personal data.
- VI: no KMP changes. Independent web artifacts are explicitly exempt from shared KMP placement.

## Verification and release

Run Node 22.23.1 web tests/build and Playwright desktop/mobile registration coverage; visually inspect the chosen flow. Run lineage validation, clean replay in a task-specific disposable loopback database, all pgTAP suites, lint/advisors and documented concurrency gates where supported by the installed toolchain. Do not mutate any existing local, staging or production database. Report any unavailable gate.

The new intake is off by default behind `GALLR_ENABLE_ARTIST_SUBMISSIONS`. Staging must apply the migration, configure the matching publishable Auth project and demonstrate upload → review → publication before activation. The chooser remains useful with gallery handoff and contact fallback when intake is off.

## Complexity tracking

Two pinned dependencies (Supabase SDK and esbuild) provide reviewed Auth/session behavior and a self-hosted browser bundle. A hand-written Auth transport was rejected because it would duplicate token-refresh/security logic. No constitution exceptions.
