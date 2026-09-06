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
                            ArticleButton(article: article, count: draft[article.id] ?? 0) {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                draft[article.id, default: 0] += 1
                            } onDecrement: {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                let next = (draft[article.id] ?? 0) - 1
                                if next <= 0 { draft[article.id] = nil } else { draft[article.id] = next }
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
        let items = articles.compactMap { article -> (article: LocalArticle, qty: Int)? in
            guard let qty = draft[article.id], qty > 0 else { return nil }
            return (article, qty)
        }
        guard !items.isEmpty else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        store.addLines(tableNumber: tableNumber, items: items)
        draft.removeAll()
        dismiss()
    }
}

private struct ArticleButton: View {
    let article: LocalArticle
    let count: Int
    let onIncrement: () -> Void
    let onDecrement: () -> Void

    var body: some View {
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
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
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
        .accessibilityLabel(
            count > 0
                ? String(localized: "\(article.name), \(article.price.formatted), \(count) gewählt")
                : String(localized: "\(article.name), \(article.price.formatted)")
        )
    }
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
