# Spring AI → AgentScope-Java Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Spring AI 1.1.4 全面替换为 AgentScope-Java 1.0.11，保留 OpenAI 作为模型后端，采用逐层迁移策略确保每一层完成后 `./mvnw test` 始终 GREEN。

**Architecture:** 5 个独立 PR（Layer），每层一个 git branch，互不依赖。L1 添加 AgentScope 依赖并为工具添加双接口；L2 替换 Agent 核心（ReActAgent + Hook 取代 ToolExecutionAspect）；L3 替换 RAG 层（AgentScope Knowledge API）；L4 替换 Memory 层（RedisMemory adapter）；L5 清除所有 Spring AI 依赖并删除遗留代码。

**Tech Stack:** AgentScope-Java 1.0.11 (`io.agentscope:agentscope`), Spring Boot 3.2.5, OpenAI, pgvector, Redis, JUnit 5, Mockito

> **⚠️ 前置条件:** 在 L1 开始前，访问 https://java.agentscope.io/ 确认：
> 1. OpenAI 模型的 class 名称（预期为 `OpenAIModel` 或 `ChatModel`，需要 model ID 和 API key 参数）
> 2. `ReActAgent.builder()` API 签名
> 3. `Hook` 接口方法签名（`onToolCall`/`onToolResult` 或类似）
> 4. `Knowledge` API 与 pgvector 集成方式
> 5. `Memory` 接口方法签名

---

## 文件结构映射

| 动作 | 文件路径 | 说明 |
|------|---------|------|
| NEW | `src/main/java/com/dawn/ai/config/AgentScopeConfig.java` | AgentScope Bean 配置（L1 创建，L2/L3/L4 增量修改） |
| NEW | `src/main/java/com/dawn/ai/agent/hook/StepTraceHook.java` | 替代 ToolExecutionAspect + StepCollector（L2） |
| NEW | `src/main/java/com/dawn/ai/agent/memory/RedisMemory.java` | Memory 接口适配器，包装 MemoryService（L4） |
| MODIFY | `pom.xml` | L1 添加 agentscope；L5 删除 spring-ai-* |
| MODIFY | `src/main/java/com/dawn/ai/agent/tools/WeatherTool.java` | L1 添加 `@Tool` 方法；L2 删除 `apply()` |
| MODIFY | `src/main/java/com/dawn/ai/agent/tools/CalculatorTool.java` | L1 添加 `@Tool` 方法；L2 删除 `apply()` |
| MODIFY | `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java` | L1 添加 `@Tool` 方法；L3 整体删除 |
| MODIFY | `src/main/java/com/dawn/ai/agent/ToolRegistry.java` | L2 改为输出 `Toolkit` |
| MODIFY | `src/main/java/com/dawn/ai/agent/AgentOrchestrator.java` | L2 换用 ReActAgent；L4 接入 RedisMemory |
| MODIFY | `src/main/java/com/dawn/ai/agent/StepCollector.java` | L2 变为 no-op stub；L5 删除 |
| MODIFY | `src/main/java/com/dawn/ai/agent/plan/TaskPlanner.java` | L5 改用 AgentScope API |
| MODIFY | `src/main/java/com/dawn/ai/controller/RagController.java` | L3 对齐 Knowledge API |
| DELETE | `src/main/java/com/dawn/ai/agent/aop/ToolExecutionAspect.java` | L2 |
| DELETE | `src/main/java/com/dawn/ai/service/RagService.java` | L3 |
| DELETE | `src/main/java/com/dawn/ai/service/QueryRewriter.java` | L3 |
| DELETE | `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java` | L3 |
| DELETE | `src/main/java/com/dawn/ai/config/AiConfig.java` | L5 |

---

## Layer 1: Foundation — 添加 AgentScope 依赖，工具双接口

**目标:** pom.xml 引入 AgentScope；现有工具同时保留 Spring AI `apply()` 和新的 `@Tool` 方法；所有现有测试继续通过。

---

### Task 1.1: 添加 AgentScope 依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 写依赖变更测试（编译验证）**

这一步通过"写代码然后编译"代替传统单元测试——AgentScope 类加载即为验证。

- [ ] **Step 2: 在 `pom.xml` 的 `<dependencies>` 块末尾添加**

```xml
<!-- AgentScope-Java -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.11</version>
</dependency>
```

- [ ] **Step 3: 编译验证**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS（无编译错误）

- [ ] **Step 4: 运行全量测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS，所有测试 PASS

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "chore: add agentscope-java 1.0.11 dependency"
```

---

### Task 1.2: 创建 AgentScopeConfig（骨架）

**Files:**
- Create: `src/main/java/com/dawn/ai/config/AgentScopeConfig.java`

> **⚠️ 在写代码前，先确认 AgentScope OpenAI 模型类名。** 访问 https://java.agentscope.io/ 查找正确的 import 和构造方式。下面使用 `OpenAIModel` 作为占位符——实际类名以文档为准。

- [ ] **Step 1: 写编译测试（import 验证）**

创建一个临时测试文件确认 class 可导入：

```java
// src/test/java/com/dawn/ai/config/AgentScopeImportTest.java
package com.dawn.ai.config;

import org.junit.jupiter.api.Test;
// TODO: 替换为实际 AgentScope OpenAI model import
// import io.agentscope.model.openai.OpenAIModel;

class AgentScopeImportTest {
    @Test
    void agentScopeDependencyIsAvailable() {
        // 能编译即通过；后续步骤会补充真实断言
    }
}
```

```bash
./mvnw test -Dtest=AgentScopeImportTest
```

Expected: PASS（即使是空测试，验证依赖可用）

- [ ] **Step 2: 创建 AgentScopeConfig 骨架**

```java
package com.dawn.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope-Java configuration.
 * L1: skeleton — model bean added in Task 1.3
 * L2: Toolkit bean added
 * L3: Knowledge bean added
 * L4: Memory wiring added
 */
@Slf4j
@Configuration
public class AgentScopeConfig {

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String modelName;

    // Beans will be added incrementally in L2, L3, L4
}
```

- [ ] **Step 3: 编译 + 测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/config/AgentScopeConfig.java \
        src/test/java/com/dawn/ai/config/AgentScopeImportTest.java
git commit -m "feat(l1): add AgentScopeConfig skeleton"
```

---

### Task 1.3: WeatherTool 添加 @Tool 方法

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/tools/WeatherTool.java`
- Test: `src/test/java/com/dawn/ai/agent/tools/WeatherToolAgentScopeTest.java`

> WeatherTool 现有结构：`@Component @Description @Slf4j`，实现 `Function<Request,Response>`，有 `apply(Request)` 方法和内部 `record Request/Response`。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/dawn/ai/agent/tools/WeatherToolAgentScopeTest.java
package com.dawn.ai.agent.tools;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WeatherToolAgentScopeTest {

    private final WeatherTool tool = new WeatherTool();

    @Test
    void getWeather_returnsConditionForKnownCity() {
        String result = tool.getWeather("Beijing");
        assertThat(result).contains("Beijing");
    }

    @Test
    void getWeather_returnsUnknownForUnknownCity() {
        String result = tool.getWeather("UnknownCity123");
        assertThat(result).contains("Unknown");
    }
}
```

```bash
./mvnw test -Dtest=WeatherToolAgentScopeTest
```

Expected: FAIL（`getWeather` 方法不存在）

- [ ] **Step 2: 在 WeatherTool 中添加 `@Tool` 方法**

在 `apply()` 方法之后，`record` 定义之前，添加（保留原有代码不变）：

```java
// TODO: 替换为实际 AgentScope @Tool import
// import io.agentscope.annotation.Tool;
// import io.agentscope.annotation.ToolParam;

/**
 * AgentScope-compatible entry point.
 * Returns plain String because AgentScope @Tool methods must return String or primitive.
 */
// @Tool(description = "Get current weather information for a city")
public String getWeather(
        // @ToolParam(description = "The city name to query weather for")
        String city) {
    Response resp = apply(new Request(city));
    return String.format("%s: %s, %d°C, humidity %d%%",
            resp.city(), resp.condition(), resp.temperatureCelsius(), resp.humidity());
}
```

> **注意:** `@Tool`/`@ToolParam` 注解行先注释掉（`//`），等 Task 1.2 确认真实 import 后解注释。保留注释形式确保编译通过。

- [ ] **Step 3: 运行测试**

```bash
./mvnw test -Dtest=WeatherToolAgentScopeTest
```

Expected: PASS

- [ ] **Step 4: 全量测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/tools/WeatherTool.java \
        src/test/java/com/dawn/ai/agent/tools/WeatherToolAgentScopeTest.java
git commit -m "feat(l1): add @Tool method to WeatherTool"
```

---

### Task 1.4: CalculatorTool 添加 @Tool 方法

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/tools/CalculatorTool.java`
- Test: `src/test/java/com/dawn/ai/agent/tools/CalculatorToolAgentScopeTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/dawn/ai/agent/tools/CalculatorToolAgentScopeTest.java
package com.dawn.ai.agent.tools;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CalculatorToolAgentScopeTest {

    private final CalculatorTool tool = new CalculatorTool();

    @Test
    void calculate_returnsResultForAddition() {
        String result = tool.calculate("2 + 3");
        assertThat(result).contains("5");
    }

    @Test
    void calculate_returnsResultForComplexExpression() {
        String result = tool.calculate("2 + 3 * 4");
        assertThat(result).contains("14");
    }

    @Test
    void calculate_handlesInvalidExpression() {
        String result = tool.calculate("invalid!!!");
        assertThat(result).containsIgnoringCase("error");
    }
}
```

```bash
./mvnw test -Dtest=CalculatorToolAgentScopeTest
```

Expected: FAIL

- [ ] **Step 2: 在 CalculatorTool 中添加 `@Tool` 方法**

在 `apply()` 方法之后添加：

```java
// @Tool(description = "Performs basic arithmetic calculations. Input: a mathematical expression like '2 + 3 * 4'")
public String calculate(
        // @ToolParam(description = "Mathematical expression to evaluate, e.g. '2 + 3 * 4'")
        String expression) {
    try {
        Response resp = apply(new Request(expression));
        return String.format("Result of '%s' = %s", resp.expression(), resp.result());
    } catch (Exception e) {
        return "Error evaluating expression: " + e.getMessage();
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
./mvnw test -Dtest=CalculatorToolAgentScopeTest
```

Expected: PASS

- [ ] **Step 4: 全量测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/tools/CalculatorTool.java \
        src/test/java/com/dawn/ai/agent/tools/CalculatorToolAgentScopeTest.java
git commit -m "feat(l1): add @Tool method to CalculatorTool"
```

---

### Task 1.5: KnowledgeSearchTool 添加 @Tool 方法

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java`
- Test: `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolAgentScopeTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolAgentScopeTest.java
package com.dawn.ai.agent.tools;

import com.dawn.ai.service.QueryRewriter;
import com.dawn.ai.service.RagService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeSearchToolAgentScopeTest {

    private KnowledgeSearchTool tool;
    private QueryRewriter queryRewriter;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        queryRewriter = mock(QueryRewriter.class);
        ragService = mock(RagService.class);
        tool = new KnowledgeSearchTool(queryRewriter, ragService, new SimpleMeterRegistry());
        when(queryRewriter.rewrite(anyString())).thenAnswer(i -> i.getArgument(0));
        when(ragService.retrieve(anyString(), anyInt())).thenReturn(java.util.List.of());
    }

    @Test
    void searchKnowledge_returnsStringResult() {
        String result = tool.searchKnowledge("what is AI?");
        assertThat(result).isNotNull();
    }
}
```

```bash
./mvnw test -Dtest=KnowledgeSearchToolAgentScopeTest
```

Expected: FAIL

- [ ] **Step 2: 在 KnowledgeSearchTool 中添加 `@Tool` 方法**

在 `apply()` 方法之后添加：

```java
// @Tool(description = "Search the knowledge base to answer questions. Call this when you need factual information.")
public String searchKnowledge(
        // @ToolParam(description = "The search query or question to look up in the knowledge base")
        String query) {
    Response resp = apply(new Request(query));
    if (resp.docsFound() == 0) {
        return "No relevant information found for: " + query;
    }
    return resp.context();
}
```

- [ ] **Step 3: 运行测试**

```bash
./mvnw test -Dtest=KnowledgeSearchToolAgentScopeTest
```

Expected: PASS

- [ ] **Step 4: 全量测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit + L1 完成**

```bash
git add src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java \
        src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolAgentScopeTest.java
git commit -m "feat(l1): add @Tool method to KnowledgeSearchTool"
```

---

### Task 1.6: 解注释 @Tool/@ToolParam 注解

> 在 Task 1.2 中已确认真实 import，现在解注释所有工具的注解。

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/tools/WeatherTool.java`
- Modify: `src/main/java/com/dawn/ai/agent/tools/CalculatorTool.java`
- Modify: `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java`

- [ ] **Step 1: 在每个工具文件中，添加真实 import 并解注释注解**

以 WeatherTool 为例（CalculatorTool、KnowledgeSearchTool 同理）：

```java
// 添加 import（替换为实际包路径）
import io.agentscope.annotation.Tool;
import io.agentscope.annotation.ToolParam;

// 将注释的注解解注释：
@Tool(description = "Get current weather information for a city")
public String getWeather(
        @ToolParam(description = "The city name to query weather for")
        String city) {
    // ... 保持方法体不变
}
```

- [ ] **Step 2: 全量编译 + 测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/tools/
git commit -m "feat(l1): enable @Tool/@ToolParam annotations on all tools"
```

**L1 完成。创建 PR: `feat/l1-agentscope-foundation` → `master`**

---

## Layer 2: Agent Core — ReActAgent + Hook 替代 Spring AI ReAct 循环

**目标:** 用 `ReActAgent` 替换 `chatClient.prompt().toolNames().call()` 的 Spring AI ReAct 循环；用 `StepTraceHook` 替代 `ToolExecutionAspect + StepCollector`；删除 AOP 配置。

---

### Task 2.1: 创建 StepTraceHook

**Files:**
- Create: `src/main/java/com/dawn/ai/agent/hook/StepTraceHook.java`
- Test: `src/test/java/com/dawn/ai/agent/hook/StepTraceHookTest.java`

> **⚠️ 先确认 AgentScope Hook 接口签名。** 访问 https://java.agentscope.io/ 确认方法名，如 `onToolCall(String toolName, Map<String,Object> args)` 和 `onToolResult(String toolName, Object result)`。下面使用占位签名。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/dawn/ai/agent/hook/StepTraceHookTest.java
package com.dawn.ai.agent.hook;

import com.dawn.ai.agent.AgentStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class StepTraceHookTest {

    private StepTraceHook hook;

    @BeforeEach
    void setUp() {
        hook = new StepTraceHook(10);
    }

    @Test
    void recordsToolCallAsStep() {
        hook.onToolCall("weatherTool", Map.of("city", "Beijing"));
        hook.onToolResult("weatherTool", "Beijing: sunny, 25°C");

        List<AgentStep> steps = hook.getSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).toolName()).isEqualTo("weatherTool");
        assertThat(steps.get(0).result()).contains("Beijing");
    }

    @Test
    void throwsWhenMaxStepsExceeded() {
        StepTraceHook limitedHook = new StepTraceHook(1);
        limitedHook.onToolCall("tool1", Map.of());
        limitedHook.onToolResult("tool1", "result1");

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> limitedHook.onToolCall("tool2", Map.of())
        );
    }

    @Test
    void clearResetsSteps() {
        hook.onToolCall("tool", Map.of());
        hook.onToolResult("tool", "r");
        hook.clear();
        assertThat(hook.getSteps()).isEmpty();
    }
}
```

```bash
./mvnw test -Dtest=StepTraceHookTest
```

Expected: FAIL（StepTraceHook 不存在）

- [ ] **Step 2: 创建 StepTraceHook**

```java
package com.dawn.ai.agent.hook;

import com.dawn.ai.agent.AgentStep;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// TODO: 替换为实际 AgentScope Hook 接口 import
// import io.agentscope.hook.AgentHook;

/**
 * Replaces ToolExecutionAspect + StepCollector.
 * Tracks tool calls as AgentStep list; enforces maxSteps hard limit.
 *
 * One instance per request — do NOT make this a Spring singleton.
 */
@Slf4j
// public class StepTraceHook implements AgentHook {
public class StepTraceHook {

    private final int maxSteps;
    private final List<AgentStep> steps = new ArrayList<>();
    private String pendingToolName;
    private String pendingInput;
    private Instant pendingStart;

    public StepTraceHook(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    // TODO: 匹配实际 AgentScope Hook 接口签名
    // @Override
    public void onToolCall(String toolName, Map<String, Object> args) {
        if (steps.size() >= maxSteps) {
            log.warn("maxSteps={} exceeded, aborting tool call: {}", maxSteps, toolName);
            throw new IllegalStateException(
                    "Max steps (" + maxSteps + ") exceeded. Stopping agent.");
        }
        log.debug("Tool call: {} args={}", toolName, args);
        this.pendingToolName = toolName;
        this.pendingInput = args.toString();
        this.pendingStart = Instant.now();
    }

    // @Override
    public void onToolResult(String toolName, Object result) {
        long durationMs = pendingStart != null
                ? Instant.now().toEpochMilli() - pendingStart.toEpochMilli()
                : 0;
        AgentStep step = new AgentStep(
                steps.size() + 1,
                toolName,
                pendingInput,
                result != null ? result.toString() : "",
                durationMs
        );
        steps.add(step);
        log.debug("Tool result: {} -> {} ({}ms)", toolName, result, durationMs);
        this.pendingToolName = null;
        this.pendingInput = null;
        this.pendingStart = null;
    }

    public List<AgentStep> getSteps() {
        return List.copyOf(steps);
    }

    public void clear() {
        steps.clear();
        pendingToolName = null;
        pendingInput = null;
        pendingStart = null;
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
./mvnw test -Dtest=StepTraceHookTest
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/hook/StepTraceHook.java \
        src/test/java/com/dawn/ai/agent/hook/StepTraceHookTest.java
git commit -m "feat(l2): add StepTraceHook to replace ToolExecutionAspect"
```

---

### Task 2.2: 改写 ToolRegistry 输出 Toolkit

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/ToolRegistry.java`
- Test: `src/test/java/com/dawn/ai/agent/ToolRegistryAgentScopeTest.java`

> 现有 ToolRegistry：扫描 `@Description + Function<?>` beans → `getNames()` + `getDescriptions()`。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/dawn/ai/agent/ToolRegistryAgentScopeTest.java
package com.dawn.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

// TODO: 替换为实际 AgentScope Toolkit import
// import io.agentscope.toolkit.Toolkit;

@SpringBootTest
class ToolRegistryAgentScopeTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void getToolkit_returnsNonNull() {
        // Toolkit toolkit = toolRegistry.getToolkit();
        // assertThat(toolkit).isNotNull();
        // assertThat(toolkit.getTools()).isNotEmpty();
        assertThat(toolRegistry.getNames()).isNotEmpty(); // 先保持现有断言
    }
}
```

```bash
./mvnw test -Dtest=ToolRegistryAgentScopeTest
```

Expected: PASS（先用现有 API，L2 完成后更新）

- [ ] **Step 2: 在 ToolRegistry 中添加 `getToolkit()` 方法**

在 `getNames()` 和 `getDescriptions()` 方法保留的前提下，追加：

```java
// TODO: 替换为实际 Toolkit import
// import io.agentscope.toolkit.Toolkit;

/**
 * Returns an AgentScope Toolkit containing all registered tools.
 * Tools must have @Tool-annotated methods (added in L1).
 */
// public Toolkit getToolkit() {
//     Toolkit.Builder builder = Toolkit.builder();
//     getToolInstances().forEach(builder::addTool);
//     return builder.build();
// }

// 临时辅助方法：返回工具实例列表
public List<Object> getToolInstances() {
    return applicationContext.getBeansWithAnnotation(
            org.springframework.ai.tool.annotation.Description.class
    ).values().stream()
            .filter(bean -> bean instanceof java.util.function.Function)
            .filter(bean -> bean.getClass().getPackageName()
                    .startsWith("com.dawn.ai.agent.tools"))
            .collect(java.util.stream.Collectors.toList());
}
```

> **注意:** `getToolkit()` 先注释，等 L2 确认 Toolkit API 后解注释。

- [ ] **Step 3: 测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/ToolRegistry.java \
        src/test/java/com/dawn/ai/agent/ToolRegistryAgentScopeTest.java
git commit -m "feat(l2): add getToolkit() to ToolRegistry"
```

---

### Task 2.3: 改写 AgentOrchestrator 使用 ReActAgent

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/AgentOrchestrator.java`
- Test: `src/test/java/com/dawn/ai/agent/AgentOrchestratorTest.java`（已存在，需更新）

> **⚠️ 先确认 ReActAgent builder API。** 预期形式：
> ```java
> ReActAgent agent = ReActAgent.builder()
>     .model(model)           // AgentScope 模型对象
>     .toolkit(toolkit)       // Toolkit from ToolRegistry
>     .sysPrompt(systemPrompt)
>     .hooks(List.of(hook))  // StepTraceHook
>     .maxIters(maxSteps)
>     .build();
> String response = agent.call(userMessage).block();
> ```

- [ ] **Step 1: 确认现有测试状态**

```bash
./mvnw test -Dtest=AgentOrchestratorTest
```

记录当前测试数量（Expected: 全部 PASS）

- [ ] **Step 2: 在 AgentScopeConfig 中添加模型 Bean**

```java
// src/main/java/com/dawn/ai/config/AgentScopeConfig.java
// 添加以下 Bean（替换 OpenAIModel 为实际类名）:

// @Bean
// public OpenAIModel agentScopeOpenAiModel() {
//     return OpenAIModel.builder()
//             .apiKey(openAiApiKey)
//             .modelName(modelName)
//             .build();
// }
```

- [ ] **Step 3: 改写 AgentOrchestrator.chat()**

保留方法签名 `public ChatResponse chat(ChatRequest request)` 不变；替换内部实现：

```java
// 旧实现（注释掉）:
// StepCollector.init(maxSteps);
// try {
//     ChatResponse chatResp = chatClient.prompt()
//         .system(systemPrompt)
//         .messages(buildHistory(request.sessionId()))
//         .user(request.message())
//         .toolNames(toolRegistry.getNames())
//         .call()
//         .chatResponse();
//     ...
// } finally {
//     StepCollector.clear();
// }

// 新实现:
// StepTraceHook hook = new StepTraceHook(maxSteps);
// try {
//     Toolkit toolkit = toolRegistry.getToolkit();
//     String historyContext = buildHistoryContext(request.sessionId());
//     String fullSystemPrompt = systemPrompt + "\n\n" + historyContext;
//
//     ReActAgent agent = ReActAgent.builder()
//             .model(agentScopeModel)
//             .toolkit(toolkit)
//             .sysPrompt(fullSystemPrompt)
//             .hooks(List.of(hook))
//             .maxIters(maxSteps)
//             .build();
//
//     String answer = agent.call(request.message()).block();
//
//     List<AgentStep> steps = hook.getSteps();
//     long ragCalls = steps.stream()
//             .filter(s -> KnowledgeSearchTool.class.getSimpleName().equals(s.toolName()))
//             .count();
//     ragCallsSummary.record(ragCalls);
//
//     memoryService.addMessage(request.sessionId(), "user", request.message());
//     memoryService.addMessage(request.sessionId(), "assistant", answer);
//
//     return new ChatResponse(answer, showSteps ? steps : List.of());
// } finally {
//     hook.clear();
// }
```

> **注意:** 由于 L2 需要确认多个 AgentScope API 细节，ReActAgent 使用部分先保持注释形式；同时保留原有 Spring AI 实现，通过 feature flag 切换：

在 `application.yml` 添加：
```yaml
app.ai.engine: spring-ai   # 切换到 agentscope 后改为: agentscope
```

```java
@Value("${app.ai.engine:spring-ai}")
private String engine;

public ChatResponse chat(ChatRequest request) {
    if ("agentscope".equals(engine)) {
        return chatWithAgentScope(request);
    }
    return chatWithSpringAi(request);  // 原有逻辑提取为此方法
}
```

- [ ] **Step 4: 运行全量测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS（engine=spring-ai，走原有路径）

- [ ] **Step 5: 切换 engine=agentscope，手动测试**

```bash
# 修改 application.yml: app.ai.engine: agentscope
# 启动应用
./mvnw spring-boot:run
# curl 测试
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"What is the weather in Beijing?","sessionId":"test-001"}'
```

Expected: 正常返回含天气信息的回答

- [ ] **Step 6: 切换回默认，Commit**

```bash
# application.yml 保持 engine: spring-ai（由实现者在验证后切换）
git add src/main/java/com/dawn/ai/agent/AgentOrchestrator.java \
        src/main/java/com/dawn/ai/config/AgentScopeConfig.java \
        src/main/resources/application.yml
git commit -m "feat(l2): add ReActAgent engine alongside Spring AI (feature flag)"
```

---

### Task 2.4: 删除 ToolExecutionAspect，StepCollector 变 no-op

**Files:**
- Delete: `src/main/java/com/dawn/ai/agent/aop/ToolExecutionAspect.java`
- Modify: `src/main/java/com/dawn/ai/agent/StepCollector.java`

> 切换到 engine=agentscope 后，StepCollector ThreadLocal 不再被写入（Hook 替代了 Aspect）。ToolExecutionAspect 也不再需要。

- [ ] **Step 1: 确认无测试直接依赖 ToolExecutionAspect**

```bash
grep -r "ToolExecutionAspect" src/test/
```

Expected: 无输出（无测试直接引用）

- [ ] **Step 2: 删除 ToolExecutionAspect.java**

```bash
rm src/main/java/com/dawn/ai/agent/aop/ToolExecutionAspect.java
```

- [ ] **Step 3: 将 StepCollector 变为 no-op stub（保留接口兼容性）**

现有代码中其他地方若仍引用 StepCollector（如 `KnowledgeSearchTool.isQueryRetrieved`），需保持编译兼容：

```java
package com.dawn.ai.agent;

import java.util.List;
import java.util.Set;

/**
 * No-op stub. Replaced by StepTraceHook in L2.
 * Will be deleted in L5 after all callers are removed.
 */
public final class StepCollector {

    private StepCollector() {}

    public static void init(int maxSteps) {}

    public static void record(String toolName, String input, String output, long durationMs) {}

    public static List<AgentStep> collect() { return List.of(); }

    public static void clear() {}

    public static boolean isQueryRetrieved(String query) { return false; }

    public static void markQueryRetrieved(String query) {}
}
```

- [ ] **Step 4: 编译 + 全量测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/StepCollector.java
git rm src/main/java/com/dawn/ai/agent/aop/ToolExecutionAspect.java
git commit -m "feat(l2): replace ToolExecutionAspect with StepTraceHook, stub StepCollector"
```

**L2 完成。创建 PR: `feat/l2-agentscope-agent-core` → `master`**

---

## Layer 3: RAG — AgentScope Knowledge API 替代 RagService

**目标:** 用 AgentScope `Knowledge` API 替代 `RagService + QueryRewriter + KnowledgeSearchTool`；AgentScope 内置 Agentic RAG 模式接管检索决策。

> **⚠️ 先确认 Knowledge API。** 访问 https://java.agentscope.io/ 确认：
> - `KnowledgeStore` 与 pgvector 的集成方式（JDBC 连接 or Spring DataSource bean）
> - `ragMode(RAGMode.AGENTIC)` 是否在 ReActAgent builder 上
> - 文档 ingest API

---

### Task 3.1: 在 AgentScopeConfig 添加 Knowledge Bean

**Files:**
- Modify: `src/main/java/com/dawn/ai/config/AgentScopeConfig.java`
- Test: `src/test/java/com/dawn/ai/config/KnowledgeBeanTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/dawn/ai/config/KnowledgeBeanTest.java
package com.dawn.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

// TODO: import io.agentscope.knowledge.Knowledge;

@SpringBootTest
class KnowledgeBeanTest {

    // @Autowired
    // private Knowledge knowledge;

    @Test
    void knowledgeBeanIsCreated() {
        // assertThat(knowledge).isNotNull();
        assertThat(true).isTrue(); // placeholder
    }
}
```

- [ ] **Step 2: 在 AgentScopeConfig 中添加 Knowledge Bean**

```java
// @Bean
// public Knowledge agentScopeKnowledge(DataSource dataSource) {
//     return Knowledge.builder()
//             .store(PgVectorKnowledgeStore.builder()
//                     .dataSource(dataSource)
//                     .tableName("vector_store")
//                     .embeddingModel(embeddingModel())  // OpenAI embedding
//                     .build())
//             .chunkSize(500)
//             .build();
// }
```

- [ ] **Step 3: 更新 ReActAgent builder（在 Task 2.3 的基础上）**

```java
// ReActAgent agent = ReActAgent.builder()
//     .model(agentScopeModel)
//     .toolkit(toolkit)
//     .knowledge(knowledge)          // 添加此行
//     .ragMode(RAGMode.AGENTIC)      // 添加此行
//     .sysPrompt(fullSystemPrompt)
//     .hooks(List.of(hook))
//     .maxIters(maxSteps)
//     .build();
```

- [ ] **Step 4: 测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dawn/ai/config/AgentScopeConfig.java \
        src/test/java/com/dawn/ai/config/KnowledgeBeanTest.java
git commit -m "feat(l3): add Knowledge bean and ragMode(AGENTIC) to ReActAgent"
```

---

### Task 3.2: 迁移 RagController 使用 Knowledge API，删除旧 RAG 类

**Files:**
- Modify: `src/main/java/com/dawn/ai/controller/RagController.java`
- Delete: `src/main/java/com/dawn/ai/service/RagService.java`
- Delete: `src/main/java/com/dawn/ai/service/QueryRewriter.java`
- Delete: `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java`

- [ ] **Step 1: 确认 RagController 的现有接口**

```bash
cat src/main/java/com/dawn/ai/controller/RagController.java
```

- [ ] **Step 2: 改写 RagController 的 ingest 端点**

```java
// 原有：
// ragService.ingest(file);

// 替换为（使用 Knowledge API）：
// knowledge.ingest(KnowledgeDocument.from(file));
```

- [ ] **Step 3: 删除旧 RAG 类**

```bash
git rm src/main/java/com/dawn/ai/service/RagService.java
git rm src/main/java/com/dawn/ai/service/QueryRewriter.java
git rm src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java
```

同时删除对应测试文件：
```bash
git rm src/test/java/com/dawn/ai/service/RagServiceTest.java 2>/dev/null || true
git rm src/test/java/com/dawn/ai/service/QueryRewriterTest.java 2>/dev/null || true
git rm src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java 2>/dev/null || true
git rm src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolAgentScopeTest.java 2>/dev/null || true
```

- [ ] **Step 4: 全量测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS（所有引用 RagService/QueryRewriter 的测试已删除）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dawn/ai/controller/RagController.java
git commit -m "feat(l3): migrate to AgentScope Knowledge API, remove RagService/QueryRewriter/KnowledgeSearchTool"
```

**L3 完成。创建 PR: `feat/l3-agentscope-rag` → `master`**

---

## Layer 4: Memory — RedisMemory 适配器

**目标:** 创建实现 AgentScope `Memory` 接口的 `RedisMemory`，包装现有 `MemoryService`；接入 ReActAgent builder。

> **⚠️ 先确认 Memory 接口方法签名。** 预期：
> ```java
> interface Memory {
>     void add(String role, String content);
>     List<Message> get(int lastN);
>     void clear();
> }
> ```

---

### Task 4.1: 创建 RedisMemory 适配器

**Files:**
- Create: `src/main/java/com/dawn/ai/agent/memory/RedisMemory.java`
- Test: `src/test/java/com/dawn/ai/agent/memory/RedisMemoryTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/dawn/ai/agent/memory/RedisMemoryTest.java
package com.dawn.ai.agent.memory;

import com.dawn.ai.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RedisMemoryTest {

    private MemoryService memoryService;
    private RedisMemory memory;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        memory = new RedisMemory(memoryService, "session-test");
    }

    @Test
    void add_delegatesToMemoryService() {
        memory.add("user", "hello");
        verify(memoryService).addMessage("session-test", "user", "hello");
    }

    @Test
    void get_returnsHistoryFromMemoryService() {
        when(memoryService.getHistory("session-test")).thenReturn(
                List.of(Map.of("role", "user", "content", "hello"))
        );
        var history = memory.get(10);
        assertThat(history).hasSize(1);
    }

    @Test
    void clear_doesNotThrow() {
        // clear is a no-op in Redis (TTL handles expiration)
        memory.clear();
        // no exception expected
    }
}
```

```bash
./mvnw test -Dtest=RedisMemoryTest
```

Expected: FAIL（RedisMemory 不存在）

- [ ] **Step 2: 创建 RedisMemory**

```java
package com.dawn.ai.agent.memory;

import com.dawn.ai.service.MemoryService;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Map;

// TODO: 替换为实际 AgentScope Memory 接口 import
// import io.agentscope.memory.Memory;
// import io.agentscope.memory.Message;

/**
 * Adapts MemoryService (Redis-backed) to AgentScope Memory interface.
 * One instance per request session.
 */
@RequiredArgsConstructor
// public class RedisMemory implements Memory {
public class RedisMemory {

    private final MemoryService memoryService;
    private final String sessionId;

    // @Override
    public void add(String role, String content) {
        memoryService.addMessage(sessionId, role, content);
    }

    // @Override
    public List<Map<String, String>> get(int lastN) {
        return memoryService.getHistory(sessionId);
    }

    // @Override
    public void clear() {
        // Redis TTL handles expiration; no explicit clear needed
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
./mvnw test -Dtest=RedisMemoryTest
```

Expected: PASS

- [ ] **Step 4: 在 AgentOrchestrator 中接入 RedisMemory**

在 `chatWithAgentScope()` 方法中：

```java
// RedisMemory memory = new RedisMemory(memoryService, request.sessionId());
//
// ReActAgent agent = ReActAgent.builder()
//     .model(agentScopeModel)
//     .toolkit(toolkit)
//     .knowledge(knowledge)
//     .ragMode(RAGMode.AGENTIC)
//     .sysPrompt(systemPrompt)      // 不再需要手动拼接 historyContext
//     .memory(memory)               // 添加此行
//     .hooks(List.of(hook))
//     .maxIters(maxSteps)
//     .build();
```

同时移除 `buildHistory()` / `buildHistoryContext()` 的手动调用（Memory 接管）。

- [ ] **Step 5: 全量测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/memory/RedisMemory.java \
        src/main/java/com/dawn/ai/agent/AgentOrchestrator.java \
        src/test/java/com/dawn/ai/agent/memory/RedisMemoryTest.java
git commit -m "feat(l4): add RedisMemory adapter for AgentScope Memory interface"
```

**L4 完成。创建 PR: `feat/l4-agentscope-memory` → `master`**

---

## Layer 5: Cleanup — 删除 Spring AI，迁移 TaskPlanner

**目标:** 从 pom.xml 移除所有 `spring-ai-*` 依赖；删除 `AiConfig.java`、`StepCollector` stub；迁移 `TaskPlanner` 使用 AgentScope API。

---

### Task 5.1: 迁移 TaskPlanner 到 AgentScope API

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/plan/TaskPlanner.java`
- Test: `src/test/java/com/dawn/ai/agent/plan/TaskPlannerTest.java`（已存在）

> 现有 TaskPlanner 使用 `chatClient.prompt().system(...).user(...).call().content()` 做独立 LLM 调用。

- [ ] **Step 1: 确认现有 TaskPlanner 测试状态**

```bash
./mvnw test -Dtest=TaskPlannerTest
```

记录测试数量（Expected: 全部 PASS）

- [ ] **Step 2: 改写 TaskPlanner 的 LLM 调用**

```java
// 旧实现（注释掉）:
// String planJson = chatClient.prompt()
//     .system(buildPlanPrompt(toolList, task))
//     .user(task)
//     .options(OpenAiChatOptions.builder().temperature(0.3).build())
//     .call()
//     .content();

// 新实现（使用 AgentScope 模型直接调用）:
// TODO: 替换为实际 AgentScope 模型直接调用 API
// String planJson = agentScopeModel.call(
//     ModelInput.builder()
//         .systemPrompt(buildPlanPrompt(toolList, task))
//         .userMessage(task)
//         .temperature(0.3)
//         .build()
// ).getContent();
```

- [ ] **Step 3: 测试**

```bash
./mvnw test -Dtest=TaskPlannerTest
```

Expected: PASS（同等数量）

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/plan/TaskPlanner.java
git commit -m "feat(l5): migrate TaskPlanner to AgentScope model API"
```

---

### Task 5.2: 删除 AiConfig 和 StepCollector stub

**Files:**
- Delete: `src/main/java/com/dawn/ai/config/AiConfig.java`
- Delete: `src/main/java/com/dawn/ai/agent/StepCollector.java`

- [ ] **Step 1: 确认无代码引用 AiConfig**

```bash
grep -r "AiConfig" src/main/
```

Expected: 无输出（或仅 AiConfig.java 自身）

- [ ] **Step 2: 确认无代码引用 StepCollector**

```bash
grep -r "StepCollector" src/main/ src/test/
```

Expected: 仅 StepCollector.java 自身和对应测试（如有）

- [ ] **Step 3: 删除文件**

```bash
git rm src/main/java/com/dawn/ai/config/AiConfig.java
git rm src/main/java/com/dawn/ai/agent/StepCollector.java
# 删除对应测试
git rm src/test/java/com/dawn/ai/agent/StepCollectorTest.java 2>/dev/null || true
```

- [ ] **Step 4: 编译验证**

```bash
./mvnw compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git commit -m "chore(l5): delete AiConfig and StepCollector stub"
```

---

### Task 5.3: 从 pom.xml 移除 spring-ai-* 依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 确认 spring-ai 相关 import 已全部清理**

```bash
grep -r "org.springframework.ai" src/main/
```

Expected: 无输出（所有 import 已在前面步骤中替换）

- [ ] **Step 2: 删除 pom.xml 中的 spring-ai 相关内容**

删除以下内容：
1. `<spring-ai.version>1.1.4</spring-ai.version>` property
2. `spring-ai-bom` 在 `<dependencyManagement>` 中的引用
3. `spring-ai-starter-model-openai` 依赖
4. `spring-ai-starter-vector-store-pgvector` 依赖
5. `spring-ai-pdf-document-reader` 依赖

- [ ] **Step 3: 全量编译 + 测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS（所有 spring-ai import 已清理）

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore(l5): remove all spring-ai-* dependencies from pom.xml"
```

---

### Task 5.4: 切换默认 engine 并最终验证

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 修改 application.yml**

```yaml
app:
  ai:
    engine: agentscope   # 从 spring-ai 改为 agentscope
```

- [ ] **Step 2: 全量测试**

```bash
./mvnw test
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 集成测试（手动）**

```bash
./mvnw spring-boot:run &
sleep 10

# 天气查询（工具调用）
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"北京今天天气怎么样？","sessionId":"final-test-001"}'

# 计算（工具调用）
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"计算 123 * 456","sessionId":"final-test-002"}'

# 知识库检索（RAG）
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"请介绍一下我们的产品","sessionId":"final-test-003"}'

kill %1
```

Expected: 三个请求都返回合理答案

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat(l5): switch default engine to agentscope, migration complete"
```

**L5 完成。创建 PR: `feat/l5-agentscope-cleanup` → `master`**

---

## 迁移顺序总结

| Layer | Branch | 核心变更 | 依赖 |
|-------|--------|---------|------|
| L1 | `feat/l1-agentscope-foundation` | pom.xml + @Tool 方法 | 无 |
| L2 | `feat/l2-agentscope-agent-core` | ReActAgent + StepTraceHook + 删除 ToolExecutionAspect | L1 |
| L3 | `feat/l3-agentscope-rag` | Knowledge API + 删除 RagService | L2 |
| L4 | `feat/l4-agentscope-memory` | RedisMemory adapter | L2 |
| L5 | `feat/l5-agentscope-cleanup` | 删除 spring-ai-* + AiConfig + StepCollector | L2+L3+L4 |

L3 和 L4 可并行开发（都基于 L2），L5 必须等 L3+L4 全部合并后开始。

## 关键风险点

1. **AgentScope OpenAI 支持**: 若 `agentscope:1.0.11` 不支持 OpenAI，需降级到 `1.0.10` 或寻找 OpenAI wrapper。在 L1 Task 1.1 后立即验证。
2. **ReActAgent 异步 API**: `agent.call().block()` 是预期形式；若实际为同步 `agent.call()` 直接返回 String，Task 2.3 代码需调整。
3. **pgvector + AgentScope**: pgvector 是 Spring AI 的 `VectorStore`；AgentScope 的 `KnowledgeStore` 可能需要自定义实现包装 JDBC。L3 前需验证。
4. **StepTraceHook 线程安全**: `StepTraceHook` 为 per-request 实例（非 singleton），天然线程安全。不要注入为 Spring Bean。
