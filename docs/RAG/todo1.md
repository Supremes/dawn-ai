结合当前代码，最值得落地的不是“再加一个 dense 模型”，而是把已有的 **hybrid / rerank / HyDE / metadata / eval** 链路真正调顺。你们项目已经有不少模块，但有些默认配置和调用方式会导致“短句召回泛化、无关文档混入”。

## 当前代码现状判断

| 方向 | 当前状态 | 问题 |
|---|---|---|
| Dense | `RagService` 使用 PGVector + `similarityThreshold` | 仍然会返回语义相近但业务无关的 chunk |
| Hybrid | 已有 `PostgresBm25Retriever` + `ReciprocalRankFusion` | 短句会走 hybrid，但 **带 metadata filter 时强制走 dense** |
| Rerank | 已有 heuristic 和 cross-encoder 两套 | 默认是 `heuristic`，不是强相关性判断 |
| HyDE | 已有 `HydeQueryGenerator`，配置里开启 | 现在容易对短句过度扩写；Agent 工具里还可能和 `RagService` 重复 HyDE |
| Query rewrite | 已有 `QueryRewriter` | 只在 `KnowledgeSearchTool` 用，`/rag/search` 直查不会用 |
| Metadata | 只有 `source/category/docId/topicId` | 不够表达产品、模块、版本、语言、文档类型、章节 |
| Chunking | `OverlapTextSplitter` 500/50 | 无标题层级、中文标点不完整、没有 parent/sibling 返回 |
| Evaluation | 已有 `RetrievalEvaluator` | 只有 2 个样例，且缺 `Precision@K`，无法定位“无关文档太多” |

---

## P0：优先落地，直接影响短句召回质量

### 1. 修正短句 + metadata filter 的路由策略

当前 `RetrievalRouter` 里有这个逻辑：

```java
if (request.hasMetadataFilters()) {
    return RetrievalStrategy.DENSE;
}
```

这会导致：

> 用户短句 query + 有 topic/category/source filter 时，反而不走 BM25，退回 dense。

但 `PostgresBm25Retriever` 实际已经支持 metadata filter：

```java
SUPPORTED_FILTER_KEYS = Set.of("source", "category", "docId", "topicId")
```

所以建议改成：

- metadata filter 只负责缩小范围；
- retrieval strategy 仍然根据 query 特征决定；
- 短句、数字、引号、错误码、产品名仍走 `HYBRID`。

**落地点：**

- 修改 `RetrievalRouter`
- 更新 `RetrievalRouterTest`
- 对 `metadataFilters + short query` 增加测试

预期效果：短句如“登录失败”“退款”“证书过期”在有 topic/category 限定时，仍能用 BM25 抓关键词，减少 dense 泛化误召回。

---

### 2. 避免 HyDE 对短句无脑扩写

当前 `RagService.retrieve()` 每次都会执行：

```java
String retrievalQuery = hydeQueryGenerator.generate(retrievalRequest.getQuery());
```

而 `application.yml` 里：

```yaml
app.ai.rag.hyde-enabled: true
```

这对长问题可能有帮助，但对短句很危险。

例如：

```text
登录失败
```

HyDE 可能扩成一段很泛的“登录失败可能由账号、密码、网络、SSO、权限导致……”，结果 dense 会召回更多泛相关文档。

**建议：**

只在这些场景启用 HyDE：

- query 是完整自然语言问题；
- query 长度较长；
- 非数字 / 非错误码 / 非产品名；
- 非短关键词；
- 非强 metadata filter 场景。

短句走：

```text
原 query / rewrite query → BM25 + Dense → rerank
```

不要走 HyDE。

**落地点：**

在 `RagService` 或 `HydeQueryGenerator` 前增加判断：

```java
boolean shouldUseHyde = strategy == RetrievalStrategy.DENSE
        && !isShortQuery(query)
        && !looksLikeExactLookup(query);
```

预期效果：短句 query 不再被 HyDE 扩散，precision 会明显好一些。

---

### 3. 统一 Query Rewrite / HyDE 的调用位置

现在链路不一致：

- `KnowledgeSearchTool` 先调用 `QueryRewriter`
- `KnowledgeSearchTool` 又调用 `HydeQueryGenerator`
- 然后传给 `RagService.retrieve`
- `RagService.retrieve` 里又会调用 `HydeQueryGenerator`
- `/api/v1/rag/search` 则完全绕过 `QueryRewriter`

也就是说 Agent 场景和 REST search 场景行为不一致，而且 Agent 场景可能重复 HyDE。

**建议：**

把 query transformation 统一收敛到 `RagService.retrieve()`：

```text
original query
  → optional query rewrite
  → strategy routing
  → optional HyDE only for dense branch
  → dense/sparse retrieve
  → fusion
  → rerank
```

`KnowledgeSearchTool` 只负责：

```text
接收 query + metadata filters → 调 RagService.retrieve()
```

**落地点：**

- `RagService` 注入 `QueryRewriter`
- `KnowledgeSearchTool` 删除直接调用 `QueryRewriter` / `HydeQueryGenerator`
- dedup key 可以使用 normalized query + filters
- 调整 `KnowledgeSearchToolTest`

预期效果：减少重复 LLM 调用，避免 query 被多次改写导致语义漂移。

---

### 4. 默认启用真正的 cross-encoder reranker

当前配置是：

```yaml
app.ai.rag.reranker.type: heuristic
```

但 `HeuristicRetrievalReranker` 只是：

- query token 覆盖率
- 原始排名 boost
- phrase boost
- metadata boost

它不是语义级相关性判断，尤其中文分词也弱。

项目里已经有 `CrossEncoderRetrievalReranker`，建议直接落地一个 reranker 服务，例如：

- `bge-reranker-v2-m3`
- `bge-reranker-large`
- Jina reranker
- Cohere rerank

配置切换：

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

同时建议补一个 rerank score 阈值：

```yaml
app.ai.rag.reranker.min-score: 0.3
```

现在 reranker 只排序，不拒绝低相关文档。应该支持：

```text
rerank 后低于阈值 → 不进入最终 context
```

**落地点：**

- `CrossEncoderRetrievalReranker` 返回时把 score 写入 metadata，例如 `rerankScore`
- `RagService` 在 rerank 后按 `min-score` 过滤
- 日志打印 docId/source/category/rerankScore

预期效果：解决“TopK 里塞进很多看似相关但实际无关的文档”。

---

## P1：提升中文和业务文档场景的召回稳定性

### 5. 中文短句不要依赖 PostgreSQL `english` FTS

当前 sparse retrieval 是：

```yaml
app.ai.rag.sparse.text-search-config: english
```

这对英文文档有用，但中文场景下 PostgreSQL FTS 默认能力不够。你们的问题是中文短句时，BM25 可能并没有真正发挥作用，最后仍然主要靠 dense。

建议做一个中文 fallback：

```text
短中文 query
  → pg_trgm / ILIKE / 自定义中文分词 FTS
  → dense
  → RRF
  → rerank
```

可选落地方案：

| 方案 | 复杂度 | 说明 |
|---|---:|---|
| `ILIKE '%query%'` fallback | 低 | 对短中文关键词立竿见影 |
| `pg_trgm` 相似度 | 中 | 适合短词、拼写变体 |
| `zhparser` / `pg_jieba` | 中高 | 更接近中文 BM25 |
| 外部 ES/OpenSearch | 高 | 更完整，但引入新组件 |

建议先做低成本版：

- 如果 query 含中文且长度 <= 8；
- 在 `PostgresBm25Retriever` 中增加 `content ILIKE ?` fallback；
- 与 FTS 结果合并去重。

预期效果：短句如“登录失败”“证书过期”“账号冻结”能命中文档原文，而不是完全依赖 embedding。

---

### 6. metadata 维度要扩展，不要只靠 source/category

当前 `RagRequest` 只有：

```java
content
source
category
topicId
```

这不够过滤真实业务文档。

建议扩展为：

```java
private Map<String, List<String>> metadata;
```

或者显式增加常用字段：

```text
product
module
platform
version
language
documentType
section
author
createdAt
updatedAt
```

同时更新：

- `RagController.search`
- `RagService.ingest`
- `PostgresBm25Retriever.SUPPORTED_FILTER_KEYS`
- `KnowledgeSearchTool.Request`
- `HeuristicRetrievalReranker.metadataBoost`

尤其是这些字段最有价值：

```text
product/module/platform/version/language/documentType/topicId
```

预期效果：用户问“登录失败”时，可以限定到具体产品、模块、平台，避免跨域召回。

---

### 7. 修正 KnowledgeSearchTool 的 filter fallback

当前逻辑：

```java
if (docs.isEmpty() && !appliedFilters.isEmpty()) {
    retry without metadata filters
}
```

这会带来一个风险：

> 如果 `topicId` 是系统上下文给的硬约束，第一次查不到，第二次去掉 topicId 后可能召回别的 topic 文档。

这会直接制造“无关文档太多”。

建议区分硬过滤和软过滤：

| filter | 类型 | fallback 时是否可移除 |
|---|---|---|
| `topicId` | 硬约束 | 不可移除 |
| `docId` | 硬约束 | 不可移除 |
| `source` | 软约束 | 可视情况移除 |
| `category` | 软约束 | 可视情况移除 |

**落地点：**

`KnowledgeSearchTool` fallback 时保留 `topicId/docId`，只尝试移除 `source/category`。

预期效果：减少 Agent 因 filter miss 而跨知识域召回。

---

## P1：Chunk 和文档入库质量

### 8. 给 chunk 加标题/章节上下文

当前 `OverlapTextSplitter` 只是切正文，然后 metadata 有：

```text
parentDocumentId
chunkIndex
chunkCount
```

但 chunk 文本里没有：

```text
文档标题
章节路径
小节标题
产品名
版本
```

dense embedding 对孤立 chunk 很敏感。建议入库时把标题路径拼到 chunk 前面：

```text
source: secure-mail-admin-guide.md
section: 登录配置 > SSO 登录 > 常见错误
content: ...
```

实际 embedding 文本可以是：

```text
文档：Secure Mail Admin Guide
章节：登录配置 > SSO 登录 > 常见错误
正文：...
```

**落地点：**

- Markdown 文档增加 header-aware splitter
- Tika 提取 title/author 时写入 metadata
- `OverlapTextSplitter.createChunk` 支持把 selected metadata prepend 到 text

预期效果：chunk 不再“裸奔”，dense 召回更稳定。

---

### 9. 中文标点边界补齐

当前 splitter 的标点只有：

```java
List.of('.', '?', '!', '\n')
```

中文文档应该补：

```text
。 ？ ！ ； ：
```

否则中文长段落可能切得不自然。

**落地点：**

修改 `OverlapTextSplitter.DEFAULT_PUNCTUATION_MARKS`：

```java
List.of('.', '?', '!', '\n', '。', '？', '！', '；')
```

预期效果：中文 chunk 更完整，减少语义被切断。

---

### 10. 做 sibling expansion，而不是只返回命中的单个 chunk

项目已经保存：

```text
parentDocumentId
chunkIndex
chunkCount
```

但 retrieve 后只返回命中的 chunk。可以落地一个轻量 parent-child 版本：

```text
命中 chunkIndex = 5
返回 chunkIndex = 4,5,6 的合并上下文
```

不一定要马上存完整 parent doc，可以先用 sibling expansion。

**落地点：**

- 新增 `ChunkNeighborExpander`
- 通过 JDBC 查询同 `parentDocumentId` 且 `chunkIndex in [i-1, i, i+1]`
- 在最终喂给 LLM 前扩展 context
- 检索排序仍以命中的 chunk 为准

预期效果：减少“召回命中但答案上下文不完整”的问题。

---

## P2：非短句 query 的落地点

### 11. 长 query 增加 Query Decomposition

当前长 query 默认走 `DENSE`：

```java
return keywordLike ? HYBRID : DENSE;
```

但长 query 可能包含多个意图，例如：

```text
如何配置 SSO 登录，并且登录失败时怎么排查？
```

现在会整体 embedding，容易召回混杂内容。

建议增加一个 `QueryDecomposer`：

```text
原问题
  → 子问题 1：如何配置 SSO 登录
  → 子问题 2：SSO 登录失败如何排查
  → 分别 retrieve
  → 合并去重
  → rerank
```

项目里 Agent 已经允许多次调用 `KnowledgeSearchTool`，`TaskPlanner` 也提示“可多次检索”，但这是交给 LLM 自己决定，不够稳定。检索层可以提供自动 decomposition。

**落地点：**

- 新增 `QueryDecomposer`
- 只对长 query / 包含“并且、同时、以及、and、or”启用
- 每个 sub-query 走现有 `RagService.retrieve`
- 最后统一 rerank

预期效果：非短句复杂问题召回更全、更少混杂。

---

## P0：必须补评估，否则不知道调参有没有变好

### 12. RetrievalEvaluator 增加 Precision@K 和真实评估集

当前 `RetrievalEvaluator` 只有：

```text
Recall@K
MRR
NDCG
```

但你们现在的问题是：

> 召回了太多无关文档

这主要是 **Precision@K 低**，不是单纯 Recall 低。

建议增加：

```text
Precision@K
NoiseRate@K = 1 - Precision@K
HitRate@K
```

并把测试集从现在的 2 条扩成至少：

```text
短句 query：30 条
中等 query：30 条
复杂多意图 query：20 条
带 metadata query：20 条
```

每条包含：

```json
{
  "query": "登录失败",
  "expectedDocIds": ["doc-login-sso-001"],
  "hardNegativeDocIds": ["doc-web-login-002", "doc-admin-permission-003"],
  "metadataFilters": {
    "product": ["secure-mail"],
    "platform": ["ios"]
  }
}
```

然后比较：

```text
DENSE
HYBRID
HYBRID + heuristic rerank
HYBRID + cross-encoder rerank
HYBRID + cross-encoder + threshold
HYDE on/off
```

预期效果：能明确知道问题到底来自 embedding、HyDE、chunk、filter、rerank 还是 topK。

---

## 最可能导致你们当前“无关文档太多”的项目内原因

按当前代码看，优先怀疑这些：

1. **默认 reranker 是 heuristic，不是真正 cross-encoder**  
   只能做简单词面打分，无法强判断 query-doc 是否真正相关。

2. **HyDE 默认开启，且可能重复执行**  
   短句 query 被扩写后容易变泛，导致 dense 召回更多“语义相关但业务无关”的文档。

3. **带 metadata filter 时强制走 dense**  
   短句最需要 BM25，但当前 `RetrievalRouter` 会在有 filter 时直接走 dense。

4. **filter fallback 可能去掉 topicId/source/category**  
   如果第一次查不到，第二次全库查，会把其他知识域的文档拉进来。

5. **中文 sparse 检索能力弱**  
   当前 PostgreSQL `english` FTS 更适合英文，不适合中文短句关键词。

6. **chunk 缺标题/章节上下文**  
   chunk 只有正文，embedding 不知道它属于哪个产品、模块、章节。

7. **评估指标不看 Precision@K**  
   现在“hit”只是非空结果，不代表召回相关文档。无关文档多的问题需要 Precision@K 才能暴露。

---

## 推荐实施顺序

| 优先级 | 落地点 | 改动规模 | 收益 |
|---|---|---:|---|
| P0 | metadata filter 不再强制 dense，短句继续 hybrid | 小 | 高 |
| P0 | HyDE 对短句关闭，避免重复 HyDE | 小中 | 高 |
| P0 | cross-encoder reranker + rerank score threshold | 中 | 很高 |
| P0 | `KnowledgeSearchTool` fallback 保留 hard filters | 小 | 高 |
| P0 | RetrievalEvaluator 增加 Precision@K | 小中 | 高 |
| P1 | 中文短句 sparse fallback：ILIKE / pg_trgm / 中文分词 | 中 | 高 |
| P1 | 扩展 metadata：product/module/version/language/docType | 中 | 高 |
| P1 | chunk 加标题/章节上下文 | 中 | 高 |
| P1 | sibling expansion | 中 | 中高 |
| P2 | 长 query decomposition / multi-query retrieval | 中高 | 中高 |

最建议先做这一组：

```text
1. 修 RetrievalRouter：短句 + metadata 仍走 HYBRID
2. HyDE 只对非短句启用，避免 Agent/RagService 重复 HyDE
3. 接入 cross-encoder reranker，并加 min rerank score
4. KnowledgeSearchTool fallback 不丢 topicId/docId
5. RetrievalEvaluator 增加 Precision@K + 真实短句评估集
```

这组改完后，基本能直接验证“短句无关召回过多”是否明显下降。


# knowledge
短句 query 在 RAG 里确实是 dense retrieval 的典型弱点：信息量太少，embedding 容易把“语义上有点像但业务上无关”的文档拉进来。业界更推荐的做法不是单纯调 dense，而是做 **Hybrid Retrieval + Rerank + Query Understanding**。

## 1. 短句 query 场景，推荐做法是什么？

比如用户只输入：

> “退款”  
> “登录失败”  
> “账号冻结”  
> “价格”  
> “权限问题”

这类 query 太短，dense embedding 缺少上下文，容易召回泛泛相关的文档。

业界常见方案是：

### 1. Hybrid Retrieval：BM25 + Dense

短句通常包含关键实体、动作、名词，**关键词匹配很重要**。

推荐架构：

```text
User Query
   ↓
BM25 / Keyword Search
Dense Vector Search
   ↓
合并候选集
   ↓
Reranker 重排
   ↓
Top N 文档进入 LLM
```

原因是：

| 方法 | 短句表现 |
|---|---|
| Dense Retrieval | 容易语义泛化，召回不精确 |
| BM25 / Sparse Retrieval | 对关键词、实体、术语更敏感 |
| Hybrid Retrieval | 同时兼顾语义和关键词 |
| Reranker | 最后判断 query-document 是否真的相关 |

短句 query 下，**BM25 往往比 dense 更可靠**，尤其是业务术语、产品名、错误码、接口名、配置项、专有名词。

---

### 2. 使用 Reranker 做二阶段排序

Dense/BM25 负责“粗召回”，reranker 负责“精排”。

例如：

```text
第一阶段：召回 Top 50 / Top 100
第二阶段：Cross-Encoder / BGE-reranker / Cohere Rerank / Jina Reranker 重排
第三阶段：只取 Top 3~8 给 LLM
```

短句 query 尤其需要 reranker，因为 embedding 相似度本身不能很好判断“是否真正回答了问题”。

常用 reranker：

- `bge-reranker-large`
- `bge-reranker-v2-m3`
- Cohere Rerank
- Jina Reranker
- ColBERT 类 late-interaction 模型

---

### 3. Query Expansion / Query Rewrite

短句可以先扩写成更完整的问题。

例如：

```text
原 query：登录失败

扩写：
用户在系统中登录失败，可能涉及账号密码错误、验证码、账号锁定、权限、SSO、OAuth、LDAP、网络或服务异常等问题。
```

但要注意：**不能盲目扩写**，否则会引入更多噪声。

更好的做法是基于上下文扩写：

```text
短句 query + 用户角色 + 当前页面 + 产品模块 + 历史对话
```

例如：

```text
query: 登录失败
context: 用户当前在企业微信 SSO 配置页
rewrite: 企业微信 SSO 登录失败的排查方法
```

---

### 4. 加强 Metadata Filter

短句 query 容易跨主题误召回，所以要用 metadata 限制范围。

例如：

```text
product = Secure Mail
platform = iOS
doc_type = troubleshooting
version = 24.3
language = zh-CN
tenant = enterprise
```

如果用户问：

> “登录失败”

不加 filter 可能召回：

- Web 登录失败
- iOS 登录失败
- Android 登录失败
- SSO 登录失败
- 邮箱登录失败
- 管理后台登录失败

加 filter 后才能提高 precision。

---

### 5. Intent Detection / Query Routing

短句往往需要先判断意图。

例如：

| Query | 可能意图 |
|---|---|
| “退款” | 售后政策、订单状态、退款流程 |
| “登录失败” | 故障排查 |
| “价格” | 产品定价 |
| “权限” | RBAC、管理员权限、接口权限 |

可以先做 query classification：

```text
query → intent → route to specific index / collection / filter
```

例如：

```text
登录失败 → troubleshooting index
价格 → pricing index
API 报错 → developer docs index
```

---

## 2. 非短句 query 出现召回错误，有哪些解决方案？

长 query 理论上信息更多，但也会召回错误，常见原因是 query 太复杂、包含多个意图、和文档 chunk 不匹配。

### 1. Query Decomposition：复杂问题拆分

例如用户问：

> “如何配置 SSO 登录，并且如果登录失败应该怎么排查？”

这其实是两个问题：

```text
1. 如何配置 SSO 登录？
2. SSO 登录失败如何排查？
```

如果直接 embedding，可能召回一堆混杂文档。更好的做法是拆成 sub-query 分别检索，再合并结果。

---

### 2. Multi-query Retrieval

让 LLM 从不同角度生成多个检索 query。

例如：

```text
原问题：iOS 设备收不到推送怎么办？

生成：
1. iOS 推送通知收不到
2. APNs 配置排查
3. 设备通知权限检查
4. MDM 推送证书问题
5. Secure Mail iOS 推送失败
```

然后多路召回，最后 rerank。

适合语义表达多样、用户说法和文档说法不一致的场景。

---

### 3. HyDE：Hypothetical Document Embeddings

先让 LLM 根据 query 生成一个“假设答案文档”，再用这个文档去做向量检索。

适合用户问题和文档表达差距较大的情况。

但 HyDE 有风险：如果 LLM 生成的假设答案偏了，检索也会偏。

---

### 4. Chunking 优化

很多召回错误不是模型问题，而是 chunk 切得不好。

常见问题：

| 问题 | 结果 |
|---|---|
| chunk 太大 | 一个 chunk 混多个主题，容易误召回 |
| chunk 太小 | 语义不完整，召回不到 |
| 没有标题上下文 | chunk 看起来孤立 |
| 表格/代码/步骤被切碎 | 检索质量下降 |
| FAQ 问答被拆开 | 问题和答案分离 |

推荐做法：

```text
标题 + 层级路径 + 正文 chunk
```

例如：

```text
文档标题：SSO 登录故障排查
章节：企业微信 SSO > 登录失败 > 回调地址错误
正文：...
```

这样 embedding 的语义更完整。

---

### 5. Parent-child Retrieval

先检索小 chunk，再返回父文档或父章节。

```text
检索粒度：小 chunk
返回粒度：父章节 / 完整段落
```

优点是：

- 小 chunk 更容易精准命中
- 父 chunk 给 LLM 足够上下文

这比直接把大文档切块扔进向量库效果更稳定。

---

### 6. Rerank + Similarity Threshold

不要只看 topK。

如果 topK 里全是不相关内容，系统也会强行返回。应该设置：

```text
最低相似度阈值
最低 rerank score 阈值
无答案 fallback
```

例如：

```text
if top_score < threshold:
    return "没有找到足够相关的资料"
```

RAG 系统必须允许“不召回”，否则会把垃圾上下文喂给 LLM，导致幻觉。

---

## 3. 项目中“召回了太多无关文档”通常是什么导致的？

严格说，“召回了太多无关文档”更像是 **Precision 低**，不是 Recall 低。

- **Recall 低**：相关文档没被召回。
- **Precision 低**：召回结果里无关文档太多。
- **TopK Recall 低**：相关文档可能在库里，但没进 Top K。
- **MRR / NDCG 低**：相关文档排得太靠后。

你描述的情况大概率是：

> 有相关文档，但被大量无关文档淹没，导致 Top K 结果质量差。

常见原因如下。

---

### 1. 只用了 Dense Retrieval，没有 Hybrid 和 Rerank

这是最常见原因。

单纯 dense search 会把“语义相似”误认为“业务相关”。

例如：

```text
query: 账号冻结
```

可能召回：

- 用户状态说明
- 权限冻结
- 订单冻结
- 设备冻结
- 邮箱账号禁用
- 管理员锁定策略

这些语义上相近，但不一定能回答用户问题。

解决：

```text
BM25 + Dense + Reranker
```

---

### 2. TopK 设置太大

很多项目为了“别漏掉”，把 topK 设置成 20、50，甚至更多，然后全部塞给 LLM。

结果是：

```text
相关文档 2 个
无关文档 18 个
LLM 被噪声干扰
```

建议：

```text
粗召回 Top 50
rerank 后只取 Top 3~8
```

不要把粗召回结果直接给 LLM。

---

### 3. 没有 metadata filter

如果知识库里混了多个产品、平台、版本、语言、模块，而检索时不加过滤，必然召回大量无关内容。

例如用户问：

> “证书过期怎么办？”

如果不加 filter，可能召回：

- APNs 证书
- S/MIME 证书
- TLS 证书
- MDM Push 证书
- 服务器证书
- 客户端证书

解决是给文档建立 metadata，并在检索前识别 query 约束。

---

### 4. Chunk 质量差

这是 RAG 里非常高频的问题。

尤其是以下情况：

```text
一个 chunk 包含多个主题
chunk 没有标题
chunk 太短缺少上下文
FAQ 问题和答案分离
Markdown 表格被切坏
代码块和说明分离
```

解决方向：

```text
按语义结构切分
保留标题层级
添加文档路径
使用 parent-child retrieval
对 FAQ 使用 Q+A 整体入库
```

---

### 5. Embedding 模型不适合业务语料

如果你的语料是中文、技术文档、代码、企业内部术语、产品名、错误码，而 embedding 模型是通用英文模型，效果会明显下降。

需要检查：

| 场景 | 建议 |
|---|---|
| 中文为主 | 使用中文/多语言强模型 |
| 中英混合 | 使用 multilingual embedding |
| 技术文档 | 评估技术语料表现 |
| 代码/API 文档 | 考虑 code-aware embedding |
| 领域术语多 | 做 domain eval，必要时微调 |

常见模型：

- `bge-m3`
- `bge-large-zh`
- `text-embedding-3-large`
- `jina-embeddings-v3`
- `e5-mistral`
- `gte` 系列

---

### 6. Query 和 Document 表达不一致

用户说：

```text
登录不了
```

文档写：

```text
身份认证失败
OAuth 回调异常
SSO assertion validation failed
```

这时 dense 可能能召回一点，但不稳定。

解决：

```text
同义词词表
业务术语归一化
query rewrite
multi-query
FAQ 增强
```

---

### 7. 索引数据本身噪声太多

如果知识库里有大量：

- 过期文档
- 重复文档
- 自动生成的低质量文档
- 日志类内容
- 草稿文档
- 多版本混杂
- 标题党文档
- 无结构长文档

那么召回质量一定差。

RAG 的效果高度依赖数据治理。

建议做：

```text
去重
版本过滤
过期文档降权
低质量文档剔除
按产品/模块建 collection
建立 doc quality score
```

---

### 8. 没有负样本评估，只凭感觉调参

很多 RAG 项目失败的原因是没有 retrieval eval。

应该构造评估集：

```text
query
expected relevant document ids
hard negative document ids
```

然后看：

| 指标 | 含义 |
|---|---|
| Recall@K | 相关文档是否进了 Top K |
| Precision@K | Top K 里有多少是真的相关 |
| MRR | 第一个相关文档排第几 |
| NDCG | 整体排序质量 |
| Hit Rate | 是否命中至少一个相关文档 |

如果你的问题是“召回太多无关文档”，重点看：

```text
Precision@K
NDCG@K
MRR
reranker score distribution
```

---

## 4. 推荐落地方案

如果你们项目当前召回结果噪声多，我建议按这个优先级改：

```text
1. 加 reranker
2. 从纯 dense 改成 hybrid retrieval
3. 限制最终进入 LLM 的 TopN
4. 增加 metadata filter
5. 优化 chunking，保留标题层级
6. 做 query rewrite / multi-query
7. 建 retrieval eval 数据集
8. 清洗知识库和去重
9. 评估或更换 embedding 模型
```

一个比较稳的生产级 RAG retrieval pipeline 是：

```text
User Query
   ↓
Query Understanding
- intent detection
- entity extraction
- metadata filter
- short-query detection
   ↓
Hybrid Retrieval
- BM25 Top 50
- Dense Top 50
   ↓
Candidate Merge
- 去重
- metadata filtering
   ↓
Reranker
- Top 50 → Top 5
   ↓
Threshold Check
- 分数过低则不回答或追问
   ↓
LLM Answer
```

核心结论：

> 短句 query 不要依赖纯 dense retrieval；应该用 **BM25 + Dense 的 Hybrid Retrieval**，再加 **Reranker**。  
> 非短句召回错误通常通过 **query decomposition、multi-query、chunk 优化、metadata filter、rerank、threshold** 解决。  
> “召回了太多无关文档”通常不是单一模型问题，而是 **缺少 rerank、缺少过滤、chunk 质量差、topK 过大、embedding 不适配、知识库噪声高、没有评估集** 共同造成的。

# 进度

![[todo1.png]]