# Design Guide — In-Cabin Monitoring Dashboard

## Philosophy

Five principles govern every UI decision:

1. **Every element justifies its existence.** If it doesn't help the driver or operator understand what's happening, remove it. No decorative borders, no gratuitous icons, no chrome.

2. **Information hierarchy through typography, not color overload.** Size and weight establish importance. Color is reserved for semantic meaning: safe, caution, danger. A screen full of colors is a screen with no hierarchy.

3. **Generous whitespace.** Elements need breathing room. Cramped layouts increase cognitive load. On a 1920x720 automotive display, space is the luxury we can afford.

4. **Subtle animations.** State transitions should be smooth, never jarring. Animations communicate change — a risk level shifting, a detection appearing — without demanding attention. No bouncing, no overshooting, no gratuitous motion.

5. **Automotive-appropriate.** The dashboard is glanced at from 60-80cm, not studied up close. Text must be legible at that distance. Contrast must be high. Critical information (risk level, active detections) must be readable in under 1 second.

---

## Target Display

- **Resolution:** 1920 x 720 (landscape, ultra-wide)
- **Density:** 160dpi
- **Refresh:** 60Hz
- **Viewing distance:** 60-80cm (driver peripheral / operator direct)
- **Ambient light:** Variable (garage to direct sunlight)

The ultra-wide aspect ratio (8:3) is the defining constraint. Vertical stacking wastes most of the width. The layout must be horizontal.

---

## Layout: Three-Zone Horizontal Split

```
+---------------------+--------------------------+----------------------+
| LEFT PANEL (100dp)  |  CENTER (flexible)       |  RIGHT PANEL (380dp) |
|                     |                          |                      |
|  Score Arc          |                          |  Risk Indicator      |
|  Streak / Session   |   Camera Preview         |  Driver Name         |
|  ─────────          |   (or idle branding)     |  Passengers          |
|  Camera Status      |                          |  Detections          |
|  Controls           |                          |  Distraction Timer   |
|  Start/Stop         |                          |                      |
|                     |   AI Status Message      |  Ticker (3 events)   |
+---------------------+--------------------------+----------------------+
|                     KPIT Technologies, India                          |
+-----------------------------------------------------------------------+
```

- **Left panel** — Always visible. Controls (buttons) at the bottom, score/streak at the top. Score section hides when not monitoring.
- **Center** — Flexible width. Camera preview when monitoring with preview enabled. Idle branding ("Honda Smart Cabin") when not monitoring. AI status message overlaid at bottom with gradient scrim.
- **Right panel** — Safety dashboard. Visible only during monitoring. Shows risk level, driver identity, passenger count, active detections, distraction timer, and recent event ticker.
- **Footer** — Single line, smallest type size, muted color. Branding only.

Implementation: `LinearLayout` (horizontal) with nested vertical `LinearLayout` for side panels and `FrameLayout` for center (allows AI status overlay on preview). Framework views only, no third-party layout libraries.

---

## Color Palette

Defined in `res/values/colors.xml`. Every color has a semantic name tied to its purpose.

### Backgrounds

| Token | Hex | Usage |
|---|---|---|
| `background` | `#0A0A0F` | Root background. Near-black with cool blue tint — not pure `#000000` which feels dead on LCD panels. |
| `surface` | `#12131A` | Panel backgrounds. Subtle lift above root. |
| `surface_elevated` | `#1A1B24` | Interactive elements: buttons, inputs. Third elevation tier. |
| `divider` | `#1E1F2A` | 1dp separators between sections. Barely visible, just enough structure. |

### Text

| Token | Hex | Usage |
|---|---|---|
| `text_primary` | `#E8E9ED` | Primary content. Soft white — not `#FFFFFF` which is harsh on dark backgrounds and causes halation. |
| `text_secondary` | `#6B6E7B` | Labels, captions, non-critical info. Readable but doesn't compete with primary. |
| `text_muted` | `#3D3F4A` | Footer, disabled states, ticker history. Present but receding. |

### Semantic

| Token | Hex | Usage |
|---|---|---|
| `accent` | `#5B8DEF` | Active indicators, driver name, links. Cool blue that stands out without alarming. |
| `safe` | `#2ECC71` | Low risk, "All Clear" label. Calm green. |
| `caution` | `#F39C12` | Medium risk, warning detections. Warm amber. |
| `danger` | `#E74C3C` | High risk, critical detections (phone, eyes closed). Urgent red. |
| `gold` | `#F1C40F` | Streak milestones, achievements. Celebratory without being alarming. |

### Rules

- Never use raw hex values in Kotlin or XML. Always reference the palette tokens.
- `danger` is reserved for genuinely dangerous conditions. Don't use it for emphasis.
- Text on `safe`/`caution`/`danger` backgrounds uses black (`#000000`) for maximum contrast.
- The three-tier background system (`background` < `surface` < `surface_elevated`) creates depth without borders.

---

## Typography

Defined in `res/values/styles.xml`. System Roboto — no custom fonts, no extra APK weight.

| Style | Size | Weight | Default Color | Usage |
|---|---|---|---|---|
| `TextDisplay` | 48sp | Bold | `text_primary` | Score number inside arc |
| `TextHeadline` | 28sp | Bold | `text_primary` | Risk level pill |
| `TextTitle` | 20sp | Medium (500) | `text_primary` | Driver name, section headers |
| `TextBody` | 16sp | Normal | `text_primary` | AI status message, detection labels |
| `TextCaption` | 13sp | Normal | `text_secondary` | Streak timer, session timer, button labels |
| `TextMicro` | 11sp | Normal | `text_muted` | Ticker events, camera status, footer |

### Rules

- Size establishes hierarchy. If two elements have the same size, one of them is wrong.
- Bold is reserved for `Display` and `Headline`. Everything else uses normal or medium weight.
- Every `TextView` in the layout uses one of these styles via `style="@style/TextXxx"`. No inline `textSize` overrides.
- At 160dpi and 60-80cm viewing distance, `TextBody` (16sp) is the minimum comfortable reading size. `TextCaption` and `TextMicro` are for peripheral/non-critical information only.

---

## Spacing

Defined in `res/values/dimens.xml`. Based on a 4dp grid.

| Token | Value | Usage |
|---|---|---|
| `space_xs` | 4dp | Tight gaps (between related text lines) |
| `space_sm` | 8dp | Standard gap between related elements |
| `space_md` | 12dp | Gap between element groups |
| `space_lg` | 16dp | Panel padding, section separation |
| `space_xl` | 24dp | Large vertical spacing |

### Panel Dimensions

| Token | Value |
|---|---|
| `left_panel_width` | 100dp |
| `right_panel_width` | 380dp |
| `score_arc_size` | 120dp |
| `button_height` | 44dp (standard), 48dp (primary action) |
| `button_radius` | 8dp |
| `risk_pill_radius` | 12dp |

---

## Components

### Score Arc
- 120x120dp circular arc showing safety score 0-100
- Animated transitions via `ValueAnimator` (400ms)
- Arc color follows palette: `safe` (>=70), `caution` (40-69), `danger` (<40)
- Glow shadow on foreground arc for depth
- Hidden when not monitoring

### Risk Indicator
- Compact pill shape with 12dp rounded corners
- 28sp bold text: "LOW", "MEDIUM", "HIGH", "NO OCCUPANTS"
- Background color transitions smoothly via `ValueAnimator.ofArgb()` (500ms)
- LOW = `safe`, MEDIUM = `caution`, HIGH = `danger`, NO OCCUPANTS = `surface` with `text_secondary`

### Detection Labels
- Vertical stack in right panel, one label per active detection
- Format: colored dot + label text ("Phone Detected", "Eyes Closed", etc.)
- Dot color: `danger` for critical (phone, eyes), `caution` for warnings (yawning, eating)
- Fade in (200ms) / fade out (300ms) via alpha animation
- Empty state: "All Clear" in `safe` color

### AI Status Message
- Overlaid on bottom of center preview area
- Gradient scrim background (transparent to `surface`) for readability over preview
- `TextBody` style (16sp normal), `text_secondary` color
- Crossfade on text change (150ms)
- Contextual messages rotate based on system state

### Buttons
- `surface_elevated` background with `button_radius` corners
- 44dp standard height, 48dp for primary action (Start/Stop)
- Start/Stop uses `accent` background with white text
- No elevation shadow (`stateListAnimator="@null"`)
- `TextCaption` size (13sp) for labels
- 8dp vertical spacing between buttons

### Ticker
- Bottom of right panel, shows last 3 detection events
- `TextMicro` style, `text_muted` color
- Single line per event, ellipsize at end
- Separated from detections by a `divider` line

### Footer
- `TextMicro` style (11sp, `text_muted`)
- Centered at bottom of screen
- Branding text only

---

## Animations

All animations use framework APIs only. No third-party animation libraries.

| Transition | Method | Duration | Easing |
|---|---|---|---|
| Score value change | `ValueAnimator(oldScore, newScore)` | 400ms | Default (AccelerateDecelerate) |
| Risk color change | `ValueAnimator.ofArgb(oldColor, newColor)` | 500ms | Default |
| Detection label appear | `view.animate().alpha(1f)` | 200ms | Default |
| Detection label disappear | `view.animate().alpha(0f)` | 300ms | Default |
| AI status text change | alpha 0 -> set text -> alpha 1 | 150ms | Default |
| Right panel show/hide | `animate().alpha()` | 300ms / 200ms | Default |

### Rules

- No spring animations, no overshoot, no bounce. Automotive UI must feel precise and controlled.
- Animations must not block the UI thread. All use `View.animate()` or `ValueAnimator` which run on the render thread.
- Duration budget: nothing over 500ms. The driver shouldn't wait for animations to finish.
- Animations are progressive enhancement — the UI must be fully functional with all animations removed.

---

## Idle vs. Monitoring States

| Element | Idle | Monitoring |
|---|---|---|
| Left panel | Controls visible, score/streak hidden | Full: score arc, streak, session timer, controls |
| Center | Idle branding overlay ("Honda Smart Cabin") | Camera preview (if enabled) or dark surface, AI status overlay |
| Right panel | Hidden (`visibility="gone"`) | Visible: risk, detections, timer, ticker |
| Footer | Always visible | Always visible |

Transition between states should feel like the dashboard is "waking up" — right panel fades in, score section appears, idle overlay fades out to reveal preview.

---

## Dark-Only Design

There is no light theme. Automotive displays in dark cabins with a bright UI cause driver distraction and eye strain. The entire palette is designed for dark backgrounds. If a light theme is ever needed, it requires a completely separate color set — do not simply invert the current palette.
