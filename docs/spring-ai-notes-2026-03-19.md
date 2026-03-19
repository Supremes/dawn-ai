# Spring AI 学习笔记

> 日期：2026-03-19  
> 背景：dawn-ai 项目 ReAct 改造后的技术问答整理

---

## 1. Spring AI 是如何调用 Tool 的？

### 工具的两个必要条件

```java
@Component                                         // ① 注册为 Spring Bean
@Description("Get current weather...")             // ② 提供描述给 LLM
public class WeatherTool implements Function<WeatherTool.Request, WeatherTool.Response> {
    @Override
    public Response apply(Request request) { ... } // ③ 实现 apply()
}
```

**最低要求：`@Component` + `implements Function<Req, Resp>` + `@Description`**

`@Description` 的内容会作为 function description 发给 LLM，LLM 据此判断什么时候该调这个工具。没有它，LLM 不知道工具是干什么的。

### `.toolNames("weatherTool")` 做了什么

```
.toolNames("weatherTool")
    ↓
Spring AI 从 ApplicationContext 按 Bean name 找到 WeatherTool
    ↓
反射 Request 的字段（city: String），自动生成 JSON Schema：
  { "type": "object", "properties": { "city": { "type": "string" } } }
    ↓
把 [工具名 + @Description + JSON Schema] 作为 tools 数组发给 OpenAI API
```

实际发出的 HTTP body 类似：
```json
{
  "tools": [{
    "type": "function",
    "function": {
      "name": "weatherTool",
      "description": "Get current weather information for a city. Input: city name",
      "parameters": {
        "type": "object",
        "properties": { "city": { "type": "string" } },
        "required": ["city"]
      }
    }
  }]
}
```

### LLM 返回 tool_call 后的执行流程

```
OpenAI 返回：
  { "finish_reason": "tool_calls",
    "tool_calls": [{ "name": "weatherTool", "arguments": "{\"city\":\"beijing\"}" }] }
    ↓
Spring AI 解析 arguments JSON → 反序列化为 WeatherTool.Request("beijing")
    ↓
调用 weatherTool.apply(request)       ← AOP 在这里拦截（ToolExecutionAspect）
    ↓
把 Response 序列化为 JSON 作为 tool message 塞回对话
    ↓
再次调用 LLM（带上 tool result）
    ↓
LLM 返回 finish_reason = "stop" → 循环终止
```

### Bean name 的来源

`toolNames("weatherTool")` 里的字符串就是 **Spring Bean 的名字**，默认是类名首字母小写。也可以用 `@Component("myWeather")` 自定义。

**总结：注册为 Bean + 传入 toolNames 就够了**，JSON Schema 是 Spring AI 自动从泛型参数反射生成的，不需要任何额外配置。

---

## 2. `.call().content()` 背后有多少次 LLM 请求？

**一次 `.call().content()` 在代码层面是一行同步阻塞调用，但背后可能是多次 LLM HTTP request。**

以"查询北京天气"为例：

```
代码调用 .call().content()
    │
    ▼  HTTP Request #1 ──────────────────────────────►  OpenAI
       { messages: [...], tools: [weatherTool, calculatorTool] }
                                                        LLM 决定调工具
    ◄────────────────────────────────────────────────
       { finish_reason: "tool_calls",
         tool_calls: [{ name: "weatherTool", args: {"city":"beijing"} }] }
    │
    ▼  Spring AI 本地执行 weatherTool.apply(...)  （不走网络）
    │
    ▼  HTTP Request #2 ──────────────────────────────►  OpenAI
       { messages: [...原始消息..., tool_call结果] }
                                                        LLM 总结答案
    ◄────────────────────────────────────────────────
       { finish_reason: "stop",
         content: "北京今天多云，12°C" }
    │
    ▼
返回给代码："北京今天多云，12°C"
```

### 关键结论

| 维度 | 说明 |
|------|------|
| **代码层** | 1 次阻塞调用，`.call()` 不返回直到所有轮次完成 |
| **LLM Request 层** | N+1 次 HTTP 请求（N = 实际工具调用次数） |
| **计费层** | 每次 HTTP request 都消耗 token，且越往后消耗越多（消息历史越来越长） |
| **延迟层** | 总延迟 = Σ 每次 LLM 响应时间，串行累加 |

如果任务需要调 3 个工具，就会产生 4 次 HTTP 请求。这也是为什么要加 `max-steps` 约束的原因。

---

## 3. Spring AI Structured Outputs 三种方案

### 方案一：Prompt 约束（当前 TaskPlanner 用的）

```java
// 靠 prompt 要求，LLM 不保证遵守，需要自己 extractJson()
String raw = chatClient.prompt().user(prompt).call().content();
```

**可靠性：低**，LLM 可能加前缀文字、包 markdown fence、字段名拼错。

---

### 方案二：`BeanOutputConverter` —— Spring AI 的 Prompt 级增强

Spring AI 自动从 Java 类生成 JSON Schema，注入到 prompt 末尾，再做反序列化：

```java
// 优雅写法：直接用 .entity()
List<PlanStep> plan = chatClient.prompt()
    .user(prompt)
    .call()
    .entity(new ParameterizedTypeReference<List<PlanStep>>() {});
```

**底层：** `BeanOutputConverter.getFormat()` 输出 schema 描述追加到 prompt，convert() 做反序列化。  
**可靠性：中**，本质还是 prompt 约束，但 schema 更精确。

---

### 方案三：OpenAI Structured Outputs —— 模型层硬约束

```java
import org.springframework.ai.openai.api.ResponseFormat;

OpenAiChatOptions options = OpenAiChatOptions.builder()
    .responseFormat(ResponseFormat.builder()
        .type(ResponseFormat.Type.JSON_SCHEMA)   // 关键
        .jsonSchema(ResponseFormat.JsonSchema.builder()
            .name("PlanStepList")
            .schema(converter.getJsonSchemaMap())
            .strict(true)                        // 严格模式，100% 强制遵守
            .build())
        .build())
    .temperature(0.3)
    .build();
```

**底层原理：** schema 放在请求体的 `response_format` 字段，OpenAI 在**模型采样层面**强制约束输出 token 必须符合 schema，LLM 物理上无法输出不合规内容。

`ResponseFormat.Type` 枚举值：`TEXT` / `JSON_OBJECT` / `JSON_SCHEMA`

### 三种方案对比

| | Prompt 约束 | BeanOutputConverter | ResponseFormat JSON_SCHEMA |
|--|--|--|--|
| 可靠性 | 低 | 中 | 极高 |
| 约束层级 | Prompt | Prompt + Schema | 模型采样层 |
| 需要 schema | 手写 | 自动生成 | 自动生成 |
| 模型要求 | 无 | 无 | gpt-4o 以上 |
| 当前项目使用 | ✅ TaskPlanner | ❌ | ❌ |

**建议**：TaskPlanner 可升级到方案三，把 `extractJson()` 的脆弱解析去掉，换成 `strict=true`，输出一定是合法 JSON。

---

## 4. DeepSeek 对三种方案的支持情况

### 支持矩阵

| 模型 | 方案一（Prompt） | 方案二（BeanOutputConverter） | 方案三（JSON_SCHEMA strict） |
|------|:-:|:-:|:-:|
| DeepSeek-V3 | ✅ | ✅ | ✅ |
| DeepSeek-R1 | ✅ | ✅ | ❌ |
| DeepSeek-V2 | ✅ | ✅ | ❌ |

**DeepSeek-R1 的特殊限制：**  
R1 是推理模型，内部有 `<think>` 推理链，官方文档明确说明**不建议对 R1 使用 `response_format`**，因为推理过程和结构化输出约束会相互干扰，输出不稳定。

### 切换到 DeepSeek 只需改 yml

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com   # 换 base-url
      chat:
        options:
          model: deepseek-chat             # deepseek-chat = V3
```

当前项目使用 `spring-ai-starter-model-openai`，DeepSeek API 兼容 OpenAI 格式，`ResponseFormat.JSON_SCHEMA` 参数会原样发给 DeepSeek API，**DeepSeek-V3 能正确处理**。

---

## 5. Agent 是如何判断执行完毕的？

**当前项目没有自己判断「执行是否完毕」，这个判断完全外包给了 Spring AI 框架。**

### Spring AI `.toolNames()` 的内部循环终止条件

Spring AI 在内部实现了一个 tool-calling loop，终止条件由 LLM 自己决定：

```
LLM 收到消息
  ↓
LLM 返回 tool_call（要调 weatherTool）→ 执行工具 → 把结果塞回给 LLM
  ↓
LLM 再次推理
  ↓
LLM 返回 stop_reason = "stop"（不再调工具）→ 循环退出
  ↑
这一步就是"判断完毕"，由 LLM 决定，Spring AI 识别 finish_reason
```

**LLM 返回 `finish_reason = stop`（而不是 `tool_calls`）时，Spring AI 就认为执行完毕**，把最终文本返回给 `chatClient.prompt()...call().content()`。

### 项目里唯一的"保护机制"是 prompt 级别的软约束

```java
// AgentOrchestrator.java
+ String.format("%n请在回复中简短说明每次工具调用的原因。最多调用工具 %d 次。", maxSteps);
```

这只是告诉 LLM「最多 10 次」，**LLM 不一定遵守**，没有代码级别的硬中断。

### 现有风险

| 场景 | 当前行为 |
|------|---------|
| LLM 正常工作 | Spring AI 检测到 `finish_reason=stop` 后退出 ✅ |
| LLM 在工具间无限循环 | 没有代码层硬限制，只靠 prompt 提示 ⚠️ |
| 工具抛异常 | Spring AI 把异常信息回传给 LLM，LLM 决定是否重试 |

### 如果要做硬性保护

**❌ 错误思路：在 `.call()` 之后检查步骤数**

```java
// 这无法限制循环次数！
List<AgentStep> steps = StepCollector.collect();  // .call() 已经返回，循环已结束
if (steps.size() >= maxSteps) { ... }             // 为时已晚
```

`.call().content()` 是同步阻塞的黑盒，整个 ReAct 循环在内部完成后才返回，`collect()` 只能用于**观测**，不能用于**控制**。

**✅ 正确方案：在 AOP 切面里拦截**

AOP 切面在 Spring AI 工具调用**中途**执行，是真正能"插入"循环的位置。当超限时，不调用 `pjp.proceed()`，直接返回终止信号给 LLM：

```java
// ToolExecutionAspect.java
@Around("execution(* com.dawn.ai.agent.tools.*.apply(..))")
public Object captureStep(ProceedingJoinPoint pjp) throws Throwable {
    int stepNum = StepCollector.nextStepNumber();

    // 超限时不执行工具，返回停止信号给 LLM
    if (stepNum > StepCollector.getMaxSteps()) {
        log.warn("[ReAct] 已达到 maxSteps 上限，返回终止信号");
        return "已达到最大工具调用次数限制，请根据已有信息直接给出最终答案，不要再调用工具。";
    }

    // 正常执行
    long start = System.currentTimeMillis();
    Object result = pjp.proceed();
    // ...
}
```

LLM 收到这个字符串作为工具返回值，会自然地产出最终答案（`finish_reason=stop`），循环终止。  
`maxSteps` 通过 `StepCollector` 的 ThreadLocal 传递（在 `init(maxSteps)` 时存入）。

| | AOP 返回终止信号（推荐）| 抛异常强制中断 |
|--|--|--|
| LLM 能否产出最终答案 | ✅ 基于已有结果总结 | ❌ 直接截断 |
| 代码侵入性 | 低 | 中 |
