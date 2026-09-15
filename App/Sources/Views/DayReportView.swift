import KassaShared
import SwiftUI

struct DayReportView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppSettings.self) private var settings
    @Environment(\.dismiss) private var dismiss

    private enum PrintState { case idle, sending, done, failed(String) }

    @State private var selectedDate = Date.now
    @State private var report: DayReportDTO?
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var printState: PrintState = .idle

    private var businessDay: String {
        BusinessDay.day(for: selectedDate, cutoffHour: settings.businessDayCutoffHour)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    DatePicker("Betriebstag", selection: $selectedDate, displayedComponents: .date)
                        .datePickerStyle(.compact)
                } footer: {
                    Text("Die Zahlen stammen aus dem Betriebstag, nicht aus dem Kalendertag. Ein neuer Betriebstag beginnt um \(settings.businessDayCutoffHour):00 Uhr.")
                }

                if isLoading {
                    Section { ProgressView().frame(maxWidth: .infinity) }
                } else if let errorMessage {
                    Section {
                        ContentUnavailableView(
                            "Nicht ladbar",
                            systemImage: "wifi.exclamationmark",
                            description: Text(errorMessage)
                        )
                    }
                } else if let report {
                    totalSection(report)
                    categorySection(report)
                    topArticleSection(report)
                    settlementSection(report)
                    printSection
                }
            }
            .navigationTitle("Tagesabschluss")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fertig") { dismiss() }
                }
            }
            .task(id: businessDay) { await load() }
        }
    }

    private func totalSection(_ report: DayReportDTO) -> some View {
        Section("Umsatz") {
            VStack(alignment: .leading, spacing: 6) {
                Text(report.total.formatted)
                    .font(.system(size: 48, weight: .heavy, design: .rounded))
                    .minimumScaleFactor(0.4)
                    .lineLimit(1)
                Text("\(report.settlementCount) Kassiervorgänge · Betriebstag \(report.businessDay)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)

            // Die große Zahl bleibt der Warenumsatz — nur so geht sie weiter
            // mit der Kategorie-Aufschlüsselung zusammen.
            if report.tipCents > 0 {
                LabeledContent("Trinkgeld") {
                    Text(report.tip.formatted)
                        .font(.headline.monospacedDigit())
                }
                LabeledContent("Kassa gesamt") {
                    Text(report.grandTotal.formatted)
                        .font(.headline.monospacedDigit().bold())
                }
            }
        }
    }

    @ViewBuilder
    private func categorySection(_ report: DayReportDTO) -> some View {
        if !report.byCategory.isEmpty {
            Section("Nach Kategorie") {
                ForEach(report.byCategory, id: \.category) { entry in
                    LabeledContent {
                        Text(Money(cents: entry.totalCents).formatted)
                            .font(.headline.monospacedDigit())
                    } label: {
                        Text(ArticleCategory(rawValue: entry.category)?.title ?? entry.category)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func topArticleSection(_ report: DayReportDTO) -> some View {
        if !report.topArticles.isEmpty {
            Section("Meistverkauft") {
                ForEach(report.topArticles, id: \.articleId) { entry in
                    LabeledContent {
                        Text(Money(cents: entry.totalCents).formatted)
                            .font(.headline.monospacedDigit())
                    } label: {
                        Text("\(entry.qty)× \(entry.name)")
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func settlementSection(_ report: DayReportDTO) -> some View {
        if !report.settlements.isEmpty {
            Section("Kassiervorgänge") {
                ForEach(report.settlements) { settlement in
                    LabeledContent {
                        Text(settlement.total.formatted)
                            .font(.headline.monospacedDigit())
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Tisch \(settlement.tableNumber)")
                            Text(settlement.paidAt.formatted(date: .omitted, time: .shortened))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if settlement.tipCents > 0 {
                                Text("inkl. \(settlement.tip.formatted) Trinkgeld")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
    }

    private var printSection: some View {
        Section {
            Button {
                printDayReport()
            } label: {
                Group {
                    if isPrinting {
                        ProgressView()
                    } else {
                        Label("Tagesstatistik drucken", systemImage: "printer")
                    }
                }
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .disabled(isPrinting)
            .accessibilityLabel("Tagesstatistik auf dem Küchendrucker ausdrucken")

            printFeedback
        }
    }

    @ViewBuilder
    private var printFeedback: some View {
        switch printState {
        case .idle, .sending:
            EmptyView()
        case .done:
            Label("Statistik liegt beim Küchendrucker", systemImage: "checkmark.circle.fill")
                .font(.headline)
                .foregroundStyle(.green)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.red)
        }
    }

    private var isPrinting: Bool {
        if case .sending = printState { true } else { false }
    }

    /// Gedruckt wird `businessDay`, nicht `report.businessDay`: Maßgeblich ist,
    /// was im DatePicker steht — der Bericht daneben kann noch der alte sein,
    /// solange `load()` läuft.
    private func printDayReport() {
        let day = businessDay

        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        printState = .sending
        Task {
            let failure = await model.printDayReport(businessDay: day)
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

    private func load() async {
        isLoading = true
        errorMessage = nil
        // `load()` läuft bei jedem Wechsel des Betriebstags. Ohne das Zurücksetzen
        // stünde der grüne Haken vom gedruckten Freitag noch da, während am Schirm
        // schon der Samstag steht — und der gilt dann als gedruckt, ohne es zu sein.
        printState = .idle
        defer { isLoading = false }
        do {
            report = try await model.dayReport(for: businessDay)
        } catch let error as APIError {
            report = nil
            errorMessage = error.germanMessage
        } catch {
            report = nil
            errorMessage = error.localizedDescription
        }
    }
}

#if DEBUG
#Preview {
    let sample = PreviewData.make()
    DayReportView()
        .environment(sample.model)
        .environment(sample.model.store)
        .environment(sample.model.settings)
        .modelContainer(sample.container)
}
#endif
