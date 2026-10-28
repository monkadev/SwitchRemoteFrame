# Switch Remote Frame — UI/UX Design System v2 ("Aurora")

The redesign of every UI in the project — the **Switch manager**, the **Tesla
overlay**, the **Android / Android TV app** and the **PC viewer** — from one
shared design language. Each platform keeps its rendering engine (the engines
are fixed by the platform: SDL2 on the Switch and PC, native Android Views,
libtesla on the overlay); what changes is the *design system* they all render.

## Why these decisions — what modern (2026) UI practice says

- **Elevation is lighter surfaces, not shadows.** Dark interfaces need at least
  four surface rungs, each 3–8 % lighter than the one below; pure black and
  shadow-only depth both fail on dark backgrounds. A 6–8 % white hairline border
  adds separation where the luminance step alone is too subtle.
- **Never pure black, never pure white.** Off-black canvas and off-white text
  avoid halation; every text/background pair is chosen to clear WCAG AA (4.5:1),
  checked against the *lightest* surface the text can appear on.
- **Semantic tokens, not hex values.** Components reference roles
  (`Acc`, `Surf2`, `Dim`…); the four platform implementations map the same role
  names to the same RGB values, so the product reads identically on a phone, a
  TV, a PC and the Switch.
- **Focus is the hero state.** On TV and the Switch there is no pointer: the
  focused element gets an accent ring, a tinted fill and (where the engine
  allows) a slight scale — visible at ten feet. One obvious default focus per
  screen, and every control reachable with a D-pad.
- **One vivid accent.** A single saturated interactive colour for primary
  actions, focus and live status; emerald/amber/rose only for good/warning/bad
  semantics. Muted accent tints carry secondary emphasis.
- **Motion is physical but cheap.** Springs/eased lerps on overlays, toggles
  and focus; nothing that allocates or re-lays-out per frame on the consoles.
- **Streaming-app UX (Moonlight, Steam Link, StreamLight):** the connection
  state is always obvious at a glance, the primary action is one big pill,
  jargon sits in secondary text, and in-stream chrome stays out of the way.

## The tokens

| Role | Hex | Use |
|---|---|---|
| `Bg` | `#0B0D11` | page canvas (never true black) |
| `Surf` | `#14181F` | cards, panels, sidebar |
| `Surf2` | `#1D222C` | raised rows, inputs, tracks |
| `Surf3` | `#282F3C` | hover/active, selected nav, knobs |
| `Surf4` | `#363E4E` | tooltips, toasts, highest elevation |
| `Hair` | `rgba(255,255,255,.06)` | 1 px card/separator hairline |
| `Ink` | `#F2F5FA` | primary text |
| `Dim` | `#ACB5C4` | secondary text (AA on every rung) |
| `Faint` | `#7C8697` | captions, section labels (large/label use only) |
| `Acc` | `#4D8DFF` | primary actions, focus, live accent |
| `AccHot` | `#7AA8FF` | pressed/hover accent |
| `AccSoft` | `rgba(77,141,255,.13)` | tinted fills behind selected/focused rows |
| `OnAcc` | `#070C18` | text on the accent |
| `Good` | `#34CD99` | connected, enabled, success |
| `Warn` | `#F5B73F` | caution, pending restart |
| `Bad` | `#F26175` | errors, stalled, destructive |
| `Heart` | `#E5484D` | the Ko-fi donate action |
| `JoyBlue` / `JoyRed` | `#00B2E6` / `#FF4C3E` | brand mark only |

## Shape, type, motion

- **Radii:** 8 chips/inputs · 14 rows · 20 cards · 26 panels · full pills.
- **Type:** display 34–40, title 26–30, card label 18–22, body 15–16,
  caption 12–13. Section labels are 12 px, bold, letter-spaced, `Faint`.
  Medium/semibold for labels (light text on dark reads heavier).
- **Focus:** 2.5–3 px `Acc` ring + `Surf2`/`AccSoft` fill; Android scales the
  view to 1.03 while focused.
- **Motion:** drawer/scrim fade-and-slide (Android spring, PC/SDL eased alpha),
  animated toggle knobs, pulsing live-status dot, eased scroll that keeps the
  selection on screen.
- **Targets:** no control under 44–48 dp/px; ten-foot pages use large type and
  generous padding; safe margins on TV.

## Per-platform surfaces

- **Switch manager (`manager/`)**: sidebar workspace, card pages, hero status,
  tiles, gallery, progress cards — all drawn from `ui.hpp`'s tokens.
- **PC viewer (`tools/raw-recv/`)**: home connection panel, settings drawer,
  in-stream chrome, HUD, on-screen gamepad and its editor — `ui.h`'s tokens.
- **Android (`android-tv/`)**: home card, settings drawer, gamepad page,
  virtual pad and editor — `Ui.java`'s tokens, with framework animations.
- **Tesla overlay (`overlay/`)**: libtesla's native chrome (itself a modern
  list system) with colour-coded status and the correct shared version string.

## Shipped in 1.1.0

All four surfaces use the Aurora tokens above. The redesign keeps every
settings key, protocol message and the hit-test/input architecture intact —
only the presentation layer was replaced.

- **Manager / overlay / sysmodule** versions move together to 1.1.0; the five
  strings `tools/make_release.sh` checks were updated in lockstep.
- **PC viewer** passed the full local suite (`tools/run_pc_tests.sh`, 25
  checks incl. end-to-end streaming, virtual pad, home, drawer, recording and
  NVENC). A pre-existing drawer bug — scrolled settings rows bleeding into the
  header band — was fixed by adding top content padding while keeping the clip
  origin at the scroll top.
- **Android** builds with the real SDK (`assembleDebug`) and passes the Gradle
  `protocolTest` / `playbackTest` jobs; the ten-foot focus state is a 1.03
  scale lift with an overshoot, layered on the accent ring.
- **Tesla overlay** keeps libtesla chrome; the status line now uses the accent
  while streaming, bright text for states that need attention, and the neutral
  description colour otherwise.

## 1.1.1 patch

- PC viewer: the streaming on-screen gamepad (controls and its Show button)
  no longer paints over the disconnected home screen, and the layout editor /
  gamepad settings page opened from home Settings stay solidly visible — the
  home wait-loop's plain 50 ms menu repaint used to paint over them, which
  read as flicker while scrolling the wheel. Covered by an extended
  `--test-home-overlays` regression that fails on the old behavior.

## 1.1.2 patch

- Android home card: the USB/Network switch spans the full width; Connect and
  Settings share one equal-width row; the Switch IP address field moved from
  the drawer onto the home card under that row (Network mode), with the
  drawer's own field kept and the two synced through the shared preference;
  Donate is now its own left-aligned row at the bottom of the panel.
- PC viewer: the "Type IP" button is gone - the IP field sits directly under
  the "Switch IP address" label on the home connection card and in Settings,
  click to type, Enter to connect. The home panel and settings drawer lost
  their hairline outline (it read as a thin white box at the window edge);
  card borders are now true rounded rings instead of four straight fills that
  left corner brackets. The settings veil is composited once per frame,
  fixing the scroll flicker from double drawing.

## 1.1.3 patch

- PC self-updater:
  - AppImage builds update again. `/proc/self/exe` points into a read-only
    squashfs mount, so the old binary swap could never write; the updater now
    detects `$APPIMAGE`, downloads the new `-linux.AppImage` release asset,
    streams it beside the running file (no 32 MB read-into-memory cap), marks
    it executable and renames it over the original, then the Restart action
    execs the replaced file.
  - Windows "cannot run curl": `CreateProcessW` with a bare `curl` no longer
    depends on the shell PATH. PATH is tried first, then `System32` (which
    always holds `curl.exe` and `tar.exe` on supported Windows), with the
    executable passed explicitly. Linux likewise falls back from `curl` to
    `wget` and searches `/usr/local/bin`, `/usr/bin`, `/bin` when PATH misses.
- PC IP field: the address is white while editing and gray while idle, and
  editing reveals Confirm and Cancel buttons under the box. Confirm does the
  same validation/connect as Enter; Cancel discards the edit. Because key
  events bypass the normal UI routing while typing, the buttons route through
  a dedicated `ui_ip_edit_event` path, covered by the new headless
  `ip_edit_test.py` (`--test-ip-edit` drives the full click/type sequence).
- Android home: Donate moved off the connection controls into the left
  (status/info) column, under the connect status and info texts, left aligned;
  the status ring now shares a top line with the mode switch so the two
  columns start aligned, and the controls column holds only full-width
  connection rows.
- Android settings: "Smooth video" moved from DISPLAY to the bottom of
  CONNECTION, with shorter copy that states the trade-off plainly - ordered
  frames stop bursty-Wi-Fi stutter but add up to two frames of input latency;
  off stays the lowest-latency, jumpier picture. The preference key
  (`smooth_video`) and engine restart on change are unchanged.

## 1.1.5 patch

- Attribution and identity cleanup across every platform:
  - the project's past contributor names and handles were removed from the
    code, comments, READMEs, the Flatpak metadata, the engineering journals and
    the captured log fixtures; monkadev is the sole project copyright holder
    in NOTICE and android-tv/UPSTREAM-NOTICE.
  - every reverse-DNS identifier and the NACP author field now use the
    `monkadev` namespace: the Android `applicationId` is
    `io.github.monkadev.switchremoteframe` (with the updater's install-result
    action), the macOS bundle identifier matches, and so does the Switch
    manager's NACP author.
  - the Flatpak was re-IDed to `io.github.monkadev.SwitchRemoteFrame`, its
    display name is Switch Remote Frame, and it builds from this repo's
    release tag.
  - Third-party license notices are deliberately kept: the vendored NVIDIA,
    AMD/FSR, NIS, DejaVu and stb components, plus the external project
    attributions (Atmosphère, SysDVR, oss-nvjpg and friends) that their
    licenses require. The Android Java package path `io.github.switchframetap`
    and the functional "Switch Frame Tap Camera" USB product string are
    interop names, not credits, and are unchanged.
  - Because the Android applicationId changes, 1.1.5 installs as a new app
    rather than updating an existing install; uninstall the old one first.

## 1.1.4 patch

- Android keyboard no longer covers the IP field: the activity is immersive
  fullscreen, where Android does not resize the window for the on-screen
  keyboard (adjustResize is ignored in fullscreen on most versions), so
  typing the Switch's address used to happen behind the keyboard. A global
  layout watcher now measures the keyboard from the window's visible display
  frame (`ImeInsets`, with a 60 dp floor so a transiently revealed navigation
  bar is not mistaken for a keyboard), applies that height as root bottom
  padding so the home and drawer ScrollViews shrink above the keyboard, and
  scrolls the focused field into view on focus and when the inset changes.
  If the window ever does resize itself, the visible frame fills the root and
  no extra padding is applied - nothing can double-apply. The manifest also
  declares `adjustResize` for non-fullscreen window paths. The decision logic
  is host-tested (`imeInsetTest` / `ImeInsetTest.java`). The PC viewer draws
  its own in-window field and only consumes physical key events, so it has no
  on-screen keyboard that could cover the field and needs no change.
