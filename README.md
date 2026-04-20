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
- OpenAI API Key
- NVIDIA GPU for local `bge-m3` embedding serving (recommended)

### Run with Docker Compose

```bash
export OPENAI_API_KEY=sk-your-key-here
export BASE_URL=https://your-chat-provider.example.com
export CHAT_MODEL=qwen-plus
export LOCAL_EMBEDDING_MODEL=BAAI/bge-m3
export LOCAL_EMBEDDING_DIMENSIONS=1024
docker compose up -d
```

This starts an `Infinity` embedding service at `http://localhost:7997/v1/embeddings` and wires the app container to use it for embeddings while chat requests keep using `BASE_URL`.

All services in `docker-compose.yml` use `restart: unless-stopped`, so after they are created once with `docker compose up -d`, Docker will bring them back automatically after a host reboot.

On Linux, also enable the Docker daemon at boot time:

```bash
sudo systemctl enable docker
sudo systemctl start docker
```

The base Compose file is CPU-safe by default. If Docker GPU runtime is available on your machine, enable GPU serving with:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d
```

If Docker reports `no known GPU vendor found`, stay on the base `docker-compose.yml` or fix NVIDIA Container Toolkit / Docker Desktop GPU integration first.

### Run locally

```bash
# Start dependencies
docker compose up -d postgres redis embedding

# Run application
export OPENAI_API_KEY=sk-your-key-here
export BASE_URL=https://your-chat-provider.example.com
export CHAT_MODEL=qwen-plus
export EMBEDDING_BASE_URL=http://localhost:7997
export EMBEDDING_MODEL=BAAI/bge-m3
export EMBEDDING_DIMENSIONS=1024
./mvnw spring-boot:run
```

If you switch an existing pgvector dataset from a 1536-d model to `bge-m3`, rebuild the vector table or re-ingest your knowledge base because the embedding dimension changes to 1024.

## 📡 API Usage

### Chat (with memory + tools)
```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What is the weather in Beijing?",
    "sessionId": "my-session-001",
    "ragEnabled": false
  }'
```

### Chat with RAG
```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What is our refund policy?",
    "sessionId": "session-002",
    "ragEnabled": true
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
| `CalculatorTool` | Math expression evaluation | JUC Callable |
| `WeatherTool` | Weather data fetch | JUC Callable |
