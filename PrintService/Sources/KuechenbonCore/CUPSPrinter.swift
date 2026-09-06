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
    case queueGestoppt(queue: String, grund: String)

    public var errorDescription: String? {
        switch self {
        case .lpNotStarted(let grund):
            return "/usr/bin/lp konnte nicht gestartet werden: \(grund)"
        case .lpFailed(let status, let ausgabe):
            let text = ausgabe.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty
                ? "lp ist mit Code \(status) fehlgeschlagen."
                : "lp ist mit Code \(status) fehlgeschlagen: \(text)"
        case .queueGestoppt(let queue, let grund):
            return """
                Die Druckerwarteschlange \(queue) ist angehalten (\(grund)). \
                Es wird nichts gedruckt. Wieder freigeben mit: sudo cupsenable \(queue)
                """
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

    /// IPP-Zustand 5 heisst „stopped“. Die Zahlen kommen aus dem Protokoll und
    /// sind sprachunabhaengig — die Klartextmeldungen von `lpstat` sind es nicht.
    /// Gibt den Grund zurueck, wenn die Warteschlange steht, sonst `nil`.
    public static func gestoppt(lpoptionsAusgabe: String) -> String? {
        let felder = lpoptionsAusgabe.split(whereSeparator: { $0 == " " || $0 == "\n" })
        guard felder.contains(where: { $0 == "printer-state=5" }) else { return nil }
        let grund = felder
            .first { $0.hasPrefix("printer-state-reasons=") }?
            .dropFirst("printer-state-reasons=".count)
        return grund.map(String.init) ?? "Grund unbekannt"
    }

    /// Fragt CUPS nach dem Zustand der Warteschlange.
    private func gestoppterGrund() -> String? {
        let prozess = Process()
        prozess.executableURL = URL(fileURLWithPath: "/usr/bin/lpoptions")
        prozess.arguments = ["-p", queue]
        let ausgabe = Pipe()
        prozess.standardOutput = ausgabe
        prozess.standardError = FileHandle.nullDevice
        guard (try? prozess.run()) != nil else { return nil }
        let daten = ausgabe.fileHandleForReading.readDataToEndOfFile()
        prozess.waitUntilExit()
        return Self.gestoppt(lpoptionsAusgabe: String(data: daten, encoding: .utf8) ?? "")
    }

    public func print(_ job: BonJob, config: BonConfig) throws {
        // `lp` meldet Erfolg, sobald der Auftrag eingereiht ist — nicht, wenn
        // Papier herauskommt. Haelt CUPS die Warteschlange nach einem Fehler an
        // (Standardverhalten), verschwänden Bons sonst lautlos: der Dienst
        // vermerkt sie als gedruckt, die Kueche sieht nie einen Zettel.
        if let grund = gestoppterGrund() {
            throw PrinterError.queueGestoppt(queue: queue, grund: grund)
        }

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
