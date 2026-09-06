import KassaShared
import SwiftData
import SwiftUI

struct TableDetailView: View {
    let tableNumber: Int
    @Binding var path: NavigationPath

    @Environment(LocalStore.self) private var store
    @Query private var lines: [LocalOrderLine]

    init(tableNumber: Int, path: Binding<NavigationPath>) {
        self.tableNumber = tableNumber
        _path = path
        _lines = Query(
            filter: #Predicate<LocalOrderLine> {
                $0.tableNumber == tableNumber && $0.voidedAt == nil && $0.settlementId == nil
            },
            sort: [SortDescriptor(\LocalOrderLine.createdAt)]
        )
    }

    private var total: Money { TableTotals.openTotal(of: lines) }

    var body: some View {
        VStack(spacing: 0) {
            if lines.isEmpty {
                ContentUnavailableView(
                    "Noch nichts gebucht",
                    systemImage: "tray",
                    description: Text("Mit „+ Artikel“ die erste Bestellung aufnehmen.")
                )
                .frame(maxHeight: .infinity)
            } else {
                List {
                    ForEach(lines) { line in
                        OrderLineRow(line: line) { newQty in
                            store.changeQty(of: line, to: newQty)
                        }
                    }
                }
                .listStyle(.plain)
            }

            bottomBar
        }
        .navigationTitle("Tisch \(tableNumber)")
        .navigationBarTitleDisplayMode(.large)
        .background(Color(.systemGroupedBackground))
    }

    private var bottomBar: some View {
        VStack(spacing: 12) {
            HStack {
                Text("Gesamt")
                    .font(.title3.weight(.semibold))
                Spacer()
                Text(total.formatted)
                    .font(.system(size: 40, weight: .heavy, design: .rounded))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Gesamtsumme \(total.formatted)")

            HStack(spacing: 12) {
                Button {
                    path.append(Route.articles(tableNumber))
                } label: {
                    Label("+ Artikel", systemImage: "plus.circle.fill")
                        .font(.title3.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 60)
                }
                .buttonStyle(.bordered)

                Button {
                    path.append(Route.settle(tableNumber))
                } label: {
                    Text("Kassieren")
                        .font(.title3.weight(.bold))
                        .frame(maxWidth: .infinity, minHeight: 60)
                }
                .buttonStyle(.borderedProminent)
                .disabled(lines.isEmpty)
            }
        }
        .padding(16)
        .background(.bar)
    }
}

private struct OrderLineRow: View {
    let line: LocalOrderLine
    let onQtyChange: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(line.nameSnapshot)
                    .font(.headline)
                Spacer(minLength: 8)
                Text(line.lineTotal.formatted)
                    .font(.headline.monospacedDigit())
            }

            HStack(spacing: 16) {
                Text("\(line.unitPrice.formatted) / Stück")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Spacer(minLength: 0)

                stepperButton(systemImage: "minus", label: String(localized: "Eins weniger")) {
                    onQtyChange(line.qty - 1)
                }
                Text("\(line.qty)")
                    .font(.title2.weight(.bold).monospacedDigit())
                    .frame(minWidth: 44)
                    .accessibilityLabel("Menge \(line.qty)")
                stepperButton(systemImage: "plus", label: String(localized: "Eins mehr")) {
                    onQtyChange(line.qty + 1)
                }
            }
        }
        .padding(.vertical, 8)
    }

    private func stepperButton(systemImage: String, label: String, action: @escaping () -> Void) -> some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            Image(systemName: systemImage)
                .font(.title2.weight(.bold))
                .frame(width: 52, height: 52)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.circle)
        .accessibilityLabel(label)
    }
}

#if DEBUG
#Preview {
    let sample = PreviewData.make()
    NavigationStack {
        TableDetailView(tableNumber: 3, path: .constant(NavigationPath()))
            .environment(sample.model)
            .environment(sample.model.store)
            .environment(sample.model.settings)
            .modelContainer(sample.container)
    }
}
#endif
