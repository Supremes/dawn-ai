老大，5 个 commit 全部落地。

## 交付清单

**分支**：`feat/multi-agent`（21 文件，+1300/-8 行）

```
e717c52 docs(multi-agent): 沉淀设计共识与验收手册
8a98aca chore(observability): sub-agent 接入 Prometheus 与 Langfuse 会话归并
ef06579 feat(sse): SSE 协议扩展支持 sub-agent 进度心跳与嵌套步骤
b93e7d9 feat(subagent): 接入 research sub-agent 与 DispatchSubAgentTool
14ce08c feat(subagent): 引入 SubAgent 基础架构骨架
```

## 待老大在开发环境验证

由于本环境没有 Maven 也只有 Java 11，无法本地编译。请老大在你的 IDE / 开发环境：

1. **编译**：`mvn compile` 应当一次过
2. **旧测试不破坏**：`mvn test -Dtest=AgentOrchestratorTest,FormatPlanFinishFilterTest,AgentPackageStructureTest` 应当全绿
3. **行为验收**：按 `docs/multi-agent/verification.md` 跑 3 场景（A 深度调研派发 / B 简单问题不派 / C 软失败 partial），截图存档到 `docs/multi-agent/screenshots/`
4. **3 场景跑通 + 截图齐全 → MVP 完成 → 开 PR 合 master**

## 关键设计回顾（落地版）

- sub-agent 跑在专用 `subAgentExecutor` 线程池（core=2/max=8），主 SSE 流量不受影响
- 通过 `AiInteractionContext.wrap` 把 sessionId 传到 worker，Langfuse Session 视图自然归并
- 失败矩阵生效：超时/步数超限 → PARTIAL_SUCCESS 含已完成步骤摘要；`AiConfigurationException` 仍向外抛
- 协议向后兼容：`AgentStep` 5 参 delegating 构造器保留，前端不渲染 `sub_progress` 时退化为只看 `step`

## 我没做（明确不做）

- 新 UT（按老大约束）
- Feature flag（YAGNI）
- 并行派发 / 递归 dispatch（MVP 范围外，扩展点已留好）
- 前端 UI 改动（协议加法不破坏现有渲染，要展开嵌套步骤是前端独立 PR 范围）

跑完验收有任何场景没过，告诉我现象，我来定位修。