# Wallhop Android — stress test audit

Date: 2026-10-19 · Device: Nothing Phone (3a) Pro (A069P), Android 16, 1260×2800, 120 Hz · Debug build, paired and connected to the Mac over Wi-Fi.

## 1. Test plan

Script: `android/scripts/stress_test.py` (adb only, so the test is repeatable).

| # | Scenario | What it does |
|---|----------|--------------|
| 1 | Tab spam | 48 tab taps, 60 ms apart |
| 2 | Droplet drags | 20 fast drags across the tab bar (Mac ↔ Settings) |
| 3 | Scroll flings | 30 fast flings up and down the Activity list |
| 4 | Filter spam | 24 taps across the Activity filters |
| 5 | Menu spam | Open and close the "More" morph menu 15 times |
| 6 | Sheet spam | Open and close the "Text" sheet 15 times |
| 7 | Push/pop | Open App notifications, fling the list, go back (×5) |
| 8 | OTP spam | 20 taps on "Send a test code", count deliveries on the Mac |

Each scenario records the frame-time percentiles (`gfxinfo`), the share of janky frames, slow UI-thread frames, frozen frames and memory (PSS). The run also records crashes and ANRs.

Safety: every tap and swipe first checks that Wallhop is the app in front, and stops the run if it isn't. The script never presses system Back, and never touches "Forget this Mac", permission switches, file pickers or the real clipboard.

## 2. Results

Run 1 is the first run, before fixes. Run 3 is the latest full run, after fixes.

| Scenario | Janky % run1 → run3 | p50 ms | p90 ms | p99 ms run1 → run3 |
|---|---|---|---|---|
| Tab spam | **21.1 → 11.3** | 29 → 22 | 93 → 38 | **150 → 61** |
| Droplet drags | 13.3 → 5.7 | 26 → 26 | 53 → 42 | **150 → 61** |
| Scroll flings | 12.6 → 5.9 | 23 → 25 | 34 → 32 | 48 → 46 |
| Filter spam | **23.6 → 1.6** | 21 → 15 | 25 → 22 | 27 → 32 |
| Menu spam | 4.2 → 5.3 | 25 → 23 | 32 → 34 | 73 → 61 |
| Sheet spam | — → 5.0 | 23 | 30 | 42 |
| Push/pop app list | — → 7.6 | 23 | 31 | 89 |
| OTP spam | — → 7.6 | 25 | 38 | 73 |

Run 1 has no numbers for scenarios 6 to 8 because the run was invalid (see issue A1).

| Health | Run 3 |
|---|---|
| Crashes / ANRs / frozen frames | **0 / 0 / 0** |
| OTP spam | 20 taps → 14 sent → **14 delivered, 0 failed** |
| Memory (PSS) | start 162 MB · peak 302 MB · end 265 MB |
| Cold start | 0.7–1.25 s |
| Link | stayed connected through doze, reinstall and Wi-Fi changes |

Idle, with the app open and the phone resting:

| | Before | After |
|---|---|---|
| Frames drawn | 415 in 5 s (constant redraw) | **1 in 10 s** |
| App CPU | ~126 % | ~23 % (the link service and the system) |

## 3. Issues found and fixed

| # | Severity | Issue | Fix |
|---|---|---|---|
| F1 | **Critical** | The Activity tab crashed whenever it had items. `DateUtils.formatDateTime` was given a null context. | The context is now passed through (`ActivityTab.kt`). |
| F2 | High | The app redrew at full frame rate while idle, which costs battery. The causes were the spinner, the progress bar and the status dot animating in composition; HopDots looping forever; and the tilt sensor updating every frame. | Animations now run in the draw phase. HopDots hops 3 times, then stops. The tilt sensor runs only while the app is resumed, updates at most 10 times a second and only for changes of 1° or more. |
| F3 | High | Switching tabs rebuilt the whole tab, giving p99 frames of 150 ms. | Visited tabs stay alive, and hidden tabs aren't drawn. |
| F4 | Medium | The tab bar used its own nested glass stage, so every frame recorded one extra layer. | The tab bar moved to the root stage. |
| F5 | Medium | The bottom scroll edge blurred on every frame. | It is now a gradient only, with no blur. |
| F6 | Low | The tab bar minimized on drags even when the content couldn't scroll. | It now minimizes only when content actually scrolled. |
| A1 | Test incident | Run 1's menu scenario pressed system Back once too many, left Wallhop, and its taps landed on the home screen. | The script now checks Wallhop is in front before every input and never presses Back. |

## 4. Remaining issues (prioritised)

| # | Priority | Issue | Evidence | Proposed fix |
|---|---|---|---|---|
| R1 | **P1** | Median frames are 22–26 ms against an 8 ms budget at 120 Hz. Motion runs at about 40–45 fps instead of 120. | p50 in every scenario; slow UI-thread counts of 50–115 | Glass `drawBackdrop` is recorded on the main thread for every glass surface on every frame. Planned changes: <ul><li>Cache the blurred backdrop while nothing behind it moves.</li><li>Drop the lens and chromatic layers while a surface is animating.</li><li>Use fewer glass surfaces per screen (list cards solid, glass only on the navigation layer, which is also Apple's rule).</li><li>Profile with a Perfetto trace.</li></ul> |
| R2 | **P1** | Memory grows from 162 MB to 302 MB under use and doesn't fully settle (265 MB). An earlier build peaked at 429 MB. | PSS per scenario: native 116 MB, graphics 54 MB | Changes: <ul><li>Cap the app-icon cache with an LRU at a fixed icon size.</li><li>Release the App notifications screen's icons when it closes.</li><li>Trim the backdrop layers on `onTrimMemory`.</li><li>Check for leaks with LeakCanary.</li></ul> |
| R3 | P2 | 6 of 20 rapid "Send a test code" taps did nothing. | OTP spam: 14 sent of 20 | The row was probably still coasting after the flings, so taps stopped the scroll instead. Re-test with a pause; if it still happens, check the debounce. No messages were lost: all 14 sends arrived. |
| R4 | P2 | Tab spam is still 11 % janky, with a p99 of 61 ms. | Scenario 1 | The droplet and the tab fade both animate glass. This gets better with R1. |
| R5 | P2 | Push/pop has a p99 of 89 ms. | Scenario 7 | Load app icons off the main thread before the slide-in, and show placeholders. |
| R6 | P3 | Glass doesn't flip between light and dark over light or dark content (Liquid Glass property 12). | Design review | Sample the backdrop's average luminance and pick the glass tint from it. |
| R7 | P3 | Activity history is kept in memory only and is lost when the app process is killed. | Code review | Persist it with Room or a small JSON file. |
| R8 | Info | The idle fixes (F2 HopDots, F6, tilt rate) were measured idle but not under a full stress re-run, because the phone locked. | — | Run the script again with the phone unlocked. |

## 5. Launch readiness

- **Stability: ready.** There are no crashes, ANRs or frozen frames under abuse, and every code that was sent was delivered.
- **Battery: ready.** The idle redraw is gone.
- **Smoothness: not yet at Apple level.** The app works and holds a steady 40–45 fps, but a 120 Hz phone should feel faster. R1 and R2 should be done before the public APK. R3 to R5 can ship as follow-ups.

To re-run:

```
python3 scripts/stress_test.py --device <serial> --out <folder>
```
