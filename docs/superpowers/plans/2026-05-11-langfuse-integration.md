# Langfuse Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Self-host Langfuse v3 in `docker-compose.yml` and ship every Spring AI Observation span (chat, embedding, vector store, advisor, tool-calling) to Langfuse via OTLP/HTTP, correlated by the existing `AiInteractionContext.sessionId`.

**Architecture:** Pure protocol-level integration (Langfuse has no Java SDK). Spring AI's built-in Micrometer Observations → `micrometer-tracing-bridge-otel` → `opentelemetry-exporter-otlp` → Langfuse v3 OTLP ingestion endpoint. Existing Prometheus + Grafana metrics pipeline is **not** touched. App boot must **not** depend on the Langfuse stack.

**Tech Stack:** Spring Boot 3.2.5, Spring AI 1.1.4, Micrometer Tracing, OpenTelemetry SDK 1.x, Langfuse v3 (Web + Worker + ClickHouse + Postgres + Redis + MinIO), Docker Compose.

**Spec:** `docs/superpowers/specs/2026-05-11-langfuse-integration-design.md`

**Branch:** `feat/langfuse-integration` (already created, design spec already committed)

---

## File Map (decomposition decisions)

| Path | Action | Responsibility |
|---|---|---|
| `docker-compose.yml` | Modify | Add 6 services + 4 volumes for Langfuse v3 stack |
| `.env.example` | Modify | Add Langfuse bootstrap + OTLP env vars |
| `pom.xml` | Modify | Add `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` |
| `src/main/resources/application.yml` | Modify | Add `management.tracing.*`, `management.otlp.tracing.*`, `spring.ai.{chat,tools}.observations.*`, `langfuse.environment` |
| `src/main/java/com/dawn/ai/config/LangfuseObservationConfig.java` | Create | Single `@Configuration`: `ObservationFilter` (per-span `session.id`) + OTel resource customizer (process-wide `langfuse.environment`) |
| `src/test/java/com/dawn/ai/config/LangfuseObservationConfigTest.java` | Create | Unit-test the filter contract: emits `session.id` iff context has one |
| `scripts/langfuse-auth-header.sh` | Create | Helper: print `base64(public:secret)` for `LANGFUSE_AUTH_BASE64` |
| `README.md` | Modify | Append "📊 Observability (Langfuse)" section |

**Why these boundaries:**
- All wiring lives in **one** new `@Configuration` so future spec changes (add `user.id`, more attributes) touch one file.
- Test file isolated to `config/` mirroring the production package.
- Helper script keeps secret encoding out of operator's shell history.

---

## Task 1: Add Langfuse stack to docker-compose

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Append Langfuse services to `services:` block**

Insert **before** the closing `volumes:` block in `docker-compose.yml`:

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

- [ ] **Step 2: Add the 4 new named volumes**

Modify the `volumes:` block at the bottom — add `langfuse_postgres_data`, `langfuse_redis_data`, `clickhouse_data`, `minio_data`:

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

- [ ] **Step 3: Verify YAML and bring up the stack**

Run:
```bash
docker compose config --quiet && echo "YAML OK"
docker compose up -d langfuse-postgres langfuse-redis clickhouse minio
```
Expected: `YAML OK`, then 4 containers running.

```bash
docker compose up -d langfuse-worker langfuse-web
sleep 60
docker compose ps langfuse-web
curl -fsS http://localhost:3001/api/public/health && echo OK
```
Expected: `langfuse-web` shows `(healthy)`, curl returns `OK`.

- [ ] **Step 4: Smoke-test the OTLP endpoint exists**

Run:
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://localhost:3001/api/public/otel/v1/traces \
  -H "Content-Type: application/x-protobuf"
```
Expected: `401` (rejects unauthenticated, but proves the route exists). NOT `404`.

If 404: Langfuse v3 OTLP not enabled — re-check image tag is `:3`, not `:2`.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(langfuse): add Langfuse v3 stack to docker-compose

6 new services (langfuse-web/worker, langfuse-postgres, clickhouse,
langfuse-redis, minio) on dawn-network. langfuse-web mapped to host:3001
to avoid Grafana on 3000. ClickHouse/Redis/MinIO are internal-only."
```

---

## Task 2: Add Langfuse env vars and helper script

**Files:**
- Modify: `.env.example`
- Create: `scripts/langfuse-auth-header.sh`

- [ ] **Step 1: Append Langfuse block to `.env.example`**

Append at the end of `.env.example`:

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

- [ ] **Step 2: Create `scripts/langfuse-auth-header.sh`**

Create file with mode 755:

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

Then:
```bash
chmod +x scripts/langfuse-auth-header.sh
```

- [ ] **Step 3: Verify script output matches the value baked into `.env.example`**

Run:
```bash
LANGFUSE_INIT_PROJECT_PUBLIC_KEY=pk-lf-dawn-dev \
LANGFUSE_INIT_PROJECT_SECRET_KEY=sk-lf-dawn-dev \
  scripts/langfuse-auth-header.sh
```
Expected: `cGstbGYtZGF3bi1kZXY6c2stbGYtZGF3bi1kZXY=` (matches `.env.example`).

- [ ] **Step 4: Commit**

```bash
git add .env.example scripts/langfuse-auth-header.sh
git commit -m "feat(langfuse): add env vars and auth-header helper script"
```

---

## Task 3: Add Maven dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the two dependencies**

Locate the `<dependencies>` block in `pom.xml`. Add right after the existing `micrometer-registry-prometheus` dependency:

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

(Versions managed by `spring-boot-starter-parent` 3.2.5 — no explicit `<version>` needed.)

- [ ] **Step 2: Verify the build resolves**

Run:
```bash
mvn -q -DskipTests dependency:resolve | tail -20
mvn -q -DskipTests compile
```
Expected: BUILD SUCCESS, no "could not resolve" errors.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat(langfuse): add micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp"
```

---

## Task 4: Add tracing/OTLP/observation config to application.yml

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Extend `management:` block with tracing + otlp**

Replace the current `management:` block (lines starting at 62) with:

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

- [ ] **Step 2: Enable Spring AI prompt/completion/tool content logging**

Add the following under the existing `spring.ai:` block (insert after the `vectorstore:` section, still within `spring.ai:`):

```yaml
    chat:
      observations:
        log-prompt: true
        log-completion: true
    tools:
      observations:
        include-content: true
```

- [ ] **Step 3: Add `langfuse:` top-level block (read by the resource customizer)**

Append at the end of the file:

```yaml
# Langfuse environment label, attached as OTel resource attribute
langfuse:
  environment: ${LANGFUSE_ENVIRONMENT:dev}
```

- [ ] **Step 4: Verify YAML parses**

Run:
```bash
mvn -q -DskipTests spring-boot:run -Dspring-boot.run.arguments="--spring.config.activate.on-profile=lint --spring.main.web-application-type=none --spring.main.lazy-initialization=true" &
APP_PID=$!
sleep 12
kill $APP_PID 2>/dev/null || true
```

Easier alt — just compile and let Spring's strict YAML parser catch it on next test run. Skip this step if `mvn compile` already passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat(langfuse): wire OTLP tracing + Spring AI observation content logging

- management.tracing.sampling.probability=1.0 (dev)
- management.otlp.tracing endpoint/headers/gzip
- spring.ai.chat.observations.log-prompt/completion=true
- spring.ai.tools.observations.include-content=true
- langfuse.environment label"
```

---

## Task 5: Write failing test for `LangfuseObservationConfig` filter

**Files:**
- Create: `src/test/java/com/dawn/ai/config/LangfuseObservationConfigTest.java`

- [ ] **Step 1: Write the test**

Create the file:

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

> Note: `AiInteractionContext.setSessionId(blank)` already calls `remove()` per its existing implementation, so the third test asserts the *resulting* behavior (no `session.id` emitted) regardless of which layer enforces it.

- [ ] **Step 2: Run test — must FAIL**

Run:
```bash
mvn -q -Dtest=LangfuseObservationConfigTest test
```
Expected: compilation FAIL — `LangfuseObservationConfig` does not exist.

---

## Task 6: Implement `LangfuseObservationConfig`

**Files:**
- Create: `src/main/java/com/dawn/ai/config/LangfuseObservationConfig.java`

- [ ] **Step 1: Implement the class**

Create:

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

- [ ] **Step 2: Run the unit test — must PASS**

Run:
```bash
mvn -q -Dtest=LangfuseObservationConfigTest test
```
Expected: 3 tests, BUILD SUCCESS.

- [ ] **Step 3: Run full test suite to ensure no regression**

Run:
```bash
mvn -q test
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/config/LangfuseObservationConfig.java \
        src/test/java/com/dawn/ai/config/LangfuseObservationConfigTest.java
git commit -m "feat(langfuse): inject session.id ObservationFilter + langfuse.environment OTel resource

Per-span session.id is read from the existing AiInteractionContext
(already propagated across Reactor / boundedElastic by
AiInteractionContextAccessor). langfuse.environment is set as a
process-wide OTel resource attribute via SDK customizer."
```

---

## Task 7: Wire app container env so it reaches Langfuse over the dawn-network

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add 3 env vars to the `app:` service environment**

In the `app:` service `environment:` block, append:

```yaml
      - LANGFUSE_OTLP_ENDPOINT=${LANGFUSE_OTLP_ENDPOINT:-http://langfuse-web:3000/api/public/otel/v1/traces}
      - LANGFUSE_AUTH_BASE64=${LANGFUSE_AUTH_BASE64}
      - LANGFUSE_ENVIRONMENT=${LANGFUSE_ENVIRONMENT:-dev}
```

> NOTE: do **NOT** add Langfuse services to `app.depends_on`. Per spec §9, the
> business app must boot independently of the observability stack — early traces
> are dropped silently if langfuse-web is not yet ready.

- [ ] **Step 2: Validate compose**

Run:
```bash
docker compose config --quiet && echo OK
```
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(langfuse): pass OTLP endpoint + auth + env to app container"
```

---

## Task 8: End-to-end verification

**Files:** none (manual verification)

- [ ] **Step 1: Bring up the full stack**

Run:
```bash
docker compose down
docker compose up -d
sleep 75
docker compose ps
```
Expected: every container shows `(healthy)` or `Up`. `langfuse-web` is `(healthy)`.

- [ ] **Step 2: Sanity-check Langfuse UI**

```bash
open http://localhost:3001    # macOS
```
Log in with `admin@dawn.local` / `dawn-admin-123`. Project `dawn-ai` should be pre-created.

- [ ] **Step 3: Trigger one chat round trip**

Run:
```bash
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is 2+2?","sessionId":"smoke-001"}' | jq .
```
Expected: HTTP 200, JSON body with an answer.

- [ ] **Step 4: Verify trace appears in Langfuse**

Wait ~5 seconds. In Langfuse UI:

1. **Tracing** → see a new trace within 5–10 s.
2. Open the trace → root span has model name, latency, token counts, **prompt + completion text visible**.
3. Child spans visible for advisor / vector-store query / embedding (depending on chat path).
4. Trace attribute panel shows `session.id = smoke-001` and `langfuse.environment = dev`.
5. **Sessions** view (left nav) → session `smoke-001` is listed and groups the trace.

- [ ] **Step 5: Verify resilience — Langfuse down does NOT break the app**

Run:
```bash
docker compose stop langfuse-web langfuse-worker
sleep 3
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"ping","sessionId":"smoke-002"}' -w "\nHTTP %{http_code}\n"
docker compose start langfuse-web langfuse-worker
```
Expected: HTTP 200 (chat succeeds even with Langfuse down).

- [ ] **Step 6: Verify Prometheus pipeline still healthy (no regression)**

Run:
```bash
curl -s http://localhost:8080/actuator/prometheus | head -20
curl -s http://localhost:9090/-/healthy
```
Expected: prometheus exposition format text; `Prometheus Server is Healthy.`.

---

## Task 9: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Append observability section**

Append at the end of `README.md`:

```markdown

## 📊 Observability with Langfuse

`docker compose up` brings up a self-hosted **Langfuse v3** stack alongside
the app. Every Spring AI call (chat, embedding, vector-store, tool-call)
is exported via OTLP to Langfuse — full prompt, completion, and tool I/O
included.

### First run

```bash
cp .env.example .env
# (Optional) regenerate the auth header if you change keys:
scripts/langfuse-auth-header.sh    # paste output into LANGFUSE_AUTH_BASE64

docker compose up -d
```

Visit **http://localhost:3001** and log in:

| Field | Value (defaults from `.env.example`) |
|---|---|
| Email | `admin@dawn.local` |
| Password | `dawn-admin-123` |

The `dawn-ai` project is auto-created. New chats appear under **Tracing**
within seconds; the **Sessions** tab groups traces by the `sessionId` you
pass in the chat request body.

### What goes where

| Stack | Purpose | UI |
|---|---|---|
| **Prometheus + Grafana** (existing) | Aggregate metrics, RED, SLOs | http://localhost:3000 |
| **Langfuse** (new) | Per-request traces, prompts, tool I/O | http://localhost:3001 |

The two are independent — Langfuse downtime never affects the app.

### Rotating keys / production

Change `LANGFUSE_INIT_PROJECT_PUBLIC_KEY` and `_SECRET_KEY` in `.env`,
re-run `scripts/langfuse-auth-header.sh`, paste the new value into
`LANGFUSE_AUTH_BASE64`, then `docker compose up -d --force-recreate
langfuse-web app`.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs(langfuse): document observability stack, first-run, and key rotation"
```

---

## Self-Review

**Spec coverage check:**

| Spec section | Implementing task |
|---|---|
| §4 Architecture diagram | Tasks 1, 3, 4, 6 |
| §5 Component inventory & ports | Task 1 |
| §6.1 New env vars | Task 2 |
| §6.2 application.yml additions | Task 4 |
| §6.3 Maven dependencies | Task 3 |
| §7.1 LangfuseObservationConfig | Tasks 5, 6 |
| §7.2 No edits to existing classes | Tasks 5–6 honor this (only new files) |
| §8 Acceptance verification | Task 8 |
| §9 Failure modes (no depends_on) | Task 7 step 1 NOTE; Task 8 step 5 |
| §10 Documentation (README, .env.example, helper script) | Tasks 2, 9 |

All spec items mapped — no gaps.

**Placeholder scan:** No "TBD", no "implement later", every code block contains the actual code. ✔

**Type consistency:** `langfuseSessionFilter()` and `langfuseResourceCustomizer()` bean names match between Tasks 5 (test) and 6 (impl). `LANGFUSE_AUTH_BASE64`, `LANGFUSE_OTLP_ENDPOINT`, `LANGFUSE_ENVIRONMENT` env names consistent across Tasks 1, 2, 4, 7. ✔

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-11-langfuse-integration.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
