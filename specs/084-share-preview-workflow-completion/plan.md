# Implementation Plan: Share preview and workflow emails

**Branch**: `084-share-preview-workflow-completion` · **Date**: 2026-09-22 · **Spec**: [spec.md](spec.md)

## Summary and Technical Context

Split native rendering from presentation; testable preview state holder and full-screen detail layer. Complete email delivery in the existing durable outbox. Existing Kotlin/Compose Multiplatform, coroutines, Android graphics/UIKit, Deno TypeScript and Postgres; retain pinned versions. No new runtime dependencies. Preserve unrelated web edits.

Completion work is isolated from the original dirty worktree and based on develop
`db2f49f`. Preserve upstream notification templates/events, query-parameter review
links, analytics, discovery navigation, and owner withdrawal behavior. The delta
is claim/contact snapshots, safe environment/provider delivery, and native preview.

## Constitution Check

Before research: PASS. Spec precedes code; observe failing tests before tested implementation. Stories are independently deliverable. Reusable theme rule goes in shared/commonMain; palette/image UI contracts and preview orchestration in composeApp/commonMain; native adapters only in platform sets. Email stays in existing backend. Sanitized logs and retry boundaries retained. No deviations.

After design: PASS for implemented paths. State is composition-owned and cancellable;
native adapters retain only platform operations, and email content/provider handling
remain in the existing backend. Database tests were observed failing and now pass.
The existing four-argument submission command is preserved; the contact overload
retains its authorization, revision and transactional outbox behavior with a contact-aware
request fingerprint. Execution evidence is recorded in tasks.md and verification.md.

## Project Structure

- `shared/src/commonMain/.../data/model/ThemeMode.kt`: resolved-theme rule.
- `composeApp/src/commonMain/.../share/`, `viewmodel/`, `ui/detail/`: palette, image, state and preview.
- `composeApp/src/{androidMain,iosMain}/.../ShareHandler.*.kt`: rendering/encoding/sheet metadata.
- `supabase/functions/outbox-delivery/`: email content/provider boundary.
- `supabase/migrations/`: new migration only if event payloads need completion; historical SQL immutable.

## Verification

Red then green focused tests; Compose common/style, Android build/lint, iOS simulator link, outbox-delivery tests/check. SQL changes require lineage and disposable local database gates. Record unavailable manual native/destination checks.

## Routing

Live TypeSafe jev-1.13.0 selected complex (confidence 1), mapped to Codex gpt-6-astra/high. applied=false; no in-session model switch.
