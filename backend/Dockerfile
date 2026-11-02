# Build: tests are not run here (they need Docker for Testcontainers); CI runs them.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:25-jre
# curl for the container health check
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 classos && mkdir -p /app/data/files && chown -R classos /app/data
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER classos
ENV STORAGE_DIR=/app/data/files
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
