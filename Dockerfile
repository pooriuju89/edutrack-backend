# ---- Build stage ----
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# ---- Run stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Most PaaS platforms (Railway, Render, SnapDeploy, etc.) inject their own
# PORT env var at runtime and route traffic to whatever port it points at.
# application.yml already reads server.port: ${PORT:8080}, so this container
# will automatically bind to whatever PORT the platform provides, falling
# back to 8080 if none is set (e.g. running locally with `docker run`).
EXPOSE 8080
ENV PORT=8080

# JVM tuned to fit inside a small (512MB-1GB) container:
#   -Xmx180m          caps heap at 180MB, leaving headroom for metaspace/thread stacks/native overhead
#   -Xss256k          smaller per-thread stack size (Spring Boot doesn't need the 1MB default)
#   -XX:+UseContainerSupport   makes the JVM respect the container's memory limit instead of the host's
#   -XX:MaxMetaspaceSize=100m  stops metaspace growing unbounded and pushing past the container limit
ENTRYPOINT ["java", "-Xmx180m", "-Xss256k", "-XX:+UseContainerSupport", "-XX:MaxMetaspaceSize=100m", "-jar", "app.jar"]
