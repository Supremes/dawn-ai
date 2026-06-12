---
updated: 2026-06-05 22:45
---
- [x] System Prompt 动态组装 
	- 
- [x] Multi-Agent
	- [ ] 缺少系统验证和测试回顾
- [x] RAG 中文检索优化 + 引入 Hyde 召回率优化
	- [ ] 缺少真实业务场景的测试
- [x] 引入 Agent 评估系统
	- [ ] 已经引入 langfuse 及其 LLM-AS-JUDGE 能力，但是没有完成系统测试
- [x] 支持 Skill 和 MCP 协议 
	- 放弃对 MCP 的支持，大部分业务场景完全可以使用 cli 来解决，不需要引入 MCP
- [x] 支持更多的 tools 

## Issues

```
[MemoryAccessUpdater] Failed to update lastAccessedAt: PreparedStatementCallback; bad SQL grammar [UPDATE vector_store SET metadata = jsonb_set(metadata, '{lastAccessedAt}', to_jsonb(?::bigint))
```

## 有价值的待落地点

- HITL(Human in the loop): 在 loop 的每个生命周期内，做出可插拔的 hook 点，进行介入，使得 agent 能够按照预期运行：
	- PreTurn：修改注入模型的上下文、注入 system 提醒、做权限检查，
	- MidTurn：审批 tool call，修改参数、拦截危险操作
	- PostTurn：在本轮 turn 结束，下一轮 turn 开始前触发。审查 observation、支持 loop、修改要传给下一个 turn 的内容
- agent paradigm
- turn 一轮对话的定义、checkpoint



老大，完整报告已出。以下是核心结论：

---

## 项目现状总评

项目已经具备一个 Agent 平台的**扎实骨架**——Plan-then-ReAct 混合循环、工具自动发现 + AOP 追踪、三层记忆系统（SEMANTIC/EPISODIC/PROCEDURAL）、Sub-agent 委派隔离、SSE 流式协议、LLM-as-Judge 六维评估框架，工程质量不低。

但目前处于 **「单用户原型 → 生产级产品」的关键转型期**，共识别出 **93 个 Gap**：

| 优先级           | 数量 | 定义                 |
| ---------------- | ---- | -------------------- |
| **Critical**     | 10   | 不解决则无法安全上线 |
| **Important**    | 50   | 生产质量与竞争力关键 |
| **Nice-to-have** | 33   | 行业领先差异化       |

---

## TOP 5 最致命缺失

1. **认证与授权体系完全缺失** — 所有 API 裸露，所有用户共享 `local-user` 记忆空间
2. **速率限制 + Token 成本治理为零** — 单次请求可触发 10-20+ 次 LLM 调用，无任何上限
3. **CI/CD + Secret 管理缺失** — 密码明文硬编码，`ddl-auto: update` 在生产极度危险
4. **Prompt 注入零防御 + BashTool 沙箱可绕过** — base64/python 等方式可轻松绕过黑名单
5. **MCP 客户端不支持** — 工具生态完全封闭，无法接入社区 500+ MCP Server

---

## 推荐实施路线图

| 阶段                  | 时间    | 目标                                                         |
| --------------------- | ------- | ------------------------------------------------------------ |
| **Phase 1: 安全基线** | 4-6 周  | Spring Security + JWT、限流、CI/CD、Flyway、Secret 管理、RAG CRUD |
| **Phase 2: 竞争力**   | 6-8 周  | MCP 支持、动态重规划、Token 窗口管理、并行 Sub-agent、Grafana Dashboard |
| **Phase 3: 领先**     | 8-12 周 | 工具 SPI 插件化、Guardrails 框架、多模态、K8s Helm、A/B 测试 |

**关键依赖**：Phase 1 的认证体系是后续所有 per-user 功能（限流/配额/权限/审计）的前置条件，必须最先做。Spring AI 版本升级是 MCP 支持的前置条件，建议 Phase 2 首周启动。

需要我针对某个具体 Gap 深入展开方案，或者直接开始实施某一项吗？
