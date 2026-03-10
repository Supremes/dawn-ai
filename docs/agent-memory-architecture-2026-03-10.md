# AI Agent 记忆遗忘问题与分层记忆架构

> 📅 Date: 2026-03-10  
> 🏷️ Tags: `AI-Agent` `Memory` `RAG` `Spring-AI` `架构设计`

---

## ① 为什么不能无限存？—— Context Window 的物理上限

### 核心限制：Transformer 的 Self-Attention 复杂度

LLM 的底层是 Transformer 架构，其 Self-Attention 计算复杂度为：

$$O(n^2 \cdot d)$$

其中 `n` = token 数量，`d` = 向量维度。

> **类比后端世界：**
> 这就像一个没有索引的 MySQL 全表扫描。`n` 翻倍，计算量变 **4 倍**，显存占用也是 $O(n^2)$ 级别增长。GPT-4 的 128K context，在推理时显存消耗已是普通请求的数十倍。

| 限制维度 | 具体表现 |
|---|---|
| **显存 (VRAM)** | Attention 矩阵大小 = $n^2$，128K token ≈ 数十 GB 显存 |
| **推理延迟** | token 越多，首 token 延迟（TTFT）线性增长 |
| **费用成本** | 主流 API 按 token 计费，长上下文直接烧钱 |
| **Lost in the Middle** | 研究证明：中间段信息 LLM 注意力权重极低，**长不等于好** |

> ⚠️ **关键认知**：上下文越长 ≠ 效果越好。Stanford 论文《Lost in the Middle》证明，LLM 对 context **头部和尾部**记忆最强，中间段严重衰减——这和人类工作记忆机制高度相似。

---

## ② leftPop 丢弃后，Agent 还认识张三吗？

**直接回答：不认识了。彻底失忆。**

```
Round 1:  [USER: 我叫张三，是VIP用户]  ← 存入 Redis List
Round 2:  [ASSISTANT: 您好张三...]
...
Round 20: 窗口已满，触发 leftPop
Round 21: [USER: 我叫张三] 被弹出 ← 信息永久丢失
...
Round 25: [USER: 帮我查我的订单]
          Agent内存: [Round6~Round25] ← 无张三身份信息
          LLM: "请问您是哪位用户？" ← 灾难性体验
```

> **类比：** 这就像你的 Redis 用了一个 `LPUSH/LTRIM` 的定长队列做 Session，但 **Session 里没存 userId**，只存了聊天流水。用户信息随着 TTL 蒸发，服务端对他一无所知——这在传统后端是绝对不允许的 P0 Bug，但在 Agent 工程里却极其常见。

---

## ③ 架构方案：分层记忆系统（Memory Hierarchy）

> **核心思想类比：** 这和 CPU 缓存架构完全同构。
> - **L1 Cache** → Working Memory（当前对话窗口）
> - **L2 Cache** → Summary Memory（对话摘要压缩）
> - **L3/主存** → Long-term Memory（向量数据库 + 结构化存储）

### 架构总览

```
用户输入
   │
   ▼
┌─────────────────────────────────────────┐
│          Memory Router（记忆路由层）       │
│  ① 写入短期记忆  ② 触发压缩  ③ 检索长期记忆  │
└─────────────────────────────────────────┘
   │              │                │
   ▼              ▼                ▼
Redis List    摘要压缩服务      向量DB (Milvus)
(Working)     (Summarizer)    + MySQL (用户画像)
(20条窗口)    (异步LLM压缩)    (Long-term)
```

---

### Layer 1：Working Memory（短期工作记忆）

```java
// Spring Boot 3 + Java 17 实现
@Service
public class WorkingMemoryService {

    private static final int MAX_WINDOW = 20;
    private static final String KEY_PREFIX = "agent:memory:working:";

    private final RedisTemplate<String, Message> redisTemplate;
    private final MemoryCompressor compressor;

    public void addMessage(String sessionId, Message message) {
        String key = KEY_PREFIX + sessionId;
        redisTemplate.opsForList().rightPush(key, message);

        long size = redisTemplate.opsForList().size(key);
        if (size > MAX_WINDOW) {
            // 异步触发压缩，不阻塞主流程
            compressor.compressAsync(sessionId,
                redisTemplate.opsForList().leftPop(key)); // 弹出前先压缩！
        }
    }

    public List<Message> getWindow(String sessionId) {
        return redisTemplate.opsForList()
            .range(KEY_PREFIX + sessionId, 0, -1);
    }
}
```

---

### Layer 2：Summary Memory（摘要压缩层）—— 解决遗忘的关键

```java
@Service
public class MemoryCompressor {

    private final ChatClient chatClient; // Spring AI
    private final VectorStore vectorStore; // Milvus/PgVector

    @Async("memoryCompressExecutor") // 异步线程池，不阻塞对话
    public void compressAsync(String sessionId, Message poppedMessage) {
        // 1. 提取结构化实体（用户身份、关键意图）
        String extractPrompt = """
            从以下对话中提取关键实体信息，JSON格式输出：
            {userName, userLevel, keyIntents, importantFacts}
            对话内容：%s
            """.formatted(poppedMessage.content());

        String extracted = chatClient.prompt(extractPrompt).call().content();

        // 2. 向量化存入长期记忆
        Document doc = new Document(extracted,
            Map.of("sessionId", sessionId, "type", "summary"));
        vectorStore.add(List.of(doc));

        // 3. 结构化字段存 MySQL（用户画像持久化）
        userProfileRepository.upsert(sessionId, extracted);
    }
}
```

---

### Layer 3：Long-term Memory 检索（RAG 注入）

```java
@Service
public class MemoryAugmentedAgentService {

    private final WorkingMemoryService workingMemory;
    private final VectorStore vectorStore;
    private final UserProfileRepository profileRepo;
    private final MeterRegistry meterRegistry; // Prometheus

    public String chat(String sessionId, String userInput) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // 1. 从长期记忆检索相关上下文（类比 MySQL 索引查询）
            List<Document> longTermContext = vectorStore.similaritySearch(
                SearchRequest.query(userInput)
                    .withFilterExpression("sessionId == '%s'".formatted(sessionId))
                    .withTopK(3)
                    .withSimilarityThreshold(0.75)
            );

            // 2. 加载用户画像（结构化硬记忆，永不丢失）
            UserProfile profile = profileRepo.findBySessionId(sessionId);

            // 3. 组装增强 Prompt
            String systemPrompt = buildSystemPrompt(profile, longTermContext);

            // 4. 短期记忆 + 增强上下文 → LLM
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.addAll(workingMemory.getWindow(sessionId));
            messages.add(new UserMessage(userInput));

            return chatClient.prompt(new Prompt(messages)).call().content();

        } finally {
            sample.stop(meterRegistry.timer("agent.memory.retrieval",
                "sessionId", sessionId));
        }
    }

    private String buildSystemPrompt(UserProfile profile, List<Document> context) {
        return """
            ## 用户身份（硬记忆，优先级最高）
            姓名：%s，等级：%s

            ## 历史关键记忆（向量检索）
            %s

            请基于以上背景信息回答用户问题。
            """.formatted(
                profile.name(),
                profile.level(),
                context.stream().map(Document::getContent).collect(joining("\n"))
            );
    }
}
```

---

### 完整数据流

```
用户: "帮我查我的订单"  (Round 25)
         │
         ▼
  ① 向量检索: "张三 VIP用户" (相似度0.92) ← 从Milvus召回
  ② 用户画像: {name:"张三", level:"VIP"}  ← 从MySQL读取
  ③ 工作窗口: [Round6 ~ Round24]          ← 从Redis读取
         │
         ▼
  System Prompt = ① + ② 注入
  Messages = System + ③ + 当前输入
         │
         ▼
  LLM: "张三您好！正在为您查询VIP订单..." ✅
```

---

### 可观测性埋点（工程化必备）

```yaml
# Prometheus metrics 关注点
agent_memory_retrieval_seconds    # 记忆检索耗时
agent_memory_hit_total{type}      # 短期/长期命中率
agent_memory_compress_queue_size  # 压缩队列积压
agent_vector_search_similarity    # 向量检索平均相似度
```

---

## 总结：记忆分层对照表

| 层次 | 类比 | 存储 | 容量 | 特点 |
|---|---|---|---|---|
| Working Memory | CPU L1 Cache | Redis List | 20 条 | 最快，会遗忘 |
| Summary Memory | CPU L2 Cache | VectorDB (Milvus) | 无限 | 语义压缩，可检索 |
| User Profile | 磁盘持久化 | MySQL | 无限 | 结构化，永不丢失 |

---

## 💡 思考题（留存）

> 在 `compressAsync` 触发向量化写入时，如果 LLM 压缩服务本身超时或幻觉（抽取了错误的用户名），你的降级策略是什么？
> 是直接丢弃这条记忆，还是原文兜底存储？这个选择会带来哪些不同的工程权衡？
