plugins {
    java
    id("org.springframework.boot") version "3.5.5"
}

group = "com.agentbench"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("dev.langchain4j:langchain4j-bom:1.1.0"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.5"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("dev.langchain4j:langchain4j")
    implementation("dev.langchain4j:langchain4j-open-ai")
    implementation("org.postgresql:postgresql")   // PGobjectJsonSerializer needs org.postgresql.util.PGobject at compile time
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("com.h2database:h2")
}

tasks.withType<Test> {
    // the agent's bash tool runs zsh; keep the environment minimal in tests as the harness does
    environment("CI", "1")
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("docker") }   // dockerTest below runs these instead - building an image is slow
}

tasks.register<Test>("dockerTest") {
    group = "verification"
    description = "Functional test: builds this app's own Dockerfile into a real image and hits its HTTP API " +
            "from outside. Slow (a full image build) and needs a running Docker daemon - not part of `test`."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("docker") }
}
