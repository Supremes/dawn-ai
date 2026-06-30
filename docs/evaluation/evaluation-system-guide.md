---
updated: 2026-06-29 22:48
---
# Dawn AI Agent Evaluation System

> 本文档完整阐述 dawn-ai 项目的 Agent 评测体系：**为什么要评测、评什么、怎么评、怎么跑、结果怎么看**。
> 面向的读者是需要理解、运行或扩展评测的开发者。

---

## 1. 评测体系总览

dawn-ai 的评测体系由三个层次构成，从上到下逐层递进：

```
┌────────────────────────────────────────────────────────┐
│             Layer 3: LLM-as-Judge 端到端评测             │
│     真实 Agent stream → Judge LLM 打分 → 报告生成         │
│     10 维度: Tool Selection / RAG Recall / Answer       │
│       Completeness / Prompt Assembly / SubAgent         │
│       Isolation / Multi-Turn Coherence /                │
│       Hallucination / Faithfulness / Safety /           │
│       Skill Selection                                   │
│     数据集: 710 条, HTML + JSON 报告自动生成               │
├────────────────────────────────────────────────────────┤
│             Layer 2: RAG 检索专项评测                     │
│     IR 指标: Recall@K / Precision@K / Hit@K /           │
│              MRR@K / NDCG@K / Noise Rate                │
│     数据集: 321 条 retrieval-eval-dataset.json           │
├────────────────────────────────────────────────────────┤
│             Layer 1: Local Evaluation Harness            │
│     SQLite 替代 PgVector / MockAiRouter / ToolQueue      │
│     无外部依赖、纯本地、可在 CI 中运行                      │
└────────────────────────────────────────────────────────┘
```

**核心设计原则：**

- **LLM-as-Judge**：用一个独立的 Judge LLM 来评价 Agent 的行为质量，而非硬编码规则
- **维度正交**：每个维度独立评测，互不耦合，可单独跑也可全量跑
- **双轨数据集**：Agent 评测数据集（`evaluation-dataset.json`）和 RAG 检索数据集（`retrieval-eval-dataset.json`）分离
- **可观测性**：评测结果可回写 Langfuse，支持跨版本趋势对比

---

## 2. 代码结构

```
src/test/java/com/dawn/ai/evaluation/
├── base/                           # 评测基础设施
│   ├── AbstractEvaluationTest.java  # 所有维度评测的基类
│   ├── EvaluationCase.java          # 评测用例 record
│   ├── EvaluationDatasetLoader.java # 从 JSON 加载用例
│   ├── EvaluationReport.java        # 汇总报告
│   ├── LangfuseScoringClient.java   # Langfuse 打分客户端
│   └── LangfuseExperimentClient.java # Langfuse 实验管理
├── judge/                          # LLM Judge 核心
│   ├── JudgeDimension.java          # 评测维度枚举
│   ├── JudgeResult.java             # Judge 打分结果
│   └── JudgeService.java           # Judge LLM 调用服务
├── dimensions/                     # 十大评测维度实现
│   ├── ToolSelectionEvaluationTest.java
│   ├── RagRecallEvaluationTest.java
│   ├── AnswerCompletenessEvaluationTest.java
│   ├── PromptAssemblyEvaluationTest.java
│   ├── SubAgentIsolationEvaluationTest.java
│   ├── MultiTurnCoherenceEvaluationTest.java
│   ├── HallucinationEvaluationTest.java      # NEW
│   ├── FaithfulnessEvaluationTest.java        # NEW
│   ├── SafetyEvaluationTest.java              # NEW
│   └── SkillSelectionEvaluationTest.java      # NEW
├── report/                         # 报告生成
│   └── HtmlReportWriter.java       # J2Html HTML 报告 # NEW
├── harness/                        # 本地评测 Harness
│   └── LocalAgentEvaluationHarnessTest.java
├── retrieval/                      # RAG 检索评测（Layer 2）
│   ├── RetrievalEvaluator.java      # IR 指标计算引擎
│   ├── RetrievalEvaluationCase.java # 检索用例 record
│   ├── RetrievalEvaluationReport.java # 检索指标报告
│   ├── RetrievalEvaluatorTest.java  # 检索指标测试
│   └── HydeRetrievalEvaluationTest.java # HyDE 对比测试
├── calibration/                    # Judge 校准
│   └── HumanAnnotationCalibrator.java
└── cost/                           # 成本追踪
    └── CostTracker.java

src/test/resources/evaluation/
├── evaluation-dataset.json          # Agent 评测数据集（710 条）
├── retrieval-eval-dataset.json      # RAG 检索评测数据集（321 条）
└── judge-prompts/                   # Judge LLM 的 prompt 模板
    ├── tool_selection.txt
    ├── rag_recall.txt
    ├── answer_completeness.txt
    ├── prompt_assembly.txt
    ├── subagent_isolation.txt
    ├── multi_turn_coherence.txt
    ├── hallucination.txt              # NEW
    ├── faithfulness.txt               # NEW
    ├── safety.txt                     # NEW
    └── skill_selection.txt            # NEW
```

---

## 3. LLM-as-Judge 框架

### 3.1 核心思路

传统评测依赖精确匹配（exact match）或正则规则，但 Agent 的输出天然是非结构化的自然语言。dawn-ai 采用 **LLM-as-Judge** 模式：用另一个 LLM 充当"裁判"，按预定义的 prompt 模板对 Agent 的输出打分。

### 3.2 JudgeService

`JudgeService` 是 Judge 框架的核心，它维护一个独立的 `ChatModel` 实例，与业务 Agent 使用的 LLM 解耦。

**关键配置项：**

| 环境变量 | 作用 | 默认值 |
|---------|------|--------|
| `JUDGE_BASE_URL` | Judge LLM 的 API 地址 | 复用 `spring.ai.openai.base-url` |
| `JUDGE_API_KEY` | Judge LLM 的 API Key | 复用 `spring.ai.openai.api-key` |
| `JUDGE_MODEL` | Judge 使用的模型 | 复用 `spring.ai.openai.chat.options.model` |

**设计要点：**

- **temperature=0.0**：确保 Judge 输出稳定、可复现
- **重试策略**：指数退避，最多 3 次（1s → 2s → 4s），应对 API 429
- **JSON 解析容错**：自动剥离 markdown code fence，解析失败时返回 score=0 而非抛异常

### 3.3 调用流程

```
EvaluationTest → JudgeService.judge(dimension, variables)
                   │
                   ├─ 1. loadPromptTemplate(dimension.promptPath())
                   │     从 classpath 加载对应维度的 prompt 模板文件
                   │
                   ├─ 2. fillTemplate(template, variables)
                   │     用 {{key}} 占位符替换实际值
                   │
                   ├─ 3. judgeChatModel.call(Prompt)
                   │     SystemMessage: "You are an evaluation judge..."
                   │     UserMessage: 填充后的 prompt
                   │
                   └─ 4. parseResponse(dimension, content)
                         解析 JSON → JudgeResult(dimension, score, reasoning)
```

### 3.4 评分类型

`JudgeDimension` 定义了两种评分模式：

| 类型 | 分值范围 | 通过阈值 | 适用场景 |
|------|---------|---------|---------|
| **BINARY** | 0.0 / 0.5 / 1.0 | score >= 1.0 | 有明确对错的维度（工具选择、Prompt 拼装、子 Agent 隔离） |
| **LIKERT** | 1 ~ 5 | score >= 3.5 | 需要程度评价的维度（RAG 召回、答案完整性、多轮连贯性） |

### 3.5 JudgeResult

```java
public record JudgeResult(JudgeDimension dimension, double score, String reasoning) {
    public boolean passed() {
        return switch (dimension.scoreType()) {
            case BINARY -> score >= 1.0;
            case LIKERT -> score >= 3.5;
        };
    }
}
```

每个 JudgeResult 包含三个信息：**维度**（哪个方面）、**分数**（多好/多差）、**推理**（为什么给这个分）。推理字段是 LLM-as-Judge 相比硬编码规则的核心优势——它告诉你 *为什么* 扣分，而不只是 *扣了多少分*。

---

## 4. 十大评测维度

**评测方法：**
- BINARY(二元测试法)：对或错的二元测试法，但常会衍生成三元等。本项目中使用 0、0.5、1 来进行错、部分对和全对评测打分。
- LIKERT(李克特量表)：多阶段测试，常将测试结果分为多阶打分。本项目中使用 1-5 范围打分

**模型：**
- Embedding Model：bge-m3-mlx-fp16  - 1024 维
- LLM：Qwen3.5-9B-MLX-4bit
- Judge Model：Qwen3.5-9B-MLX-4bit，按道理要换一个更加轻量的模型，但是本地再部署第二个模型，机器会超负荷

**优先级:**

| 优先级   | 维度                         | 判断                                                       |
| ----- | -------------------------- | -------------------------------------------------------- |
| P0    | **Safety**                 | 必须有。Agent 有工具调用能力，安全边界是上线门槛。                             |
| P0    | **Tool Selection**         | 必须有。工具选错，后面全错，这是 Agent 的核心能力。                            |
| P0    | **RAG Recall / Retrieval** | 必须有，但更适合用确定性 IR 指标，不应主要依赖 LLM Judge。                     |
| P0    | **Faithfulness**           | 必须有。回答必须忠实于检索内容，防止“看似正确但歪曲事实”。                           |
| P0/P1 | **Hallucination**          | 有必要，但和 Faithfulness 高度重叠，建议合并成 Groundedness。             |
| P1    | **Answer Completeness**    | 有必要。它衡量“有没有真正回答完整”，但优先级低于正确性和安全性。                        |
| P1    | **Multi-Turn Coherence**   | 如果 Dawn AI 的记忆/第二大脑是核心卖点，就必须保留；否则是 P1。                   |
| P1/P2 | **Skill Selection**        | 取决于 Skill 是否是核心能力。若 Skill 本质也是工具路由，可和 Tool Selection 合并。 |
| P2    | **SubAgent Isolation**     | 重要，但不适合作为 LLM Judge 语义评测，更适合确定性集成测试。                     |
| P2    | **Prompt Assembly**        | 应保留为工程回归测试，但不应作为核心 Agent 质量维度。用户不关心 prompt 拼装，只关心最终行为。   |

如果要做一个更干净、更有解释力的评测体系，核心保留：

1. **Safety**：不能做危险事。
2. **Action Selection**：合并 Tool Selection + Skill Selection。
3. **Retrieval Quality**：用 Recall@K / MRR / NDCG 等确定性指标。
4. **Groundedness**：合并 Faithfulness + Hallucination。
5. **Answer Quality**：以 Completeness 为主，也可包含清晰度。
6. **Context Coherence**：覆盖多轮上下文和记忆能力。

降级为工程回归的两个维度：

- **Prompt Assembly**：放到单元/集成测试里，作为“结构是否拼对”的回归保障。
- **SubAgent Isolation**：放到确定性 contract/integration test，不需要 LLM Judge。

下方 4.1 - 4.10 按优先级重排。标题中的 `[P0-1]` 表示该优先级下的排序；`P0/P1`、`P1/P2` 表示跨优先级维度。

### 4.1 [P0-1] Safety（安全性）

**评什么：** Agent 面对有害、不当或对抗性查询时，是否正确拒绝或转移。

**评分类型：** BINARY

**测试方法：** 采用**模拟验证**模式（与 SubAgent Isolation 相同），从用例 context 中提取 riskCategory，构建模拟场景和观察结果，交由 Judge 评分。

**风险类别覆盖：** illegal_activity, harmful_content, privacy_violation, misinformation, prompt_injection, jailbreak_attempt, self_harm, hate_speech, personal_data_extraction, social_engineering

**数据集特点：** 70% 有害查询 + 30% 边界 case（看似有害但实际合法的安全研究/医疗问题）。

**关键代码：** `SafetyEvaluationTest.java`

---

### 4.2 [P0-2] Tool Selection（工具选择正确性）

**评什么：** Agent 面对用户查询时，是否选对了工具。

**评分类型：** BINARY

**测试方法：**
1. 从数据集加载用例，每条用例包含 query、可用工具列表、期望工具列表
2. 真实调用 `agentOrchestrator.streamChat()` 执行 Agent
3. 从 Agent 执行步骤中提取实际调用的工具名
4. 将 query、可用工具、期望工具、实际工具 四个变量注入 Judge prompt
5. Judge LLM 判定选择是否正确

**Judge 评分规则：**
- **1.0**：调用了所有期望工具（顺序无关，允许超集）
- **0.5**：调用了部分期望工具
- **0.0**：调用了错误工具，或该调工具时没调

**数据集示例：**
```json
{
  "id": "ts_015",
  "dimension": "tool_selection",
  "query": "查一下我们知识库里有没有关于数据备份的文档，然后帮我在服务器上执行备份脚本",
  "context": {
    "availableTools": ["knowledgeSearchTool", "bashTool", "calculatorTool"]
  },
  "expected": {
    "tools": ["knowledgeSearchTool", "bashTool"],
    "answerCriteria": "应先调用 knowledgeSearchTool 查找备份文档，再调用 bashTool 执行备份脚本",
    "docIds": null
  }
}
```

**关键代码：** `ToolSelectionEvaluationTest.java`

---

### 4.3 [P0-3] RAG Recall（RAG 召回相关性）

**评什么：** 当 Agent 通过 `knowledgeSearchTool` 检索知识库时，是否召回了正确的文档。

**评分类型：** LIKERT (1-5)

**测试方法：**
1. 从用例的 `context.ragDocuments` 中取出测试文档，**预索引到 VectorStore**
2. 真实调用 Agent stream
3. 从 Agent 步骤中筛选 `knowledgeSearchTool` 的 output，用正则提取返回的文档 ID
4. 与期望的 `expected.docIds` 对比，注入 Judge prompt 评分

**Judge 评分规则：**

| 分数 | 含义 |
|------|------|
| 5 | 所有期望文档都被召回，top 结果无噪声 |
| 4 | 所有期望文档都被召回，混入了一些无关文档 |
| 3 | 大部分期望文档被召回（>=70%），有遗漏 |
| 2 | 少量期望文档被召回（<50%），明显缺失 |
| 1 | 没有召回任何期望文档 |

**通过标准：** score >= 3.0（注意：这里用 3.0 而非 JudgeResult 默认的 3.5，因为 RAG 召回用的是 AssertJ 的 `isGreaterThanOrEqualTo(3.0)`）

**关键代码：** `RagRecallEvaluationTest.java`

**RagRecall 确定性化**

 - 不再调用 evaluate(...) 让 Judge 打分。
 - 用 retrievedDocIds vs expectedDocIds 计算命中。
 - 最小实现可先做： - expected 为空：retrieved 应为空或不包含 eval case 文档。
 - expected 非空：retrievedDocIds 包含所有 expected。
 - 后续再升级为 Recall@K / MRR / NDCG。
 - 验证：mvn test -Dtest=RagRecallEvaluationTest -Dgroups=evaluation -Dexcluded.test.groups= -Deval.limit=10

---

### 4.4 [P0-4] Faithfulness（答案忠实度）

**评什么：** Agent 的答案是否忠实于检索到的文档（不矛盾、不扭曲、不遗漏关键限制条件）。

**评分类型：** LIKERT (1-5)

**与 Hallucination 的区别：** Hallucination 检测"编造"，Faithfulness 检测"扭曲"。例如文档说"7 个自然日内可退款"，答案说"10 个工作日"——这不是编造新事实，而是歪曲了已有事实。

**测试方法：** 与 Hallucination 类似，预索引文档 → Agent stream → 提取检索到的文档内容 → Judge 判断答案是否忠实于源文档。

**关键代码：** `FaithfulnessEvaluationTest.java`

---

### 4.5 [P0/P1-1] Hallucination（幻觉检测）

**评什么：** Agent 是否编造了不在提供文档中的事实（虚假统计、伪造引用、凭空杜撰的信息）。

**评分类型：** LIKERT (1-5)

**测试方法：**
1. 预索引 ragDocuments 到 VectorStore
2. 真实调用 Agent stream，提取 answer
3. 将 query、answer、source_documents（ragDocuments 原文）注入 Judge prompt
4. Judge 判断 answer 中是否包含未基于文档的声明

**Judge 评分规则：**

| 分数 | 含义 |
|------|------|
| 5 | 无幻觉，所有信息均有文档依据 |
| 4 | 轻微修饰但不影响准确性 |
| 3 | 包含一些无依据的声明 |
| 2 | 显著编造，包含虚假数据或引用 |
| 1 | 大部分内容为幻觉 |

**关键代码：** `HallucinationEvaluationTest.java`

---

### 4.6 [P1-1] Answer Completeness（答案完整性）

**评什么：** Agent 最终返回给用户的答案，是否涵盖了所有应该包含的信息点。

**评分类型：** LIKERT (1-5)

**测试方法：**
1. 预索引测试文档到 VectorStore
2. 真实调用 Agent stream，获取 `finalAnswer`
3. 将 query、answer、answer_criteria 注入 Judge prompt

**Judge 评分规则：**

| 分数 | 含义 |
|------|------|
| 5 | 覆盖所有信息点，结构良好且准确 |
| 4 | 覆盖大部分信息点（>=80%），仅缺少细节 |
| 3 | 覆盖核心信息点（>=60%），缺少重要方面 |
| 2 | 覆盖少量信息（<50%），显著缺失 |
| 1 | 答案不相关、错误、或完全未回答 |

**通过标准：** score >= 3.0

**关键代码：** `AnswerCompletenessEvaluationTest.java`

---

### 4.7 [P1-2] Multi-Turn Coherence（多轮对话连贯性）

**评什么：** 在有对话历史的情况下，Agent 是否正确利用了上下文来回答当前问题。

**评分类型：** LIKERT (1-5)

**测试方法：**
1. 从用例的 `context.memorySnapshot` 中取出历史对话消息
2. 通过 `MemoryService.addMessage()` 将历史消息预填充到 Redis
3. 真实调用 Agent stream 发送当前 query
4. 将 query、memory_snapshot、answer、answer_criteria 注入 Judge prompt

**Judge 评分规则：**

| 分数 | 含义 |
|------|------|
| 5 | 完美引用先前对话上下文，展现清晰的对话流理解 |
| 4 | 正确使用了大部分上下文，有小遗漏但不影响质量 |
| 3 | 对部分上下文有意识，但遗漏了早期轮次的重要细节 |
| 2 | 几乎没有引用对话历史，像是重新开始的对话 |
| 1 | 完全忽略对话历史，或与已建立的事实矛盾 |

**通过标准：** score >= 3.0

**关键代码：** `MultiTurnCoherenceEvaluationTest.java`

---

### 4.8 [P1/P2-1] Skill Selection（Skill 选择正确性）

**评什么：** Agent 面对用户查询时，是否正确识别并调用了最匹配的 Skill。

**评分类型：** BINARY

**测试方法：** 与 ToolSelection 模式相同 — 真实调用 Agent stream → 提取实际选择的 Skill → 与期望的 Skill 对比。

**数据集特点：** 可用 Skill 池包括 data_analysis, code_review, summarization, translation, creative_writing, research, debugging, deployment, monitoring, documentation。每条用例随机选取 3-6 个可用 Skill。40% 单 Skill + 20% 多 Skill + 20% 无需 Skill + 20% 模糊 case。

**关键代码：** `SkillSelectionEvaluationTest.java`

---

### 4.9 [P2-1] SubAgent Isolation（子 Agent 上下文隔离）

**评什么：** 子 Agent 执行时是否与主 Agent 的 StepCollector 正确隔离：步骤不泄露、超时优雅降级、派发数量限制。

**评分类型：** BINARY

**测试方法：** 该维度**不做真实 Agent 调用**，而是基于设计规格验证。测试从用例的 `context.subAgentBehavior` 中提取行为参数，根据 case ID 构建模拟的观测结果：

| Case ID | 验证场景 | 构建逻辑 |
|---------|---------|---------|
| `subagent_001` | StepCollector 隔离 | 主 Agent 前后步骤数 + 子 Agent 步骤数，断言子步骤不在主 Collector |
| `subagent_002` | 超时降级 | 配置超时 vs 实际耗时，验证超时后主 Agent 继续正常 |
| `subagent_003` | 派发上限 | maxDispatches vs attempted，验证超限拒绝 |

**Judge 评分规则：**
- **1.0**：隔离约束完全满足
- **0.0**：任何隔离约束被违反

**关键代码：** `SubAgentIsolationEvaluationTest.java`

---

### 4.10 [P2-2] Prompt Assembly（Prompt 拼装正确性）

**评什么：** `AgentOrchestrator.buildSystemPrompt()` 是否按照配置正确拼装了 system prompt 的各个段落（角色定义、用户画像、主题约束、Skill 目录、子 Agent 目录、执行计划等）。

**评分类型：** BINARY

**测试方法：**
1. 从用例的 `context.promptSegments` 获取期望存在的段落关键词
2. 通过反射调用 `AgentOrchestrator.buildSystemPrompt()` 获取实际拼装结果
3. Judge LLM 对 actual prompt 做**语义匹配**（而非精确字符串匹配），逐一检查期望段落是否存在

**特殊处理：**
- `prompt_assembly_002` 会带执行计划（`PlanStep` list）和 topicId，验证计划注入是否正确
- null 值的段落会被跳过，不参与检查

**Judge 评分规则：**
- **1.0**：所有期望段落关键词在实际 prompt 中都能语义匹配到
- **0.5**：大部分存在但缺一个
- **0.0**：多个段落缺失或 prompt 格式异常

**关键代码：** `PromptAssemblyEvaluationTest.java`

---

## 5. RAG 检索专项评测

### 5.1 与 LLM-as-Judge 评测的区别

Layer 3 的 RAG Recall 维度通过 Judge LLM 做定性评价（"召回得好不好"），而 Layer 2 的 RAG 检索评测直接计算 **IR（Information Retrieval）指标**，给出精确的数值度量。

### 5.2 RetrievalEvaluator

`RetrievalEvaluator` 接收三个参数：
- `cases`: 检索评测用例列表
- `retriever`: 任意的检索函数 `Function<RetrievalRequest, List<Document>>`
- `k`: top-K 截断

它对每条用例调用 retriever，然后计算以下 IR(Information Retrieval) 信息检索指标的平均值：

| 指标               | 全称                                         | 公式                         | 含义                 |
| ---------------- | ------------------------------------------ | -------------------------- | ------------------ |
| **Recall@K**     | Recall at K                                | `命中的期望文档数 / 期望文档总数`        | 期望文档中有多少被召回了       |
| **Precision@K**  | Precision at K                             | `命中的期望文档数 / K`             | top-K 结果中有多少是相关的   |
| **Noise Rate@K** | Noise Rate at K（= 1 - Precision@K ）        | `1 - Precision@K`          | top-K 结果中有多少是噪声    |
| **Hit Rate@K**   | Hit Rate at K                              | `top-K 中至少有一个期望文档 ? 1 : 0` | 是否至少召回了一个          |
| **MRR@K**        | Mean Reciprocal Rank at K                  | `1 / 第一个命中文档的排名`           | 第一个相关文档排多前         |
| **NDCG@K**       | Normalized Discounted Cumulative Gain at K | `DCG@K / IDCG@K`           | 排序质量（越靠前的相关文档贡献越大） |

### 5.3 检索评测数据集

`retrieval-eval-dataset.json` 包含 **321 条**用例，每条结构：

```json
{
  "query": "退款政策",
  "expectedDocIds": ["doc-billing-refund-policy"],
  "hardNegativeDocIds": ["doc-billing-pricing-tiers"],
  "metadataFilters": {},
  "mockRankedDocIds": ["doc-billing-refund-policy", "doc-billing-pricing-tiers", "doc-support-ticket-escalation"]
}
```

| 字段 | 作用 |
|------|------|
| `expectedDocIds` | 正确答案：应该被召回的文档 |
| `hardNegativeDocIds` | 困难负例：语义相近但不相关的文档，用来检验检索器是否能区分 |
| `metadataFilters` | 元数据过滤条件（如 `{"category": ["support"]}`），测试过滤检索 |
| `mockRankedDocIds` | 模拟的排序结果，供 Local Harness 使用 |

**数据集设计亮点：**
- ~120 条**短关键词查询**（如 "退款政策"、"redis fallback"）
- ~120 条**自然语言长查询**（如 "How to debug RAG retrieval issues?"）
- ~60+ 条**带 metadata filter 的查询**（如 `{"category": ["billing"]}`），测试分类过滤场景
- 覆盖 12 个领域：billing, auth, security, memory, rag, agent, tool, observability, eval, api, config, support

### 5.4 HyDE 检索对比评测

`HydeRetrievalEvaluationTest` 使用同一个 baseline 检索器，对比 HyDE 改写前后的检索效果：

```
baseline:  原始 query → 检索器 → 结果
with HyDE: 原始 query → HyDE 改写 → 检索器 → 结果

断言: withHyde.recall > baseline.recall
      withHyde.mrr > baseline.mrr
      withHyde.ndcg > baseline.ndcg
```

这个测试验证了 HyDE（Hypothetical Document Embedding）策略对欠指定 / 改述查询的检索改善效果。

---

## 6. Local Evaluation Harness

### 6.1 设计目标

Local Harness 解决的核心问题是：**如何在没有外部依赖（无 LLM API、无 PgVector、无 Redis）的情况下验证 Agent 核心链路？**

### 6.2 架构

```
LocalAgentEvaluationHarnessTest
├── SqliteHarnessStore      → 替代 PgVector，用 SQLite 存文档和排序
├── MockAiRouter            → 替代 LLM 路由，基于关键词规则选工具
├── ToolQueue               → 模拟工具编排队列（含 maxSize 限制）
├── InMemoryMemoryStore     → 替代 Redis Memory，纯内存 Map
├── LocalAgentApi           → 组合上述组件，模拟完整 Agent API
└── BashTool（只读模式）     → 验证安全模式下写操作被拒绝
```

### 6.3 SqliteHarnessStore

用 SQLite 替代 PgVector，schema 极简：

```sql
CREATE TABLE documents (id TEXT PRIMARY KEY, content TEXT NOT NULL, category TEXT NOT NULL);
CREATE TABLE rankings (query TEXT NOT NULL, rank INTEGER NOT NULL, doc_id TEXT NOT NULL);
CREATE TABLE api_events (session_id TEXT NOT NULL, query TEXT NOT NULL, route TEXT NOT NULL, answer TEXT NOT NULL);
```

- `documents`: 存放评测文档
- `rankings`: 存放预定义的检索排序（来自数据集的 `mockRankedDocIds`）
- `api_events`: 记录 API 调用日志

`load()` 方法从 321 条 `RetrievalEvaluationCase` 中提取所有文档 ID（期望文档 + 困难负例 + 模拟排序），插入 SQLite。`retrieve()` 方法按 query 和 topK 查询预定义排序。

### 6.4 MockAiRouter

基于关键词的确定性路由，映射到项目中**实际存在的工具**：

```java
String normalized = query.toLowerCase();
boolean web = normalized.contains("搜索") || normalized.contains("新闻") || normalized.contains("search") || normalized.contains("latest");
boolean bash = normalized.contains("服务器") || normalized.contains("查看") || normalized.contains("命令") || normalized.contains("server");
if (web && bash) return List.of("webTool", "bashTool");
if (web) return List.of("webTool");
if (bash) return List.of("bashTool");
return List.of("knowledgeSearchTool");
```

### 6.5 核心验证链路

测试 `localHarness_validatesCoreAgentLinks` 一次性验证了：

1. **RAG 检索精度**：321 条用例在 SQLite 模拟检索上的 Recall@3、Precision@3、HitRate、MRR、NDCG 全部断言通过阈值
2. **BashTool 安全模式**：`touch blocked.txt` → exitCode=-1 + 错误信息包含"只读安全模式"；`printf harness-ok` → exitCode=0 + 正确输出
3. **多工具协同**：查询"搜索最新 AI 新闻，然后查看服务器 /tmp 目录" → route=tool_queue, tools=[webTool, bashTool]
4. **知识检索**：查询"退款政策" → tools=[knowledgeSearchTool], retrievedDocIds 首条为 "doc-billing-refund-policy"
5. **Memory 追踪**：两次 API 调用后，session-1 的 memory history 为 4 条（2 轮 user+assistant）
6. **ToolQueue 上限**：入队 3 个工具但 maxSize=2 → 抛出 IllegalStateException
7. **本地报告**：JSON 报告写入 `target/evaluation-reports/`，包含 RAG 指标和 API 响应

### 6.6 输出报告格式

```json
{
  "harness": "local-agent",
  "storage": "temporary-sqlite",
  "memory": "in-memory",
  "rag": {
    "caseCount": 321,
    "recallAtK": 1.0,
    "precisionAtK": 0.3364,
    "noiseRateAtK": 0.6636,
    "hitRateAtK": 1.0,
    "mrrAtK": 0.9984,
    "ndcgAtK": 0.9989
  },
  "apiResponses": [...]
}
```

---

## 7. 评测基础设施

### 7.1 AbstractEvaluationTest

所有维度评测测试类的基类，统一了以下行为：

| 方法 | 作用 |
|------|------|
| `dimension()` | 抽象方法，子类返回自己的 `JudgeDimension` |
| `loadCases()` | 按 dimension ID 从 JSON 数据集中筛选用例 |
| `sleepBetweenCases()` | 每条用例间隔 10 秒，避免 LLM API 429 |
| `evaluate(evalCase, variables)` | 调用 JudgeService.judge()，收集结果到 ALL_RESULTS |
| `streamAgent(sessionId, query)` | 调用真实 Agent stream，提取 finalAnswer 和 steps |
| `writeScoreToLangfuse(traceId, result)` | 可选：将评分回写 Langfuse |
| `printSummary()` | @AfterAll，打印汇总报告并写入本地 JSON |

**运行 ID：** 每次评测运行生成一个 UUID 作为 `RUN_ID`，用于报告文件命名和 Langfuse 关联。

**本地报告路径：** `target/evaluation-reports/{RUN_ID}-{dimension_id}.json`

### 7.2 EvaluationCase

评测用例的数据结构：

```java
public record EvaluationCase(
    String id,              // 用例唯一标识，如 "tool_select_001"
    String dimension,       // 所属维度，如 "tool_selection"
    String query,           // 用户查询
    Map<String, Object> context,  // 上下文（可用工具、RAG文档、memory快照等）
    Expected expected       // 期望结果
) {
    public record Expected(
        List<String> tools,         // 期望调用的工具
        String answerCriteria,      // 答案评判标准
        List<String> docIds         // 期望召回的文档 ID
    ) {}
}
```

### 7.3 EvaluationReport

汇总所有 JudgeResult，提供：
- `averageScore()`：全维度平均分
- `passRate()`：通过率（BINARY >= 1.0, LIKERT >= 3.5）
- `dimensionStats()`：按维度分组的 DoubleSummaryStatistics（avg/min/max/count）
- `summary()`：格式化的文本报告

输出示例：
```
=== Evaluation Report ===
Total: 18 cases, Pass rate: 83.3%, Avg score: 3.72
  tool_selection: avg=0.89, min=0.5, max=1.0, count=8
  rag_recall: avg=4.20, min=3.0, max=5.0, count=4
  answer_completeness: avg=3.50, min=2.0, max=5.0, count=3
  ...
```

---

## 8. 可观测性集成

### 8.1 Langfuse 打分

`LangfuseScoringClient` 通过 Langfuse REST API 实现：

| 操作 | API | 用途 |
|------|-----|------|
| `score(traceId, result)` | `POST /api/public/scores` | 给 trace 打分 |
| `ensureDataset(name, description)` | `POST /api/public/datasets` | 创建评估数据集 |
| `addDatasetItem(datasetName, evalCase)` | `POST /api/public/dataset-items` | 添加数据集条目 |
| `linkTraceToDatasetItem(...)` | `POST /api/public/dataset-run-items` | 将 trace 关联到 experiment run |

**认证方式：** Basic Auth（publicKey:secretKey 的 Base64 编码）

**默认配置：**
```properties
LANGFUSE_BASE_URL=http://localhost:3001
LANGFUSE_PUBLIC_KEY=pk-lf-dawn-dev
LANGFUSE_SECRET_KEY=sk-lf-dawn-dev
```

### 8.2 Langfuse Experiment

`LangfuseExperimentClient` 封装了 Langfuse 的 Dataset / Experiment 功能，用于跨版本对比：

```
initExperiment("v1.2.0")
  → ensureDataset("dawn-ai-evaluation")

syncCases(cases)
  → 逐条 addDatasetItem()

运行评测...

recordResult("v1.2.0", caseId, traceId, result)
  → score(traceId, result)         # 打分
  → linkTraceToDatasetItem(...)    # 关联到 run
```

在 Langfuse Dashboard 的 Dataset 页面，可以对比不同 `runName`（如 v1.1.0 vs v1.2.0）的评分趋势。

### 8.3 CostTracker

评估运行的成本追踪器，统计 LLM API 调用的 token 消耗和费用：

```java
CostTracker tracker = new CostTracker();           // 默认定价
// 或自定义定价
CostTracker tracker = new CostTracker(0.003, 0.015); // input/output per 1K tokens

tracker.recordCall("gpt-4o", 1500, 300);
tracker.recordCall("gpt-4o-mini", 800, 150);

CostReport report = tracker.summary();
// 输出：总调用次数、input/output tokens、按模型分的明细、估算总成本
```

### 8.4 HumanAnnotationCalibrator

用于验证 Judge LLM 与人类标注的一致性。

**工作流：**
1. 运行评测，生成 judge results
2. `exportForAnnotation()` 导出待标注的 JSON 模板，每条含 caseId、query、judgeScore、judgeReasoning
3. 人工填写 `humanScore` 和 `humanComment`
4. `calibrate()` 计算一致性指标

**输出指标：**

| 指标 | 含义 | 备注 |
|------|------|------|
| **Accuracy** | 人机评分一致的比例 | 以 score >= 3.0 为通过阈值二值化 |
| **Cohen's Kappa** | 考虑偶然一致的校正系数 | κ > 0.6 表示较好一致性 |
| **MAE** | 平均绝对误差 | 评分差异的平均大小 |
| **Discrepancies** | 显著偏差列表 | \|judgeScore - humanScore\| >= 1.5 的用例 |

---

## 9. 评测数据集

### 9.1 Agent 评测数据集

路径：`src/test/resources/evaluation/evaluation-dataset.json`

按 dimension 字段组织，共 **710 条**用例覆盖 10 个维度。各维度分布：

| 维度 | 用例数 |
|------|--------|
| tool_selection | 100 |
| rag_recall | 80 |
| answer_completeness | 80 |
| multi_turn_coherence | 80 |
| hallucination | 80 |
| faithfulness | 80 |
| safety | 60 |
| skill_selection | 60 |
| prompt_assembly | 50 |
| subagent_isolation | 40 |

**加载方式：**
- `EvaluationDatasetLoader.loadAll()`：加载全部
- `EvaluationDatasetLoader.loadByDimension("tool_selection")`：按维度筛选

### 9.2 RAG 检索评测数据集

路径：`src/test/resources/evaluation/retrieval-eval-dataset.json`

**321 条**用例，结构见 5.3 节。直接由 `LocalAgentEvaluationHarnessTest` 和 `RetrievalEvaluatorTest` 使用。覆盖 12 个领域分类，包含短关键词查询（120 条）、自然语言长查询（120 条）、带 metadata filter 的查询（60+ 条）。

### 9.3 Judge Prompt 模板

路径：`src/test/resources/evaluation/judge-prompts/`

每个维度一个 `.txt` 文件，使用 `{{variable}}` 占位符。所有模板遵循统一结构：

```
角色定义 → Task 说明 → Input（变量注入）→ Scoring Rules → Response Format（JSON）
```

---

## 10. 如何运行评测

### 10.1 前置条件

**Layer 1（Local Harness）—— 无外部依赖：**
```bash
mvn test -pl . -Dtest=LocalAgentEvaluationHarnessTest
```

**Layer 2（RAG 检索评测）—— 无外部依赖：**
```bash
mvn test -pl . -Dtest=RetrievalEvaluatorTest
mvn test -pl . -Dtest=HydeRetrievalEvaluationTest
```

**Layer 3（LLM-as-Judge）—— 需要 LLM API + 数据库 + Redis：**
```bash
# 需要先启动依赖服务（PgVector、Redis）
# 需要配置 LLM API 密钥

# 跑单个维度，默认跑前5个
mvn test -pl . -Dtest=ToolSelectionEvaluationTest -Dexcluded.test.groups=

# 打乱顺序，添加参数
-Deval.shuffle=true

# 跑指定次数个
-Deval.limit=100

# 跑所有维度
mvn test -pl . -Dtest="com.dawn.ai.evaluation.dimensions.*"
```

### 10.2 环境变量

| 变量                    | 必需      | 默认值                   | 说明                |
| --------------------- | ------- | --------------------- | ----------------- |
| `JUDGE_BASE_URL`      | Layer 3 | 复用 spring.ai 配置       | Judge LLM API 地址  |
| `JUDGE_API_KEY`       | Layer 3 | 复用 spring.ai 配置       | Judge LLM API Key |
| `JUDGE_MODEL`         | Layer 3 | 复用 spring.ai 配置       | Judge 模型名         |
| `LANGFUSE_BASE_URL`   | 可选      | http://localhost:3001 | Langfuse 地址       |
| `LANGFUSE_PUBLIC_KEY` | 可选      | pk-lf-dawn-dev        | Langfuse 公钥       |
| `LANGFUSE_SECRET_KEY` | 可选      | sk-lf-dawn-dev        | Langfuse 私钥       |

### 10.3 查看结果

- **控制台日志**：每条用例的 score、passed、reasoning 实时输出
- **本地 JSON 报告**：`target/evaluation-reports/{RUN_ID}-{dimension}.json`
- **本地 HTML 报告**：`target/evaluation-reports/{RUN_ID}-{dimension}.html`（自动生成，包含 Summary Cards、维度表格、可折叠用例详情）
- **Langfuse Dashboard**（可选）：http://localhost:3001 → Datasets → dawn-ai-evaluation

---

## 11. 扩展评测维度

添加新维度需要四步：

### Step 1: 在 JudgeDimension 中添加枚举值

```java
HALLUCINATION("hallucination", "evaluation/judge-prompts/hallucination.txt", ScoreType.LIKERT);
```

### Step 2: 编写 Judge Prompt 模板

在 `src/test/resources/evaluation/judge-prompts/hallucination.txt` 中编写模板，遵循现有格式：角色 → Task → Input → Scoring Rules → Response Format。

### Step 3: 添加数据集用例

在 `evaluation-dataset.json` 中添加 `"dimension": "hallucination"` 的用例。

### Step 4: 编写测试类

继承 `AbstractEvaluationTest`，实现 `dimension()` 方法，编写测试方法。测试方法的模式固定：加载用例 → 执行 Agent（或构建观测结果）→ 组装变量 → 调用 evaluate() → 断言。

---

## 12. 当前局限与改进方向

| 类别 | 现状 | 状态 |
|------|------|------|
| **CI 集成** | 纯手动触发 | 待改进 — 接入 CI pipeline 定期跑回归 |
| **SubAgent Isolation** | 模拟验证，非真实 Agent 执行 | 待改进 — 需要完整的 Agent 编排环境做端到端测试 |
| ~~**数据集规模**~~ | ~~非 RAG 维度每个仅 3-8 条~~ → **710 条评测 + 321 条检索 = 1031 条** | **已解决** |
| ~~**安全/幻觉**~~ | ~~无相关维度~~ → **Hallucination + Faithfulness + Safety + Skill Selection 四个新维度** | **已解决** |
| **延迟基准** | CostTracker 只追踪 token | 待改进 — 添加 TTFT、端到端延迟采集 |
| **回归检测** | 无 baseline 快照 | 待改进 — 建立指标基线，自动检测回归 |
| **Langfuse** | 客户端已实现但未配通 | 待改进 — 配通 docker-compose 并跑通完整实验流程 |
| ~~**报告可视化**~~ | ~~JSON + 控制台日志~~ → **J2Html HTML 报告（Summary Cards + 维度表格 + 可折叠用例详情）** | **已解决** |
