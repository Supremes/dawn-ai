# dawn-ai ReAct 改造方案

## 背景与问题

### 当前实现的问题

`AgentOrchestrator.doChat()` 当前用 Spring AI 的 `.toolNames()` 自动处理工具循环：

```java
String response = chatClient.prompt()
        .messages(history)
        .user(userMessage)
        .toolNames("weatherTool", "calculatorTool")
        .call()
        .content();
```

`.toolNames()` 本身没有问题——它把工具的名称、描述、入参 Schema 发给 LLM，LLM 决定调哪个工具，Spring AI 执行后把结果喂回 LLM，循环直到 LLM 给出最终答案。这个机制本质上就是 ReAct 循环。

**真正缺的是：**

| 缺失 | 说明 |
|------|------|
| 执行前无规划 | 没有 TaskPlanner，直接丢给 LLM 闷头执行 |
| 工具调用不可见 | 调了几次工具、输入/输出是什么，外部看不到 |
| 无步骤上限保护 | 没有 maxSteps 控制，工具循环次数不可控 |
| 响应无过程信息 | ChatResponse 只有最终答案，无推理过程 |

---

## 方案总览

**保留** `.toolNames()` 机制不动，在其上方叠加三层能力：

```
┌─────────────────────────────────────────┐
│  ① TaskPlanner（执行前生成计划）          │  新增
├─────────────────────────────────────────┤
│  ② AgentOrchestrator（注入计划+收集结果）  │  改造
├─────────────────────────────────────────┤
│  ③ ToolExecutionAspect（AOP 拦截步骤）    │  新增（核心）
├─────────────────────────────────────────┤
│  Spring AI .toolNames() 内部循环          │  不动
└─────────────────────────────────────────┘
```

---

## 新增文件（6 个）

### 1. `agent/AgentStep.java`

单步执行记录，记录一次工具调用的完整信息。

```java
package com.dawn.ai.agent;

public record AgentStep(
        int stepNumber,
        String toolName,
        Object toolInput,
        String toolOutput,
        long durationMs
) {}
```

### 2. `agent/AgentResult.java`

`AgentOrchestrator` 的新返回类型，替换原来的裸 `String`。

```java
package com.dawn.ai.agent;

import com.dawn.ai.agent.plan.PlanStep;

import java.util.List;

public record AgentResult(
        String finalAnswer,
        List<AgentStep> steps,
        List<PlanStep> plan
) {
}
```

### 3. `agent/PlanStep.java`

计划中的单个步骤，使用 class 而非 record，因为 `completed` 和 `result` 在执行过程中需要可变更新。

```java
package com.dawn.ai.agent;

import lombok.Data;

@Data
public class PlanStep {
    private final int stepNumber;
    private final String action;   // 对应工具名，末尾步骤为 "finish"
    private final String reason;
    private boolean completed = false;
    private String result;
}
```

### 4. `agent/TaskPlanner.java`

执行前调用 LLM 生成结构化执行计划。

**职责：**
- 把任务描述 + 可用工具列表发给 LLM（temperature=0.3，更确定性）
- 要求 LLM 以 JSON 格式返回 3-8 个步骤：`[{"step":1,"action":"weatherTool","reason":"..."}]`
- 解析 JSON，构建 `List<PlanStep>`
- 解析失败时返回空列表（优雅降级，不影响正常执行）
- 计划结果以文本形式注入 system prompt，引导 LLM 按序执行

**关键实现细节：**
- 规划请求独立发出，不混入对话历史
- 规划 prompt 包含所有可用工具的名称和描述
- 最后一步必须是 "finish"

**伪代码：**

```
plan(task, availableToolDescriptions):
  prompt = buildPlanPrompt(task, availableToolDescriptions)
  raw = chatClient.prompt().user(prompt).options(temperature=0.3).call().content()
  try:
    return parseJson(raw) -> List<PlanStep>
  catch:
    log.warn("规划失败，降级为无计划模式")
    return []
```

### 5. `agent/StepCollector.java`

基于 `ThreadLocal` 的请求级步骤收集器，在 AOP 切面和 Orchestrator 之间传递数据，无需修改工具类。

```java
package com.dawn.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class StepCollector {

    private static final ThreadLocal<List<AgentStep>> STEPS =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<AtomicInteger> COUNTER =
            ThreadLocal.withInitial(() -> new AtomicInteger(0));

    /** 请求开始时调用，清空上一次的状态 */
    public static void init() {
        STEPS.get().clear();
        COUNTER.get().set(0);
    }

    /** 由 AOP 切面在每次工具执行后调用 */
    public static void record(AgentStep step) {
        STEPS.get().add(step);
    }

    /** 返回下一个步骤编号 */
    public static int nextStepNumber() {
        return COUNTER.get().incrementAndGet();
    }

    /** 请求结束后读取全部步骤 */
    public static List<AgentStep> collect() {
        return new ArrayList<>(STEPS.get());
    }

    /** 必须在 finally 块中调用，防止 ThreadLocal 内存泄漏 */
    public static void clear() {
        STEPS.remove();
        COUNTER.remove();
    }
}
```

### 6. `agent/aop/ToolExecutionAspect.java`

**最核心的新增**。用 AOP 拦截 `tools` 包下所有工具的 `apply()` 调用，自动记录步骤。

```java
package com.dawn.ai.agent.aop;

import com.dawn.ai.agent.AgentStep;
import com.dawn.ai.agent.StepCollector;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class ToolExecutionAspect {

    @Around("execution(* com.dawn.ai.agent.tools.*.apply(..))")
    public Object captureStep(ProceedingJoinPoint pjp) throws Throwable {
        String toolName = pjp.getTarget().getClass().getSimpleName();
        Object input = pjp.getArgs()[0];
        long start = System.currentTimeMillis();

        Object result = pjp.proceed();   // 执行原始工具方法

        long duration = System.currentTimeMillis() - start;
        int stepNum = StepCollector.nextStepNumber();

        AgentStep step = new AgentStep(stepNum, toolName, input, result.toString(), duration);
        StepCollector.record(step);

        log.debug("[ReAct] Step {} | tool={} | input={} | output={} | {}ms",
                stepNum, toolName, input, result, duration);

        return result;
    }
}
```

**优势：**
- `WeatherTool` / `CalculatorTool` 零改动
- 未来新增到 `tools` 包下的工具自动被追踪，无需额外配置

---

## 修改文件（4 个）

### 1. `AgentOrchestrator.java` — 主要改造

新增依赖注入：
- `TaskPlanner taskPlanner`
- `@Value("${app.ai.react.max-steps:10}") int maxSteps`
- `@Value("${app.ai.react.plan-enabled:true}") boolean planEnabled`

`doChat()` 改造后流程：

```
① StepCollector.init()

② 如果 planEnabled：
     plan = taskPlanner.plan(userMessage, toolDescriptions)
     planSummary = formatPlan(plan)
   否则：
     plan = []，planSummary = ""

③ systemPrompt = baseSystemPrompt
               + planSummary（计划摘要文本）
               + "请在回复中简短说明每次工具调用的原因。最多调用工具 {maxSteps} 次。"

④ history = buildHistory(sessionId)

⑤ response = chatClient.prompt()
                 .system(systemPrompt)
                 .messages(history)
                 .user(userMessage)
                 .toolNames("weatherTool", "calculatorTool")
                 .call()
                 .content()
     // ↑ AOP 在此期间自动拦截并记录每次工具调用

⑥ steps = StepCollector.collect()

⑦ 根据 steps 中的 toolName 标记 plan 对应步骤为 completed

⑧ memoryService.addMessage(sessionId, "user", userMessage)
   memoryService.addMessage(sessionId, "assistant", response)

⑨ return AgentResult(response, steps, plan)

finally：StepCollector.clear()
```

**返回类型从 `String` 改为 `AgentResult`。**

### 2. `ChatResponse.java` — 增加字段

在现有 `sessionId / answer / durationMs / model` 基础上新增：

```java
private List<AgentStep> steps;     // 工具调用步骤列表（showSteps=false 时为 null）
private String planSummary;        // 计划摘要（如"步骤1: 查询天气 → 步骤2: 完成"）
private int totalSteps;            // 实际执行的工具调用次数
```

### 3. `ChatService.java` — 适配新返回类型

```
AgentResult result = agentOrchestrator.chat(sessionId, userMessage)

return ChatResponse.builder()
    .sessionId(sessionId)
    .answer(result.finalAnswer())
    .steps(showSteps ? result.steps() : null)   // 按配置决定是否返回步骤
    .planSummary(formatPlanSummary(result.plan()))
    .totalSteps(result.steps().size())
    .durationMs(...)
    .model("gpt-4o")
    .build()
```

新增依赖：`@Value("${app.ai.react.show-steps:false}") boolean showSteps`

### 4. `application.yml` — 新增配置块

在现有 `app.ai` 下新增 `react` 子节点：

```yaml
app:
  ai:
    react:
      max-steps: 10        # 单次对话最多工具调用次数（超出时 LLM 自行收敛）
      show-steps: false    # 响应体中是否包含 steps 详情
      plan-enabled: true   # 是否启用执行前规划（关闭后跳过 TaskPlanner）
    system-prompt: |
      You are Dawn AI, a helpful and knowledgeable assistant powered by advanced AI.
      You can help with calculations, weather queries, and answer questions based on your knowledge base.
      Always be concise, accurate, and helpful.
      Before calling a tool, briefly explain your reasoning in one sentence.
```

---

## 完整数据流（改造后）

```
POST /api/v1/chat
  │
  ▼ ChatController.chat()
  │
  ▼ ChatService.chat()
      - ensureConfigured()
      - 处理 sessionId
      - 如果 ragEnabled：prepend 上下文
      │
      ▼ AgentOrchestrator.doChat()
          - StepCollector.init()
          │
          ▼ TaskPlanner.plan(userMessage, tools)
              - 独立调用 LLM（temperature=0.3）
              - 返回 List<PlanStep>
              例：[查询天气, 完成任务]
          │
          - buildSystemPrompt（base + plan摘要 + maxSteps说明）
          - buildHistory（从 Redis 加载对话历史）
          │
          ▼ chatClient + .toolNames()（Spring AI 内部循环）
              LLM 决定调 weatherTool
                  │
                  ▼ ToolExecutionAspect.captureStep()（AOP 拦截）
                      - 记录 input / 调用 WeatherTool.apply() / 记录 output + 耗时
                      - StepCollector.record(AgentStep#1)
              LLM 收到结果，生成最终答案
          │
          - steps = StepCollector.collect()   → [AgentStep#1]
          - 标记 plan 步骤 completed
          - 持久化到 Redis
          - StepCollector.clear()
          │
          return AgentResult(answer, steps, plan)
      │
      - 组装 ChatResponse（按配置决定是否含 steps）
      │
  ◄ ResponseEntity<ChatResponse>
      {
        "sessionId": "...",
        "answer": "北京今天多云，12°C",
        "planSummary": "步骤1: weatherTool → 步骤2: 完成",
        "totalSteps": 1,
        "durationMs": 1240,
        "model": "gpt-4o",
        "steps": null   // show-steps=false 时不返回
      }
```

---

## 改动规模汇总

| 文件 | 类型 | 预估行数 |
|------|------|---------|
| `agent/AgentStep.java` | 新建 | ~15 |
| `agent/AgentResult.java` | 新建 | ~15 |
| `agent/PlanStep.java` | 新建 | ~25 |
| `agent/TaskPlanner.java` | 新建 | ~90 |
| `agent/StepCollector.java` | 新建 | ~35 |
| `agent/aop/ToolExecutionAspect.java` | 新建 | ~40 |
| `AgentOrchestrator.java` | 改造 doChat() | ~60 行改动 |
| `ChatResponse.java` | 加字段 | ~5 行改动 |
| `ChatService.java` | 适配返回类型 | ~15 行改动 |
| `application.yml` | 加配置 | ~8 行改动 |

**`WeatherTool.java` / `CalculatorTool.java` 零改动。**

---

## 关键设计决策说明

### 为什么用 AOP 而不是修改工具类？

修改工具类需要每个类都注入 `StepCollector`，违反单一职责原则，且未来每增加一个工具都要重复这个操作。AOP 切点 `execution(* com.dawn.ai.agent.tools.*.apply(..))` 覆盖整个包，一次配置永久生效。

### 为什么用 ThreadLocal 而不是方法参数传递？

工具的 `apply()` 方法签名是 `Function<Req, Resp>`，是框架约定，无法修改入参。ThreadLocal 是在不改变方法签名的前提下传递请求级状态的标准做法，需注意在 `finally` 块调用 `StepCollector.clear()` 防止内存泄漏。

### 为什么 TaskPlanner 用独立调用而不复用对话历史？

规划是一次全局视角的推理（需要 temperature=0.3 更确定性），和任务执行的对话上下文无关。混入历史会引入噪声，独立调用更干净，失败也不影响主流程。

### max-steps 如何生效？

不通过代码强制中断 Spring AI 内部循环（那需要 hack 框架），而是通过 system prompt 告知 LLM "最多调用 N 次工具"，让模型自行控制。这是最简单且副作用最小的方式。如果需要硬性保证，可在 `steps.size()` 超限时提前返回（后续迭代考虑）。
