import Foundation
import KassaShared
@testable import KuechenbonCore

/// Fester Katalog für alle Tests: zwei Speisen, ein Getränk und die beiden
/// Artikel, die trotz Kategorie „Speisen" nie in die Küche gehören.
enum Katalog {
    static let krainer = ArticleDTO(id: "a-krainer", category: "Speisen", name: "Käsekrainer mit Gebäck", priceCents: 620, sortOrder: 1, active: true)
    static let bratwurst = ArticleDTO(id: "a-wurst", category: "Speisen", name: "Bratwurst mit Pommes", priceCents: 750, sortOrder: 2, active: true)
    static let bier = ArticleDTO(id: "a-bier", category: "Getränke", name: "Bier 0,5", priceCents: 480, sortOrder: 3, active: true)
    static let kaffee = ArticleDTO(id: "a-kaffee", category: "Speisen", name: "Kaffee", priceCents: 350, sortOrder: 4, active: true)
    static let mehlspeise = ArticleDTO(id: "a-mehl", category: "Speisen", name: "Mehlspeise", priceCents: 420, sortOrder: 5, active: true)

    static let alle: [String: ArticleDTO] = [
        krainer.id: krainer,
        bratwurst.id: bratwurst,
        bier.id: bier,
        kaffee.id: kaffee,
        mehlspeise.id: mehlspeise
    ]
}

let geraete = ["d-anna": "iPhone Anna", "d-bert": "iPad Bert"]

let testConfig = BonConfig(serverURL: "https://kassa.viennax.at", password: "geheim")

func wienerZeit(_ tag: Int, _ stunde: Int, _ minute: Int) -> Date {
    var kalender = Calendar(identifier: .gregorian)
    kalender.timeZone = TimeZone(identifier: "Europe/Vienna")!
    var teile = DateComponents()
    teile.year = 2025
    teile.month = 9
    teile.day = tag
    teile.hour = stunde
    teile.minute = minute
    return kalender.date(from: teile)!
}

func zeile(
    _ artikel: ArticleDTO,
    tisch: Int,
    qty: Int = 1,
    id: UUID = UUID(),
    createdAt: Date,
    deviceId: String = "d-anna",
    voidedAt: Date? = nil,
    settlementId: UUID? = nil,
    seq: Int = 1
) -> OrderLineDTO {
    OrderLineDTO(
        id: id,
        tableNumber: tisch,
        articleId: artikel.id,
        nameSnapshot: artikel.name,
        unitPriceCents: artikel.priceCents,
        qty: qty,
        createdAt: createdAt,
        deviceId: deviceId,
        voidedAt: voidedAt,
        settlementId: settlementId,
        updatedSeq: seq
    )
}

/// Kurzform: die Testfälle interessieren sich fast nie für Katalog und Geräte.
func planen(
    _ delta: [OrderLineDTO],
    state: PrintState = PrintState(),
    config: BonConfig = testConfig,
    now: Date,
    coldStart: Bool = false
) -> PlannerResult {
    BonPlanner.plan(
        delta: delta,
        catalog: Katalog.alle,
        deviceNames: geraete,
        state: state,
        config: config,
        now: now,
        coldStart: coldStart
    )
}

func druckauftrag(
    tisch: Int,
    id: UUID = UUID(),
    positionen: [PrintRequestDTO.Item] = [
        PrintRequestDTO.Item(name: "Käsekrainer mit Gebäck", qty: 2, unitPriceCents: 620)
    ],
    requestedAt: Date,
    deviceId: String = "d-anna",
    seq: Int = 1
) -> PrintRequestDTO {
    PrintRequestDTO(
        id: id,
        tableNumber: tisch,
        items: positionen,
        totalCents: positionen.reduce(0) { $0 + $1.unitPriceCents * $1.qty },
        requestedAt: requestedAt,
        deviceId: deviceId,
        updatedSeq: seq
    )
}

func planenAufstellungen(
    _ auftraege: [PrintRequestDTO],
    state: PrintState = PrintState(),
    config: BonConfig = testConfig,
    now: Date
) -> PlannerResult {
    BonPlanner.planOverviews(
        requests: auftraege,
        deviceNames: geraete,
        state: state,
        config: config,
        now: now
    )
}
