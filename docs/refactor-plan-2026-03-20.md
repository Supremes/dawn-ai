# Dawn AI 整改计划

> 基于 `feature/react-redesign` 分支 (commit `8012f58`) 全量代码审计后制定

---

## 现状诊断（比现有 action-plan 更深一层）

现有 `docs/action-plan-2026-03-20.md` 列出了 7 个 Action，但存在三个结构性问题：

1. **测试放最后（Week 4）是经典瀑布反模式** — 每个 Phase 新增的逻辑如果不立即配套测试，后期补测时上下文已丢失，测试质量必然下降。
2. **Telemetry 作为独立 Phase 也是错的** — 可观测性是横切关注点，应随代码一起长出来，而不是事后插桩。每次改动时"顺手"加指标，边际成本最低。
3. **缺少 Phase 0（地基）** — 分支未合并、硬编码值散落、零 CI、测试基础设施缺失。在这个地基上直接盖功能，返工概率极高。

### 代码级问题清单（超出现有 action-plan 的发现）

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| A | `model = "gpt-4o"` 硬编码 | `ChatService` | 应从 Spring AI 配置读取，而非写死 |
| B | Memory 参数全部硬编码 | `MemoryService` | `MAX_HISTORY=20`, `SESSION_TTL=2h` 应提取为配置 |
| C | RAG topK=5 硬编码 | `RagService.buildContext()` | 应可配置或由调用方传入 |
| D | 无 CI Pipeline | 项目根目录 | 无 GitHub Actions，代码质量无自动化守护 |
| E | 无错误恢复策略 | `AgentOrchestrator` | LLM 调用无 retry/timeout/fallback，一次超时整个请求挂掉 |
| F | CLAUDE.md / action-plan 未提交 | 工作目录 | untracked 文件，团队成员无法看到 |

---

## 整改计划

### 设计原则

- **每个 Phase 自带测试** — 不单独设"补测试"阶段，测试随功能一起交付
- **每个 Phase 自带指标** — Telemetry 不再是独立 Action，而是每次改动的 checklist 项
- **Phase 间松耦合** — 每个 Phase 合并后系统可独立运行，不存在"做了一半不能用"的中间态
- **先加护栏，再加能力** — 安全限制 (#4 maxSteps) 在新功能 (#3 Agentic RAG) 之前

---

### Phase 0 — 地基夯实

**目标**：清理技术债，建立自动化基础，让后续 Phase 站在可靠的地面上。

#### 0.1 分支整理与提交

- 将 `feature/react-redesign` 合并到 `master`（该分支的 ReAct 重构已是项目主要代码）
- 提交 `CLAUDE.md` 和 `docs/action-plan-2026-03-20.md`
- 后续每个 Phase 从 master 拉新分支，完成后 PR 合回

#### 0.2 硬编码提取为配置

- `ChatService` 中 `model = "gpt-4o"` → 从 `spring.ai.openai.chat.options.model` 读取
- `MemoryService` 中 `MAX_HISTORY` / `SESSION_TTL` → `app.memory.max-history` / `app.memory.session-ttl`
- `RagService.buildContext()` 中 `topK=5` → `app.ai.rag.default-top-k`

#### 0.3 测试基础设施

- 引入 `spring-boot-testcontainers`（PostgreSQL + Redis），替代对外部 Docker 实例的依赖
- 创建 `AbstractIntegrationTest` 基类，统一管理容器生命周期
- 为现有两个测试类 (`AgentOrchestratorTest`, `RagControllerValidationTest`) 确认可通过 `mvn test`

#### 0.4 CI Pipeline

- 添加 `.github/workflows/ci.yml`：`mvn test` + 编译检查
- PR 合入 master 前必须 CI 通过

#### 0.5 Telemetry（Phase 0 部分）

- `ChatService` 新增 `ai.chat.model` tag 到现有 timer，方便后续按模型切片

**交付物**：master 分支代码整洁、CI 绿灯、测试可本地/CI 运行、配置外部化。

---

### Phase 1 — 核心抽象重构

**目标**：解决两个最根本的架构缺陷 — 工具硬编码和输出不可靠。所有后续 Phase 依赖此阶段。

#### 1.1 ToolRegistry 动态注册（GitHub #1）

- 新增 `ToolRegistry` 组件，启动时扫描所有实现 `Function` 接口且标注 `@Component` + `@Description` 的 Bean
- `AgentOrchestrator.chat()` 改为 `toolNames(toolRegistry.getNames())`
- `TaskPlanner` 改为 `toolRegistry.getDescriptions()` 生成工具描述
- 删除 `AgentOrchestrator` 和 `TaskPlanner` 中所有硬编码工具名

**测试**：
- `ToolRegistryTest` — 验证扫描逻辑、空工具场景、Description 提取
- 更新 `AgentOrchestratorTest` — 注入 mock ToolRegistry 替代硬编码工具列表

**指标**：
- `ai.tools.registered.count` (Gauge) — 注册工具数量，启动时上报

#### 1.2 Structured Output 硬约束（GitHub #7）

- `TaskPlanner` 引入 `BeanOutputConverter<List<PlanStep>>`，替换提示词软约束
- 删除 `TaskPlanner.extractJson()` 手动解析方法
- 删除 prompt 中的手写 JSON 格式说明

**测试**：
- `TaskPlannerTest` — 验证正常解析、schema 注入、fallback 降级（LLM 返回非法格式时不崩溃）

**指标**：
- `ai.planner.result{status=success|fallback}` (Counter) — Planner 成功/降级率

**交付物**：新增工具零改动 Orchestrator；TaskPlanner 输出 100% 可靠反序列化。

---

### Phase 2 — 安全护栏

**目标**：在释放更多 Agent 能力（Phase 3）之前，先把安全边界建好。类比：先装刹车再踩油门。

#### 2.1 maxSteps 硬限制（GitHub #4）

- `ToolExecutionAspect` 在 `proceed()` 前检查 `StepCollector.nextStepNumber()` 是否超过 `app.ai.react.max-steps`
- 超限时抛出 `MaxStepsExceededException`
- `AgentOrchestrator` catch 该异常，返回已有部分结果 + 超限提示
- `ApiExceptionHandler` 增加该异常的 HTTP 映射

**测试**：
- `ToolExecutionAspectTest` — 模拟连续调用直到超限，验证异常抛出
- `AgentOrchestratorTest` — 验证超限时返回部分结果而非 500

**指标**：
- `ai.agent.max_steps_exceeded.total` (Counter) — 超限触发次数

#### 2.2 LLM 调用韧性

- `AgentOrchestrator` 的 `chatClient.prompt().call()` 包装 try-catch，区分：
  - 超时 (SocketTimeoutException) → 返回友好超时提示
  - 限流 (429 Too Many Requests) → 返回限流提示
  - 其他异常 → 统一错误响应
- 考虑引入 Spring Retry (`@Retryable`) 对瞬时失败自动重试（最多 2 次，指数退避）

**测试**：
- `AgentOrchestratorTest` — mock ChatClient 抛出各类异常，验证降级行为

**指标**：
- `ai.error.total{type=timeout|rate_limit|llm_error|unknown}` (Counter) — 按错误类型分类

**交付物**：Agent 不会因 LLM 异常/工具循环而失控；错误有分类、有指标、有降级。

---

### Phase 3 — 能力升级

**目标**：在可靠的地基和安全护栏之上，释放 Agent 的真正能力。

#### 3.1 RAG as a Tool / Agentic RAG Level 1（GitHub #3）

- 新增 `KnowledgeSearchTool` 实现 `Function<Request, Response>`
  - 内部调用 `RagService.buildContext(query)`
  - 注册到 ToolRegistry 后自动被 Orchestrator 发现
- 删除 `ChatService` 中的 `ragEnabled` 预处理逻辑和相关代码路径
- `ChatRequest.ragEnabled` 字段标记为 `@Deprecated`，保留兼容但不再生效
- Agent 在 ReAct 循环中自主决定是否检索知识库

**测试**：
- `KnowledgeSearchToolTest` — mock VectorStore，验证检索与格式化
- 端到端集成测试 — 验证 Agent 在需要知识时调用该工具，在纯计算时不调用

**指标**：
- `ai.rag.retrieval.result{result=hit|miss}` (Counter) — RAG 命中率（从 RagService 层上报）

#### 3.2 Streaming SSE（GitHub #2）

- 新增 `GET /api/v1/chat/stream` endpoint，返回 `Flux<ServerSentEvent<String>>`
- `AgentOrchestrator` 新增 `streamChat()` 方法，使用 `.stream().content()`
- 原 `POST /api/v1/chat` 保持不变（非破坏性变更）
- SSE 事件类型设计：
  - `plan` — TaskPlanner 输出的步骤计划
  - `step` — 每次工具调用的 AgentStep
  - `token` — LLM 流式 token
  - `done` — 最终结果 + 元数据

**测试**：
- `ChatControllerTest` — WebFlux `StepVerifier` 验证 SSE 事件序列
- 手动验证 — `curl` + EventSource 确认浏览器可消费

**指标**：
- `ai.chat.stream.duration` (Timer) — 流式请求端到端耗时
- `ai.token.input` / `ai.token.output` (Counter) — 从 ChatResponse metadata 提取 token 用量

**交付物**：Agent 具备自主检索能力（不再盲目 RAG）；前端可实时展示推理过程。

---

### Phase 4 — 可观测性收尾 & 全量测试加固

**目标**：补齐前三个 Phase 遗漏的指标盲区，整体测试覆盖率达标。

#### 4.1 Telemetry 查漏补缺（GitHub #5 收尾）

前三个 Phase 已随代码新增了大量指标，此阶段仅处理剩余项：
- `ai.tool.duration{tool=...}` (Histogram) — ToolExecutionAspect 中按工具名打 tag（如果前面未覆盖）
- Grafana Dashboard 模板 JSON — 提供开箱即用的仪表盘
- 验证 Prometheus scrape 配置覆盖所有新增指标

#### 4.2 测试加固（GitHub #6 收尾）

前三个 Phase 已为每个新增/修改的组件编写了测试，此阶段补齐存量代码：
- `StepCollectorTest` — ThreadLocal 多线程隔离验证
- `MemoryServiceTest` — Redis 读写、TTL 过期、MAX_HISTORY 截断
- `ChatControllerTest` — MockMvc 完整 API 契约（含错误码）
- `ChatServiceTest` — 验证 orchestrator 编排、ragEnabled 降级兼容
- 目标：核心业务类行覆盖率 ≥ 70%

**交付物**：全链路可观测；`mvn test` 全绿且覆盖率达标。

---

## 执行顺序总览

```
Phase 0 (地基)     → 分支合并、配置外部化、CI、测试基础设施
  ↓
Phase 1 (核心抽象) → #1 ToolRegistry + #7 Structured Output
  ↓
Phase 2 (安全护栏) → #4 maxSteps 硬限制 + LLM 调用韧性
  ↓
Phase 3 (能力升级) → #3 Agentic RAG + #2 Streaming SSE
  ↓
Phase 4 (收尾加固) → #5 Telemetry 补全 + #6 测试覆盖率达标
```

### 依赖关系

```
Phase 0 ──→ Phase 1 ──→ Phase 2 ──→ Phase 3 ──→ Phase 4
                │                       │
                │  1.1 ToolRegistry ────→ 3.1 RAG as Tool (自动注册)
                │  1.2 Structured Output (独立)
                │                       │
                └───────────────────────→ 3.2 Streaming SSE (独立，仅需 Phase 0 完成)
```

注：Phase 3 内部 3.1 和 3.2 可并行开发。3.2 Streaming SSE 技术上只依赖 Phase 0，但建议在 Phase 2 安全护栏就位后再做，避免流式场景下循环失控无保护。

---

## 与现有 action-plan 的关键差异

| 维度 | 现有 action-plan | 本计划 |
|---|---|---|
| 测试策略 | 最后单独补 (Week 4) | 每个 Phase 自带测试，最后只做查漏 |
| Telemetry | 独立 Action (Week 3) | 随代码一起长出来，最后只做收尾 |
| Phase 0 | 无 | 新增：分支合并、CI、配置外部化、测试基础设施 |
| LLM 韧性 | 未提及 | 新增：错误分类、retry、降级 (Phase 2.2) |
| 硬编码治理 | 仅提到工具名 | 扩展到 model、memory、RAG topK 等所有散落的魔法值 |
| SSE 优先级 | P0 (Week 2) | 降为 Phase 3（先有护栏再加能力） |
| 执行逻辑 | 按"独立性"分组 | 按"依赖链 + 风险"排序：地基→抽象→护栏→能力→收尾 |
