// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "KassaShared",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "KassaShared", targets: ["KassaShared"])
    ],
    targets: [
        .target(name: "KassaShared"),
        .testTarget(name: "KassaSharedTests", dependencies: ["KassaShared"])
    ]
)
