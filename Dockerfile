# ---- Build Stage ----
# syntax=docker/dockerfile:1
FROM maven:3-eclipse-temurin-17 AS builder

WORKDIR /build

# Layer 1: pom.xml only — invalidated only when dependencies change
COPY pom.xml .
COPY .mvn/ .mvn/

# Layer 2: resolve all deps into the BuildKit cache mount (.m2 is NOT baked into the image)
# --mount=type=cache persists /root/.m2 across builds on the same host, so deps are never re-downloaded
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B dependency:go-offline

# Layer 3: source code — changes every commit, but deps are already cached above
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package -DskipTests

# ---- Runtime Stage ----
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=builder /build/target/dawn-ai-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080 5005

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
ENV JAVA_DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS $JAVA_DEBUG_OPTS -jar app.jar"]
