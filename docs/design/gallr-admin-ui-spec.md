# gallr admin UI specification

Source concepts:

- `docs/design/gallr-admin-primary-screen-concept.png`
- `docs/design/gallr-admin-login-concept.png`

## Product surface

The first usable surface is a desktop editorial workspace for listing, filtering,
creating, and editing exhibition drafts. It is an application screen, not a
marketing page. The table remains the dominant surface and a single right-hand
inspector owns editing.

## Allowed primary-screen copy

- `gallr admin`
- `Exhibitions`
- `Submissions`
- `Venues`
- `Events`
- `Editors`
- `Audit`
- `Search exhibitions...`
- `All`
- `Draft`
- `Published`
- `Archived`
- `New exhibition`
- `Exhibition`
- `Venue`
- `Dates`
- `Status`
- `Last edited`
- `Version`
- `revision`
- `All changes saved`
- `View history`
- `Basics`
- `Schedule`
- `Media`
- `Curation`
- `Preview`
- `Publish`
- `Archive`
- `Restore`
- `gallr`
- `Content admin`
- `Email`
- `Password`
- `Sign in`
- `Forgot password?`

Operational empty, error, loading, authentication, and validation messages may
be added when they communicate real state. Decorative labels, metrics, badges,
and marketing copy are not permitted.

## Design tokens

| Token | Value | Use |
| --- | --- | --- |
| `--color-background` | `#ffffff` | Application and field background |
| `--color-text` | `#000000` | Primary text and strong rules |
| `--color-muted` | `#525252` | Secondary metadata and placeholders |
| `--color-rule` | `#e5e5e5` | Hairline dividers and field borders |
| `--color-surface-muted` | `#f5f5f5` | Loading and disabled surfaces only |
| `--color-accent` | `#ff5400` | Active marker, new, and publish only |
| `--font-ui` | `Inter, "Gothic A1", sans-serif` | All application chrome and content |
| `--space-unit` | `8px` | Base layout grid |
| `--radius` | `0` | Every control and container |
| `--shadow` | `none` | Every control and container |

Typography uses 12-14px for dense controls and metadata, 16px for exhibition
names, 24-30px for the workspace title, and explicit line heights. Buttons and
fields never inherit browser-default typography.

## Container model

- Fixed navigation rail: 176px on a wide desktop.
- Flexible exhibition table: the primary working area.
- Editing inspector: 420-440px on a wide desktop.
- Divisions are 1px rules, not floating cards.
- The selected table row has a black outline and a 4px orange leading rule.
- Table rows are open bands separated by hairlines.
- Form sections are tabs inside the inspector, followed by open field groups.
- The inspector footer is sticky and contains `Preview` and `Publish`.

## Component inventory

- `AdminShell`
- `PrimaryNavigation`
- `WorkspaceHeader`
- `SearchField`
- `StatusFilter`
- `ExhibitionTable`
- `ExhibitionRow`
- `ExhibitionInspector`
- `InspectorTabs`
- `Field`, `TextArea`, and `DateField`
- `MediaEditor`, `MediaAsset`, `MediaPreview`, and `MetadataEditor`
- `RevisionStatus`
- `Button` with `standard`, `outlined`, and `accent` variants
- `AuthGate`
- `LoginRail` and `LoginForm`
- `EmptyState` and `ErrorNotice`

## Interaction model

1. Selecting a row opens it in the inspector.
2. Search and status filters update the table immediately.
3. `New exhibition` creates a permanent identity plus draft and selects it.
4. Edits mark the draft dirty; save state reflects pending/saved/error.
5. Inspector tabs change the visible field group without discarding edits.
6. `Preview` opens a code-native preview dialog or route.
7. `Publish` requires an authenticated publisher and confirmation.
8. Revision conflicts block publish and ask the editor to reload or compare.
9. Editing a published version creates or reuses a separate working draft;
   public readers stay on the old published version.
10. Publisher-only `Archive` removes a record from public projections without
    deleting history; `Restore` makes it editable again without restoring
    curation automatically.
11. A saved draft accepts signed JPEG/PNG/WebP uploads in the Media tab. Cover
    replacement preserves the previous cover as gallery media; removal is
    explicit, gallery order is exact, and presentation metadata saves against
    the current revision.
12. Processing and rejected states remain visible. Publish is blocked until all
    attached media is published, while generic exhibition JSON never accepts
    browser-supplied cover URLs or media presentation fields.

With Supabase environment variables configured, the app requires an authenticated
active staff record and uses the command/query adapter. Without them, a typed
in-memory repository provides deterministic fixture mode for component and visual
tests.

## Responsive continuation

- At 1100px and below, the inspector becomes an overlay panel.
- At 760px and below, navigation becomes a top bar/drawer and the table becomes
  a compact list while preserving columns as metadata lines.
- Touch targets remain at least 44px.
- No horizontal clipping of the primary action or editor fields is permitted.

## Icon treatment

Use small, square, 1.5px-stroke monochrome SVGs only when they clarify an action
(search, history, close, sign out, pagination). Do not use decorative icons or
colored icon containers. All icons use `currentColor` and align optically with
their labels.

## Motion

Use a 150ms opacity transition for inspector content and save-state changes.
Disable it under `prefers-reduced-motion`. Do not translate or float panels for
decoration.
