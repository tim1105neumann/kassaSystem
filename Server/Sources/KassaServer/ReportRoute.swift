import Fluent
import Foundation
import KassaShared
import Vapor

@Sendable
func dayReport(request: Request) async throws -> DayReportDTO {
    let db = request.db
    let day = request.query[String.self, at: "date"]
        ?? BusinessDay.day(for: Date(), cutoffHour: request.kassa.cutoffHour)

    let settlements = try await Settlement.query(on: db)
        .filter(\.$businessDay == day)
        .sort(\.$paidAt)
        .all()

    let settlementIDs = try settlements.map { try $0.requireID() }
    let lines: [OrderLine] = settlementIDs.isEmpty ? [] : try await OrderLine.query(on: db)
        .filter(\.$settlementId ~~ settlementIDs.map { $0 as UUID? })
        .all()

    // Der Katalog hat 32 Zeilen — ein Join lohnt sich dafür nicht.
    let articles = try await Article.query(on: db).all()
    var categoryOf: [String: String] = [:]
    for article in articles { categoryOf[try article.requireID()] = article.category }

    var byCategory: [String: Int] = [:]
    var qtyByArticle: [String: Int] = [:]
    var centsByArticle: [String: Int] = [:]
    var nameByArticle: [String: String] = [:]

    for line in lines {
        let cents = line.unitPriceCents * line.qty
        byCategory[categoryOf[line.articleId] ?? "Unbekannt", default: 0] += cents
        qtyByArticle[line.articleId, default: 0] += line.qty
        centsByArticle[line.articleId, default: 0] += cents
        nameByArticle[line.articleId] = line.nameSnapshot
    }

    let topArticles = qtyByArticle
        .map { articleId, qty in
            DayReportDTO.ArticleTotal(
                articleId: articleId,
                name: nameByArticle[articleId] ?? articleId,
                qty: qty,
                totalCents: centsByArticle[articleId] ?? 0
            )
        }
        .sorted { ($0.qty, $1.name) > ($1.qty, $0.name) }

    return DayReportDTO(
        businessDay: day,
        totalCents: settlements.reduce(0) { $0 + $1.totalCents },
        settlementCount: settlements.count,
        byCategory: byCategory
            .map { DayReportDTO.CategoryTotal(category: $0.key, totalCents: $0.value) }
            .sorted { $0.category < $1.category },
        topArticles: topArticles,
        settlements: try settlements.map { try $0.dto() }
    )
}
