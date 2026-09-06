#!/usr/bin/env bash
#
# Baut das Küchenbon-Binary für den alten Mac in der Küche.
#
# Der alte Mac läuft macOS 11.7.11 (Big Sur) auf Intel (x86_64). Der
# Entwicklungsrechner ist arm64 und meist auf einer neueren macOS-Version —
# es wird also cross-gebaut. Das Ergebnis kann auf diesem Rechner nicht
# ausgeführt werden, deshalb prüft dieses Skript das Binary stattdessen mit
# `file` und `otool`, statt es zu starten.
#
# Aufruf: ./build-release.sh   (aus einem beliebigen Verzeichnis heraus)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

BINARY_NAME="kuechenbon"
OUTPUT_DIR="$SCRIPT_DIR/build"
OUTPUT_PATH="$OUTPUT_DIR/$BINARY_NAME"

echo "==> Baue $BINARY_NAME für x86_64 (macOS 11)"
swift build -c release --arch x86_64

# Statt den Pfad zu erraten, fragt man swift build selbst danach. Der Pfad
# hängt von der Swift-Version ab (z. B. .build/x86_64-apple-macosx/release)
# und soll hier nicht hart codiert werden.
BIN_PATH="$(swift build -c release --arch x86_64 --show-bin-path)"
BUILT_BINARY="$BIN_PATH/$BINARY_NAME"

if [ ! -f "$BUILT_BINARY" ]; then
  echo "FEHLER: Nach dem Build wurde kein Binary unter '$BUILT_BINARY' gefunden." >&2
  echo "        Prüfen, ob im Package.swift ein ausführbares Ziel namens '$BINARY_NAME' existiert." >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
cp "$BUILT_BINARY" "$OUTPUT_PATH"
echo "==> Binary liegt jetzt unter: $OUTPUT_PATH"

echo "==> Prüfe Architektur (muss x86_64 sein)"
FILE_OUTPUT="$(file "$OUTPUT_PATH")"
echo "    $FILE_OUTPUT"
if ! echo "$FILE_OUTPUT" | grep -q "x86_64"; then
  echo "FEHLER: Das Binary ist nicht für x86_64 gebaut. Auf dem alten Mac würde es gar nicht erst starten." >&2
  exit 1
fi

echo "==> Prüfe Deployment-Target (muss minos 11.0 sein)"
MINOS_LINE="$(otool -l "$OUTPUT_PATH" | grep -A3 -E "LC_BUILD_VERSION|LC_VERSION_MIN_MACOSX" | grep -E "minos|version" || true)"
echo "    $MINOS_LINE"
if ! echo "$MINOS_LINE" | grep -qE "(minos|version) 11\."; then
  echo "FEHLER: Das Binary ist nicht für macOS 11 gebaut (erwartet: minos 11.x)." >&2
  echo "        Prüfen, ob 'platforms: [.macOS(.v11)]' im Package.swift steht." >&2
  exit 1
fi

# Diese Prüfung ist die wichtigste im ganzen Skript:
#
# Swift-Nebenläufigkeit (async/await, Task, Actor, ...) wird auf macOS-
# Versionen älter als 12 nicht vom Betriebssystem selbst bereitgestellt.
# Der Compiler löst das normalerweise über "Back-Deployment"-Bibliotheken
# wie libswift_Concurrency.dylib, die zusammen mit einer App ausgeliefert
# werden (App-Bundle mit eingebetteten Frameworks). Ein nacktes
# Kommandozeilenprogramm ohne App-Bundle hat diesen Mechanismus nicht —
# verlinkt das Binary trotzdem gegen diese Bibliothek, fehlt sie beim Start
# auf dem alten Mac schlicht, und das Programm stürzt sofort beim Start ab
# ("Library not loaded"). Das lässt sich hier vorab erkennen, ohne den
# alten Mac überhaupt anzufassen.
echo "==> Prüfe, dass keine Concurrency-Backdeployment-Bibliothek verlinkt ist"
LINKED_LIBS="$(otool -L "$OUTPUT_PATH")"
if echo "$LINKED_LIBS" | grep -q "libswift_Concurrency.dylib"; then
  echo "FEHLER: Das Binary verlinkt gegen libswift_Concurrency.dylib." >&2
  echo "        Diese Bibliothek gibt es auf macOS 11 für ein einzelnes" >&2
  echo "        Kommandozeilenprogramm nicht (kein App-Bundle). Das Binary" >&2
  echo "        würde auf dem alten Mac beim Start mit 'Library not loaded'" >&2
  echo "        abstürzen." >&2
  echo "        Ursache meist: async/await oder Actor-Code im Quelltext." >&2
  echo "        Betroffene Stelle im Code entfernen oder ohne Swift-" >&2
  echo "        Concurrency umschreiben, dann neu bauen." >&2
  exit 1
fi

echo "==> Alle Prüfungen bestanden. $OUTPUT_PATH ist bereit für den alten Mac."
