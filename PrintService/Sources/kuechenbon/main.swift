import Foundation
import KuechenbonCore

// Kein async/await, kein Task, kein Actor — das Binary läuft auf macOS 11,
// wo die Nebenläufigkeits-Bibliothek für ein nacktes Kommandozeilenprogramm
// nicht verfügbar ist (siehe Kommentar in build-release.sh).

/// Wird aus dem Signal-Handler beschrieben, deshalb `sig_atomic_t`. Die
/// Variable steht bewusst in main.swift: Globale hier werden beim Start der
/// Reihe nach angelegt, nicht erst beim ersten Zugriff — ein Signal-Handler
/// darf keine verzögerte Initialisierung auslösen.
nonisolated(unsafe) var beendenAngefordert: sig_atomic_t = 0

// MARK: - Pfade

/// Konfiguration und Zustand liegen neben dem Binary. `argv[0]` kann relativ
/// sein (`./kuechenbon`), deshalb erst am Arbeitsverzeichnis auflösen.
func programmVerzeichnis() -> String {
    let arbeitsverzeichnis = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
    let programm = URL(fileURLWithPath: CommandLine.arguments[0], relativeTo: arbeitsverzeichnis)
    return programm.resolvingSymlinksInPath().deletingLastPathComponent().path
}

func hilfe() -> String {
    """
    Küchenbon-Dienst

      kuechenbon run                 Dienst starten (Endlosschleife, für launchd)
      kuechenbon once [--dry-run]    Genau einen Durchlauf, danach Ende
      kuechenbon testbon [--dry-run] Beispiel-Bon drucken (Zeichensatz und Breite prüfen)

      --config <pfad>                Andere Konfigurationsdatei als config.json neben dem Binary
      --dry-run                      Nicht drucken, Klartext auf den Bildschirm, Zustand bleibt unverändert
    """
}

// MARK: - Ablauf

func hauptprogramm() -> Int32 {
    let argumente = Array(CommandLine.arguments.dropFirst())
    guard let befehl = argumente.first, ["run", "once", "testbon"].contains(befehl) else {
        FileHandle.standardError.write(Data((hilfe() + "\n").utf8))
        return 2
    }

    let trockenlauf = argumente.contains("--dry-run")
    if trockenlauf, befehl == "run" {
        FileHandle.standardError.write(Data("--dry-run gibt es nur für once und testbon.\n".utf8))
        return 2
    }

    let verzeichnis = programmVerzeichnis()
    var configPfad = verzeichnis + "/config.json"
    if let stelle = argumente.firstIndex(of: "--config") {
        guard stelle + 1 < argumente.count else {
            FileHandle.standardError.write(Data("--config braucht einen Pfad.\n".utf8))
            return 2
        }
        configPfad = argumente[stelle + 1]
    }

    guard FileManager.default.fileExists(atPath: configPfad) else {
        FileHandle.standardError.write(Data("""
            Keine Konfiguration gefunden.
            Erwartet wird die Datei: \(configPfad)
            Ein Beispiel steht in der Installationsanleitung (PrintService/README.md, Abschnitt 2).

            """.utf8))
        return 1
    }

    let config: BonConfig
    do {
        config = try BonConfig.load(from: configPfad)
    } catch {
        FileHandle.standardError.write(Data("""
            Die Konfigurationsdatei \(configPfad) konnte nicht gelesen werden.
            Grund: \(error.localizedDescription)
            Meist fehlt ein Komma oder eine Anführungszeichen-Klammer im JSON.

            """.utf8))
        return 1
    }

    // `once` und `testbon` ruft ein Mensch auf und will die Ausgabe sehen — auch
    // dann, wenn er sie in eine Datei umleitet. Nur der Dauerbetrieb spiegelt
    // ausschliesslich im Terminal: unter launchd zeigt stdout auf dieselbe Datei,
    // in die der Logger schreibt, dort stuende sonst jede Zeile doppelt.
    let spiegeln = befehl == "run" ? isatty(STDOUT_FILENO) != 0 : true
    let log = Logger(path: verzeichnis + "/kuechenbon.log", mirrorToStdout: spiegeln)

    if befehl == "testbon" {
        return probebon(config: config, trockenlauf: trockenlauf, log: log)
    }

    let statePfad = verzeichnis + "/state.json"
    let state: PrintState
    do {
        state = try PrintState.load(from: statePfad)
    } catch {
        FileHandle.standardError.write(Data("""
            Die Zustandsdatei \(statePfad) ist beschädigt und wurde nicht angetastet.
            Grund: \(error.localizedDescription)
            Sie kann gelöscht werden — danach beginnt die Bon-Nummerierung von vorne,
            und bereits bestehende Bestellungen werden nicht nachgedruckt.

            """.utf8))
        return 1
    }

    let client: HTTPClient
    do {
        client = try HTTPClient(config: config, token: state.token, deviceId: state.deviceId)
    } catch {
        FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8))
        return 1
    }

    let dienst = Service(
        config: config,
        state: state,
        // Der Probelauf darf nichts hinterlassen: sonst gälten die dabei
        // „gedruckten“ Bons als erledigt und kämen nie aufs Papier.
        statePath: trockenlauf ? nil : statePfad,
        server: client,
        printer: trockenlauf ? DryRunPrinter() : CUPSPrinter(queue: config.printerQueue),
        log: log
    )

    if befehl == "once" {
        return dienst.runOnce() ? 0 : 1
    }
    return dauerlauf(dienst: dienst, config: config, log: log)
}

func dauerlauf(dienst: Service, config: BonConfig, log: Logger) -> Int32 {
    // launchd beendet den Dienst mit SIGTERM, ein Strg+C im Terminal mit SIGINT.
    // Beide dürfen nicht mitten im Druck abbrechen, deshalb nur ein Merker.
    signal(SIGTERM) { _ in beendenAngefordert = 1 }
    signal(SIGINT) { _ in beendenAngefordert = 1 }

    log.info(
        "Küchenbon-Dienst gestartet — Server \(config.serverURL), Queue \(config.printerQueue), "
        + "Abfrage alle \(config.pollIntervalSeconds) Sekunden."
    )

    while beendenAngefordert == 0 {
        dienst.runOnce()
        // In Scheiben schlafen, damit das Beenden nicht bis zum Ende des
        // Intervalls hängt. `max` fängt eine 0 in der Konfigurationsdatei ab,
        // die sonst zur Dauerabfrage gegen den Server würde.
        var rest = max(0.5, config.pollIntervalSeconds)
        while rest > 0, beendenAngefordert == 0 {
            let scheibe = min(0.2, rest)
            Thread.sleep(forTimeInterval: scheibe)
            rest -= scheibe
        }
    }

    log.info("Küchenbon-Dienst beendet.")
    return 0
}

/// Beispiel-Bon mit Umlauten und ß: daran lässt sich auf Papier ablesen, ob
/// Zeichensatz, Breite und Abschnitt stimmen.
func probebon(config: BonConfig, trockenlauf: Bool, log: Logger) -> Int32 {
    let job = BonJob(
        kind: .order,
        tableNumber: 7,
        bonNumber: 0,
        time: Date(),
        deviceName: "Probedruck",
        items: [
            BonJob.Item(qty: 2, name: "Käsekrainer mit Gebäck"),
            BonJob.Item(qty: 1, name: "Weißwurst süß"),
            BonJob.Item(qty: 3, name: "Gebäck")
        ]
    )

    let drucker: BonPrinter = trockenlauf ? DryRunPrinter() : CUPSPrinter(queue: config.printerQueue)
    do {
        try drucker.print(job, config: config)
    } catch {
        log.error((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        return 1
    }
    if !trockenlauf {
        log.info("Probebon an die Queue \(config.printerQueue) geschickt.")
    }
    return 0
}

exit(hauptprogramm())
