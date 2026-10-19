// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "WallhopMac",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "WallhopMac", path: "Sources/WallhopMac")
    ]
)
