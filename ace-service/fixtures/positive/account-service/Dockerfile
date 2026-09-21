# Build context is the repository root (see docker-compose.yml) so the multi-project settings resolve.
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
COPY account-service ./account-service
COPY market-service ./market-service
COPY gateway ./gateway
RUN --mount=type=cache,target=/home/gradle/.gradle gradle :account-service:bootJar --no-daemon -q
FROM eclipse-temurin:21-jre-alpine
COPY --from=build /src/account-service/build/libs/account-service-0.1.0.jar /app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app.jar"]
