import Foundation

/// Die einzige Stelle, an der Papier ins Spiel kommt. Als Protokoll, damit
/// der Probelauf (`--dry-run`) und die Tests dieselbe Dienstschleife benutzen.
///
/// Der Drucker bekommt den fertigen Bon und nicht schon gerenderte Bytes:
/// die ESC/POS-Fassung ist auf dem Bildschirm unlesbar, der Probelauf braucht
/// den Klartext.
public protocol BonPrinter: AnyObject {
    func print(_ job: BonJob, config: BonConfig) throws
}

public enum PrinterError: Error, LocalizedError {
    case lpNotStarted(String)
    case lpFailed(status: Int32, output: String)

    public var errorDescription: String? {
        switch self {
        case .lpNotStarted(let grund):
            return "/usr/bin/lp konnte nicht gestartet werden: \(grund)"
        case .lpFailed(let status, let ausgabe):
            let text = ausgabe.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty
                ? "lp ist mit Code \(status) fehlgeschlagen."
                : "lp ist mit Code \(status) fehlgeschlagen: \(text)"
        }
    }
}

/// Schiebt die ESC/POS-Bytes über `lp -o raw` in die CUPS-Queue. `raw` heißt:
/// CUPS reicht die Bytes unverändert weiter und versucht nicht, sie als Text
/// oder PDF zu deuten — genau das braucht ein ESC/POS-Drucker.
public final class CUPSPrinter: BonPrinter {
    private let queue: String

    public init(queue: String) {
        self.queue = queue
    }

    public func print(_ job: BonJob, config: BonConfig) throws {
        let daten = BonRenderer.escPos(job, config: config)

        let prozess = Process()
        prozess.executableURL = URL(fileURLWithPath: "/usr/bin/lp")
        prozess.arguments = ["-d", queue, "-o", "raw"]

        let eingabe = Pipe()
        let fehlerkanal = Pipe()
        prozess.standardInput = eingabe
        prozess.standardError = fehlerkanal
        // lp meldet auf stdout nur „request id is ...“ — das gehört nicht ins Log.
        prozess.standardOutput = FileHandle.nullDevice

        do {
            try prozess.run()
        } catch {
            throw PrinterError.lpNotStarted(error.localizedDescription)
        }

        eingabe.fileHandleForWriting.write(daten)
        eingabe.fileHandleForWriting.closeFile()

        // Erst stderr leerlesen, dann warten: bliebe die Pipe ungelesen und
        // liefe voll, würde lp beim Schreiben blockieren und nie beenden.
        let fehlertext = String(
            data: fehlerkanal.fileHandleForReading.readDataToEndOfFile(),
            encoding: .utf8
        ) ?? ""
        prozess.waitUntilExit()

        guard prozess.terminationStatus == 0 else {
            throw PrinterError.lpFailed(status: prozess.terminationStatus, output: fehlertext)
        }
    }
}

/// Probelauf: schreibt den Bon als Klartext auf die Standardausgabe, damit
/// sich der Dienst ohne Drucker gegen den echten Server prüfen lässt.
public final class DryRunPrinter: BonPrinter {
    public init() {}

    public func print(_ job: BonJob, config: BonConfig) throws {
        Swift.print(BonRenderer.plainText(job, config: config))
        Swift.print("")
    }
}
