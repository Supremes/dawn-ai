# Dawn AI — Complete Java AI Agent Application

> Built with Java 17 + Spring Boot 3.2 + Spring AI

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     REST API Layer                       │
│              ChatController | RagController              │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│                  Service Layer                           │
│         ChatService │ RagService │ MemoryService         │
└──────┬───────────────┬───────────────┬──────────────────┘
       │               │               │
┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
│   Agent     │ │  PGVector   │ │    Redis    │
│ Orchestrator│ │ (RAG Store) │ │  (Memory)  │
└──────┬──────┘ └─────────────┘ └─────────────┘
       │
┌──────▼──────────────────────────────────────┐
│           Spring AI / OpenAI API            │
│   Chat Model │ Embedding Model │ Tool Calls │
└─────────────────────────────────────────────┘
```

## 🚀 Quick Start

### Prerequisites
- Java 17+
- Docker & Docker Compose
- AI Model Configuration
  - Local: with oMLX enabled, deployed LLM and embedding model as belows:
    - Qwen3.5-9B-MLX-4bit
    - bge-m3-mlx-fp16

  - Cloud


### Run with Docker Compose

```bash
# Start dependencies
docker compose up -d

# Run application with below env variables configured
# oMLX - LLM
OPENAI_API_KEY=2486
BASE_URL=http://host.docker.internal:8000
CHAT_MODEL=Qwen3.5-9B-MLX-4bit

# oMXL - EMBEDDING MODEL 
EMBEDDING_BASE_URL=http://host.docker.internal:8000
EMBEDDING_API_KEY=2486
EMBEDDING_DIMENSIONS=1024
EMBEDDING_MODEL=bge-m3-mlx-fp16
```



## 📡 API Usage

### Chat (with agentic RAG + memory + tools)
```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What is the weather in Beijing?",
    "sessionId": "my-session-001"
  }'
```



### Ingest Document into Knowledge Base
```bash
curl -X POST http://localhost:8080/api/v1/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Our refund policy allows returns within 30 days with receipt.",
    "source": "policy-doc-v1",
    "category": "policy"
  }'
```

### Search Knowledge Base
```bash
curl "http://localhost:8080/api/v1/rag/search?query=refund+policy&topK=3"
```

## 📊 Observability

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Health status |
| `GET /actuator/prometheus` | Prometheus metrics |
| `http://localhost:9090` | Prometheus UI |
| `http://localhost:3000` | Grafana (admin/admin123) |

### Key Metrics
- `ai.agent.chat.duration` — Agent response latency
- `ai.rag.ingestion.total` — Documents ingested
- `ai.rag.retrieval.total` — RAG queries executed
- `ai.chat.request.duration` — Raw LLM call duration
- `ai.subagent.dispatches{type,status}` — Sub-agent dispatch count, see [docs/multi-agent/](docs/multi-agent/)

## 🧩 Core Components

| Component | Role | Analogy |
|-----------|------|---------|
| `AgentOrchestrator` | ReAct loop, Tool dispatch | Thread Pool Manager |
| `SubAgentRegistry` / `DispatchSubAgentTool` | Isolated-context sub-agent for heavy research | Worker pool with dedicated scratchpad |
| `MemoryService` | Redis-backed conversation history | Circular Buffer + TTL |
| `RagService` | Vector similarity retrieval | MySQL Index Lookup |

See [docs/multi-agent/design.md](docs/multi-agent/design.md) for the sub-agent design and
[docs/multi-agent/verification.md](docs/multi-agent/verification.md) for the manual verification scenarios.

## 📊 Observability

可观测能力分两条独立线，全部走 **`docker-compose.observe.yml`** overlay 文件，
按需 opt-in，互不依赖：

| Profile | 启动的容器 | 用途 | UI |
|---|---|---|---|
| `--profile metrics` | `prometheus` + `grafana` | JVM / HTTP / 业务指标 | http://localhost:3000 |
| `--profile observe` | `langfuse-*` + `clickhouse` + `minio` 等 7 个 | LLM trace / prompt / tool I/O | http://localhost:3001 |

### 启动命令矩阵

```bash
# 业务最小（仅 app + postgres + redis，~1.1 GB）
docker compose up -d

# + 监控
docker compose -f docker-compose.yml -f docker-compose.observe.yml --profile metrics up -d

# + Langfuse（首次启动等 ~60s 让 langfuse-web 健康）
docker compose -f docker-compose.yml -f docker-compose.observe.yml --profile observe up -d

# 全开
docker compose -f docker-compose.yml -f docker-compose.observe.yml \
  --profile metrics --profile observe up -d
```

> **Tracing 总开关**：`MANAGEMENT_TRACING_ENABLED`（Spring Boot 原生 `management.tracing.enabled`）。
> 主 compose 默认 `false`；叠加 `-f docker-compose.observe.yml` 时 overlay 会把它覆盖为 `true`。
> 想强制覆盖时在 `.env` 里设置即可。⚠️ 这是整个 Spring Tracing 子系统的开关，
> 关掉它所有 `@Observed` 和 OTLP exporter（不限后端）都会失效。
>
> **Corner case**：单用 `--profile metrics`（叠加了 overlay 但没启 langfuse-web）时
> tracing 仍被自动开为 `true`，会持续 OTLP warn。此时在 `.env` 里显式
> `MANAGEMENT_TRACING_ENABLED=false` 静默即可。

### 内存限制

Docker Compose 不再为容器设置 `mem_limit` / `mem_reservation`，也不再为 Redis、Node 或 ClickHouse 额外设置本地开发内存上限。各组件按宿主机 / Docker Desktop 配额自适应。

### Langfuse 登录

Visit **http://localhost:3001**：

| Field | Value (defaults from `.env.example`) |
|---|---|
| Email | `admin@dawn.local` |
| Password | `dawn-admin-123` |

`dawn-ai` 项目自动创建。新会话几秒后出现在 **Tracing**；**Sessions** 标签按
你传入的 `sessionId` 聚合。

### Rotating keys / production

Change `LANGFUSE_INIT_PROJECT_PUBLIC_KEY` and `_SECRET_KEY` in `.env`,
re-run `scripts/langfuse-auth-header.sh`, paste the new value into
`LANGFUSE_AUTH_BASE64`, then:

```bash
docker compose -f docker-compose.yml -f docker-compose.observe.yml \
  --profile observe up -d --force-recreate langfuse-web app
```
