# Memory 设计端到端测试 Playbook

> 一套**持续多轮会话**的测试方案，覆盖 Dawn AI memory 设计的全链路：
> 工作记忆滑窗 → pending 溢出 → 摘要(EPISODIC) + 事实抽取(SEMANTIC) → 反思(PROCEDURAL + 用户画像) → 检索注入 → importance 加权 → 淘汰治理。
>
> 配套自动化脚本：[`scripts/mem-test.sh`](../../scripts/mem-test.sh)

---

## 1. 触发机制速查（源码 + `application.yml` 核对）

| 机制 | 配置项 | 默认值 | 说明 |
|---|---|---|---|
| 工作记忆滑窗 | `app.memory.max-history` | **10** | 每轮对话 push **2 条**（user + assistant）；list 大小 `> 10` 时最旧消息 `leftPop` 进 pending |
| 摘要批量 | `app.memory.summary.batch-size` | **3** | pending 积满 ≥3 → `SummarizationRequestEvent`（原子 `rename` 排空），并发触发 Summarizer + FactExtractor |
| 反思阈值 | `app.memory.consolidation.reflection-threshold` | **3** | 每累计 3 条 EPISODIC → `ReflectionRequestEvent` |
| 反思下限 | `app.memory.reflection.episode-threshold` | **4** | 反思时检索 EPISODIC top-4；`size < 4/2 = 2` 则跳过（至少要 2 条 EPISODIC） |
| 检索注入 | `injection.procedural-top-k` / `semantic-top-k` | **2 / 3** | 仅注入 PROCEDURAL + SEMANTIC；**EPISODIC 不进主 prompt** |
| 召回加权 | `app.memory.search.importance-weight` | **0.3** | `finalScore = similarity × (1 + 0.3 × importance)` |
| 去重 | `app.memory.dedup.semantic-enabled` / `semantic-top-k` | **true / 3** | 同内容 MD5 直接去重；语义去重先向量召回 topK 候选，再由 LLM 判断是否重复 |
| 淘汰 | `eviction.importance-threshold` / `max-age-days` | **0.1 / 2** | cron 03:00；`imp < 0.1 且 age > 2天` 软删除，PROCEDURAL 豁免 |
| 衰减 | `decay.half-life-days` | **30** | cron 03:30；仅 EPISODIC，`imp × e^(−ln2·Δd/30)` |
| 记忆主键 | `app.memory.default-user-id` | **local-user** | 长期记忆按固定 userId **跨 session** 累积；检索用**当前 query** 做相似度 |

### 消息数学（决定第几轮触发什么）

```
每轮 chat = 2 条进窗口（user + assistant）
前 5 轮：5×2 = 10 条，窗口刚好填满，不溢出
第 6 轮起：每轮溢出 2 条旧消息 → pending；pending 每达到 3 → 一次摘要(EPISODIC)
3 条 EPISODIC → 一次反思(PROCEDURAL + 画像)
```

理论时点（实际受 LLM 抽取条数影响，以 `/state` 实测为准）：

| 轮次 | 事件 |
|---|---|
| t1–t5 | 填满窗口，无整合 |
| ~t7 / ~t8 / ~t10 | 第 1 / 2 / 3 次摘要 |
| **~t10** | 第 3 条 EPISODIC → **首次反思**：PROCEDURAL + Redis 画像 |
| ~t16 | 第 6 条 EPISODIC → 第 2 次反思 |

---

## 2. 前提

1. 服务已启动，Redis + PostgreSQL(pgvector) 就绪。
2. LLM / Embedding 已配置（每轮对话、摘要、事实抽取、反思都调 LLM）。
3. 诊断端点 `/api/v1/debug/memory/**` 可访问。
4. 本机有 `curl` 与 `jq`。

> ⚠️ 长期记忆按 `local-user` **跨会话累积**，反复跑测试会叠加。要纯净基线：清空 `memory_entries` 表，或临时改 `default-user-id`。

---

## 3. 多轮对话脚本（16 轮，事实都放在 user 消息——FactExtractor 只从用户消息抽取）

### Phase A · 播种身份与偏好（t1–t5，填满窗口）

| # | 用户消息 | 埋入的事实 |
|---|---|---|
| t1 | 我叫陈昊，是一名后端工程师，平时主要写 Java 和 Go。 | 姓名 / 职业 / 语言 |
| t2 | 我特别喜欢用 Spring Boot，比较反感过度设计的代码。 | 框架偏好 |
| t3 | 数据库我更偏好 PostgreSQL，不太喜欢 MySQL。 | DB 偏好 |
| t4 | 补充一下我的饮食：我是素食者，而且对花生严重过敏。 | 饮食 / 过敏 |
| t5 | 生活上我每周跑步三次，喜欢长距离慢跑。 | 运动习惯 |

### Phase B · 溢出 → 摘要 → 首次反思（t6–t10）

| # | 用户消息 | 作用 |
|---|---|---|
| t6 | 我最近在读科幻小说，正在看《三体》，很入迷。 | 阅读偏好 |
| t7 | 工作习惯方面，我习惯早上写代码，下午和晚上才安排开会。 | 作息（PROCEDURAL 素材） |
| t8 | 咖啡我只喝美式，不加糖不加奶。 | 饮品偏好 |
| t9 | 再强调下，我对花生过敏这件事很重要，外卖一定要避开。 | **去重测试**（与 t4 同一事实） |
| t10 | 下个月我计划去日本旅行，想体验当地的素食餐厅。 | 计划 |

> 此处 ≈ 第 3 条 EPISODIC → **首次反思**：PROCEDURAL + Redis 画像覆盖写。

### Phase C · 巩固，逼出第 2 次反思（t11–t13）

| # | 用户消息 |
|---|---|
| t11 | 我用 IntelliJ IDEA 做开发，终端常驻 tmux。 |
| t12 | 周末我喜欢去爬山，偶尔骑公路车。 |
| t13 | 我不喜欢长会议，能用文档异步沟通就尽量异步。 |

### Phase D · 召回验证（t14–t16，触发检索注入）

| # | 用户消息 | 期望命中 |
|---|---|---|
| t14 | 帮我推荐一家适合我的午餐外卖。 | SEMANTIC：素食 + 花生过敏 |
| t15 | 根据你对我的长期了解，帮我排一下明天的日程。 | PROCEDURAL 画像：早上写码 / 晚上开会 / 跑步 |
| t16 | 我之前说过我更喜欢哪个数据库？为什么？ | SEMANTIC：PostgreSQL |

---

## 4. 检查点（每个 Phase 后查 `/state`）

```bash
curl -s $BASE/api/v1/debug/memory/$S/state | jq
```

| 时点 | l1Window | l2Pending | l3Count | l4Profile（本 session） |
|---|---|---|---|---|
| t5 后 | **10**（满） | 0 | 0 | `{}` |
| t8 后 | 10 | 0–2 波动 | >0（EPISODIC + SEMANTIC 落库） | `{}` |
| t10 后（等几秒） | 10 | 小 | 继续增长 | `{}`（见下注） |
| t16 后 | 10 | 小 | 更多 | `{}`（见下注） |

> `l3Count` 含三类记忆、受 LLM 抽取数量影响、非定值，趋势应单调增。
>
> ⚠️ **L4 画像不要看本 session**：`/state` 的 `l4Profile` 是用「传入的 id」去读 `ai:profile:{id}`，
> 而真实对话的反思把画像写在 **`default-user-id`（默认 `local-user`）** 下。
> 所以**测试会话的 `l4Profile` 恒为 `{}`**，这并不代表反思失败。验证反思画像要查 default-user-id：
>
> ```bash
> curl -s $BASE/api/v1/debug/memory/local-user/state | jq '.l4Profile.reflection'
> ```
>
> ✅ 实测（16 轮跑完）该 key 已生成完整画像，涵盖职业/语言/框架/数据库/作息/咖啡/跑步/素食+花生过敏，证明
> 工作记忆→pending→摘要→事实→**反思→画像** 全链路打通；t14/t16/t15 三项召回亦全部命中。

---

## 5. 逐项验证清单

| 记忆 / 机制 | 验证方式 | 通过标准 |
|---|---|---|
| 工作记忆滑窗 | t5 后看 `l1Window` | 恒为 10，不再增长 |
| pending 队列 | 连发多轮看 `l2Pending` | 在 0–2 间波动，积满 3 清零 |
| EPISODIC 摘要 | 日志 `[MemorySummarizer] Summarized` | 出现摘要日志，l3 增长 |
| SEMANTIC 事实 | t14 / t16 回答 | 主动避开花生 / 推荐素食、答出 PostgreSQL |
| SEMANTIC 去重 | t4 vs t9 同一事实 | 日志 `[MemoryManager] Duplicate memory skipped`，不产生重复 doc |
| PROCEDURAL + 画像 | 查 **default-user-id** 的 `l4Profile.reflection`、t15 回答 | 画像非空且涵盖各偏好；日程按早写码 / 晚开会 / 跑步安排 |
| EPISODIC 不注入 | 观察回答 | 注入只来自事实 / 画像，从不照搬整段「对话摘要」原文 |
| importance 加权 | 相近记忆中 PROCEDURAL(0.9) 优先 | 画像类记忆排在事实之前命中 |

---

## 6. 治理类（衰减 / 淘汰）

对话无法等待天级 cron，需手动验证。

### 端点连通性（脚本已自动做）

```bash
curl -s -X POST $BASE/api/v1/debug/memory/evict   # {"status":"eviction triggered"}
```

### 严格验证「真正删除」（psql）

> ⚠️ `EvictionPolicyManager` 扫描的是 **JPA 表 `memory_entries`**（`imp < 0.1 且 created_at > 2天前`，排除 PROCEDURAL）。
> 纯对话产生的记忆 `age = now`、`importance ≥ 0.5`，**不满足淘汰条件**；诊断接口 `inject-doc` **只写向量库、不建 JPA 行**，也**不会**被该策略删除（实测 `evict` 后 `l3Count` 不变）。
> 因此要真正触发删除，需在 DB 层把一条真实记忆人为置为「可淘汰」：

```sql
-- 1) 取一条真实记忆，置低 importance + 超龄（排除 PROCEDURAL）
UPDATE memory_entries
SET importance = 0.05, created_at = now() - interval '3 days', updated_at = now()
WHERE deleted = false AND memory_type <> 'PROCEDURAL'
ORDER BY created_at DESC
LIMIT 1
RETURNING id;
```
```bash
# 2) 触发淘汰
curl -s -X POST $BASE/api/v1/debug/memory/evict
```
```sql
-- 3) 验证该行被软删除
SELECT id, deleted, importance FROM memory_entries WHERE importance < 0.1;
-- 期望：deleted = true
```

- **PROCEDURAL 豁免**：把上面的 `memory_type` 改成 `'PROCEDURAL'` 同样置低/超龄，再 `evict`，该行应**仍 `deleted = false`**。
- **衰减**（半衰期 30d、cron、无手动接口、仅 EPISODIC）建议用单元测试验证 `ImportanceDecayManager.computeDecay`，不在对话内测。

> 💡 备注：诊断 `inject-doc` 的注释称「用于 Eviction 测试」，但当前 eviction 已改为扫描 JPA，二者不一致；`inject-doc` 现仅适合构造**向量库脏数据**做检索/隔离类测试。

---

## 7. 一键运行

```bash
# 默认 http://localhost:8080，自动跑完 16 轮 + 各阶段 state + 召回校验 + 淘汰测试
bash scripts/mem-test.sh

# 自定义
BASE_URL=http://your-host:8080 SESSION_ID=mem-test-001 bash scripts/mem-test.sh

# 跳过淘汰测试 / 阶段间手动暂停
RUN_EVICTION=0 INTERACTIVE=1 bash scripts/mem-test.sh
```

环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 服务地址 |
| `SESSION_ID` | `mem-test-<时间戳>` | 会话 ID |
| `STEP_WAIT` | `5` | 异步整合等待秒数 |
| `RUN_EVICTION` | `1` | 是否跑淘汰诊断 |
| `INTERACTIVE` | `0` | `1` 时每阶段暂停等回车 |

---

## 8. 注意事项

1. 整合是 **@Async + LLM**，查 `state` 前需 `sleep`（脚本已内置 `STEP_WAIT`）。
2. 长期记忆按 `local-user` **跨会话**，多次运行会叠加；纯净基线请清表或换 userId。
3. 精确触发轮次受「每轮消息数 / 单次抽取事实条数」影响，理论时点仅供参考，**以 `/state` 实测为准**。
4. 召回类断言（关键词命中）受 LLM 随机性影响，脚本中为**软校验**（未命中给 warning，非硬失败），请结合回复人工确认。
5. **诊断端点 `/state` 的两处键不一致**（已实测）：
   - `l4Profile` 按传入 id 读 `ai:profile:{id}`，而反思写在 `default-user-id` 下 → **测试会话 L4 恒为 `{}`**，须查 `local-user`（脚本 `show_profile` 已自动处理）。
   - `inject-doc` 只写向量库、不建 JPA 行，而 `EvictionPolicyManager` 扫描 JPA `memory_entries` → inject 的 doc **不会被 evict 删除**（见 §6）。
   这两点是诊断工具与当前实现的历史遗留不一致，测试时按上文方式规避即可。
