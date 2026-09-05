import KassaShared
import Vapor

// Die DTOs liegen im Shared-Package und dürfen dort nicht von Vapor wissen.
// `Content` ist reine Vapor-Zutat und wird deshalb hier nachgereicht.
extension ArticleDTO: @retroactive Content {}
extension OrderLineDTO: @retroactive Content {}
extension SettlementDTO: @retroactive Content {}
extension SettlementConflictDTO: @retroactive Content {}
extension SyncResponse: @retroactive Content {}
extension LoginResponse: @retroactive Content {}
extension ServerConfigDTO: @retroactive Content {}
extension DayReportDTO: @retroactive Content {}
