import SwiftData
import SwiftUI

/// Der Status muss ehrlich sein: offline heißt, der gezeigte Stand ist nicht
/// der geteilte. Das steht deshalb im Klartext da.
struct ConnectionBanner: View {
    @Environment(LocalStore.self) private var store
    @Query(sort: \PendingCommand.createdAt) private var commands: [PendingCommand]

    private var openCount: Int { commands.count(where: { !$0.failedPermanently }) }
    private var failedCount: Int { commands.count(where: \.failedPermanently) }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(.white)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(.white)
                if let detail {
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.9))
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(background, in: .rect(cornerRadius: 16))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title). \(detail ?? "")")
    }

    private var icon: String {
        switch store.connection {
        case .connected: "checkmark.icloud.fill"
        case .syncing: "arrow.triangle.2.circlepath"
        case .offline: "exclamationmark.icloud.fill"
        case .needsLogin: "person.crop.circle.badge.exclamationmark"
        }
    }

    private var title: String {
        switch store.connection {
        case .connected: openCount > 0
            ? String(localized: "Wird übertragen …")
            : String(localized: "Verbunden")
        case .syncing: String(localized: "Wird abgeglichen …")
        case .offline: String(localized: "Offline")
        case .needsLogin: String(localized: "Nicht angemeldet")
        }
    }

    private var detail: String? {
        var parts: [String] = []
        if openCount > 0 {
            parts.append(String(localized: "\(openCount) Buchungen ausstehend"))
        }
        if failedCount > 0 {
            parts.append(String(localized: "\(failedCount) fehlgeschlagen"))
        }
        if store.connection == .offline {
            parts.append(String(localized: "Angezeigter Stand ist nicht der geteilte"))
        }
        if store.connection == .needsLogin {
            parts.append(String(localized: "Bitte in den Einstellungen anmelden"))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private var background: Color {
        switch store.connection {
        case .connected: openCount > 0 ? .orange : .green
        case .syncing: .blue
        case .offline: .red
        case .needsLogin: .gray
        }
    }
}
