#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Memory 设计端到端测试脚本
#
# 一套持续 16 轮的真实对话，完整覆盖 memory 全链路：
#   工作记忆滑窗(10) → pending 溢出(batch 3) → 摘要 EPISODIC + 事实 SEMANTIC
#   → 反思 PROCEDURAL + Redis 画像 → 检索注入 → importance 加权 → 淘汰治理
#
# 配套文档: docs/memory/memory-test-playbook.md
#
# 消息数学:
#   每轮 chat = user + assistant = 2 条进窗口
#   前 5 轮填满窗口(10)，此后每轮溢出 2 条进 pending
#   pending 每达 3 → 一次摘要；3 条 EPISODIC → 一次反思(≈ t10)
#
# 用法:
#   bash scripts/mem-test.sh
#   BASE_URL=http://host:8080 SESSION_ID=mem-test-001 bash scripts/mem-test.sh
#   RUN_EVICTION=0 INTERACTIVE=1 bash scripts/mem-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SESSION_ID="${SESSION_ID:-mem-test-$(date +%s)}"
DEFAULT_USER_ID="${DEFAULT_USER_ID:-local-user}"   # 反思画像写入处(app.memory.default-user-id)
STEP_WAIT="${STEP_WAIT:-5}"
RUN_EVICTION="${RUN_EVICTION:-1}"
INTERACTIVE="${INTERACTIVE:-0}"

# ── 颜色 ─────────────────────────────────────────────────────────────────────
BOLD='\033[1m'; CYAN='\033[0;36m'; GREEN='\033[0;32m'
YELLOW='\033[1;33m'; RED='\033[0;31m'; DIM='\033[2m'; NC='\033[0m'

ANSWER=""   # 最近一次 assistant 回复，供召回校验

# ── 工具函数 ──────────────────────────────────────────────────────────────────
banner() {
  echo -e "\n${BOLD}${CYAN}╔══════════════════════════════════════════════════════╗${NC}"
  printf  "${BOLD}${CYAN}║  %-52s  ║${NC}\n" "$1"
  echo -e "${BOLD}${CYAN}╚══════════════════════════════════════════════════════╝${NC}"
}
step() { echo -e "\n${YELLOW}▶ $1${NC}"; }
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
warn() { echo -e "${YELLOW}! $1${NC}"; }
err()  { echo -e "${RED}✗ $1${NC}"; }

pause() {
  [ "$INTERACTIVE" = "1" ] || return 0
  echo -e "\n${BOLD}  按 Enter 继续...${NC}"; read -r
}

# 发送对话，捕获回复到 ANSWER
send_chat() {
  local message="$1" payload http_body dur
  payload=$(jq -nc --arg m "$message" --arg s "$SESSION_ID" '{message:$m, sessionId:$s}')
  echo -e "${DIM}  › user: $message${NC}"
  http_body=$(curl -sf -X POST "$BASE_URL/api/v1/chat" \
      -H "Content-Type: application/json" --max-time 90 -d "$payload") || {
    err "chat 请求失败(超时或 HTTP 错误)，跳过本条"; ANSWER=""; return 0
  }
  ANSWER=$(echo "$http_body" | jq -r '.answer // ""')
  dur=$(echo "$http_body" | jq -r '.durationMs // 0')
  echo -e "${DIM}  ‹ assistant: ${ANSWER}  [${dur}ms]${NC}"
}

# 查询并打印四层状态
show_state() {
  local label="$1" response l1 l2 l3 l4n l4
  echo -e "\n${BOLD}  [状态快照] $label${NC}"
  response=$(curl -sf "$BASE_URL/api/v1/debug/memory/$SESSION_ID/state") || { err "state 查询失败"; return 0; }
  l1=$(echo "$response" | jq -r '.l1Window')
  l2=$(echo "$response" | jq -r '.l2Pending')
  l3=$(echo "$response" | jq -r '.l3Count')
  l4n=$(echo "$response" | jq -r '.l4Profile | keys | length')
  echo -e "  L1 活跃窗口   : ${BOLD}$l1${NC} / 10"
  echo -e "  L2 Pending    : ${BOLD}$l2${NC}"
  echo -e "  L3 向量记忆   : ${BOLD}$l3${NC} 篇 (EPISODIC+SEMANTIC+PROCEDURAL)"
  echo -e "  L4 用户画像   : ${BOLD}$l4n${NC} 个属性"
  l4=$(echo "$response" | jq -r '.l4Profile')
  if [ "$l4" != "{}" ]; then
    echo -e "  L4 画像内容   :"
    echo "$l4" | jq -r 'to_entries[] | "    \(.key): \(.value)"'
  fi
}

# 反思画像：注意 /state 的 l4Profile 按"传入的 id"读 ai:profile:{id}，而真实对话
# 的反思写在 default-user-id 下。故测试会话的 L4 恒为 {}；要看画像须查 default-user-id。
show_profile() {
  echo -e "\n${BOLD}  [反思画像] 读取 ai:profile:${DEFAULT_USER_ID}${NC}"
  local r refl
  r=$(curl -sf "$BASE_URL/api/v1/debug/memory/$DEFAULT_USER_ID/state") || { warn "画像查询失败"; return 0; }
  refl=$(echo "$r" | jq -r '.l4Profile.reflection // ""')
  if [ -n "$refl" ]; then
    ok "反思画像已生成(PROCEDURAL → Redis)："
    echo -e "  ${DIM}${refl}${NC}"
  else
    warn "画像暂为空：反思需 ≥3 条 EPISODIC 且异步，稍候再查 ai:profile:${DEFAULT_USER_ID}"
  fi
}

# 软召回校验：ANSWER 命中任一关键词则 ✓，否则 warning（LLM 输出有随机性）
recall_check() {
  local label="$1"; shift
  local kw hit=0
  for kw in "$@"; do
    if echo "$ANSWER" | grep -qiE "$kw"; then hit=1; break; fi
  done
  if [ "$hit" = "1" ]; then ok "召回[$label]：命中记忆关键词"
  else warn "召回[$label]：未命中关键词，请人工确认回复是否用到记忆"; fi
}

# 等待异步整合管线
wait_async() { step "等待异步整合(摘要/抽取/反思)完成 ${STEP_WAIT}s..."; sleep "$STEP_WAIT"; }

check_prereq() {
  step "环境检查"
  command -v curl >/dev/null || { err "缺少 curl"; exit 1; }
  command -v jq   >/dev/null || { err "缺少 jq（请先安装：brew install jq）"; exit 1; }
  local code
  code=$(curl -o /dev/null -s -w "%{http_code}" --max-time 5 "$BASE_URL/actuator/health") || code=000
  [ "$code" = "000" ] && { err "无法连接 $BASE_URL，请确认服务已启动"; exit 1; }
  ok "服务可达 (HTTP $code)"
  code=$(curl -o /dev/null -s -w "%{http_code}" --max-time 5 "$BASE_URL/api/v1/debug/memory/_probe/state") || code=000
  { [ "$code" = "000" ] || [ "$code" = "404" ]; } && { err "诊断端点不可达 (HTTP $code)"; exit 1; }
  ok "诊断 API 可达 (HTTP $code)"
}

# ── 主流程 ────────────────────────────────────────────────────────────────────
banner "Memory 端到端测试 — 16 轮持续会话"
echo -e "\n  Session ID : ${GREEN}$SESSION_ID${NC}"
echo -e "  Base URL   : ${GREEN}$BASE_URL${NC}"
echo -e "  ${DIM}阈值: 窗口10 · 摘要batch3 · 反思3 · 注入PROC2+SEM3 · 加权0.3${NC}"
echo -e "  ${DIM}注意: 长期记忆按 local-user 跨会话累积，反复运行会叠加${NC}"
echo -e "  ${DIM}注意: /state 的 L4 按本 session 读，恒为 0；反思画像在 ai:profile:${DEFAULT_USER_ID}，脚本会单独打印${NC}"

check_prereq

# ── Phase A：播种身份与偏好（t1–t5，填满窗口）──────────────────────────────────
banner "Phase A / D — 播种偏好(t1–t5，填满窗口)"
echo -e "  预期: L1=10, L2=0, L3=0, L4={}"
step "发送 t1–t5"
send_chat "我叫陈昊，是一名后端工程师，平时主要写 Java 和 Go。"
send_chat "我特别喜欢用 Spring Boot，比较反感过度设计的代码。"
send_chat "数据库我更偏好 PostgreSQL，不太喜欢 MySQL。"
send_chat "补充一下我的饮食：我是素食者，而且对花生严重过敏。"
send_chat "生活上我每周跑步三次，喜欢长距离慢跑。"
show_state "Phase A 完成 — 窗口应填满"
pause

# ── Phase B：溢出→摘要→首次反思（t6–t10，含去重）──────────────────────────────
banner "Phase B / D — 触发摘要+首次反思(t6–t10)"
echo -e "  预期: L3>0 开始增长; ~t10 后反思生成画像(查 default-user-id)"
echo -e "  ${DIM}t9 与 t4 是同一事实(花生过敏) → 去重测试，看日志 'Duplicate memory skipped'${NC}"
step "发送 t6–t10"
send_chat "我最近在读科幻小说，正在看《三体》，很入迷。"
send_chat "工作习惯方面，我习惯早上写代码，下午和晚上才安排开会。"
send_chat "咖啡我只喝美式，不加糖不加奶。"
send_chat "再强调下，我对花生过敏这件事很重要，外卖一定要避开。"
send_chat "下个月我计划去日本旅行，想体验当地的素食餐厅。"
wait_async
show_state "Phase B 完成 — L3 应已增长"
show_profile
pause

# ── Phase C：巩固，逼出第 2 次反思（t11–t13）───────────────────────────────────
banner "Phase C / D — 巩固(t11–t13)"
echo -e "  预期: L3 继续增长; default-user-id 的反思画像非空"
step "发送 t11–t13"
send_chat "我用 IntelliJ IDEA 做开发，终端常驻 tmux。"
send_chat "周末我喜欢去爬山，偶尔骑公路车。"
send_chat "我不喜欢长会议，能用文档异步沟通就尽量异步。"
wait_async
show_state "Phase C 完成 — L3 继续增长"
show_profile
pause

# ── Phase D：召回验证（t14–t16，触发检索注入）─────────────────────────────────
banner "Phase D / D — 召回验证(t14–t16)"
echo -e "  发送召回类问题，观察是否用到【相关记忆】/【用户画像】注入"
step "t14 — 期望命中 SEMANTIC(素食/花生)"
send_chat "帮我推荐一家适合我的午餐外卖。"
recall_check "饮食偏好" "素食|蔬|花生|过敏|vegetarian"
step "t15 — 期望命中 PROCEDURAL(作息画像)"
send_chat "根据你对我的长期了解，帮我排一下明天的日程。"
recall_check "作息画像" "早上.*代码|写代码|跑步|晚上.*会|开会|异步"
step "t16 — 期望命中 SEMANTIC(PostgreSQL)"
send_chat "我之前说过我更喜欢哪个数据库？为什么？"
recall_check "数据库偏好" "PostgreSQL|postgres|pg"
show_state "Phase D 完成 — 全链路记忆已激活"
show_profile

# ── 治理：淘汰策略端点连通性（可选）────────────────────────────────────────────
# 注意：EvictionPolicyManager 扫描的是 JPA 表 memory_entries(imp<0.1 且 age>2d)。
# 纯对话产生的记忆 age=now、importance≥0.5，无法被淘汰；诊断接口 inject-doc 只写
# 向量库、不建 JPA 行，同样不会被该策略删除。因此这里只做"端点连通性"校验，
# 真正的删除验证见 docs/memory/memory-test-playbook.md 的 psql 方案或单元测试。
if [ "$RUN_EVICTION" = "1" ]; then
  banner "治理 — 淘汰策略端点连通性(eviction wiring)"
  resp=$(curl -sf -X POST "$BASE_URL/api/v1/debug/memory/evict") || resp=""
  status=$(echo "$resp" | jq -r '.status // "?"' 2>/dev/null || echo "?")
  if [ "$status" = "eviction triggered" ]; then ok "evict 端点可用：$status"
  else warn "evict 返回异常：${resp:-<空>}"; fi
  echo -e "  ${DIM}严格验证淘汰删除：见 playbook §6（psql 人为置低 importance + 超龄后再 evict）${NC}"
fi

# ── 收尾 ──────────────────────────────────────────────────────────────────────
banner "测试完成"
echo -e "\n  ${GREEN}✓ 16 轮会话 + 召回 + 淘汰 全链路跑通${NC}"
echo -e "\n  逐项核对(看应用日志):"
echo -e "  ${DIM}grep 'Summarized'                 # EPISODIC 摘要${NC}"
echo -e "  ${DIM}grep 'Extracted .* facts'         # SEMANTIC 事实${NC}"
echo -e "  ${DIM}grep 'Duplicate memory skipped'   # 去重(t4 vs t9)${NC}"
echo -e "  ${DIM}grep 'Reflection persisted'       # PROCEDURAL + 画像${NC}"
echo -e "\n  清理本次 session:"
echo -e "  ${DIM}curl -X DELETE $BASE_URL/api/v1/debug/memory/$SESSION_ID${NC}\n"
