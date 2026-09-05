import KassaShared
import Vapor

/// Hält die offenen WebSockets. Sie tragen keinen State — es geht nur darum,
/// nach jeder Mutation "es gibt Neues" zu rufen.
actor SocketHub {
    private var sockets: [UUID: WebSocket] = [:]

    func add(_ socket: WebSocket) -> UUID {
        let id = UUID()
        sockets[id] = socket
        return id
    }

    func remove(_ id: UUID) {
        sockets[id] = nil
    }

    var count: Int { sockets.count }

    func broadcast(seq: Int) async {
        guard !sockets.isEmpty,
              let data = try? KassaJSON.encoder.encode(SyncPing(seq: seq)),
              let text = String(data: data, encoding: .utf8)
        else { return }

        for (id, socket) in sockets {
            guard !socket.isClosed else {
                sockets[id] = nil
                continue
            }
            try? await socket.send(text)
        }
    }
}
