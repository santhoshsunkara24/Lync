#!/bin/bash
# Builds and runs the stand-in test phone (scripts/TestPhone). See its header for details.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
swiftc -O -o build/test-phone Sources/WallhopMac/Core/Crypto.swift Sources/WallhopMac/Core/Framing.swift \
  Sources/WallhopMac/Core/Message.swift Sources/WallhopMac/Core/Storage.swift scripts/TestPhone/main.swift
exec build/test-phone "$@"
