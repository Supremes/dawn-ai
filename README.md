# Dawn AI - Python版本

> 使用LangChain/LangGraph重写的AI Agent应用

## 架构

```
┌─────────────────────────────────────────────────────────┐
│                    FastAPI Layer                         │
│              ChatRouter | RagRouter                      │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│                  Agent Layer                             │
│         AgentOrchestrator (LangGraph)                    │
└──────┬───────────────┬───────────────┬──────────────────┘
       │               │               │
┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
│   LangGraph │ │   PGVector  │ │    Redis    │
│  ReAct Loop │ │ (RAG Store) │ │  (Memory)  │
└──────┬──────┘ └─────────────┘ └─────────────┘
       │
┌──────▼──────────────────────────────────────┐
│           LangChain / OpenAI API            │
│   Chat Model │ Embedding Model │ Tool Calls │
└─────────────────────────────────────────────┘
```

## 快速开始

### 前置条件
- Python 3.12+
- Docker & Docker Compose
- AI模型配置（本地oMLX或云端）

### 安装依赖

```bash
uv sync
```

### 配置环境变量

```bash
cp .env.example .env
# 编辑.env文件，配置API密钥等
```

### 启动服务

```bash
# 启动依赖
docker compose up -d

# 启动应用
uv run dawn-ai
```

## API使用

### Chat（带RAG + Memory + Tools）

```bash
curl -X POST http://localhost:8000/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "北京天气怎么样？",
    "session_id": "my-session-001"
  }'
```

### 流式Chat

```bash
curl -X POST http://localhost:8000/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "message": "北京天气怎么样？",
    "session_id": "my-session-001"
  }'
```

### 摄取文档到知识库

```bash
curl -X POST http://localhost:8000/api/v1/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "content": "我们的退款政策允许在30天内凭收据退货。",
    "source": "policy-doc-v1",
    "category": "policy"
  }'
```

### 搜索知识库

```bash
curl "http://localhost:8000/api/v1/rag/search?query=退款政策&top_k=3"
```

## 核心组件

| 组件 | 角色 | 类比 |
|------|------|------|
| `AgentOrchestrator` | ReAct循环，工具调度 | 线程池管理器 |
| `MemoryService` | Redis对话历史 | 循环缓冲区 + TTL |
| `RagService` | 向量相似度检索 | MySQL索引查找 |

## 开发

### 运行测试

```bash
uv run pytest
```

### 代码格式化

```bash
uv run ruff format
```

### 类型检查

```bash
uv run mypy src
```
