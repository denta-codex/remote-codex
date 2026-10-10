// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "HelloWorldFixture",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [.library(name: "HelloWorldFixtureCore", targets: ["HelloWorldFixtureCore"])],
    targets: [
        .target(name: "HelloWorldFixtureCore"),
        .testTarget(name: "HelloWorldFixtureCoreTests", dependencies: ["HelloWorldFixtureCore"]),
    ],
    swiftLanguageModes: [.v5]
)
