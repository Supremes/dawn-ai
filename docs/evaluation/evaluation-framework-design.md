---
updated: 2026-06-23 22:30
---
# Agent 自动化评估体系 — 当前状态与执行手册

- **日期**：2026-06-01
- **当前落地分支**：`feat/local-eval-harness`
- **状态**：本地确定性 harness 已落地；LLM-as-Judge 作为高成本专项评估保留
- **负责人**：Supremes

## 1. 目标

为 dawn-ai 构建一套**分层自动化评估体系**，用低成本、可重复的本地 harness 覆盖日常回归，用真实 LLM + LLM-as-Judge 覆盖需要语义判断的专项评估，并通过本地报告 / Langfuse 形成可追溯闭环。

核心诉求：
- 面试场景下可一键展示 Agent 技术深度（工具选择、RAG 召回、Prompt 工程、Multi-Agent、记忆系统、风险安全）
- 每次 prompt / 策略变更后自动回归，量化对比效果
- 日常评估不依赖真实 LLM、Redis、PostgreSQL 或 Langfuse，避免成本与波动
- 高成本评估结果可追溯、可对比、可可视化

## 2. 非目标（Non-Goals）

- 不替代现有单元测试（评估体系是补充而非替代）
- 不做线上 A/B 测试（本方案聚焦开发阶段的离线评估）
- 不引入 Langfuse Java SDK（延续现有纯 OTel 集成风格，评估打分通过 REST API 实现）
- 不把真实 LLM-as-Judge 作为默认回归门槛（默认回归必须稳定、快速、零 token 成本）

## 3. 当前落地状态

| 能力 | 状态 | 默认运行 | 说明 |
|------|------|----------|------|
| 本地确定性 Agent harness | 已落地 | 是，随 `mvn test` 运行 | Mock AI、临时 SQLite、内存记忆、本地报告，覆盖风险安全、路由、RAG、API、工具队列 |
| RAG 检索指标 | 已落地 | 是 | 60 条数据集，指标包含 Recall、HitRate、MRR、NDCG、Precision、NoiseRate |
| Agent 端到端 LLM-as-Judge | 已落地但非默认 | 否，需 `-Dgroups=evaluation -Dexcluded.test.groups=` | 真实 Agent + 独立 Judge 模型，适合专项/夜间 benchmark |
| Memory Pipeline E2E | 已落地但非默认 | 否，需 Docker + `e2e-test` profile | 真实 Redis + PGVector，Mock LLM/Embedding |
| Langfuse score / dataset 写入 | 客户端已落地 | 否 | Langfuse 可用时作为可视化与 Experiment 对比通道 |

### 3.1 推荐日常执行路径

```bash
# 默认回归：包含本地 harness 与 RAG 指标，排除 evaluation/e2e
 

# 聚焦评估：只跑本地 harness 与 RAG 指标
mvn test -Dtest=LocalAgentEvaluationHarnessTest,RetrievalEvaluatorTest,HydeRetrievalEvaluationTest

# 高成本语义评估：真实 LLM-as-Judge
mvn test -Dgroups=evaluation -Dexcluded.test.groups=
```

### 3.2 当前 RAG 基线

| 指标 | 当前值 | 解释 |
|------|--------|------|
| `caseCount` | 60 | 本地 RAG 检索数据集规模 |
| `Recall@K` | 0.9667 | 58 / 60 个 case 在 Top 3 命中期望文档 |
| `HitRate@K` | 0.9667 | 58 / 60 个 case 至少命中 1 个期望文档 |
| `MRR@K` | 0.9083 | 期望文档平均排位较靠前 |
| `NDCG@K` | ≥ 0.90 | 排序质量门槛 |
| `Precision@K` | 58 / 180 | Top 3 命中文档占比；当前数据集刻意包含 hard negatives |
| `NoiseRate@K` | 122 / 180 | `1 - Precision@K`；用于观察噪声，不等同线上真实 precision |

## 4. 已锁定决策

| # | 决策项 | 选择 | 理由 |
|---|--------|------|------|
| 1 | 评估策略 | 分层评估 | 默认走 deterministic harness，高成本语义判断按需运行 |
| 2 | 默认回归 | Mock AI + 临时 SQLite + 内存记忆 | 零外部依赖、零 token 成本、CI 稳定 |
| 3 | RAG 指标 | Retrieval metrics + 60 条数据集 | 比纯 Judge 更可重复，可定位召回不足或噪声过多 |
| 4 | LLM Judge | 非默认专项评估 | 保留真实 Agent 行为判断，避免日常开发被限流/成本影响 |
| 5 | 数据集管理 | 本地 JSON 为 source of truth，Langfuse 可选同步 | git 版本控制 + 可视化对比 |
| 6 | 运行模式 | JUnit 测试类 + Maven group | CI 原生支持，最小侵入 |
| 7 | Judge 模型 | JudgeService 内独立 ChatModel | 避免自评偏见，职责分离 |
| 8 | 评分方式 | Binary + Likert + Retrieval Metrics | 行为类确定性评分，质量类语义评分，检索类数值指标 |
| 9 | 高保真环境 | Docker Compose | 仅 E2E/专项评估使用，最接近真实 Redis/PGVector |
| 10 | Langfuse API | 封装 REST Client | 零额外 SDK 依赖，与现有设计一致 |
| 11 | Judge Prompt | 代码内 `.txt` 文件 | git 版本控制，测试自包含 |

## 5. 架构

```
┌───────────────────────────────────────────────────────────────────────┐
│                         Evaluation Layers                              │
│                                                                       │
│  默认回归层                                                            │
│  ┌──────────────────────────┐  ┌───────────────────────────────────┐  │
│  │ LocalAgentEvaluation     │  │ RetrievalEvaluatorTest             │  │
│  │ HarnessTest              │  │ 60-case Retrieval Metrics          │  │
│  │ Mock AI + SQLite + Memory│  │ Recall/Precision/Noise/MRR/NDCG   │  │
│  └────────────┬─────────────┘  └──────────────────┬────────────────┘  │
│               │                                   │                   │
│               ▼                                   ▼                   │
│      local-agent-harness-report.json     retrieval-eval-dataset.json   │
│                                                                       │
│  专项评估层                                                            │
│  ┌──────────────────────────┐  ┌───────────────────────────────────┐  │
│  │ Evaluation dimensions    │  │ MemoryPipelineE2ETest             │  │
│  │ Tool/RAG/Answer/Prompt/  │  │ Redis + PGVector                  │  │
│  │ SubAgent/MultiTurn       │  │ Mock LLM/Embedding                │  │
│  └────────────┬─────────────┘  └───────────────────────────────────┘  │
│               │                                                       │
│               ▼                                                       │
│       JudgeService → independent Judge ChatModel → JSON result         │
│               │                                                       │
│               ▼                                                       │
│       Local JSON report + optional Langfuse REST score/dataset         │
└───────────────────────────────────────────────────────────────────────┘
```

### 5.1 默认本地 harness 数据流

```
retrieval-eval-dataset.json (60 cases)
        │
        ▼
LocalAgentEvaluationHarnessTest
        │
        ├── 1. 写入临时 SQLite
        │       ├── documents
        │       ├── rankings
        │       └── api_events
        │
        ├── 2. RetrievalEvaluator.evaluate(cases, store::retrieve, 3)
        │       └── 输出 Recall / HitRate / MRR / NDCG / Precision / NoiseRate
        │
        ├── 3. MockAiRouter + ToolQueue
        │       ├── weatherTool
        │       ├── calculatorTool
        │       └── knowledgeSearchTool
        │
        ├── 4. InMemoryMemoryStore 验证多轮记忆写入
        │
        ├── 5. BashTool readonly mode 验证风险安全
        │
        └── 6. target/evaluation-reports/local-agent-harness-report.json
```

### 5.2 LLM-as-Judge 数据流

```
evaluation-dataset.json
        │
        ▼
EvaluationDatasetLoader.loadByDimension("tool_selection")
        │
        ▼
ToolSelectionEvaluationTest
        │
        ├── 1. agentOrchestrator.streamChat(sessionId, query, ...) → done event
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

## 6. 评估维度详解

### 6.1 工具选择正确性（Tool Selection）

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

### 6.2 RAG 召回相关性（RAG Recall）

| 属性 | 值 |
|------|-----|
| 维度 ID | `rag_recall` |
| 评分类型 | Likert（1-5）+ 确定性 Retrieval Metrics |
| 通过阈值 | LLM Judge ≥ 3.5；本地 harness 固定校验指标阈值 |
| 面试价值 | 证明 RAG pipeline 的检索质量 |

**评什么**：检索到的文档是否与 query 语义相关，是否召回了正确的文档。

**评分规则**：
- 5：所有期望文档被召回，无无关文档
- 4：所有期望文档被召回，混入少量无关文档
- 3：大部分期望文档被召回（≥70%），部分遗漏
- 2：少量期望文档被召回（<50%），显著缺失
- 1：无期望文档被召回，或完全无关

**确定性指标**：

| 指标 | 含义 | 当前本地 harness 基线 |
|------|------|------------------------|
| `Recall@K` | 期望文档中有多少进入 Top K | 0.9667 |
| `HitRate@K` | 每个 query 是否至少命中 1 个期望文档 | 0.9667 |
| `MRR@K` | 第一个相关文档的平均倒数排名 | 0.9083 |
| `NDCG@K` | 相关文档排序质量 | ≥ 0.90 |
| `Precision@K` | Top K 中相关文档占比 | 58 / 180 |
| `NoiseRate@K` | Top K 中噪声文档占比，`1 - Precision@K` | 122 / 180 |

### 6.3 Answer 完整性（Answer Completeness）

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

### 6.4 Prompt 拼装正确性（Prompt Assembly）

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

### 6.5 子 Agent 上下文隔离（Sub-Agent Isolation）

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

### 6.6 多轮对话连贯性（Multi-Turn Coherence）

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

## 7. 数据模型

### 7.1 统一 JSON Schema

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

### 7.2 各维度字段使用情况

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

### 7.3 数据集规模

当前 Agent 端到端数据集：**36 条用例**

| 维度 | 用例数 | 覆盖场景 |
|------|--------|---------|
| 工具选择 | 10 | 单工具、多工具组合、边界情况 |
| RAG 召回 | 5 | 精确召回、多文档召回、干扰文档过滤、安全类查询 |
| Answer 完整性 | 6 | 多子主题覆盖、步骤指引、技术栈概述、容错、Multi-Agent、评估说明 |
| Prompt 拼装 | 5 | 基础拼装、带执行计划、用户画像、topic 缺省行为 |
| 子 Agent 隔离 | 5 | 步骤隔离、超时降级、派发限制、工具计数隔离、多子 Agent 隔离 |
| 多轮对话 | 5 | 历史引用、偏好记忆、计算结果记忆、主题延续、指代消解 |

独立 RAG 检索数据集：`src/test/resources/evaluation/retrieval-eval-dataset.json`，当前 **60 条用例**。每条包含：

| 字段 | 用途 |
|------|------|
| `query` | 检索输入 |
| `expectedDocIds` | 应召回的相关文档 |
| `hardNegativeDocIds` | 容易误召回的干扰文档，用于观察噪声 |
| `metadataFilters` | metadata 过滤条件 |
| `mockRankedDocIds` | 本地 harness 的确定性 Top K 排序结果 |

## 8. Judge 系统

### 8.1 架构

```
JudgeService
    ├── 构造独立 OpenAiChatModel
    │   ├── temperature=0.0（确定性输出）
    │   ├── 独立 baseUrl / apiKey / model（可通过 JUDGE_* 环境变量覆盖）
    │   └── 默认复用业务 LLM 配置
    ├── 加载 judge-prompts/{dimension}.txt
    ├── 填充模板变量 {{query}}, {{expected_tools}}, ...
    ├── 调用 judgeChatModel.call()
    ├── 解析 JSON 响应 {score, reasoning}
    └── 返回 JudgeResult(dimension, score, reasoning)
```

### 8.2 为什么需要独立 Judge 模型

| 问题 | 解决方案 |
|------|---------|
| 自评偏见 | 独立模型避免"自己给自己打高分" |
| 面试加分 | 可回答"如何避免评估偏见"的拷问 |
| 职责分离 | Judge Prompt 和业务 Prompt 独立调优 |
| 成本可控 | Judge 仅在测试时调用，不影响线上 |

### 8.3 Judge Prompt 设计原则

1. **结构化输出**：要求返回 `{"score": N, "reasoning": "..."}` JSON
2. **明确评分规则**：每个 Prompt 内嵌评分 rubric，消除歧义
3. **容错解析**：`JudgeService.extractJson()` 自动剥离 markdown 代码围栏
4. **模板变量**：`{{variable}}` 占位符，运行时填充

### 8.4 评分阈值

| 评分类型 | 通过阈值 | 判定逻辑 |
|---------|---------|---------|
| Binary | ≥ 1.0 | `JudgeResult.passed()` → `score >= 1.0` |
| Likert | ≥ 3.5 | `JudgeResult.passed()` → `score >= 3.5` |

## 9. Langfuse 集成

### 9.1 双写策略

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

### 9.2 Langfuse REST API 使用

| 端点 | 用途 | 调用时机 |
|------|------|---------|
| `POST /api/public/scores` | 写入评分 | 每个 case 评估完成后 |
| `POST /api/public/datasets` | 创建/更新 Dataset | 评估开始前 |
| `POST /api/public/dataset-items` | 添加 Dataset Item | 同步数据集时 |
| `POST /api/public/dataset-run-items` | 关联 trace 到 Dataset Item | 评估完成后 |

### 9.3 认证方式

```java
String auth = Base64.getEncoder().encodeToString(
    (publicKey + ":" + secretKey).getBytes(UTF_8));
// Header: Authorization: Basic {auth}
```

与现有 OTel 集成使用相同的凭据（`LANGFUSE_INIT_PROJECT_PUBLIC_KEY` / `LANGFUSE_INIT_PROJECT_SECRET_KEY`）。

## 10. 测试执行流程

### 10.1 测试分层与推荐执行顺序

当前评估体系分为五层，推荐按成本从低到高执行：

| 顺序 | 测试层 | 入口 | 外部依赖 | 用途 |
|------|--------|------|----------|------|
| 1 | 默认回归测试 | `mvn test` | 无真实 LLM；默认排除 `evaluation,e2e` | 每次提交前确认普通单元测试和本地 harness 都通过 |
| 2 | 本地确定性 Agent harness | `LocalAgentEvaluationHarnessTest` | Mock AI、临时 SQLite、内存记忆、本地文件输出 | 快速验证风险安全、Agent 路由、RAG、API、工具队列 |
| 3 | 确定性 RAG 指标测试 | `RetrievalEvaluatorTest` | 本地 JSON 数据集 | 量化 RAG 指标，避免真实向量库/LLM 波动 |
| 4 | 真实 E2E 记忆管道 | `MemoryPipelineE2ETest` | Docker Redis + PostgreSQL/PGVector；Mock LLM/Embedding | 验证 Redis/PGVector 真实读写链路 |
| 5 | LLM-as-Judge 评估 | `mvn test -Dgroups=evaluation -Dexcluded.test.groups=` | Docker 基础设施 + 真实 LLM，可选 Langfuse | 手动/夜间 benchmark，观察 Agent 行为质量 |

默认开发路径优先跑第 1-3 层；第 4-5 层有外部依赖和耗时成本，适合合并前或专项评估。

### 10.2 本地确定性 harness 流程

入口：

```bash
mvn test -Dtest=LocalAgentEvaluationHarnessTest
```

该 harness 的目标是用**无外部服务**方式覆盖 Agent 核心链路，保证 CI 可重复：

1. **加载 RAG 数据集**：读取 `src/test/resources/evaluation/retrieval-eval-dataset.json`，断言 60 条用例存在。
2. **初始化临时 SQLite**：通过 JUnit `@TempDir` 创建 `harness.db`，建表：
   - `documents`：mock 文档内容与 category
   - `rankings`：每个 query 的确定性排序结果
   - `api_events`：本地 API 调用记录
3. **写入 mock retrieval 排序**：将每条用例的 `expectedDocIds`、`hardNegativeDocIds`、`mockRankedDocIds` 写入 SQLite。
4. **计算 RAG 指标**：`RetrievalEvaluator.evaluate(cases, store::retrieve, 3)` 计算：
   - `Recall@K = 0.9667`
   - `HitRate@K = 0.9667`
   - `MRR@K = 0.9083`
   - `NDCG@K >= 0.90`
   - `Precision@K = 58 / 180`
   - `NoiseRate@K = 122 / 180`
5. **验证风险安全**：构造只读模式 `BashTool`：
   - `touch blocked.txt` 必须被拒绝，并返回“只读安全模式”
   - `printf harness-ok` 必须允许执行
6. **验证 Agent 路由与工具队列**：Mock AI router 根据 query 选择工具：
   - “北京今天多少度？顺便帮我算一下华氏度” → `weatherTool` + `calculatorTool`
   - route 为 `tool_queue`
   - 回答包含 `25C` 与 `77F`
7. **验证 RAG/API/记忆链路**：
   - “refund policy” → `knowledgeSearchTool`
   - 首个 retrieved doc 必须为 `doc-billing-refund-policy`
   - 内存记忆保存 user/assistant 两轮共 4 条消息
   - SQLite `api_events` 写入 2 条 API 事件
8. **验证工具队列上限**：队列上限为 2 时插入 3 个工具必须抛出 `max tool queue size exceeded`。
9. **写入本地报告**：输出到 `target/evaluation-reports/local-agent-harness-report.json`。

本地报告结构：

```json
{
  "harness": "local-agent",
  "storage": "temporary-sqlite",
  "memory": "in-memory",
  "rag": {
    "caseCount": 60,
    "recallAtK": 0.9667,
    "precisionAtK": 0.3222,
    "noiseRateAtK": 0.6778,
    "hitRateAtK": 0.9667,
    "mrrAtK": 0.9083,
    "ndcgAtK": 0.90
  },
  "apiResponses": []
}
```

### 10.3 RAG 指标测试流程

入口：

```bash
mvn test -Dtest=RetrievalEvaluatorTest,HydeRetrievalEvaluationTest
```

执行内容：

| 测试 | 目的 |
|------|------|
| `evaluate_hybridOutperformsDenseOnSampleDataset` | 用小样例证明 hybrid 在 Recall、Precision、NoiseRate、HitRate、MRR、NDCG 上优于 dense |
| `evaluate_localDatasetReportsExtendedMetrics` | 用 60 条数据集校验本地指标基线 |
| `HydeRetrievalEvaluationTest` | 用同一 mock retriever 对比 HyDE 改写前后指标变化 |

指标解释：

| 指标 | 计算方式 | 主要回答的问题 |
|------|----------|----------------|
| `Recall@K` | Top K 命中数 / 期望相关文档数 | 该召回的有没有召回 |
| `Precision@K` | Top K 命中数 / K | Top K 里噪声多不多 |
| `NoiseRate@K` | `1 - Precision@K` | 无关文档比例 |
| `HitRate@K` | 至少命中一个相关文档则为 1，否则 0 | 每个 query 是否有基本命中 |
| `MRR@K` | 第一个相关文档排名的倒数均值 | 相关文档排得是否靠前 |
| `NDCG@K` | DCG / IDCG | 整体排序质量 |

### 10.4 真实 E2E 记忆管道流程

前置条件：

```bash
docker compose up -d postgres redis
```

入口：

```bash
mvn test -Dtest=MemoryPipelineE2ETest -Dspring.profiles.active=e2e-test -Dexcluded.test.groups=
```

执行内容：

1. 使用真实 Redis 验证 L1 滑动窗口、清理、容量上限。
2. 使用真实 PostgreSQL/PGVector 验证 L2 摘要写入和相似搜索。
3. Mock `ChatModel` 与 `EmbeddingModel`，避免真实 LLM 成本与波动。
4. 触发 L3 consolidation 与 L4 reflection，验证 UserProfile 写入 Redis。
5. 每个测试使用独立 UUID session，结束后清理 Redis key；VectorStore 通过 sessionId filter 隔离。

### 10.5 LLM-as-Judge 评估流程

前置条件：

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

运行命令：

```bash
# 全量评估（6 维度，36 用例）
mvn test -Dgroups=evaluation -Dexcluded.test.groups=

# 单维度评估
mvn test -Dtest=ToolSelectionEvaluationTest
mvn test -Dtest=RagRecallEvaluationTest
mvn test -Dtest=AnswerCompletenessEvaluationTest
mvn test -Dtest=PromptAssemblyEvaluationTest
mvn test -Dtest=SubAgentIsolationEvaluationTest
mvn test -Dtest=MultiTurnCoherenceEvaluationTest

# 跳过评估测试（正常开发时）
mvn test
```

执行流程：

1. `EvaluationDatasetLoader` 按 dimension 加载 `evaluation-dataset.json`。
2. 维度测试调用真实 Agent 或反射内部方法：
   - Tool Selection：执行 Agent，提取实际工具调用
   - RAG Recall / Answer Completeness：预索引测试文档，执行 Agent，交给 Judge 打分
   - Prompt Assembly：反射调用 `buildSystemPrompt()`
   - SubAgent Isolation：用行为配置构造观测结果
   - Multi-Turn Coherence：预填充 memory snapshot 后执行 Agent
3. `JudgeService` 加载对应 `judge-prompts/*.txt`，调用独立 judge model。
4. `JudgeResult.passed()` 根据评分类型判定：
   - Binary：`score >= 1.0`
   - Likert：`score >= 3.5`
5. `AbstractEvaluationTest` 汇总结果，写本地 JSON 报告。
6. 如果 Langfuse 可用，可通过 REST client 写 score / dataset / run item。

### 10.6 输出

| 输出 | 位置 | 格式 |
|------|------|------|
| 控制台报告 | stdout | 每个 case 的 score + reasoning + 总结 |
| 本地 JSON 报告 | `target/evaluation-reports/{runId}-{dimension}.json` | 结构化 JSON |
| 本地 harness 报告 | `target/evaluation-reports/local-agent-harness-report.json` | 结构化 JSON |
| Langfuse 评分 | Langfuse Dashboard → Scores | 评分趋势图 |
| Langfuse Dataset | Langfuse Dashboard → Datasets | Experiment 对比 |

### 10.7 控制台输出示例

```
[Evaluation] case=tool_select_001 | dimension=tool_selection | score=1.0 | passed=true | reasoning=The agent correctly called weatherTool for the weather query.
[Evaluation] case=tool_select_002 | dimension=tool_selection | score=1.0 | passed=true | reasoning=The agent correctly called calculatorTool for arithmetic.
[Evaluation] case=tool_select_003 | dimension=tool_selection | score=1.0 | passed=true | reasoning=The agent correctly called knowledgeSearchTool for company data.

=== Evaluation Report ===
Total: 5 cases, Pass rate: 100.0%, Avg score: 1.00
  tool_selection: avg=1.00, min=1.0, max=1.0, count=5
```

### 10.8 本地 JSON 报告示例

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

## 11. 文件结构

```
src/test/java/com/dawn/ai/evaluation/
├── base/
│   ├── AbstractEvaluationTest.java        # 基类：环境检查、报告汇总、Langfuse 推送
│   ├── EvaluationCase.java                # 数据模型：统一 JSON Schema
│   ├── EvaluationDatasetLoader.java       # 加载器：从 classpath 加载 JSON
│   ├── EvaluationReport.java              # 报告：统计、分组、格式化
│   ├── LangfuseExperimentClient.java      # Langfuse Dataset Run / Experiment 关联
│   └── LangfuseScoringClient.java         # REST Client：写 score + 同步 Dataset
├── calibration/
│   └── HumanAnnotationCalibrator.java     # 人工标注校准：Accuracy/Kappa/MAE
├── cost/
│   └── CostTracker.java                   # 评估调用成本统计
├── harness/
│   └── LocalAgentEvaluationHarnessTest.java # 本地确定性 harness
├── judge/
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
├── evaluation-dataset.json                # 统一 Agent 评估数据集（36 条用例）
├── judge-prompts/
│   ├── tool_selection.txt                 # 工具选择 Judge Prompt
│   ├── rag_recall.txt                     # RAG 召回 Judge Prompt
│   ├── answer_completeness.txt            # Answer 完整性 Judge Prompt
│   ├── prompt_assembly.txt                # Prompt 拼装 Judge Prompt
│   ├── subagent_isolation.txt             # 子 Agent 隔离 Judge Prompt
│   └── multi_turn_coherence.txt           # 多轮对话 Judge Prompt
└── retrieval-eval-dataset.json            # 独立 RAG 检索数据集（60 条用例）
```

## 12. 与现有测试体系的关系

```
                       dawn-ai 测试体系
                              │
            ┌─────────────────┼─────────────────┬──────────────────┐
            ▼                 ▼                 ▼                  ▼
     单元测试/默认回归    本地 harness      E2E 记忆管道       LLM-as-Judge
     ───────────────    ─────────────      ────────────       ─────────────
     Mock LLM           Mock AI            Mock LLM/Embedding  真实 LLM
     确定性断言          临时 SQLite/内存    真实 Redis/PGVector Judge 打分
     mvn test           默认随 mvn test     e2e-test profile   -Dgroups=evaluation
```

| 维度 | 单元测试/默认回归 | 本地 harness | E2E 记忆管道 | LLM-as-Judge |
|------|-------------------|--------------|---------------|--------------|
| LLM 调用 | Mock | Mock AI router | Mock ChatModel/EmbeddingModel | 真实 |
| 存储依赖 | 无或 mock | 临时 SQLite + 内存 | Redis + PGVector | Redis + PGVector |
| 断言方式 | assertEquals / AssertJ | AssertJ + 固定指标阈值 | AssertJ | LLM Judge |
| 运行频率 | 每次 commit | 每次 commit / PR | 合并前 | 手动 / 夜间 |
| 运行时间 | 秒级 | 秒级 | 秒级到分钟级 | 分钟级 |
| 成本 | 零 | 零 | 零 | LLM API 费用 |

## 13. 面试叙事线

### 13.1 开场（30 秒）

> "我搭了一套分层 Agent 评估体系：默认本地 harness 用 Mock AI、临时 SQLite、内存记忆跑稳定回归；需要真实行为判断时，再用 6 维度 LLM-as-Judge 评估并写入 Langfuse。"

### 13.2 技术深度（2-3 分钟）

逐维度展示，每个维度用 30 秒：

1. **本地 harness**："Mock AI + 临时 SQLite + 内存记忆，无外部依赖覆盖风险安全、工具队列、Agent 路由、RAG、API。"
2. **RAG 指标**："60 条检索数据集同时看 Recall、HitRate、MRR、NDCG、Precision、NoiseRate，能区分召回不足和噪声过多。"
3. **工具选择**："10 个用例覆盖单工具、多工具组合、边界情况。Judge 验证 Agent 是否调用了正确的 tool。"
4. **Answer 完整性**："6 个用例验证端到端回答质量。Judge 检查是否覆盖所有关键信息点。"
5. **Prompt 拼装**："通过反射调用 private buildSystemPrompt()，验证 8 段拼装的正确性。"
6. **子 Agent / 多轮记忆**："验证步骤隔离、超时降级、派发限制、历史引用、偏好记忆和指代消解。"

### 13.3 工程化能力（1 分钟）

> "评估结果分层落盘：本地 harness 写 `target/evaluation-reports/local-agent-harness-report.json`，LLM-as-Judge 写 `{runId}-{dimension}.json`，Langfuse Dashboard 负责可视化和 Experiment 对比。Judge 模型独立于业务模型，避免自评偏见。"

### 13.4 迭代能力（30 秒）

> "每次 prompt 变更后跑一次评估，Langfuse Experiment 对比不同版本的评分趋势。数据驱动迭代，不是凭感觉调 prompt。"

## 14. 后续扩展

| 项目 | 优先级 | 说明 |
|------|--------|------|
| 扩充数据集 | 高 | 每个维度扩充到 20+ 用例 |
| CI 集成 | 中 | GitHub Actions 中运行评估，PR 评论评分 |
| Langfuse Experiment 对比 | 中 | 不同版本的评分趋势对比 |
| 人工标注校准 | 低 | 人工标注一批用例，校准 Judge 评分准确率 |
| 成本监控 | 低 | 统计评估运行的 LLM API 费用 |
