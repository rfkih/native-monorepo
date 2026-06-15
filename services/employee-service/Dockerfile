# Multi-stage build for employee-service.
# Stage 1 builds the boot jar with the Java 25 toolchain; stage 2 ships a slim
# JRE-only runtime image that launches it.

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the Gradle wrapper + build setup first so dependency resolution is cached
# across source-only changes.
COPY gradlew gradlew.bat settings.gradle.kts gradle.properties build.gradle.kts ./
COPY gradle ./gradle
COPY build-logic ./build-logic
COPY libs ./libs
# settings.gradle.kts `include`s every services:* project AND service-template; Gradle
# refuses to configure the build unless each included project's directory exists. We only
# build this one service, but its settings evaluation still needs the other project dirs
# present — so copy the whole services tree + service-template. (Copying just this one
# service dir fails: "Configuring project ':services:gateway' without an existing
# directory is not allowed".)
COPY service-template ./service-template
COPY services ./services

RUN chmod +x ./gradlew \
    && ./gradlew :services:employee-service:bootJar --no-daemon

# ---- runtime ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as an unprivileged user, never root.
RUN groupadd --system native && useradd --system --gid native --home /app native-svc
USER native-svc

COPY --from=build /workspace/services/employee-service/build/libs/*.jar app.jar

# PII encryption key (rule 6) — injected from Vault at deploy time, NEVER baked into the image.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
