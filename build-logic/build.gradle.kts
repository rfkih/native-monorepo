plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Expose third-party plugins to our precompiled convention plugins so they can apply
    // them by id (e.g. `id("org.springframework.boot")`, `id("io.spring.dependency-management")`).
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.0")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}
