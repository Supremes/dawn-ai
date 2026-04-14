# Embedding 接口测试文档

**项目**：dawn-ai  
**日期**：2026-04-14  
**测试环境**：本地 Docker（WSL2 Ubuntu 24.04 + RTX 3060 Ti 8GB）  
**Embedding 模型**：`BAAI/bge-m3`（Infinity `0.0.77`）  
**向量维度**：1024

---

## 1. 环境配置

### 配置链路

```
.env
  └─ LOCAL_EMBEDDING_DIMENSIONS=1024
        ↓ docker-compose.yml
  EMBEDDING_DIMENSIONS=${LOCAL_EMBEDDING_DIMENSIONS:-1024}
        ↓ application.yml
  spring.ai.openai.embedding.options.dimensions: ${EMBEDDING_DIMENSIONS:1536}
  spring.ai.vectorstore.pgvector.dimensions:     ${EMBEDDING_DIMENSIONS:1536}
        ↓ PGVector
  vector_store.embedding  vector(1024)
```

### 关键环境变量（`.env`）

| 变量 | 值 |
|---|---|
| `LOCAL_EMBEDDING_MODEL` | `BAAI/bge-m3` |
| `LOCAL_EMBEDDING_BASE_URL` | `http://embedding:7997` |
| `LOCAL_EMBEDDING_API_KEY` | `local-embedding-key` |
| `LOCAL_EMBEDDING_DIMENSIONS` | `1024` |

### `application.yml` 相似度阈值

```yaml
app.ai.rag.similarity-threshold: 0.45
```

> bge-m3 的 cosine 相似度普遍在 0.4–0.65，原值 `0.7`（为 OpenAI ada-002 校准）会过滤掉大部分结果，已调整为 `0.45`。

---

## 2. Infinity 服务直连测试

**Endpoint**：`http://localhost:7997`  
**关键参数**：启动时加 `--url-prefix /v1` 使路径与 Spring AI OpenAI 客户端兼容

### 2.1 模型信息

```bash
curl -s http://localhost:7997/v1/models | python3 -m json.tool
```

**预期响应**：

```json
{
  "data": [{
    "id": "BAAI/bge-m3",
    "capabilities": ["embed"],
    "backend": "torch"
  }]
}
```

**结果**：✅ PASS

---

### 2.2 单条 Embedding 请求（英文）

```bash
curl -s http://localhost:7997/v1/embeddings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer local-embedding-key" \
  -d '{"model":"BAAI/bge-m3","input":"Hello, this is a test sentence for BGE-M3 embedding."}'
```

| 验证项 | 预期 | 实际 |
|---|---|---|
| HTTP 状态 | 200 | 200 ✅ |
| `data[0].embedding` 长度 | 1024 | 1024 ✅ |
| `model` 字段 | `BAAI/bge-m3` | `BAAI/bge-m3` ✅ |

---

### 2.3 批量 Embedding 请求（英文 + 中文）

```bash
curl -s http://localhost:7997/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"BAAI/bge-m3","input":["Hello, this is a test sentence.","中文测试：这是一段用于测试BGE-M3多语言能力的文本。"]}'
```

| 验证项 | 预期 | 实际 |
|---|---|---|
| `data[0]` 维度 | 1024 | 1024 ✅ |
| `data[1]` 维度 | 1024 | 1024 ✅ |
| 中文多语言支持 | 正常返回向量 | ✅ |

---

### 2.4 语义相似度验证

```bash
curl -s http://localhost:7997/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{
    "model":"BAAI/bge-m3",
    "input":[
      "What is the capital of France?",
      "Paris is the capital of France.",
      "The weather is nice today."
    ]
  }'
```

使用 cosine 相似度计算：

| 句对 | cosine sim | 判断 |
|---|---|---|
| 问题 vs 相关答案 | **0.7917** | ✅ 高（语义相关） |
| 问题 vs 无关句 | **0.3503** | ✅ 低（语义无关） |

---

## 3. Spring Boot REST 接口测试

**Base URL**：`http://localhost:8080/api/v1/rag`

### 3.1 批量文档注入（`POST /ingest`）

注入 10 条测试文档，覆盖 4 个 source、6 个 category：

| # | source | category | 内容摘要 |
|---|---|---|---|
| 1 | spring-ai-docs | framework | Spring AI 框架介绍 |
| 2 | model-docs | embedding | BGE-M3 模型描述 |
| 3 | rag-tutorial | architecture | RAG 架构说明 |
| 4 | pgvector-docs | database | PGVector 扩展介绍 |
| 5 | rag-tutorial | retrieval | Hybrid Search + RRF |
| 6 | model-docs | reranking | Cross-encoder 重排 |
| 7 | devops-notes | infrastructure | Docker Compose |
| 8 | devops-notes | infrastructure | NVIDIA Container Toolkit |
| 9 | spring-ai-docs | infrastructure | Redis + Spring Boot |
| 10 | devops-notes | monitoring | Prometheus + Actuator |

**请求示例**：

```bash
curl -X POST http://localhost:8080/api/v1/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "content": "BGE-M3 is a multilingual embedding model developed by BAAI...",
    "source": "model-docs",
    "category": "embedding"
  }'
```

**预期响应**：

```json
{"docId": "5c0fee21-a794-4169-8b73-f2c76a448e50", "status": "ingested"}
```

**结果**：10/10 ✅ 全部注入成功

---

### 3.2 语义检索测试（`GET /search`）

#### T1 — 语义搜索：向量相似度检索

```bash
curl "http://localhost:8080/api/v1/rag/search?query=how+does+vector+similarity+search+work&topK=3"
```

| 结果 | source | category | sim |
|---|---|---|---|
| 1 | pgvector-docs | database | 0.5575 |
| 2 | rag-tutorial | retrieval | 0.5525 |
| 3 | rag-tutorial | architecture | 0.4705 |

**结果**：✅ PASS — top-1 为 PGVector 文档，语义高度相关

---

#### T2 — 语义搜索：GPU Docker

```bash
curl "http://localhost:8080/api/v1/rag/search?query=how+to+run+GPU+workloads+in+Docker+containers&topK=3"
```

| 结果 | source | category | sim |
|---|---|---|---|
| 1 | devops-notes | infrastructure | **0.7129** |
| 2 | devops-notes | infrastructure | 0.5538 |

**结果**：✅ PASS — top-1 sim=0.71，NVIDIA Toolkit 文档精准召回

---

#### T3 — 元数据过滤：单值 category

```bash
curl "http://localhost:8080/api/v1/rag/search?query=multilingual+text+representation&topK=5&category=embedding"
```

| 结果 | source | category | sim |
|---|---|---|---|
| 1 | model-docs | embedding | 0.5201 |

**结果**：✅ PASS — category 过滤生效，仅返回 embedding 类文档

---

#### T4 — 元数据过滤：单值 source

```bash
curl "http://localhost:8080/api/v1/rag/search?query=container+infrastructure+deployment&topK=5&source=devops-notes"
```

| 结果 | source | category | sim |
|---|---|---|---|
| 1 | devops-notes | infrastructure | 0.5152 |
| 2 | devops-notes | infrastructure | 0.5069 |

**结果**：✅ PASS — source 过滤生效，只返回 devops-notes 文档

---

#### T5 — 元数据过滤：多值 category

```bash
curl "http://localhost:8080/api/v1/rag/search?query=improving+search+precision+and+recall&topK=5&category=retrieval&category=reranking"
```

| 结果 | source | category | sim |
|---|---|---|---|
| 1 | rag-tutorial | retrieval | 0.5864 |
| 2 | model-docs | reranking | 0.5638 |

**结果**：✅ PASS — 两类别各召回 1 条，多值过滤正确

---

#### T6 — 检索策略：DENSE

```bash
curl "http://localhost:8080/api/v1/rag/search?query=Spring+Boot+AI+integration+framework&topK=3&strategy=DENSE"
```

| 结果 | source | category | sim |
|---|---|---|---|
| 1 | spring-ai-docs | framework | 0.6621 |
| 2 | spring-ai-docs | infrastructure | 0.4644 |
| 3 | model-docs | embedding | 0.4551 |

**结果**：✅ PASS

---

#### T7 — 检索策略：HYBRID

```bash
curl "http://localhost:8080/api/v1/rag/search?query=Spring+Boot+AI+integration+framework&topK=3&strategy=HYBRID"
```

| 结果 | source | category | sim |
|---|---|---|---|
| 1 | spring-ai-docs | framework | 0.6621 |
| 2 | spring-ai-docs | infrastructure | 0.4644 |
| 3 | model-docs | embedding | 0.4551 |

**结果**：✅ PASS — HYBRID 与 DENSE 结果一致（当前测试集规模小，BM25 无显著差异）

---

#### T8 — 跨语言检索（中文 Query）

```bash
curl "http://localhost:8080/api/v1/rag/search?query=%E5%A6%82%E4%BD%95%E4%BD%BF%E7%94%A8%E5%90%91%E9%87%8F%E6%95%B0%E6%8D%AE%E5%BA%93%E8%BF%9B%E8%A1%8C%E8%AF%AD%E4%B9%89%E6%90%9C%E7%B4%A2&topK=3"
```

（Query: `如何使用向量数据库进行语义搜索`，文档为英文）

| 结果 | source | category | sim |
|---|---|---|---|
| 1 | pgvector-docs | database | 0.5514 |
| 2 | rag-tutorial | retrieval | 0.5431 |
| 3 | model-docs | reranking | 0.5038 |

**结果**：✅ PASS — 中文 query 成功召回英文文档，验证 bge-m3 跨语言能力

---

## 4. 问题记录与修复

| # | 问题 | 原因 | 修复 |
|---|---|---|---|
| 1 | Infinity `/v1/embeddings` 返回 404 | `0.0.77` 默认路径为 `/embeddings`，无 `/v1` 前缀 | 启动参数加 `--url-prefix /v1` |
| 2 | 注入时 HTTP 500 | PGVector 表列为 `vector(1536)`，与 bge-m3 输出 1024 维不符 | `ALTER TABLE` 重建列为 `vector(1024)` |
| 3 | 大部分查询返回 0 条结果 | `similarity-threshold: 0.7` 针对 ada-002 调参，bge-m3 分数普遍在 0.4–0.65 | 调整为 `0.45` |
| 4 | `.env` 末尾 `LOCAL_EMBEDDING_DIMENSIONS=1024#` | 编辑时误加 inline 注释（无前置空格） | `sed` 清除末尾注释 |

---

## 5. 测试总结

| 测试用例 | 状态 |
|---|---|
| Infinity 模型信息 | ✅ PASS |
| 单条英文 embedding | ✅ PASS |
| 批量多语言 embedding | ✅ PASS |
| 语义相似度验证 | ✅ PASS |
| 批量注入 10 条文档 | ✅ PASS (10/10) |
| T1 语义检索 | ✅ PASS |
| T2 GPU Docker 语义检索 | ✅ PASS |
| T3 category 单值过滤 | ✅ PASS |
| T4 source 单值过滤 | ✅ PASS |
| T5 category 多值过滤 | ✅ PASS |
| T6 DENSE 策略 | ✅ PASS |
| T7 HYBRID 策略 | ✅ PASS |
| T8 中文跨语言检索 | ✅ PASS |

**总计：13/13 ✅**
