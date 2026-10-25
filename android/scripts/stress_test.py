#!/usr/bin/env python3
"""
Wallhop UI stress test. Hammers the app through adb and records frame times, crashes,
ANRs, memory and delivery counts for each scenario.

    python3 scripts/stress_test.py --device <serial> --out <folder> [--minutes 15]

Needs a debug build installed, the phone unlocked with Wallhop paired and connected.
Never taps anything destructive: no "Forget this Mac", no permission switches, no file
pickers, and no sending the real clipboard ("Send a test code" is used instead).
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

# Test codes per round: each one shows on the Mac.
OTP_BURST = 5
ADB = str(Path.home() / "Library/Android/sdk/platform-tools/adb")
PKG = "com.santhoshsunkara.lync"
ACTIVITY = f"{PKG}/app.wallhop.ui.MainActivity"


class Phone:
    def __init__(self, serial):
        self.serial = serial

    def sh(self, *args, check=False):
        return subprocess.run([ADB, "-s", self.serial, "shell", *args], capture_output=True, text=True, check=check).stdout

    def in_front(self):
        # The focused app, not the focused window: Lync's own menus are popup windows of their own.
        out = self.sh("dumpsys", "window").splitlines()
        app = next((l for l in out if "mFocusedApp" in l), "")
        window = next((l for l in out if "mCurrentFocus" in l), "")
        return f"{PKG}/" in app and (f"{PKG}/" in window or "PopupWindow" in window or "=null" in window)

    def guard(self):
        """Stops everything if another app came to the front, so no input lands outside Wallhop."""
        if not self.in_front():
            raise SystemExit("Wallhop is no longer in front. Stopping so no taps land in another app.")

    def tap(self, x, y):
        self.guard()
        self.sh("input", "tap", str(int(x)), str(int(y)))

    def swipe(self, x1, y1, x2, y2, ms):
        self.guard()
        self.sh("input", "swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), str(int(ms)))

    def size(self):
        w, h = re.search(r"(\d+)x(\d+)", self.sh("wm", "size")).groups()
        return int(w), int(h)

    def nodes(self):
        """Visible elements with text or a description, and their centres."""
        self.sh("uiautomator", "dump", "/sdcard/wh_dump.xml")
        xml = self.sh("cat", "/sdcard/wh_dump.xml")
        found = {}
        for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            label = m.group(1) or m.group(2)
            if not label:
                continue
            x1, y1, x2, y2 = map(int, m.groups()[2:])
            found.setdefault(label, []).append(((x1 + x2) / 2, (y1 + y2) / 2))
        # Default to the first occurrence; `lowest` picks e.g. a tab label over a page title.
        return {label: spots[0] for label, spots in found.items()} | {"__all__": found}

    def tap_label(self, label, nodes=None):
        nodes = nodes or self.nodes()
        if label not in nodes:
            raise RuntimeError(f"'{label}' not on screen; saw {sorted(nodes)[:25]}")
        self.tap(*nodes[label])

    def reset_frames(self):
        self.sh("dumpsys", "gfxinfo", PKG, "reset")

    def frames(self):
        out = self.sh("dumpsys", "gfxinfo", PKG)
        def num(pattern, default=0.0):
            m = re.search(pattern, out)
            return float(m.group(1)) if m else default
        return {
            "frames": int(num(r"Total frames rendered: (\d+)")),
            "janky": int(num(r"Janky frames: (\d+)")),
            "janky_pct": num(r"Janky frames: \d+ \(([\d.]+)%\)"),
            "p50_ms": num(r"50th percentile: (\d+)ms"),
            "p90_ms": num(r"90th percentile: (\d+)ms"),
            "p95_ms": num(r"95th percentile: (\d+)ms"),
            "p99_ms": num(r"99th percentile: (\d+)ms"),
            "slow_ui": int(num(r"Number Slow UI thread: (\d+)")),
            "slow_draw": int(num(r"Number Slow issue draw commands: (\d+)")),
            "frozen": int(num(r"Number Frozen frames: (\d+)")),
        }

    def phases(self):
        """Median main-thread and render-thread time per frame, from gfxinfo framestats."""
        out = self.sh("dumpsys", "gfxinfo", PKG, "framestats")
        header, ui, render, total = None, [], [], []
        for line in out.splitlines():
            if line.startswith("Flags,"):
                header = line.rstrip(",").split(",")
                continue
            if header is None or not line or not line[0].isdigit():
                continue
            row = line.rstrip(",").split(",")
            if len(row) < len(header):
                continue
            f = dict(zip(header, map(int, row)))
            if f["Flags"] != 0:
                continue
            ui.append((f["SyncQueued"] - f["IntendedVsync"]) / 1e6)
            render.append((f["FrameCompleted"] - f["SyncStart"]) / 1e6)
            total.append((f["FrameCompleted"] - f["IntendedVsync"]) / 1e6)
        def median(xs):
            return round(sorted(xs)[len(xs) // 2], 1) if xs else -1
        return {"ui_thread_p50_ms": median(ui), "render_thread_p50_ms": median(render), "frame_p50_ms": median(total)}

    def memory_kb(self):
        out = self.sh("dumpsys", "meminfo", PKG)
        m = re.search(r"TOTAL PSS:\s+(\d+)", out) or re.search(r"TOTAL\s+(\d+)", out)
        return int(m.group(1)) if m else -1

    def crashes(self):
        crash = self.sh("logcat", "-d", "-b", "crash")
        main = self.sh("logcat", "-d")
        return {
            "crashes": crash.count(f"Process: {PKG}"),
            "anrs": main.count(f"ANR in {PKG}"),
            "crash_text": "\n".join(l for l in crash.splitlines() if PKG in l or "Exception" in l)[:4000],
        }

    def otp_delivered(self):
        return self.sh("logcat", "-d", "-s", "WallhopLink").count("otp delivered=true")


def run(phone, out, only=None, minutes=0):
    w, h = phone.size()
    results = {"device": phone.sh("getprop", "ro.product.model").strip(),
               "android": phone.sh("getprop", "ro.build.version.release").strip(), "scenarios": []}
    phone.sh("logcat", "-c")
    if not phone.in_front():
        raise SystemExit("Open Wallhop on the unlocked phone first; the test never opens it over another app.")
    phone.sh("am", "start", "-n", ACTIVITY)
    time.sleep(6)
    results["memory_start_kb"] = phone.memory_kb()

    def scenario(name, body):
        phone.reset_frames()
        started = time.time()
        error = None
        try:
            body()
        except Exception as e:  # Keep going; record what broke.
            error = str(e)
        time.sleep(1.0)
        record = {"name": name, "seconds": round(time.time() - started, 1), **phone.frames(), **phone.phases(),
                  "memory_kb": phone.memory_kb(), "error": error}
        results["scenarios"].append(record)
        print(json.dumps(record))
        if not minutes or name.startswith("r1_"):  # Screenshots from the first round only.
            (out / f"{name}.png").write_bytes(subprocess.run([ADB, "-s", phone.serial, "exec-out", "screencap", "-p"],
                                                             capture_output=True).stdout)

    nodes = phone.nodes()
    everything = nodes["__all__"]
    tabs = {name: max(everything[name], key=lambda p: p[1]) for name in ("Home", "Activity", "Settings") if name in everything}
    if len(tabs) < 3:
        raise SystemExit(f"Tab bar not found. Is the phone unlocked with Wallhop open? Saw: {sorted(nodes)[:30]}")
    bar_y = tabs["Home"][1]

    def tab_spam():
        order = ["Home", "Activity", "Settings", "Activity", "Home", "Settings"] * 8
        for name in order:
            phone.tap(*tabs[name])
            time.sleep(0.06)

    def droplet_drags():
        left, right = tabs["Home"][0], tabs["Settings"][0]
        for i in range(20):
            a, b = (left, right) if i % 2 == 0 else (right, left)
            phone.swipe(a, bar_y, b, bar_y, 280)

    def scroll_flings():
        phone.tap(*tabs["Activity"])
        time.sleep(0.6)
        for i in range(30):
            if i % 2 == 0:
                phone.swipe(w / 2, h * 0.75, w / 2, h * 0.25, 90)
            else:
                phone.swipe(w / 2, h * 0.3, w / 2, h * 0.8, 90)

    def filter_spam():
        phone.tap(*tabs["Activity"])
        time.sleep(0.6)
        seg = phone.nodes()
        for _ in range(6):
            for label in ("Codes", "Files", "Calls", "All"):
                if label in seg:
                    phone.tap(*seg[label])
                    time.sleep(0.05)

    def menu_spam():
        phone.tap(*tabs["Home"])
        time.sleep(0.8)
        more = phone.nodes().get("More")
        if not more:
            raise RuntimeError("'More' button not found")
        # Left of the menu, in the gap between the Mac card and "Send to Mac": the tap only closes the menu.
        empty = (w * 0.16, h * 0.46)
        for _ in range(15):
            phone.tap(*more)
            time.sleep(0.18)
            phone.tap(*empty)
            time.sleep(0.12)

    def sheet_spam():
        phone.tap(*tabs["Home"])
        time.sleep(0.8)
        text_tile = phone.nodes().get("Text")
        if not text_tile:
            raise RuntimeError("'Text' tile not found")
        for _ in range(15):
            phone.tap(*text_tile)
            time.sleep(0.3)
            phone.tap(w / 2, h * 0.12)  # The dimmed area above the sheet closes it.
            time.sleep(0.15)

    def push_pop():
        for _ in range(5):
            phone.tap(*tabs["Settings"])
            time.sleep(0.7)
            for _ in range(8):  # Settings keeps its scroll position: back to the top first.
                phone.swipe(w / 2, h * 0.3, w / 2, h * 0.9, 80)
            time.sleep(0.6)
            phone.tap_label("Phone notifications on Mac")
            time.sleep(1.2)
            for _ in range(6):
                phone.swipe(w / 2, h * 0.8, w / 2, h * 0.2, 80)
            for _ in range(6):  # Back to the top, where the Back button is.
                phone.swipe(w / 2, h * 0.3, w / 2, h * 0.9, 80)
            time.sleep(0.8)
            phone.tap_label("Back")
            time.sleep(0.6)

    def otp_spam():
        before = phone.otp_delivered()
        phone.tap(*tabs["Settings"])
        time.sleep(0.7)
        for _ in range(8):  # Scroll to the bottom where the debug row lives.
            phone.swipe(w / 2, h * 0.8, w / 2, h * 0.2, 120)
        time.sleep(0.6)
        button = phone.nodes().get("Send a test code")
        if not button:
            raise RuntimeError("'Send a test code' row not found (debug build?)")
        # Load rises each round: 5, 10, 20 codes, then again.
        burst = OTP_BURST * (2 ** ((results["rounds"] - 1) % 3))
        for _ in range(burst):
            phone.tap(*button)
            time.sleep(0.08)
        time.sleep(4 + burst * 0.2)
        results["otp_sent"] = results.get("otp_sent", 0) + burst
        results["otp_delivered"] = results.get("otp_delivered", 0) + phone.otp_delivered() - before

    def restart_reconnect():
        """Force-stops Lync and opens it again; times how long until the Mac shows as linked."""
        phone.guard()
        phone.sh("am", "force-stop", PKG)
        started = time.time()
        phone.sh("am", "start", "-n", ACTIVITY)
        while time.time() - started < 30:
            time.sleep(0.5)
            if not phone.in_front():
                continue
            if "Your Mac" in phone.nodes():
                break
        seconds = round(time.time() - started, 1)
        results.setdefault("reconnect_seconds", []).append(seconds)
        if seconds >= 30:
            raise RuntimeError("Not linked again within 30 s")
        # The tab bar moves with a fresh launch: find it again.
        found = phone.nodes()["__all__"]
        tabs.update({name: max(found[name], key=lambda p: p[1]) for name in ("Home", "Activity", "Settings") if name in found})

    scenarios = [
        ("1_tab_spam", tab_spam),
        ("2_droplet_drags", droplet_drags),
        ("3_scroll_flings", scroll_flings),
        ("4_filter_spam", filter_spam),
        ("5_menu_spam", menu_spam),
        ("6_sheet_spam", sheet_spam),
        ("7_push_pop_app_list", push_pop),
        ("8_otp_spam", otp_spam),
        ("9_restart_reconnect", restart_reconnect),
    ]
    # One pass, or rounds until the time is up (memory and crashes checked every round).
    deadline = time.time() + minutes * 60
    results["rounds"] = 0
    while True:
        results["rounds"] += 1
        for name, body in scenarios:
            if only and not any(name.startswith(o) for o in only):
                continue
            scenario(f"r{results['rounds']}_{name}" if minutes else name, body)
        if not minutes or time.time() >= deadline:
            break
        if phone.crashes()["crashes"]:
            print("Crash seen; stopping early.")
            break

    # Recovery: a minute with nothing happening, then is it still linked?
    if minutes:
        time.sleep(60)
        results["linked_after_idle"] = "Your Mac" in phone.nodes()
    results["memory_end_kb"] = phone.memory_kb()
    results.update(phone.crashes())
    (out / "results.json").write_text(json.dumps(results, indent=2))
    print(json.dumps({k: v for k, v in results.items() if k != "scenarios"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", nargs="*", help="scenario numbers to run, e.g. 1 2 3")
    parser.add_argument("--minutes", type=float, default=0, help="repeat the scenarios for this long")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    phone = Phone(args.device)
    # Start from a fresh launch. Only when Wallhop is already in front.
    phone.guard()
    phone.sh("am", "force-stop", PKG)
    phone.sh("am", "start", "-n", ACTIVITY)
    time.sleep(4)
    run(phone, out, args.only, args.minutes)
