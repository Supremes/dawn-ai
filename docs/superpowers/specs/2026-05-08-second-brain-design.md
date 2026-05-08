# Second Brain：个人研究知识库助手 设计文档

**日期**：2026-05-08
**状态**：已批准，待实现

---

## 背景与目标

Dawn AI 目前具备 ReAct Agent、3 层记忆体系、高级 RAG 管道、用户画像等完整能力，但以"通用 chat robot"形态呈现，无法体现各技术选型的必要性。

本设计将项目落地为**个人研究知识库助手（Second Brain）**：用户把私有资料上传到特定研究主题下，AI 能在该主题范围内回答问题、追踪学习轨迹、生成蒸馏报告。

核心价值主张：
- RAG 必要：用户私有资料 LLM 完全不知道
- 3 层记忆必要：跨会话追踪学习轨迹
- ReAct 必要：多步检索与推理
- 用户画像必要：个性化解释风格

---

## 实现方案：Topic 作纯 Metadata 标签

`topicId` 是用户自定义的字符串 tag（如 `distributed-tx`），贯穿文档入库和检索全链路。无需新建实体、无需 Redis 额外结构，完全复用现有组件。

---

## 改动清单（最小化）

### 1. RagService — 入库加 topicId

`/api/v1/rag/ingest` 请求体新增可选字段 `topicId`。
入库时把 `topicId` 写入每个 chunk 的 metadata，现有分块、向量化、存储逻辑全部不动。

```json
POST /api/v1/rag/ingest
{
  "content": "...",
  "topicId": "distributed-tx",   // 新增，可选
  "source": "saga-pattern.pdf"
}
```

### 2. KnowledgeSearchTool — 检索加 filter

新增可选参数 `topicId`，透传给 `RagService` 做 metadata filter。
- 有 `topicId` → 只检索该 tag 下的文档
- 无 `topicId` → 全库检索，完全向后兼容

### 3. ChatController — 接收 topicId

`/api/v1/chat` 请求体新增可选字段 `topicId`，
传入 `AgentOrchestrator` 后注入 system prompt：

```
你当前在帮助用户研究主题：{topicId}
调用 KnowledgeSearchTool 时，topicId 参数始终使用此值。
```

LLM 读到 system prompt 后，调用工具时自动带上 topicId。

---

## 数据流

### 文档上传

```
POST /api/v1/rag/ingest { content, topicId?, source }
  → RagService: topicId 写入 chunk metadata
  → 分块 → 向量化 → 存 pgvector（现有逻辑不动）
```

### Topic 限定问答

```
POST /api/v1/chat { message, sessionId, topicId? }
  → system prompt 注入 topicId
  → AgentOrchestrator ReAct loop
      LLM → KnowledgeSearchTool(query, topicId)
               → RagService metadata filter
               → hybrid retrieval → rerank → top-K
               → LLM 综合作答
  → 对话写入 Working Memory → 正常 3-layer 流转
```

---

## 边界情况

| 情况 | 处理方式 |
|---|---|
| topicId 无效或不存在 | 检索返回空，LLM 提示"该主题下暂无相关资料" |
| 无 topicId 的请求 | 行为与现在完全一致，向后兼容 |
| 多个主题混用同一 sessionId | 不做强校验，由调用方保证 sessionId 语义 |

---

## 不在范围内

- Topic CRUD API（topicId 由调用方自定义，无需创建）
- 概念索引与 Gap Analysis
- 学习蒸馏报告
- 定时推送
- 前端 UI
