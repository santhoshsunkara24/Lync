#!/bin/bash
# Renders the Mac app's screens to PNGs (scripts/Snapshots). No display needed.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
SOURCES=$(find Sources/WallhopMac -name '*.swift' ! -name main.swift)
# The app's Info.plist goes into the binary, so screens show the real version, not "dev", under
# its own bundle ID, so the renderer's settings never mix with the installed app's.
cp Info.plist build/snapshots-Info.plist
/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier app.wallhop.mac.snapshots" build/snapshots-Info.plist
swiftc -O -D SNAPSHOTS -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker build/snapshots-Info.plist -o build/snapshots $SOURCES scripts/Snapshots/main.swift 2>&1 | grep -E "error" || true
exec build/snapshots "${1:-build/snapshots-out}"
