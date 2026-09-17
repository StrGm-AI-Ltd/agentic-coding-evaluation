# Multi-stage: the app is compiled INSIDE the image, never COPYing a host-built jar - the same rule
# the oracle enforces on the agent's own Dockerfiles (see PORTING-harness-fixes.md Fix 1) applies to
# this one too, so DockerApplicationIT proves the same thing the grader would rely on.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# gradle.properties is deliberately NOT copied: it pins org.gradle.java.home to the host's Homebrew
# JDK path, which does not exist in this image - the base image's own JDK 21 is found on PATH instead
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test
# bootJar's own jar, not the plain classes-only one the `jar` task also produces
RUN find build/libs -maxdepth 1 -name "*.jar" ! -name "*-plain.jar" -exec cp {} /src/app.jar \;

FROM eclipse-temurin:21-jre
# git is a FATAL preflight check (Preflight.java): a deployed container without it can never pass
# guard() and would refuse every job forever - this is not test-only scaffolding, a real deployment
# needs this to do its actual job
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/app.jar app.jar
EXPOSE 8765
ENTRYPOINT ["java", "-jar", "app.jar"]
