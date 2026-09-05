# Kassasystem — Betriebsanleitung für den Server

Diese Anleitung beschreibt, wie der Kassaserver auf dem VPS installiert,
aktualisiert und gesichert wird. Sie ist so geschrieben, dass sie auch nach
einem Jahr ohne Vorwissen noch verständlich ist.

Alle Befehle werden **auf dem VPS** eingegeben, nicht am iPhone und nicht am
eigenen Rechner. Verbindung zum VPS mit:

```bash
ssh root@<IP-des-VPS>
```

## Was hier eigentlich läuft

Zwei Programme („Container") laufen nebeneinander:

| Container | Aufgabe |
|-----------|---------|
| `kassa`   | Der eigentliche Server. Speichert Bestellungen in einer SQLite-Datei und hält die iPhones per WebSocket auf dem gleichen Stand. |
| `caddy`   | Steht davor und macht die Verschlüsselung (HTTPS). Holt und erneuert das Zertifikat vollautomatisch. |

Der `kassa`-Container ist aus dem Internet **nicht direkt** erreichbar — es
kommt alles über `caddy`. Das ist Absicht.

Die Daten liegen in zwei Docker-Volumes, die Updates und Neustarts überleben:

- `kassa_data` — die Datenbank (`/data/kassa.sqlite`)
- `kassa_caddy_data` — die HTTPS-Zertifikate

---

## 1. Voraussetzungen

1. **Ein Debian-VPS** (Debian 12 oder neuer) mit Root-Zugang.

2. **Eine Domain, die auf den VPS zeigt.**
   Beim Domain-Anbieter einen A-Record anlegen, z. B.
   `kassa.beispiel.at` → `<IP-des-VPS>`.

   Prüfen, ob das schon greift:

   ```bash
   dig +short kassa.beispiel.at
   ```

   Es muss die IP des VPS herauskommen. Erst dann weitermachen — ohne
   funktionierenden DNS-Eintrag bekommt der Server **kein Zertifikat**.
   Nach einer DNS-Änderung kann es bis zu einer Stunde dauern.

3. **Docker installieren** (nur beim ersten Mal):

   ```bash
   apt update
   apt install -y curl git sqlite3
   curl -fsSL https://get.docker.com | sh
   ```

   `sqlite3` wird für die Backup-Skripte gebraucht, `git` zum Holen des
   Projekts.

   Kontrolle:

   ```bash
   docker --version
   docker compose version
   ```

---

## 2. Firewall

Es sollen nur drei Ports von außen erreichbar sein: SSH (22), HTTP (80) und
HTTPS (443). Port 80 wird gebraucht, weil Let's Encrypt darüber prüft, ob die
Domain wirklich zum Server gehört.

```bash
apt install -y ufw

ufw default deny incoming
ufw default allow outgoing

ufw allow 22/tcp     # SSH — NICHT vergessen, sonst sperrt man sich aus
ufw allow 80/tcp     # HTTP (Zertifikatsprüfung + Weiterleitung auf HTTPS)
ufw allow 443/tcp    # HTTPS
ufw allow 443/udp    # HTTP/3

ufw enable
ufw status
```

> **Achtung:** Vor `ufw enable` unbedingt kontrollieren, dass Port 22 erlaubt
> ist. Andernfalls ist der VPS per SSH nicht mehr erreichbar und muss über die
> Notfall-Konsole des Anbieters repariert werden.

Der Datenbank-Port muss **nicht** geöffnet werden — die Datenbank ist eine
Datei im Container und von außen nicht ansprechbar.

---

## 3. Erstinstallation

### Schritt 1 — Projekt auf den VPS holen

```bash
mkdir -p /opt
cd /opt
git clone <URL-des-Repositories> kassa
cd /opt/kassa/deploy
```

Ab hier ist `/opt/kassa/deploy` das Arbeitsverzeichnis für alle weiteren
Befehle.

### Schritt 2 — Einstellungen anlegen

```bash
cp .env.example .env
```

Ein starkes Passwort erzeugen:

```bash
openssl rand -base64 24
```

Die Ausgabe kopieren, dann die Datei öffnen:

```bash
nano .env
```

Ausfüllen: `DOMAIN`, `ACME_EMAIL` und `KASSA_PASSWORD`. Speichern mit
`Strg+O`, `Enter`, schließen mit `Strg+X`.

Rechte einschränken, damit das Passwort nicht für jeden lesbar ist:

```bash
chmod 600 .env
```

> Die Datei `.env` wird nie ins Git-Repository übernommen (dafür sorgt die
> `.gitignore`). Das Passwort existiert also **nur hier auf dem VPS** — es
> sollte zusätzlich im Passwortmanager liegen.

### Schritt 3 — Starten

```bash
docker compose up -d --build
```

Der erste Start dauert lange (10–20 Minuten), weil der Swift-Server
komplett übersetzt wird. Spätere Starts dauern Sekunden.

### Schritt 4 — Kontrollieren

```bash
docker compose ps
```

Beide Container sollen `running` sein, `kassa` zusätzlich `healthy`
(das kann bis zu einer Minute dauern).

Dann von außen testen — am besten vom Handy im Mobilfunknetz, nicht vom VPS:

```bash
curl https://kassa.beispiel.at/config
```

Wenn eine Antwort kommt (auch eine Fehlermeldung wegen fehlendem Login ist
in Ordnung) und `curl` **nicht** über das Zertifikat meckert, steht der
Server.

### Schritt 5 — iPhones einrichten

In der Kassa-App die Adresse `https://kassa.beispiel.at` und das Passwort aus
der `.env` eintragen. Das ist pro Gerät einmal nötig.

### Schritt 6 — Backup einrichten

Siehe Abschnitt 6 weiter unten. **Bitte gleich machen, nicht später** — ein
Backup, das erst nach dem ersten Datenverlust eingerichtet wird, hilft nicht.

---

## 4. Täglicher Betrieb

```bash
cd /opt/kassa/deploy

docker compose ps          # Läuft alles?
docker compose logs -f     # Live mitschauen (beenden mit Strg+C)
docker compose restart     # Neu starten, wenn etwas hängt
```

Nur die Meldungen des Servers, die letzten 100 Zeilen:

```bash
docker compose logs --tail=100 kassa
```

Nur den Proxy (interessant bei Zertifikatsproblemen):

```bash
docker compose logs --tail=100 caddy
```

Die Logs werden automatisch begrenzt (max. 30 MB pro Container), es kann also
nichts unbemerkt die Platte vollschreiben.

**Neustart des ganzen VPS:** Beide Container starten von selbst wieder
(`restart: unless-stopped`). Es ist nichts von Hand zu tun.

---

## 5. Update auf eine neue Version

```bash
cd /opt/kassa/deploy

./backup.sh                       # zuerst sichern
git pull                          # neue Version holen
docker compose up -d --build      # neu bauen und starten
docker compose ps                 # kontrollieren
docker compose logs --tail=50 kassa
```

Die Datenbank bleibt dabei erhalten — sie liegt im Volume, nicht im Container.

Alte, nicht mehr gebrauchte Docker-Images gelegentlich wegräumen:

```bash
docker image prune -f
```

> `docker system prune` ohne weitere Angaben ist ebenfalls möglich, aber
> **niemals** mit der Option `--volumes` — die würde die Datenbank löschen.

### Wenn ein Update Probleme macht

```bash
git log --oneline -5              # letzte Versionen ansehen
git checkout <alte-version>       # eine Version zurück
docker compose up -d --build
```

---

## 6. Backup

### Einmalig einrichten

```bash
cd /opt/kassa/deploy
./backup.sh                       # einmal von Hand testen
```

Wenn das klappt, den nächtlichen Lauf eintragen:

```bash
crontab -e
```

Am Ende diese Zeile einfügen (Pfad anpassen, falls das Projekt woanders
liegt):

```
30 3 * * * /opt/kassa/deploy/backup.sh >> /var/log/kassa-backup.log 2>&1
```

Damit wird jede Nacht um 03:30 Uhr gesichert — dann hat der Heuriger sicher
zu. Die Sicherungen landen in `/var/backups/kassa/` und werden 14 Tage lang
aufgehoben.

Das Skript kopiert die Datei nicht einfach, sondern benutzt die
Sicherungsfunktion von SQLite. Das ist wichtig: Eine simple Kopie während des
Betriebs wäre unvollständig, was man erst im Ernstfall merken würde.

### Kontrollieren, ob das Backup läuft

```bash
ls -lh /var/backups/kassa/
tail -20 /var/log/kassa-backup.log
```

Das Datum der neuesten Datei sollte von letzter Nacht sein.

### Backups vom VPS herunterladen

Backups, die nur auf demselben Server liegen, helfen nicht, wenn der Server
selbst kaputt geht. Vom eigenen Rechner aus, etwa einmal im Monat:

```bash
scp root@<IP-des-VPS>:/var/backups/kassa/kassa_*.sqlite ~/Kassa-Backups/
```

---

## 7. Backup einspielen

Vorhandene Sicherungen ansehen:

```bash
cd /opt/kassa/deploy
./restore.sh
```

Eine davon einspielen:

```bash
./restore.sh /var/backups/kassa/kassa_2026-09-04_033000.sqlite
```

Das Skript

1. prüft, ob die Sicherung überhaupt lesbar ist,
2. fragt nach — bestätigt wird durch Eintippen von `JA`,
3. hält den Server an,
4. legt den bisherigen Stand als `.vor-restore-...` zur Seite,
5. spielt die Sicherung ein und startet den Server wieder.

Danach kontrollieren:

```bash
docker compose logs --tail=50 kassa
```

Und in der App nachsehen, ob die Daten stimmen.

> Alles, was nach dem Zeitpunkt der Sicherung eingegeben wurde, ist danach
> weg. Wenn der laufende Betrieb nur „komisch" ist und nicht wirklich kaputt,
> zuerst `docker compose restart` probieren — das ist harmlos.

---

## 8. Preisliste neu importieren

Die Preisliste liegt als `preisliste.csv` im Hauptverzeichnis des Projekts
(Spalten: `Kategorie`, `Artikel`, `Preis`).

1. Datei anpassen — am einfachsten am eigenen Rechner in einem
   Tabellenprogramm, dann per Git oder `scp` auf den VPS.

2. Vorher sichern:

   ```bash
   cd /opt/kassa/deploy
   ./backup.sh
   ```

3. Die Datei in den Server-Container legen und diesen neu starten:

   ```bash
   docker compose cp ../preisliste.csv kassa:/data/preisliste.csv
   docker compose restart kassa
   docker compose logs --tail=50 kassa
   ```

   Im Log muss stehen, wie viele Artikel eingelesen wurden.

4. Auf den iPhones die App einmal neu starten, damit die neuen Preise
   ankommen.

> **Hinweis:** Preise wirken sich nur auf **neue** Bestellungen aus. Bereits
> gebuchte Zeilen behalten den Preis, der zum Zeitpunkt der Bestellung
> gegolten hat — das muss so sein, sonst würden alte Tagesabschlüsse
> nachträglich andere Summen ergeben.

> **Falls der Import so nicht greift:** Der genaue Weg hängt davon ab, wie der
> Server den Import umsetzt (Datei beim Start einlesen oder Import über die
> App). Im Zweifel ins Server-Log schauen — dort steht, welchen Pfad der
> Server sucht.

---

## 9. Wenn's nicht geht

### Das Zertifikat kommt nicht / die App meldet einen HTTPS-Fehler

```bash
docker compose logs caddy | tail -50
```

Die häufigsten Ursachen, der Reihe nach prüfen:

1. **Die Domain zeigt nicht auf den VPS.**
   ```bash
   dig +short kassa.beispiel.at      # muss die IP des VPS liefern
   ```
   Wenn nicht: DNS-Eintrag beim Domain-Anbieter korrigieren und bis zu einer
   Stunde warten.

2. **Port 80 ist von außen zu.** Let's Encrypt muss den Server auf Port 80
   erreichen. Firewall prüfen (`ufw status`) — auch die Firewall im Webpanel
   des VPS-Anbieters, die gibt es oft zusätzlich.

3. **Tippfehler in `DOMAIN`.** In der `.env` steht die Domain ohne
   `https://` und ohne Schrägstrich am Ende.

4. **Zu viele Fehlversuche.** Let's Encrypt sperrt nach etwa fünf
   fehlgeschlagenen Versuchen pro Domain und Woche. Dann erst die Ursache
   beheben, danach eine Stunde warten. Zum Ausprobieren kann im `Caddyfile`
   die Zeile `acme_ca ...` (Staging) vorübergehend aktiviert werden — die
   Zertifikate sind dann zwar ungültig, aber man sieht, ob der Ablauf
   funktioniert. **Danach wieder auskommentieren** und einmal
   `docker compose restart caddy`.

### Port 80 oder 443 ist belegt — Caddy startet nicht

Fehlermeldung in der Art von `address already in use`.

```bash
ss -tlnp | grep -E ':(80|443)\s'
```

Meist läuft ein Apache oder Nginx aus einer früheren Installation:

```bash
systemctl stop apache2 nginx
systemctl disable apache2 nginx
docker compose up -d
```

### Die iPhones aktualisieren sich nicht gegenseitig (WebSocket)

Bestellungen kommen an, aber die anderen Geräte sehen sie erst nach manuellem
Neuladen. Dann steht die WebSocket-Verbindung nicht.

1. Läuft der Server überhaupt?
   ```bash
   docker compose ps
   docker compose logs --tail=50 kassa
   ```

2. Verbindung von außen testen:
   ```bash
   curl -i -N \
     -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGVzdHRlc3R0ZXN0dGVzdA==" \
     https://kassa.beispiel.at/ws
   ```
   Richtig ist `HTTP/1.1 101 Switching Protocols`. Kommt `200` oder `404`,
   antwortet der Server, aber nicht als WebSocket. Kommt `502`, läuft der
   `kassa`-Container nicht.

3. **Nicht am `Caddyfile` herumbasteln.** Caddy leitet WebSockets von sich aus
   korrekt weiter; zusätzliche Header-Regeln (wie man sie aus
   Nginx-Anleitungen kennt) machen es eher kaputt. Wurde die Datei geändert:
   ```bash
   git checkout Caddyfile
   docker compose restart caddy
   ```

4. Mobilfunknetze trennen ruhende Verbindungen nach einigen Minuten. Wenn ein
   iPhone nach längerer Pause im Sperrbildschirm kurz nicht aktuell ist und
   sich nach ein paar Sekunden von selbst fängt, ist das normal.

### Die Platte ist voll

Anzeichen: Der Server nimmt keine Bestellungen mehr an, im Log steht
`no space left on device` oder `database or disk is full`.

```bash
df -h /                  # wie voll ist es?
docker system df         # was belegt Docker?
```

Aufräumen, in dieser Reihenfolge:

```bash
docker image prune -af           # alte Images (meist der größte Brocken)
docker builder prune -af         # Zwischenstände vom Bauen
journalctl --vacuum-time=7d      # System-Logs kürzen
```

Wenn es die Backups sind, ältere löschen oder in `backup.sh` die
Aufbewahrung reduzieren (`KEEP_DAYS`).

> **Niemals** `docker system prune --volumes` oder `docker volume rm` — damit
> ist die Datenbank weg.

Danach:

```bash
docker compose up -d
```

### Gar nichts geht mehr — der schnellste Weg zurück

Im laufenden Betrieb, in dieser Reihenfolge, immer erst das Nächste probieren
wenn das Vorherige nichts gebracht hat:

```bash
cd /opt/kassa/deploy

docker compose restart            # 1. einfacher Neustart (Sekunden)
docker compose down && docker compose up -d   # 2. Container neu aufsetzen
reboot                            # 3. ganzen VPS neu starten
```

Die Daten bleiben bei allen drei Schritten erhalten. Erst wenn die Datenbank
selbst beschädigt ist, kommt `./restore.sh` an die Reihe (Abschnitt 7).

---

## Merkzettel

```bash
cd /opt/kassa/deploy

docker compose ps                 # Status
docker compose logs -f kassa      # Log mitlesen
docker compose restart            # Neustart
./backup.sh                       # sichern
./restore.sh                      # Sicherungen auflisten
```

Wichtige Orte:

| Was | Wo |
|-----|-----|
| Einstellungen und Passwort | `/opt/kassa/deploy/.env` |
| Backups | `/var/backups/kassa/` |
| Backup-Protokoll | `/var/log/kassa-backup.log` |
| Datenbank (im Docker-Volume) | `docker volume inspect kassa_data` |
