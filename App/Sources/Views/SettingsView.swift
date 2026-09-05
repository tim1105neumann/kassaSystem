import KassaShared
import SwiftData
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppSettings.self) private var settings
    @Environment(LocalStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @Query(sort: \PendingCommand.createdAt) private var commands: [PendingCommand]

    @State private var password = ""
    @State private var showLogoutConfirmation = false

    var body: some View {
        @Bindable var settings = settings

        NavigationStack {
            Form {
                Section("Server") {
                    TextField("https://kassa.example.at", text: $settings.serverURLString)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .onSubmit { Task { await model.applyServerURLChange() } }
                    TextField("Gerätename", text: $settings.deviceName)
                        .textInputAutocapitalization(.words)
                }

                if settings.isLoggedIn {
                    Section("Verbindung") {
                        LabeledContent("Status", value: statusText)
                        if let lastSync = store.lastSyncAt {
                            LabeledContent("Letzter Abgleich", value: lastSync.formatted(date: .omitted, time: .standard))
                        }
                        if let error = store.lastErrorMessage {
                            Text(error)
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                        Button("Jetzt abgleichen") { model.syncSoon() }
                    }
                } else {
                    Section("Anmeldung") {
                        SecureField("Passwort", text: $password)
                        Button("Anmelden") {
                            Task { await model.login(password: password) }
                        }
                        .disabled(password.isEmpty || model.isLoggingIn)
                        if let error = model.loginError {
                            Text(error).foregroundStyle(.red)
                        }
                    }
                }

                queueSection

                if settings.isLoggedIn {
                    Section {
                        Button("Abmelden", role: .destructive) {
                            showLogoutConfirmation = true
                        }
                        // Abmelden loescht den lokalen Speicher. Solange Buchungen
                        // noch nicht am Server sind, waere das echter Datenverlust —
                        // deshalb gesperrt statt nur mit Warnhinweis versehen.
                        .disabled(store.openPendingCount > 0)
                    } footer: {
                        if store.openPendingCount > 0 {
                            Text("Abmelden ist gesperrt: \(pendingPhrase) noch nicht am Server. Sobald wieder Netz da ist, wird das automatisch übertragen.")
                        } else {
                            Text("Beim Abmelden werden die lokalen Daten von diesem Gerät gelöscht. Am Server bleibt alles erhalten.")
                        }
                    }
                }

                Section {
                    Text("Diese App ist eine Rechenhilfe und keine registrierte Kassa im Sinne der RKSV. Kein Belegdruck, keine Signatureinheit.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Einstellungen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fertig") { dismiss() }
                }
            }
            .confirmationDialog(
                "Wirklich abmelden?",
                isPresented: $showLogoutConfirmation,
                titleVisibility: .visible
            ) {
                Button("Abmelden", role: .destructive) {
                    Task {
                        await model.logout()
                        dismiss()
                    }
                }
                Button("Abbrechen", role: .cancel) {}
            } message: {
                Text("Die lokalen Daten werden von diesem Gerät gelöscht. Am Server bleibt alles erhalten.")
            }
        }
    }

    /// "eine Buchung ist" / "3 Buchungen sind" — damit der Satz im Fusstext stimmt.
    private var pendingPhrase: String {
        let count = store.openPendingCount
        return count == 1 ? "eine Buchung ist" : "\(count) Buchungen sind"
    }

    private var queueSection: some View {
        Section {
            if commands.isEmpty {
                Text("Keine ausstehenden Buchungen.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(commands) { command in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(label(for: command))
                                .font(.headline)
                            Spacer()
                            if command.failedPermanently {
                                Label("fehlgeschlagen", systemImage: "xmark.octagon.fill")
                                    .labelStyle(.iconOnly)
                                    .foregroundStyle(.red)
                            }
                        }
                        Text(command.createdAt.formatted(date: .abbreviated, time: .standard))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if let error = command.lastError {
                            Text("\(command.attemptCount) Versuche · \(error)")
                                .font(.caption)
                                .foregroundStyle(.red)
                        }
                    }
                    .swipeActions {
                        Button("Verwerfen", role: .destructive) {
                            model.discardCommand(id: command.id)
                        }
                    }
                }
            }
        } header: {
            Text("Offline-Warteschlange")
        } footer: {
            Text("Dauerhaft fehlgeschlagene Buchungen blockieren die Warteschlange nicht. Sie können hier verworfen werden — die Buchung ist dann endgültig verloren.")
        }
    }

    private func label(for command: PendingCommand) -> String {
        switch command.kind {
        case .addLines: String(localized: "Buchung")
        case .voidLine: String(localized: "Storno")
        case .settle: String(localized: "Kassiervorgang")
        case .none: String(localized: "Unbekannt")
        }
    }

    private var statusText: String {
        switch store.connection {
        case .connected: String(localized: "Verbunden")
        case .syncing: String(localized: "Wird abgeglichen")
        case .offline: String(localized: "Offline")
        case .needsLogin: String(localized: "Nicht angemeldet")
        }
    }
}

#Preview {
    let sample = PreviewData.make()
    SettingsView()
        .environment(sample.model)
        .environment(sample.model.store)
        .environment(sample.model.settings)
        .modelContainer(sample.container)
}
