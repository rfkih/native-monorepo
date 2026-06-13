// Conventions for modules that consume the Spring ecosystem (libs that need
// spring-tx/jackson/avro from the managed BOM, and the services themselves).
// Layers the Spring Boot 4 dependency BOM on top of the base Java conventions.
// The Spring Boot application plugin itself is applied only by deployable services.

plugins {
    id("native.java-conventions")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}
