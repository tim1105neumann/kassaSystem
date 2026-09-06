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

Auf diesem VPS laufen bereits andere Webseiten (z. B.
`aerolog.timneumann.tech`), und dafür läuft dort ein **nginx**, das die Ports
80 und 443 belegt. Das Kassasystem fügt sich da ein, statt sich damit zu
streiten:

| Was | Wo | Aufgabe |
|-----|-----|---------|
| `kassa` (Docker-Container) | nur `127.0.0.1:8080` | Der eigentliche Server. Speichert Bestellungen in einer SQLite-Datei und hält die iPhones per WebSocket auf dem gleichen Stand. |
| `nginx` (auf dem Server selbst, kein Container) | Ports 80 + 443 | Nimmt die Anfragen aus dem Internet an, macht die Verschlüsselung (HTTPS) und reicht alles an `127.0.0.1:8080` weiter. |

Der Container hängt bewusst nur auf `127.0.0.1` (also „nur dieser Rechner").
Aus dem Internet ist er **nicht direkt** erreichbar — es kommt alles über
nginx. Würde dort `0.0.0.0` oder gar nichts stehen, wäre der Kassaserver
ungeschützt und ohne HTTPS offen im Netz.

Das HTTPS-Zertifikat besorgt **certbot**, nicht das Kassasystem. Es erneuert
sich danach von selbst.

Zwei Dateien gehören zusammen:

- `docker-compose.yml` — startet den Container
- `nginx-kassa.conf` — der nginx-Block, der auf den Container zeigt.
  Diese Datei wird nach `/etc/nginx/sites-available/kassa` **kopiert**; das
  Original hier im Ordner ist nur die Vorlage.

Die Daten liegen im Docker-Volume `kassa_data` (`/data/kassa.sqlite`), das
Updates und Neustarts überlebt.

---

## 1. Voraussetzungen

1. **Ein Debian-VPS** (Debian 12 oder neuer) mit Root-Zugang.

2. **Eine Domain, die auf den VPS zeigt.**
   Beim Domain-Anbieter einen A-Record anlegen, z. B.
   `kassa.viennax.at` → `<IP-des-VPS>`.

   Prüfen, ob das schon greift:

   ```bash
   dig +short kassa.viennax.at
   ```

   Es muss die IP des VPS herauskommen. Erst dann weitermachen — ohne
   funktionierenden DNS-Eintrag bekommt certbot **kein Zertifikat**.
   Nach einer DNS-Änderung kann es bis zu einer Stunde dauern.

3. **nginx und certbot** (nginx läuft meist schon):

   ```bash
   apt update
   apt install -y nginx certbot python3-certbot-nginx
   apt install -y curl git sqlite3
   ```

   `sqlite3` wird für die Backup-Skripte gebraucht, `git` zum Holen des
   Projekts.

   Kontrolle:

   ```bash
   nginx -v
   systemctl status nginx     # soll "active (running)" sein
   certbot --version
   ```

4. **Docker installieren** (nur beim ersten Mal):

   ```bash
   curl -fsSL https://get.docker.com | sh
   docker --version
   docker compose version
   ```

5. **Ist Port 8080 noch frei?** Auf dem Port soll der Container lauschen. Wenn
   dort schon etwas anderes läuft, gibt es beim Start einen Fehler:

   ```bash
   ss -tlnp | grep 8080
   ```

   **Keine Ausgabe = frei = alles gut.** Kommt eine Zeile zurück, ist der
   Port belegt. Dann in der `.env` einen anderen Port eintragen, z. B.
   `KASSA_HOST_PORT=8090`, und in `nginx-kassa.conf` das `proxy_pass`
   auf `http://127.0.0.1:8090` ändern. Die beiden Werte müssen immer
   zusammenpassen.

---

## 2. Firewall

Es sollen nur drei Ports von außen erreichbar sein: SSH (22), HTTP (80) und
HTTPS (443). Diese Ports gehören dem nginx, das ohnehin schon die anderen
Seiten ausliefert. Port 80 muss offen bleiben, weil certbot darüber prüft, ob
die Domain wirklich zu diesem Server gehört — und das bei **jeder**
Erneuerung erneut tut.

```bash
apt install -y ufw

ufw default deny incoming
ufw default allow outgoing

ufw allow 22/tcp     # SSH — NICHT vergessen, sonst sperrt man sich aus
ufw allow 80/tcp     # HTTP (certbot-Prüfung + Weiterleitung auf HTTPS)
ufw allow 443/tcp    # HTTPS

ufw enable
ufw status
```

> **Achtung:** Vor `ufw enable` unbedingt kontrollieren, dass Port 22 erlaubt
> ist. Andernfalls ist der VPS per SSH nicht mehr erreichbar und muss über die
> Notfall-Konsole des Anbieters repariert werden.

Der Port 8080 wird **nicht** freigegeben und darf es auch nicht. Er ist an
`127.0.0.1` gebunden und damit von außen ohnehin nicht erreichbar — das ist
so gewollt.

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

Ausfüllen: `KASSA_PASSWORD`. Speichern mit `Strg+O`, `Enter`, schließen mit
`Strg+X`.

Rechte einschränken, damit das Passwort nicht für jeden lesbar ist:

```bash
chmod 600 .env
```

> Die Datei `.env` wird nie ins Git-Repository übernommen (dafür sorgt die
> `.gitignore`). Das Passwort existiert also **nur hier auf dem VPS** — es
> sollte zusätzlich im Passwortmanager liegen.
>
> Die Domain steht **nicht** in der `.env`, sondern in der nginx-Datei
> (Schritt 4). HTTPS macht nginx, nicht der Container.

### Schritt 3 — Container starten

```bash
docker compose up -d --build
```

Der erste Start dauert lange (10–20 Minuten), weil der Swift-Server
komplett übersetzt wird. Spätere Starts dauern Sekunden.

Kontrollieren:

```bash
docker compose ps
```

Der Container soll `running` sein und nach etwa einer Minute `healthy`.

Direkt auf dem Server testen, noch ohne nginx:

```bash
curl -i http://127.0.0.1:8080/config
```

Eine Antwort muss kommen — auch eine Fehlermeldung wegen fehlendem Login ist
in Ordnung. Wichtig ist nur, dass überhaupt etwas antwortet. Erst wenn das
klappt, hat der nächste Schritt Sinn.

### Schritt 4 — nginx einrichten

Die Vorlage kopieren:

```bash
cp /opt/kassa/deploy/nginx-kassa.conf /etc/nginx/sites-available/kassa
```

Domain kontrollieren — sie muss beim `server_name` stehen:

```bash
nano /etc/nginx/sites-available/kassa
```

Block aktivieren:

```bash
ln -s /etc/nginx/sites-available/kassa /etc/nginx/sites-enabled/kassa
```

**Erst prüfen, dann neu laden.** `nginx -t` findet Tippfehler, bevor sie die
anderen Seiten auf dem Server mitreißen:

```bash
nginx -t
```

Nur wenn dort `syntax is ok` und `test is successful` steht, weitermachen:

```bash
systemctl reload nginx
```

> `reload` statt `restart`: nginx übernimmt die neue Konfiguration, ohne die
> laufenden Verbindungen der anderen Seiten zu unterbrechen.

Jetzt testen — noch über HTTP, ohne `s`:

```bash
curl -i http://kassa.viennax.at/config
```

Kommt eine Antwort vom Kassaserver, steht die Weiterleitung.

### Schritt 5 — HTTPS-Zertifikat holen

**Erst jetzt**, nachdem der Port-80-Block aus Schritt 4 aktiv ist:

```bash
certbot --nginx -d kassa.viennax.at
```

> **Die Reihenfolge ist wichtig.** certbot sucht in den nginx-Dateien nach
> einem Block mit genau diesem `server_name`. Ohne Schritt 4 findet es die
> Domain nicht und bricht ab. Deshalb liefert `nginx-kassa.conf` bewusst nur
> den Port-80-Block mit — den HTTPS-Teil schreibt certbot selbst dazu.

certbot fragt nach einer E-Mail-Adresse (dorthin kommen Ablaufwarnungen) und
ob auf HTTPS umgeleitet werden soll — **ja, umleiten** auswählen.

Danach von außen prüfen, am besten vom Handy im Mobilfunknetz:

```bash
curl https://kassa.viennax.at/config
```

Die richtige Antwort sieht so aus:

```json
{"reason":"Kein Bearer-Token","error":true}
```

**Das ist der Erfolgsfall, kein Fehler.** `/config` ist geschützt, und ohne
Anmeldung weist der Server die Anfrage genau so zurück. Entscheidend ist,
dass diese Meldung überhaupt ankommt: Sie stammt vom Kassa-Server, nicht von
nginx — der ganze Weg von außen steht also.

Meckert `curl` dagegen über das Zertifikat, oder kommt eine HTML-Seite mit
`404 Not Found` von nginx zurück, dann siehe Abschnitt 9.

Gegenprobe, dass auch die Anmeldung arbeitet (absichtlich falsches Passwort):

```bash
curl -sS -X POST https://kassa.viennax.at/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"password":"absichtlich-falsch","deviceName":"Pruefung"}'
```

Erwartete Antwort: `{"error":true,"reason":"Falsches Passwort"}`.

### Schritt 5b — WebSocket prüfen (nicht überspringen)

Das ist der wichtigste Test des ganzen Setups, weil sein Ausfall nichts
sichtbar kaputt macht: Ohne WebSocket lässt sich weiterhin einwandfrei
kassieren, aber die Geräte aktualisieren sich nicht mehr gegenseitig. Das
fällt erst im Betrieb auf, wenn zwei Leute denselben Tisch bearbeiten.

Erst ein Anmelde-Token holen (das echte Passwort aus der `.env` einsetzen):

```bash
TOKEN=$(curl -sS -X POST https://kassa.viennax.at/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"password":"DEIN-PASSWORT","deviceName":"Pruefung"}' \
  | sed 's/.*"token":"\([^"]*\)".*/\1/')
echo "$TOKEN"
```

Dann den Upgrade auf WebSocket versuchen:

```bash
curl -sSi -N -H "Authorization: Bearer $TOKEN" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==" \
  https://kassa.viennax.at/ws | head -3
```

| Antwort | Bedeutung |
|---|---|
| `101 Switching Protocols` | Alles richtig — die Live-Aktualisierung funktioniert. |
| `200` oder `400` | nginx reicht den Upgrade nicht durch. Siehe Abschnitt 9, WebSocket. |
| `401` | Das Token stimmt nicht — den Schritt davor wiederholen. |

Die Erneuerung läuft ab jetzt automatisch. Kontrollieren lässt sie sich mit:

```bash
certbot renew --dry-run
```

### Schritt 6 — iPhones einrichten

In der Kassa-App die Adresse `https://kassa.viennax.at` und das Passwort aus
der `.env` eintragen. Das ist pro Gerät einmal nötig.

### Schritt 7 — Backup einrichten

Siehe Abschnitt 6 weiter unten. **Bitte gleich machen, nicht später** — ein
Backup, das erst nach dem ersten Datenverlust eingerichtet wird, hilft nicht.

---

## 4. Täglicher Betrieb

```bash
cd /opt/kassa/deploy

docker compose ps          # Läuft der Server?
docker compose logs -f     # Live mitschauen (beenden mit Strg+C)
docker compose restart     # Neu starten, wenn etwas hängt
```

Die letzten 100 Zeilen:

```bash
docker compose logs --tail=100 kassa
```

Das nginx hat eigene Logdateien, nur für das Kassasystem:

```bash
tail -50 /var/log/nginx/kassa.access.log
tail -50 /var/log/nginx/kassa.error.log
```

Die Container-Logs werden automatisch begrenzt (max. 30 MB), es kann also
nichts unbemerkt die Platte vollschreiben. Die nginx-Logs übernimmt das
`logrotate` des Systems.

**Neustart des ganzen VPS:** Container und nginx starten von selbst wieder.
Es ist nichts von Hand zu tun.

---

## 5. Update auf eine neue Version

> **Hinweis für den Küchenbon-Dienst:** Sobald der Server um den Endpunkt
> `GET /devices` ergänzt wurde (für den Küchenbon-Dienst auf dem alten Mac
> in der Küche, siehe `PrintService/README.md`), muss diese Version einmal
> neu gebaut werden: `docker compose build && docker compose up -d`. Das
> am besten außerhalb der Öffnungszeiten machen, weil der Container dabei
> kurz durchstartet.

```bash
cd /opt/kassa/deploy

./backup.sh                       # zuerst sichern
git pull                          # neue Version holen
docker compose up -d --build      # neu bauen und starten
docker compose ps                 # kontrollieren
docker compose logs --tail=50 kassa
```

Das betrifft **nur den Container**. Am nginx und am Zertifikat muss dabei
nichts angefasst werden — die laufen unabhängig weiter, und während der
wenigen Sekunden Neustart antwortet die Seite kurz mit `502` (siehe
Abschnitt 9). Die anderen Seiten auf dem Server merken davon nichts.

Nur wenn sich `nginx-kassa.conf` durch das Update geändert hat, die Datei
neu kopieren:

```bash
cp nginx-kassa.conf /etc/nginx/sites-available/kassa
nginx -t && systemctl reload nginx
```

Die Datenbank bleibt bei alldem erhalten — sie liegt im Volume, nicht im
Container.

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

> **Wichtig zu wissen:** Der Server liest die CSV **nur beim allerersten
> Start** von selbst ein, solange noch kein einziger Artikel in der Datenbank
> steht. Danach passiert beim Neustart nichts mehr — sonst würde jeder
> Neustart die Preise überschreiben. Für eine Änderung im laufenden Betrieb
> muss der Import deshalb ausdrücklich angestoßen werden.

1. **Datei anpassen** — am einfachsten am eigenen Rechner in einem
   Tabellenprogramm, dann per Git oder `scp` auf den VPS. Die Datei sollte
   auch im Projekt aktualisiert werden, damit eine spätere Neuinstallation
   gleich den richtigen Stand hat.

2. **Vorher sichern:**

   ```bash
   cd /opt/kassa/deploy
   ./backup.sh
   ```

3. **Datei in den Container kopieren und den Import auslösen:**

   ```bash
   docker compose cp ../preisliste.csv kassa:/data/preisliste.csv
   docker compose exec kassa ./Server import-prices /data/preisliste.csv
   ```

   Der zweite Befehl gibt aus, wie viele Artikel eingelesen wurden, z. B.
   `32 Artikel importiert, catalogVersion=4.` Kommt keine solche Zeile,
   wurde nichts geändert.

4. **Auf den iPhones** die App einmal neu starten, falls die neuen Preise
   nicht von selbst ankommen.

> Am besten außerhalb der Öffnungszeiten machen. Der Import schreibt in
> dieselbe Datenbank, in der gerade kassiert wird.

**Was der Import tut und was nicht:** Bekannte Artikel bekommen den neuen
Preis, neue kommen dazu, und Artikel, die nicht mehr in der CSV stehen,
werden stillgelegt — **gelöscht wird nie**. Das muss so sein: Bereits
gebuchte Zeilen behalten den Preis, der zum Zeitpunkt der Bestellung
gegolten hat. Sonst würden alte Tagesabschlüsse nachträglich andere Summen
ergeben.

---

## 8b. Ein Gerät aussperren (Handy verloren, Aushilfe weg)

Alle Geräte melden sich mit demselben Passwort aus der `.env` an. Danach
bekommt jedes Gerät ein eigenes, dauerhaftes Token — deshalb gilt:

> **Das Passwort zu ändern sperrt bereits angemeldete Geräte NICHT aus.**
> Ein neues Passwort verhindert nur neue Anmeldungen. Ein Handy, das sich
> einmal angemeldet hat, behält seinen Zugang, bis es hier gelöscht wird.

Muss ein Gerät wirklich raus, geht das direkt in der Datenbank.

Das läuft über dasselbe `sqlite3` auf dem Server, das auch `backup.sh`
benutzt (im Container ist es nicht installiert). Zuerst den Pfad zur
Datenbank ermitteln:

```bash
DB="$(sudo docker volume inspect --format '{{ .Mountpoint }}' kassa_data)/kassa.sqlite"
```

**1. Nachsehen, welche Geräte angemeldet sind:**

```bash
sudo sqlite3 -header -column "$DB" ".timeout 15000" \
  "SELECT name, datetime(last_seen_at,'localtime') AS zuletzt FROM devices ORDER BY last_seen_at DESC;"
```

**2. Das betroffene Gerät löschen** (Name aus der Liste oben einsetzen):

```bash
sudo sqlite3 "$DB" ".timeout 15000" \
  "DELETE FROM devices WHERE name = 'Schank';"
```

Das `.timeout` ist kein Zierrat: Der Server schreibt parallel weiter, und
ohne Wartezeit bricht der Befehl bei gleichzeitigem Zugriff einfach ab.

Die Sperre wirkt sofort, ein Neustart ist nicht nötig. Auf dem betroffenen
Handy verlangt die App beim nächsten Zugriff wieder das Passwort.

**3. Anschließend das Passwort wechseln,** sonst meldet sich dasselbe Gerät
einfach neu an:

```bash
nano .env                 # KASSA_PASSWORD neu setzen
docker compose up -d
```

Danach müssen sich **alle** verbliebenen Geräte einmal neu anmelden.

### Zwei Dinge, die dabei zu beachten sind

- **Bereits gebuchte Umsätze bleiben erhalten.** Bestellungen und
  Kassiervorgänge speichern die Geräte-ID als Text und hängen nicht am
  Eintrag in dieser Tabelle. In der Konfliktmeldung steht danach aber nur
  noch die ID statt des Namens.
- **Noch nicht übertragene Buchungen auf dem gesperrten Handy sind verloren.**
  Sperre deshalb, wenn möglich, erst nachdem das Gerät zuletzt online war —
  in der Liste oben steht dafür die Spalte `zuletzt`.

## 9. Wenn's nicht geht

Vorweg die nützlichste Unterscheidung: **Antwortet der Container, oder
antwortet nur nginx?**

```bash
curl -i http://127.0.0.1:8080/config      # der Container direkt
curl -i https://kassa.viennax.at/config   # der ganze Weg von außen
```

Klappt der erste Befehl, aber der zweite nicht, liegt es am nginx oder am
Zertifikat. Klappt schon der erste nicht, liegt es am Container.

### 502 Bad Gateway

nginx antwortet, erreicht aber den Kassaserver nicht. Das ist der häufigste
Fall.

```bash
cd /opt/kassa/deploy
docker compose ps
```

1. **Der Container läuft nicht.** Steht dort nichts oder `exited`:

   ```bash
   docker compose logs --tail=50 kassa
   docker compose up -d
   ```

   Beim Start abgebrochen? Oft fehlt die `.env` oder das
   `KASSA_PASSWORD` darin — die Fehlermeldung sagt das im Klartext.

2. **Der Container läuft, aber auf einem anderen Port.** Die beiden Werte
   müssen zusammenpassen:

   ```bash
   grep KASSA_HOST_PORT .env
   grep proxy_pass /etc/nginx/sites-available/kassa
   ```

   Steht in der `.env` z. B. `8090`, muss im `proxy_pass` auch
   `http://127.0.0.1:8090` stehen. Nach einer Änderung:
   `nginx -t && systemctl reload nginx`.

3. **Kurzzeitig nach einem Update** ist `502` normal — der Container startet
   gerade neu. Nach ein paar Sekunden noch einmal probieren.

### 404 oder die falsche Seite statt der Kassa-Antwort

Es kommt eine fremde Seite, die Standardseite von nginx oder ein `404`. Dann
greift der Kassa-Block nicht, und nginx bedient die Anfrage mit einem anderen
Block.

1. **Ist der Block überhaupt aktiv?**

   ```bash
   ls -l /etc/nginx/sites-enabled/ | grep kassa
   ```

   Fehlt die Zeile, wurde der Symlink aus Schritt 4 nicht angelegt:

   ```bash
   ln -s /etc/nginx/sites-available/kassa /etc/nginx/sites-enabled/kassa
   nginx -t && systemctl reload nginx
   ```

2. **Stimmt der `server_name`?** Er muss exakt der aufgerufenen Domain
   entsprechen — ohne `https://`, ohne Schrägstrich:

   ```bash
   grep server_name /etc/nginx/sites-available/kassa
   ```

3. **Wurde nach der Änderung neu geladen?** Ein `nginx -t` allein bewirkt
   nichts, es prüft nur:

   ```bash
   nginx -t && systemctl reload nginx
   ```

### Das Zertifikat kommt nicht / die App meldet einen HTTPS-Fehler

Typisches Zeichen: `curl` meckert über das Zertifikat, weil nginx mangels
passendem Block auf eine andere Seite zurückfällt und deren Zertifikat
zeigt.

1. **Zeigt die Domain auf den VPS?**

   ```bash
   dig +short kassa.viennax.at      # muss die IP des VPS liefern
   ```

   Wenn nicht: DNS-Eintrag beim Domain-Anbieter korrigieren und bis zu einer
   Stunde warten.

2. **Ist Port 80 von außen erreichbar?** certbot prüft darüber die Domain.
   Firewall kontrollieren (`ufw status`) — auch die Firewall im Webpanel des
   VPS-Anbieters, die gibt es oft zusätzlich.

3. **Gibt es den Port-80-Block schon?** certbot findet die Domain nur, wenn
   der Block aus Schritt 4 aktiv ist. Also zuerst Schritt 4 fertig machen,
   dann:

   ```bash
   certbot --nginx -d kassa.viennax.at
   ```

4. **Was sagt certbot selbst?**

   ```bash
   certbot certificates          # welche Zertifikate gibt es, wie lange gültig?
   certbot renew --dry-run       # Erneuerung proben, ohne etwas zu ändern
   ```

5. **Zu viele Fehlversuche.** Let's Encrypt sperrt nach etwa fünf
   fehlgeschlagenen Versuchen pro Domain und Woche. Dann erst die Ursache
   beheben, danach eine Stunde warten. Zum Ausprobieren hilft
   `certbot --nginx --dry-run -d kassa.viennax.at` — das zählt nicht auf das
   Limit.

### Die iPhones aktualisieren sich nicht gegenseitig (WebSocket)

**Das Bild:** Kassieren funktioniert, Bestellungen werden gespeichert, alles
wirkt normal — aber die anderen Geräte sehen eine neue Bestellung erst, wenn
man die App neu startet. Nichts stürzt ab, es gibt keine Fehlermeldung.

Genau so sieht es aus, wenn die WebSocket-Verbindung nicht durchkommt. Das
ist der unangenehmste Fehler, weil er leicht monatelang unbemerkt bleibt.

**Der Grund:** nginx reicht WebSocket-Verbindungen — anders als manch andere
Proxys — **nicht von selbst** durch. Es braucht dafür ausdrücklich die drei
Zeilen `proxy_http_version 1.1`, `proxy_set_header Upgrade` und
`proxy_set_header Connection` sowie die `map`-Zuordnung darüber. Fehlt davon
etwas, behandelt nginx die Anfrage als ganz normalen Aufruf: Der Server
antwortet mit `200` oder `404` statt mit `101`, die Verbindung kommt nie
zustande — und der Rest der App läuft weiter, als wäre nichts.

**So prüft man es.** Von außen, mit einem echten Upgrade-Versuch:

```bash
curl -i -N \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGVzdHRlc3R0ZXN0dGVzdA==" \
  https://kassa.viennax.at/ws
```

| Antwort | Bedeutung |
|---------|-----------|
| `101 Switching Protocols` | Richtig. Die WebSockets kommen durch. |
| `200` oder `404` | Der Server antwortet, aber die Upgrade-Header fehlen — genau der oben beschriebene Fehler. |
| `502` | Der Container läuft nicht, siehe „502 Bad Gateway". |

**Wenn `101` fehlt**, die vier Stellen in der nginx-Datei kontrollieren:

```bash
grep -nE "^[[:space:]]*(map|proxy_http_version|proxy_set_header (Upgrade|Connection))" \
  /etc/nginx/sites-available/kassa
```

**Es müssen genau vier Zeilen herauskommen:**

```
24:map $http_upgrade $kassa_connection_upgrade {
48:        proxy_http_version 1.1;
49:        proxy_set_header Upgrade    $http_upgrade;
50:        proxy_set_header Connection $kassa_connection_upgrade;
```

Die Zeilennummern dürfen abweichen, die vier Zeilen selbst nicht. Kommt
weniger heraus, fehlt genau das, was die WebSockets durchlässt. Dann ist die
einfachste Reparatur, die geprüfte Vorlage neu zu kopieren:

```bash
cd /opt/kassa/deploy
cp nginx-kassa.conf /etc/nginx/sites-available/kassa
nano /etc/nginx/sites-available/kassa    # server_name kontrollieren
nginx -t && systemctl reload nginx
certbot --nginx -d kassa.viennax.at      # HTTPS-Teil wieder ergänzen
```

> Achtung: Die Vorlage enthält nur den Port-80-Block. Wird sie neu kopiert,
> ist der von certbot ergänzte HTTPS-Teil weg und muss mit dem letzten
> Befehl wieder hinzugefügt werden.

**Was normal ist:** Mobilfunknetze trennen ruhende Verbindungen. Wenn ein
iPhone nach längerer Pause kurz nicht aktuell ist und sich nach ein paar
Sekunden von selbst fängt, ist das in Ordnung. Der `proxy_read_timeout` von
einer Stunde in der nginx-Datei sorgt dafür, dass nginx die Verbindung nicht
schon nach einer Minute Stille abräumt — dieser Wert sollte nicht verkleinert
werden.

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

docker compose restart                        # 1. Container neu starten (Sekunden)
systemctl reload nginx                        # 2. nginx neu einlesen
docker compose down && docker compose up -d   # 3. Container neu aufsetzen
reboot                                        # 4. ganzen VPS neu starten
```

Die Daten bleiben bei allen vier Schritten erhalten. Erst wenn die Datenbank
selbst beschädigt ist, kommt `./restore.sh` an die Reihe (Abschnitt 7).

> Schritt 4 startet auch die anderen Seiten auf dem Server neu. Im
> Heurigenbetrieb ist das kein Problem, aber man sollte es wissen.

---

## Merkzettel

```bash
cd /opt/kassa/deploy

docker compose ps                 # Läuft der Container?
docker compose logs -f kassa      # Log mitlesen
docker compose restart            # Neustart
./backup.sh                       # sichern
./restore.sh                      # Sicherungen auflisten

curl -i http://127.0.0.1:8080/config      # Container direkt testen
curl -i https://kassa.viennax.at/config   # ganzen Weg testen

nginx -t && systemctl reload nginx        # nginx-Änderung übernehmen
certbot certificates                      # Zertifikat und Laufzeit
```

Wichtige Orte:

| Was | Wo |
|-----|-----|
| Einstellungen und Passwort | `/opt/kassa/deploy/.env` |
| nginx-Block (aktiv) | `/etc/nginx/sites-available/kassa` |
| nginx-Block (Vorlage) | `/opt/kassa/deploy/nginx-kassa.conf` |
| nginx-Logs der Kassa | `/var/log/nginx/kassa.access.log`, `kassa.error.log` |
| Backups | `/var/backups/kassa/` |
| Backup-Protokoll | `/var/log/kassa-backup.log` |
| Datenbank (im Docker-Volume) | `docker volume inspect kassa_data` |
