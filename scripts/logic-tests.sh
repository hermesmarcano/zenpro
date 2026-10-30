#!/usr/bin/env bash
# Corre los tests de la lógica pura de ZEN (código que solo depende de Foundation)
# con SwiftPM, sin Xcode ni simulador. Sirve en Linux y macOS, y da feedback en
# segundos en vez de esperar al CI de macOS.
#
# Uso: scripts/logic-tests.sh [argumentos extra para `swift test`]
# Requiere `swift` en el PATH (toolchain de swift.org, 6.0 o superior).
#
# Para sumar un archivo: agrégalo a LOGIC_SOURCES (no debe importar UIKit, SwiftUI,
# SwiftData, Vision, AVFoundation, etc.) y su test a LOGIC_TESTS.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

LOGIC_SOURCES=(
  ZEN/SmartAlarm/MotionClassifier.swift
  ZEN/SmartAlarm/SmartVolumeController.swift
  ZEN/Photo/PhotoMatchPolicy.swift
)
LOGIC_TESTS=(
  ZENTests/MotionClassifierTests.swift
  ZENTests/SmartVolumeControllerTests.swift
  ZENTests/PhotoMatchPolicyTests.swift
)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/Sources/ZEN" "$WORK/Tests/ZENLogicTests"

for file in "${LOGIC_SOURCES[@]}"; do
  ln -s "$ROOT/$file" "$WORK/Sources/ZEN/$(basename "$file")"
done
for file in "${LOGIC_TESTS[@]}"; do
  ln -s "$ROOT/$file" "$WORK/Tests/ZENLogicTests/$(basename "$file")"
done

# El módulo se llama ZEN para que `@testable import ZEN` en los tests funcione igual
# que en el proyecto de Xcode. Modo de lenguaje Swift 5, como project.yml.
cat > "$WORK/Package.swift" <<'MANIFEST'
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ZENLogic",
    targets: [
        .target(name: "ZEN", path: "Sources/ZEN"),
        .testTarget(name: "ZENLogicTests", dependencies: ["ZEN"], path: "Tests/ZENLogicTests"),
    ],
    swiftLanguageModes: [.v5]
)
MANIFEST

swift test --package-path "$WORK" "$@"
