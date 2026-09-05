import KassaShared
import SwiftData
import SwiftUI

enum Route: Hashable {
    case table(Int)
    case articles(Int)
    case settle(Int)
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppSettings.self) private var settings
    @State private var path = NavigationPath()
    @State private var showsSettings = false
    @State private var showsDayReport = false

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if settings.isLoggedIn {
                    TableGridView(path: $path)
                } else {
                    LoginView()
                }
            }
            .navigationTitle(settings.isLoggedIn ? "Tische" : "Anmelden")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showsDayReport = true
                    } label: {
                        Label("Tagesabschluss", systemImage: "chart.bar.doc.horizontal")
                    }
                    .disabled(!settings.isLoggedIn)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showsSettings = true
                    } label: {
                        Label("Einstellungen", systemImage: "gearshape")
                    }
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .table(let number):
                    TableDetailView(tableNumber: number, path: $path)
                case .articles(let number):
                    ArticlePickerView(tableNumber: number, path: $path)
                case .settle(let number):
                    SettlementView(tableNumber: number, path: $path)
                }
            }
        }
        .sheet(isPresented: $showsSettings) { SettingsView() }
        .sheet(isPresented: $showsDayReport) { DayReportView() }
    }
}

#Preview {
    let sample = PreviewData.make()
    RootView()
        .environment(sample.model)
        .environment(sample.model.store)
        .environment(sample.model.settings)
        .modelContainer(sample.container)
}
