plugins {
    id("native.spring-conventions")
}

dependencyManagement {
    imports {
        // Same explicit import as libs/tenant: the Boot BOM pins testcontainers.version but
        // does not flatten the Testcontainers BOM's managed coordinates, so import it here
        // (same pinned 2.0.5) for the MinIO round-trip test's container machinery.
        mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
    }
}

dependencies {
    // The generic S3 API client (ADR 0048). AWS SDK v2, NOT a MinIO-specific library —
    // MinIO is the deployment today; the code only ever speaks the S3 protocol, so the
    // store can move to any S3-compatible target (AWS S3, Cloudflare R2, SeaweedFS)
    // with a config change. `api` because consumers catch this library's exceptions and
    // the client type appears in service-visible signatures.
    api(libs.awssdk.s3)

    // Auto-configuration machinery (the libs/tenant idiom): MediaStorageAutoConfiguration
    // is registered in META-INF/spring/...AutoConfiguration.imports so a service gets a
    // ready MediaStorage bean just by depending on this module and setting native.media.*.
    api("org.springframework.boot:spring-boot-autoconfigure")

    // MediaStorageProperties carries jakarta.validation constraints (@NotNull/@NotBlank) so a
    // half-wired service fails fast at startup; the api itself is tiny and the actual validator
    // implementation comes from each consuming service's starter-validation.
    api("jakarta.validation:jakarta.validation-api")

    testImplementation("org.assertj:assertj-core")
    // The MinIO round-trip test runs against a REAL minio/minio container (GenericContainer —
    // Testcontainers 2.x ships no dedicated MinIO module): put/get/delete through the actual
    // S3 wire protocol, not a mock.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
