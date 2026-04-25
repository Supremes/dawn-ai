# ---- Build Stage ----
FROM maven:3-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# Download dependencies first (layer cache friendly)
COPY pom.xml .
COPY .mvn/ .mvn/

COPY src/ src/
# RUN mvn clean package -DskipTests -q
RUN mvn -s .mvn/settings.xml clean package -DskipTests

# ---- Runtime Stage ----
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/dawn-ai-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080 5005

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
ENV JAVA_DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS $JAVA_DEBUG_OPTS -jar app.jar"]
