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

# Skills 外挂目录：docker-compose 会把宿主机 ./skills 只读挂载到这里
RUN mkdir -p /app/skills

EXPOSE 8080 5005

# Container-aware heap：跟随容器可用内存自动伸缩。
#   - MaxRAMPercentage=70：留 30% 给 Metaspace/CodeCache/线程栈/RSS
#   - SerialGC：小堆（< 2 GB）下比 G1 省 ~30-50MB region 开销，dev 不关心 pause
#   - MaxMetaspaceSize / ReservedCodeCacheSize：封顶防漏，避免 cgroup OOMKill
#   - ExitOnOutOfMemoryError：早失败比僵尸进程更易诊断
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=20 \
               -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m \
               -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError"
ENV JAVA_DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS $JAVA_DEBUG_OPTS -jar app.jar"]
