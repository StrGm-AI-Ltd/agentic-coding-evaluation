# Build context is the repository root (see docker-compose.yml) so the multi-project settings resolve.
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
COPY gateway ./gateway
COPY market-service ./market-service
COPY gateway ./gateway
RUN --mount=type=cache,target=/home/gradle/.gradle gradle :gateway:bootJar --no-daemon -q
FROM eclipse-temurin:21-jre-alpine
COPY --from=build /src/gateway/build/libs/gateway-0.1.0.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
