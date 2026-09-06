# Küchenbon-Dienst — Installationsanleitung

Diese Anleitung beschreibt, wie der Küchenbon-Dienst auf dem alten Mac in
der Küche eingerichtet wird. Sie ist so geschrieben, dass sie auch ohne
Systemadministrations-Vorwissen funktioniert — jeder Befehl ist vollständig
zum Kopieren, und nach jedem Schritt steht, was man sehen muss, wenn es
geklappt hat.

## Was hier eigentlich läuft

| Was | Wo | Aufgabe |
|-----|-----|---------|
| `kuechenbon` (Binary) | `/usr/local/kuechenbon/` auf dem alten Mac | Fragt den Kassaserver alle paar Sekunden nach neuen Bestellungen und druckt Küchenbons. |
| LaunchDaemon `at.heuriger.kuechenbon` | `/Library/LaunchDaemons/` | Startet den Dienst automatisch beim Hochfahren — auch nach einem Stromausfall, ohne dass sich jemand anmelden muss. |
| CUPS-Warteschlange (z. B. `Printer_80`) | Legt macOS beim Anstecken selbst an | Nimmt die Druckaufträge entgegen; der Dienst schickt sie mit `-o raw`, also unverändert, an den Munbyn. |

Der Mac, auf dem der Dienst läuft, ist **alt (2014), Intel, macOS 11.7.11
Big Sur**. Der Entwicklungsrechner ist ein neuerer, arm64-basierter Mac.
Deshalb wird nicht direkt auf dem alten Mac gebaut (das würde ewig dauern
und setzt eine aktuelle Xcode-Version voraus, die es für Big Sur nicht mehr
gibt), sondern **cross-gebaut**: Das Binary entsteht am Entwicklungsrechner
und wird danach nur noch kopiert.

Der Dienst **liest nur**. Er kann am Kassenstand nichts verändern — siehe
Abschnitt 8 ganz unten.

---

## 1. Am Entwicklungsrechner: Binary bauen und übertragen

Im Projektverzeichnis, im Ordner `PrintService`:

```bash
cd PrintService
./build-release.sh
```

Das Skript baut, kopiert das Ergebnis nach `PrintService/build/kuechenbon`
und prüft es selbst — es behauptet nichts, was es nicht auch nachgesehen
hat. Am Ende muss stehen:

```
==> Alle Prüfungen bestanden. .../PrintService/build/kuechenbon ist bereit für den alten Mac.
```

Kommt stattdessen eine `FEHLER`-Zeile, ist das Binary **nicht** übertragen
worden — die Meldung sagt, woran es liegt (falsche Architektur, falsches
Deployment-Target, oder die Concurrency-Bibliothek, siehe Kommentar im
Skript).

Binary auf den alten Mac übertragen (IP-Adresse oder Hostname anpassen):

```bash
scp PrintService/build/kuechenbon benutzer@kuechen-mac.local:~/kuechenbon
```

**Erfolg sieht so aus:** `scp` zeigt eine Fortschrittsanzeige und endet ohne
Fehlermeldung. Auf dem alten Mac liegt danach eine Datei `kuechenbon` im
Home-Verzeichnis des Benutzers.

---

## 2. Am alten Mac: Verzeichnis anlegen

Ab hier alle Befehle **auf dem alten Mac** in der Küche, im Terminal.

```bash
sudo mkdir -p /usr/local/kuechenbon
sudo mv ~/kuechenbon /usr/local/kuechenbon/kuechenbon
sudo chmod 755 /usr/local/kuechenbon/kuechenbon
```

Kontrollieren:

```bash
ls -l /usr/local/kuechenbon/
```

Es muss eine Datei `kuechenbon` mit den Rechten `-rwxr-xr-x` erscheinen.

### `config.json` anlegen

```bash
sudo nano /usr/local/kuechenbon/config.json
```

Inhalt (Werte anpassen — Passwort ist dasselbe wie in der `.env` des
Servers, siehe `deploy/README.md`):

```json
{
  "serverURL": "https://kassa.viennax.at",
  "password": "das-gemeinsame-kassa-passwort",
  "deviceName": "Kuechendrucker",
  "printerQueue": "Printer_80",
  "foodCategory": "Speisen",
  "excludedArticles": ["Kaffee", "Mehlspeise"],
  "pollIntervalSeconds": 2,
  "maxBonAgeMinutes": 120,
  "asciiFallback": false,
  "lineWidth": 48
}
```

Speichern mit `Strg+O`, `Enter`, schließen mit `Strg+X`.

**Rechte einschränken — das ist wichtig, nicht optional:**

```bash
sudo chmod 600 /usr/local/kuechenbon/config.json
```

Die Datei enthält das Kassa-Passwort im Klartext. Mit `600` kann nur `root`
sie lesen; jeder andere Benutzer an diesem Mac (und es ist ein Küchen-Mac,
an dem im Lauf der Zeit vielleicht mehrere Leute ein Konto bekommen) bliebe
sonst außen vor gehalten — mit den Standardrechten `644` könnte dagegen
jeder, der sich an diesem Mac anmelden kann, das Passwort einfach auslesen.

Kontrollieren:

```bash
ls -l /usr/local/kuechenbon/config.json
```

Es muss `-rw-------` dastehen.

---

## 3. Drucker einrichten

Der Munbyn hängt per USB und versteht **ESC/POS**: Er bekommt seine
Formatierung nicht über einen Treiber, sondern über Steuerzeichen mitten im
Datenstrom. Die Daten müssen ihn deshalb **unverändert** erreichen. Ein
normaler Druckertreiber würde sie als Text oder PDF interpretieren und
Zeichensalat oder gar nichts drucken.

Genau dafür gibt es in CUPS die Option `-o raw`, die alle Filter überspringt.
Der Dienst setzt sie bei jedem Druckauftrag selbst — du musst dich nur um die
richtige Warteschlange kümmern.

**macOS legt die beim Anstecken bereits von selbst an.** Anzeigen lassen:

```bash
lpstat -p
```

**Erfolg sieht so aus:** Eine der Zeilen nennt den Drucker, meist unter einem
generischen Namen:

```
printer Printer_80 is idle.  enabled since ...
```

Diesen Namen — hier `Printer_80` — trägst du in `config.json` als
`printerQueue` ein. Das ist alles.

### Damit die Warteschlange nicht stehen bleibt

Bei einem USB-Aussetzer hält CUPS die Warteschlange standardmäßig an und
lässt sie angehalten — mitten im Betrieb fällt das niemandem auf. Einmalig
umstellen:

```bash
sudo lpadmin -p Printer_80 -o printer-error-policy=retry-job
```

Danach versucht CUPS es erneut, statt aufzugeben. Der Dienst prüft den
Zustand zusätzlich vor jedem Druck: steht die Warteschlange, verweigert er
den Druck und vermerkt den Bon **nicht** als erledigt — er kommt nach, sobald
die Warteschlange wieder läuft. Ohne diese Prüfung gingen Bons lautlos
verloren, denn `lp` meldet bereits Erfolg, wenn ein Auftrag nur eingereiht
wurde.

Angehaltene Warteschlange von Hand freigeben:

```bash
sudo cupsenable Printer_80
lpq -P Printer_80          # hängen dort alte Aufträge?
cancel -a Printer_80       # nur falls die verworfen werden sollen
```

> **Raw-Queues sind ein Auslaufmodell.** Frühere Anleitungen (auch eine
> frühere Fassung dieser hier) empfahlen `lpadmin -m raw`. Der Stand, beides
> geprüft:
>
> - **macOS 11.7** legt sie noch an, warnt aber: „Reine Wartelisten wurden
>   verworfen und funktionieren in künftigen Versionen von CUPS nicht mehr."
> - **macOS 26** verweigert sie ganz: `Raw queues are no longer supported`.
>
> Gebraucht werden sie nicht. Die Option `-o raw` beim Druckauftrag überspringt
> die Filter unabhängig davon, welchen Treiber die Warteschlange hat — deshalb
> funktioniert die von macOS selbst angelegte Warteschlange genauso gut und
> bleibt auch künftig nutzbar. Auf einem 80-mm-Munbyn ist dieser Weg erprobt,
> Umlaute inklusive.

Taucht unter `lpstat -p` gar nichts auf, prüfen, ob CUPS das Gerät sieht:

```bash
lpinfo -v
```

Dort muss eine Zeile wie `direct usb://Printer/Printer-80?serial=...`
stehen. Erscheint sie, aber es gibt keine Warteschlange, lässt sie sich von
Hand anlegen (die Adresse aus `lpinfo -v` einsetzen):

```bash
sudo lpadmin -p Kuechenbon -E -v "usb://Printer/Printer-80?serial=012345678AB" \
     -m drv:///sample.drv/generic.ppd
```

Welches Modell, ist gleichgültig — es kommt nie zum Einsatz, weil der Dienst
beim Drucken `-o raw` setzt und damit alle Filter überspringt. Andere
Möglichkeiten zeigt `lpinfo -m`.

> **Nicht `-m everywhere` verwenden.** Das steht für IPP Everywhere und lässt
> CUPS den Drucker übers Netz befragen. Bei einer `usb://`-Adresse deutet es
> den ersten Pfadteil als Rechnernamen und scheitert mit
> `nodename nor servname provided or not known`.

> Ein späterer Umstieg auf einen Netzwerkdrucker wäre nur eine andere
> `-v`-Adresse (z. B. `socket://192.168.1.50:9100` statt `usb://...`) — an
> `config.json` und am restlichen Dienst ändert sich nichts.

---


## 4. Probebon drucken

```bash
cd /usr/local/kuechenbon
sudo ./kuechenbon testbon
```

Am ausgedruckten Papier drei Dinge prüfen:

1. **Umlaute richtig?** Wörter wie „Käsekrainer", „Weißwurst", „Gebäck"
   müssen lesbar sein, nicht als Kästchen oder falsche Zeichen erscheinen.
2. **Passt die Breite aufs 80-mm-Papier?** Der Text darf nicht abgeschnitten
   wirken oder ins Nichts laufen.
3. **Schneidet der Drucker das Papier ab?** Nach dem Bon muss der Streifen
   von selbst abgetrennt sein, ohne dass man reißen muss.

**Wenn die Umlaute Zeichensalat sind:** Das ist ein bekanntes Risiko — die
genaue Zeichensatz-Firmware des verwendeten Munbyn-Modells ist vorab nicht
bekannt, manche ESC/POS-Drucker beherrschen den nötigen Codepage-Umschalter
nicht zuverlässig. Abhilfe in `config.json`:

```json
"asciiFallback": true
```

Danach druckt der Dienst „Kaesekrainer" statt „Käsekrainer" — hässlicher,
aber garantiert lesbar. Nach der Änderung erneut `sudo ./kuechenbon testbon`
ausführen und kontrollieren.

---

## 5. Dienst installieren

### plist an die richtige Stelle kopieren

Vom Entwicklungsrechner übertragen (falls noch nicht geschehen) oder direkt
aus dem Projekt kopieren, dann auf dem alten Mac:

```bash
sudo cp at.heuriger.kuechenbon.plist /Library/LaunchDaemons/
sudo chown root:wheel /Library/LaunchDaemons/at.heuriger.kuechenbon.plist
sudo chmod 644 /Library/LaunchDaemons/at.heuriger.kuechenbon.plist
```

> **Warum ein LaunchDaemon und kein LaunchAgent:** Ein LaunchAgent startet
> erst, wenn sich jemand anmeldet — nach einem Stromausfall stünde der Mac
> also in der Küche und würde keine Bons drucken, bis jemand sich vor die
> Tastatur setzt. Ein LaunchDaemon startet dagegen beim Hochfahren, noch
> bevor sich irgendjemand anmeldet.
>
> **Warum `caffeinate -s`:** Ein LaunchDaemon hindert macOS nicht am
> Einschlafen. Schläft der Mac während des Abends ein, pollt der Dienst
> nicht mehr, und Bons kommen verspätet oder gar nicht. `caffeinate -s`
> hält den Rechner (nicht zwingend den Bildschirm) wach, solange der
> Prozess läuft — und das ist die ganze Betriebszeit über der Fall.

### Dienst starten

```bash
sudo launchctl bootstrap system /Library/LaunchDaemons/at.heuriger.kuechenbon.plist
```

Status prüfen:

```bash
sudo launchctl print system/at.heuriger.kuechenbon
```

**Erfolg sieht so aus:** Es erscheint ein Block mit `state = running` (oder
kurz danach `waiting`) und `pid = <Zahl>`. Steht dort etwas wie
`Could not find service`, ist der Dienst nicht geladen — Schritt wiederholen
und auf Tippfehler im Pfad achten.

Log ansehen:

```bash
tail -f /usr/local/kuechenbon/kuechenbon.log
```

(Beenden mit `Strg+C`.) Dort sollten regelmäßig Zeilen zum Abfragen des
Servers erscheinen, ohne Fehlermeldungen.

### Dienst stoppen (Gegenrichtung)

```bash
sudo launchctl bootout system/at.heuriger.kuechenbon
```

### Nach einer Änderung an `config.json` neu starten

Der Dienst liest `config.json` beim Start ein, nicht laufend. Nach jeder
Änderung:

```bash
sudo launchctl bootout system/at.heuriger.kuechenbon
sudo launchctl bootstrap system /Library/LaunchDaemons/at.heuriger.kuechenbon.plist
```

Kontrolle wie oben mit `launchctl print`.

---

## 6. Energieeinstellungen

```bash
sudo pmset -a sleep 0 disksleep 0
```

Das verhindert, dass der Mac oder seine Festplatte während des Betriebs
einschlafen.

**Ehrlicher Hinweis:** Ist es ein Laptop, schläft er trotz dieser
Einstellung ein, sobald der Deckel zugeklappt wird — das ist eine separate
Hardware-Funktion, die `pmset` nicht abschaltet. Also entweder den Deckel
während des Betriebs offen lassen, oder einen externen Bildschirm
anschließen (dann zählt der Deckel-Zustand nicht mehr).

---

## 7. Fehlersuche

| Symptom | Wahrscheinliche Ursache | Befehl zum Prüfen |
|---|---|---|
| Gar kein Bon kommt | Dienst läuft nicht, oder Queue angehalten | `sudo launchctl print system/at.heuriger.kuechenbon`, `lpstat -p` |
| Bon kommt, aber deutlich verspätet | Mac war eingeschlafen, oder `pollIntervalSeconds` sehr hoch gewählt | `pmset -g log \| grep -i sleep`, `cat /usr/local/kuechenbon/config.json` |
| Zeichensalat statt Umlauten | Drucker-Firmware kommt mit der Codepage nicht zurecht | `"asciiFallback": true` setzen, siehe Abschnitt 4 |
| Log meldet „Die Druckerwarteschlange … ist angehalten" | USB-Aussetzer, CUPS hat die Queue gestoppt | `lpstat -p`, dann `sudo cupsenable <Name der Queue>`; dauerhaft abstellen mit `printer-error-policy=retry-job`, siehe Abschnitt 3 |
| Dienst läuft nicht / startet nicht | plist fehlerhaft, falsche Rechte, oder Binary fehlt | `sudo launchctl print system/at.heuriger.kuechenbon`, `tail -50 /usr/local/kuechenbon/kuechenbon.log` |
| Papier leer | Der Drucker selbst braucht neues Papier | Papierrolle am Munbyn kontrollieren |

Bei jedem Problem lohnt zuerst ein Blick ins Log:

```bash
tail -100 /usr/local/kuechenbon/kuechenbon.log
```

---

## 8. Was der Dienst NICHT tut

Der Küchenbon-Dienst **liest nur** vom Kassaserver. Er kann keine
Bestellung anlegen, ändern oder löschen und hat auf den Kassenstand selbst
keinerlei Schreibzugriff.

**Fällt der alte Mac aus** (Stromausfall, Absturz, Drucker kaputt), läuft
die Kassa an den iPhones **unverändert weiter** — kassiert wird ganz normal,
es fehlen dann nur die gedruckten Küchenbons. Das Personal in der Küche
müsste sich in diesem Fall an den Bildschirmen/iPhones orientieren, aber es
geht keine Bestellung verloren.

## Zwei weitere Dinge, die man kennen sollte

**Jeder Bon trägt eine fortlaufende Nummer.** Das ist mit Absicht so: Fehlt
in der Küche plötzlich eine Nummer in der Reihenfolge, fällt sofort auf,
dass ein Bon nicht gedruckt wurde — statt dass es unbemerkt bleibt, bis sich
ein Gast über eine fehlende Bestellung beschwert.

**Bestellungen, die älter als zwei Stunden sind, werden nicht mehr
gedruckt** (`maxBonAgeMinutes: 120`). Das verhindert, dass nach einem
längeren Ausfall (Stromausfall über Nacht, Drucker tagelang aus) beim
nächsten Start plötzlich ein ganzer Stapel längst kalter Bestellungen
herausschießt.
