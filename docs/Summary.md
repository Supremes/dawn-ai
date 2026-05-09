
## Tech points
  
  - 3 层记忆体系：Working(Redis) → Summary(pgvector) → Long-term(向量反思) — 跨会话持久记忆
  - ReAct Agent + 自动规划：Tool 自动发现、AOP step 追踪、pre-execution 任务规划
  - 高级 RAG 管道：混合检索(dense+sparse) + Query Rewriting + RRF + 两阶段重排序
  - 用户画像注入：User profile 自动注入 system prompt，实现个性化
  - 完整可观测：Prometheus + Grafana，token 成本追踪

## 应用方向

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

## Reference

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
