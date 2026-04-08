# AgentScope Java — Structured Output & Agent Paradigms

> AgentScope Java 1.0.11  
> 记录日期：2026-04-08

---

## 1. Structured Output（结构化输出）

### 原理：Tool-Call 强制输出

AgentScope 不依赖 prompt 里附加 JSON 格式指令，而是将目标结构注册成一个隐藏 tool（`generate_response`），强制 LLM 通过 tool call 返回结构化数据，内置自动重试纠错。

### 对比 Spring AI BeanOutputConverter

| | Spring AI `BeanOutputConverter` | AgentScope Structured Output |
|---|---|---|
| **原理** | JSON Schema 拼入 system prompt，正则/Jackson 解析返回文本 | 注册隐藏 tool，LLM 通过 tool call 返回结构 |
| **可靠性** | 依赖 LLM 遵守格式，可能返回无效 JSON | tool call 协议保证结构合法，内置重试 |
| **调用方式** | `converter.getFormat()` 拼入 prompt + `converter.convert(raw)` | `agent.call(msgs, MyClass.class)` |
| **取结果** | 手动解析字符串 | `msg.getStructuredData(MyClass.class)` |
| **失败策略** | 解析失败抛异常，需手动 fallback | 内置 `MAX_RETRIES` 自动重试 |

### 使用方式

```java
// 1. 定义目标 POJO（Jackson 反序列化用）
record WeatherResult(String city, int temperature, String condition) {}

// 2. call() 时传入 Class
Msg response = agent.call(
    List.of(userMsg),
    WeatherResult.class   // ← 只需传 class，框架自动生成 schema
).block();

// 3. 从返回的 Msg 取强类型对象
WeatherResult result = response.getStructuredData(WeatherResult.class);
```

### Reminder 模式（`StructuredOutputReminder` enum）

| 值 | 适用场景 |
|---|---|
| `TOOL_CHOICE`（默认） | 通过 tool call 强制返回，更可靠，推荐 |
| `PROMPT` | 在 prompt 里加格式说明，兼容不支持 tool call 的模型 |

### 手动指定 JsonSchema

```java
// 从 Class 生成 schema
Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromClass(WeatherResult.class);

// 传 JsonNode 手动定义 schema
agent.call(List.of(userMsg), myJsonNode);
```

### 关键类

| 类 | 说明 |
|---|---|
| `AgentBase.call(List<Msg>, Class<?>)` | 触发 structured output 的入口 |
| `StructuredOutputCapableAgent` | 所有支持结构化输出 agent 的基类（ReActAgent 继承自它） |
| `StructuredOutputHook` | 内部实现：拦截 reasoning/acting 事件，处理重试 |
| `StructuredOutputReminder` | 枚举：TOOL_CHOICE / PROMPT |
| `JsonSchemaUtils` | 工具类：从 Class/Type/JsonNode 生成 JSON Schema |
| `Msg.getStructuredData(Class<T>)` | 从返回 Msg 反序列化为强类型对象 |

---

## 2. Plan-and-Resolve 范式

### 原理

AgentScope **原生内置**该范式，核心是 `PlanNotebook`。它是一个挂载在 ReActAgent 上的结构化任务管理器，框架自动将其操作方法注册为 tool，LLM 在 ReAct 循环中自主调用。

### LLM 可调用的内置 Tool

| Tool | 作用 |
|---|---|
| `createPlan(name, desc, expectedOutcome, subtasks)` | 制定计划和子任务列表 |
| `updateSubtaskState(index, state)` | 更新子任务状态 |
| `finishSubtask(index, outcome)` | 完成某步骤并记录结果 |
| `reviseCurrentPlan(index, ...)` | 中途修改计划 |
| `finishPlan(state, outcome)` | 结束整个计划 |
| `viewSubtasks(indices)` | 查看子任务详情 |
| `viewHistoricalPlans()` | 查看历史计划 |
| `recoverHistoricalPlan(id)` | 恢复历史计划 |

### SubTaskState / PlanState

```
TODO → IN_PROGRESS → DONE
                   → ABANDONED
```

### 用法

```java
// 1. 构建 PlanNotebook
PlanNotebook notebook = PlanNotebook.builder()
    .maxSubtasks(10)
    .needUserConfirm(false)           // true = 每步需要人工确认（HITL）
    .storage(new InMemoryPlanStorage())
    .planToHint(new DefaultPlanToHint())  // 把当前计划进度注入 system prompt
    .build();

// 2. 挂载到 ReActAgent
ReActAgent agent = ReActAgent.builder()
    .name("Planner")
    .model(agentScopeModel)
    .toolkit(toolkit)
    .planNotebook(notebook)           // ← 挂上去即启用
    .maxIters(20)
    .build();

// 3. 调用 — LLM 自主 createPlan → 逐步 finishSubtask → finishPlan
Msg response = agent.call(userMsg).block();

// 4. 查看最终计划状态
Plan plan = notebook.getCurrentPlan();
plan.getSubtasks().forEach(t ->
    System.out.println(t.getName() + " → " + t.getState() + ": " + t.getOutcome())
);
```

### DefaultPlanToHint 的作用

每轮 reasoning 前自动把当前计划进度格式化注入 system prompt，LLM 始终感知"做到哪一步了"，无需外部管理状态。

### 与项目现有 TaskPlanner 的对比

| | 项目 `TaskPlanner` | AgentScope `PlanNotebook` |
|---|---|---|
| 规划时机 | 对话开始前一次性生成静态 JSON 计划 | LLM 在执行中动态生成和修改计划 |
| 步骤执行 | 计划仅作为 system prompt hint，不追踪执行状态 | 每步有状态追踪（TODO/IN_PROGRESS/DONE） |
| 可修改性 | 静态，不可变 | 支持 `reviseCurrentPlan` 动态调整 |
| 实现复杂度 | 简单，一次 LLM 调用 | 需要 maxIters 足够大 |

---

## 3. Reflection 范式

AgentScope 1.0.11 **没有内置 Reflection 专用类**，提供两种实现方式。

### 方式 1：Hook 注入批评（单 Agent，推荐）

在 `PostCallEvent` 里截获结果，驱动 critic LLM 评估，再把反馈注入 memory，下轮 reasoning 自动感知。

```java
Hook reflectionHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostCallEvent e) {
            String answer = e.getFinalMessage().getTextContent();

            // 用 agentScopeModel 直接调一次 critic
            Msg critiqueResult = callCritic(answer);

            // 注入 memory，下轮推理会读到
            e.getMemory().add(critiqueResult);
        }
        return Mono.just(event);
    }

    private Msg callCritic(String answer) {
        Msg systemMsg = Msg.builder()
            .role(MsgRole.SYSTEM)
            .textContent("你是一个严格的评审员，找出答案中的错误和遗漏，给出具体改进建议。")
            .build();
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .textContent(answer)
            .build();
        ChatResponse response = agentScopeModel
            .stream(List.of(systemMsg, userMsg), Collections.emptyList(), GenerateOptions.builder().build())
            .reduce((a, b) -> b).block();
        // 提取文本并构建 Msg...
        return Msg.builder().role(MsgRole.USER).textContent(extractText(response)).build();
    }
};

ReActAgent agent = ReActAgent.builder()
    .hook(reflectionHook)
    // ...
    .build();
```

### 方式 2：外部 Reflection 循环（多 Agent）

```java
// Agent 1: 执行者
ReActAgent executor = ReActAgent.builder()
    .name("Executor")
    .model(agentScopeModel)
    .toolkit(toolkit)
    .build();

// Agent 2: 批评者（纯 LLM，不需要 tool）
ReActAgent critic = ReActAgent.builder()
    .name("Critic")
    .sysPrompt("你是一个严格的评审员，指出答案中的错误和遗漏，给出具体改进建议。")
    .model(agentScopeModel)
    .maxIters(1)
    .build();

// 外部 Reflection 循环（最多 N 轮）
int maxRounds = 3;
Msg draft = executor.call(List.of(userMsg)).block();

for (int i = 0; i < maxRounds; i++) {
    Msg feedback = critic.call(List.of(draft)).block();

    if (isSatisfied(feedback)) break;  // 自定义满意度判断

    // 把原始问题 + 草稿 + 反馈一起给 executor 修正
    draft = executor.call(
        List.of(userMsg, draft, feedback)
    ).block();
}

// draft 是最终结果
```

### 两种方式对比

| | Hook 注入 | 外部循环 |
|---|---|---|
| **复杂度** | 低，单 agent | 高，多 agent 协作 |
| **透明度** | 反馈在 memory 里，不易观察 | 每轮 draft/feedback 独立可见 |
| **控制力** | 被动，由 hook 触发 | 主动，可定制终止条件 |
| **适用场景** | 单次对话质量提升 | 复杂任务多轮打磨 |

---

## 4. 关键类索引

| 类/接口 | 包 | 说明 |
|---|---|---|
| `PlanNotebook` | `io.agentscope.core.plan` | Plan-and-Resolve 核心，任务管理器 |
| `Plan` | `io.agentscope.core.plan.model` | 计划实体（含 subtasks） |
| `SubTask` | `io.agentscope.core.plan.model` | 子任务实体 |
| `SubTaskState` | `io.agentscope.core.plan.model` | TODO/IN_PROGRESS/DONE/ABANDONED |
| `PlanState` | `io.agentscope.core.plan.model` | 同上，Plan 级别 |
| `InMemoryPlanStorage` | `io.agentscope.core.plan.storage` | 内存计划存储 |
| `DefaultPlanToHint` | `io.agentscope.core.plan.hint` | 计划→system prompt 注入 |
| `StructuredOutputCapableAgent` | `io.agentscope.core.agent` | 结构化输出基类 |
| `StructuredOutputHook` | `io.agentscope.core.agent` | 结构化输出内部 Hook |
| `StructuredOutputReminder` | `io.agentscope.core.model` | TOOL_CHOICE / PROMPT |
| `JsonSchemaUtils` | `io.agentscope.core.util` | JSON Schema 生成工具 |
