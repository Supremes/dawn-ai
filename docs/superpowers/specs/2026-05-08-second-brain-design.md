# Second Brain：个人研究知识库助手 设计文档

**日期**：2026-05-08
**状态**：已批准，待实现

---

## 背景与目标

Dawn AI 目前具备 ReAct Agent、3 层记忆体系、高级 RAG 管道、用户画像等完整能力，但以"通用 chat robot"形态呈现，无法体现各技术选型的必要性。

本设计将项目重新落地为**个人研究知识库助手（Second Brain）**：用户把自己的私有资料（论文、笔记、PDF）上传到特定研究主题下，AI 能回答私有知识、分析覆盖盲点、生成学习蒸馏报告。

核心价值主张：
- RAG 必要：用户私有资料 LLM 完全不知道
- 3 层记忆必要：跨会话追踪学习轨迹，AI 真正"认识"这个用户
- ReAct 必要：gap analysis 需要多步推理（检索 → 对比 → 归纳盲点）
- 用户画像必要：记录研究兴趣、偏好解释风格

---

## 核心场景

### B：渐进式研究助手（Gap Analysis）
用户围绕一个研究主题上传资料，Agent 分析已覆盖概念，识别知识盲点，提示"还差什么"。

### C：知识蒸馏回顾（用户触发）
用户主动发起，Agent 结合长期记忆和概念索引生成学习进展报告。

---

## 架构设计

### 原则：增量扩展，零重写

所有现有组件保持不变，仅做扩展：

```
现有组件（复用）                    新增组件
────────────────────────           ──────────────────────────
AgentOrchestrator (ReAct)          GapAnalysisTool
KnowledgeSearchTool (扩展)         ProgressReportTool
RagService (入参扩展)              ConceptExtractorService
3-layer Memory (不动)              TopicService
UserProfileService (不动)          POST /api/v1/topics
Spring AI ChatClient (不动)        POST /api/v1/topics/{id}/ingest
```

### 数据存储分配

| 数据 | 存储 | Key 格式 |
|---|---|---|
| Topic 元信息（name, description, researchIntent） | Redis Hash | `topic:{topicId}:meta` |
| 概念索引 | Redis Set | `topic:{topicId}:concepts` |
| 文档归属 | pgvector metadata | `topicId` 字段 |
| 学习轨迹摘要 | pgvector（Long-term Memory） | sessionId = `{userId}:{topicId}` |

---

## API 设计

### 新增接口

```
POST /api/v1/topics
  Body: { name, description, researchIntent }
  → 写入 Redis Hash topic:{topicId}:meta
  → 返回 topicId

POST /api/v1/topics/{topicId}/ingest
  Body: multipart/form-data (file) 或 JSON (text)
  → 验证 topicId 存在
  → 调用 RagService.ingest()，metadata 注入 topicId
  → 发布 DocumentIngestedEvent，异步触发概念抽取
```

### 现有接口扩展

```
POST /api/v1/chat
  Body 新增可选字段: { topicId? }
  → topicId 注入 system prompt
  → KnowledgeSearchTool 携带 topicId 做 metadata filter
  → 无 topicId 时行为与现在完全一致（向后兼容）

POST /api/v1/rag/ingest
  Body 新增可选字段: { topicId? }
  → /topics/{id}/ingest 内部复用此 Service 层逻辑
```

---

## 工具链设计

### topicId 注入方式

`topicId` 通过 system prompt 注入 Agent 上下文，LLM 调用工具时自动带上，无需 ThreadLocal：

```
你当前在帮助用户研究主题：{topicName}（topicId: {topicId}）
调用工具时，topicId 参数始终使用此值。
```

### GapAnalysisTool

- **触发条件**：用户问"还缺什么资料" / "覆盖了哪些内容" / "有什么盲点"
- **输入参数**：`topicId`, `researchQuestion`
- **执行逻辑**：
  1. `SMEMBERS topic:{topicId}:concepts` → 已有概念集合
  2. 读 `topic:{topicId}:meta.researchIntent`
  3. LLM 推理：该研究意图下应覆盖的概念全集
  4. diff(目标概念集, 已有概念集) → 盲点列表
- **输出**：`{ covered: [...], missing: [...], suggestions: [...] }`

### ProgressReportTool

- **触发条件**：用户问"总结学习进展" / "帮我梳理一下"
- **输入参数**：`topicId`, `userId`
- **执行逻辑**：
  1. `SMEMBERS topic:{topicId}:concepts` → 概念覆盖全貌
  2. 查询 Long-term Memory（pgvector），filter: `sessionId = {userId}:{topicId}`
  3. LLM 综合生成报告
- **输出**：Markdown 格式报告，含概念图谱、学习轨迹、下一步建议

### KnowledgeSearchTool（扩展）

新增可选参数 `topicId`，透传给 `RagService` 做 metadata filter。
有 topicId → 只搜该 Topic 文档；无 topicId → 全库检索，完全向后兼容。

### ConceptExtractorService（后台服务，非 Agent Tool）

- **触发**：监听 `DocumentIngestedEvent`（异步）
- **逻辑**：LLM 从新入库的 chunks 中提取关键概念 → `SADD topic:{topicId}:concepts`
- **容错**：抽取失败不影响文档入库，可重试；概念索引为空时工具返回提示

---

## 完整数据流

### 场景一：文档上传

```
POST /api/v1/topics/{topicId}/ingest
  → TopicController 验证 topicId（Redis lookup）
  → RagService.ingest()（topicId 写入 chunk metadata）
  → 分块 → 向量化 → 存 pgvector
  → 发布 DocumentIngestedEvent
      └─ ConceptExtractorService（async）
            LLM 抽取概念 → SADD topic:{topicId}:concepts
```

### 场景二：Topic 限定问答

```
POST /api/v1/chat { message, sessionId, topicId }
  → system prompt 注入 topicId + topicName
  → AgentOrchestrator ReAct loop
      LLM → KnowledgeSearchTool(query, topicId)
               → RagService metadata filter
               → hybrid retrieval → rerank → top-K
               → LLM 综合作答
  → 对话写入 Working Memory → 正常 3-layer 流转
```

### 场景三：Gap Analysis

```
用户："我的分布式事务资料还缺什么？"
  → ReAct: GapAnalysisTool(topicId)
      → SMEMBERS topic:{topicId}:concepts → {Saga, 幂等, 补偿事务}
      → 读 researchIntent
      → LLM diff → missing: {2PC, TCC, AT模式, Seata}
      → 输出带优先级的补充建议
```

### 场景四：学习蒸馏报告

```
用户："帮我总结最近分布式事务的学习进展"
  → ReAct: ProgressReportTool(topicId, sessionId)
      → SMEMBERS topic:{topicId}:concepts → 概念全貌
      → Long-term Memory query（filter by sessionId）→ 历史摘要
      → LLM 生成报告：概念图谱 + 学习轨迹 + 推荐下一步
```

---

## 边界情况

| 情况 | 处理方式 |
|---|---|
| topicId 不存在 | TopicController 返回 400，不进 RagService |
| 文档上传但概念抽取失败 | 文档已入库，仅 Redis Set 未更新，异步重试 |
| 概念索引为空时调用 GapAnalysis | Tool 返回"暂无已索引概念，请先上传文档" |
| 无 topicId 的 chat 请求 | 全库检索，行为与现在完全一致 |
| sessionId 混用 | 约定 `{userId}:{topicId}`，文档注释写清楚，不做强校验 |

---

## 不在范围内

- Topic 之间的知识关联（跨 Topic 搜索）
- 定时推送学习报告（选择了用户主动触发）
- 前端 UI（纯后端 API 项目）
- 知识图谱（概念关系存储）
