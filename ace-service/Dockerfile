# Multi-stage: the app is compiled INSIDE the image, never COPYing a host-built jar - the same rule
# the oracle enforces on the agent's own Dockerfiles (see PORTING-harness-fixes.md Fix 1) applies to
# this one too, so DockerApplicationIT proves the same thing the grader would rely on.
#
# ace-service is a Gradle subproject: build with the REPO ROOT as context, e.g.
#   docker build -f ace-service/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# gradle.properties is deliberately NOT copied: it pins host-specific settings (org.gradle.java.home
# to the local Homebrew JDK, org.gradle.caching for the host daemon) that do not exist in this image
# - the base image's own JDK 21 is found on PATH instead, and the toolchain config in
# build.gradle is what the build actually needs. If a setting ever becomes ESSENTIAL for the
# in-image build, provide it via a sibling gradle.properties copied here, not the host file.
COPY gradlew settings.gradle ./
COPY gradle ./gradle
# settings.gradle includes ace-ui-vaadin too, and Gradle refuses to configure a multi-module build
# when an included project's directory does not exist - even for a build invocation scoped entirely
# to :ace-service:... tasks. An empty stub (no build.gradle) satisfies that without pulling in any
# of the UI subproject's actual content, which this image has no business depending on.
RUN mkdir -p ace-ui-vaadin
COPY ace-service/build.gradle ace-service/build.gradle
# compileJava now transitively depends on generateJooq -> prepareJooqSchema, which reads this real
# migration file to build the scratch SQLite DB jOOQ introspects - it is needed at probe time too,
# not just once the real sources arrive below.
COPY ace-service/src/main/resources/db ace-service/src/main/resources/db
RUN chmod +x gradlew
# #673: the agent's own sandbox has no `gradle` at all - no system binary, no pre-made wrapper
# (it hand-writes build.gradle.kts from scratch, not from a Spring-Initializr-style skeleton that
# ships gradlew+gradle-wrapper.jar+gradle-wrapper.properties). `gradle wrapper` itself needs an
# EXISTING Gradle to generate one, so with neither, the only path left is downloading a full
# distribution zip by hand and hand-rolling a gradlew against it - fragile (no persistent shell
# state between separate bash calls) and the single biggest cause of wasted task budget seen this
# session.
# #679: found live (job c965a635, T5) - this used to just reuse whatever Gradle version THIS
# repo's OWN build below happens to pin (gradle/wrapper/gradle-wrapper.properties), coupling two
# unrelated concerns: the harness's own build tooling, and what we hand an agent whose training
# data defaults to "well-known, slightly older" plugin versions (e.g. Spring Boot Gradle plugin
# 3.2.0). Pairing that against whatever Gradle happens to be bleeding-edge this week is a near-
# guaranteed mismatch: Spring Boot plugin 3.2.0's `bootJar` task called an internal Gradle API
# ('CopyProcessingSpec.getDirMode()') this repo's own pinned Gradle 9.5.1 no longer has - the agent
# then "fixed" it by abandoning `bootJar` for the plain `jar` task, which bundles neither
# dependencies nor an executable manifest and can never actually run; no curl variant
# (localhost/host.docker.internal/the container's own IP) could ever reach a health endpoint from a
# service that was crashing on startup every time. AGENT_GRADLE_VERSION is bootstrapped from its
# OWN temporary wrapper-properties override - independent of whatever this repo's wrapper uses
# below - pinned to a release still broadly compatible with the wide span of Spring Boot 3.x plugin
# versions a model is likely to reach for.
# found live: the first version of this command used -maxdepth 4, one short of where the real
# launcher actually sits (dists/gradle-<v>-bin/<hash>/gradle-<v>/bin/gradle is 5 path segments
# below dists/, not 4) - find silently matched nothing, $gradle_bin stayed empty, and
# dirname(dirname("")) resolved to ".", so the build quietly copied this WHOLE STAGE's own /src
# project tree (gradlew, settings.gradle, source) into /opt/gradle-dist instead of a real Gradle
# install - no build error anywhere, just a broken binary discovered only by actually running it.
# The explicit [ -n ... ] guard below turns the next occurrence of exactly this mistake into a
# loud build failure instead of a silent wrong-directory copy. This step runs BEFORE this repo's
# own dependency pre-fetch below so /root/.gradle/wrapper/dists holds ONLY this one distribution
# when found - no ambiguity between the agent's pinned version and the repo's own.
ARG AGENT_GRADLE_VERSION=8.11
RUN cp gradle/wrapper/gradle-wrapper.properties /tmp/repo-gradle-wrapper.properties \
    && sed -i "s|^distributionUrl=.*|distributionUrl=https://services.gradle.org/distributions/gradle-${AGENT_GRADLE_VERSION}-bin.zip|" gradle/wrapper/gradle-wrapper.properties \
    && ./gradlew --version \
    && gradle_bin=$(find /root/.gradle/wrapper/dists -maxdepth 5 -type f -name gradle -path '*/bin/gradle' | head -1) \
    && [ -n "$gradle_bin" ] || { echo "ERROR: could not locate the bootstrapped agent-gradle launcher under /root/.gradle/wrapper/dists"; exit 1; } \
    && cp -r "$(dirname "$(dirname "$gradle_bin")")" /opt/gradle-dist \
    && /opt/gradle-dist/bin/gradle --version \
    && cp /tmp/repo-gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
# pre-fetch ALL dependencies into this layer: a one-line source change invalidates only the src
# layer below, never a full Maven Central re-download. The probe class is a dummy so compileJava
# (and thus the whole dependency graph) resolves before the real sources arrive. Uses THIS repo's
# own pinned Gradle (restored above) - unaffected by the agent-gradle bootstrap that ran first.
RUN ./gradlew --version \
    && mkdir -p ace-service/src/main/java/com/strgmai/ace/service \
    && echo 'package com.strgmai.ace.service; final class __probe{}' > ace-service/src/main/java/com/strgmai/ace/service/__probe.java \
    && ./gradlew --no-daemon :ace-service:compileJava \
    && rm -rf ace-service/src   # the probe is done its job; delete it so COPY src below brings ONLY the real sources
COPY ace-service/src ace-service/src
RUN ./gradlew :ace-service:bootJar --no-daemon -x test
# exactly ONE boot jar is expected: if the filter ever matched more (a -sources/-javadoc jar, a new
# sub-project), cp -exec would let the last-in-filesystem-order win and the image would be non-deterministic
RUN sh -c 'jar=$(find ace-service/build/libs -maxdepth 1 -name "*.jar" ! -name "*-plain.jar"); \
    [ "$(echo "$jar" | wc -l)" -eq 1 ] || { echo "Expected exactly 1 boot jar, got: $jar"; exit 1; }; \
    cp "$jar" /src/app.jar'

FROM eclipse-temurin:21-jre
# git is a FATAL preflight check (Preflight.java): a deployed container without it can never pass
# guard() and would refuse every job forever - this is not test-only scaffolding, a real deployment
# needs this to do its actual job. zsh is the SAME kind of hard dependency, just a silent one until
# now: AgentTools.bash() execs every agent shell command via "zsh", "-lc", with no fallback - found
# live on a real AWS deployment that never had it installed, where every bash tool call in every
# task crashed (java.io.IOException: Cannot run program "zsh") for hours with nothing surfacing it
# until #327's own new failure-category telemetry made the crash directly visible. Added as its own
# fatal Preflight check too (Preflight.java), so a future deployment missing it refuses to start
# instead of silently burning a whole run's budget the same way again.
RUN apt-get update && apt-get install -y --no-install-recommends git zsh unzip ca-certificates curl gnupg \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    # DockerService.java execs `docker`/`docker compose` directly against the HOST's own daemon
    # (its socket is bind-mounted in by docker-compose.yml - this image never runs its own daemon,
    # it is Docker-outside-of-Docker) - without the CLI/plugin here, every Docker-gated oracle
    # check (B1/B2/C2/F5/F6/F7) silently reports "docker unavailable" and skips, the same failure
    # class as the launchd PATH bug fixed this session (#455), just a missing binary this time.
    && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/*
# least privilege: an app compromise (e.g. RCE) must not start as root and be able to touch
# system files or /root/.ssh - a dedicated unprivileged user runs the service instead
RUN adduser --disabled-password --gecos '' appuser
WORKDIR /app
# /app doubles as the writable results/ home, so the whole tree (plus the jar) must be owned by
# the unprivileged user - the app cannot create or write under a root-owned /app.
# /app/data/{db,results,workspaces} is where docker-compose.yml mounts the persistent volume -
# pre-creating it (chown'd) here matters even though a volume mount overlays it at runtime:
# Docker copies an image directory's own existing content (ownership included) into a NAMED
# volume the first time it's used if the volume starts empty, which is the only way appuser
# (non-root) ever gets write access to it. Without this, SQLite's own JDBC driver fails outright
# ("path to '...': '...' does not exist" - it never creates its own parent directory) or the
# unprivileged user simply can't write there (found live - both failure modes hit in testing).
RUN mkdir -p /app/results /app/data/db /app/data/results /app/data/workspaces && chown -R appuser /app
COPY --from=build /src/app.jar app.jar
RUN chown appuser /app/app.jar
# #581 wired ACE_JAVA_HOME (the JDK handed to the agent's own `./gradlew`/tool calls and to
# VerifyTask's post-task test run) to reuse this image's OWN runtime JAVA_HOME for simplicity - but
# this stage's base is deliberately `eclipse-temurin:21-jre` (no javac, just java, to keep ace-service's
# own runtime image small), so every agent task that compiles or tests Java failed with Gradle's
# "Java compiler is not available" (found live, job cf0ccdb6: T1's wrapper bootstrap and T11's test
# compile both hit this - a 100% reproducible harness bug, not an agent mistake). The build stage above
# already has a full JDK (`eclipse-temurin:21-jdk`) at this same conventional Temurin path; copy it in
# under its own name so it never collides with or replaces this image's own runtime JRE.
COPY --from=build --chown=appuser /opt/java/openjdk /opt/agent-jdk
# #673: same reasoning as /opt/agent-jdk immediately above, for Gradle - see the build stage's own
# comment for the live incident this closes. ACE_GRADLE_HOME (docker-compose.yml) wires this onto
# the agent's own PATH via RunBenchSupport.scrubbedEnv(), the same mechanism ACE_JAVA_HOME uses.
COPY --from=build --chown=appuser /opt/gradle-dist /opt/agent-gradle
USER appuser
EXPOSE 8765
ENTRYPOINT ["java", "-jar", "app.jar"]
