# RAG Phase 2 & 3 — Agentic RAG Level 1-3 设计文档

> 创建日期：2026-03-31
> 关联 Issue：[#16](https://github.com/Supremes/dawn-ai/issues/16)（P1）
> 前置依赖：Action #1（ToolRegistry ✅）、Action #7（BeanOutputConverter ✅）、Phase 1（Chunking + Threshold ✅）
> 分支策略：Phase 2 和 Phase 3 各一个独立 PR

---

## 背景

Phase 1 完成了检索质量基础（Chunking + 相似度阈值）。Phase 2 & 3 解决剩余的两个核心问题：

| 问题 | 影响 |
|---|---|
| RAG 在 ChatService 强制预处理（Bug B1） | RAG context 被拼入 userMessage 存入 Redis，污染对话历史 |
| 口语查询与存储向量语义错位 | 召回率低，找不到相关文档 |
| 单次检索信息不足时无法补充 | 复杂多角度问题召回不完整 |

---

## Phase 2 — Agentic RAG Level 1-2

### 目标

- 让 Agent 自主决定何时检索（Level 1）
- 检索前自动改写查询提升召回率（Level 2）
- 消除 Bug B1（RAG context 历史污染）

### 新增组件

#### 2.1 `QueryRewriter`（`com.dawn.ai.service`）

职责：将用户口语查询改写为适合向量检索的语义短语。

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriter {

    private final ChatClient chatClient;

    @Setter
    @Value("${app.ai.rag.query-rewrite-enabled:true}")
    private boolean queryRewriteEnabled;

    record RewriteResult(String rewrittenQuery) {}

    public String rewrite(String originalQuery) {
        if (!queryRewriteEnabled) {
            return originalQuery;
        }
        BeanOutputConverter<RewriteResult> converter =
            new BeanOutputConverter<>(RewriteResult.class);

        String response = chatClient.prompt()
            .system("将用户问题改写为适合向量检索的关键词短语，保留核心语义，去除口语助词。"
                    + converter.getFormat())
            .user(originalQuery)
            .options(OpenAiChatOptions.builder().temperature(0.1).build())
            .call()
            .content();

        return converter.convert(response).rewrittenQuery();
    }
}
```

配置项：`app.ai.rag.query-rewrite-enabled: true`

**改写示例：**
```
输入:  "Dawn AI 贵不贵？月费大概多少啊"
输出:  "Dawn AI 定价 月费 价格 收费标准"
```

#### 2.2 `KnowledgeSearchTool`（`com.dawn.ai.agent.tools`）

放在 `tools` 包，自动被 `ToolRegistry` 发现，无需改 `AgentOrchestrator` 和 `TaskPlanner`。

```java
@Slf4j
@Component
@Description("搜索内部知识库，获取与问题相关的背景信息。需要查询产品信息、技术文档或领域知识时调用。")
public class KnowledgeSearchTool implements Function<KnowledgeSearchTool.Request, KnowledgeSearchTool.Response> {

    record Request(@JsonProperty(required = true) String query) {}
    record Response(String context, int docsFound) {}

    // 通过 @RequiredArgsConstructor 注入（与项目其他组件保持一致）
    private final QueryRewriter queryRewriter;
    private final RagService ragService;
    private final MeterRegistry meterRegistry;

    @Value("${app.ai.rag.default-top-k:5}")
    private int defaultTopK;

    private Counter dedupCounter;

    @PostConstruct
    void initMetrics() {
        dedupCounter = Counter.builder("ai.rag.dedup.skipped")
                .description("RAG queries skipped due to deduplication")
                .register(meterRegistry);
    }

    public Response apply(Request req) {
        String rewrittenQuery = queryRewriter.rewrite(req.query());
        List<Document> docs = ragService.retrieve(rewrittenQuery, defaultTopK);
        String context = formatContext(docs);
        log.info("[KnowledgeSearchTool] query='{}' → rewritten='{}', docsFound={}",
                req.query(), rewrittenQuery, docs.size());
        return new Response(context, docs.size());
    }

    private String formatContext(List<Document> docs) {
        if (docs.isEmpty()) return "未找到相关知识库内容。";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append(String.format("[%d] %s\n", i + 1, docs.get(i).getText()));
        }
        return sb.toString();
    }
}
```

#### 2.3 删除 ChatService RAG 预处理（消除 Bug B1）

```java
// 删除 ChatService.chat() 中的以下代码：
if (request.isRagEnabled()) {
    String context = ragService.buildContext(userMessage);
    if (!context.isBlank()) {
        userMessage = context + "\n\nUser question: " + userMessage;
    }
}

// 同步删除：ChatRequest.ragEnabled 字段及其 getter/setter
```

### 数据流（改造后）

```
用户: "Dawn AI 月费多少？"

ChatService → AgentOrchestrator（原始 userMessage，无 RAG 预处理）
  └── TaskPlanner: 生成计划 [knowledgeSearchTool, finish]
  └── ReAct Loop:
        LLM Reason: 需要查询产品定价信息
        LLM Action: knowledgeSearchTool("Dawn AI 月费多少？")
          → QueryRewriter: "Dawn AI 定价 月费 价格"
          → RagService.retrieve("Dawn AI 定价 月费 价格", topK=5)
          → Response(context="[1]月费¥99...", docsFound=1)
        LLM Observe: 已获得定价信息
        LLM Answer: "Dawn AI 月费为 ¥99"
```

### 配置项（新增）

```yaml
app:
  ai:
    rag:
      query-rewrite-enabled: true   # QueryRewriter 开关，false 时原样传入
```

---

## Phase 3 — Agentic RAG Level 3（Iterative Retrieval）

### 目标

- 支持多轮检索：LLM 自主判断信息是否充分，不足则换角度再次调用 `knowledgeSearchTool`
- 防止重复检索浪费 Token
- 通过 Prompt 引导 LLM 合理使用多轮检索能力

### 3.1 StepCollector 扩展

新增 `RETRIEVED_QUERIES` ThreadLocal，生命周期与现有 STEPS/COUNTER/MAX_STEPS 完全一致。

```java
// 新增字段
private static final ThreadLocal<Set<String>> RETRIEVED_QUERIES =
        ThreadLocal.withInitial(HashSet::new);

// init() 追加
RETRIEVED_QUERIES.get().clear();

// 新增静态方法
public static boolean isQueryRetrieved(String query) {
    return RETRIEVED_QUERIES.get().contains(query);
}
public static void markQueryRetrieved(String query) {
    RETRIEVED_QUERIES.get().add(query);
}

// clear() 追加
RETRIEVED_QUERIES.remove();
```

### 3.2 KnowledgeSearchTool 防重复逻辑

在 Phase 2 实现基础上，`apply()` 开头加去重判断：

```java
public Response apply(Request req) {
    String rewrittenQuery = queryRewriter.rewrite(req.query());

    if (StepCollector.isQueryRetrieved(rewrittenQuery)) {
        dedupCounter.increment();
        log.info("[KnowledgeSearchTool] Skipping duplicate query: {}", rewrittenQuery);
        return new Response("（已检索过相同内容，请换个角度或直接生成回答）", 0);
    }
    StepCollector.markQueryRetrieved(rewrittenQuery);

    List<Document> docs = ragService.retrieve(rewrittenQuery, defaultTopK);
    // ... 其余逻辑不变
}
```

### 3.3 TaskPlanner Prompt 注入

在 `buildPlanPrompt()` 的业务约束部分追加：

```
- 若单次检索信息不足，可多次调用 knowledgeSearchTool 从不同角度补充，
  直到信息充分再生成最终答案。每次请求最多检索 {maxRagCalls} 次。
```

`maxRagCalls` 从 `application.yml` 注入，与 `maxSteps` 独立：

```yaml
app:
  ai:
    rag:
      max-calls-per-session: 3   # 每次请求最多 RAG 调用次数，独立于 maxSteps
```

### 3.4 指标

| 指标 | 类型 | 注册位置 | 语义 |
|---|---|---|---|
| `ai.rag.calls_per_session` | Histogram | `AgentOrchestrator` | 每次会话中 `knowledgeSearchTool` 调用次数 |
| `ai.rag.dedup.skipped` | Counter | `KnowledgeSearchTool` | 因去重跳过的检索次数 |

`ai.rag.calls_per_session` 计算方式：`StepCollector.collect()` 后，过滤 action == "knowledgeSearchTool" 的步骤数量，在 `AgentOrchestrator.doChat()` 末尾 `record()`。

---

## 完整架构（Phase 1-3 完成后）

```
用户问题
    ↓
ChatService（纯路由，无 RAG 预处理）
    ↓
AgentOrchestrator
    ├── TaskPlanner（含 maxRagCalls 引导语）
    └── Spring AI ReAct Loop
          ↓ LLM 推理
          ├── knowledgeSearchTool(query)        ← 按需，可多次（最多 maxRagCalls）
          │     ├── QueryRewriter（BeanOutputConverter，temperature=0.1）
          │     ├── StepCollector.isQueryRetrieved() 防重复
          │     └── RagService.retrieve（Chunked + Threshold）
          ├── calculatorTool / weatherTool / ...
          └── 最终答案
```

---

## 文件变更汇总

### Phase 2 PR

| 操作 | 文件 |
|---|---|
| 新增 | `src/main/java/com/dawn/ai/service/QueryRewriter.java` |
| 新增 | `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java` |
| 修改 | `src/main/java/com/dawn/ai/service/ChatService.java`（删除 RAG 预处理） |
| 修改 | `src/main/java/com/dawn/ai/dto/ChatRequest.java`（删除 ragEnabled） |
| 修改 | `src/main/resources/application.yml`（新增 query-rewrite-enabled） |
| 新增 | `src/test/java/com/dawn/ai/service/QueryRewriterTest.java` |
| 新增 | `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java` |

### Phase 3 PR

| 操作 | 文件 |
|---|---|
| 修改 | `src/main/java/com/dawn/ai/agent/StepCollector.java`（RETRIEVED_QUERIES） |
| 修改 | `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java`（防重复） |
| 修改 | `src/main/java/com/dawn/ai/agent/plan/TaskPlanner.java`（maxRagCalls prompt） |
| 修改 | `src/main/java/com/dawn/ai/agent/AgentOrchestrator.java`（RAG 指标） |
| 修改 | `src/main/resources/application.yml`（max-calls-per-session） |
| 修改 | `src/test/java/com/dawn/ai/agent/StepCollectorTest.java` |
| 修改 | `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java`（防重复测试） |

---

## 不在范围内

- RAG Evaluation 框架（有真实数据后再做）
- Cross-encoder Reranking
- Hybrid Search（向量 + BM25）
- Self-RAG（Agentic RAG Level 4）
