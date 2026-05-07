# Design System — gallr

## Product Context
- **What this is:** Exhibition discovery app for art lovers in Seoul, evolving into a taste-driven social platform (Letterboxd for art exhibitions)
- **Who it's for:** Art lovers in Seoul who actively visit smaller/indie galleries and treat exhibition visits as part of their identity
- **Space/industry:** Art/culture discovery, social curation
- **Project type:** Mobile app (KMP/Compose Multiplatform, iOS + Android)

## Aesthetic Direction
- **Direction:** Brutally Minimal / Reductionist Monochrome
- **Decoration level:** Minimal. Typography and whitespace do all the work. No shadows, no gradients, no rounded corners.
- **Mood:** Gallery wall. The app should feel like the white walls of a contemporary art gallery, where the art (exhibitions) is the focus and the frame disappears. Quiet confidence, not flashy.
- **Reference:** The monochrome constraint forces every pixel to earn its place. Color is reserved for a single accent that means "act now."

## Typography

### Font Stack
- **Primary (Latin):** Inter — neo-grotesque sans-serif. Clean, neutral, excellent readability at all sizes. Chosen for bilingual Korean/Latin support.
- **Fallback (Korean):** Gothic A1 Medium — Korean-optimized sans-serif for Hangul rendering. Compose uses the first font that can render a glyph, so Inter handles Latin chars and Gothic A1 handles Korean.
- **Loading:** Bundled via compose-resources (TTF files in app binary). No network font loading.

### Type Scale
| Role | Style | Size | Weight | Line Height | Letter Spacing | Usage |
|------|-------|------|--------|-------------|----------------|-------|
| displayLarge | Display | 40sp | Bold | 48sp | -0.025em | Sign-in brand title ("gallr") |
| displayMedium | Display | 32sp | Bold | 40sp | -0.015em | — |
| displaySmall | Display | 24sp | Medium | 32sp | 0em | — |
| headlineSmall | Headline | 24sp | Medium | 32sp | 0em | Empty state messages, section heroes |
| titleLarge | Title | 24sp | Bold | 32sp | 0em | — |
| titleMedium | Title | 18sp | Medium | 26sp | 0em | Profile display name, card titles |
| titleSmall | Title | 16sp | Medium | 22sp | 0em | — |
| bodyLarge | Body | 16sp | Normal | 24sp | 0em | — |
| bodyMedium | Body | 14sp | Normal | 20sp | 0em | Button labels, sign-in subtitles, form inputs |
| bodySmall | Body | 12sp | Normal | 18sp | 0em | Error messages, metadata, delete account |
| labelLarge | Label | 13sp | Medium | 18sp | 0.04em | Section titles ("EXHIBITION DIARY", "SETTINGS"), tab labels |
| labelMedium | Label | 12sp | Normal | 16sp | 0.04em | — |
| labelSmall | Label | 11sp | Normal | 16sp | 0.05em | Card venue names, thought badges, stat labels |

## Color

### Approach: Restrained
One accent color. Everything else is black, white, or gray. Color is rare and meaningful.

### Light Mode
| Token | Hex | Usage |
|-------|-----|-------|
| background | #FFFFFF | Screen backgrounds |
| onBackground | #000000 | Primary text, borders, filled buttons |
| surface | #FFFFFF | Card backgrounds |
| onSurface | #000000 | Card text |
| surfaceVariant | #F5F5F5 | Muted surfaces (avatar circles, image placeholders) |
| onSurfaceVariant | #525252 | Secondary text (subtitles, metadata, placeholders) |
| outline | #000000 | Card borders (1dp), focused text field borders (2dp) |
| outlineVariant | #E5E5E5 | Hairline dividers, unfocused text field borders (1dp) |
| error | #000000 | Error text (monochrome errors, see Error Treatment below) |
| scrim | #000000 | Overlays |

### Dark Mode
| Token | Hex | Usage |
|-------|-----|-------|
| background | #121212 | Screen backgrounds |
| onBackground | #E0E0E0 | Primary text |
| surface | #1E1E1E | Card backgrounds |
| surfaceVariant | #2C2C2C | Muted surfaces |
| onSurfaceVariant | #A0A0A0 | Secondary text |
| outline | #404040 | Card borders |
| outlineVariant | #333333 | Hairline dividers |

### Accent (Single, Intentional)
| Token | Hex | Role | Rule |
|-------|-----|------|------|
| ctaPrimary | #FF5400 | Primary call-to-action button fill | ONLY for the main action button (e.g., GallrEmptyState CTA) |
| activeIndicator | #FF5400 | Active tab underline, selected filter chips | ONLY for current selection indicators |
| interactionFeedback | #FF5400 | Pressed/active state color shift | ONLY for immediate touch feedback |

**Accent rules:** NEVER use #FF5400 for backgrounds, large surfaces, decoration, text on small targets, or any purpose not listed above. The accent is a signal, not a theme.

## Spacing

### 8pt Grid System
All layout decisions reference these tokens. Base unit: 8dp.

| Token | Value | Usage |
|-------|-------|-------|
| xs | 4dp | Tight internal padding (icon margins, small gaps) |
| sm | 8dp | Chip padding, label gap, gutter width |
| md | 16dp | Card internal padding, screen horizontal margin |
| lg | 24dp | Card-to-card gap, section sub-spacing |
| xl | 32dp | Major section spacing |
| xxl | 48dp | Full-screen section breaks |
| screenMargin | 16dp | Left/right screen edge padding |
| gutterWidth | 8dp | Column gutter width |

### Density: Comfortable
Not cramped, not spacious. Gallery-like breathing room without wasting space on a mobile screen.

## Layout
- **Approach:** Grid-disciplined
- **Grid:** Single column on mobile (full width minus 16dp margins), 2-column grid for diary cards (12dp gap)
- **Max content width:** Device width (mobile-only app)
- **Border radius:** 0dp on everything. Sharp rectangles only. This is the most distinctive visual rule. All shapes (extraSmall through extraLarge) are `RoundedCornerShape(0.dp)`.
- **Exception:** Avatar circles use `CircleShape` (the only non-rectangular element)

## Motion
- **Approach:** Minimal-functional
- **Philosophy:** State feedback relies on immediate color/opacity shift. No motion or positional animation.
- **Press duration:** < 100ms for press/active state color shift
- **Transitions:** System defaults only. No custom enter/exit animations.

## Component Patterns

### Buttons
- **Primary CTA (rare):** Orange fill (#FF5400), white text, sharp rectangle, `labelLarge` text, uppercase. Used ONLY in `GallrEmptyState`.
- **Standard action:** Black fill, white text, sharp rectangle, `bodyMedium` text. Used for sign-in buttons, primary form actions.
- **Outlined:** Black border, transparent background, sharp rectangle. Used for settings actions (sign out).
- **Text button:** No border, no background. Used for secondary actions (delete account, skip, toggle links).
- **Height:** 52dp for primary auth buttons, 44dp for settings buttons, 40dp for inline actions.

### Text Fields (new, added 2026-04-08)
- **Shape:** `RectangleShape` (0dp radius)
- **Unfocused border:** `outlineVariant` color, 1dp
- **Focused border:** `outline` color (black), 2dp
- **Error border:** `outline` color (black), 2dp + error text below
- **Placeholder:** `bodyMedium`, `onSurfaceVariant` color. Disappears on focus (no floating label animation).
- **Input text:** `bodyMedium`, `onBackground` color

### Error Treatment (new, added 2026-04-08)
- **Style:** Monochrome. No red. Stays on-brand.
- **Format:** `! Error message text` (exclamation prefix)
- **Typography:** `bodySmall`, `onBackground` color
- **Position:** Below the offending field, 4dp gap
- **Field indication:** Border thickens to 2dp on the errored field

### Empty States
- `GallrEmptyState` component: `headlineSmall` message text, centered, with optional orange CTA button below (24dp gap).
- Empty states should have warmth, a primary action, and context. "No items found" is not a design.

### Cards
- 1dp border (`outline` color), no shadow, no border radius
- Image fills top section, text content below with 8dp horizontal + 8dp vertical padding

### Avatar
- `CircleShape`, 72dp on profile screen
- Background: `surfaceVariant`
- Content: first letter of display name, `headlineSmall`, `onSurfaceVariant`
- Edit state: camera icon overlay at bottom-right corner

### Navigation
- 4-tab bottom navigation: Featured | List | Map | Profile
- Active tab: `activeIndicator` (#FF5400) underline
- Inactive tab: `onSurfaceVariant` color

## Auth Screen Hierarchy (added 2026-04-08)

### Sign-In Screen Layout (top to bottom)
1. Brand: "gallr" in `displayLarge`
2. Tagline: "discover exhibitions through taste" in `bodyMedium`, `onSurfaceVariant`
3. 64dp spacer
4. Email text field (outlined, sharp rectangle)
5. Password text field (with show/hide toggle)
6. Primary action button: "Sign In" (black fill, full width, 52dp height)
7. Toggle link: "Don't have an account? Sign Up" (`bodySmall`)
8. "Forgot password?" link (`bodySmall`, `onSurfaceVariant`)
9. Divider: "or continue with" (`outlineVariant` line, `bodySmall` text)
10. "Continue with Google" button (black fill)
11. "Continue with Apple" button (black fill)

### Sign-Up Mode
- Button text changes to "Sign Up"
- Toggle: "Already have an account? Sign In"
- "Forgot password?" hidden
- Instant swap, no animation

### Verification Screen
- "gallr" brand at top
- Mail icon or text
- "Check your email" in `headlineSmall`
- "We sent a verification link to [email]" in `bodyMedium`, `onSurfaceVariant`
- "Didn't receive it? Resend" text button
- "Back to Sign In" outlined button

## Accessibility
- Touch targets: minimum 44dp height on all interactive elements
- Password toggle: content description "Show password" / "Hide password"
- Avatar edit: content description "Change profile photo"
- Error messages: semantics role = error for screen readers
- Material3 components provide built-in accessibility labels

## Decisions Log
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-03-27 | Monochrome + single orange accent | Gallery-wall aesthetic, art is the focus |
| 2026-03-27 | Inter + Gothic A1 fonts | Bilingual Korean/Latin support |
| 2026-03-27 | 0dp border radius everywhere | Sharp, editorial, anti-generic |
| 2026-03-27 | 8pt spacing grid | Standard, predictable, flexible |
| 2026-04-08 | Custom text fields (sharp, no floating labels) | Match monochrome system, avoid Material3 rounded defaults |
| 2026-04-08 | Monochrome error treatment (! prefix, no red) | Stay on-brand, avoid introducing a third color |
| 2026-04-08 | Email above OAuth on sign-in screen | Email is the new feature, matches test account goal |
| 2026-04-08 | No toggle animation (instant swap) | Consistent with sharp, minimal aesthetic |
| 2026-04-08 | Avatar: letter initial + camera icon overlay | Clear edit affordance, personal before upload |
