# ---- Build Stage ----
FROM maven:3-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# Download dependencies first (layer cache friendly)
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src/ src/
RUN mvn clean package -DskipTests -q

# ---- Runtime Stage ----
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/dawn-ai-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
