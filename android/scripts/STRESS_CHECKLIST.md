# Lync stress test checklist

Stress testing pushes the app past normal use to find where it breaks, then checks that it
recovers. Plan → script → run with load rising step by step → analyse → fix → run again.

## Plan

- **Goal:** Lync on the phone and the Mac stays smooth, keeps every code, and recovers on its own
  under heavy use for 15 minutes straight.
- **Setup:** real phone (benchmark build, Lync in front, unlocked, plugged in) and the real Mac app,
  linked on the same Wi‑Fi. Not an emulator.
- **Load rises each round:** the codes burst goes 5 → 10 → 20 a round, on top of fast UI input.
- **Safety:** the script stops the moment another app comes to the front. It never presses
  system Back, never taps Forget this Mac, permission switches or file pickers, and never sends
  the real clipboard.

## What gets checked

| # | Area | How | Pass if |
|---|------|-----|---------|
| 1 | Tab switching spam | 48 taps across Home / Activity / Settings, 60 ms apart | janky < 5 %, no frozen frames |
| 2 | Tab bar drags | 20 fast drags across the bar | janky < 5 %, no frozen frames |
| 3 | List scrolling | 30 hard flings on Activity | janky < 5 %, p95 < 32 ms |
| 4 | Filter spam | Activity filters tapped 24 times | no errors, janky < 5 % |
| 5 | Menu spam | "More" menu opened and closed 15 times | no stuck menu |
| 6 | Sheet spam | Text sheet opened and closed 15 times | no stuck sheet |
| 7 | Page push/pop | Phone notifications on Mac page opened, scrolled and closed 5 times | no errors |
| 8 | Codes to Mac (data integrity) | bursts of test codes, rising each round | delivered = sent, none lost |
| 9 | Phone app restart (recovery) | Lync force-stopped and reopened | linked again in < 10 s |
| 10 | Memory leak | phone memory every scenario, start vs end | growth < 25 % |
| 11 | Mac load | Mac CPU and memory every 30 s | no runaway growth; CPU settles after |
| 12 | Crashes and freezes | phone crash log and ANRs; Mac crash reports | 0 crashes, 0 ANRs |
| 13 | Recovery after load | 60 s idle at the end | Mac CPU back near idle, phone still linked |

## Checked by hand (not automatable safely)

- New ask sheet and blocked sheet: every permission is already granted on the test phone, and the
  script never touches permission switches.
- Wi‑Fi dropping and coming back: changing the phone's Wi‑Fi is a system setting, so it's done
  by hand.
- Mac app restart: the real Mac app isn't quit by the test.
- Files and text from Mac to phone: needs clicks on the Mac.

## Outputs

`results.json` (every scenario, every round), first-round screenshots, `mac.log` (Mac CPU and
memory) and a short report: what passed, what didn't, and what to fix.
