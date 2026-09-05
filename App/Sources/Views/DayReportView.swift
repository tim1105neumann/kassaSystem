import KassaShared
import SwiftUI

struct DayReportView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppSettings.self) private var settings
    @Environment(\.dismiss) private var dismiss

    @State private var selectedDate = Date.now
    @State private var report: DayReportDTO?
    @State private var isLoading = false
    @State private var errorMessage: String?

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
                        }
                    }
                }
            }
        }
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
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

#Preview {
    let sample = PreviewData.make()
    DayReportView()
        .environment(sample.model)
        .environment(sample.model.store)
        .environment(sample.model.settings)
        .modelContainer(sample.container)
}
