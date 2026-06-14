# Multi-stage build for finance-service.
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
COPY services/finance-service ./services/finance-service

RUN chmod +x ./gradlew \
    && ./gradlew :services:finance-service:bootJar --no-daemon

# ---- runtime ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as an unprivileged user, never root.
RUN groupadd --system native && useradd --system --gid native --home /app native-svc
USER native-svc

COPY --from=build /workspace/services/finance-service/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
