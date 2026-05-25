---
updated: 2026-05-23 20:34
---
1. System Prompt 动态组装 #issue22
2. Multi-Agent #issue28
3. RAG 中文检索优化 + 引入 Hyde 召回率优化 #issue36
4. 引入 Agent 评估系统 #issue26
5. 支持 Skill 和 MCP 协议 #issue25
6. 支持更多的 tools 

## Issues

```
[MemoryAccessUpdater] Failed to update lastAccessedAt: PreparedStatementCallback; bad SQL grammar [UPDATE vector_store SET metadata = jsonb_set(metadata, '{lastAccessedAt}', to_jsonb(?::bigint))
```

## 有价值的待落地点

- HITL(Human in the loop): 在 loop 的每个生命周期内，做出可插拔的 hook 点，进行介入，使得 agent 能够按照预期运行：
	- PreTurn：修改注入模型的上下文、注入 system 提醒、做权限检查，
	- MidTurn：审批 tool call，修改参数、拦截危险操作
	- PostTurn：在本轮 turn 结束，下一轮 turn 开始前触发。审查 observation、支持 loop、修改要传给下一个 turn 的内容
- Skill
	- 

