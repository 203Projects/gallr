# gallr admin visual fidelity ledger

- Reference: `gallr-admin-primary-screen-concept.png`
- Verified fixture render: `gallr-admin-phase2-workspace.png`
- Verified live render: `gallr-admin-phase2-live-workspace.png`
- Verified mobile render: `gallr-admin-phase2-live-workspace-mobile.png`
- Verified authentication render: `gallr-admin-phase2-login.png`
- Verified Media render: `gallr-admin-phase3-media-desktop.png`
- Verified Media mobile render: `gallr-admin-phase3-media-mobile.png`

Authentication reference: `gallr-admin-login-concept.png`

| Check | Concept evidence | Render evidence | Resolution |
| --- | --- | --- | --- |
| Three-column hierarchy | Narrow left rail, dominant table, fixed right inspector | Same division at 1536 × 1024 | Matched with 176px rail and 440px inspector |
| Dense exhibition table | Ten open rows with hairline separators | Ten rows fit above the footer without clipping | Column widths and row height were tightened |
| Selection treatment | Orange leading rule and black selected-row boundary | Selected draft uses both treatments | Matched without an extra status color |
| Inspector structure | Header, revision, five tabs, fields, media, sticky actions | Same order and controls remain visible at native size | Textareas and cover block were compacted |
| Media workspace | Concept reserves media editing in the right inspector | Cover/gallery sections, square previews, explicit metadata saves, and sticky preview/publish actions use the same inspector grammar | Functional Phase 3 extension; no new color, radius, shadow, or modal pattern |
| Color and shape | White, black, gray rules; orange only for active/primary; square controls | No radius, shadow, or gradient; orange limited to nav/filter/new/publish | Matched to `DESIGN.md` tokens |
| Typography | Compact grotesk UI with Korean support | Self-hosted Inter and Gothic A1 | Existing product font assets reused |
| Mobile continuation | Not pictured in the source concept | 390 × 844 overlay editor and compact list verified | Added a dedicated close control and sticky footer |
| Authentication | Quiet rail and centered open login form; no card, shadow, or sign-up | Staff-only AuthGate uses the same rail, typography, square fields, and black action | Matched; inactive and non-staff states reuse the open layout |

Intentional differences:

- The concept's fictional editor count and total-record pagination were not
  copied. The implementation shows only the ten deterministic fixture records.
- Sample content uses 2026 data so browser and component tests remain stable.
- The desktop wordmark is visually omitted to preserve the concept's quiet
  rail; `gallr admin` appears in the compact mobile header for orientation.
- `Archive`/`Restore` replaces the concept's inert overflow action so lifecycle
  management is explicit and reversible.
- The concept shows a compact cover placeholder only. The Phase 3 Media tab
  extends it with signed upload, processing state, metadata, gallery ordering,
  and explicit removal while preserving the accepted visual system.
- Browser QA found the visually hidden file input was positioned outside its
  label after inspector scrolling. The label now owns a full-size transparent
  input, preserving a 44px target and reliable keyboard/file-chooser behavior.
