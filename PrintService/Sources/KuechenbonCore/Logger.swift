import Foundation

/// Der Dienst schreibt sein eigenes Logfile, statt sich auf die Umleitung
/// durch launchd zu verlassen.
///
/// Grund: launchd öffnet die Datei aus `StandardOutPath` einmal beim Start und
/// hält sie die ganze Betriebszeit über offen. Ein Rotationswerkzeug von außen
/// (`newsyslog`) kann sie zwar wegschieben, aber launchd schreibt danach weiter
/// in die weggeschobene Datei — die neue bliebe leer, und der Platz würde nie
/// frei. Wer die Datei selbst schreibt, kann sie auch selbst rotieren.
public final class Logger {
    public enum Level: String {
        case info = "INFO"
        case warn = "WARN"
        case error = "FEHLER"
    }

    private let path: String
    private let maxBytes: Int
    private let mirrorToStdout: Bool
    private let formatter: DateFormatter

    /// - Parameter mirrorToStdout: Für `once` und `testbon` im Terminal. Unter
    ///   launchd zeigt stdout auf dieselbe Datei — dann muss die Spiegelung aus
    ///   sein, sonst steht jede Zeile doppelt darin.
    public init(path: String, maxBytes: Int = 1_048_576, mirrorToStdout: Bool) {
        self.path = path
        self.maxBytes = maxBytes
        self.mirrorToStdout = mirrorToStdout

        formatter = DateFormatter()
        formatter.locale = Locale(identifier: "de_AT")
        formatter.timeZone = TimeZone(identifier: "Europe/Vienna")
        formatter.dateFormat = "dd.MM.yyyy HH:mm:ss"
    }

    public func info(_ text: String) { schreibe(.info, text) }
    public func warn(_ text: String) { schreibe(.warn, text) }
    public func error(_ text: String) { schreibe(.error, text) }

    private func schreibe(_ level: Level, _ text: String) {
        let zeile = "\(formatter.string(from: Date())) [\(level.rawValue)] \(text)\n"

        if mirrorToStdout {
            Swift.print(zeile, terminator: "")
            // Auf eine Pipe ist stdout blockgepuffert; ohne das sähe man beim
            // Zuschauen minutenlang nichts.
            fflush(stdout)
        }

        guard let daten = zeile.data(using: .utf8) else { return }
        rotiereFallsNoetig(zusatz: daten.count)
        haengeAn(daten)
    }

    /// Es wird genau eine Altdatei aufgehoben: die Küche braucht das Log zur
    /// Fehlersuche am selben Abend, nicht als Archiv.
    private func rotiereFallsNoetig(zusatz: Int) {
        let dateien = FileManager.default
        guard let groesse = (try? dateien.attributesOfItem(atPath: path)[.size]) as? Int,
              groesse + zusatz > maxBytes else { return }

        let alt = path + ".1"
        try? dateien.removeItem(atPath: alt)
        try? dateien.moveItem(atPath: path, toPath: alt)
    }

    private func haengeAn(_ daten: Data) {
        let dateien = FileManager.default
        if !dateien.fileExists(atPath: path) {
            dateien.createFile(atPath: path, contents: nil)
        }
        guard let griff = FileHandle(forWritingAtPath: path) else { return }
        griff.seekToEndOfFile()
        griff.write(daten)
        griff.closeFile()
    }
}
