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
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // the agent's bash tool runs zsh; keep the environment minimal in tests as the harness does
    environment("CI", "1")
}
