# CI-only runtime image (used by .github/workflows/images.yml): expects a PRE-BUILT
# boot jar and is invoked with the jar's own directory as the build context:
#
#   ./gradlew :services:<svc>:bootJar
#   docker build -f docker/runtime.Dockerfile -t <svc> services/<svc>/build/libs
#
# The context must contain exactly ONE jar (a fresh bootJar output — a full `build`
# also leaves a *-plain.jar behind, which would break the COPY glob).
#
# Rationale: the per-service Dockerfiles (services/<svc>/Dockerfile) rebuild the jar
# from source inside Docker with zero Gradle cache — correct for local one-off builds,
# wasteful in CI where the jar was just built with a warm cache. This file mirrors
# their runtime stage exactly; the per-service files remain the source of truth for
# local from-source builds.
FROM eclipse-temurin:25-jre
WORKDIR /app

# Run as an unprivileged user, never root.
RUN groupadd --system native && useradd --system --gid native --home /app native-svc
USER native-svc

COPY *.jar app.jar

# PII encryption key (rule 6) — injected from Vault at deploy time, NEVER baked into the image.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
