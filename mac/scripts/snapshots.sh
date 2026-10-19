#!/bin/bash
# Renders the Mac app's screens to PNGs (scripts/Snapshots). No display needed.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
SOURCES=$(find Sources/WallhopMac -name '*.swift' ! -name main.swift)
swiftc -O -o build/snapshots $SOURCES scripts/Snapshots/main.swift 2>&1 | grep -E "error" || true
exec build/snapshots "${1:-build/snapshots-out}"
