# syntax=docker/dockerfile:1

FROM docker.io/eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies > /dev/null || true
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test

FROM docker.io/victoriametrics/vmagent:latest AS vmagent

FROM docker.io/bellsoft/liberica-runtime-container:jre-21-slim-musl
RUN addgroup -S app && adduser -S -u 1000 -G app app
WORKDIR /app
COPY --from=build /app/build/libs/app.jar app.jar
COPY --from=vmagent /vmagent-prod /usr/local/bin/vmagent
COPY vmagent-scrape-config.yml docker-entrypoint.sh ./
RUN chmod +x /usr/local/bin/vmagent docker-entrypoint.sh
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["./docker-entrypoint.sh"]
