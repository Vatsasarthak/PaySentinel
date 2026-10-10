# syntax=docker/dockerfile:1

# ---- Build stage -------------------------------------------------------------
# Base images are pinned by digest (Dependabot keeps them current).
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
# Tests need Docker (Testcontainers) and run in CI, not inside the image build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && cp target/outbox-*.jar /workspace/app.jar

# ---- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61

RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
