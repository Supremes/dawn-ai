# RAG 召回优化落地点（结合项目代码）

> 创建日期：2026-05-21  
> 背景：分析 dawn-ai 项目现有 RAG 代码后，给出可直接落地的召回优化点。

---

## 当前代码现状判断

| 方向 | 当前状态 | 问题 |
|---|---|---|
| Dense | `RagService` 使用 PGVector + `similarityThreshold` | 仍然会返回语义相近但业务无关的 chunk |
| Hybrid | 已有 `PostgresBm25Retriever` + `ReciprocalRankFusion` | 短句会走 hybrid，但 **带 metadata filter 时强制走 dense** |
| Rerank | 已有 heuristic 和 cross-encoder 两套 | 默认是 `heuristic`，不是强相关性判断 |
| HyDE | 已有 `HydeQueryGenerator`，配置里开启 | 对短句容易过度扩写；Agent 工具里还可能和 `RagService` 重复 HyDE |
| Query rewrite | 已有 `QueryRewriter` | 只在 `KnowledgeSearchTool` 用，`/rag/search` 直查不走 |
| Metadata | 只有 `source/category/docId/topicId` | 不够表达产品、模块、版本、语言、文档类型、章节 |
| Chunking | `OverlapTextSplitter` 500/50 | 无标题层级、中文标点不完整、没有 parent/sibling 返回 |
| Evaluation | 已有 `RetrievalEvaluator` | 只有 2 个样例，缺 `Precision@K`，无法定位"无关文档太多" |

---

## P0：优先落地，直接影响短句召回质量

### 1. 修正短句 + metadata filter 的路由策略

**问题代码** — `RetrievalRouter`：

```java
if (request.hasMetadataFilters()) {
    return RetrievalStrategy.DENSE;
}
```

这会导致：短句 query + 有 topic/category/source filter 时，反而不走 BM25，退回 dense。

`PostgresBm25Retriever` 已经支持 `SUPPORTED_FILTER_KEYS = Set.of("source", "category", "docId", "topicId")`，完全具备边过滤边 BM25 的能力。

**改法**：metadata filter 只负责缩小范围；strategy 仍根据 query 特征决定；短句、数字、引号、错误码、产品名仍走 `HYBRID`。

- 修改 `RetrievalRouter`
- 更新 `RetrievalRouterTest`，补充 `metadataFilters + short query → HYBRID` 测试

**预期效果**：短句如"登录失败""退款""证书过期"在有 topic/category 限定时，仍能用 BM25 抓关键词，减少 dense 泛化误召回。

---

### 2. 避免 HyDE 对短句无脑扩写

**问题**：`RagService.retrieve()` 每次都执行 `hydeQueryGenerator.generate()`，而 `application.yml` 里 `hyde-enabled: true`。

对短句（如"登录失败"），HyDE 可能扩写成一段泛化描述，导致 dense 召回更多不相关文档。

**改法**：只在以下场景启用 HyDE：

- query 是完整自然语言问题
- query 长度较长（> 15 字 / token）
- 非数字 / 非错误码 / 非产品名
- 非短关键词
- 非强 metadata filter 场景

短句走：`原 query / rewrite query → BM25 + Dense → rerank`（不走 HyDE）

```java
// 示例判断逻辑，放在 RagService 或 HydeQueryGenerator 前
boolean shouldUseHyde = strategy == RetrievalStrategy.DENSE
        && !isShortQuery(query)
        && !looksLikeExactLookup(query);
```

**预期效果**：短句 query 不再被 HyDE 扩散，precision 明显改善。

---

### 3. 统一 Query Rewrite / HyDE 的调用位置

**问题**：当前链路不一致：

- `KnowledgeSearchTool` 先调 `QueryRewriter`，再调 `HydeQueryGenerator`，传给 `RagService.retrieve`
- `RagService.retrieve` 里还会再调一次 `HydeQueryGenerator`（重复执行）
- `/api/v1/rag/search` 完全绕过 `QueryRewriter`

**改法**：query transformation 统一收敛到 `RagService.retrieve()`：

```text
original query
  → optional query rewrite (注入 QueryRewriter)
  → strategy routing
  → optional HyDE only for dense branch
  → dense/sparse retrieve
  → fusion
  → rerank
```

`KnowledgeSearchTool` 只负责：接收 query + metadata filters → 调 `RagService.retrieve()`。

- `RagService` 注入 `QueryRewriter`
- `KnowledgeSearchTool` 删除直接调用 `QueryRewriter` / `HydeQueryGenerator`
- dedup key 使用 normalized query + filters
- 调整 `KnowledgeSearchToolTest`

**预期效果**：减少重复 LLM 调用，避免 query 被多次改写导致语义漂移。

---

### 4. 默认启用真正的 cross-encoder reranker

**问题**：当前 `reranker.type: heuristic`，只做词面 token 覆盖率打分，对中文效果弱，不是语义相关性判断。

项目已有完整的 `CrossEncoderRetrievalReranker` 实现，支持调用外部 reranker 服务。

**改法**：

1. 部署一个 reranker 服务（如 `bge-reranker-v2-m3`、`bge-reranker-large`、Jina reranker）
2. 切换配置：

```yaml
app:
  ai:
    rag:
      reranker:
        type: cross-encoder
      cross-encoder:
        base-url: http://localhost:8081
        rerank-path: /rerank
        model: bge-reranker-v2-m3
```

3. 增加 rerank score 阈值（现在 reranker 只排序，不拒绝低相关文档）：

```yaml
app.ai.rag.reranker.min-score: 0.3
```

4. `CrossEncoderRetrievalReranker` 返回时把 score 写入 metadata（`rerankScore`）
5. `RagService` 在 rerank 后按 `min-score` 过滤

**预期效果**：解决"TopK 里塞进很多看似相关但实际无关的文档"。

---

### 5. 修正 KnowledgeSearchTool 的 filter fallback

**问题代码**：

```java
if (docs.isEmpty() && !appliedFilters.isEmpty()) {
    retry without metadata filters  // 会丢掉 topicId！
}
```

如果 `topicId` 是系统上下文给的硬约束，去掉后可能召回别的 topic 文档，直接制造"无关文档"。

**改法**：区分硬过滤和软过滤：

| filter | 类型 | fallback 时是否可移除 |
|---|---|---|
| `topicId` | 硬约束 | **不可移除** |
| `docId` | 硬约束 | **不可移除** |
| `source` | 软约束 | 可视情况移除 |
| `category` | 软约束 | 可视情况移除 |

**预期效果**：减少 Agent 因 filter miss 而跨知识域召回无关文档。

---

## P1：提升中文和业务文档场景的召回稳定性

### 6. 中文短句不要依赖 PostgreSQL `english` FTS

**问题**：`app.ai.rag.sparse.text-search-config: english` 对英文文档有用，但中文短句下 BM25 几乎失效，仍然主要靠 dense。

**建议方案（按复杂度）**：

| 方案 | 复杂度 | 说明 |
|---|---:|---|
| `ILIKE '%query%'` fallback | 低 | 对短中文关键词立竿见影 |
| `pg_trgm` 相似度 | 中 | 适合短词、拼写变体 |
| `zhparser` / `pg_jieba` | 中高 | 更接近中文 BM25 |

**落地点**：如果 query 含中文且长度 <= 8 个字，`PostgresBm25Retriever` 增加 `content ILIKE ?` fallback，与 FTS 结果合并去重。

---

### 7. 扩展 metadata 维度

**问题**：`RagRequest` 只有 `source/category/docId/topicId`，不够表达真实业务文档的维度。

**建议扩展字段**：

```text
product       # 产品名（secure-mail / secure-hub 等）
module        # 功能模块（login / push / cert 等）
platform      # 平台（ios / android / web）
version       # 版本号
language      # 文档语言（zh-CN / en-US）
documentType  # 文档类型（tutorial / reference / faq / troubleshooting）
section       # 章节路径
```

**落地点**：

- `RagRequest` 增加 `Map<String, String> extraMetadata` 或显式字段
- `RagController.ingest/search` 透传
- `PostgresBm25Retriever.SUPPORTED_FILTER_KEYS` 扩展
- `KnowledgeSearchTool.Request` 增加对应字段

---

### 8. Chunk 加标题/章节上下文

**问题**：当前 chunk 只有正文，embedding 不知道该 chunk 属于哪个产品、模块、章节。

**改法**：入库时把标题路径 prepend 到 chunk 文本：

```text
文档：Secure Mail Admin Guide
章节：登录配置 > SSO 登录 > 常见错误
正文：...
```

**落地点**：

- Markdown 文档增加 header-aware splitter
- Tika 提取 title/author 时写入 metadata
- `OverlapTextSplitter.createChunk` 支持把 selected metadata prepend 到 text

---

### 9. 中文标点边界补齐

**问题代码**：

```java
List.of('.', '?', '!', '\n')  // 没有中文标点
```

中文长段落可能切得不自然，语义被截断。

**落地点**：

```java
List.of('.', '?', '!', '\n', '。', '？', '！', '；')
```

---

### 10. Sibling expansion（轻量 Parent-Child）

**现状**：已保存 `parentDocumentId/chunkIndex/chunkCount`，但 retrieve 后只返回单个命中 chunk。

**改法**：新增 `ChunkNeighborExpander`，通过 JDBC 查询同 `parentDocumentId` 且 `chunkIndex in [i-1, i, i+1]`，在最终喂给 LLM 前扩展上下文。

---

## P2：非短句 query 的落地点

### 11. 长 query 增加 Query Decomposition

**问题**：包含多个意图的长 query 整体 embedding，容易召回混杂内容。例如：

> "如何配置 SSO 登录，并且登录失败时怎么排查？"

项目已支持 Agent 多次调用 `KnowledgeSearchTool`，但依赖 LLM 自己决策，不够稳定。

**落地点**：

- 新增 `QueryDecomposer`
- 只对含"并且、同时、以及、and、or"的长 query 启用
- 每个 sub-query 走现有 `RagService.retrieve`
- 最后统一 rerank 合并

---

## P0：补评估，否则无法量化改进效果

### 12. RetrievalEvaluator 增加 Precision@K + 扩充评估集

**问题**："召回了太多无关文档"是 **Precision@K 低**，但当前 `RetrievalEvaluator` 没有 Precision@K 指标。

**增加指标**：

```text
Precision@K
NoiseRate@K = 1 - Precision@K
HitRate@K
```

**评估集扩充（至少 100 条）**：

- 短句 query：30 条
- 中等 query：30 条
- 复杂多意图 query：20 条
- 带 metadata query：20 条

每条结构：

```json
{
  "query": "登录失败",
  "expectedDocIds": ["doc-login-sso-001"],
  "hardNegativeDocIds": ["doc-web-login-002"],
  "metadataFilters": {
    "product": ["secure-mail"],
    "platform": ["ios"]
  }
}
```

**比较矩阵**：

```text
DENSE
HYBRID
HYBRID + heuristic rerank
HYBRID + cross-encoder rerank
HYBRID + cross-encoder + threshold
HyDE on/off
```

---

## 最可能导致当前"无关文档太多"的项目内原因

1. **默认 reranker 是 heuristic**：只做词面 token 打分，无法强判断 query-doc 是否真正相关
2. **HyDE 默认开启且可能重复执行**：短句 query 被扩写后 dense 召回更多泛相关文档
3. **带 metadata filter 时强制走 dense**：短句最需要 BM25，却被路由到 dense
4. **filter fallback 可能丢掉 topicId/source/category**：导致跨知识域召回
5. **中文 sparse 检索能力弱**：`english` FTS 不适合中文短句
6. **chunk 缺标题/章节上下文**：embedding 不知道 chunk 属于哪个产品/模块
7. **评估只看是否有结果，不看 Precision@K**：无关文档多的问题无法量化

---

## 推荐实施顺序

| 优先级 | 落地点 | 改动规模 | 收益 |
|---|---|---:|---|
| P0 | metadata filter 不再强制 dense，短句继续 HYBRID | 小 | 高 |
| P0 | HyDE 对短句关闭，避免重复 HyDE | 小中 | 高 |
| P0 | cross-encoder reranker + rerank score threshold | 中 | 很高 |
| P0 | `KnowledgeSearchTool` fallback 保留 hard filters | 小 | 高 |
| P0 | `RetrievalEvaluator` 增加 Precision@K + 评估集 | 小中 | 高 |
| P1 | 中文短句 sparse fallback：ILIKE / pg_trgm | 中 | 高 |
| P1 | 扩展 metadata：product/module/version/language/docType | 中 | 高 |
| P1 | chunk 加标题/章节上下文 | 中 | 高 |
| P1 | sibling expansion | 中 | 中高 |
| P2 | 长 query decomposition / multi-query retrieval | 中高 | 中高 |

### 推荐 MVP 第一组（一个迭代）

```text
1. 修 RetrievalRouter：短句 + metadata 仍走 HYBRID
2. HyDE 只对非短句启用，避免 Agent/RagService 重复 HyDE
3. 接入 cross-encoder reranker + min rerank score
4. KnowledgeSearchTool fallback 不丢 topicId/docId
5. RetrievalEvaluator 增加 Precision@K + 真实短句评估集
```

这五条改完后，可直接量化验证"短句无关召回过多"是否明显下降。
