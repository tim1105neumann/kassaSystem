import KassaShared
import SwiftData
import SwiftUI

struct ArticlePickerView: View {
    let tableNumber: Int
    @Binding var path: NavigationPath

    @Environment(LocalStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @Query(filter: #Predicate<LocalArticle> { $0.active },
           sort: [SortDescriptor(\LocalArticle.sortOrder), SortDescriptor(\LocalArticle.name)])
    private var articles: [LocalArticle]

    @State private var category: ArticleCategory = .speisen
    /// Der laufende Vorgang: Artikel-ID -> Anzahl.
    @State private var draft: [String: Int] = [:]
    /// Zweite flache Map statt `draft` auf Tupel: die Anzahl ändert sich bei
    /// jedem Tipp auf die Kachel, die Notiz nur im Sheet.
    @State private var notes: [String: String] = [:]
    /// Artikel, dessen Notiz gerade bearbeitet wird.
    @State private var noteArticle: LocalArticle?

    private var visible: [LocalArticle] {
        articles.filter { $0.category == category.rawValue }
    }

    private var draftTotal: Money {
        articles.reduce(Money.zero) { partial, article in
            guard let qty = draft[article.id], qty > 0 else { return partial }
            return partial + article.price * qty
        }
    }

    private let columns = [GridItem(.adaptive(minimum: 150, maximum: 260), spacing: 12)]

    var body: some View {
        VStack(spacing: 0) {
            Picker("Kategorie", selection: $category) {
                ForEach(ArticleCategory.allCases) { value in
                    Text(value.title).tag(value)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if visible.isEmpty {
                ContentUnavailableView(
                    "Kein Katalog geladen",
                    systemImage: "wifi.exclamationmark",
                    description: Text("Die Artikelliste kommt vom Server. Bitte Verbindung prüfen.")
                )
                .frame(maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(visible) { article in
                            ArticleButton(
                                article: article,
                                count: draft[article.id] ?? 0,
                                note: notes[article.id]
                            ) {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                draft[article.id, default: 0] += 1
                            } onDecrement: {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                let next = (draft[article.id] ?? 0) - 1
                                if next <= 0 {
                                    draft[article.id] = nil
                                    // Sonst taucht die Notiz beim Wiederantippen
                                    // als Geist eines abgewählten Artikels auf.
                                    notes[article.id] = nil
                                } else {
                                    draft[article.id] = next
                                }
                            } onEditNote: {
                                noteArticle = article
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
            }

            bottomBar
        }
        .navigationTitle("Tisch \(tableNumber)")
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(.systemGroupedBackground))
        .sheet(item: $noteArticle) { article in
            NoteSheet(articleName: article.name, note: notes[article.id] ?? "") { text in
                notes[article.id] = text.isEmpty ? nil : text
            }
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 12) {
            HStack {
                Text("Vorgang")
                    .font(.title3.weight(.semibold))
                Spacer()
                Text(draftTotal.formatted)
                    .font(.system(size: 34, weight: .heavy, design: .rounded))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Summe dieses Vorgangs \(draftTotal.formatted)")

            Button {
                book()
            } label: {
                Text("Buchen")
                    .font(.title3.weight(.bold))
                    .frame(maxWidth: .infinity, minHeight: 60)
            }
            .buttonStyle(.borderedProminent)
            .disabled(draft.isEmpty)
        }
        .padding(16)
        .background(.bar)
    }

    private func book() {
        let items = articles.compactMap { article -> BookingItem? in
            guard let qty = draft[article.id], qty > 0 else { return nil }
            return BookingItem(article: article, qty: qty, note: notes[article.id])
        }
        guard !items.isEmpty else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        store.addLines(tableNumber: tableNumber, items: items)
        draft.removeAll()
        notes.removeAll()
        dismiss()
    }
}

private struct ArticleButton: View {
    let article: LocalArticle
    let count: Int
    let note: String?
    let onIncrement: () -> Void
    let onDecrement: () -> Void
    let onEditNote: () -> Void

    var body: some View {
        // Zwei getrennte Knöpfe in einem Stapel: ein Tap-Bereich innerhalb eines
        // Button-Labels wird nicht zuverlässig vor dem Button selbst erkannt.
        VStack(alignment: .leading, spacing: 6) {
            Button(action: onIncrement) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(article.name)
                        .font(.headline)
                        .multilineTextAlignment(.leading)
                        .lineLimit(3)
                        .minimumScaleFactor(0.7)
                        .foregroundStyle(count > 0 ? Color.white : Color.primary)
                        .padding(.trailing, count > 0 ? 44 : 0)
                    Spacer(minLength: 4)
                    Text(article.price.formatted)
                        .font(.title3.weight(.bold).monospacedDigit())
                        .foregroundStyle(count > 0 ? Color.white : Color.secondary)
                }
                // Eigene Mindesthöhe, damit die Notizzeile die Kachel wachsen
                // lässt statt Name und Preis zusammenzudrücken.
                .frame(maxWidth: .infinity, minHeight: 108, maxHeight: .infinity, alignment: .topLeading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)

            if count > 0 { noteButton }
        }
        .frame(maxWidth: .infinity, minHeight: 108, alignment: .topLeading)
        .padding(14)
        .background(
            count > 0 ? Color.accentColor : Color(.secondarySystemGroupedBackground),
            in: .rect(cornerRadius: 16)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(count > 0 ? Color.clear : Color(.separator), lineWidth: 1)
        }
        .overlay(alignment: .topTrailing) {
            if count > 0 {
                Text("\(count)")
                    .font(.title3.bold().monospacedDigit())
                    .foregroundStyle(Color.accentColor)
                    .frame(minWidth: 36, minHeight: 36)
                    .background(.white, in: .circle)
                    .padding(8)
                    .allowsHitTesting(false)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if count > 0 {
                Button(action: onDecrement) {
                    Image(systemName: "minus")
                        .font(.headline.bold())
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 44, height: 44)
                        .background(.white, in: .circle)
                }
                .buttonStyle(.plain)
                .padding(8)
                .accessibilityLabel("Ein \(article.name) weniger")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(accessibilityText)
    }

    /// Eigene Zeile statt eines zweiten runden Knopfes: oben rechts sitzt das
    /// Zähler-Abzeichen, unten rechts das Minus — neben zwei Kreisen bliebe auf
    /// einer 150pt-Kachel kein Platz für den Preis.
    private var noteButton: some View {
        Button(action: onEditNote) {
            HStack(spacing: 4) {
                Image(systemName: "pencil")
                    .font(.footnote.weight(.semibold))
                Text(note ?? String(localized: "Notiz"))
                    .font(.footnote)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .foregroundStyle(note == nil ? Color.white.opacity(0.75) : Color.white)
            // Läuft neben dem Minus-Overlay frei vorbei.
            .padding(.trailing, 50)
            .frame(maxWidth: .infinity, minHeight: 30, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            note == nil
                ? String(localized: "Notiz für \(article.name) erfassen")
                : String(localized: "Notiz für \(article.name) ändern, \(note ?? "")")
        )
    }

    private var accessibilityText: String {
        guard count > 0 else {
            return String(localized: "\(article.name), \(article.price.formatted)")
        }
        guard let note else {
            return String(localized: "\(article.name), \(article.price.formatted), \(count) gewählt")
        }
        return String(localized: "\(article.name), \(article.price.formatted), \(count) gewählt, Notiz \(note)")
    }
}

/// Notizblatt: freies Feld plus Vorlagen. Feste Reihenfolge, wie die Wünsche an
/// der Bude vorkommen.
private let noteTemplates = [
    String(localized: "ohne Senf"),
    String(localized: "extra Senf"),
    String(localized: "ohne Ketchup"),
    String(localized: "extra Ketchup"),
    String(localized: "ohne Zwiebel"),
    String(localized: "scharf")
]

private struct NoteSheet: View {
    let articleName: String
    let onDone: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var text: String
    /// Nur zum Wegräumen der Tastatur, wenn ein Chip getippt wird — das Blatt
    /// öffnet bewusst ohne Tastatur, sonst verdeckt sie die Vorlagen.
    @FocusState private var focused: Bool

    init(articleName: String, note: String, onDone: @escaping (String) -> Void) {
        self.articleName = articleName
        self.onDone = onDone
        _text = State(initialValue: note)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    TextField(String(localized: "z. B. ohne Senf"), text: $text)
                        .font(.system(size: 28, weight: .semibold, design: .rounded))
                        .textFieldStyle(.roundedBorder)
                        .focused($focused)
                        // Einzeilig: der Server entfernt Zeilenumbrüche ohnehin,
                        // und auf dem Bon steht die Notiz in einer Zeile.
                        .submitLabel(.done)
                        .onSubmit { finish() }
                        .onChange(of: text) { _, new in
                            if new.count > OrderNote.maxLength {
                                text = String(new.prefix(OrderNote.maxLength))
                            }
                        }
                        .accessibilityLabel("Notiz zu \(articleName)")

                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 10)], spacing: 10) {
                        ForEach(noteTemplates, id: \.self) { template in
                            let isActive = noteContains(text, template)
                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                text = toggleNote(text, template)
                                focused = false
                            } label: {
                                Text(template)
                                    .font(.headline)
                                    .frame(maxWidth: .infinity, minHeight: 52)
                            }
                            .buttonStyle(.bordered)
                            .tint(isActive ? Color.accentColor : nil)
                            .accessibilityAddTraits(isActive ? .isSelected : [])
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle(articleName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fertig") { finish() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func finish() {
        onDone(text.trimmingCharacters(in: .whitespacesAndNewlines))
        dismiss()
    }
}

/// Vorlagen hängen sich kommagetrennt an, ein zweiter Tipp nimmt sie wieder weg.
private func toggleNote(_ note: String, _ template: String) -> String {
    let parts = noteParts(note)
    let next = parts.contains { $0.caseInsensitiveCompare(template) == .orderedSame }
        ? parts.filter { $0.caseInsensitiveCompare(template) != .orderedSame }
        : parts + [template]
    return String(next.joined(separator: ", ").prefix(OrderNote.maxLength))
}

private func noteContains(_ note: String, _ template: String) -> Bool {
    noteParts(note).contains { $0.caseInsensitiveCompare(template) == .orderedSame }
}

private func noteParts(_ note: String) -> [String] {
    note.split(separator: ",")
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty }
}

#if DEBUG
#Preview {
    let sample = PreviewData.make()
    NavigationStack {
        ArticlePickerView(tableNumber: 3, path: .constant(NavigationPath()))
            .environment(sample.model)
            .environment(sample.model.store)
            .environment(sample.model.settings)
            .modelContainer(sample.container)
    }
}
#endif
