# Multi-stage: the app is compiled INSIDE the image, never COPYing a host-built jar - same rule
# ace-service/Dockerfile follows (see that file's own comment for why).
#
# ace-ui-vaadin is a Gradle subproject: build with the REPO ROOT as context, e.g.
#   docker build -f ace-ui-vaadin/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# gradle.properties is deliberately NOT copied - see ace-service/Dockerfile's own comment; the
# base image's own JDK 21 and build.gradle's toolchain config are what the in-image build needs.
COPY gradlew settings.gradle ./
COPY gradle ./gradle
# settings.gradle includes ace-service too, and Gradle refuses to configure a multi-module build
# when an included project's directory does not exist - even for a build invocation scoped
# entirely to :ace-ui-vaadin:... tasks. An empty stub (no src) satisfies that without pulling in
# any of the backend subproject's actual content, which this image has no business depending on
# (ace-ui-vaadin has no compile-time dependency on ace-service - it only talks to it over HTTP).
RUN mkdir -p ace-service
COPY ace-service/build.gradle ace-service/build.gradle
COPY ace-ui-vaadin/build.gradle ace-ui-vaadin/build.gradle
# pre-fetch ALL Java dependencies into this layer: a one-line source change invalidates only the
# src layer below, never a full Maven Central re-download. The probe class is a dummy so
# compileJava (and thus the whole dependency graph) resolves before the real sources arrive -
# same trick ace-service/Dockerfile uses. Frontend (npm) dependencies are fetched separately, as
# part of the real vaadinBuildFrontend step below - there is no equivalent early-pin for those.
RUN chmod +x gradlew && ./gradlew --version \
    && mkdir -p ace-ui-vaadin/src/main/java/com/strgmai/ace/ui \
    && echo 'package com.strgmai.ace.ui; final class __probe{}' > ace-ui-vaadin/src/main/java/com/strgmai/ace/ui/__probe.java \
    && ./gradlew --no-daemon :ace-ui-vaadin:compileJava \
    && rm -rf ace-ui-vaadin/src   # the probe is done its job; delete it so COPY src below brings ONLY the real sources
COPY ace-ui-vaadin/src ace-ui-vaadin/src
# the Vaadin Gradle plugin hooks bootJar to run vaadinBuildFrontend in PRODUCTION mode (not dev
# mode's on-the-fly bundling) - this is the same jar scripts/dev.sh's own :ace-ui-vaadin:bootRun
# would use in production, just built ahead of time here.
RUN ./gradlew :ace-ui-vaadin:bootJar --no-daemon -x test
RUN sh -c 'jar=$(find ace-ui-vaadin/build/libs -maxdepth 1 -name "*.jar" ! -name "*-plain.jar"); \
    [ "$(echo "$jar" | wc -l)" -eq 1 ] || { echo "Expected exactly 1 boot jar, got: $jar"; exit 1; }; \
    cp "$jar" /src/app.jar'

FROM eclipse-temurin:21-jre
# least privilege: an app compromise must not start as root
RUN adduser --disabled-password --gecos '' appuser
WORKDIR /app
COPY --from=build /src/app.jar app.jar
RUN chown appuser /app/app.jar
USER appuser
EXPOSE 8800
ENTRYPOINT ["java", "-jar", "app.jar"]
