import SwiftUI

struct LoginView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppSettings.self) private var settings
    @State private var password = ""

    var body: some View {
        @Bindable var settings = settings

        Form {
            Section("Server") {
                TextField("https://kassa.example.at", text: $settings.serverURLString)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                TextField("Gerätename", text: $settings.deviceName)
                    .textInputAutocapitalization(.words)
            }

            Section("Anmeldung") {
                SecureField("Passwort", text: $password)
                Button {
                    Task { await model.login(password: password) }
                } label: {
                    Text("Anmelden")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                .disabled(password.isEmpty || model.isLoggingIn)
            }

            if let error = model.loginError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                }
            }
        }
    }
}

#if DEBUG
#Preview {
    let sample = PreviewData.make()
    NavigationStack {
        LoginView()
            .environment(sample.model)
            .environment(sample.model.settings)
    }
}
#endif
