// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RemoteCodex",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [
        .library(name: "RemoteCodexCore", targets: ["RemoteCodexCore"]),
        .executable(name: "RemoteCodexFixture", targets: ["RemoteCodexFixture"]),
    ],
    targets: [
        .systemLibrary(name: "CSQLite"),
        .target(name: "RemoteCodexCore", dependencies: ["CSQLite"]),
        .executableTarget(name: "RemoteCodexFixture", dependencies: ["RemoteCodexCore"]),
        .testTarget(name: "RemoteCodexCoreTests", dependencies: ["RemoteCodexCore"]),
    ],
    swiftLanguageModes: [.v5]
)
