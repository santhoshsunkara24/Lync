#!/bin/bash
# Builds and runs the stress phone (scripts/StressPhone) against a test copy of the Mac app.
#   scripts/stress-phone.sh <port> <minutes>
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
swiftc -O -D DEBUG -o build/stress-phone Sources/WallhopMac/Core/Noise.swift Sources/WallhopMac/Core/Crypto.swift Sources/WallhopMac/Core/Framing.swift \
  Sources/WallhopMac/Core/Message.swift Sources/WallhopMac/Core/Storage.swift Sources/WallhopMac/Core/DevEnv.swift scripts/StressPhone/main.swift
exec build/stress-phone "$@"
