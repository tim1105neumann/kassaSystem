import KassaShared
import SwiftData
import SwiftUI

struct TableGridView: View {
    @Binding var path: NavigationPath
    @Environment(AppModel.self) private var model
    @Environment(AppSettings.self) private var settings
    @Environment(LocalStore.self) private var store

    /// Nur offene Zeilen — Storniertes und Kassiertes gehört nicht mehr zum Tisch.
    @Query(filter: #Predicate<LocalOrderLine> { $0.voidedAt == nil && $0.settlementId == nil })
    private var openLines: [LocalOrderLine]

    @State private var now = Date.now
    private let ticker = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    private var byTable: [Int: [LocalOrderLine]] {
        Dictionary(grouping: openLines, by: \.tableNumber)
    }

    private let columns = [GridItem(.adaptive(minimum: 104, maximum: 200), spacing: 12)]

    var body: some View {
        VStack(spacing: 0) {
            ConnectionBanner()
                .padding(.horizontal, 16)
                .padding(.bottom, 8)

            ScrollView {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(1...max(settings.tableCount, 1), id: \.self) { number in
                        Button {
                            path.append(Route.table(number))
                        } label: {
                            TableTile(number: number, lines: byTable[number] ?? [], now: now)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
        }
        .background(Color(.systemGroupedBackground))
        .refreshable { model.syncSoon() }
        .onReceive(ticker) { now = $0 }
        .alert(
            "Hinweis",
            isPresented: Binding(
                get: { store.conflictMessage != nil },
                set: { if !$0 { store.conflictMessage = nil } }
            )
        ) {
            Button("OK", role: .cancel) { store.conflictMessage = nil }
        } message: {
            Text(store.conflictMessage ?? "")
        }
    }
}

private struct TableTile: View {
    let number: Int
    let lines: [LocalOrderLine]
    let now: Date

    private var total: Money { TableTotals.openTotal(of: lines) }
    private var isOpen: Bool { !lines.isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(number)")
                .font(.system(size: 34, weight: .heavy, design: .rounded))
                .minimumScaleFactor(0.6)
                .lineLimit(1)
                .foregroundStyle(isOpen ? Color.white : Color.primary)

            if isOpen {
                Text(total.formatted)
                    .font(.headline)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                    .foregroundStyle(.white)
                Text(openSince)
                    .font(.caption)
                    .lineLimit(1)
                    .foregroundStyle(.white.opacity(0.85))
            } else {
                Text("frei")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 96, alignment: .topLeading)
        .padding(12)
        .background(isOpen ? Color.accentColor : Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 16))
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(isOpen ? Color.clear : Color(.separator), lineWidth: 1)
        }
        .contentShape(.rect)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            isOpen
                ? String(localized: "Tisch \(number), offen \(total.formatted), \(openSince)")
                : String(localized: "Tisch \(number), frei")
        )
    }

    private var openSince: String {
        guard let start = TableTotals.openedAt(of: lines) else { return "" }
        let minutes = max(Int(now.timeIntervalSince(start) / 60), 0)
        if minutes < 60 { return String(localized: "seit \(minutes) min") }
        return String(localized: "seit \(minutes / 60) h \(minutes % 60) min")
    }
}

#Preview {
    let sample = PreviewData.make()
    NavigationStack {
        TableGridView(path: .constant(NavigationPath()))
            .environment(sample.model)
            .environment(sample.model.store)
            .environment(sample.model.settings)
            .modelContainer(sample.container)
    }
}
