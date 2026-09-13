#!/bin/sh
set -e

if [ -n "$GRAFANA_PROMETHEUS_URL" ]; then
    # Spring binds to $PORT (server.port: ${PORT:8080} in application.yml) - the
    # scrape target must match, since Railway assigns a dynamic port, not always 8080.
    sed "s/__APP_PORT__/${PORT:-8080}/" /app/vmagent-scrape-config.yml > /tmp/vmagent-scrape-config.yml
    vmagent \
        -promscrape.config=/tmp/vmagent-scrape-config.yml \
        -remoteWrite.tmpDataPath=/tmp/vmagent-remotewrite-data \
        -remoteWrite.url="$GRAFANA_PROMETHEUS_URL" \
        -remoteWrite.basicAuth.username="$GRAFANA_PROMETHEUS_USER" \
        -remoteWrite.basicAuth.password="$GRAFANA_PROMETHEUS_TOKEN" \
        -httpListenAddr=127.0.0.1:8429 \
        -loggerLevel=WARN &
fi

exec java -XX:+UseSerialGC -XX:MaxRAMPercentage=75 -jar app.jar
