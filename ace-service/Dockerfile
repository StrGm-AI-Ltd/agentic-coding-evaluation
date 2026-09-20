# Multi-stage: the app is compiled INSIDE the image, never COPYing a host-built jar - the same rule
# the oracle enforces on the agent's own Dockerfiles (see PORTING-harness-fixes.md Fix 1) applies to
# this one too, so DockerApplicationIT proves the same thing the grader would rely on.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# gradle.properties is deliberately NOT copied: it pins host-specific settings (org.gradle.java.home
# to the local Homebrew JDK, org.gradle.caching for the host daemon) that do not exist in this image
# - the base image's own JDK 21 is found on PATH instead, and the toolchain config in
# build.gradle.kts is what the build actually needs. If a setting ever becomes ESSENTIAL for the
# in-image build, provide it via a sibling gradle.properties copied here, not the host file.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# pre-fetch ALL dependencies into this layer: a one-line source change invalidates only the src
# layer below, never a full Maven Central re-download. The probe class is a dummy so compileJava
# (and thus the whole dependency graph) resolves before the real sources arrive.
RUN chmod +x gradlew && ./gradlew --version \
    && mkdir -p src/main/java/com/strgmai/ace/service \
    && echo 'package com.strgmai.ace.service; final class __probe{}' > src/main/java/com/strgmai/ace/service/__probe.java \
    && ./gradlew --no-daemon compileJava \
    && rm -rf src   # the probe is done its job; delete it so COPY src below brings ONLY the real sources
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test
# exactly ONE boot jar is expected: if the filter ever matched more (a -sources/-javadoc jar, a new
# sub-project), cp -exec would let the last-in-filesystem-order win and the image would be non-deterministic
RUN sh -c 'jar=$(find build/libs -maxdepth 1 -name "*.jar" ! -name "*-plain.jar"); \
    [ "$(echo "$jar" | wc -l)" -eq 1 ] || { echo "Expected exactly 1 boot jar, got: $jar"; exit 1; }; \
    cp "$jar" /src/app.jar'

FROM eclipse-temurin:21-jre
# git is a FATAL preflight check (Preflight.java): a deployed container without it can never pass
# guard() and would refuse every job forever - this is not test-only scaffolding, a real deployment
# needs this to do its actual job
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
# least privilege: an app compromise (e.g. RCE) must not start as root and be able to touch
# system files or /root/.ssh - a dedicated unprivileged user runs the service instead
RUN adduser --disabled-password --gecos '' appuser
WORKDIR /app
# /app doubles as the writable results/ home, so the whole tree (plus the jar) must be owned by
# the unprivileged user - the app cannot create or write under a root-owned /app
RUN mkdir -p /app/results && chown -R appuser /app
COPY --from=build /src/app.jar app.jar
RUN chown appuser /app/app.jar
USER appuser
EXPOSE 8765
ENTRYPOINT ["java", "-jar", "app.jar"]
