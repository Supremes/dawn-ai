# Langfuse Observability Integration — Design Spec

- **Date**: 2026-05-11
- **Branch**: `feat/langfuse-integration`
- **Status**: Draft (pending spec review + user sign-off)
- **Owner**: Supremes

## 1. Goal

Add end-to-end LLM observability to dawn-ai by self-hosting **Langfuse v3** in
the existing `docker-compose.yml` and shipping all Spring AI Observation spans
(chat, embedding, vector store, advisor, tool-calling) to Langfuse via the
**OTLP/HTTP** protocol. Sessions in the Langfuse UI must aggregate by the
existing `sessionId` carried in `AiInteractionContext`.

## 2. Non-Goals

- No production deployment topology (this spec covers local-dev only;
  prod hardening is a separate workstream).
- No Langfuse prompt-management, evaluation, or playground features. Only
  tracing/observability.
- No replacement of the existing `agent/trace` (`AgentStep` /
  `StepCollector` / `ToolExecutionAspect`) module — that records
  business-level step timelines and stays untouched.
- No replacement of Prometheus/Grafana metrics — they continue to serve
  RED metrics; Langfuse owns LLM-specific traces.

## 3. Decisions Locked-In via Brainstorming

| # | Decision | Choice |
|---|----------|--------|
| 1 | Integration path | **OTel**: Spring AI Observation → Micrometer → OTLP exporter → Langfuse OTLP endpoint |
| 2 | Langfuse hosting | **Self-host v3 in docker-compose**, fully isolated from business pg/redis |
| 3 | Data granularity | **Full prompt + completion + tool I/O** (`spring.ai.chat.observations.log-prompt/completion=true`, `spring.ai.tools.observations.include-content=true`); 100% sampling in dev |
| 4 | sessionId correlation | **Enabled** — `ObservationFilter` reads `AiInteractionContext.getSessionId()` and emits OTel attribute `session.id` |
| 5 | First-run UX | **Auto-bootstrap** via `LANGFUSE_INIT_*` env (org/project/user/keys created on first start) |

## 4. Architecture

```
dawn-ai (Spring Boot 3.2 + Spring AI 1.1)
   │
   │  Spring AI Observation (built-in instrumentation:
   │     ChatModel, EmbeddingModel, VectorStore, Advisor, ToolCalling)
   ▼
Micrometer ObservationRegistry
   │  + LangfuseSessionObservationFilter
   │       ↳ reads AiInteractionContext.getSessionId()
   │       ↳ injects KeyValues: session.id, langfuse.environment
   ▼
micrometer-tracing-bridge-otel  (Span ↔ Observation bridge)
   │
   ▼
OpenTelemetry SDK + OTLP/HTTP Exporter
   │
   │  POST  http://langfuse-web:3000/api/public/otel/v1/traces
   │  Header  Authorization: Basic base64(public_key:secret_key)
   ▼
┌──────────────────────────── Langfuse v3 stack ────────────────────────────┐
│  langfuse-web      (Next.js, ingestion + UI, host:3001 → container:3000)  │
│  langfuse-worker   (background processor)                                 │
│  langfuse-postgres (metadata, host:5433 → container:5432)                 │
│  clickhouse        (trace columnar store, host:8123/9000)                 │
│  langfuse-redis    (queues, host:6380 → container:6379)                   │
│  minio             (S3-compatible object store, host:9100/9101)           │
└────────────────────────────────────────────────────────────────────────────┘
```

## 5. Component Inventory & Port Allocation

| Service | Image (pinned tag) | Host port | Internal port | Notes |
|---------|--------------------|-----------|---------------|-------|
| langfuse-web | `langfuse/langfuse:3` | **3001** | 3000 | Web UI + OTLP ingestion endpoint. Grafana already owns 3000. |
| langfuse-worker | `langfuse/langfuse-worker:3` | – | – | Internal-only |
| langfuse-postgres | `postgres:16-alpine` | 5433 | 5432 | Isolated from business `postgres` (5432) |
| clickhouse | `clickhouse/clickhouse-server:24.3` | – | 8123 / 9000 | **Internal-only** — only consumed by langfuse-web/worker, no host exposure to keep dev port surface small |
| langfuse-redis | `redis:7-alpine` | – | 6379 | **Internal-only** — only consumed by langfuse stack |
| minio | `minio/minio:latest` | – | 9000 / 9001 | **Internal-only** — only consumed by langfuse stack |

All services join the existing `dawn-network` bridge. New named volumes:
`langfuse_postgres_data`, `clickhouse_data`, `langfuse_redis_data`, `minio_data`.

## 6. Configuration Surface

### 6.1 New environment variables (`.env.example`)

```dotenv
# --- Langfuse first-run bootstrap (langfuse-web container) ---
LANGFUSE_INIT_ORG_ID=dawn-ai
LANGFUSE_INIT_PROJECT_ID=dawn-ai
LANGFUSE_INIT_PROJECT_PUBLIC_KEY=pk-lf-dawn-dev
LANGFUSE_INIT_PROJECT_SECRET_KEY=sk-lf-dawn-dev
LANGFUSE_INIT_USER_EMAIL=admin@dawn.local
LANGFUSE_INIT_USER_PASSWORD=dawn-admin-123
LANGFUSE_INIT_USER_NAME=Dawn Admin

# --- dawn-ai → Langfuse OTLP exporter ---
LANGFUSE_OTLP_ENDPOINT=http://langfuse-web:3000/api/public/otel/v1/traces
LANGFUSE_AUTH_BASE64=<base64(LANGFUSE_INIT_PROJECT_PUBLIC_KEY:LANGFUSE_INIT_PROJECT_SECRET_KEY)>
```

> The base64 value is generated once by a helper script (`scripts/langfuse-auth-header.sh`)
> and pasted into `.env`; documented in README.

### 6.2 `application.yml` additions

```yaml
management:
  tracing:
    sampling:
      probability: 1.0   # dev: full sampling
  otlp:
    tracing:
      endpoint: ${LANGFUSE_OTLP_ENDPOINT:http://localhost:3001/api/public/otel/v1/traces}
      compression: gzip
      headers:
        Authorization: Basic ${LANGFUSE_AUTH_BASE64:}

spring:
  ai:
    chat:
      observations:
        log-prompt: true
        log-completion: true
    tools:
      observations:
        include-content: true
```

### 6.3 New Maven dependencies (`pom.xml`)

- `io.micrometer:micrometer-tracing-bridge-otel`
- `io.opentelemetry:opentelemetry-exporter-otlp`

(Spring Boot starter `actuator` already on the classpath provides
`management.otlp.tracing.*` autoconfiguration. No need for the
`opentelemetry-spring-boot-starter` — Spring Boot 3.2 actuator covers it.)

## 7. Code Changes

### 7.1 `LangfuseObservationConfig` (new)

A single `@Configuration` class with two beans:

**(a) Per-span session filter** — injects `session.id` from
`AiInteractionContext` onto every observation:

```java
@Bean
ObservationFilter langfuseSessionFilter() {
    return ctx -> {
        String sid = AiInteractionContext.getSessionId();
        if (sid != null && !sid.isBlank()) {
            ctx.addLowCardinalityKeyValue(KeyValue.of("session.id", sid));
        }
        return ctx;
    };
}
```

**(b) Static resource attribute** — `langfuse.environment` is process-wide
(not per-span), so it is set as an OTel **resource attribute** via the
SDK customizer instead of polluting every span:

```java
@Bean
OpenTelemetryConfigurer langfuseResourceCustomizer(
        @Value("${langfuse.environment:dev}") String env) {
    return otel -> otel.addResourceCustomizer((res, cfg) ->
        res.merge(Resource.create(Attributes.of(
            AttributeKey.stringKey("langfuse.environment"), env))));
}
```

The bridge converts low-cardinality KeyValues to OTel span attributes;
Langfuse picks up `session.id` natively (documented Langfuse OTel attribute)
to drive the **Sessions** view.

### 7.2 No edits to existing classes

- `AiInteractionContext` already exposes `getSessionId()` — used as-is.
- `AiInteractionContextAccessor` already registered for Reactor / executor
  propagation — covers tool callbacks running on `boundedElastic`.
- `agent/trace/*` untouched.
- Controllers / services untouched.

## 8. Data Flow Verification (Acceptance)

1. `cp .env.example .env` and `./scripts/langfuse-auth-header.sh` to fill
   `LANGFUSE_AUTH_BASE64`.
2. `docker compose up -d` — all containers reach `healthy`.
3. Visit `http://localhost:3001`, log in with `admin@dawn.local` /
   `dawn-admin-123`, see `dawn-ai` project pre-created.
4. `mvn spring-boot:run` (or `docker compose up app`).
5. `curl -X POST localhost:8080/api/v1/chat -H 'Content-Type: application/json' \
    -d '{"message":"hi","sessionId":"smoke-001"}'`
6. In Langfuse UI → **Tracing**: a new trace appears within ≤5 s containing:
   - root span `chat` (model, latency, tokens, full prompt + completion)
   - child spans for advisor, vector-store query, embedding, tool calls
     (with full I/O)
   - attribute `session.id = smoke-001`
7. **Sessions** view groups all traces with `sessionId = smoke-001`.

## 9. Failure Modes & Handling

| Failure | Behavior | Mitigation |
|---------|----------|-----------|
| Langfuse stack down | OTLP exporter retries with backoff, eventually drops spans. App requests **succeed**. | OTel SDK default behavior; explicit `otel.exporter.otlp.timeout=10s`. |
| Wrong `LANGFUSE_AUTH_BASE64` | 401 from ingestion endpoint, spans dropped. | Logged at WARN once per minute (OTel internal logger). |
| First-run bootstrap race | langfuse-web may need 30–60 s after pg/clickhouse ready. | App **does NOT** declare `depends_on` on the Langfuse stack at all — observability failure must never block business boot. Early traces (before langfuse-web is up) are dropped silently by the OTel exporter. |
| ClickHouse / MinIO disk full | langfuse-worker stops persisting | Out of scope for dev; documented in README. |

## 10. Documentation Changes

- `README.md` — append new section **"📊 Observability (Langfuse)"**
  with: how to start, default credentials, where to view traces, how to
  rotate keys.
- `docs/` — add this design spec (current file).
- `.env.example` — add the variables listed in §6.1.
- `scripts/langfuse-auth-header.sh` — small helper that prints
  `base64(public:secret)` for the operator to paste into `.env`.

## 11. Out-of-Scope / Follow-Ups

- Production secret management (Vault / AWS Secrets Manager).
- Trace sampling tuning for production (`probability=0.1` + tail sampling).
- Langfuse RBAC / multi-tenant setup.
- Wiring Langfuse evaluations & datasets into RAG eval workflow
  (`rag/evaluation`) — natural follow-up but separate spec.
- Replacing `agent/trace` with pure OTel spans — possible long-term
  unification, not part of this PR.

## 12. Risks

- **Container footprint**: stack adds ~1.5 GB RAM + ~6 containers; Apple
  Silicon dev machines should handle it but documented as a caveat.
- **Spring AI Observation API drift**: 1.1 is the first stable line; if
  property names move in a minor version, config must be re-checked. Pinned
  version in `pom.xml` mitigates.
- **Langfuse OTel endpoint stability**: `/api/public/otel/v1/traces` is
  marked stable in Langfuse v3 (>= v3.0). We pin the `langfuse:3` tag.

## 13. Rollout

1. Merge `feat/langfuse-integration` to `master` after review.
2. No DB migration, no breaking change for existing clients.
3. Existing users running `docker compose up` get the new stack
   automatically; opt-out is `docker compose up app postgres redis`
   (explicit service list).
