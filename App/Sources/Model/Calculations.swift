import Foundation
import KassaShared

// MARK: - Kategorien

/// Die CSV schreibt `Getraenke` ohne Umlaut — die UI zeigt trotzdem „Getränke“.
enum ArticleCategory: String, CaseIterable, Identifiable, Sendable {
    case speisen = "Speisen"
    case getraenke = "Getraenke"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .speisen: String(localized: "Speisen")
        case .getraenke: String(localized: "Getränke")
        }
    }
}

// MARK: - Summen

enum TableTotals {
    /// Nur offene Zeilen zählen: Storniertes und bereits Kassiertes gehört
    /// nicht mehr zur Tischsumme.
    static func openTotal(of lines: [LocalOrderLine]) -> Money {
        lines.reduce(Money.zero) { $1.isOpen ? $0 + $1.lineTotal : $0 }
    }

    static func openLines(of lines: [LocalOrderLine]) -> [LocalOrderLine] {
        lines.filter(\.isOpen).sorted { $0.createdAt < $1.createdAt }
    }

    /// Ältester offener Zeitstempel eines Tisches — daraus wird „seit 42 min“.
    static func openedAt(of lines: [LocalOrderLine]) -> Date? {
        lines.filter(\.isOpen).map(\.createdAt).min()
    }
}

// MARK: - Teilzahlung

/// Auswahl `Zeilen-ID -> Menge` für eine Teilzahlung.
struct SettlementSelection: Equatable, Sendable {
    private(set) var quantities: [UUID: Int] = [:]

    init(quantities: [UUID: Int] = [:]) {
        self.quantities = quantities.filter { $0.value > 0 }
    }

    func qty(for lineId: UUID) -> Int { quantities[lineId] ?? 0 }

    var isEmpty: Bool { quantities.isEmpty }

    mutating func set(_ qty: Int, for lineId: UUID, max maxQty: Int) {
        let clamped = min(max(qty, 0), maxQty)
        if clamped == 0 {
            quantities.removeValue(forKey: lineId)
        } else {
            quantities[lineId] = clamped
        }
    }

    static func all(of lines: [LocalOrderLine]) -> SettlementSelection {
        SettlementSelection(quantities: Dictionary(uniqueKeysWithValues: lines.map { ($0.id, $0.qty) }))
    }

    /// Zwischensumme der ausgewählten Mengen. Zeilen, die nicht mehr offen
    /// sind, zählen nicht mit.
    func subtotal(over lines: [LocalOrderLine]) -> Money {
        lines.reduce(Money.zero) { partial, line in
            guard line.isOpen, let qty = quantities[line.id] else { return partial }
            return partial + Money(cents: line.unitPriceCents * min(qty, line.qty))
        }
    }

    func requestLines(over lines: [LocalOrderLine]) -> [SettlementLineSelection] {
        lines.compactMap { line in
            guard line.isOpen, let qty = quantities[line.id], qty > 0 else { return nil }
            return SettlementLineSelection(lineId: line.id, qty: min(qty, line.qty))
        }
    }
}

// MARK: - Rückgeld

enum ChangeError: Error, Equatable {
    case notEnough(missing: Money)
}

enum ChangeCalculator {
    /// Rückgeld. Zu wenig Geld ist ein Fehler, kein negativer Betrag — sonst
    /// liest die Kellnerin im Stress ein Minus als Rückgeld.
    static func change(total: Money, given: Money) -> Result<Money, ChangeError> {
        guard given >= total else {
            return .failure(.notEnough(missing: total - given))
        }
        return .success(given - total)
    }

    /// Schnellwahl: passend, sinnvoll aufgerundete Beträge und die gängigen
    /// Scheine, die den Betrag decken.
    static func quickAmounts(for total: Money) -> [Money] {
        guard total.cents > 0 else { return [] }
        var candidates: Set<Int> = [total.cents]

        for step in [50, 100, 500, 1_000] {
            let rounded = ((total.cents + step - 1) / step) * step
            candidates.insert(rounded)
        }
        for note in [1_000, 2_000, 5_000, 10_000] where note >= total.cents {
            candidates.insert(note)
        }

        return candidates.sorted().prefix(6).map(Money.init(cents:))
    }
}
