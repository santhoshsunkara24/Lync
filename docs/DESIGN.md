# Wallhop Android: design standard

> **Current direction (October 2026): Material 3 Expressive, Material You colour.** This
> replaces the Liquid Glass direction below. Where the sections below talk about glass, the
> rules in this box win; the older sections stay as reference for spacing, motion and haptics.
>
> - **Colour:** the phone's Material You palette (`system_accent1/3`), turned into
>   `HomeColors` (`ui/design/HomeColors.kt`): `top` → `mid` → ink gradient behind every screen,
>   `card` for tinted surfaces, `pale`/`onPale` for main buttons, `peek` (tertiary) for the one
>   contrasting card. The app is always dark; text is white at 100 / 68 / 40 %.
> - **Type:** Figtree. Titles are calm medium weights (largeTitle 30 sp / 500, title2 22 sp /
>   500); semibold only for headlines, labels and buttons. Small labels above titles are 12 sp semibold in sentence case: no all-caps text anywhere, on the phone or the Mac.
> - **Shapes and surfaces:** no glass, no blur. Cards 28 dp radius; grouped lists on white 7 %
>   at 24 dp; chips and buttons are full capsules. Main action: pale pill. Secondary: white
>   13 % pill. Top actions: 48 dp white 14 % circles.
> - **Navigation:** floating dark pill (`#262628`), icons only, light pill slides to the
>   selected tab.
> - **Filters:** chips in a row with a pale pill that follows the swipe (`SegmentedControl`).
> - **Switches:** Material 3 style: pale track and dark thumb when on, outlined when off.
> - **Reference:** Google's podcast app screens the user shared (green home, purple player,
>   red show page): colour from content, near-black lower half, pale pills, see-through chips.

Status: draft 2 · October 2026 · applies to `android/app/src/main/java/app/wallhop/ui` and `share/`.

---

## 1. Principles

1. **Content first, controls float.** Content fills the screen edge to edge. Navigation and
   controls sit on a separate floating layer made of glass. Glass is never used for content
   itself (cards of data, list rows, message bubbles).
2. **One layer of glass.** Glass never stacks on glass. A sheet is glass; the buttons inside a
   sheet are not glass, they're tinted or plain.
3. **Concentric shapes.** Corners nest: an inner element's radius = outer radius − the gap
   between them. Floating controls near screen corners follow the display's corner radius.
4. **Calm motion, physical response.** Everything moves on springs. Things respond the moment
   a finger touches them (press scale, glass highlight), not when it lifts.
5. **Clarity over effect.** Every glass surface has a solid fallback and must stay readable
   over any background, in light and dark, with Reduce Transparency on.
6. **Android where it matters.** Keep the system back gesture, predictive back, Android share
   sheet, notification and permission flows. Don't fake an iPhone status bar or home indicator.

## 2. Platform and legal constraints

| Topic | Rule |
|---|---|
| Fonts | **SF Pro can't be used.** Apple's licence limits it to Apple platforms. Use **Figtree** (SIL OFL): clean, warm and readable at small sizes. Bundle the `Figtree` variable font (62 KB) in `res/font/`. Tracking is half of SF's, since Figtree is already tightly spaced. |
| Icons | **SF Symbols can't be used** outside Apple platforms. Use Compose **Material Symbols Rounded** (`Icons.Rounded.*`) at weight 400, which reads closest to SF Symbols "regular". Never mix Rounded with Outlined/Filled sets. |
| Apple branding | No Apple logos, glyphs or wording like "iOS style" in the UI. |
| Glass rendering | Real backdrop effects need Android 12+ (blur) and Android 13+ (lens refraction). Android 10–11 get the solid fallback in §4.4. |
| Libraries | `io.github.kyant0:backdrop` (Liquid Glass lens, blur, vibrancy, highlight), `io.github.kyant0:capsule` (continuous "squircle" corners). Both Apache-2.0. Pin exact versions. |

## 3. Foundations

### 3.1 Layout

- Base unit **4 dp**. Spacing steps: 4, 8, 12, 16, 20, 24, 32, 40.
- Screen side margin: **16 dp** (phones < 400 dp wide), **20 dp** otherwise. Inset grouped lists
  use the same margin.
- Minimum touch target **44 × 44 dp** (Apple) — larger than Android's 48 is not required, but
  never smaller than 44.
- Edge-to-edge always. Content scrolls under the status bar and under the floating tab bar;
  the scroll edge effect (§4.5) keeps text readable there.
- Bottom content padding = tab bar height + 16 dp + navigation bar inset, so the last row can
  scroll clear of the floating bar.

### 3.2 Shapes and corners

All rounded rectangles use **continuous corners** (capsule library `ContinuousRoundedRectangle`),
never circular-arc `RoundedCornerShape`. Capsules use `ContinuousCapsule`.

| Token | Radius | Used for |
|---|---|---|
| `radius.xs` | 6 dp | Badges, small chips |
| `radius.s` | 10 dp | Thumbnails, app icons in lists (icons: 22.5% of size) |
| `radius.m` | 14 dp | Text fields, small cards |
| `radius.l` | 22 dp | Inset grouped list sections, cards |
| `radius.xl` | 28 dp | Hero card, alerts |
| `radius.sheet` | 34 dp | Sheets (top corners); matches typical phone display corners |
| `capsule` | height / 2 | Buttons, tab bar, search field, segmented control, toasts |

Concentric rule: a control inside a card with padding *p* uses radius `card − p`, clamped to ≥ 6 dp.

### 3.3 Colour

System palette, mapped from Apple's semantic colours. All colours are tokens in `Theme.kt`;
no literal colours in components.

| Token | Light | Dark | Use |
|---|---|---|---|
| `background` | `#F2F2F7` | `#000000` | Screen behind grouped lists |
| `backgroundElevated` | `#FFFFFF` | `#1C1C1E` | List sections, cards |
| `backgroundElevated2` | `#F2F2F7` | `#2C2C2E` | Fields and wells inside cards |
| `label` | `#000000` | `#FFFFFF` | Primary text |
| `labelSecondary` | `#3C3C43` @ 60% | `#EBEBF5` @ 60% | Subtitles, footers |
| `labelTertiary` | `#3C3C43` @ 30% | `#EBEBF5` @ 30% | Placeholders, disabled |
| `separator` | `#3C3C43` @ 29% | `#545458` @ 60% | Row dividers (0.5 dp, inset 16 dp + icon) |
| `fill` | `#787880` @ 20% | `#787880` @ 36% | Switch track off, segmented background |
| `tint` (accent) | `#007AFF` | `#0A84FF` | Links, prominent buttons, selection |
| `green` | `#34C759` | `#30D158` | Connected, success, switch on, answer call |
| `red` | `#FF3B30` | `#FF453A` | Destructive, decline call, errors |
| `orange` | `#FF9500` | `#FF9F0A` | Warnings |
| `indigo` | `#5856D6` | `#5E5CE6` | Settings icon tiles |

Settings-style icon tiles use solid colour squares (29 dp, radius 7 dp, white glyph), one colour
per feature: Codes = green, Calls = green, Texts = green, Notifications = red, Background = orange,
Files = blue, Mac = gray `#8E8E93`.

### 3.4 Typography (Figtree, mapped to Apple's text styles)

| Style | Size / line | Weight | Tracking | Use |
|---|---|---|---|---|
| `largeTitle` | 34 / 41 | Bold 700 | +0.4 | Screen titles at rest |
| `title1` | 28 / 34 | Bold | +0.38 | Pairing code headings |
| `title2` | 22 / 28 | Bold | −0.26 | Hero Mac name |
| `title3` | 20 / 25 | Semibold 600 | −0.45 | Sheet titles |
| `headline` | 17 / 22 | Semibold | −0.43 | Row titles that need emphasis, buttons |
| `body` | 17 / 22 | Regular 400 | −0.43 | Row titles, body text |
| `callout` | 16 / 21 | Regular | −0.31 | Secondary body |
| `subheadline` | 15 / 20 | Regular | −0.23 | Row subtitles |
| `footnote` | 13 / 18 | Regular | −0.08 | Section footers |
| `caption1` | 12 / 16 | Regular | 0 | Timestamps |
| `caption2` | 11 / 13 | Regular | +0.06 | Tab bar labels |
| `code` | 48 / 52 | Semibold, tabular | +2 | OTP and pairing codes (Figtree) |

- Section headers: `footnote`, uppercase off (iOS 26 uses sentence case), `labelSecondary`.
- Honour the system font scale up to 200%; layouts reflow, nothing truncates mid-word.
- **Doto (dot-matrix) is retired** from the default theme. It returns only in the optional
  Nothing theme (§9).

### 3.5 Iconography

- Material Symbols Rounded, 400 weight. Sizes: 17 dp inline with body text, 22 dp in tab bar,
  20 dp in buttons, 15 dp in tiles.
- Icons inherit text colour; tinted only when they mean something (green = connected).

## 4. Materials (Liquid Glass)

### 4.0 What Liquid Glass is (study)

Distilled from Apple's WWDC25 session "Meet Liquid Glass" and the iOS 26 announcement.
These are the behaviours that make it read as Liquid Glass rather than frosted plastic:

| # | Property | What it means for us |
|---|---|---|
| 1 | **Lensing, not frosting** | Light bends and concentrates at the edges; the middle stays fairly clear. Blur is light; refraction does the work. |
| 2 | **Materialization** | Glass never fades in. It appears by ramping refraction, highlight and tint from 0 up to full, and disappears in reverse. |
| 3 | **Fluidity and morphing** | Controls morph between states. A menu grows out of the button that opened it, like a bubble popping open. |
| 4 | **Gel-like flex** | On touch, glass instantly swells a little (not shrinks) with a springy, under-damped bounce. |
| 5 | **Illumination on touch** | A glow lights up from the finger and spreads through the element, and onto glass right next to it. |
| 6 | **Specular highlights** | Light sources rim the shape; the highlight follows the geometry and shifts slightly with device tilt. |
| 7 | **Adaptive shadows** | Darker shadow over busy content (text), lighter over plain backgrounds. |
| 8 | **Light/dark flip** | Small glass (tab bar, buttons) flips light/dark with what's behind it; big glass (menus, sheets) adapts but doesn't flip. |
| 9 | **Thickness by size** | Bigger glass looks thicker: more refraction, deeper and softer shadow, softer light scattering. |
| 10 | **Regular vs Clear** | Regular adapts and works everywhere. Clear is always more transparent and needs a dimming layer; only over bold media. Never mix them. |
| 11 | **Tint sparingly** | Tint only the primary action. Colour belongs in the content layer. |
| 12 | **Scroll edge effect** | Content dissolves into the background as it scrolls under glass; over dark content it dims instead. |
| 13 | **Navigation layer only** | Glass is for controls floating above content. Lists and cards stay content. Never glass on glass: things on glass use fills and vibrancy. |

### 4.1 Layers

| Layer | Contents | Material |
|---|---|---|
| Content | Lists, cards, messages, empty states | Opaque (`background`, `backgroundElevated`) |
| Controls | Tab bar, top bar buttons, floating actions | **Glass** |
| Overlays | Sheets, menus, alerts, toasts | **Glass** (sheets turn opaque at full height) |

### 4.2 The glass recipe

All parameters scale with the element's **thickness** `t = clamp(minDimension / 120 dp, 0.35, 1)`
(property 9), so a 44 dp button is thin and a sheet is thick.

| Parameter | Regular | Clear |
|---|---|---|
| Vibrancy (saturation ×1.5) | on | on |
| Blur | `2 + 6·t` dp | `1 + 2·t` dp |
| Lens refraction height | `min(cornerRadius, 10 + 14·t)` dp | same |
| Lens refraction amount | `(24 + 40·t)` dp | `(28 + 44·t)` dp |
| Chromatic aberration | when `t > 0.7` | when `t > 0.7` |
| Surface tint (light / dark) | white 14% / black 18% | white 4% / black 10% |
| Highlight | Default style, alpha `0.45 + 0.25·t`, angle = 45° + device tilt | alpha 0.7 |
| Shadow | radius `12 + 20·t` dp, y `radius/5`, black `8 + 10·t` % (light), `30 + 20·t` % (dark) | same |
| Tinted (primary action) | Regular + accent at 70% surface | — |

**Touch (properties 4, 5):** on press the element scales to **1.04** (small) – **1.02** (large) on
`spring(damping 0.55, stiffness 600)` — a gel bounce; refraction amount +30%; highlight alpha +0.35;
and a radial glow (white, additive, 35% → 0) centred on the finger with radius = 1.2 × the element's
diagonal, which follows the finger while pressed and fades over 300 ms after release.

**Device motion (property 6):** a game-rotation sensor tilts the highlight angle by up to ±20°,
smoothed (low-pass 0.1). Off with Reduce Motion.

**Materialization (property 2):** every glass element has an `appear` value 0→1 driven by its
enter/exit transition. Refraction, blur, highlight, shadow and tint all multiply by `appear`;
content on the glass fades in from `appear > 0.5`. Enter `spring(0.8, 300)`; exit `spring(1.0, 500)`.

### 4.3 Where glass is allowed

Tab bar and its selection droplet · top bar buttons and title capsule · floating actions · sheets ·
menus · alerts · toasts · the scroll edge strip. **Not** on list rows, cards, message bubbles or
the screen background. Elements placed *on* glass (sheet rows, menu items) use fills and vibrancy.

### 4.4 Fallbacks

| Condition | Rendering |
|---|---|
| Android 12 | Blur + tint + highlight, no lens |
| Android 10–11 | Solid `backgroundElevated @ 94%` + 0.5 dp separator stroke + shadow |
| Battery saver or high-contrast text | Same as Android 10–11 |

### 4.5 Scroll edge effect

Under the top bar: a strip `status bar + 72 dp` tall that samples the content with a 10 dp blur,
masked by a vertical gradient (opaque at the top, transparent at the bottom) — content dissolves
progressively — plus a `background` colour fade at 70% → 0 over the same height. Only visible once
content has scrolled under it. The bottom (behind the tab bar) gets the same, mirrored, at half height.

## 5. Components

Each component lists anatomy, sizes, states, interaction, motion and implementation notes.
All components live in `ui/components/` and take colours/typography from tokens only.

### 5.1 Large title scaffold

- **Anatomy:** large title (`largeTitle`, left aligned at margin, 52 dp below status bar) →
  content. On scroll the title collapses into an inline title (`headline`, centred) inside a
  glass capsule at the top.
- **Behaviour:** title scrolls with content for the first 52 dp; past that the inline title
  fades in (opacity 0→1 over 12 dp of scroll) and the scroll edge effect turns on. Pulling down
  past the top stretches the large title slightly (scale up to 1.06, anchored left) — the iOS
  rubber band feel.
- **Trailing actions:** up to two glass circle buttons (44 dp) at top right, e.g. "More".
- **Compose:** `LargeTitleScaffold(title, actions, content: LazyListScope.() -> Unit)` using
  `LazyListState` offsets; nested scroll for the stretch.

### 5.2 Floating tab bar

- **Anatomy:** glass capsule, 64 dp tall, inset 20 dp from the sides and 12 dp above the
  navigation bar. 3 tabs: **Mac**, **Activity**, **Settings**. Each tab: icon 22 dp over label
  `caption2`. Selected tab: icon and label in `tint`, plus a lighter glass "lens" pill behind it.
- **Selection at rest:** a soft fill capsule (`label` @ 7%) behind the selected tab.
- **Droplet:** pressing anywhere on the bar lifts the selection into a glass **droplet** that
  refracts the bar's own icons (magnifies what's under it), scaled 1.15 with the gel spring. Dragging
  moves the droplet across tabs, stretching in the direction of travel by velocity; release snaps
  to the nearest tab and the droplet settles back into the rest capsule. A plain tap does the same
  quickly. Selection haptic on each tab crossed.
- **Minimise on scroll:** scrolling down more than 24 dp shrinks the bar to a 48 dp capsule
  showing only the selected tab icon, anchored left; scrolling up or reaching the top expands
  it. Transition `spring.smooth`.
- **Press:** tab icon scales to 0.88 on touch down, back on release; selection haptic.
- **Accessibility:** each tab is a `Role.Tab` with a label; selected state announced.

### 5.3 Buttons

| Style | Look | Use |
|---|---|---|
| `prominent` | `glass.tinted` capsule, white `headline` label, 50 dp tall | One primary action per screen (Pair, Send) |
| `glass` | `glass.regular` capsule, `label` text, 50 dp | Secondary actions on floating layers |
| `filled` | `tint @ 15%` fill, `tint` label, 50 dp | Secondary actions inside content |
| `plain` | Text only in `tint`, `body` | Inline actions, "Enter address instead" |
| `destructive` | Any of the above with `red` | Forget Mac, Decline |
| `circle` | 44 dp circle, glass, 20 dp icon | Top bar actions, close |

- **States:** glass buttons follow the touch rules in §4.2 (swell, glow from the finger). Filled and
  plain buttons, which live in content, scale to 0.97 and dim the label to 70%. Disabled = 35%
  opacity, no shadow. Loading = label cross-fades to the activity indicator, width fixed.
- **Motion:** gel spring for glass, `spring.press` / `spring.bouncy` for content buttons.
- **Haptic:** light impact on release for prominent buttons only.

### 5.4 Inset grouped list

- **Section:** `backgroundElevated`, `radius.l`, side margin 16/20 dp, 35 dp between sections.
- **Header:** `footnote` in `labelSecondary`, 16 dp from the section's left edge, 7 dp above.
- **Footer:** `footnote` `labelSecondary`, 7 dp below, explains the section.
- **Dividers:** 0.5 dp `separator`, inset to the text start (not under icon tiles), none after
  the last row.

### 5.5 List row

- **Height:** 44 dp minimum single line; 60 dp with subtitle. Padding 16 dp horizontal.
- **Variants:** title · title + subtitle · title + value (right, `labelSecondary`) ·
  title + switch · title + chevron (navigation) · title + button (action).
- **Leading:** optional icon tile (29 dp, §3.3) or app icon (29 dp, radius 22.5%) or avatar.
- **Press:** row background animates to `fill @ 50%` immediately on touch down (no ripple), fades
  out over 250 ms on release. Ripples are not used anywhere.
- **Chevron:** `Icons.Rounded.ChevronRight`, 14 dp, `labelTertiary`.
- **Swipe actions** (Activity list): swipe left reveals full-height colour buttons (Delete = red)
  that stretch as you pull; past 60% width the action commits with a medium haptic.

### 5.6 Switch (toggle)

- 51 × 31 dp capsule track; 27 dp thumb with soft shadow.
- Off: track `fill`; on: track `green`. Thumb slides on `spring.snappy`; on touch down the thumb
  widens to 33 dp (stretch) and returns on release.
- Whole row toggles it; selection haptic on change.

### 5.7 Segmented control

- Capsule, 32 dp, `fill` background; selected segment is a raised glass capsule that slides with
  the same liquid stretch as the tab bar.

### 5.8 Text field and search field

- **Text field:** 44 dp min, `backgroundElevated2`, `radius.m`, 16 dp padding, `body`, placeholder
  `labelTertiary`. Focus: 2 dp `tint` ring fades in over 150 ms. Clear button (circle-x) appears
  when not empty.
- **Search field:** capsule, 36 dp, magnifier icon, placeholder "Search". Cancel text button slides
  in from the right on focus (`spring.smooth`).
- **Multiline composer** (send text): grows to 6 lines, then scrolls.

### 5.9 Cards

- `backgroundElevated`, `radius.l` (hero `radius.xl`), no border in light mode; 0.5 dp `separator`
  border in dark mode. No shadows on cards (shadows are for floating glass only).
- **Hero connection card** (§6.3) is the only card with an illustration.

### 5.10 Sheets

- **Detents:** `medium` (≈ 50% height) and `large` (full minus 10 dp top gap). Grabber: 36 × 5 dp
  capsule, `labelTertiary`, 5 dp from top.
- **Material:** `glass.regular`, inset 8 dp from the screen sides and bottom with `radius.sheet`
  corners at medium height (floating sheet); at large it attaches to the edges and becomes
  opaque `backgroundElevated` (iOS 26 behaviour).
- **Header:** optional title `title3` centred; leading "Cancel" (`plain`), trailing primary
  action (`plain` semibold, or `circle` checkmark in `tint`).
- **Background:** content behind scales to 0.94 and dims to 40% black at large detent.
- **Motion:** present/dismiss on `spring.smooth`; drag follows the finger 1:1 and settles with
  velocity; dismiss past 25% drag or fast flick.
- **Predictive back:** system back gesture drags the sheet down progressively.

### 5.11 Alerts and confirmation

- **Alert:** centred, 270 dp wide, `glass.regular`, `radius.xl`; title `headline`, message
  `footnote`; buttons stacked as full-width capsules (iOS 26) with the default action in
  `prominent`. Background dims 20%.
- **Confirmation (destructive)** e.g. Forget Mac: bottom action sheet of capsule buttons:
  destructive (red) + Cancel (bold).
- **Motion:** alert scales from 1.1 → 1.0 with opacity on `spring.bouncy`.

### 5.12 Menus and context menus

- **Button menus** (e.g. "More" in the top bar) are a single glass shape that **morphs out of the
  button**: width, height and corner radius interpolate from the 44 dp circle to the menu
  (250 dp wide, `radius.l`) on `spring(0.75, 350)`, anchored at the button's corner. Items fade in
  after 40% of the morph. Closing reverses into the button.

### 5.12b Context menu (long press)

- Long press (400 ms) on an Activity row or app row: the row lifts (scale 1.03, shadow) with a
  medium haptic, background blurs, and a glass menu appears anchored below/above it.
- Menu: `glass.regular`, `radius.l`, 250 dp wide; items 44 dp with trailing icons; destructive
  items red; separators between groups.

### 5.13 Progress and activity

- **Activity indicator:** iOS-style 8-spoke spinner (spokes fade in sequence, 1 s period), 20 dp.
  Replaces Material circular progress everywhere.
- **Progress bar:** 4 dp capsule, track `fill`, bar `tint`, animated width on `spring.smooth`.
- **Progress ring:** 22 dp, 3 dp stroke, for transfer status in compact spots.

### 5.14 Toast banner

- Glass capsule dropping from the top (like the Dynamic Island area), 44–56 dp tall, icon + one line.
- Shows 2.5 s; swipe up dismisses. Success = `green` icon, failure = `red`.
- Replaces Material snackbars.

### 5.15 Empty states

- Centred icon (40 dp, `labelTertiary`), `headline` title, `subheadline` explanation, optional
  `filled` button. No illustrations or emoji.

### 5.16 Code display (pairing, OTP)

- Digits in `code` style, grouped 3 + 3 with a 12 dp gap, on a `glass.regular` capsule.
- On appear: digits count in from 0 with a slot-machine roll (each digit staggered 40 ms,
  `spring.bouncy`). Respect Reduce Motion (plain fade).

### 5.17 Thumbnails and app icons

- Thumbnails `radius.s`, 0.5 dp `separator` border. Stack of up to 3 rotated −6°, 0°, +6° with
  4 dp offsets. App icons 29/40/60 dp with radius 22.5% continuous.

## 6. Screens

### 6.1 Information architecture

```
Not paired:   Welcome → Connect (find Mac) → Pairing code → Done (sheet) → Mac tab
Paired:       Tab bar: Mac | Activity | Settings
Sheets:       Send text · Enter address · App notifications (from Settings, pushed) · Forget Mac
External:     Share sheet target "Send to Mac" (glass sheet, §6.8) · Quick Settings tile
```

### 6.2 Welcome and connect (not paired)

- Full-bleed `background`. Large title "Connect your Mac". Subtitle `callout` `labelSecondary`.
- Animated illustration: phone and laptop glyphs with dots hopping between them (existing
  HopDots, restyled: 6 dp dots, `label` colour, travel on `spring.smooth`).
- Section "Macs on this Wi-Fi": inset grouped list; each Mac row = gray laptop tile + name +
  IP subtitle + chevron. While searching: activity indicator in the section header.
- Footer link: `plain` "Enter the Mac's address instead" → Enter address sheet (medium).
- Errors appear as an inline row with red icon, not a separate card.

### 6.3 Pairing code

- Centred content; `title1` "Pairing code"; code capsule (§5.16); `body` instruction; small
  activity indicator with "Waiting for your Mac…". Bottom `glass` button "Cancel".
- On success: code capsule turns `green`-tinted glass, checkmark draws in (stroke animation
  300 ms), success haptic, then the screen morphs into the Mac tab.

### 6.4 Mac tab

- Large title: Mac name (e.g. "Santhosh's MacBook Air"); trailing circle button "More" (menu:
  Forget Mac, About).
- **Hero card** (`radius.xl`): phone glyph · hop dots · laptop glyph; status row
  ("Connected" with green dot, or "Looking for your Mac…" with pulsing gray dot) and IP in
  `footnote`. Tapping it shows connection details in a medium sheet.
- **Transfer card** (when active): thumbnail stack, title, progress bar, Cancel `plain`.
- **Send to Mac:** 2 × 2 grid of action tiles (Photos, Files, Text, Clipboard): `backgroundElevated`
  squares `radius.l`, icon 24 dp in `tint`, label `headline`. Press = scale 0.96.
- **Floating Send button:** none on this tab (tiles are the actions).

### 6.5 Activity tab

- Large title "Activity". Segmented control: All · Codes · Files · Calls.
- Inset grouped list grouped by day ("Today", "Yesterday"). Rows: leading tile by type, title,
  subtitle, trailing time `caption1`. Codes show digits in `headline` tabular.
- Swipe left to delete; long press for context menu (Copy, Delete).
- Empty state per filter.

### 6.6 Settings tab

Inset grouped sections:

1. **On your Mac:** App notifications (chevron, value "3 apps") · Codes from messages (switch
   reflects permission; off → requests) · Calls (switch) · Texts (switch).
2. **Stay connected:** Run in background (value "Allowed"/"Off" → system settings) ·
   Notification access (value).
3. **Mac:** connected Mac name (value) · Forget this Mac (red, opens confirmation).
4. **About:** Version · Open-source licences · Privacy ("Nothing leaves your devices").

Permission rows show a red badge dot on the Settings tab icon while something essential is off.

### 6.7 App notifications (pushed screen)

- Large title "App notifications", search field under the title (moves into the inline bar on
  scroll), sections: On · Recently active · All apps. Rows: 29 dp app icon, name, switch.
- Back: chevron + "Settings" in a glass capsule at top left; system back works.

### 6.8 Share sheet ("Send to Mac")

- Medium-detent glass sheet over the sharing app. Header: Mac name in `title3`, "Cancel" plain.
- Body: thumbnail stack (or link/text preview), summary line, progress bar.
- Success: progress bar morphs into a green glass capsule "Added to Mac" with checkmark draw,
  success haptic, auto-dismiss after 1.2 s.
- Failure: inline red text + `filled` "Try again".

### 6.9 Send text sheet

- Large-detent sheet: title "Send text", Cancel / Send (Send disabled until text). Multiline
  composer fills the sheet; keyboard opens immediately.

## 7. Motion

### 7.1 Spring tokens (Compose `spring(dampingRatio, stiffness)`)

| Token | SwiftUI equivalent | Damping | Stiffness | Use |
|---|---|---|---|---|
| `spring.press` | — | 1.0 | 1500 | Touch-down scale |
| `spring.snappy` | `.snappy` (0.35 s, bounce 0.15) | 0.85 | 400 | Switches, tab selection, segmented |
| `spring.smooth` | `.smooth` (0.5 s) | 1.0 | 160 | Sheets, bar minimise, layout changes |
| `spring.bouncy` | `.bouncy` (0.5 s, bounce 0.3) | 0.7 | 250 | Release, alerts, code digits, success |

No `tween` easing for anything the user touches. Fades alone may use 200 ms ease-out.

### 7.2 Patterns

| Moment | Motion |
|---|---|
| Screen push | New screen slides from right 100% → 0 while the old one moves −30% and dims 10%; `spring.smooth`. Predictive back scrubs it. |
| Tab switch | Cross-fade 150 ms + 8 dp upward drift of the new content; no slide. |
| List insert | New row expands height from 0 and fades in; others shift on `spring.smooth`. |
| Glass press | Scale 0.96, highlight up, slight lens strength increase (refraction +20%). |
| Glass morph | Two glass shapes closer than 12 dp merge into one blob (e.g. tab pill leaving one tab and arriving at another). |
| Connected | Hop dots accelerate, status dot turns green with a 1.4× pulse once, light haptic. |
| Success | Checkmark stroke draw 300 ms + `spring.bouncy` scale 0.8 → 1. |

### 7.3 Reduce Motion

When the system "Remove animations" is on: springs become 150 ms fades, no stretch/morph, no
parallax, no rolling digits, hop dots static.

## 8. Haptics

| Event | Android constant |
|---|---|
| Tab change, switch, segmented | `HapticFeedbackConstants.CLOCK_TICK` (selection) |
| Prominent button release | `VIRTUAL_KEY` (light) |
| Long press lift, swipe commit | `LONG_PRESS` (medium) |
| Success (paired, sent) | `CONFIRM` (API 30+) |
| Failure | `REJECT` (API 30+) |

Never more than one haptic per gesture.

## 9. Nothing theme (optional)

A setting "Nothing style" swaps: Doto for large titles and codes, monochrome tint (white/black),
red `#D71921` live accent, dot-matrix hop dots. Glass, layout and motion stay identical.

## 10. Accessibility

- Contrast ≥ 4.5:1 for text on glass in both themes; the adaptive tint (§4.2) guarantees it,
  and the fallback (§4.4) kicks in if luminance sampling can't run.
- Every interactive element ≥ 44 dp, has a content description and a role.
- Font scale to 200%: rows grow, grids drop to 1 column above 150%.
- TalkBack order follows visual order; the floating tab bar is read after content.

## 11. Performance

- At most **4 live glass surfaces** on screen; tab bar backdrop is shared by its children
  (one `rememberLayerBackdrop` per screen, combined backdrops for tab items).
- Glass that doesn't move (sheet at rest) can cache its backdrop; moving glass re-renders.
- Target 60 fps on mid-range phones (Pixel 6a class). Profile with the Compose layout inspector
  and `adb shell dumpsys gfxinfo`. If a frame budget is missed, drop `lens` first, then blur.

---

## 12. Implementation plan

### Phase 1 · Foundations (tokens and primitives)
1. Add dependencies: `io.github.kyant0:backdrop:2.0.1`, `io.github.kyant0:capsule:2.1.3`;
   bundle the Figtree variable font (OFL) in `res/font/`.
2. Rewrite `ui/Theme.kt` → `ui/design/Tokens.kt`: colour tokens (§3.3), type scale (§3.4),
   radii (§3.2), spacing, spring tokens (§7.1), haptics helper (§8).
3. `ui/design/Glass.kt`: `Modifier.glass(variant, shape, backdrop)` wrapping Backdrop's
   `drawBackdrop` with the recipes in §4.2, the highlight and shadow, adaptive label colour,
   and the fallbacks in §4.4 (API level, Reduce Transparency, battery saver).
4. `ui/design/Press.kt`: `Modifier.pressable()` — scale/highlight on touch down with springs,
   no ripple.
**Done when:** a sample screen shows each glass variant over a photo in light and dark on the
Nothing phone (Android 16) and the emulator at API 30 (fallback).

### Phase 2 · Components (§5)
Build in `ui/components/`: LargeTitleScaffold, FloatingTabBar, Buttons, InsetGroupedList +
ListRow, Switch, SegmentedControl, TextField/SearchField, Card, GlassSheet (detents, grabber,
predictive back), Alert/ActionSheet, ContextMenu, ActivityIndicator, Progress, ToastBanner,
EmptyState, CodeDisplay, Thumbnail/AppIcon.
**Done when:** a debug-only "Components" screen shows every component in every state.

### Phase 3 · Screens (§6)
1. Navigation: tab host (Mac / Activity / Settings) + pushed App notifications screen +
   sheets. Keep `MainActivity` single-activity.
2. Rebuild: Connect, Pairing, Mac tab, Activity tab (with filters, swipe, context menu),
   Settings tab (permissions become rows), App notifications, Send text sheet, Enter address
   sheet, Forget confirmation.
3. Rebuild the share sheet (`ShareActivity`) on GlassSheet.
4. Replace snackbars with ToastBanner; remove Material ripples and circular progress.
**Done when:** every screen matches §6 on the real phone, screenshots reviewed in both themes.

### Phase 4 · Motion and haptics polish (§7, §8)
Tab bar liquid morph and minimise-on-scroll, large title collapse/stretch, code digit roll,
success checkmark, list insert/remove, context menu lift, Reduce Motion paths.

### Phase 5 · Accessibility and performance (§10, §11)
TalkBack pass, 200% font pass, contrast check on glass over light/dark/colourful content,
gfxinfo frame stats on the Nothing phone, fallback check on API 30 emulator.

### Phase 6 · Nothing theme (§9)
Setting toggle, Doto font for titles/codes, monochrome tint, red accent.

### Risks
- Backdrop library is young; pin versions, keep the fallback path working at all times.
- Glass over scrolling lists costs GPU time; the 4-surface budget and fallback order protect
  frame rate.
- An iPhone look on Android can feel foreign in system flows (permissions, share sheet chooser);
  those stay system-native by design.
