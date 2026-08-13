<p align="center">
  <img src="web/public/logos/b-arch-pin.svg" width="72" alt="gallr arch pin">
</p>

<h1 align="center">gallr</h1>

<p align="center">
  Bilingual exhibition discovery for Korea.<br>
  Find what is showing, save what matters, and follow the people and places shaping the scene.
</p>

<p align="center">
  <a href="https://gallrmap.com">Explore gallr</a> ·
  <a href="CHANGELOG.md">What's new</a> ·
  <a href="CLAUDE.md">Engineering guide</a>
</p>

---

## What gallr does

- Browse exhibitions through featured, list, and map views.
- Filter by city, district, event, and editor-curated selections.
- Save exhibitions and receive local opening, reception, and closing reminders.
- Read Korean or English throughout the experience.
- Give galleries a structured workspace for claims, submissions, review, and launch tools.
- Keep publishing controlled: nothing reaches the public catalogue without staff review.

## Product surfaces

| Surface | Audience | Purpose |
|---|---|---|
| Android + iOS | Art visitors | Discover, map, bookmark, and share exhibitions |
| [Public web](https://gallrmap.com) | Everyone | Browse the live catalogue and learn about gallr |
| [Gallery workspace](https://gallery.gallrmap.com) | Gallery operators | Maintain gallery information and submit exhibitions |
| [Editor portal](https://editor.gallrmap.com) | Invited editors | Maintain profiles, statements, and curated selections |
| [Admin](https://admin.gallrmap.com) | gallr staff | Review, publish, archive, and operate the catalogue |

## How it fits together

```text
Gallery workspace ─┐
Editor portal ─────┼─► staff review in Admin ─► canonical Supabase catalogue
Staff authoring ───┘                                      │
                                       ┌──────────────────┴─────────────────┐
                                       ▼                                    ▼
                              Android + iOS                         Public web build
```

This is one monorepo with clear product boundaries:

| Area | Stack | Responsibility |
|---|---|---|
| `androidApp/`, `composeApp/`, `shared/`, `iosApp/` | Kotlin + Compose Multiplatform | Mobile apps, shared domain logic, native adapters |
| `web/` | Eleventy | Static public site and catalogue |
| `gallery/` | React + Vite | Gallery-owner workspace |
| `admin/` | React + Vite | Staff Admin and editor portal entrypoint |
| `supabase/` | Postgres, Auth, Storage, Edge Functions | Versioned content, publishing commands, audit, and delivery |

The full data and publishing design lives in
[Exhibition content architecture](docs/exhibition-content-architecture.md).

## Quick start

### Prerequisites

- JDK 17 and the Android SDK for mobile work
- Xcode for iOS builds
- Node.js 22+ and npm for web workspaces
- Supabase CLI only when working on database or Edge Function changes

### Run a web surface

```bash
# Public web
cd web && npm ci && npm run dev

# Staff Admin / editor portal
cd admin && npm ci && npm run dev

# Gallery workspace
cd gallery && npm ci && npm run dev
```

### Build and test mobile

```bash
./gradlew :androidApp:assembleDebug
./gradlew :shared:allTests :composeApp:allTests
./gradlew :androidApp:lintDebug
```

Open `iosApp/iosApp.xcodeproj` in Xcode for the iOS host. For subsystem-specific commands and
configuration, read the nearest `AGENTS.md` and the canonical [engineering guide](CLAUDE.md).

### Configuration

Never commit credentials. Use 1Password as the source of truth and inject only the values needed by
the current command. Public clients accept Supabase URL and publishable-key configuration; secret or
service-role keys are rejected. Example variable names are documented in each workspace.

## Delivery model

```text
feature branch ─► PR to develop ─► CI + preview ─► promotion PR ─► main ─► production
```

- `develop` is the default working and integration branch.
- `main` is production-only; it receives reviewed promotion pull requests.
- Vercel creates previews for changed web surfaces and production deployments from `main`.
- Mobile releases are versioned in [`VERSION`](VERSION) and [`CHANGELOG.md`](CHANGELOG.md), then
  recorded with a matching `v<version>` GitHub Release. Store upload and review are separate,
  explicitly approved operations.

## Repository map

```text
gallr/
├── androidApp/      Android application host
├── composeApp/      Shared Compose UI and platform adapters
├── shared/          Domain models, networking, repositories, sync
├── iosApp/          iOS host, assets, Fastlane archive tooling
├── web/             Public Eleventy site
├── admin/           Staff Admin + editor portal
├── gallery/         Gallery-owner workspace
├── supabase/        Migrations, tests, and Edge Functions
├── specs/           Numbered feature specifications
└── docs/            Architecture, design, and operational runbooks
```

## Useful documentation

- [Engineering guide](CLAUDE.md) — architecture boundaries, conventions, and verification commands
- [Design system](DESIGN.md) — typography, spacing, colour, and interaction rules
- [Editor onboarding](docs/editor-onboarding-guide.md) — editor access and curation workflow
- [Gallery-owner release runbook](docs/gallery-owner-release-runbook.md) — owner publishing operations
- [Database migration lineage](docs/database-migration-lineage.md) — immutable migration policy
- [Public catalogue cutover](docs/public-exhibition-catalog-cutover-runbook.md) — rollout and rollback gates

## Contributing

Start from `develop`, keep changes inside the owning subsystem, and open a pull request back to
`develop`. Run the narrowest relevant checks locally; GitHub Actions validates affected product
surfaces and database changes. Read `DESIGN.md` before any UI change.

---

<p align="center">gallr — exhibitions, mapped with context.</p>
