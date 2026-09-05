// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Server",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "Server", targets: ["Server"])
    ],
    dependencies: [
        .package(path: "../Shared"),
        .package(url: "https://github.com/vapor/vapor.git", from: "4.115.0"),
        .package(url: "https://github.com/vapor/fluent.git", from: "4.12.0"),
        .package(url: "https://github.com/vapor/fluent-sqlite-driver.git", from: "4.8.0"),
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0" ..< "5.0.0"),
    ],
    targets: [
        .target(
            name: "KassaServer",
            dependencies: [
                .product(name: "KassaShared", package: "Shared"),
                .product(name: "Vapor", package: "vapor"),
                .product(name: "Fluent", package: "fluent"),
                .product(name: "FluentSQLiteDriver", package: "fluent-sqlite-driver"),
                .product(name: "Crypto", package: "swift-crypto"),
            ],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .executableTarget(
            name: "Server",
            dependencies: [
                .target(name: "KassaServer"),
                .product(name: "Vapor", package: "vapor"),
            ],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "ServerTests",
            dependencies: [
                .target(name: "KassaServer"),
                .product(name: "VaporTesting", package: "vapor"),
            ],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
