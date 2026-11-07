// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "WallhopMac",
    platforms: [.macOS(.v14)],
    dependencies: [
        // Updates from the Lync download page (appcast on GitHub Pages), checked with an EdDSA key.
        .package(url: "https://github.com/sparkle-project/Sparkle", exact: "2.10.0"),
    ],
    targets: [
        .executableTarget(
            name: "WallhopMac",
            dependencies: [.product(name: "Sparkle", package: "Sparkle")],
            path: "Sources/WallhopMac"
        )
    ]
)
