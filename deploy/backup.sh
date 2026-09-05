#!/usr/bin/env bash
#
# Sichert die Kassa-Datenbank.
#
# Aufruf:   sudo ./backup.sh
#           sudo ./backup.sh /pfad/zum/backupordner
#
# Automatisch jede Nacht um 03:30 Uhr (der Heuriger hat dann zu).
# Cron-Eintrag anlegen mit "sudo crontab -e", dann diese Zeile einfuegen:
#
#   30 3 * * * /opt/kassa/deploy/backup.sh >> /var/log/kassa-backup.log 2>&1
#
# (Pfad anpassen, falls das Projekt woanders liegt.)
#
# Warum nicht einfach die Datei kopieren? SQLite laeuft im WAL-Modus.
# Ein simples "cp" waehrend des Betriebs liefert eine halbfertige Datei,
# die man erst im Ernstfall als kaputt erkennt. ".backup" benutzt die
# eingebaute Sicherungsfunktion von SQLite und liefert immer einen
# in sich stimmigen Stand — auch waehrend geschrieben wird.

set -euo pipefail

# --- Einstellungen ---------------------------------------------------------

# Wohin die Backups geschrieben werden. Per Argument oder Umgebungs-
# variable BACKUP_DIR ueberschreibbar.
BACKUP_DIR="${1:-${BACKUP_DIR:-/var/backups/kassa}}"

# Wie viele Tage die Backups aufgehoben werden.
KEEP_DAYS="${KEEP_DAYS:-14}"

# Name des Docker-Volumes mit der Datenbank (aus docker-compose.yml:
# Projektname "kassa" + Volume "data").
VOLUME="${VOLUME:-kassa_data}"

# Dateiname der Datenbank innerhalb des Volumes (= DATABASE_PATH).
DB_NAME="kassa.sqlite"

# --- Vorbedingungen pruefen ------------------------------------------------

for cmd in docker sqlite3; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "FEHLER: '$cmd' ist nicht installiert." >&2
        echo "        Nachinstallieren mit:  sudo apt install sqlite3" >&2
        exit 1
    fi
done

# Den echten Ablageort des Volumes auf der Platte herausfinden, statt ihn
# fest einzutippen. Der Ordner gehoert root — das Skript braucht sudo.
if ! VOLUME_PATH="$(docker volume inspect --format '{{ .Mountpoint }}' "$VOLUME" 2>/dev/null)"; then
    echo "FEHLER: Docker-Volume '$VOLUME' existiert nicht." >&2
    echo "        Vorhandene Volumes:" >&2
    docker volume ls >&2
    exit 1
fi

DB_FILE="$VOLUME_PATH/$DB_NAME"

if [ ! -f "$DB_FILE" ]; then
    echo "FEHLER: Datenbank nicht gefunden: $DB_FILE" >&2
    echo "        Laeuft der Server schon einmal gelaufen? (docker compose ps)" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"

# --- Sicherung erstellen ---------------------------------------------------

STAMP="$(date +%Y-%m-%d_%H%M%S)"
TARGET="$BACKUP_DIR/kassa_$STAMP.sqlite"
TEMP="$TARGET.unfertig"

# Halbfertige Datei aufraeumen, falls das Skript unterwegs abbricht.
trap 'rm -f "$TEMP"' EXIT

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Sichere $DB_FILE"

# .timeout wartet, falls der Server gerade schreibt, statt sofort
# aufzugeben. .backup erzeugt eine saubere Kopie ohne WAL-Reste.
sqlite3 "$DB_FILE" ".timeout 15000" ".backup '$TEMP'"

# Nachkontrolle: lieber jetzt merken, dass etwas faul ist, als im Ernstfall.
if [ ! -s "$TEMP" ]; then
    echo "FEHLER: Das Backup ist leer geblieben." >&2
    exit 1
fi

RESULT="$(sqlite3 "$TEMP" "PRAGMA integrity_check;")"
if [ "$RESULT" != "ok" ]; then
    echo "FEHLER: Das Backup ist beschaedigt (integrity_check: $RESULT)." >&2
    exit 1
fi

# Erst jetzt, nach bestandener Pruefung, den endgueltigen Namen vergeben.
mv "$TEMP" "$TARGET"
trap - EXIT
chmod 600 "$TARGET"

SIZE="$(du -h "$TARGET" | cut -f1)"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Fertig: $TARGET ($SIZE)"

# --- Alte Backups wegraeumen -----------------------------------------------

# Loescht nur Dateien mit genau diesem Namensmuster in genau diesem Ordner.
# Das heutige Backup ist davon nie betroffen.
DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'kassa_*.sqlite' -mtime "+$KEEP_DAYS" -print -delete | wc -l)"
echo "Aufbewahrung: $KEEP_DAYS Tage, $DELETED alte Sicherung(en) geloescht."

COUNT="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'kassa_*.sqlite' | wc -l)"
echo "Im Ordner $BACKUP_DIR liegen jetzt $COUNT Sicherungen."
