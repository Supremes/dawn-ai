---
updated: 2026-06-22
---
# Spring AI 版本升级分析报告

> **当前版本**：1.1.4 | **Spring Boot**：3.2.5 | **Java**：17

> **结论**：**立即升级到 1.1.7**（零代码修改），2.0 等 GA 稳定后再评估。

---

## 一、1.1.5（向后兼容，零代码修改）

| 类别 | 内容 | 对项目影响 |
|------|------|-----------|
| **Bug 修复** | PgVector 过滤表达式 `doKey()` SQL 单引号转义修复（+54/-7 行） | **直接影响** `MemoryManager.search()` 的 `FilterExpressionBuilder.eq()` |
| **Bug 修复** | OpenAI `extra_body` 参数泄露修复 | 影响 OpenAI 集成，升级后自动解决 |
| **Bug 修复** | `VectorStoreChatMemoryAdvisor` conversationId 过滤修复 | 项目用自定义 MemoryManager，无影响 |
| **安全修复** | 恶意 PDF 拒绝服务漏洞 | 项目不使用 Spring AI PDF 阅读器，无影响 |
| **安全修复** | Transformer 模型缓存目录加固 | 项目使用 OpenAI 嵌入模型，无影响 |

---

## 二、1.1.6（向后兼容，零代码修改）

| 类别 | 内容 | 对项目影响 |
|------|------|-----------|
| **Breaking** | `ChatMemoryAdvisor` 需要显式 `conversationId`，`DEFAULT_CONVERSATION_ID` 被删除 | 项目不使用此 Advisor，**无影响** |
| **Bug 修复** | Milvus/Typesense/MistralAI/Ollama 相关修复 | 项目用 OpenAI + PGVector，**全部无影响** |
| **内部重构** | Starter 模块迁移到 starters/ 目录 | Maven artifact 坐标不变，无影响 |

---

## 三、1.1.7（向后兼容，零代码修改）

| 类别 | 内容 | 对项目影响 |
|------|------|-----------|
| **关键修复** | **流式推理 `switchMap` → `concatMap` 修复** — 背压场景下 token 被静默丢弃 | **直接影响项目核心** `AgentOrchestrator.streamChat()` 的 SSE 管道 |
| **Breaking** | OpenAI Image API 默认模型改为 `gpt-image-1-mini`，DALL_E 枚举值被删除 | 项目不使用 Image API，无影响 |
| **安全修复** | Anthropic Skills 路径穿越漏洞 | 项目不用 Anthropic，无影响 |

### 关键修复详情：switchMap → concatMap

所有 ChatModel 流式实现中的 `switchMap` 被替换为 `concatMap/map`。`switchMap` 操作符在下游施加背压时会急切取消正在处理的项，导致流式 token 被**静默丢弃**。

- 修复涵盖 OpenAI、Bedrock、DeepSeek、MistralAI 等所有模型
- 已添加 300-chunk 背压下的排序回归测试
- **直接影响**项目 `AgentOrchestrator.streamChat()` 第 218-250 行 `chatClient.prompt().stream().chatResponse()` 的 SSE 流式管道
- 在 tool-calling 循环或背压场景下可能静默丢失 token

### 升级方式

仅需修改 `pom.xml` 一行：

```xml
<spring-ai.version>1.1.7</spring-ai.version>
```

全部 API（ChatClient、Tool 系统、VectorStore、Embedding、Observability、消息类型、BeanOutputConverter、Document）均无变化，完全向后兼容。

---

## 四、2.0-RC（大量破坏性变更，不建议现在升级）

**发布时间**：RC2 2026-06-09，GA 预计 2026-06-14

**前置条件**：Spring Boot 3.2.5 → **4.0+**（Spring Framework 7.0+，Jakarta EE 11），Jackson 2 → **3**（包名 `com.fasterxml.jackson` → `tools.jackson`）

### 破坏性变更清单

| 变更 | 对项目的具体影响 | 改造量 |
|------|-----------------|--------|
| **Spring Boot 4.0 基线** | 项目当前 3.2.5 不兼容，必须升级。推荐路径：先升到 3.5.x 解决 deprecation，再迁 4.0 | **阻塞** |
| **Jackson 3 迁移** | 项目 10+ 文件使用 `com.fasterxml.jackson.databind.*`，需全部改为 `tools.jackson.databind.*`。ObjectMapper 变不可变，需改 builder 模式 | **阻塞** |
| **`toolNames()` 被彻底删除** | `AgentOrchestrator.java:222` `.toolNames(toolNames)` 和 `GenericReActSubAgentExecutor.java:214` `.toolNames(...)` 必须重构为 `.tools(ToolCallback[])`。需改造 ToolRegistry 返回 ToolCallback 实例而非 Bean 名称 | **重大** |
| **`OpenAiApi` 类被删除** | 改用官方 openai-java SDK。`JudgeService.java`（测试）直接使用 `OpenAiApi.builder()`，需完全重写为 `OpenAIOkHttpClient.builder()` | **重大** |
| **ChatModel 内部 tool 执行被移除** | 必须使用 `ToolCallingAdvisor`（原 `ToolCallAdvisor`）。需确认 Agent 编排流程兼容 | **中等** |
| **Observability 机制变更** | 配置属性重命名：`include-prompt`→`log-prompt`。`LangfuseObservationConfig.java` 需适配 logging-based 观测 | **中等** |
| **`spring-ai-core` 拆分** | 拆为 `spring-ai-commons`/`spring-ai-model`/`spring-ai-vector-store`/`spring-ai-client-chat`。import 路径可能变化 | **低** |
| **ChatOptions setter 删除** | 必须使用 builder 模式。项目已全部使用 `OpenAiChatOptions.builder()`，**无需修改** | **无影响** |
| **VectorStore.delete() 返回 void** | 失败时抛异常而非返回 `Optional<Boolean>`。项目在 try-catch 中不使用返回值，**无影响** | **无影响** |
| **Generation API 变更** | `getGenerationTokens()` → `getCompletionTokens()`，token 计数 Long→Integer，`getText()` 可返回 null，温度 Float→Double | **低** |

### 2.0 新能力亮点

| 特性 | 说明 | 与项目的关系 |
|------|------|-------------|
| **ToolCallingAdvisor** | 成为标准 tool 调用处理器，ChatClient.tools() 直接接受 ToolCallback/ToolCallbackProvider | 可简化 LoadSkillToolCallbackProvider 的注册模式 |
| **ToolSearchToolCallingAdvisor** | 三种 ToolIndex（向量/Lucene/正则），LLM 按需发现工具 | 项目可实现动态工具发现 |
| **官方 OpenAI Java SDK** | 替代内部 OpenAiApi，默认模型更新为 GPT-5 Mini | 更好的 OpenAI 新功能兼容性 |
| **Session API + AutoMemoryTools** | 事件溯源短期记忆 + 基于文件的跨会话长期记忆 | 可替代或补充现有 MemoryManager |
| **MCP 集成迁入核心** | SSE transport 废弃，改用 Streamable HTTP | 项目当前不使用 MCP，未来可采用 |

---

## 五、升级建议

| 维度 | 1.1.7 | 2.0 GA |
|------|-------|--------|
| **推荐度** | **立即升级** | 等 GA 后评估 |
| **改造量** | 零代码修改 | 大量（至少 6 个核心文件需重构） |
| **紧迫性** | 高 — 流式 token 丢失是生产级 bug | 低 — RC 阶段，API 可能继续变 |
| **前置条件** | 无 | Spring Boot 4.0 + Jackson 3 |
| **风险** | 极低 | 高（全链路验证） |

### 行动计划

**现在（1.1.7）**：
1. `pom.xml` 改版本号 `1.1.4` → `1.1.7`
2. `mvn clean compile` 验证编译
3. 跑全量测试确认无回归
4. 测试环境验证 SSE 流式 token 完整性（特别是 tool-calling 循环场景）

**未来（2.0 GA 稳定后）**：
1. 非生产环境先行验证
2. 制定分阶段迁移计划：Spring Boot 升级 → Jackson 3 迁移 → Spring AI 2.0 API 适配
3. 重点重构：ToolRegistry + AgentOrchestrator（toolNames→tools）、JudgeService（OpenAiApi 删除）、LangfuseObservationConfig（Observability 变更）
