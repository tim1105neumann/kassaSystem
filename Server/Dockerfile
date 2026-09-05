# Build-Kontext ist die REPO-WURZEL, weil Server/ eine Pfad-Abhängigkeit auf
# ../Shared hat:
#   docker build --platform=linux/amd64 -f Server/Dockerfile -t kassa-server .
#
# --platform ist gesetzt, weil auf Apple Silicon gebaut, aber auf einem
# amd64-Debian-VPS ausgeführt wird.

# ---------- Build ----------
FROM --platform=linux/amd64 swift:6.2-noble AS build
WORKDIR /build

# Shared und die Manifeste zuerst: der Layer mit den aufgelösten Abhängigkeiten
# bleibt dann über Änderungen am Servercode hinweg im Cache.
COPY Shared ./Shared
COPY Server/Package.swift Server/Package.resolved ./Server/
WORKDIR /build/Server
RUN swift package resolve

COPY Server/Sources ./Sources
# Tests muessen mit, obwohl sie nicht gebaut werden: SwiftPM wertet das ganze
# Manifest aus, und ohne Tests/ServerTests sucht es den Pfad des Test-Targets
# weiter und kollidiert mit Sources/. Nur Build-Stufe, nicht im Runtime-Image.
COPY Server/Tests ./Tests
RUN swift build -c release --product Server \
 && mkdir -p /staging \
 && cp "$(swift build -c release --product Server --show-bin-path)/Server" /staging/Server

# ---------- Runtime ----------
FROM --platform=linux/amd64 swift:6.2-noble-slim

# tzdata ist nicht optional: ohne die Zonendatenbank fällt
# TimeZone("Europe/Vienna") auf UTC zurück und der Betriebstag verrutscht.
# curl braucht der Healthcheck aus deploy/docker-compose.yml.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tzdata ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --user-group --create-home --home-dir /app --shell /usr/sbin/nologin kassa \
 && mkdir -p /data \
 && chown kassa:kassa /data

WORKDIR /app
COPY --from=build --chown=kassa:kassa /staging/Server ./Server
# Für den Erstimport beim ersten Start.
COPY --chown=kassa:kassa preisliste.csv ./preisliste.csv

USER kassa
VOLUME ["/data"]
EXPOSE 8080

ENV DATABASE_PATH=/data/kassa.sqlite \
    PORT=8080 \
    TZ=Europe/Vienna
# KASSA_PASSWORD kommt von außen — niemals ins Image backen.

ENTRYPOINT ["./Server"]
CMD ["serve", "--env", "production"]
