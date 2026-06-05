---
updated: 2026-06-05 22:33
---
# Agent 端到端自动化评估体系 — 设计规格文档

- **日期**：2026-06-01
- **分支**：`feat/multi-agent`
- **状态**：已实现
- **负责人**：Supremes

## 1. 目标

为 dawn-ai 构建一套**端到端自动化评估体系**，覆盖 AI Agent 系统的 6 个核心质量维度。通过真实 LLM 调用 + LLM-as-Judge 评分，量化证明 Agent 架构的设计决策正确性，并将评估结果自动写入 Langfuse Dashboard 形成可观测闭环。

核心诉求：
- 面试场景下可一键展示 Agent 技术深度（工具选择、RAG 召回、Prompt 工程、Multi-Agent、记忆系统）
- 每次 prompt / 策略变更后自动回归，量化对比效果
- 评估结果可追溯、可对比、可可视化

## 2. 非目标（Non-Goals）

- 不替代现有单元测试（37 个测试类保持不动，评估体系是补充而非替代）
- 不做线上 A/B 测试（本方案聚焦开发阶段的离线评估）
- 不引入 Langfuse Java SDK（延续现有纯 OTel 集成风格，评估打分通过 REST API 实现）
- 不做人工标注评估（纯自动化，LLM-as-Judge 替代人工）

## 3. 已锁定决策

| # | 决策项 | 选择 | 理由 |
|---|--------|------|------|
| 1 | 评估维度 | 全部 6 个 | 覆盖面试高频拷问领域 |
| 2 | LLM 策略 | 纯真实 LLM | Agent 系统的核心是 LLM 行为，Mock 太假 |
| 3 | 评分方式 | Langfuse LLM-as-Judge | Langfuse v3 内置 evals 模块，零额外依赖 |
| 4 | 数据集管理 | 双写（本地 JSON + Langfuse Dataset） | git 版本控制 + Langfuse 可视化 |
| 5 | 运行模式 | JUnit 测试类（`@Tag("evaluation")`） | CI 原生支持，最小侵入 |
| 6 | Judge 模型 | 独立 ChatModel bean | 避免自评偏见，面试时可解释 |
| 7 | 数据集结构 | 统一 JSON Schema | 6 维度共用一个结构，维护成本低 |
| 8 | 评分方式 | 混合（Binary + Likert） | 行为类用 Binary 确定性强，质量类用 Likert 细粒度 |
| 9 | 测试环境 | 本地 Docker Compose | 最接近真实环境 |
| 10 | Langfuse API | 封装 REST Client | 零额外依赖，与现有设计一致 |
| 11 | Judge Prompt | 代码内 `.txt` 文件 | git 版本控制，测试自包含 |

## 4. Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Evaluation Test Suite                             │
│                                                                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐ │
│  │ ToolSelection   │  │ RagRecall       │  │ AnswerCompleteness  │ │
│  │ EvaluationTest  │  │ EvaluationTest  │  │ EvaluationTest      │ │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────────┘ │
│           │                    │                     │              │
│  ┌────────┴────────┐  ┌───────┴─────────┐  ┌───────┴─────────────┐│
│  │ PromptAssembly  │  │ SubAgentIsol.   │  │ MultiTurnCoherence  ││
│  │ EvaluationTest  │  │ EvaluationTest  │  │ EvaluationTest      ││
│  └────────┬────────┘  └───────┬─────────┘  └───────┬─────────────┘│
│           │                   │                     │              │
│           └───────────────────┼─────────────────────┘              │
│                               ▼                                    │
│                    ┌─────────────────────┐                         │
│                    │ AbstractEvaluation  │                         │
│                    │ Test (基类)         │                         │
│                    └────────┬────────────┘                         │
│                             │                                      │
│              ┌──────────────┼──────────────┐                       │
│              ▼              ▼              ▼                        │
│     ┌──────────────┐ ┌───────────┐ ┌──────────────┐               │
│     │ JudgeService │ │ Dataset   │ │ Langfuse     │               │
│     │ (LLM Judge)  │ │ Loader    │ │ ScoringClient│               │
│     └──────┬───────┘ └───────────┘ └──────┬───────┘               │
│            │                               │                       │
└────────────┼───────────────────────────────┼───────────────────────┘
             │                               │
             ▼                               ▼
    ┌─────────────────┐            ┌─────────────────┐
    │ Judge ChatModel │            │ Langfuse REST   │
    │ (独立 LLM)      │            │ API             │
    └─────────────────┘            └─────────────────┘
```

### 4.1 数据流

```
evaluation-dataset.json
        │
        ▼
EvaluationDatasetLoader.loadByDimension("tool_selection")
        │
        ▼
ToolSelectionEvaluationTest
        │
        ├── 1. agentOrchestrator.chat(sessionId, query) → AgentResult
        │       └── 真实 LLM 调用 + 工具执行
        │
        ├── 2. 提取 actualTools from AgentResult.steps()
        │
        ├── 3. JudgeService.judge(TOOL_SELECTION, variables)
        │       ├── 加载 judge-prompts/tool_selection.txt
        │       ├── 填充模板变量 (query, expected_tools, actual_tools)
        │       ├── 调用 Judge ChatModel (独立 LLM)
        │       └── 解析 JSON 响应 → JudgeResult(score, reasoning)
        │
        ├── 4. 断言: judgeResult.passed() == true
        │
        └── 5. (可选) LangfuseScoringClient.score(traceId, result)
                └── POST /api/public/scores → Langfuse Dashboard
```

## 5. 评估维度详解

### 5.1 工具选择正确性（Tool Selection）

| 属性 | 值 |
|------|-----|
| 维度 ID | `tool_selection` |
| 评分类型 | Binary（0/1） |
| 通过阈值 | 1.0 |
| 面试价值 | 证明 ReAct 范式的工具调度可靠性 |

**评什么**：给定用户 query，Agent 是否调用了正确的 tool 组合。

**评分规则**：
- 1.0：调用了所有期望工具（顺序无关，允许多调用）
- 0.5：调用了部分期望工具
- 0.0：调用了错误工具或应调用而未调用

**测试用例示例**：

| Query | 期望工具 | 验证点 |
|-------|---------|--------|
| "今天北京天气怎么样？" | `weatherTool` | 天气查询 → 天气工具 |
| "帮我算 (15*23)+47" | `calculatorTool` | 数学计算 → 计算器工具 |
| "公司2024年营收增长率？" | `knowledgeSearchTool` | 知识查询 → RAG 检索 |
| "上海今天多少度？超30度算空调电费" | `weatherTool` + `calculatorTool` | 多工具编排 |

### 5.2 RAG 召回相关性（RAG Recall）

| 属性 | 值 |
|------|-----|
| 维度 ID | `rag_recall` |
| 评分类型 | Likert（1-5） |
| 通过阈值 | ≥ 3.5 |
| 面试价值 | 证明 RAG pipeline 的检索质量 |

**评什么**：检索到的文档是否与 query 语义相关，是否召回了正确的文档。

**评分规则**：
- 5：所有期望文档被召回，无无关文档
- 4：所有期望文档被召回，混入少量无关文档
- 3：大部分期望文档被召回（≥70%），部分遗漏
- 2：少量期望文档被召回（<50%），显著缺失
- 1：无期望文档被召回，或完全无关

### 5.3 Answer 完整性（Answer Completeness）

| 属性 | 值 |
|------|-----|
| 维度 ID | `answer_completeness` |
| 评分类型 | Likert（1-5） |
| 通过阈值 | ≥ 3.5 |
| 面试价值 | 证明端到端回答质量 |

**评什么**：最终回答是否覆盖了 query 要求的所有关键信息点。

**评分规则**：
- 5：完整覆盖所有关键信息点，结构清晰，准确无误
- 4：覆盖大部分信息点（≥80%），遗漏次要细节
- 3：覆盖核心信息点（≥60%），遗漏重要方面
- 2：覆盖部分信息（<50%），显著缺失
- 1：回答与问题无关或完全错误

### 5.4 Prompt 拼装正确性（Prompt Assembly）

| 属性 | 值 |
|------|-----|
| 维度 ID | `prompt_assembly` |
| 评分类型 | Binary（0/1） |
| 通过阈值 | 1.0 |
| 面试价值 | 证明 Prompt 工程的可维护性 |

**评什么**：`AgentOrchestrator.buildSystemPrompt()` 是否正确拼装了所有必要的 prompt 段落。

**System Prompt 拼装顺序**（8 段）：
1. `baseSystemPrompt` — 基础角色定义
2. User Profile — 用户画像（`UserProfileService.formatForSystemPrompt`）
3. Topic Section — 话题约束（如有 topicId）
4. Skills Catalog — 技能目录（`formatSkills()`）
5. Sub-Agent Catalog — 子 Agent 目录（`formatSubAgents()`）
6. Execution Plan — 执行计划（`TaskPlanner` 输出）
7. Plan Enforcement — 计划强制指令
8. Max-Steps — 最大步数限制

**验证方式**：通过反射调用 private `buildSystemPrompt()` 方法，将实际输出与期望段落对比。

### 5.5 子 Agent 上下文隔离（Sub-Agent Isolation）

| 属性 | 值 |
|------|-----|
| 维度 ID | `subagent_isolation` |
| 评分类型 | Binary（0/1） |
| 通过阈值 | 1.0 |
| 面试价值 | 证明 Multi-Agent 设计的可靠性 |

**评什么**：子 Agent 的执行是否与主 Agent 完全隔离。

**验证的 3 个隔离约束**：

| 约束 | 验证方式 |
|------|---------|
| 步骤隔离 | 子 Agent 的步骤不进入主 Agent 的 `StepCollector`（主 Agent 步骤数不变） |
| 超时降级 | 子 Agent 超时后返回 `PARTIAL_SUCCESS`，主 Agent 不崩溃 |
| 派发限制 | 超过 `max-dispatches-per-session`（默认 3）的派发被拦截 |

**实现说明**：`GenericReActSubAgentExecutor` 为每个子 Agent 创建独立的 `StepCollectorContext`，通过 `CompletableFuture.get(timeoutSeconds, SECONDS)` 强制超时。

### 5.6 多轮对话连贯性（Multi-Turn Coherence）

| 属性 | 值 |
|------|-----|
| 维度 ID | `multi_turn_coherence` |
| 评分类型 | Likert（1-5） |
| 通过阈值 | ≥ 3.5 |
| 面试价值 | 证明记忆系统的价值 |

**评什么**：Agent 是否正确利用对话历史（memory snapshot）提供连贯的回答。

**评分规则**：
- 5：完美引用对话历史，理解上下文，基于先前对话构建回答
- 4：正确使用大部分上下文，轻微遗漏不影响质量
- 3：感知部分上下文，遗漏重要细节
- 2：几乎忽略对话历史，像全新对话一样回答
- 1：完全忽略历史，或与先前事实矛盾

**记忆机制**：`MemoryService` 使用 Redis 列表（20 条滑动窗口），`getHistory()` 从 Redis 加载历史，`buildHistory()` 转换为 Spring AI Message 对象。

## 6. 数据模型

### 6.1 统一 JSON Schema

```json
{
  "id": "tool_select_001",
  "dimension": "tool_selection",
  "query": "今天北京天气怎么样？",
  "context": {
    "availableTools": ["weatherTool", "calculatorTool", "knowledgeSearchTool"],
    "memorySnapshot": null,
    "ragDocuments": [],
    "promptSegments": null,
    "subAgentBehavior": null
  },
  "expected": {
    "tools": ["weatherTool"],
    "answerCriteria": "应调用 weatherTool 查询天气",
    "docIds": null
  }
}
```

### 6.2 各维度字段使用情况

| 字段 | tool_selection | rag_recall | answer_completeness | prompt_assembly | subagent_isolation | multi_turn_coherence |
|------|:-:|:-:|:-:|:-:|:-:|:-:|
| `query` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `context.availableTools` | ✓ | ○ | ○ | - | ✓ | - |
| `context.memorySnapshot` | - | - | - | - | - | ✓ |
| `context.ragDocuments` | - | ✓ | ✓ | - | - | - |
| `context.promptSegments` | - | - | - | ✓ | - | - |
| `context.subAgentBehavior` | - | - | - | - | ✓ | - |
| `expected.tools` | ✓ | ○ | ○ | - | ✓ | - |
| `expected.answerCriteria` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `expected.docIds` | - | ✓ | ✓ | - | - | - |

✓ = 必填  ○ = 可选  - = 不适用

### 6.3 数据集规模

当前数据集：**18 条用例**

| 维度 | 用例数 | 覆盖场景 |
|------|--------|---------|
| 工具选择 | 5 | 单工具、多工具组合、边界情况 |
| RAG 召回 | 3 | 精确召回、多文档召回、干扰文档过滤 |
| Answer 完整性 | 3 | 多子主题覆盖、步骤指引、技术栈概述 |
| Prompt 拼装 | 2 | 基础拼装、带执行计划拼装 |
| 子 Agent 隔离 | 3 | 步骤隔离、超时降级、派发限制 |
| 多轮对话 | 3 | 历史引用、偏好记忆、计算结果记忆 |

## 7. Judge 系统

### 7.1 架构

```
JudgeChatModelConfig
    │
    ├── 独立 OpenAiChatModel bean（@Qualifier("judgeChatModel")）
    │   ├── temperature=0.0（确定性输出）
    │   ├── 独立 baseUrl / apiKey / model（可通过 JUDGE_* 环境变量覆盖）
    │   └── 默认复用业务 LLM 配置
    │
    └── JudgeService
        ├── 加载 judge-prompts/{dimension}.txt
        ├── 填充模板变量 {{query}}, {{expected_tools}}, ...
        ├── 调用 judgeChatModel.call()
        ├── 解析 JSON 响应 {score, reasoning}
        └── 返回 JudgeResult(dimension, score, reasoning)
```

### 7.2 为什么需要独立 Judge 模型

| 问题 | 解决方案 |
|------|---------|
| 自评偏见 | 独立模型避免"自己给自己打高分" |
| 面试加分 | 可回答"如何避免评估偏见"的拷问 |
| 职责分离 | Judge Prompt 和业务 Prompt 独立调优 |
| 成本可控 | Judge 仅在测试时调用，不影响线上 |

### 7.3 Judge Prompt 设计原则

1. **结构化输出**：要求返回 `{"score": N, "reasoning": "..."}` JSON
2. **明确评分规则**：每个 Prompt 内嵌评分 rubric，消除歧义
3. **容错解析**：`JudgeService.extractJson()` 自动剥离 markdown 代码围栏
4. **模板变量**：`{{variable}}` 占位符，运行时填充

### 7.4 评分阈值

| 评分类型 | 通过阈值 | 判定逻辑 |
|---------|---------|---------|
| Binary | ≥ 1.0 | `JudgeResult.passed()` → `score >= 1.0` |
| Likert | ≥ 3.5 | `JudgeResult.passed()` → `score >= 3.5` |

## 8. Langfuse 集成

### 8.1 双写策略

```
本地 JSON（source of truth）          Langfuse（可视化）
        │                                    │
        ▼                                    ▼
evaluation-dataset.json              Langfuse Dataset API
        │                                    │
        ├── git 版本控制                      ├── UI 可视化
        ├── CI 原生加载                       ├── Experiment 对比
        └── 离线可用                          └── 评分趋势
```

### 8.2 Langfuse REST API 使用

| 端点 | 用途 | 调用时机 |
|------|------|---------|
| `POST /api/public/scores` | 写入评分 | 每个 case 评估完成后 |
| `POST /api/public/datasets` | 创建/更新 Dataset | 评估开始前 |
| `POST /api/public/dataset-items` | 添加 Dataset Item | 同步数据集时 |
| `POST /api/public/dataset-run-items` | 关联 trace 到 Dataset Item | 评估完成后 |

### 8.3 认证方式

```java
String auth = Base64.getEncoder().encodeToString(
    (publicKey + ":" + secretKey).getBytes(UTF_8));
// Header: Authorization: Basic {auth}
```

与现有 OTel 集成使用相同的凭据（`LANGFUSE_INIT_PROJECT_PUBLIC_KEY` / `LANGFUSE_INIT_PROJECT_SECRET_KEY`）。

## 9. 测试执行流程

### 9.1 前置条件

```bash
# 1. 启动基础设施
docker compose up -d postgres redis

# 2. （可选）启动 Langfuse
docker compose -f docker-compose.yml -f docker-compose.observe.yml --profile observe up -d

# 3. 配置 LLM API
export OPENAI_API_KEY=your-key
export BASE_URL=your-base-url
export CHAT_MODEL=your-model
```

### 9.2 运行命令

```bash
# 全量评估（6 维度，18 用例）
mvn test -Dgroups=evaluation

# 单维度评估
mvn test -Dtest=ToolSelectionEvaluationTest
mvn test -Dtest=RagRecallEvaluationTest
mvn test -Dtest=AnswerCompletenessEvaluationTest
mvn test -Dtest=PromptAssemblyEvaluationTest
mvn test -Dtest=SubAgentIsolationEvaluationTest
mvn test -Dtest=MultiTurnCoherenceEvaluationTest

# 跳过评估测试（正常开发时）
mvn test -DexcludedGroups=evaluation
```

### 9.3 输出

| 输出 | 位置 | 格式 |
|------|------|------|
| 控制台报告 | stdout | 每个 case 的 score + reasoning + 总结 |
| 本地 JSON 报告 | `target/evaluation-reports/{runId}-{dimension}.json` | 结构化 JSON |
| Langfuse 评分 | Langfuse Dashboard → Scores | 评分趋势图 |
| Langfuse Dataset | Langfuse Dashboard → Datasets | Experiment 对比 |

### 9.4 控制台输出示例

```
[Evaluation] case=tool_select_001 | dimension=tool_selection | score=1.0 | passed=true | reasoning=The agent correctly called weatherTool for the weather query.
[Evaluation] case=tool_select_002 | dimension=tool_selection | score=1.0 | passed=true | reasoning=The agent correctly called calculatorTool for arithmetic.
[Evaluation] case=tool_select_003 | dimension=tool_selection | score=1.0 | passed=true | reasoning=The agent correctly called knowledgeSearchTool for company data.

=== Evaluation Report ===
Total: 5 cases, Pass rate: 100.0%, Avg score: 1.00
  tool_selection: avg=1.00, min=1.0, max=1.0, count=5
```

### 9.5 本地 JSON 报告示例

```json
{
  "runId": "eval-a3f7b2c1",
  "dimension": "tool_selection",
  "totalCases": 5,
  "passRate": 1.0,
  "averageScore": 1.0,
  "results": [
    {
      "dimension": "tool_selection",
      "score": 1.0,
      "passed": true,
      "reasoning": "The agent correctly called weatherTool for the weather query."
    }
  ]
}
```

## 10. 文件结构

```
src/test/java/com/dawn/ai/evaluation/
├── base/
│   ├── AbstractEvaluationTest.java        # 基类：环境检查、报告汇总、Langfuse 推送
│   ├── EvaluationCase.java                # 数据模型：统一 JSON Schema
│   ├── EvaluationDatasetLoader.java       # 加载器：从 classpath 加载 JSON
│   ├── EvaluationReport.java              # 报告：统计、分组、格式化
│   └── LangfuseScoringClient.java         # REST Client：写 score + 同步 Dataset
├── judge/
│   ├── JudgeChatModelConfig.java          # 独立 Judge ChatModel bean
│   ├── JudgeDimension.java                # 6 维度枚举 + 评分类型
│   ├── JudgeResult.java                   # 评分结果 + passed() 阈值判断
│   └── JudgeService.java                  # 统一调用 + 模板填充 + JSON 解析
└── dimensions/
    ├── ToolSelectionEvaluationTest.java   # Binary，验证工具选择
    ├── RagRecallEvaluationTest.java       # Likert，验证 RAG 召回
    ├── AnswerCompletenessEvaluationTest.java # Likert，验证回答完整性
    ├── PromptAssemblyEvaluationTest.java  # Binary，验证 prompt 拼装
    ├── SubAgentIsolationEvaluationTest.java # Binary，验证子 Agent 隔离
    └── MultiTurnCoherenceEvaluationTest.java # Likert，验证多轮对话

src/test/resources/evaluation/
├── evaluation-dataset.json                # 统一数据集（18 条用例）
├── judge-prompts/
│   ├── tool_selection.txt                 # 工具选择 Judge Prompt
│   ├── rag_recall.txt                     # RAG 召回 Judge Prompt
│   ├── answer_completeness.txt            # Answer 完整性 Judge Prompt
│   ├── prompt_assembly.txt                # Prompt 拼装 Judge Prompt
│   ├── subagent_isolation.txt             # 子 Agent 隔离 Judge Prompt
│   └── multi_turn_coherence.txt           # 多轮对话 Judge Prompt
└── retrieval-eval-dataset.json            # 已有的 RAG 评估数据集（独立）
```

## 11. 与现有测试体系的关系

```
                    dawn-ai 测试体系
                           │
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
     单元测试 (37)    E2E 测试 (2)    评估测试 (6) ← 新增
     ─────────────    ────────────    ─────────────
     Mock LLM         Mock LLM        真实 LLM
     确定性断言        集成验证         LLM-as-Judge
     mvn test         mvn test        mvn test
     默认运行          e2e-test        -Dgroups=
                      profile         evaluation
```

| 维度 | 单元测试 | E2E 测试 | 评估测试 |
|------|---------|---------|---------|
| LLM 调用 | Mock | Mock | 真实 |
| 断言方式 | assertEquals | assertEquals | LLM-as-Judge |
| 运行频率 | 每次 commit | PR 合并前 | 需要时 |
| 运行时间 | 秒级 | 秒级 | 分钟级 |
| 成本 | 零 | 零 | LLM API 费用 |

## 12. 面试叙事线

### 12.1 开场（30 秒）

> "我搭了一套 6 维度的 Agent 评估体系，用 LLM-as-Judge 做自动化评分。数据集 git 版本控制，评分自动写入 Langfuse Dashboard。`mvn test -Dgroups=evaluation` 一键跑完。"

### 12.2 技术深度（2-3 分钟）

逐维度展示，每个维度用 30 秒：

1. **工具选择**："5 个用例覆盖单工具、多工具组合、边界情况。Judge 验证 Agent 是否调用了正确的 tool。"
2. **RAG 召回**："3 个用例验证检索质量。Judge 给出 1-5 分，通过阈值 3.5。可以展示 dense vs hybrid 的 ablation study。"
3. **Answer 完整性**："3 个用例验证端到端回答质量。Judge 检查是否覆盖了所有关键信息点。"
4. **Prompt 拼装**："通过反射调用 private buildSystemPrompt()，验证 8 段拼装的正确性。"
5. **子 Agent 隔离**："验证 3 个约束：步骤隔离、超时降级、派发限制。"
6. **多轮对话**："预填充 Redis 记忆，验证 Agent 能否正确引用对话历史。"

### 12.3 工程化能力（1 分钟）

> "评估结果双写：本地 JSON 做 git 版本控制，Langfuse Dashboard 做可视化。Judge 模型独立于业务模型，避免自评偏见。整个评估体系零 Langfuse SDK 依赖，通过 REST API 实现。"

### 12.4 迭代能力（30 秒）

> "每次 prompt 变更后跑一次评估，Langfuse Experiment 对比不同版本的评分趋势。数据驱动迭代，不是凭感觉调 prompt。"

## 13. 后续扩展

| 项目 | 优先级 | 说明 |
|------|--------|------|
| 扩充数据集 | 高 | 每个维度扩充到 20+ 用例 |
| CI 集成 | 中 | GitHub Actions 中运行评估，PR 评论评分 |
| Langfuse Experiment 对比 | 中 | 不同版本的评分趋势对比 |
| 人工标注校准 | 低 | 人工标注一批用例，校准 Judge 评分准确率 |
| 成本监控 | 低 | 统计评估运行的 LLM API 费用 |
