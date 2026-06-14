// Conventions for a *deployable* Native service (an executable Spring Boot app).
//
// Layers, on top of the base Java conventions:
//   - io.spring.dependency-management + the Spring Boot 4 BOM (so dependency
//     coordinates resolve without explicit versions); and
//   - org.springframework.boot, which produces the runnable boot jar
//     (bootJar / bootRun) every service is deployed as.
//
// The spring-boot-gradle-plugin is already on build-logic's classpath (see
// build-logic/build.gradle.kts), so it can be applied by id here without
// repeating the version. This is the single starting point every Native service
// (cloned from service-template) builds on; libraries keep using
// native.java-conventions / native.spring-conventions instead, because a library
// must not carry the Spring Boot application plugin.

plugins {
    id("native.java-conventions")
    id("io.spring.dependency-management")
    id("org.springframework.boot")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}
