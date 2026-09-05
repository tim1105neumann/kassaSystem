#!/usr/bin/env bash
#
# Spielt eine gesicherte Kassa-Datenbank zurueck.
#
# Aufruf:   sudo ./restore.sh                     -> zeigt vorhandene Backups
#           sudo ./restore.sh /var/backups/kassa/kassa_2026-09-04_033000.sqlite
#
# Der Server wird dafuer kurz angehalten und danach wieder gestartet.
# Der aktuelle Stand wird vorher zur Seite gelegt, falls die Sicherung
# doch nicht die richtige war.

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/kassa}"
VOLUME="${VOLUME:-kassa_data}"
DB_NAME="kassa.sqlite"

# Ordner mit docker-compose.yml — also der Ordner, in dem dieses Skript liegt.
COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Vorbedingungen --------------------------------------------------------

for cmd in docker sqlite3; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "FEHLER: '$cmd' ist nicht installiert." >&2
        echo "        Nachinstallieren mit:  sudo apt install sqlite3" >&2
        exit 1
    fi
done

# --- Backup auswaehlen -----------------------------------------------------

if [ $# -lt 1 ]; then
    echo "Vorhandene Sicherungen in $BACKUP_DIR:"
    echo
    if ! ls -1t "$BACKUP_DIR"/kassa_*.sqlite 2>/dev/null; then
        echo "  (keine gefunden)"
    fi
    echo
    echo "Aufruf:  sudo $0 <pfad-zur-sicherung>"
    exit 1
fi

BACKUP_FILE="$1"

if [ ! -f "$BACKUP_FILE" ]; then
    echo "FEHLER: Datei nicht gefunden: $BACKUP_FILE" >&2
    exit 1
fi

# Die Sicherung pruefen, BEVOR der laufende Betrieb angefasst wird.
echo "Pruefe die Sicherung ..."
RESULT="$(sqlite3 "$BACKUP_FILE" "PRAGMA integrity_check;" 2>/dev/null || echo "unlesbar")"
if [ "$RESULT" != "ok" ]; then
    echo "FEHLER: '$BACKUP_FILE' ist keine brauchbare Datenbank (Ergebnis: $RESULT)." >&2
    echo "        Es wurde nichts veraendert." >&2
    exit 1
fi
echo "Die Sicherung ist in Ordnung."

if ! VOLUME_PATH="$(docker volume inspect --format '{{ .Mountpoint }}' "$VOLUME" 2>/dev/null)"; then
    echo "FEHLER: Docker-Volume '$VOLUME' existiert nicht." >&2
    exit 1
fi

DB_FILE="$VOLUME_PATH/$DB_NAME"

# --- Sicherheitsabfrage ----------------------------------------------------

echo
echo "============================================================"
echo " ACHTUNG: Die laufende Datenbank wird ueberschrieben."
echo
echo "   Sicherung:  $BACKUP_FILE"
echo "               vom $(date -r "$BACKUP_FILE" '+%d.%m.%Y %H:%M' 2>/dev/null || echo 'unbekannt')"
echo "   Ziel:       $DB_FILE"
if [ -f "$DB_FILE" ]; then
    echo "               (derzeitiger Stand: $(du -h "$DB_FILE" | cut -f1))"
fi
echo
echo " Alle Bestellungen, die nach der Sicherung eingegeben wurden,"
echo " sind danach weg."
echo "============================================================"
echo
read -r -p "Wirklich zurueckspielen? Zum Bestaetigen JA eintippen: " ANSWER

if [ "$ANSWER" != "JA" ]; then
    echo "Abgebrochen. Es wurde nichts veraendert."
    exit 1
fi

# --- Zurueckspielen --------------------------------------------------------

echo
echo "Halte den Server an ..."
docker compose -f "$COMPOSE_DIR/docker-compose.yml" stop kassa

# Wem gehoert die Datei bisher? Der Server laeuft als eigener Benutzer und
# kann nach dem Zurueckspielen sonst nicht mehr schreiben.
if [ -f "$DB_FILE" ]; then
    OWNER="$(stat -c '%u:%g' "$DB_FILE")"
    MODE="$(stat -c '%a' "$DB_FILE")"

    ASIDE="$DB_FILE.vor-restore-$(date +%Y-%m-%d_%H%M%S)"
    cp -p "$DB_FILE" "$ASIDE"
    echo "Bisheriger Stand liegt zur Sicherheit unter:"
    echo "  $ASIDE"
else
    OWNER=""
    MODE="600"
    ASIDE=""
    echo "Hinweis: Es war noch keine Datenbank vorhanden."
fi

# Die WAL-Dateien MUESSEN weg. Bleiben sie liegen, spielt SQLite beim
# naechsten Start die alten Aenderungen ueber die zurueckgespielte
# Datenbank drueber — und man hat einen wirren Mischstand.
rm -f "$DB_FILE-wal" "$DB_FILE-shm"

cp "$BACKUP_FILE" "$DB_FILE"

if [ -n "$OWNER" ]; then
    chown "$OWNER" "$DB_FILE"
fi
chmod "$MODE" "$DB_FILE"

echo "Starte den Server wieder ..."
docker compose -f "$COMPOSE_DIR/docker-compose.yml" start kassa

echo
echo "Fertig. Bitte kontrollieren:"
echo "  docker compose -f $COMPOSE_DIR/docker-compose.yml logs -f kassa"
if [ -n "$ASIDE" ]; then
    echo
    echo "Wenn alles passt, kann $ASIDE geloescht werden."
fi
