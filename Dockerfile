# ---- 构建阶段 ----
# syntax=docker/dockerfile:1
FROM maven:3-eclipse-temurin-17 AS builder

WORKDIR /build

# 第 1 层：只 COPY pom.xml —— 只有依赖变了才会让这层失效
COPY pom.xml .

# 第 2 层：把所有依赖拉到 BuildKit 的 cache mount 里（.m2 不会打进镜像）
# --mount=type=cache 会让 /root/.m2 在同一台宿主机的多次 build 之间一直保留，所以依赖不会重复下载
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B dependency:go-offline

# 第 3 层：源码 —— 每次 commit 都会变，但依赖已经在上面 cache 好了
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package -DskipTests

# ---- 运行阶段 ----
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
