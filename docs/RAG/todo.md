Reference: 

- https://www.zerozzz.win/posts/knowledge-base-strategies

老大，基于文章内容，整理如下：

---

## ✅ 自研 Agent 集成 RAG — 注意事项 Todolist

### 🏗️ 一、架构设计阶段

- [ ] **禁止"一锅端"**：上线前先对知识资产做分类盘点（结构化/半结构化/非结构化/代码），不同类型规划不同处理管道
- [ ] **设计路由层**：即使初期只有一个知识库，也要预留路由接口；推荐从 LLM 零样本分类路由起步
- [ ] **联邦制设计**：将每类知识的 RAG 管道设计为独立可替换的模块，避免耦合

---

### ✂️ 二、切片策略选择

- [ ] **有 Markdown/HTML 标题结构** → `MarkdownHeaderTextSplitter` / `HTMLHeaderTextSplitter`，标题自动入元数据
- [ ] **普通段落文档（Wiki、博客）** → `RecursiveCharacterTextSplitter`；chunk_size 起点 512~1024 token，overlap 设 10%~20%
- [ ] **需要深度上下文（法律文书、研究报告）** → `ParentDocumentRetriever`，子块检索 + 父块返回
- [ ] **非结构化自由文本（客服、评论）** → `SemanticChunker`，保证每块主题纯净
- [ ] **时序对话/日志** → 任何策略 + 滑动窗口（设置 overlap 或自定义窗口），防止上下文断裂
- [ ] **代码文件** → 必须使用 AST Splitter（`Language.PYTHON` 等），严禁用普通字符切片
- [ ] **PDF 中含表格** → 必须用 PyMuPDF / Amazon Textract 布局感知解析，单独提取表格对象

---

### 🏷️ 三、元数据工程

- [ ] **所有文档块必须附加元数据**：来源文件名、页码/章节名、日期/版本、作者/部门
- [ ] **时序数据追加时间戳**：支持"只查过去N天"等时间过滤
- [ ] **对话数据追加参与者、会话ID**：支持"只查某用户某产品线"的精确过滤
- [ ] **法律/财务文档追加合同ID、条款号、签署日期**：支持指哪打哪式查询

---

### 🔍 四、召回策略

- [ ] **有元数据的场景优先做预过滤**，再对子集做语义搜索（不要直接全库大海捞针）
- [ ] **语义切片块用纯向量相似度搜索**即可，无需额外规则（其纯净度已保证精度）
- [ ] **Top-K 设置** 要结合 chunk_size 调整，块越小 K 可以适当调大
- [ ] **代码召回** 要确保向量化时包含函数名、Docstring、代码体三个维度

---

### 🌐 五、跨领域查询（进阶）

- [ ] **评估是否需要 Graph RAG**：若存在"X 与 Y 如何关联"类业务问题，规划知识图谱
- [ ] **Graph RAG 实施前**：预定义有限的关系类型 Schema（如 `IMPLEMENTS / REPORTED_BY / MENTIONS`），防止LLM生成混乱关系
- [ ] **做实体归一化**："支付模块"/"Payment Module"/"支付系统"需合并为同一节点
- [ ] **混合路由**：向量RAG（回答"是什么"）和 Graph RAG（回答"怎么关联"）并存，由 LLM 智能路由

---

### ⚠️ 六、常见坑（避坑清单）

- [ ] 不要把表格压平成纯文本放进向量库（数字失去结构意义）
- [ ] 不要用同一 chunk_size 处理所有文档（短文档会产生大量无意义小块）
- [ ] 不要忽略 overlap（边界处的关键信息会被切断导致召回失败）
- [ ] 不要用字符切片处理代码（函数被拦腰斩断，LLM 必然幻觉）
- [ ] 不要把对话记录按单轮切分（上下文完全丢失，"金鱼记忆"问题）
- [ ] LLM 提取图谱三元组**不是100%准确**，上线前需人工抽样校验，上游错误会污染整个图谱

---

### 📈 七、迭代路线

- [ ] **Phase 1**：选一个最高价值场景，端到端跑通 MVP，真实用户反馈驱动调优
- [ ] **Phase 2**：增加专家小队数量，引入智能路由层，建立统一性能监控
- [ ] **Phase 3**：启动实体关系提取，接入图数据库，融合混合系统



# 落地
## 现状速览（已有 ✓ / 缺口 ✗）

| 维度 | 现状 |
|---|---|
| 切片 | ✓ `OverlapTextSplitter`（token+标点边界，全局唯一）  ✗ 无 Markdown/HTML/AST/Semantic |
| 抽取 | ✓ Tika 处理 PDF/Word/Excel（`BodyContentHandler` 纯文本）  ✗ 表格压平、无页码/章节 |
| 元数据 | ✓ `source/category/docId/topicId/parentDocumentId/chunkIndex` ✗ 页码、章节、时间戳、作者、版本 |
| 召回 | ✓ Dense+BM25 混合、RRF、HyDE、QueryRewriter、Reranker、metadata 过滤(eq/in) |
| 路由 | ✓ `RetrievalRouter`（dense vs hybrid 策略）  ✗ todo 说的"知识域路由"（先分类→走哪条管道） |
| 父子检索 | ✗ 已存 `parentDocumentId`，但 retrieve 没有 expand 父块 |
| 评估 | ✓ `RetrievalEvaluator` |
| Graph RAG | ✗ 完全没有 |

## 可落地点（按 ROI 排序）

**🟢 立刻能做（小改动、不动架构）**

1. **Splitter 按 `DocumentType` 多态分发**
   `DocumentType` 枚举已存在但所有类型共用一个 Splitter。新增 `MarkdownHeaderSplitter`（标题入元数据）、给 PDF/Word 加章节/页码注入；保留 `OverlapTextSplitter` 作为兜底。→ 对应 todo 一、二的核心。

2. **`DocumentTextExtractor` 改用 XHTML handler + 提取结构化元数据**
   Tika 已能拿到 `TikaCoreProperties`（页码、标题、作者），现在被 `BodyContentHandler` 丢掉了。换成 `ToXMLContentHandler`/`SAXContentHandler` 解析后：①带页码/章节进元数据；②识别 `<table>` 单独成 chunk 并打 `contentType=table` 标记，至少做到"不混进段落里"。→ 对应 todo 二第 7 条 + 三全部 + 避坑第 1 条。

3. **父子返回（ParentDocumentRetriever 等价实现）**
   `parentDocumentId` 已写进 metadata，`RagService.retrieve` 加一个可选 expand 步：命中子块后按 `parentDocumentId` 去补回父块或前后兄弟块。配置开关即可。→ 对应 todo 二第 3 条。

4. **`RagRequest` / ingest API 接受额外元数据**
   开放 `timestamp/author/version/section` 等字段直通 `metadata`；`RetrievalRequest.metadataFilters` 已支持 in/eq 过滤，端到端立即可用。→ todo 三、四第 1 条。

**🟡 值得规划（架构性，先讨论再做）**

5. **知识域路由层**（todo 一第 2 条）
   当前 `RetrievalRouter` 只决定 dense/hybrid。要做的是再上一层：query→分类(LLM 零样本)→选不同 `RagPipeline`。建议先抽象 `RagPipeline` 接口（ingest + retrieve），让现有 RagService 成为 default pipeline，路由层留 hook，不引入实现。

6. **按 category 差异化 chunk_size / overlap**
   现在全局 `app.ai.rag.chunk-size=500`，对长法律文档和短客服记录都不合适。改成按 `category`/`DocumentType` 读不同配置。→ 避坑第 2、3、5 条。

**🔴 暂不建议**

7. **AST 代码切片**：取决于知识库是否真有代码资产，目前看不到代码 ingestion 场景，先不投入。
8. **Graph RAG / 实体抽取**：缺图数据库、缺业务驱动（"X 与 Y 如何关联"类问题），属于 todo 自己的 Phase 3，先不动。
9. **SemanticChunker**：要二次 embedding 调用，成本高，建议有客服/评论场景后再说。

## 建议的 MVP（一个迭代周期）

打包 **1 + 2 + 4**（Splitter 多态 + Tika 结构化抽取 + 元数据 API 直通）。这三条彼此正交、共享一条 ingest 链路、能立刻验证"元数据驱动过滤"在现有 retrieve 上的效果。**3（父子返回）作为 follow-up**，因为需要顺手做检索侧改动和评估对比。

要不要我针对 MVP 这三条写一份具体的设计 plan？或者你想先讨论哪一条？

---
小提示：你的提问没有语法问题，"落地的点"用得很自然 👍