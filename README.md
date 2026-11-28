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

### Streaming Chat (with agentic RAG + memory + tools)
```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "message": "What is the weather in Beijing?",
    "sessionId": "my-session-001",
    "enabledTools": ["webTool"],
    "enabledSkills": []
  }'
```

`enabledTools` and `enabledSkills` are optional per-request allowlists. Omit either
field to use all corresponding server defaults; send an empty array to disable that
capability type for the current request. The web UI stores both selections per Session.



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
- `ai.subagent.dispatches{type,status}` — Sub-agent dispatch count, see [docs/agent-paradigms/multi-agent/](docs/agent-paradigms/multi-agent/)

## 🧩 Core Components

| Component | Role | Analogy |
|-----------|------|---------|
| `AgentOrchestrator` | ReAct loop, Tool dispatch | Thread Pool Manager |
| `SubAgentRegistry` / `DispatchSubAgentTool` | Isolated-context sub-agent for heavy research | Worker pool with dedicated scratchpad |
| `MemoryService` | Redis-backed conversation history | Circular Buffer + TTL |
| `RagService` | Vector similarity retrieval | MySQL Index Lookup |

See [docs/agent-paradigms/multi-agent/design.md](docs/agent-paradigms/multi-agent/design.md) for the sub-agent design and
[docs/agent-paradigms/multi-agent/verification.md](docs/agent-paradigms/multi-agent/verification.md) for the manual verification scenarios.

## 📊 Observability

可观测能力按职责拆分，默认走**轻量云端 + VPS 方案**（本地仅跑 `app + postgres + redis`）：

| 能力 | 部署位置 | 接入方式 | UI |
|---|---|---|---|
| **LLM Tracing / Evaluation** | **Langfuse Cloud (JP Region)** | OTLP/HTTP (`x-langfuse-ingestion-version: 4`) | https://jp.cloud.langfuse.com |
| **JVM / HTTP / 业务指标** | **远程 VPS** (`/opt/dawn-ai-metrics`，编排见 `deploy/vps-metrics/`) | SSH 双向隧道 (`scripts/vps-metrics-tunnel.sh`) | http://localhost:3000（经隧道） |

### 1. Langfuse Cloud (JP Region) 接入

在 https://jp.cloud.langfuse.com 创建项目并获取 API Keys 后，填入 `.env`：

```dotenv
LANGFUSE_BASE_URL=https://jp.cloud.langfuse.com
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
LANGFUSE_OTLP_ENDPOINT=https://jp.cloud.langfuse.com/api/public/otel/v1/traces
MANAGEMENT_TRACING_ENABLED=true
```

然后生成并填入 `LANGFUSE_AUTH_BASE64`：

```bash
./scripts/langfuse-auth-header.sh
```

### 2. VPS 指标监控（Prometheus + Grafana）

VPS 上的 Prometheus 与 Grafana 均采用 `network_mode: host` 且仅绑定 `127.0.0.1`（0 公网暴露），完整编排与预装看板统一位于 `deploy/vps-metrics/`（同步到 VPS `/opt/dawn-ai-metrics/`）。

> **为什么需要保活隧道**：`scripts/vps-metrics-tunnel.sh` 同时维护反向抓取通道（`VPS:18080 → 本地:8080`）和正向 UI 映射（`本地:3000/9090 → VPS:3000/9090`）。若隧道未开启，VPS Prometheus 将无法拉取本地指标（期间 Grafana 曲线会出现断层）。Langfuse Cloud Tracing 为主动 HTTPS 推送，不受隧道影响。

#### 方式 A：macOS `launchd` 后台自启保活（推荐，无感持续采集）

使用仓库提供的模板 `deploy/vps-metrics/com.dawn-ai.vps-metrics-tunnel.plist` 注册为当前用户的后台常驻服务（开机自启、休眠唤醒/断网每 10s 自动重连，约占 5 MB 内存）：

```bash
# 1. 安装并启动（在项目根目录执行）
sed "s|__PROJECT_DIR__|$(pwd)|g" deploy/vps-metrics/com.dawn-ai.vps-metrics-tunnel.plist \
  > ~/Library/LaunchAgents/com.dawn-ai.vps-metrics-tunnel.plist
launchctl bootstrap "gui/$(id -u)" ~/Library/LaunchAgents/com.dawn-ai.vps-metrics-tunnel.plist

# 2. 查看运行状态与日志
launchctl print "gui/$(id -u)/com.dawn-ai.vps-metrics-tunnel" | grep -E "state|pid"
tail -n 30 /tmp/dawn-ai-vps-metrics-tunnel.log

# 3. 停止 / 卸载
launchctl bootout "gui/$(id -u)/com.dawn-ai.vps-metrics-tunnel"
rm -f ~/Library/LaunchAgents/com.dawn-ai.vps-metrics-tunnel.plist
```

安装完成后无需手动执行脚本，直接打开浏览器即可查看：
- **Grafana**：http://localhost:3000（默认 `admin` / `admin123`，看板：`Dawn AI - Spring Boot`）
- **Prometheus**：http://localhost:9090

#### 方式 B：临时前台运行（不装常驻服务时）

```bash
./scripts/vps-metrics-tunnel.sh
```

> **Tracing 总开关**：`MANAGEMENT_TRACING_ENABLED`（Spring Boot 原生 `management.tracing.enabled`）。
> 未填入真实 `LANGFUSE_AUTH_BASE64` 时可在 `.env` 中暂时设为 `false` 避免 OTLP 401 warn。
