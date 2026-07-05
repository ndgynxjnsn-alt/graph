# syntax=docker/dockerfile:1

########################################
# Build stage
########################################
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Cache dependencies first (only re-resolves when the pom changes)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

# Build the application
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests

# Explode the fat jar into Spring Boot layers for better image caching
RUN java -Djarmode=tools -jar target/showcase-*.jar extract --layers --destination extracted

########################################
# Runtime stage
########################################
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as an unprivileged user
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

# Copy Spring Boot layers (most-stable first so Docker can cache them)
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

EXPOSE 8080

# Container-aware JVM defaults; can be extended at runtime via JAVA_OPTS
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar showcase-0.0.1-SNAPSHOT.jar"]
