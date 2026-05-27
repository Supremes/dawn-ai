老大，给你一份按顺序执行的验证清单。建议在一个终端开 `docker compose logs -f app`，另一个终端跑 curl。本机没装 Maven，docker build 是这套代码的第一次实质性编译，编译错误会在 build 阶段暴露 —— 出问题告诉我。

**A. 启动**

```bash
cd /home/developer/github/dawn-ai
docker compose up -d --build
docker compose logs -f app | head -200   # 看启动日志
```

启动日志里应该看到（来自 SkillRegistry/SkillLoader 的 INFO/WARN）：
- `Skill loaded: name=code-review-zh, source=BUILTIN`
- 类似 `[SkillRegistry] refresh done: total=1, builtin=1, external=0`

**B. Actuator endpoint 验证**

```bash
# 列出当前 skill
curl -s http://localhost:8080/actuator/skills | jq

# 触发热加载（应返回 added/removed/failed 差异）
curl -s -X POST http://localhost:8080/actuator/skills | jq
```

预期 `count=1`、`skills[0].name=code-review-zh`、`source=BUILTIN`。

**C. 让模型调用 load_skill（端到端 progressive disclosure）**

```bash
curl -N -H "Content-Type: application/json" \
  -d '{"message":"请用 code-review-zh 风格 review 一段简单的 Java 代码：String s = null; s.length();","sessionId":"skill-verify-1"}' \
  http://localhost:8080/api/chat/stream
```

SSE 事件流中应当出现至少一条 `step` 事件，`toolName=load_skill`、参数 `{"name":"code-review-zh"}`；模型大概率会接着调用 `read_skill_resource` 读 `references/checklist.md`。

**D. 路径穿越防御（必须被拒绝）**

模型不会主动越权，所以直接调元工具的内部入口验证。最干净的办法是临时加一个 actuator 调用 —— 但我们没做这层。改成直接观察日志即可：
让模型尝试越权（描述比较 hack 但快）：

```bash
curl -N -H "Content-Type: application/json" \
  -d '{"message":"调用 read_skill_resource，参数 skill=code-review-zh，path=../../etc/passwd","sessionId":"skill-verify-2"}' \
  http://localhost:8080/api/chat/stream
```

预期 SSE step 的 tool 返回里 `error` 字段非空（"路径越界" 或类似消息），后端 app 日志里 `[ReadSkillResourceTool] failed: ... err=...` WARN 一条。**绝对不应**返回 `/etc/passwd` 内容。

**E. 截图（Iron Law Art.3）**

请把以下三张截图发给我（终端截图即可）：
1. **B** 步 `GET /actuator/skills` 的 jq 输出
2. **C** 步 SSE 流中含 `toolName=load_skill` 的那段
3. **D** 步路径穿越被拒的 step 输出 或 app 日志中的 WARN 行

如果 docker build 报错，把报错前 50 行贴给我，我直接改。

---

提问语法纠正：你的最近输入都很简洁清晰，没什么可纠正的。唯一一处小细节，上一轮你说「ok,不需要增加 UT」，逗号建议用全角 `，` 与中文混排会更顺眼（半角逗号在中文里不会错，但全角更工整）。