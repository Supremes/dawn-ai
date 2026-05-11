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

## 🧩 Core Components

| Component | Role | Analogy |
|-----------|------|---------|
| `AgentOrchestrator` | ReAct loop, Tool dispatch | Thread Pool Manager |
| `MemoryService` | Redis-backed conversation history | Circular Buffer + TTL |
| `RagService` | Vector similarity retrieval | MySQL Index Lookup |

## 📊 Observability with Langfuse

A self-hosted **Langfuse v3** stack ships in `docker-compose.yml` behind
the **`observe`** profile. Every Spring AI call (chat, embedding,
vector-store, tool-call) is exported via OTLP to Langfuse — full prompt,
completion, and tool I/O included.

### Daily dev (no Langfuse, ~2.4 GB total)

```bash
cp .env.example .env
docker compose up -d                 # business services only
```

Set `LANGFUSE_TRACING_ENABLED=false` in `.env` to suppress OTLP exporter
warnings while no Langfuse backend is running.

### When you want traces (~3.2 GB total)

```bash
# (Optional) regenerate the auth header if you changed the keys:
scripts/langfuse-auth-header.sh      # paste into LANGFUSE_AUTH_BASE64
# Make sure LANGFUSE_TRACING_ENABLED=true in .env

docker compose --profile observe up -d
```

The `observe` profile spins up `langfuse-postgres`, `clickhouse`,
`langfuse-redis`, `minio`, a one-shot `minio-init` (creates the
`langfuse` S3 bucket), then `langfuse-worker` and `langfuse-web`.
Wait ~60 s for `langfuse-web` to become healthy.

### Memory budget

| Container | Limit (`mem_limit`) | Notes |
|---|---:|---|
| langfuse-web | 640 MB | Node `--max-old-space-size=512` |
| clickhouse | 768 MB | Custom `clickhouse/config.d/low-memory.xml` caps caches |
| langfuse-worker | 384 MB | Node `--max-old-space-size=320` |
| minio | 256 MB | |
| langfuse-postgres | 192 MB | |
| langfuse-redis | 64 MB | `--maxmemory 32mb --maxmemory-policy allkeys-lru` |

Hard caps make the dev VM behave; remove them for production and let
ClickHouse auto-size against the host.

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
`LANGFUSE_AUTH_BASE64`, then `docker compose --profile observe up -d
--force-recreate langfuse-web app`.

