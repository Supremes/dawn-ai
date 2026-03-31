# RAG 检索准确性优化方案

> 创建日期：2026-03-31
> 关联 Issue：待创建（P1）
> 依赖 Action：Action 1（ToolRegistry）、Action 3（RAG as Tool）、Action 7（BeanOutputConverter）

---

## 背景与问题

当前 RAG 实现（`RagService`）存在以下结构性缺陷：

| 问题 | 现状 | 影响 |
|---|---|---|
| 无 Chunking | 整段文本直接存入向量库 | 长文档语义覆盖差，检索不准 |
| 无相似度阈值 | `similaritySearch` 返回任意结果 | 低相关文档混入 context，引入噪音 |
| 无 Query Rewriting | 原始用户查询直接向量化 | 口语/简写查询与存储向量语义错位 |
| RAG 强制预处理 | `ChatService` 每次请求都调 RAG | Bug B1：RAG context 污染 Redis 历史消息 |
| 无 Iterative Retrieval | 单次检索，信息不足无法补充 | 复杂多角度问题召回不完整 |

---

## 目标

1. 提升检索精确率（Precision）：过滤低相关文档
2. 提升检索召回率（Recall）：支持多轮、多角度检索
3. 消除 Bug B1（RAG context 历史污染）
4. 与 Agentic RAG 架构对齐：Agent 自主决定何时检索

---

## 方案：渐进式三阶段交付

### Phase 1 — 检索质量基础层

**目标**：让检索结果"宁缺毋滥"。

#### 1.1 Chunking（`RagService.ingest()`）

使用 Spring AI 内置 `TokenTextSplitter`：

```java
TokenTextSplitter splitter = new TokenTextSplitter(500, 50, 5, 10000, true);
List<Document> chunks = splitter.apply(List.of(new Document(content, metadata)));
vectorStore.add(chunks);
```

参数说明：
- `chunkSize=500` tokens：单块大小，适合中短文档
- `overlap=50` tokens：滑窗重叠，保留块间上下文连续性
- 每个 chunk 继承父文档的 `source` / `category` metadata

#### 1.2 相似度阈值过滤（`RagService.retrieve()`）

```java
SearchRequest request = SearchRequest.builder()
    .query(query)
    .topK(topK * 2)                              // 先取 2x 候选
    .similarityThreshold(similarityThreshold)    // 过滤低相关
    .build();
List<Document> results = vectorStore.similaritySearch(request);
return results.stream().limit(topK).toList();    // 再取 topK
```

配置项（`application.yml`）：
```yaml
app.ai.rag:
  similarity-threshold: 0.7   # 可按数据集调整
  default-top-k: 5
```

#### 1.3 指标补全

| 指标 | 类型 | 说明 |
|---|---|---|
| `ai.rag.retrieval.filtered_count` | Histogram | 每次被阈值过滤掉的文档数 |
| `ai.rag.retrieval.total{result=hit/miss}` | Counter | 已有，miss 语义更精确 |

**交付物**：修改 `RagService`，更新 `application.yml`，单元测试覆盖阈值过滤逻辑。

---

### Phase 2 — Agentic RAG Level 1-2

**目标**：Agent 自主决定何时检索，并对查询语义改写。

#### 2.1 删除 ChatService RAG 预处理（消除 Bug B1）

```java
// 删除 ChatService 中：
if (request.isRagEnabled()) {
    String context = ragService.buildContext(userMessage);
    if (!context.isBlank()) {
        userMessage = context + "\n\nUser question: " + userMessage;
    }
}
// 同步删除 ChatRequest.ragEnabled 字段
```

#### 2.2 新增 `QueryRewriter`

职责：将用户口语查询改写为语义更精准的检索词组。

```java
@Component
public class QueryRewriter {
    record RewriteResult(String rewrittenQuery, String reasoning) {}

    public String rewrite(String originalQuery) {
        BeanOutputConverter<RewriteResult> converter =
            new BeanOutputConverter<>(RewriteResult.class);
        // 独立 LLM 调用，temperature=0.1（低随机性）
        String response = chatClient.prompt()
            .system("将用户问题改写为适合向量检索的关键词短语，保留核心语义。" + converter.getFormat())
            .user(originalQuery)
            .call().content();
        return converter.convert(response).rewrittenQuery();
    }
}
```

#### 2.3 新增 `KnowledgeSearchTool`

```java
@Component
@Description("搜索内部知识库，获取与问题相关的背景信息。当需要查询产品信息、技术文档或领域知识时调用。")
public class KnowledgeSearchTool
    implements Function<KnowledgeSearchTool.Request, KnowledgeSearchTool.Response> {

    record Request(@JsonProperty(required = true) String query) {}
    record Response(String context, int docsFound) {}

    public Response apply(Request req) {
        String rewrittenQuery = queryRewriter.rewrite(req.query());
        List<Document> docs = ragService.retrieve(rewrittenQuery, 5);
        String context = formatContext(docs);
        return new Response(context, docs.size());
    }
}
```

注册到 `ToolRegistry`（依赖 Action 1）后，`AgentOrchestrator` 自动感知，无需手动改 `toolNames()`。

#### 2.4 数据流对比

```
改造前：
  ChatService → ragService.buildContext(userMsg) → 污染后存 Redis
  → AgentOrchestrator（带 RAG context 的 userMessage）

改造后：
  ChatService → AgentOrchestrator（原始 userMessage）
                    └── LLM 推理：需要知识吗？
                    └── knowledgeSearchTool(query)
                          └── QueryRewriter → RagService.retrieve()
                    └── 继续推理 → 最终答案
```

**交付物**：`QueryRewriter.java`、`KnowledgeSearchTool.java`，删除 `ChatService` 预处理逻辑，集成测试。

---

### Phase 3 — Agentic RAG Level 3（Iterative Retrieval）

**目标**：支持多轮检索，复杂问题信息充分后再回答。

#### 3.1 多轮检索（零代码改动）

Spring AI ReAct 循环原生支持同一工具多次调用。LLM 会在每次 `knowledgeSearchTool` 返回后判断"信息是否充分"，不足则以不同查询再次调用。

#### 3.2 System Prompt 引导

在 `TaskPlanner` 生成的计划 system prompt 中注入：
```
若单次检索信息不足，可多次调用 knowledgeSearchTool 从不同角度补充，
直到信息充分再生成最终答案。每次请求最多检索 {maxRagCalls} 次。
```

配置项：
```yaml
app.ai.rag:
  max-calls-per-session: 3   # 独立于 maxSteps，防 RAG 滥用
```

#### 3.3 防重复检索

在 `KnowledgeSearchTool.apply()` 中记录已检索的改写查询，语义相近时跳过，避免 Token 浪费。

状态存储：使用 `ThreadLocal<Set<String>>`（与 `StepCollector` 同等模式），在 `AgentOrchestrator.chat()` 的 finally 块中随 `StepCollector.clear()` 一并清理，确保无跨请求污染。

```java
// 简单实现：对改写后的 query 做 exact match 去重
if (retrievedQueries.get().contains(rewrittenQuery)) {
    return new Response("（已检索过相同内容）", 0);
}
retrievedQueries.get().add(rewrittenQuery);
```

#### 3.4 专项指标

| 指标 | 类型 | 说明 |
|---|---|---|
| `ai.rag.calls_per_session` | Histogram | 每次会话 RAG 调用次数分布 |
| `ai.rag.dedup.skipped` | Counter | 因去重跳过的检索次数 |

**交付物**：更新 `KnowledgeSearchTool`（防重复逻辑）、更新 `TaskPlanner` system prompt、更新 `application.yml`、指标注册。

---

## 完整架构（三阶段完成后）

```
用户问题
    ↓
ChatService（纯路由，无 RAG 预处理）
    ↓
AgentOrchestrator
    ├── TaskPlanner（含 maxRagCalls 引导语）
    └── Spring AI ReAct Loop
          ↓ LLM 推理
          ├── knowledgeSearchTool(query)          ← 按需，可多次
          │     ├── QueryRewriter（LLM 改写，BeanOutputConverter）
          │     ├── RagService.retrieve（Chunked docs + Threshold 过滤）
          │     └── 防重复检索（session 级去重）
          ├── calculatorTool / weatherTool / ...
          └── 最终答案
```

---

## 执行顺序与依赖

```
Phase 1（独立）：RagService Chunking + 阈值过滤
    ↓
Action 1（ToolRegistry）完成后：
Phase 2：删 ChatService 预处理 + QueryRewriter + KnowledgeSearchTool
    ↓
Phase 3：防重复检索 + Prompt 引导 + 指标
```

| Phase | 前置依赖 | 预估工作量 |
|---|---|---|
| Phase 1 | 无 | 1-2 天 |
| Phase 2 | Action 1（ToolRegistry）、Action 7（BeanOutputConverter） | 3-5 天 |
| Phase 3 | Phase 2 | 1-2 天 |

---

## 关键配置汇总

```yaml
app.ai.rag:
  similarity-threshold: 0.7
  default-top-k: 5
  max-calls-per-session: 3
```

---

## 不在本方案范围内

- RAG Evaluation 框架（Ragas / MRR / NDCG）— 有真实数据后再做
- Cross-encoder Reranking — 需要额外模型，Phase 3 后视效果决定
- Hybrid Search（向量 + BM25 关键词）— 现阶段 pgvector 单路足够
- Self-RAG（带自我反思的检索决策）— Agentic RAG Level 4，未来迭代
