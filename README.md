# Kassasystem Würstelheuriger

Eine iPhone-App plus kleiner Server, um bei einem Würstelheurigen zu kassieren:
Bestellungen auf mehrere Tische buchen, Summen automatisch rechnen, Rechnungen
teilen, Rückgeld ermitteln und am Ende des Abends den Umsatz sehen.

---

## ⚠️ Rechtlicher Hinweis — bitte lesen

**Dieses System ist eine Rechenhilfe, keine registrierte Kassa.**

In Österreich gilt die Registrierkassenpflicht ab 15.000 € Jahresumsatz, wenn
davon mehr als 7.500 € bar vereinnahmt werden (§ 131b BAO). Eine
gesetzeskonforme Registrierkasse braucht eine Signaturerstellungseinheit, ein
manipulationssicheres Datenerfassungsprotokoll und eine Registrierung über
FinanzOnline.

**Nichts davon leistet dieses System.** Es erzeugt keine signierten Belege,
kein revisionssicheres Journal und ist nicht bei der Finanz registriert. Es
ersetzt keine registrierte Kassa und keine Belegerteilung. Wer der
Registrierkassenpflicht unterliegt, braucht zusätzlich eine zertifizierte
Lösung.

---

## Was es kann

- **Mehrere Tische gleichzeitig** — Standard 40, Anzahl am Server einstellbar
- **Mehrere Geräte auf denselben Tischen** — alle sehen denselben Stand
- **Offline weiterarbeiten** — bei Funklöchern wird lokal gebucht und später
  automatisch abgeglichen
- **Rechnung teilen** — einzelne Positionen und Mengen separat kassieren
- **Rückgeld-Rechner** — mit Schnellwahl für gängige Scheine
- **Tagesabschluss** — Umsatz gesamt, je Kategorie, meistverkaufte Artikel

Bewusst nicht enthalten: Belegdruck, RKSV-Funktionen, Preislistenbearbeitung in
der App, Benutzerkonten pro Person, Reservierungen, Küchenbons.

## Aufbau

```
preisliste.csv    Die Preisliste. Einzige Quelle der Artikel und Preise.
Shared/           Swift-Package: Datenmodelle, Sync-Protokoll, Geldtyp.
                  Der Vertrag, gegen den App UND Server bauen.
Server/           Vapor-Server (Swift), SQLite. Läuft auf dem VPS.
App/              iPhone-App (SwiftUI). Projekt wird aus project.yml erzeugt.
deploy/           Docker-Compose, Caddy, Backup/Restore, Betriebsanleitung.
```

## Drei Entwurfsentscheidungen, die man kennen sollte

**Geld ist immer eine ganze Zahl in Cent.** Nie `Double`. Fließkommazahlen
verrechnen sich bei Geld (`0.1 + 0.2` ergibt nicht `0.3`), und eine Kassa, die
sich um einen Cent verrechnet, ist wertlos.

**Preis und Artikelname werden bei der Buchung eingefroren.** Änderst du später
einen Preis in der CSV, ändern sich alte Rechnungen nicht rückwirkend.

**Der Betriebstag endet um 06:00, nicht um Mitternacht.** Beim Heurigen wird
nach Mitternacht weiterkassiert. Eine Buchung um 01:00 zählt deshalb noch zum
Vorabend — sonst würde der Tagesabschluss den laufenden Betrieb zerschneiden.
Der Cutoff ist über `BUSINESS_DAY_CUTOFF_HOUR` einstellbar.

## Wie die Geräte zusammenspielen

Der Server ist die einzige Wahrheit. Jedes iPhone hält eine lokale Kopie und
eine Warteschlange noch nicht gesendeter Buchungen.

Buchungen sind *hinzufügend*: Wenn zwei Geräte ohne Netz auf denselben Tisch
buchen, addieren sich ihre Zeilen beim Abgleich einfach — es gibt nichts zu
lösen. Jede Bestellzeile bekommt ihre ID vom Gerät, deshalb erzeugt ein doppelt
gesendeter Auftrag keine Doppelbuchung.

Der einzige echte Konfliktfall ist **gleichzeitiges Kassieren desselben
Tisches**. Den entscheidet der Server: Wer zuerst kommt, kassiert; das zweite
Gerät bekommt eine klare Meldung, wer den Tisch schon abgerechnet hat, statt
einer stillen Doppelbuchung.

## Preisliste ändern

`preisliste.csv` bearbeiten, dann am Server neu importieren (siehe
`deploy/README.md`). Die App lädt den Katalog automatisch nach.

Artikel-IDs werden aus Kategorie und Name abgeleitet: Eine reine Preisänderung
behält die ID. Wird ein Artikel **umbenannt**, gilt er als neuer Artikel; der
alte wird deaktiviert, bleibt aber erhalten, damit alte Rechnungen lesbar
bleiben.

## Entwicklung

Vorausgesetzt: Xcode 26, Swift 6.2, Docker, `brew install xcodegen`.

```bash
# Vertrag testen
cd Shared && swift test

# Server lokal starten
cd Server && KASSA_PASSWORD=test swift run

# Xcode-Projekt erzeugen und öffnen
cd App && xcodegen generate && open Kassa.xcodeproj
```

Die `.xcodeproj` ist ein Build-Artefakt und nicht eingecheckt — `App/project.yml`
ist die Quelle.

## Betrieb

Server aufsetzen, Backups, Updates, Fehlersuche: **`deploy/README.md`**.

Für den Betrieb brauchst du einen Server mit Docker und eine Domain, die darauf
zeigt. HTTPS ist Pflicht, nicht optional — die Geräte kommen über offenes
Mobilfunknetz.
