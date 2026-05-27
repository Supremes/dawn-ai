---
updated: 2026-05-11 00:10
---
# Tech
## Tech points

  - 3 层记忆体系：Working(Redis) → Summary(pgvector) → Long-term(向量反思) — 跨会话持久记忆
  - ReAct Agent + 自动规划：Tool 自动发现、AOP step 追踪、pre-execution 任务规划
  - 高级 RAG 管道：混合检索(dense+sparse) + Query Rewriting + RRF + 两阶段重排序
  - 用户画像注入：User profile 自动注入 system prompt，实现个性化
  - 完整可观测：Prometheus + Grafana，token 成本追踪

## Agent 范式

项目要点：

- ReAct：边思考边执行，项目利用了SpringAI实现的ReAct方案，非自实现的
- Plan and solve：自实现，依据用户输入+LLM call，编排后续的流程，避免长任务跑偏

学习要点：
- ReAct 适合用在局部决策场景，而不是整个系统。**大多数场景，整体流程是确定的**，适合用 workflow 来保证稳定性。针对某些局部节点（如果需要根据当前上下文动态决定是否调用工具、调用哪个工具、是否进行多轮推理，这时候可以引入 ReAct 来增强灵活性），使用 ReAct 来处理不确定性，保证稳定性和灵活性之间取得平衡。

## Spring 

### [SSE] - ThreadLocal 跨线程传播模式

- 详细参考:  [ThreadLocal跨线程传播模式详解 ](#ThreadLocal跨线程传播模式详解 )
> [!NOTE]
> 
> - Servlet/任意线程 → Reactor 调度器线程: 通过 Reactor Context + Micrometer Hook来处理
> - 任意线程 -> 自定义 ExecutorService：通过使用装饰器 wrap 或手动快照来解决
> 

SSE 模型下，易出现多线程的共享问题，这里处理多线程 threadlocal 数据共享问题：
- Reactor 模型：通过项目中自定义 ApplicationRunner bean，保持时序，实现跨线程的访问。覆盖的是 `Servlet/任意线程 → Reactor 调度器线程` 这个场景。
`ApplicationRunner` 的唯一目的是**时序控制**：
```
所有 Bean 初始化完成
所有 WebClient / RestClient 基础设施就绪
↓
ApplicationRunner.run()
    registerThreadLocalAccessor(...)   ← 注册哪些 ThreadLocal
    Hooks.enableAutomaticContextPropagation()  ← 开启 Reactor 全局 Hook
↓
第一个真实业务请求进来
```

在这个窗口注册，既能覆盖所有后续的 Reactor pipeline，又能享受完整的 Spring 上下文（条件判断、日志、依赖注入）。

### [SSE] - Spring WebMVC(阻塞 io 模型) + SseEmitter

- 详细参考：[SseEmitter与Spring WebFlux的核心区别](#SseEmitter与SpringWebFlux的核心区别)

项目使用 Spring webmvc 框架，属于阻塞 io 模型，基于 servlet api。对于基于非阻塞模型的 Spring Webflux 模型，有几点不同。

| 维度          | SseEmitter (Spring MVC)      | Spring WebFlux (Flux SSE)       |
| ----------- | ---------------------------- | ------------------------------- |
| **并发模型**    | 阻塞/异步线程池，每个长连接占用资源较多         | 非阻塞 Reactive，背压（backpressure）支持 |
| **资源消耗**    | 高并发时线程数容易成为瓶颈                | 极低线程数（几十个线程可支撑数万连接）             |
| **编程风格**    | 命令式（Imperative），易理解          | 函数式+响应式，学习曲线较陡                  |
| **错误/超时处理** | 需手动管理 complete、timeout、error | Reactor 自动处理，生命周期更优雅            |
| **性能**      | 适合中等并发                       | 极高吞吐量、高并发场景首选                   |
| **集成难度**    | 简单，直接用注解控制器                  | 需要理解 Mono/Flux，响应式数据源更佳         |
| **适用场景**    | 已有 MVC 项目、简单实时推送、中低并发        | 新项目、高并发微服务、流式数据处理               |

# 应用方向

个人学习追踪助手：上传技术书/笔记/论文等   → 问问题 → AI 追踪"你学了什么、还不会什么"

能把 3 层记忆都激活的场景：
  - Working Memory：当前对话上下文
  - Summary Memory：上周问过什么、第一次没理解什么
  - Long-term Reflection：AI 自动归纳"你在系统设计上还有薄弱点"

  没有这套记忆体系，AI 每次对话都失忆——这个痛点在 Demo 里说得出口。

  RAG 负责知识库检索，ReAct 负责分步拆解复杂概念，User Profile 存学习进度，Streaming
  让解题过程实时可见。所有技术都有合理动机，不是堆砌。

### 方案

Topic 作纯 Metadata 标签（最轻）, 不新建 Topic 实体，仅在现有 RAG 的 metadata 里加 topicId 字段。KnowledgeSearchTool 带 topicId 过滤即可。
Gap analysis 完全依赖 LLM 推理："在这个 topic 的文档里搜索 X，没结果就是盲点"。

# Reference

### 应用方向

三个推荐方向

  方向 A：个人学习追踪助手（最推荐）

  用例：用户上传论文/技术书/笔记 → 问问题 → AI 追踪"你学了什么、还不会什么"

  为什么能秀肌肉：这是唯一一个能把 3 层记忆都激活 的场景：
  - Working Memory：当前对话上下文
  - Summary Memory：上周问过什么、第一次没理解什么
  - Long-term Reflection：AI 自动归纳"你在系统设计上还有薄弱点"

  没有这套记忆体系，AI 每次对话都失忆——这个痛点在 Demo 里说得出口。

  RAG 负责知识库检索，ReAct 负责分步拆解复杂概念，User Profile 存学习进度，Streaming
  让解题过程实时可见。所有技术都有合理动机，不是堆砌。

  弱点：数据太私人，公开 Demo 需要准备固定的示例素材库。

---
  方向 B：AI Code Review 顾问

  用例：提交代码 / 贴架构描述 → AI 检索最佳实践 → ReAct 多步分析 → 生成审查报告

  为什么能秀肌肉：
  - RAG 检索最佳实践文档（Design Patterns、SOLID、System Design 资料），体现混合检索价值
  - ReAct step tracing 对外可见——面试官能看到"AI 是怎么一步步分析的"
  - Memory 记住该用户历史上反复犯的错误，下次自动提醒
  - Streaming 让审查过程实时流出，体验感强

  弱点：Tools 目前只有 Weather/Calculator/RAG 三个，需要额外开发"代码解析 Tool"，工作量会增加。

---
  方向 C：智能技术客服 + 私有知识库

  用例：企业内部问答，员工上传产品手册/SOP/FAQ，AI 回答并记住每个用户的历史问题

  为什么能秀肌肉：这是最"企业落地"的故事，面向 B 端招聘很加分。RAG 管道、用户画像、记忆都能说清楚价值。

  弱点：方向太常见，几乎每个 RAG Demo 都是这个，很难差异化。记忆体系的价值不如方向 A 那么突出。

---
  我的建议

  首选方向 A（学习追踪助手），理由：

  1. 3 层记忆是这个项目真正的护城河，其他方向没能充分利用
  2. 有具体的失败场景对比：普通 ChatGPT 失忆 vs. 这套系统记住你的学习轨迹
  3. 用例具体、Demo 好讲——"我学 Spring 3 个月了，它还记得我第一周问过什么问题"
  4. 技术选型每一个都有动机，不会被问"为什么要这么复杂"

  想进一步讨论哪个方向，或者直接选定方向 A 开始设计？

三种实现方案

  方案一：Topic 作纯 Metadata 标签（最轻）

  不新建 Topic 实体，仅在现有 RAG 的 metadata 里加 topicId 字段。KnowledgeSearchTool 带 topicId 过滤即可。

  Gap analysis 完全依赖 LLM 推理："在这个 topic 的文档里搜索 X，没结果就是盲点"。

  优点：改动最小，一两天能做完。
  弱点：没有新的 Agent 工具，ReAct 步骤单薄，面试官看不到工具链的设计能力。

---
  方案二：Topic 实体 + 3 个专用 Tool（推荐）

  新增 Topic 表，加 3 个 Agent Tool：

  - ConceptIndexTool：文档入库时自动抽取关键概念，存入 Topic 的概念索引
  - GapAnalysisTool：Agent 拿用户的研究意图 vs. 概念索引做对比，输出"已覆盖 / 未覆盖"列表
  - ProgressReportTool：读取该 Topic 的 Long-term Memory + 概念索引，生成学习蒸馏报告

  ReAct 的完整推理链会是：search → gap_analysis → suggest_missing_areas，步骤可见、逻辑清晰。

  优点：把现有所有技术栈都激活，工具链设计有层次，最能体现 Agent 架构能力。
  弱点：需要新增概念索引的数据结构，工程量中等。

### ThreadLocal跨线程传播模式详解 
#### 问题本质

`ThreadLocal` 是线程隔离的，这既是它的优点（天然线程安全），也是它的限制——当任务从线程 A 提交到线程 B 执行时，B 上读不到 A 设置的值。

```
Thread A: ThreadLocal.set("value")
    └── executor.submit(task)
            └── Thread B: ThreadLocal.get() → null  ❌
```

在异步/响应式架构中，一次业务请求可能跨越：
- Servlet 线程 → 自定义线程池
- Servlet 线程 → Reactor 调度器（`boundedElastic` / `parallel`）
- Reactor operator → operator（`publishOn` 切换调度器）

---

#### 三种传播手段

##### 1. 手动快照传递（适合简单异步场景）

在**提交侧**捕获值，用闭包包裹任务：

```java
String captured = threadLocal.get();   // 提交线程抓快照
executor.submit(() -> {
    String prev = threadLocal.get();
    threadLocal.set(captured);         // 工作线程恢复
    try { task.run(); }
    finally {
        if (prev == null) threadLocal.remove();
        else threadLocal.set(prev);    // 恢复原值，防止线程池线程污染
    }
});
```

关键点：`finally` 里必须恢复而不是直接 `remove()`，因为线程池线程可能本身就有值。

##### 2. ExecutorService 装饰器（适合线程池统一管理） - 

避免每次提交都手动 wrap，用装饰器模式统一拦截：

```java
class ContextAwareExecutor implements ExecutorService {
    public Future<?> submit(Runnable task) {
        return delegate.submit(Context.wrap(task));  // 统一入口
    }
    // invokeAll / invokeAny 同样处理...
}
```

好处：调用方无感知，只需将线程池替换为装饰版。

##### 3. Micrometer Context Propagation（适合 Reactor 响应式管道）

Reactor 的调度器切换无法用上述方式拦截。Micrometer 提供了标准 SPI：

```java
// 1. 声明哪个 ThreadLocal 需要传播
class MyAccessor implements ThreadLocalAccessor<String> {
    public String getValue() { return threadLocal.get(); }
    public void setValue(String v) { threadLocal.set(v); }
    public void setValue() { threadLocal.remove(); }    // 无值时的清理
}

// 2. 注册 + 开启
ContextRegistry.getInstance().registerThreadLocalAccessor(new MyAccessor());
Hooks.enableAutomaticContextPropagation();  // 告诉 Reactor 接管所有已注册的 ThreadLocal
```

开启后，Reactor 在每次 `publishOn` / `subscribeOn` 切换线程前，自动将所有注册的 ThreadLocal 值保存到 Reactor Context，切换后从 Context 恢复到新线程。

---

#### 三者对比

| 方式                    | 适用场景       | 侵入性     | 覆盖范围           |
| --------------------- | ---------- | ------- | -------------- |
| 手动 `wrap`             | 少量一次性提交    | 高（每次手写） | 仅包裹的任务         |
| `ExecutorService` 装饰器 | 自定义线程池     | 低（换一次池） | 该池所有任务         |
| Micrometer Accessor   | Reactor 管道 | 低（注册一次） | 所有 operator 切换 |

---

#### 两个容易忽略的细节

**引用传播 vs 值传播**  
如果 ThreadLocal 存的是**可变对象的引用**（如 `List`），传播后所有线程共享同一对象，修改互相可见——这可能是特性（如聚合多线程结果），也可能是 bug。如果存的是**不可变值**（如 `String`），则每次传播是独立快照，互不影响。

**additivity=false 的日志隔离**  
命名 Logger + `additivity="false"` 是 logback 中将特定日志流路由到独立文件的标准做法，与上下文传播无关，但常配合使用——传播保证"写谁的"，独立 Logger 保证"写到哪"。

### SseEmitter与SpringWebFlux的核心区别

从原始需求出发：两者都**可以实现 Server-Sent Events (SSE)**，即服务器单向实时推送数据给客户端（如实时通知、监控、聊天等）。但它们解决问题的**底层模型和适用场景完全不同**。

#### 1. 所属框架与编程模型
- **SseEmitter**：属于 **Spring MVC**（基于 Servlet 容器，如 Tomcat）。它是传统的**阻塞/线程池模型**的扩展。
  - 核心是 `SseEmitter` 类（Spring 4.2 引入），继承自 `ResponseBodyEmitter`。
  - 每个连接通常占用一个线程（或通过异步线程池管理），连接保持打开。
  - 适合**请求-响应**风格的同步思维开发者。
- **Spring WebFlux**：Spring 5 引入的**响应式 Web 框架**，基于 Reactive Streams + Netty（默认）或 Undertow。
  - 使用 `Flux<ServerSentEvent<T>>` 或 `Flux<T>` 返回 SSE 流。
  - **非阻塞、事件驱动**模型，少量线程即可处理大量并发连接（通过 Reactor 调度器）。

#### 2. 关键技术差异对比
| 维度          | SseEmitter (Spring MVC)                  | Spring WebFlux (Flux SSE)                  |
|---------------|------------------------------------------|--------------------------------------------|
| **并发模型** | 阻塞/异步线程池，每个长连接占用资源较多 | 非阻塞 Reactive，背压（backpressure）支持 |
| **资源消耗** | 高并发时线程数容易成为瓶颈               | 极低线程数（几十个线程可支撑数万连接）     |
| **编程风格** | 命令式（Imperative），易理解             | 函数式+响应式，学习曲线较陡                 |
| **错误/超时处理** | 需手动管理 complete、timeout、error     | Reactor 自动处理，生命周期更优雅           |
| **性能**      | 适合中等并发                             | 极高吞吐量、高并发场景首选                 |
| **集成难度**  | 简单，直接用注解控制器                   | 需要理解 Mono/Flux，响应式数据源更佳       |
| **适用场景**  | 已有 MVC 项目、简单实时推送、中低并发   | 新项目、高并发微服务、流式数据处理         |

#### 3. 代码风格示例（概念对比）
**SseEmitter 示例**（MVC）：
```java
@GetMapping("/sse")
public SseEmitter handleSse() {
    SseEmitter emitter = new SseEmitter(0L); // 超时时间
    // 异步线程推送
    executor.execute(() -> {
        try {
            emitter.send("数据");
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });
    return emitter;
}
```

**WebFlux 示例**：
```java
@GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> handleSse() {
    return Flux.interval(Duration.ofSeconds(1))
               .map(i -> ServerSentEvent.<String>builder()
                   .data("数据 " + i)
                   .build());
}
```

#### 4. 选型建议（从需求出发）
- 如果你的项目已经是 **Spring MVC** 栈，且并发不高、团队对响应式不熟悉 → 优先用 **SseEmitter**，改动最小。
- 如果追求**高并发、低延迟、资源利用率**，或项目已计划响应式 → 强烈推荐 **Spring WebFlux**。
- 混合使用：WebFlux 项目里也可以注入 SseEmitter，但不推荐（破坏响应式优势）。
