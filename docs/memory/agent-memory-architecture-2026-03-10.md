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
> - **L3/主存 (legacy)** → Long-term Memory（历史上用于指代可回溯/持久事件与结构化事实的 umbrella；在 canonical taxonomy 中应映射为 Episodic / Semantic-Hard，Summary 为独立的 Summary Memory 层）

### 架构总览

```
用户输入
   │
   ▼
┌─────────────────────────────────────────┐
│          Memory Router（记忆路由层）       │
│  ① 写入短期记忆  ② 触发压缩  ③ 检索长期记忆  │
└─────────────────────────────────────────┘
   │         │             │
   ▼         ▼             ▼
Redis List  摘要压缩服务    Long-term Memory (legacy umbrella)
(Working)   (Summarizer)    ┌───────────────┬───────────────┐
(20条窗口)  (向量文档/embedding)│ Episodic      │ Semantic/Hard│
                                 │ (事件回溯/原文)│ (用户画像/配置)│
                                 └───────────────┴───────────────┘

注：Summary Memory（摘要/向量工件）为独立的层（L2），用于相似度检索与提示增强。上方的 "Long-term Memory"（legacy）在新 taxonomy 中更精确地映射为 Episodic 与 Semantic/Hard 两类。实现时请将各类职责分别映射到合适的存储与检索策略。
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
            // 安全模式：不要直接 pop 然后异步处理 —— 这会在压缩失败或进程崩溃时丢失数据。
            // 推荐做法：原子地把最旧条目从 Working Window 移动到一个持久的 pending 队列，
            // 然后再异步处理 pending 队列中的条目，处理成功后再从 pending 中 ACK/删除。
            // 下面示例演示该模式（伪实现，保留可读性）：

            String pendingKey = key + ":pending"; // durable pending queue

            // 原子移动：Redis LMOVE/LMOVE-like 操作（LEFT -> RIGHT），保证在一条命令内完成移除与写入，
            // 消除 pop-then-async 的 crash 窗口。
            // 具体 Java 客户端实现可用 RedisTemplate.execute(RedisCallback) 或 Lettuce 的 LMOVE 接口。
            // 伪代码示例：
            // Message moved = redisTemplate.execute(conn -> conn.lMove(key, pendingKey, "LEFT", "RIGHT"));

            // 为可读性，这里假定 moved 已被原子写入 pending 并返回（真实实现需按客户端 API 序列化/反序列化）
            Message moved = /* atomicMoveToPending(key, pendingKey) */ null;

            if (moved != null) {
                // 仅传 sessionId 给压缩器；压缩器不会用简单的 peek->process->ack。
                // 推荐模式：在处理前对 pending 条目执行 claim/lease（短 TTL）或使用稳定的文档 ID 以支持幂等写入。
                // 处理成功且向量/元数据持久化后，再 ACK/删除该 pending 条目。
                compressor.compressAsync(sessionId);
            } else {
                // 若移动失败，可记录警告并在后台重试移动或触发告警。
                // log.warn("Failed to move message to pending queue for session {}", sessionId);
            }
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

> 注意：下列示例为 summary‑oriented 样例，展示如何把被弹出的短期片段压缩并作为 Summary Memory 写入向量仓。若需要保留可回溯的原始事件（Episodic Memory），应单独以 sessionId/ts 为键存入 episodic store（示例在下文说明）。

```java
@Service
public class MemoryCompressor {

    private final ChatClient chatClient; // Spring AI
    private final VectorStore vectorStore; // Milvus/PgVector

    @Async("memoryCompressExecutor") // 异步线程池，不阻塞对话
    public void compressAsync(String sessionId) {
        // 安全处理模式：compressAsync 不直接接收已 pop 的 message。
        // 推荐两种保障手段（至少采用其一）以避免重复处理或丢失：
        //  1) Claim/lease: 在处理前对 pending 条目进行原子 claim（把条目标记为正在处理中并附带短 TTL），
        //     处理完成后再释放/ACK；若处理超时，lease 到期后条目可被其他 worker 重新 claim。
        //  2) 幂等写入：为待写入的 Summary 生成稳定的文档 ID（例如 sessionId + timestamp + hash(content)），
        //     并在向量仓/元数据仓上执行 upsert/insert-if-not-exists，从而允许 at-least-once 语义安全地重复写入。
        //
        // 在工程实现中，建议同时结合上述两者：use claim/lease to avoid concurrent processing,
        // and use stable IDs / upserts so retries won't create duplicates.
        //
        String pendingKey = "agent:memory:working:" + sessionId + ":pending";

        // 1. Claim 一个待处理条目（原子操作），并把 claimId / leaseTTL 与条目关联；
        //    如果没有可 claim 的条目就返回。
        //    示例（伪代码）：pendingMessage = pendingStore.claim(pendingKey, workerId, leaseSeconds)
        Message pendingMessage = /* pendingStore.claim(pendingKey, workerId, leaseSeconds) */ null;
        if (pendingMessage == null) {
            return; // nothing to do
        }

        // 2. 提取结构化实体（用户身份、关键意图）并返回必要的质量指标（例如 importance）
        String extractPrompt = """
            从以下对话中提取关键实体信息，JSON格式输出：
            {userId, userName, userLevel, keyIntents, importantFacts, importance}
            对话内容：%s
            """.formatted(pendingMessage.content());

        String extractedJson = chatClient.prompt(extractPrompt).call().content();

        // 3. 组合 richer metadata 写入 Summary Memory（向量文档）
        String userId = pendingMessage.getUserId();
        String ts = pendingMessage.getTimestamp().toString();
        double importance = parseImportanceFrom(extractedJson).orElse(0.5);
        Document doc = new Document(extractedJson,
            Map.of(
                "userId", userId,
                "type", "summary",
                "sessionId", sessionId,
                "timestamp", ts,
                "importance", String.valueOf(importance),
                "source", "memory_compressor_v1",
                "summaryOnly", "true"
            )
        );

        // 4. 写入向量仓（Summary Memory）—— 可被检索用于增强提示
        vectorStore.add(List.of(doc));

        // 5. ACK-on-success：仅当向量写入与必要的元数据写入成功后，才从 pending 中 ACK/删除或释放 claim。
        //    失败处理原则：
        //      - 对可重试错误尝试有限次重试（带退避）；
        //      - 对长期失败的条目移入 dead-letter 队列并告警；
        //      - 在任何失败路径中，保证若写入未成功，pending 条目仍可被再次 claim 与处理（lease 超时或手动重试）。
        //    重要：如果使用 claim/lease，请显式释放或 ACK；若使用幂等 upsert，重复执行写入也不会产生重复文档。
        // 示例伪代码：pendingStore.ackClaim(pendingKey, claimId) 或 pendingStore.moveToDeadLetter(...)


        // 6. 如果需要保留原始会话片段做审计/回溯（Episodic Memory），
        //    应单独写入 Episodic Store（按 sessionId & timestamp 索引）。

        // 注：绝对禁止在此处直接覆盖 userProfileRepository（Semantic/Hard Memory）。
        //       任何对 user profile 的最终修改必须经过 ReflectionWorker（反思/验证）并记录 provenance。
    }
}
```

以上示例明确把压缩器限定为“摘要向量工件”的生产者（Summary Memory），并把任何结构化提取仅作为候选（pending）保存。硬记忆（Semantic/Hard Memory）的写入须在反思/验证（Reflection）阶段执行，以避免把未验证的抽取直接固化为长期事实。

---

### Layer 3：Long-term Memory 检索（注意：本处“Long-term Memory”为 legacy umbrella，见下方规范映射）

> 说明：自 2026-04-27 起，内存 taxonomy 将“Long-term Memory”拆分为更精确的层：
> - Summary Memory（摘要/向量工件）
> - Episodic Memory（原始或可回溯的事件记录 / episodic footprints）
> - Semantic / Hard Memory（用户画像、配置、知识库注入）
>
> 本节保留“Long-term Memory”叙述以便历史连贯，但在实现或设计时应按 canonical taxonomy 映射使用。RAG（知识检索）为相邻但独立的子系统。

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
            // 1.1 从 Summary/Episodic stores 检索与当前输入相关的上下文
            // 注意：对语义/硬记忆（用户画像、偏好等）应按 userId 检索；SessionId 仅用于 episodic / session-scoped 回溯。
            List<Document> longTermContext = vectorStore.similaritySearch(
                SearchRequest.query(userInput)
                    .withFilterExpression("userId == '%s'".formatted(getUserIdForSession(sessionId)))
                    .withTopK(3)
                    .withSimilarityThreshold(0.75)
            );

            // 2. 加载用户画像（结构化硬记忆，按 userId 查询，永不丢失）
            UserProfile profile = profileRepo.findByUserId(getUserIdForSession(sessionId));

            // Helper: 将 sessionId 映射为 userId（示例：来自会话表或 JWT）
            // 该映射清晰地把 session-scoped 信息与 user-level hard memory 分离
        


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

> 注：文中早期使用的“Long-term Memory”为 legacy umbrella；canonical taxonomy 请参考顶部链接（memory-taxonomy-2026-04-27.md）。下表为更精确的映射。

| 层次 | 类比 | 存储 | 容量 | 特点 |
|---|---|---|---|---|
| Working Memory | CPU L1 Cache | Redis List | 20 条 | 最快，会遗忘 |
| Summary Memory | CPU L2 Cache | VectorDB (Milvus/PgVector) | 无穷（向量工件） | 语义压缩，适合相似度检索 |
| Episodic Memory | 事件日志 / 冷存 | Object Store / Append-only Logs | 按事件增长 | 保留原始回溯记录，支持审计/还原 |
| User Profile (Semantic/Hard Memory) | 磁盘持久化 | MySQL / RDB | 无限 | 结构化，永不丢失，注入 system prompt |

---

## 边界说明

- 注意：文中出现的“Long-term Memory”一词为历史上的 umbrella 说法，不是当前 canonical 层名。当前应使用 taxonomy 中的 Summary Memory、Episodic Memory、Semantic/Hard Memory 三类，并据此设计存储与检索策略（参见文末的 canonical 链接）。
- 用户画像属于 hard memory / semantic memory，优先进入 system prompt
- 对话摘要属于 summary memory，不等于原始会话历史
- 企业知识库 / FAQ / 文档检索属于 RAG knowledge，不等于用户长期记忆；RAG 与 Summary/Episodic/Hard memory 可并行或互为补充

前置阅读（canonical）：
- [memory-taxonomy-2026-04-27.md](memory-taxonomy-2026-04-27.md)
- [memory-lifecycle-2026-04-27.md](memory-lifecycle-2026-04-27.md)

---

## 💡 思考题（留存）

> 在 `compressAsync` 触发向量化写入时，如果 LLM 压缩服务本身超时或幻觉（抽取了错误的用户名），你的降级策略是什么？
> 是直接丢弃这条记忆，还是原文兜底存储？这个选择会带来哪些不同的工程权衡？
