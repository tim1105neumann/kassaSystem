import Fluent
import Vapor

/// Serialisiert alle schreibenden Transaktionen. Ein Actor allein reicht dafür
/// nicht: Actors sind an `await`-Punkten reentrant, die Transaktionen liefen
/// also doch verschränkt und der Sequenzzähler könnte zweimal denselben Wert
/// vergeben. SQLite ist ohnehin Single-Writer.
actor WriteLock {
    private var locked = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func run<T: Sendable>(_ body: @Sendable () async throws -> T) async rethrows -> T {
        while locked {
            await withCheckedContinuation { waiting.append($0) }
        }
        locked = true
        defer {
            locked = false
            if !waiting.isEmpty { waiting.removeFirst().resume() }
        }
        return try await body()
    }
}

extension Request {
    /// Eine synchronisierte Mutation: global serialisiert, in einer Transaktion,
    /// mit frischer Sequenznummer. Erst nach dem Commit erfahren die Clients davon.
    func mutate<T: Sendable>(
        _ body: @Sendable @escaping (any Database, Int) async throws -> T
    ) async throws -> T {
        let db = self.db
        let sockets = application.sockets
        return try await application.writeLock.run {
            let (result, seq) = try await db.transaction { tx -> (T, Int) in
                let state = try await ServerState.current(on: tx)
                state.seq += 1
                try await state.save(on: tx)
                return (try await body(tx, state.seq), state.seq)
            }
            await sockets.broadcast(seq: seq)
            return result
        }
    }
}
