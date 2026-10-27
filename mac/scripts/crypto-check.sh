#!/bin/bash
# Checks the link's cryptography against fixed vectors (scripts/CryptoCheck). No phone needed.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
swiftc -O -o build/crypto-check Sources/WallhopMac/Core/Noise.swift Sources/WallhopMac/Core/Crypto.swift \
  Sources/WallhopMac/Core/BleWire.swift Sources/WallhopMac/Core/Message.swift \
  Sources/WallhopMac/Core/Framing.swift scripts/CryptoCheck/main.swift
exec build/crypto-check "$@"
