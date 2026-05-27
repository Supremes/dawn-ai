# Skill 支持 — 设计规格文档

- **日期**：2026-05-26
- **分支**：（尚未创建，待规格定稿后建分支）
- **状态**：grill 完成，全部决策已锁定，待进入实施阶段
- **负责人**：Supremes
- **来源 issue**：`docs/TODO.md` 第 5 条「支持 Skill 和 MCP 协议 #issue25」

## 1. 目标

为 dawn-ai 自实现的 Agent（`AgentOrchestrator` + `ToolRegistry` + `TaskPlanner`）接入 **Anthropic Claude Skills** 形态的能力扩展机制 —— 即基于 `SKILL.md` + YAML frontmatter 的「progressive disclosure」式按需指令加载。

## 2. 非目标（Non-Goals）

- **不接入 MCP 协议**。
  - **决策原因**：CLI（即 Function/Tool 形态）已能覆盖 MCP 在服务端 agent 这一侧的工具消费场景；MCP 在桌面客户端（Claude Desktop / Cursor）的边际价值高，在 LLM 后端服务的边际价值低。
  - **历史记录**：grill 阶段曾完整讨论过 MCP Client 接入方案（见 §6），并已在传输协议层确定 SSE-only；最终在「选定接入哪个 server」环节判断 ROI 不足，整体放弃。
- 不替换现有 `agent/trace` 模块（`AgentStep` / `StepCollector` / `ToolExecutionAspect`），Skill 调用须复用该追踪链路。
- 不实现 Skill 的远程分发 / 中央仓库 / 签名校验等治理能力（如未来需要，单独立项）。

## 3. 已锁定决策

| # | 决策项 | 选择 | 备注 |
|---|--------|------|------|
| 1 | Skill 形态 | **Anthropic Claude Skills**（`SKILL.md` + frontmatter，progressive disclosure） | 区别于 Semantic Kernel "Skill"（≈ 工具分组）与自定义"技能"语义 |
| 2 | 集成模式 | **Pattern A：统一抽象到 `ToolRegistry`** | 抽出 `ToolDescriptor`（name/description/callback），本地 Function 与 Skill 共用同一出口，保证 `TaskPlanner` 与 `ToolExecutionAspect` 自动覆盖；避免双轨制导致 trace/metrics 缺失 |
| 3 | 集成范围 | **仅 Skill，放弃 MCP** | 见 §2 非目标说明 |
| 4 | 动机 | **(iii) 探索性复用 Anthropic 模式** | 忠于原版 progressive disclosure 语义；学习/示范价值优先；可写入 `docs/notes/` 分享 |
| 5 | Progressive disclosure 实现 | **b1：元工具 `load_skill(name)` + system prompt 列举 skill 清单** | Anthropic 原版的字面 1:1 翻译；保持 skill 与 tool 的语义分离；`ToolRegistry` 中仅多 `load_skill` 一项，工具列表无膨胀 |
| 6 | 存放位置 + 目录结构 | **目录形态外挂 `./skills/{name}/SKILL.md`（路径由 `app.skills.path` 配置）+ classpath 内置示例兜底** | 忠于原版目录形态；运维加 skill 无需 mvn package；内置 1 个示例 skill 打进 jar（`src/main/resources/skills-builtin/`）保证首次启动可演示 |
| 7 | 可执行内容（`scripts/`） | **完全不支持**；扫描时发现 `scripts/` 日志 WARN 并忽略 | dawn-ai 无 shell tool，沙箱执行是 issue 级独立工程；明确边界优于模糊能力；未来加 shell tool 后可演进到支持 |
| 8 | 内嵌资源（`references/`） | **(b) 加第二个元工具 `read_skill_resource(skill, path)`**，路径强制限定在 `./skills/{name}/` 子树内 | 忠于原版"嵌套 progressive disclosure"；工具签名职责单一；防路径穿越在实现层用 `Path.resolve()` + 子树校验 |
| 9 | Frontmatter 字段 | **(B) 仅消费 `name` + `description`（必需，缺失则拒绝注册）；其他字段解析进 `SkillManifest` 但忽略；未知字段不报错** | `allowed-tools` 落地需中途变更 ChatClient 工具集，Spring AI 当前 API 不支持，单独立项；解析层保留完整字段便于未来扩展；单个 skill 解析失败仅跳过该 skill，不阻塞 app 启动 |
| 10 | 热加载 | **(B) Actuator endpoint `POST /actuator/skills/reload` 手动触发**；老 session 持有旧 system prompt 不刷新（新 session 自然生效，文档化即可） | 演示叙事完整性；`WatchService` 在 macOS 上是 polling 不划算；endpoint 即 `skillRegistry.refresh()` 一行，未来可演进到自动 watch |
| 11 | 激活范围 | **全局可见**，所有 skill 列入 system prompt，由模型自主决策 load | (iii) 忠于原版；dawn-ai 无租户/角色体系 |
| 12 | 内置示例 skill | **`code-review-zh`** —— 中文 Java 代码 review 风格指南；`SKILL.md` 描述用法，`references/checklist.md` 列详细 checklist | 与项目工程感契合；演示截图自然；中文 |
| 13 | system prompt 中 skill 清单格式 | **Markdown 列表**：`## 可用 Skills\n按需调用 load_skill(name) ...\n- **{name}**: {description}` | 人类可读 + LLM 友好；比 JSON 短，比自然语言段落结构化 |
| 14 | skill 名字符约束 | **kebab-case，正则 `^[a-z][a-z0-9-]{0,63}$`** | 与原版一致；URL/路径友好；防 escape；违反即拒绝注册 + ERROR 日志 |
| 15 | `description` 长度上限 | **1024 字符硬上限**（超过截断 + WARN 日志） | description 永驻 system prompt，每 chat 都付 token；1024 足够说明触发场景 |
| 16 | 命名空间 | **`load_skill` / `read_skill_resource` 为 tool 名；skill 名是其参数，不进工具命名空间** | 不存在与现有 KnowledgeSearch/Weather/Calculator 工具冲突 |
| 17 | 可观测性 | **自动复用现有 `ToolExecutionAspect` + `StepCollector` + Langfuse**，零额外工作 | 两个元工具是标准 Spring Bean + `Function` + `@Description`，被 `ToolRegistry` 自动发现并被 AOP 拦截 |
| 18 | 分支命名 | **`feat/skill-support`** | 仓库现有约定 `feat/...`（如 `feat/langfuse-integration`） |

## 4. 开放问题

无 —— 全部 18 项决策已在 §3 锁定，下一步进入实施 plan。

## 5. 既有架构上下文（决策依据）

- `AgentOrchestrator` 走 Spring AI `ChatClient` + `toolNames()`，工具循环由 Spring AI 接管（非手写 ReAct）。
- `ToolRegistry` 当前发现规则：包路径 `com.dawn.ai.agent.tools` + `Function` Bean + `@Description` 注解 —— 这套硬约束需在 Pattern A 下重构。
- `TaskPlanner` 调用 `toolRegistry.getDescriptions()` 喂规划 LLM —— 若 Skill 不进 Registry，plan 会与实际可用能力不一致。
- `ToolExecutionAspect` 拦截 `apply()` 收集步骤、上报指标、推送 SSE `step` 事件 —— Skill 加载必须复用此链路。
- `StepCollector` 与 `AiInteractionContext` 通过 `Hooks.enableAutomaticContextPropagation()` 在 Reactor 流式管道中跨线程传播 —— Skill 实现不得破坏此机制。

## 6. 历史：放弃的 MCP 方案（备查）

为避免日后重复 grill，将已讨论过的 MCP 决策点存档：

| 决策项 | 当时的选择 |
|--------|------------|
| Client vs Server | **Client only**（消费方视角，与 Skill 互补） |
| 传输协议 | **SSE-only**（放弃 STDIO，避免 Dockerfile 装 node 等子进程依赖） |
| 配置形态 | **独立 `mcp-servers.json`**（Claude Desktop / Cursor 风格，`${ENV_VAR}` 注入 secret） |
| 接入哪个 server | **未定**（讨论到该问题时，整体放弃了 MCP） |

技术校验过 `spring-ai-starter-mcp-client-webflux` 可与现有 Spring MVC 共存（只引入 `WebClient`，不启动 Netty 服务器）—— 此结论作为未来若重启 MCP 工作时的输入保留。

---

> 本文档随 grill 推进持续更新。每锁定一项决策即从 §4 移入 §3。
