// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PrintService",
    platforms: [.macOS(.v11)],
    products: [
        .library(name: "KuechenbonCore", targets: ["KuechenbonCore"]),
        .executable(name: "kuechenbon", targets: ["kuechenbon"])
    ],
    dependencies: [.package(path: "../Shared")],
    targets: [
        .target(name: "KuechenbonCore", dependencies: [.product(name: "KassaShared", package: "Shared")]),
        .executableTarget(name: "kuechenbon", dependencies: ["KuechenbonCore"]),
        .testTarget(name: "KuechenbonCoreTests", dependencies: ["KuechenbonCore"])
    ]
)
