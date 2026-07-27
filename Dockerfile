# syntax=docker/dockerfile:1

###############################################################################
# Stage 1 — build + layer extraction
###############################################################################
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Dependency layer. Only invalidated when the wrapper or the POM changes, so
# editing src/ does not re-download the world.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

# Source layer.
COPY src/ src/
RUN ./mvnw -B -ntp clean package -DskipTests \
 && cp target/*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

###############################################################################
# Stage 2 — runtime
###############################################################################
FROM eclipse-temurin:25-jre AS runtime

# UID 1000 is already taken by the pre-created `ubuntu` account in the
# ubuntu:26.04 base this image uses — hence 1001. curl is needed for the
# docker-compose healthcheck; the base image ships neither curl nor wget.
RUN groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid spring --no-create-home --home-dir /application --shell /usr/sbin/nologin spring \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /application

# One COPY per Boot layer: dependencies change rarely, application code every
# build, so Docker re-pushes and re-pulls only the last one.
# All four must land in the SAME directory — application.jar's manifest
# Class-Path references its siblings by relative path.
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/          ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/    ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/           ./

USER spring:spring

EXPOSE 8080

# Exec form: java is PID 1 and receives SIGTERM directly, which is what makes
# `server.shutdown: graceful` and the k8s preStop window actually work.
ENTRYPOINT ["java", \
            "-XX:MaxRAMPercentage=75.0", \
            "-XX:+ExitOnOutOfMemoryError", \
            "-jar", "application.jar"]
