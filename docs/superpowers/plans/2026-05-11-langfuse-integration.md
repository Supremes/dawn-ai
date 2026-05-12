# Langfuse 集成实施计划

> **给 Agent 执行者：** 必须使用技能 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务执行本计划。每个步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 在 `docker-compose.yml` 中自托管 Langfuse v3，并将所有 Spring AI Observation Span（chat、embedding、vector store、advisor、tool-calling）通过 OTLP/HTTP 协议推送到 Langfuse，并与现有的 `AiInteractionContext.sessionId` 关联。

**架构：** 纯协议层集成（Langfuse 没有官方 Java SDK）。Spring AI 内置 Micrometer Observations → `micrometer-tracing-bridge-otel` → `opentelemetry-exporter-otlp` → Langfuse v3 OTLP 摄入端点。现有 Prometheus + Grafana 指标流水线**不受影响**。应用启动**不得**依赖 Langfuse 服务栈。

**技术栈：** Spring Boot 3.2.5、Spring AI 1.1.4、Micrometer Tracing、OpenTelemetry SDK 1.x、Langfuse v3（Web + Worker + ClickHouse + Postgres + Redis + MinIO）、Docker Compose。

**设计规格文档：** `docs/superpowers/specs/2026-05-11-langfuse-integration-design.md`

**分支：** `feat/langfuse-integration`（已创建，设计规格文档已提交）

---

## 文件映射（拆分决策）

| 路径 | 操作 | 职责 |
|---|---|---|
| `docker-compose.yml` | 修改 | 新增 6 个服务 + 4 个 Volume（Langfuse v3 服务栈） |
| `.env.example` | 修改 | 新增 Langfuse 初始化引导 + OTLP 环境变量 |
| `pom.xml` | 修改 | 新增 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` |
| `src/main/resources/application.yml` | 修改 | 新增 `management.tracing.*`、`management.otlp.tracing.*`、`spring.ai.{chat,tools}.observations.*`、`langfuse.environment` |
| `src/main/java/com/dawn/ai/config/LangfuseObservationConfig.java` | 新建 | 单一 `@Configuration`：`ObservationFilter`（每 Span 注入 `session.id`）+ OTel Resource 定制器（进程级 `langfuse.environment`） |
| `src/test/java/com/dawn/ai/config/LangfuseObservationConfigTest.java` | 新建 | 单元测试 filter 契约：有 sessionId 时发射 `session.id`，无时不发射 |
| `scripts/langfuse-auth-header.sh` | 新建 | 辅助脚本：输出 `base64(public:secret)` 用于 `LANGFUSE_AUTH_BASE64` |
| `README.md` | 修改 | 追加"📊 可观测性（Langfuse）"章节 |

**边界划分原因：**
- 所有接线逻辑集中在**一个**新的 `@Configuration` 类中，未来规格变更（如新增 `user.id` 等属性）只需修改一个文件。
- 测试文件置于 `config/` 包下，与生产代码包结构镜像对应。
- 辅助脚本将密钥编码操作从操作者的 shell 历史记录中隔离出去。

---

## 任务 1：将 Langfuse 服务栈加入 docker-compose

**涉及文件：**
- 修改：`docker-compose.yml`

- [ ] **步骤 1：在 `services:` 块中追加 Langfuse 服务**

在 `docker-compose.yml` 末尾的 `volumes:` 块**之前**插入：

```yaml
  # ───────── Langfuse v3 (LLM observability) ─────────
  langfuse-postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_USER: langfuse
      POSTGRES_PASSWORD: langfuse123
      POSTGRES_DB: langfuse
    volumes:
      - langfuse_postgres_data:/var/lib/postgresql/data
    ports:
      - "5433:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U langfuse -d langfuse"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks:
      - dawn-network

  langfuse-redis:
    image: redis:7-alpine
    restart: unless-stopped
    command: redis-server --appendonly yes --requirepass langfuse123
    volumes:
      - langfuse_redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "langfuse123", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks:
      - dawn-network

  clickhouse:
    image: clickhouse/clickhouse-server:24.3
    restart: unless-stopped
    environment:
      CLICKHOUSE_USER: clickhouse
      CLICKHOUSE_PASSWORD: clickhouse123
      CLICKHOUSE_DB: default
    volumes:
      - clickhouse_data:/var/lib/clickhouse
    ulimits:
      nofile:
        soft: 262144
        hard: 262144
    healthcheck:
      test: ["CMD-SHELL", "clickhouse-client --user clickhouse --password clickhouse123 --query 'SELECT 1' || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks:
      - dawn-network

  minio:
    image: minio/minio:latest
    restart: unless-stopped
    command: server --address ":9000" --console-address ":9001" /data
    environment:
      MINIO_ROOT_USER: minio
      MINIO_ROOT_PASSWORD: minio12345
    volumes:
      - minio_data:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks:
      - dawn-network

  langfuse-worker:
    image: langfuse/langfuse-worker:3
    restart: unless-stopped
    depends_on:
      langfuse-postgres: { condition: service_healthy }
      langfuse-redis:    { condition: service_healthy }
      clickhouse:        { condition: service_healthy }
      minio:             { condition: service_healthy }
    environment:
      DATABASE_URL: postgresql://langfuse:langfuse123@langfuse-postgres:5432/langfuse
      SALT: "dawn-langfuse-salt-change-me"
      ENCRYPTION_KEY: "0000000000000000000000000000000000000000000000000000000000000000"
      TELEMETRY_ENABLED: "false"
      CLICKHOUSE_URL: http://clickhouse:8123
      CLICKHOUSE_MIGRATION_URL: clickhouse://clickhouse:9000
      CLICKHOUSE_USER: clickhouse
      CLICKHOUSE_PASSWORD: clickhouse123
      CLICKHOUSE_CLUSTER_ENABLED: "false"
      LANGFUSE_S3_EVENT_UPLOAD_BUCKET: langfuse
      LANGFUSE_S3_EVENT_UPLOAD_REGION: auto
      LANGFUSE_S3_EVENT_UPLOAD_ACCESS_KEY_ID: minio
      LANGFUSE_S3_EVENT_UPLOAD_SECRET_ACCESS_KEY: minio12345
      LANGFUSE_S3_EVENT_UPLOAD_ENDPOINT: http://minio:9000
      LANGFUSE_S3_EVENT_UPLOAD_FORCE_PATH_STYLE: "true"
      LANGFUSE_S3_EVENT_UPLOAD_PREFIX: "events/"
      REDIS_HOST: langfuse-redis
      REDIS_PORT: 6379
      REDIS_AUTH: langfuse123
    networks:
      - dawn-network

  langfuse-web:
    image: langfuse/langfuse:3
    restart: unless-stopped
    depends_on:
      langfuse-postgres: { condition: service_healthy }
      langfuse-redis:    { condition: service_healthy }
      clickhouse:        { condition: service_healthy }
      minio:             { condition: service_healthy }
    ports:
      - "3001:3000"
    environment:
      DATABASE_URL: postgresql://langfuse:langfuse123@langfuse-postgres:5432/langfuse
      NEXTAUTH_URL: http://localhost:3001
      NEXTAUTH_SECRET: "dawn-langfuse-nextauth-secret-change-me"
      SALT: "dawn-langfuse-salt-change-me"
      ENCRYPTION_KEY: "0000000000000000000000000000000000000000000000000000000000000000"
      TELEMETRY_ENABLED: "false"
      CLICKHOUSE_URL: http://clickhouse:8123
      CLICKHOUSE_MIGRATION_URL: clickhouse://clickhouse:9000
      CLICKHOUSE_USER: clickhouse
      CLICKHOUSE_PASSWORD: clickhouse123
      CLICKHOUSE_CLUSTER_ENABLED: "false"
      LANGFUSE_S3_EVENT_UPLOAD_BUCKET: langfuse
      LANGFUSE_S3_EVENT_UPLOAD_REGION: auto
      LANGFUSE_S3_EVENT_UPLOAD_ACCESS_KEY_ID: minio
      LANGFUSE_S3_EVENT_UPLOAD_SECRET_ACCESS_KEY: minio12345
      LANGFUSE_S3_EVENT_UPLOAD_ENDPOINT: http://minio:9000
      LANGFUSE_S3_EVENT_UPLOAD_FORCE_PATH_STYLE: "true"
      LANGFUSE_S3_EVENT_UPLOAD_PREFIX: "events/"
      REDIS_HOST: langfuse-redis
      REDIS_PORT: 6379
      REDIS_AUTH: langfuse123
      LANGFUSE_INIT_ORG_ID: ${LANGFUSE_INIT_ORG_ID:-dawn-ai}
      LANGFUSE_INIT_ORG_NAME: ${LANGFUSE_INIT_ORG_NAME:-Dawn AI}
      LANGFUSE_INIT_PROJECT_ID: ${LANGFUSE_INIT_PROJECT_ID:-dawn-ai}
      LANGFUSE_INIT_PROJECT_NAME: ${LANGFUSE_INIT_PROJECT_NAME:-dawn-ai}
      LANGFUSE_INIT_PROJECT_PUBLIC_KEY: ${LANGFUSE_INIT_PROJECT_PUBLIC_KEY:-pk-lf-dawn-dev}
      LANGFUSE_INIT_PROJECT_SECRET_KEY: ${LANGFUSE_INIT_PROJECT_SECRET_KEY:-sk-lf-dawn-dev}
      LANGFUSE_INIT_USER_EMAIL: ${LANGFUSE_INIT_USER_EMAIL:-admin@dawn.local}
      LANGFUSE_INIT_USER_PASSWORD: ${LANGFUSE_INIT_USER_PASSWORD:-dawn-admin-123}
      LANGFUSE_INIT_USER_NAME: ${LANGFUSE_INIT_USER_NAME:-Dawn Admin}
    healthcheck:
      test: ["CMD", "wget", "-q", "--spider", "http://localhost:3000/api/public/health"]
      interval: 15s
      timeout: 5s
      retries: 20
      start_period: 60s
    networks:
      - dawn-network
```

- [ ] **步骤 2：新增 4 个命名 Volume**

修改文件底部的 `volumes:` 块，追加 `langfuse_postgres_data`、`langfuse_redis_data`、`clickhouse_data`、`minio_data`：

```yaml
volumes:
  huggingface_cache:
  postgres_data:
  redis_data:
  grafana_data:
  langfuse_postgres_data:
  clickhouse_data:
  langfuse_redis_data:
  minio_data:
```

- [ ] **步骤 3：验证 YAML 并启动服务栈**

执行：
```bash
docker compose config --quiet && echo "YAML OK"
docker compose up -d langfuse-postgres langfuse-redis clickhouse minio
```
预期：输出 `YAML OK`，4 个容器正常运行。

```bash
docker compose up -d langfuse-worker langfuse-web
sleep 60
docker compose ps langfuse-web
curl -fsS http://localhost:3001/api/public/health && echo OK
```
预期：`langfuse-web` 状态显示 `(healthy)`，curl 返回 `OK`。

- [ ] **步骤 4：冒烟测试 OTLP 端点是否存在**

执行：
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://localhost:3001/api/public/otel/v1/traces \
  -H "Content-Type: application/x-protobuf"
```
预期：返回 `401`（未认证被拒绝，但证明路由存在）。**不应**返回 `404`。

若返回 404：Langfuse v3 OTLP 未启用——请检查镜像标签是否为 `:3` 而非 `:2`。

- [ ] **步骤 5：提交**

```bash
git add docker-compose.yml
git commit -m "feat(langfuse): add Langfuse v3 stack to docker-compose

6 new services (langfuse-web/worker, langfuse-postgres, clickhouse,
langfuse-redis, minio) on dawn-network. langfuse-web mapped to host:3001
to avoid Grafana on 3000. ClickHouse/Redis/MinIO are internal-only."
```

---

## 任务 2：新增 Langfuse 环境变量与辅助脚本

**涉及文件：**
- 修改：`.env.example`
- 新建：`scripts/langfuse-auth-header.sh`

- [ ] **步骤 1：在 `.env.example` 末尾追加 Langfuse 配置块**

在 `.env.example` 末尾追加：

```dotenv

# ───────── Langfuse (observability) ─────────
# Bootstrap creds — change for non-local use. langfuse-web auto-creates
# the org/project/user with these values on first start.
LANGFUSE_INIT_ORG_ID=dawn-ai
LANGFUSE_INIT_ORG_NAME=Dawn AI
LANGFUSE_INIT_PROJECT_ID=dawn-ai
LANGFUSE_INIT_PROJECT_NAME=dawn-ai
LANGFUSE_INIT_PROJECT_PUBLIC_KEY=pk-lf-dawn-dev
LANGFUSE_INIT_PROJECT_SECRET_KEY=sk-lf-dawn-dev
LANGFUSE_INIT_USER_EMAIL=admin@dawn.local
LANGFUSE_INIT_USER_PASSWORD=dawn-admin-123
LANGFUSE_INIT_USER_NAME=Dawn Admin

# OTLP exporter (read by dawn-ai application.yml)
# Default endpoint targets the langfuse-web container over dawn-network.
LANGFUSE_OTLP_ENDPOINT=http://langfuse-web:3000/api/public/otel/v1/traces
# base64(LANGFUSE_INIT_PROJECT_PUBLIC_KEY:LANGFUSE_INIT_PROJECT_SECRET_KEY)
# Generate with: scripts/langfuse-auth-header.sh
LANGFUSE_AUTH_BASE64=cGstbGYtZGF3bi1kZXY6c2stbGYtZGF3bi1kZXY=
LANGFUSE_ENVIRONMENT=dev
```

- [ ] **步骤 2：新建 `scripts/langfuse-auth-header.sh`**

创建文件并设置权限为 755：

```bash
#!/usr/bin/env bash
# Generate the value of LANGFUSE_AUTH_BASE64 used by the OTLP exporter.
# Reads LANGFUSE_INIT_PROJECT_PUBLIC_KEY / SECRET_KEY from .env (or env).
set -euo pipefail

if [[ -f .env ]]; then
  set -a; source .env; set +a
fi

: "${LANGFUSE_INIT_PROJECT_PUBLIC_KEY:?missing LANGFUSE_INIT_PROJECT_PUBLIC_KEY}"
: "${LANGFUSE_INIT_PROJECT_SECRET_KEY:?missing LANGFUSE_INIT_PROJECT_SECRET_KEY}"

printf '%s:%s' \
  "$LANGFUSE_INIT_PROJECT_PUBLIC_KEY" \
  "$LANGFUSE_INIT_PROJECT_SECRET_KEY" \
  | base64
```

然后执行：
```bash
chmod +x scripts/langfuse-auth-header.sh
```

- [ ] **步骤 3：验证脚本输出与 `.env.example` 中预置值一致**

执行：
```bash
LANGFUSE_INIT_PROJECT_PUBLIC_KEY=pk-lf-dawn-dev \
LANGFUSE_INIT_PROJECT_SECRET_KEY=sk-lf-dawn-dev \
  scripts/langfuse-auth-header.sh
```
预期输出：`cGstbGYtZGF3bi1kZXY6c2stbGYtZGF3bi1kZXY=`（与 `.env.example` 中的值一致）。

- [ ] **步骤 4：提交**

```bash
git add .env.example scripts/langfuse-auth-header.sh
git commit -m "feat(langfuse): add env vars and auth-header helper script"
```

---

## 任务 3：新增 Maven 依赖

**涉及文件：**
- 修改：`pom.xml`

- [ ] **步骤 1：新增两个依赖项**

在 `pom.xml` 的 `<dependencies>` 块中，紧接 `micrometer-registry-prometheus` 依赖项之后插入：

```xml
        <!-- Micrometer Tracing → OpenTelemetry bridge -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-tracing-bridge-otel</artifactId>
        </dependency>

        <!-- OpenTelemetry OTLP exporter (HTTP/Protobuf) -->
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp</artifactId>
        </dependency>
```

（版本由 `spring-boot-starter-parent` 3.2.5 统一管理，无需填写 `<version>`。）

- [ ] **步骤 2：验证构建依赖解析成功**

执行：
```bash
mvn -q -DskipTests dependency:resolve | tail -20
mvn -q -DskipTests compile
```
预期：BUILD SUCCESS，无 "could not resolve" 错误。

- [ ] **步骤 3：提交**

```bash
git add pom.xml
git commit -m "feat(langfuse): add micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp"
```

---

## 任务 4：在 application.yml 中添加 tracing/OTLP/observation 配置

**涉及文件：**
- 修改：`src/main/resources/application.yml`

- [ ] **步骤 1：在 `management:` 块中补充 tracing + otlp 配置**

将当前 `management:` 块（约从第 62 行开始）替换为：

```yaml
# Actuator & Prometheus Metrics + OTLP tracing → Langfuse
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${LANGFUSE_OTLP_ENDPOINT:http://localhost:3001/api/public/otel/v1/traces}
      compression: gzip
      headers:
        Authorization: Basic ${LANGFUSE_AUTH_BASE64:}
```

- [ ] **步骤 2：开启 Spring AI prompt/completion/tool 内容日志**

在现有 `spring.ai:` 块下（`vectorstore:` 段之后，仍在 `spring.ai:` 内）追加：

```yaml
    chat:
      observations:
        log-prompt: true
        log-completion: true
    tools:
      observations:
        include-content: true
```

- [ ] **步骤 3：在文件末尾添加顶级 `langfuse:` 配置块（供 Resource 定制器读取）**

在文件末尾追加：

```yaml
# Langfuse environment label, attached as OTel resource attribute
langfuse:
  environment: ${LANGFUSE_ENVIRONMENT:dev}
```

- [ ] **步骤 4：验证 YAML 解析正确**

执行：
```bash
mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--spring.config.activate.on-profile=lint --spring.main.web-application-type=none --spring.main.lazy-initialization=true" &
APP_PID=$!
sleep 12
kill $APP_PID 2>/dev/null || true
```

更简便的替代方案——直接编译，让 Spring 严格 YAML 解析器在下一次测试时捕获错误。若 `mvn compile` 已通过，可跳过此步骤。

- [ ] **步骤 5：提交**

```bash
git add src/main/resources/application.yml
git commit -m "feat(langfuse): wire OTLP tracing + Spring AI observation content logging

- management.tracing.sampling.probability=1.0（dev 开发环境）
- management.otlp.tracing 端点/认证头/gzip 压缩
- spring.ai.chat.observations.log-prompt/completion=true
- spring.ai.tools.observations.include-content=true
- langfuse.environment 环境标签"
```

---

## 任务 5：编写红测（失败优先）——`LangfuseObservationConfig` filter 测试

**涉及文件：**
- 新建：`src/test/java/com/dawn/ai/config/LangfuseObservationConfigTest.java`

- [ ] **步骤 1：编写测试类**

创建文件：

```java
package com.dawn.ai.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseObservationConfigTest {

    private final LangfuseObservationConfig config = new LangfuseObservationConfig();

    @AfterEach
    void clear() {
        AiInteractionContext.clear();
    }

    @Test
    void filterEmitsSessionIdWhenContextHasOne() {
        AiInteractionContext.setSessionId("sess-123");
        ObservationFilter filter = config.langfuseSessionFilter();

        Observation.Context ctx = newContext();
        filter.map(ctx);

        assertThat(ctx.getLowCardinalityKeyValues())
                .contains(KeyValue.of("session.id", "sess-123"));
    }

    @Test
    void filterEmitsNothingWhenContextEmpty() {
        ObservationFilter filter = config.langfuseSessionFilter();

        Observation.Context ctx = newContext();
        filter.map(ctx);

        assertThat(ctx.getLowCardinalityKeyValues())
                .noneMatch(kv -> kv.getKey().equals("session.id"));
    }

    @Test
    void filterIgnoresBlankSessionId() {
        AiInteractionContext.setSessionId("   ");
        ObservationFilter filter = config.langfuseSessionFilter();

        Observation.Context ctx = newContext();
        filter.map(ctx);

        assertThat(ctx.getLowCardinalityKeyValues())
                .noneMatch(kv -> kv.getKey().equals("session.id"));
    }

    private Observation.Context newContext() {
        Observation.Context ctx = new Observation.Context();
        ctx.setName("test.observation");
        return ctx;
    }
}
```

> 注意：`AiInteractionContext.setSessionId(blank)` 在现有实现中已调用 `remove()`，因此第三个测试断言的是**最终行为**（不发射 `session.id`），无论由哪层来执行这个逻辑。

- [ ] **步骤 2：运行测试——必须失败**

执行：
```bash
mvn -q -Dtest=LangfuseObservationConfigTest test
```
预期：编译失败——`LangfuseObservationConfig` 尚不存在。

---

## 任务 6：实现 `LangfuseObservationConfig`

**涉及文件：**
- 新建：`src/main/java/com/dawn/ai/config/LangfuseObservationConfig.java`

- [ ] **步骤 1：实现该类**

创建文件：

```java
package com.dawn.ai.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires dawn-ai's existing per-thread sessionId into Spring AI's
 * Micrometer Observations so Langfuse can group traces by chat session,
 * and labels every exported span with a process-wide environment tag.
 */
@Configuration
public class LangfuseObservationConfig {

    /**
     * Per-span filter: stamps {@code session.id} on every Observation when
     * a sessionId is present on the current thread (already propagated by
     * {@link AiInteractionContextAccessor} across Reactor / executor handoffs).
     * {@code session.id} is the documented Langfuse OTel attribute that drives
     * the Sessions view.
     */
    @Bean
    public ObservationFilter langfuseSessionFilter() {
        return ctx -> {
            String sid = AiInteractionContext.getSessionId();
            if (sid != null && !sid.isBlank()) {
                ctx.addLowCardinalityKeyValue(KeyValue.of("session.id", sid));
            }
            return ctx;
        };
    }

    /**
     * Process-wide OTel resource attribute. Set once at SDK init rather than
     * per-span so it doesn't bloat every span payload.
     */
    @Bean
    public AutoConfigurationCustomizerProvider langfuseResourceCustomizer(
            @Value("${langfuse.environment:dev}") String env) {
        return customizer -> customizer.addResourceCustomizer((resource, props) ->
                resource.merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("langfuse.environment"), env))));
    }
}
```

- [ ] **步骤 2：运行单元测试——必须通过**

执行：
```bash
mvn -q -Dtest=LangfuseObservationConfigTest test
```
预期：3 个测试，BUILD SUCCESS。

- [ ] **步骤 3：运行完整测试套件，确认无回归**

执行：
```bash
mvn -q test
```
预期：BUILD SUCCESS。

- [ ] **步骤 4：提交**

```bash
git add src/main/java/com/dawn/ai/config/LangfuseObservationConfig.java \
        src/test/java/com/dawn/ai/config/LangfuseObservationConfigTest.java
git commit -m "feat(langfuse): inject session.id ObservationFilter + langfuse.environment OTel resource

每个 Span 的 session.id 从现有的 AiInteractionContext 读取
（已由 AiInteractionContextAccessor 跨 Reactor / boundedElastic 传播）。
langfuse.environment 通过 SDK 定制器设置为进程级 OTel 资源属性。"
```

---

## 任务 7：为 app 容器注入环境变量，使其可访问 dawn-network 上的 Langfuse

**涉及文件：**
- 修改：`docker-compose.yml`

- [ ] **步骤 1：在 `app:` 服务的 `environment:` 块中追加 3 个环境变量**

```yaml
      - LANGFUSE_OTLP_ENDPOINT=${LANGFUSE_OTLP_ENDPOINT:-http://langfuse-web:3000/api/public/otel/v1/traces}
      - LANGFUSE_AUTH_BASE64=${LANGFUSE_AUTH_BASE64}
      - LANGFUSE_ENVIRONMENT=${LANGFUSE_ENVIRONMENT:-dev}
```

> 注意：**不得**将 Langfuse 服务加入 `app.depends_on`。根据规格文档第 9 节，业务应用必须独立启动，与可观测性服务栈无关——若 langfuse-web 尚未就绪，早期 Span 将被 OTel 导出器静默丢弃。

- [ ] **步骤 2：验证 Compose 配置**

执行：
```bash
docker compose config --quiet && echo OK
```
预期：输出 `OK`。

- [ ] **步骤 3：提交**

```bash
git add docker-compose.yml
git commit -m "feat(langfuse): pass OTLP endpoint + auth + env to app container"
```

---

## 任务 8：端到端验证

**涉及文件：** 无（手动验证）

- [ ] **步骤 1：启动完整服务栈**

执行：
```bash
docker compose down
docker compose up -d
sleep 75
docker compose ps
```
预期：所有容器显示 `(healthy)` 或 `Up`，`langfuse-web` 状态为 `(healthy)`。

- [ ] **步骤 2：验证 Langfuse UI 可访问**

```bash
open http://localhost:3001    # macOS
```
使用 `admin@dawn.local` / `dawn-admin-123` 登录，项目 `dawn-ai` 应已自动创建。

- [ ] **步骤 3：触发一次完整的 Chat 请求**

执行：
```bash
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is 2+2?","sessionId":"smoke-001"}' | jq .
```
预期：HTTP 200，JSON 响应体包含答案。

- [ ] **步骤 4：在 Langfuse 中验证 Trace 出现**

等待约 5 秒后，在 Langfuse UI 中：

1. **Tracing** 页面 → 5–10 秒内出现新 Trace。
2. 打开该 Trace → 根 Span 包含模型名称、延迟、Token 用量，**prompt + completion 文本可见**。
3. 子 Span 可见（advisor / vector-store query / embedding，取决于对话路径）。
4. Trace 属性面板显示 `session.id = smoke-001` 和 `langfuse.environment = dev`。
5. **Sessions** 视图（左侧导航）→ 会话 `smoke-001` 已列出并聚合该 Trace。

- [ ] **步骤 5：验证容灾——Langfuse 宕机不影响业务请求**

执行：
```bash
docker compose stop langfuse-web langfuse-worker
sleep 3
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"ping","sessionId":"smoke-002"}' -w "\nHTTP %{http_code}\n"
docker compose start langfuse-web langfuse-worker
```
预期：HTTP 200（Langfuse 宕机时 Chat 请求仍然成功）。

- [ ] **步骤 6：验证 Prometheus 指标流水线无回归**

执行：
```bash
curl -s http://localhost:8080/actuator/prometheus | head -20
curl -s http://localhost:9090/-/healthy
```
预期：输出 Prometheus 文本格式数据；`Prometheus Server is Healthy.`。

---

## 任务 9：更新 README

**涉及文件：**
- 修改：`README.md`

- [ ] **步骤 1：在 README.md 末尾追加可观测性章节**

在 `README.md` 末尾追加：

```markdown

## 📊 可观测性（Langfuse）

`docker compose up` 会在应用旁启动自托管的 **Langfuse v3** 服务栈。所有 Spring AI 调用（chat、embedding、vector-store、tool-call）均通过 OTLP 导出到 Langfuse，包含完整的 prompt、completion 与 tool I/O。

### 首次启动

```bash
cp .env.example .env
# （可选）修改密鑰后重新生成认证头：
scripts/langfuse-auth-header.sh    # 将输出粘贴到 LANGFUSE_AUTH_BASE64

docker compose up -d
```

访问 **http://localhost:3001** 并登录：

| 字段 | 默认值（来自 `.env.example`） |
|---|---|
| 邮筱 | `admin@dawn.local` |
| 密码 | `dawn-admin-123` |

`dawn-ai` 项目已自动创建。新的对话几秒内出现在 **Tracing** 页面；**Sessions** 页面按 Chat 请求中传入的 `sessionId` 进行聚合。

### 数据分层

| 服务栈 | 用途 | UI 地址 |
|---|---|---|
| **Prometheus + Grafana**（现有） | 职指标汇总、RED 指标、SLO | http://localhost:3000 |
| **Langfuse**（新增） | 单请求 Trace、Prompt、Tool I/O | http://localhost:3001 |

两者相互独立——Langfuse 宕机不影响业务应用。

### 密鑰更换 / 生产环境

在 `.env` 中修改 `LANGFUSE_INIT_PROJECT_PUBLIC_KEY` 和 `_SECRET_KEY`，重新执行 `scripts/langfuse-auth-header.sh`，将新值填入 `LANGFUSE_AUTH_BASE64`，然后执行 `docker compose up -d --force-recreate langfuse-web app`。
```

- [ ] **步骤 2：提交**

```bash
git add README.md
git commit -m "docs(langfuse): document observability stack, first-run, and key rotation"
```

---

## 自审（Self-Review）

**规格覆盖检查：**

| 规格章节 | 对应实施任务 |
|---|---|
| §4 架构图 | 任务 1、3、4、6 |
| §5 组件清单与端口分配 | 任务 1 |
| §6.1 新增环境变量 | 任务 2 |
| §6.2 application.yml 补充 | 任务 4 |
| §6.3 Maven 依赖 | 任务 3 |
| §7.1 LangfuseObservationConfig | 任务 5、6 |
| §7.2 不修改现有类 | 任务 5–6 只新建文件，符合要求 |
| §8 验收核查 | 任务 8 |
| §9 故障模式（no depends_on） | 任务 7 步骤 1 注意事项；任务 8 步骤 5 |
| §10 文档（README、.env.example、辅助脚本） | 任务 2、9 |

所有规格条目均已映射，无遗漏。✔

**占位符扫描：** 无 "TBD"，无 "implement later"，每个代码块均为可执行的完整代码。✔

**命名一致性：** `langfuseSessionFilter()` 和 `langfuseResourceCustomizer()` Bean 名称在任务 5（测试）和任务 6（实现）之间保持一致。`LANGFUSE_AUTH_BASE64`、`LANGFUSE_OTLP_ENDPOINT`、`LANGFUSE_ENVIRONMENT` 环境变量名称在任务 1、2、4、7 中保持一致。✔

---

## 执行交接

计划已完成并保存至 `docs/superpowers/plans/2026-05-11-langfuse-integration.md`。提供两种执行方式：

1. **子 Agent 驱动（推荐）** — 每个任务分派独立子 Agent 执行，任务间可人工审查，迭代速度快。
2. **内联执行** — 在当前会话中使用 executing-plans 技能批量执行，按检查点推进。

请选择执行方式？
