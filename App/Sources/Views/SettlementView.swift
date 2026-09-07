import KassaShared
import SwiftData
import SwiftUI

struct SettlementView: View {
    let tableNumber: Int
    @Binding var path: NavigationPath

    @Environment(LocalStore.self) private var store
    @Environment(AppModel.self) private var model
    @Query private var lines: [LocalOrderLine]

    private enum Stage { case select, pay }
    private enum Mode: Hashable { case all, partial }
    private enum PrintState { case idle, sending, done, failed(String) }

    @State private var stage: Stage = .select
    @State private var mode: Mode = .all
    @State private var selection = SettlementSelection()
    @State private var printState: PrintState = .idle
    @State private var paidText = ""
    @State private var givenText = ""
    @FocusState private var paidFocused: Bool
    @FocusState private var givenFocused: Bool

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

    private var amount: Money {
        mode == .all ? TableTotals.openTotal(of: lines) : selection.subtotal(over: lines)
    }

    /// Kassieren und Aufstellung müssen dasselbe zeigen — beide leiten die
    /// Positionen hier ab.
    private var selections: [SettlementLineSelection] {
        mode == .all
            ? lines.map { SettlementLineSelection(lineId: $0.id, qty: $0.qty) }
            : selection.requestLines(over: lines)
    }

    private var paid: Money? { Money(parsing: paidText) }

    /// Ohne Eingabe im „Macht“-Feld zahlt der Gast einfach die Rechnung.
    private var payable: Money { paid ?? amount }

    private var tip: Result<Money, TipError>? {
        guard let paid else { return nil }
        return TipCalculator.tip(total: amount, paid: paid)
    }

    private var given: Money? { Money(parsing: givenText) }

    private var change: Result<Money, ChangeError>? {
        guard let given else { return nil }
        return ChangeCalculator.change(total: payable, given: given)
    }

    var body: some View {
        VStack(spacing: 0) {
            switch stage {
            case .select: selectionContent
            case .pay: paymentContent
            }
            bottomBar
        }
        .navigationTitle("Tisch \(tableNumber) kassieren")
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(.systemGroupedBackground))
    }

    // MARK: - Auswahl

    private var selectionContent: some View {
        VStack(spacing: 0) {
            Picker("Umfang", selection: $mode) {
                Text("Alles kassieren").tag(Mode.all)
                Text("Teilbetrag").tag(Mode.partial)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            List {
                ForEach(lines) { line in
                    if mode == .all {
                        LabeledContent {
                            Text(line.lineTotal.formatted)
                                .font(.headline.monospacedDigit())
                        } label: {
                            Text("\(line.qty)× \(line.nameSnapshot)")
                                .font(.headline)
                        }
                    } else {
                        PartialLineRow(line: line, selected: selection.qty(for: line.id)) { qty in
                            selection.set(qty, for: line.id, max: line.qty)
                        }
                    }
                }
            }
            .listStyle(.plain)
        }
    }

    // MARK: - Rückgeld

    private var paymentContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Zu zahlen")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                    Text(amount.formatted)
                        .font(.system(size: 44, weight: .heavy, design: .rounded))
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                }

                tipInput

                VStack(alignment: .leading, spacing: 8) {
                    Text("Gegeben")
                        .font(.headline)
                    TextField("0,00", text: $givenText)
                        .keyboardType(.decimalPad)
                        .font(.system(size: 34, weight: .semibold, design: .rounded))
                        .textFieldStyle(.roundedBorder)
                        .focused($givenFocused)
                        .accessibilityLabel("Gegebener Betrag")

                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 10)], spacing: 10) {
                        ForEach(ChangeCalculator.quickAmounts(for: payable), id: \.cents) { value in
                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                givenText = value.formattedPlain
                                givenFocused = false
                            } label: {
                                Text(value == payable ? String(localized: "passend") : value.formatted)
                                    .font(.headline)
                                    .frame(maxWidth: .infinity, minHeight: 52)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }

                changeDisplay
            }
            .padding(16)
        }
    }

    private var tipInput: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text("Macht")
                    .font(.headline)
                TextField(amount.formattedPlain, text: $paidText)
                    .keyboardType(.decimalPad)
                    .font(.system(size: 28, weight: .semibold, design: .rounded))
                    .textFieldStyle(.roundedBorder)
                    .focused($paidFocused)
                    .accessibilityLabel("Betrag, den der Gast nennt")
                tipDisplay
            }

            tipWarning

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 10)], spacing: 10) {
                ForEach(TipCalculator.quickTotals(for: amount), id: \.cents) { value in
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        paidText = value.formattedPlain
                        paidFocused = false
                    } label: {
                        Text(value == amount ? String(localized: "passend") : value.formatted)
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 52)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    @ViewBuilder
    private var tipDisplay: some View {
        if case .success(let value) = tip, value.cents > 0 {
            VStack(alignment: .trailing, spacing: 2) {
                Text("Trinkgeld")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(value.formatted)
                    .font(.title3.weight(.bold).monospacedDigit())
                    .foregroundStyle(.green)
            }
            .fixedSize()
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Trinkgeld \(value.formatted)")
        }
    }

    @ViewBuilder
    private var tipWarning: some View {
        if case .failure = tip {
            Label("Weniger als die Rechnung", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 16))
        }
    }

    @ViewBuilder
    private var changeDisplay: some View {
        if let change {
            switch change {
            case .success(let value):
                VStack(alignment: .leading, spacing: 4) {
                    Text("Rückgeld")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                    Text(value.formatted)
                        .font(.system(size: 60, weight: .black, design: .rounded))
                        .minimumScaleFactor(0.4)
                        .lineLimit(1)
                        .foregroundStyle(.green)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 16))
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Rückgeld \(value.formatted)")
            case .failure(.notEnough(let missing)):
                Label("Es fehlen noch \(missing.formatted)", systemImage: "exclamationmark.triangle.fill")
                    .font(.headline)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 16))
            }
        }
    }

    // MARK: - Aktionsleiste

    private var bottomBar: some View {
        VStack(spacing: 12) {
            if stage == .select {
                HStack {
                    Text("Betrag")
                        .font(.title3.weight(.semibold))
                    Spacer()
                    Text(amount.formatted)
                        .font(.system(size: 36, weight: .heavy, design: .rounded))
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                }
                printFeedback
            }

            HStack(spacing: 12) {
                if stage == .select {
                    Button {
                        printOverview()
                    } label: {
                        Group {
                            if isPrinting {
                                ProgressView()
                            } else {
                                Label("Aufstellung", systemImage: "printer")
                            }
                        }
                        .font(.title3.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 60)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isPrimaryDisabled || isPrinting)
                    .accessibilityLabel("Aufstellung zum Nachrechnen drucken")
                }

                if stage == .pay {
                    Button {
                        stage = .select
                    } label: {
                        Text("Zurück")
                            .font(.title3.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 60)
                    }
                    .buttonStyle(.bordered)
                }

                Button {
                    if stage == .select {
                        paidFocused = false
                        givenFocused = false
                        stage = .pay
                    } else {
                        confirm()
                    }
                } label: {
                    Text(stage == .select ? "Weiter" : "Kassieren bestätigen")
                        .font(.title3.weight(.bold))
                        .frame(maxWidth: .infinity, minHeight: 60)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isPrimaryDisabled)
            }
        }
        .padding(16)
        .background(.bar)
    }

    private var isPrimaryDisabled: Bool {
        switch stage {
        case .select:
            mode == .all ? lines.isEmpty : selection.isEmpty
        case .pay:
            // Leere Felder sind erlaubt: kein Trinkgeld, kein Rückgeld —
            // nur ein zu kleiner eingegebener Betrag blockiert.
            if case .failure = tip { true } else if case .failure = change { true } else { false }
        }
    }

    @ViewBuilder
    private var printFeedback: some View {
        switch printState {
        case .idle, .sending:
            EmptyView()
        case .done:
            Label("Aufstellung liegt in der Küche", systemImage: "checkmark.circle.fill")
                .font(.headline)
                .foregroundStyle(.green)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 16))
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 16))
        }
    }

    private var isPrinting: Bool {
        if case .sending = printState { true } else { false }
    }

    /// Der Zettel geht direkt zum Server, nicht über die Offline-Queue: er wird
    /// sofort in der Küche gebraucht, eine stille Nachlieferung wäre wertlos.
    /// Ohne Netz sieht der Kellner deshalb lieber gleich eine Meldung.
    private func printOverview() {
        let requested = selections
        guard !requested.isEmpty else { return }

        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        printState = .sending
        Task {
            let failure = await model.printOverview(tableNumber: tableNumber, selections: requested)
            let feedback = UINotificationFeedbackGenerator()
            if let failure {
                feedback.notificationOccurred(.error)
                printState = .failed(failure)
            } else {
                feedback.notificationOccurred(.success)
                printState = .done
            }
        }
    }

    private func confirm() {
        guard !selections.isEmpty else { return }

        // `amount` bleibt die reine Warensumme, das Trinkgeld geht daneben mit.
        let tipOrZero = if case .success(let value) = tip { value } else { Money.zero }

        UINotificationFeedbackGenerator().notificationOccurred(.success)
        store.settle(tableNumber: tableNumber, selections: selections, total: amount, tip: tipOrZero)
        model.syncSoon()
        path = NavigationPath()
    }
}

private struct PartialLineRow: View {
    let line: LocalOrderLine
    let selected: Int
    let onChange: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(line.nameSnapshot)
                    .font(.headline)
                Spacer(minLength: 8)
                Text("\(line.unitPrice.formatted) / Stück")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 16) {
                Text("\(selected) von \(line.qty)")
                    .font(.title3.weight(.semibold).monospacedDigit())
                Spacer(minLength: 0)
                Button {
                    onChange(selected - 1)
                } label: {
                    Image(systemName: "minus").font(.title2.weight(.bold)).frame(width: 52, height: 52)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.circle)
                .disabled(selected == 0)
                .accessibilityLabel("Eins weniger")

                Button {
                    onChange(selected + 1)
                } label: {
                    Image(systemName: "plus").font(.title2.weight(.bold)).frame(width: 52, height: 52)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.circle)
                .disabled(selected >= line.qty)
                .accessibilityLabel("Eins mehr")
            }
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(line.nameSnapshot), \(selected) von \(line.qty) ausgewählt")
    }
}

#if DEBUG
#Preview {
    let sample = PreviewData.make()
    NavigationStack {
        SettlementView(tableNumber: 3, path: .constant(NavigationPath()))
            .environment(sample.model)
            .environment(sample.model.store)
            .environment(sample.model.settings)
            .modelContainer(sample.container)
    }
}
#endif
