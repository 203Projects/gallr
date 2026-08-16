# Implementation Plan: My Gallr activation entry points

## Summary

Reuse the existing visit and following repositories across catalogue surfaces. Add pure eligibility
and gallery-grouping functions with tests, then render contextual entry points in exhibition detail,
List search, and gallery detail. Featured remains dedicated to exhibition discovery. No new backend
tables or authentication behavior are introduced.

## Architecture

- Shared catalogue contract: request `gallery_id` only from `canonical-v2`.
- App state: observe the account-aware visit/follow repositories once at the composition root.
- Featured: no archive activation gate or promotional insertion.
- Exhibition detail: an ended-only compact row creates the same visit record as bulk archive
  creation.
- List: pure catalogue grouping renders gallery matches before existing exhibition results.
- Gallery detail: ViewModel combines catalogue, follow, and visit flows; UI expands the programme
  locally.

## Verification

- Focused shared and Compose host tests while iterating.
- Shared/Compose/Android ktlint and tests after implementation.
- Android debug assembly and lint.
- iOS simulator compile and interaction capture where the environment permits.
- Visual QA against the selected compact exhibition-detail row mock.
